// needs: service
// what: the three AI-context cases of docs/AI-CONTEXT-CASES.md: (1) a multi-hop question whose answer is a
//       path with per-hop sentences, (2) cross-document entity resolution plus a coverage-based refusal, and
//       (3) a semantic-layer attribution whose contributions add up and whose ratio metric is refused with
//       the SQL. Case 1 reads the pre-loaded agent-demo graph; cases 2 and 3 ingest / read as they run.
const API = 'http://localhost:8081';
const SPEND_2025 = 14731149.38;

let failures = 0;
const check = (name, ok, detail) => {
  if (!ok) failures += 1;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`);
};
const post = async (path, body) => {
  const response = await fetch(API + path, {
    method: 'POST', headers: { 'Content-Type': 'application/json; charset=utf-8' }, body: JSON.stringify(body),
  });
  const text = await response.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* non-JSON bodies are a finding */ }
  return { status: response.status, json };
};
const close = (a, b, tol = 1) => Math.abs((a ?? 0) - b) <= tol;
const rel = (r) => (r.relations || []).some((x) => x.head === r.head && x.relation === r.relation && x.tail === r.tail);

// ---- 1. multi-hop: the answer is a path, and each hop carries its sentence ----------------------------
console.log('--- 1. a multi-hop question answers with a traceable path');
const multiHop = await post('/api/v1/agent/context', { graph: 'agent-demo', question: '关羽和诸葛亮什么关系' });
const sibling = (multiHop.json.relations || []).find((x) => x.head === '关羽' && x.relation === 'sibling_of' && x.tail === '刘备');
const strategist = (multiHop.json.relations || []).find((x) => x.head === '诸葛亮' && x.relation === 'strategist_of' && x.tail === '刘备');
check('the context carries the two edges the 2-hop answer is built from',
  !!sibling && !!strategist, JSON.stringify(multiHop.json.relations?.map((x) => `${x.head}-${x.relation}->${x.tail}`)));
check('each edge carries the source sentence, so every hop is traceable',
  !!sibling?.sentence?.includes('义弟') && !!strategist?.sentence?.includes('军师'),
  `${sibling?.sentence?.slice(0, 20)}… / ${strategist?.sentence?.slice(0, 20)}…`);

// ---- 2. cross-document resolution + a coverage-based refusal -------------------------------------------
console.log('--- 2. cross-document entities merge, and no evidence means refuse');
const GRAPH = 'case-outage-' + Date.now();
const ingest = await post('/api/v1/agent/ingest', {
  graph: GRAPH, apply: true, rulePack: 'incident',
  dictionary: { 'payment-service': 'Service', 'PaymentService': 'Service', 'payment service': 'Service',
    'Redis': 'Service', 'database': 'Service', 'checkout-api': 'Service', 'Deploy 42': 'Deploy',
    'Alice': 'Person', 'Bob': 'Person', 'cache leak': 'Incident', 'outage': 'Incident' },
  documents: [
    { id: 'checkout-outage', title: 'Checkout outage postmortem',
      text: 'The payment-service depends on Redis.\nDeploy 42 caused the checkout-api outage.\nAlice fixed the cache leak.' },
    { id: 'database-incident', title: 'Database incident',
      text: 'The payment service depends on the database.\nThe database failed during Deploy 42.\nThe outage was caused by Deploy 42.' },
    { id: 'redis-outage', title: 'Redis outage',
      text: 'PaymentService depends on Redis.\nRedis was down.\nBob reported the Redis outage.' },
  ],
});
if (ingest.status !== 200 || !ingest.json.applied) {
  const problem = ingest.json?.error || '';
  if (/Aerospike|write path|store|forGraph/i.test(problem)) {
    console.log(`skipped: ingestion refused (${problem.slice(0, 100)}) - this case needs the Aerospike write path`);
    process.exit(0);
  }
  check('the cross-document ingestion writes', false, `http ${ingest.status} ${problem.slice(0, 100)}`);
  process.exit(1);
}
const merged = (ingest.json.quality?.mergedEntities || []).find((m) => m.canonical === 'payment service');
check('the same service in three spellings across three documents merges into one entity',
  !!merged && merged.mentions === 3 && merged.aliases.includes('payment-service') && merged.aliases.includes('PaymentService'),
  JSON.stringify(merged));
const caused = await post('/api/v1/agent/context', { graph: GRAPH, question: 'what did Deploy 42 cause' });
check('a question the corpus answers returns the causal relation',
  (caused.json.relations || []).some((x) => x.head === 'Deploy 42' && x.relation === 'caused'),
  JSON.stringify(caused.json.relations?.map((x) => `${x.head}-${x.relation}->${x.tail}`)));
const approved = await post('/api/v1/agent/find-entities', { graph: GRAPH, question: 'who approved the deploy', k: 5 });
const named = (approved.json.entities || []).filter((e) => e.via === 'name');
const approvingRelation = (caused.json.relations || []).some((x) => /approv|authoriz|sign/.test(x.relation));
check('a question the corpus cannot answer has no name match and no approving relation',
  named.length === 0 && !approvingRelation,
  `name matches=${named.length}, approving relation=${approvingRelation}`);

// ---- 3. the semantic layer: attribution adds up, and a ratio metric is refused with the SQL -------------
console.log('--- 3. the semantic layer answers with a plan, and refuses a ratio');
const attributed = await post('/api/v1/analysis/demo-semantics/attribute',
  { question: '2024 到 2025 年各渠道花费为什么变化' });
check('the attribution decomposes and adds up',
  attributed.json?.attribution?.decompositionAddsUp === true
  && close(attributed.json.attribution.contributionsSum, attributed.json.attribution.delta, 1),
  JSON.stringify({ sum: attributed.json?.attribution?.contributionsSum, delta: attributed.json?.attribution?.delta }));
const cac = await post('/api/v1/semantic/demo-semantics/ask', { question: '2025 年各渠道获客成本' });
check('a ratio metric is refused with the warehouse SQL, not answered as a sum',
  !!cac.json?.executionRefused && /SUM\(marketing\.spend\) \/ SUM\(marketing\.new_customers\)/.test(cac.json?.sql || ''),
  String(cac.json?.executionRefused || '').slice(0, 60));

console.log('');
console.log(failures === 0
  ? 'the AI-context cases hold: a multi-hop path with per-hop sentences, cross-document entities that merge, a coverage-based refusal, and a semantic attribution that adds up'
  : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
