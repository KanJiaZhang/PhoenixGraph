// needs: service
// what: the document-ingestion case of docs/AGENT-INGESTION-CASE.md with a real write (apply=true): a
//       seven-sentence postmortem becomes a graph (9 entities / 5 relations / 2 merges), the artifact
//       answers the recall tools, the graph answers the expansion tools, and /merge accepts the
//       near-duplicate the deduper left for a human. Needs the Aerospike write path (the running
//       service's docker): without it the ingestion is refused and this check reports that instead of
//       a false failure.
const API = 'http://localhost:8081';
// a fresh name per run keeps each run's graph self-contained (re-ingesting after a merge now resurrects
// the folded-away entity, so a fixed name would also work - this just leaves no residue)
const GRAPH = 'case-postmortem-' + Date.now();
const DOCUMENT = 'The payment-service depends on Redis.\nDeploy 42 caused the checkout-api outage.\n'
  + 'Alice fixed the cache leak.\nPaymentService depends on the database.\n'
  + 'The payment service depends on Redis.\npayments-service depends on Redis.\n'
  + 'The outage was caused by Deploy 42.';
const DICTIONARY = { 'payment-service': 'Service', 'PaymentService': 'Service', 'payment service': 'Service',
  'payments-service': 'Service', 'Redis': 'Service', 'database': 'Service', 'checkout-api': 'Service',
  'Deploy 42': 'Deploy', 'Alice': 'Person', 'cache leak': 'Incident', 'outage': 'Incident' };

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
  return { status: response.status, text, json };
};
const get = async (path) => {
  const response = await fetch(API + path);
  return { status: response.status, json: await response.json().catch(() => ({})) };
};

// ---- 1. ingestion (a real write) -----------------------------------------------------------------------
console.log('--- 1. ingestion');
const ingest = await post('/api/v1/agent/ingest', {
  graph: GRAPH, apply: true, replace: true, rulePack: 'incident', dictionary: DICTIONARY,
  documents: [{ id: 'postmortem', title: 'Checkout latency postmortem', text: DOCUMENT }],
});
if (ingest.status !== 200 || !ingest.json.applied) {
  const problem = ingest.json?.error || ingest.text || 'the write path is not available';
  if (/Aerospike|write path|store|forGraph/i.test(problem)) {
    console.log(`skipped: the ingestion was refused (${problem.slice(0, 120)}) - this check needs Aerospike`);
    process.exit(0);
  }
  check('the ingestion writes the graph', false, `http ${ingest.status} ${problem.slice(0, 120)}`);
  process.exit(1);
}
const quality = ingest.json.quality || {};
check('it extracts nine entities and five relations', ingest.json.entities === 9 && ingest.json.relations === 5,
  `${ingest.json.entities} entities / ${ingest.json.relations} relations`);
check('it writes vertices and edges through the ordinary write path',
  ingest.json.vertices === 10 && ingest.json.edges === 14, `${ingest.json.vertices} vertices / ${ingest.json.edges} edges`);
check('the three spellings of one service merge, with the variants kept as aliases',
  quality.duplicatesRemoved === 2
  && (quality.mergedEntities || []).some((m) => m.canonical === 'payment service'
    && m.aliases.includes('payment-service') && m.aliases.includes('PaymentService')),
  JSON.stringify(quality.mergedEntities));
check('the one-edit near-duplicate is listed for review, not merged',
  (quality.mergeCandidates || []).some((c) => c.right === 'payments-service' && c.score === 0.8),
  JSON.stringify(quality.mergeCandidates));
check('every relation cites a sentence that contains both of its ends',
  quality.provenanceRate === 1, `provenanceRate=${quality.provenanceRate}`);

// ---- 2. read the artifact ------------------------------------------------------------------------------
console.log('--- 2. the artifact');
const index = await get(`/api/v1/agent/index/${GRAPH}`);
const payment = index.json.entities?.find((e) => e.id === 'payment service');
check('an entity carries its context card, its aliases and its citation',
  !!payment && payment.type === 'Service' && payment.aliases.includes('PaymentService')
  && Array.isArray(payment.cites) && payment.cites.length > 0,
  JSON.stringify(payment).slice(0, 160));

// ---- 3. recall (the artifact, not the graph) -----------------------------------------------------------
console.log('--- 3. recall');
const found = await post('/api/v1/agent/find-entities', { graph: GRAPH, question: 'which service caused the outage', k: 3 });
const outage = found.json.entities?.find((e) => e.id === 'outage');
check('a name match finds the entity the question names, and says how',
  !!outage && outage.via === 'name' && outage.score > 0.5, JSON.stringify(outage));
const searched = await post('/api/v1/agent/search', { graph: GRAPH, question: 'payment-service', k: 3 });
check('hybrid search finds the canonical entity',
  searched.json.entities?.some((e) => e.id === 'payment service'), JSON.stringify(searched.json.entities?.map((e) => e.id)));

// ---- 4. expansion (the graph) --------------------------------------------------------------------------
console.log('--- 4. expansion');
const related = await post('/api/v1/agent/related', { graph: GRAPH, entity: 'payment service' });
check('a one-hop walk finds the dependency and its sentence',
  related.json.relations?.some((r) => r.head === 'payment service' && r.relation === 'depends_on'
    && r.tail === 'Redis' && r.sentence.includes('depends on')),
  JSON.stringify(related.json.relations?.map((r) => `${r.head} ${r.relation} ${r.tail}`)));
const context = await post('/api/v1/agent/context', { graph: GRAPH, question: 'what caused the outage' });
check('context returns the cause as a relation with its sentence',
  context.json.relations?.some((r) => r.head === 'Deploy 42' && r.relation === 'caused' && r.tail === 'outage'),
  JSON.stringify(context.json.relations?.map((r) => `${r.head} ${r.relation} ${r.tail}`)));

// ---- 5. the reviewer's merge ----------------------------------------------------------------------------
console.log('--- 5. merge');
const merged = await post('/api/v1/agent/merge', { graph: GRAPH, keep: 'payment service', drop: 'payments-service' });
check('the merge rewrites the two edges and reports what it did',
  merged.status === 200 && merged.json.edgesRewritten === 2, JSON.stringify(merged.json));
const after = await get(`/api/v1/agent/index/${GRAPH}`);
const canonical = after.json.entities?.find((e) => e.id === 'payment service');
check('after the merge the dropped name is an alias of the canonical',
  !!canonical && canonical.aliases.includes('payments-service'), JSON.stringify(canonical?.aliases));
check('and the dropped entity is gone',
  !after.json.entities?.some((e) => e.id === 'payments-service'), 'payments-service removed');

console.log('');
console.log(failures === 0
  ? 'the ingestion case holds end to end: a postmortem becomes an auditable graph, the tools answer with citations, and the reviewer merge sticks'
  : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
