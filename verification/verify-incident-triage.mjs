// needs: service + aerospike
// what: the incident-triage case of examples/incident-triage: a corpus of four RCAs, a topology, hosts, metrics
//       and a change window is ingested, then three symptoms are run through the same ranking the example uses.
//       Asserts that the three spellings of one service merge and a one-edit near-duplicate is left for review;
//       that today's fault ranks the change window that touched redis-cache first, with the prior incident named
//       as a precedent; that a quiet-cache day ranks the checkout release instead; and that a symptom naming
//       nothing is refused rather than answered.
import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');
const API = 'http://localhost:8081';
const GRAPH = 'incident-triage';

let failures = 0;
const check = (name, ok, detail) => {
  if (!ok) failures += 1;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`);
};

const triage = (symptom, extra = []) => {
  const result = spawnSync(process.execPath,
    [join(REPO, 'examples/incident-triage/triage.mjs'), '--json', '--symptom', symptom, ...extra],
    { cwd: REPO, encoding: 'utf8' });
  if (result.status !== 0) {
    throw new Error(`triage exited ${result.status}: ${(result.stderr || result.stdout).slice(0, 300)}`);
  }
  return JSON.parse(result.stdout);
};

// ---- the corpus became a graph -------------------------------------------------------------------------
const FAULT = '2026-10-24 14:32 起，checkout-api 的 p99 从 180ms 涨到 4.2s，error-rate-5xx 到 2.3%。'
  + ' 同时 redis-cpu-percent 到 95%，redis-evictions-rate 高频抖动，redis-memory-used-percent 88%。';
const QUIET_CACHE = '2026-10-20 18:10 起 checkout-api 的 error-rate-5xx 从 0.2% 到 2.1%，'
  + 'checkout-p99-latency 到 1.9s；缓存与数据库侧无任何抖动。';
const UNRELATED = '支付网关的 TLS 证书是不是快过期了？';

console.log('--- the corpus becomes a graph, with the spellings resolved');
const first = triage(FAULT, ['--reingest']);
const quality = await (await fetch(`${API}/api/v1/agent/quality/${GRAPH}`)).json();
const merged = (quality.mergedEntities || []).find((entry) => entry.canonical === 'payment service');
check('the three spellings of payment-service merge into one entity',
  // mentions is every occurrence in the corpus (11 here: topology, hosts, two RCAs and the change window), not
  // three - the point is that the variants land on one node instead of three.
  !!merged && merged.mentions >= 3 && merged.aliases.includes('payment-service') && merged.aliases.includes('PaymentService'),
  JSON.stringify(merged));
check('the one-edit near-duplicate stays a review candidate, not a merge',
  (quality.mergeCandidates || []).some((pair) => pair.left === 'payment service' && pair.right === 'payments-service'),
  JSON.stringify((quality.mergeCandidates || []).find((pair) => pair.right === 'payments-service')));
check('every relation still carries the sentence it was read from', quality.provenanceRate === 1, `provenanceRate=${quality.provenanceRate}`);

// ---- scenario 1: the fault -----------------------------------------------------------------------------
console.log('--- 1. the fault ranks the change window that touched redis-cache');
const [top, runnerUp] = first.ranked;
check('deploy-48 is the most likely cause', top.cause === 'deploy-48', `${top.cause} (score ${top.score})`);
check('the cache failure mode is the link, and it acts on redis-cache',
  top.effect === 'cache-key-prefix-churn' && top.edges.some((edge) => edge.head === 'cache-key-prefix-churn' && edge.tail === 'redis-cache'),
  `${top.cause} -> ${top.effect}`);
check('the change window is evidence, not a guess', top.reasons.some((reason) => /change window/.test(reason)), top.reasons[0]);
check('the prior incident on the same component is named as a precedent',
  top.reasons.some((reason) => /INC-1042/.test(reason)), top.reasons.find((reason) => /INC-1042/.test(reason)));
check('the runner-up is a real alternative, ranked below', !!runnerUp && runnerUp.score < top.score,
  runnerUp ? `${runnerUp.cause} (${runnerUp.score})` : 'none');
check('the report says what would overturn it', (first.verdict.checks || []).length >= 2, JSON.stringify(first.verdict.checks));
check('the DNS incident is ruled out because nothing it touches shows a signal',
  first.ranked.every((candidate) => candidate.cause !== 'deploy-46' || candidate.score < 0),
  JSON.stringify(first.ranked.find((candidate) => candidate.cause === 'deploy-46')));
check('every edge quoted in the report has a sentence',
  first.ranked.every((candidate) => candidate.edges.every((edge) => typeof edge.sentence === 'string' && edge.sentence.length > 0)));

// ---- scenario 2: the same corpus, a different day -------------------------------------------------------
console.log('--- 2. a quiet cache points at the checkout release instead');
const quiet = triage(QUIET_CACHE);
check('deploy-47 leads when the cache side is quiet', quiet.ranked[0].cause === 'deploy-47',
  quiet.ranked.slice(0, 3).map((candidate) => `${candidate.cause} ${candidate.score}`).join(', '));
check('the cache change is demoted, not deleted', quiet.ranked.some((candidate) => candidate.cause === 'deploy-48'
  && candidate.score < quiet.ranked[0].score),
  JSON.stringify(quiet.ranked.find((candidate) => candidate.cause === 'deploy-48')));
check('the metrics the symptom did not name are not treated as observed',
  !Object.keys(quiet.observed).includes('redis-cache'), JSON.stringify(Object.keys(quiet.observed)));

// ---- scenario 3: a symptom the corpus cannot answer -----------------------------------------------------
console.log('--- 3. a symptom that names nothing is refused');
const unrelated = triage(UNRELATED);
check('no seed entity, and no cause is named', !!unrelated.refusal && unrelated.ranked.length === 0,
  unrelated.refusal || JSON.stringify(unrelated.ranked.slice(0, 1)));

// ---- the corpus can be ingested again without duplicating the graph --------------------------------------
// The edge id is derived from (head, relation, tail), so a second ingestion of the same documents upserts. It
// used to be `rel-<n>` - a position - which wrote a second set of edges over the same ids with different meanings.
console.log('--- 4. re-ingesting upserts instead of duplicating');
const { readFileSync, readdirSync } = await import('node:fs');
const DIR = join(REPO, 'examples/incident-triage');
// The corpus - documents AND their validity windows - comes from the example, so this ingest cannot un-version
// the graph the temporal assertions rest on. A second copy of the corpus here is a second corpus.
const { dictionary, documents } = await import('../examples/incident-triage/corpus.mjs');
const ingestBody = { graph: GRAPH, apply: true, rulePack: 'incident', dictionary: dictionary(), documents: documents() };
const countDeploy48Edges = async () => {
  const answer = await (await fetch(`${API}/api/v1/query`, {
    method: 'POST', headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ graphName: GRAPH, query: "g.V('deploy-48','Entity').outE('Relates').limit(50)", includeResult: true, returnSubgraph: true }),
  })).json();
  return (answer.subgraph?.edges || []).length;
};
const before = await countDeploy48Edges();
const again = await (await fetch(`${API}/api/v1/agent/ingest`, {
  method: 'POST', headers: { 'Content-Type': 'application/json; charset=utf-8' }, body: JSON.stringify(ingestBody),
})).json();
const after = await countDeploy48Edges();
check('the second ingestion writes', again.applied === true, String(again.applied));
check('deploy-48 still has its six edges, not twelve', before === 6 && after === 6, `${before} then ${after}`);

console.log('');
console.log(failures === 0
  ? 'the triage case holds: four RCAs become one graph, the spellings resolve, the change window outranks the '
    + 'alternatives on the evidence, another day ranks differently, an unknown symptom is refused, and '
    + 're-ingesting upserts instead of duplicating'
  : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
