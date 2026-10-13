// needs: service
// what: the SLOs of docs/SLO.md, measured - the four query classes end to end, plus two checks that keep the
//       document and the alert rules honest (every metric a rule names exists in the live exposition, and the
//       thresholds in the rules match the document)
//
// The measurement is interleaved (A,B,C,D,A,B,C,D...) rather than one class at a time: a machine that gets
// busy halfway through then affects all four classes instead of making the last one look slow. That mistake
// was made in this repository before, and it produced a number nobody could explain.
import { readFileSync } from 'node:fs';

const API = 'http://localhost:8081';
let failures = 0;
const check = (name, ok, detail) => {
  if (!ok) failures += 1;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`);
};

/** The query classes, with the objective each one is held to (docs/SLO.md §1). */
const CLASSES = [
  { id: 'S1', what: 'keyed point query', graph: 'payment-graph',
    query: "g.V(1000,'Account')", p99: 40 },
  { id: 'S2', what: 'one hop', graph: 'payment-graph',
    query: "g.V(135,'Account').bothE('Payment').limit(50).otherV()", p99: 60 },
  { id: 'S3', what: 'two hops', graph: 'payment-graph',
    query: "g.V(135,'Account').both('Payment').both('Payment').dedup().limit(200)", p99: 150 },
  { id: 'S4', what: 'class aggregate (8,000 rows)', graph: 'tpc-h-data',
    query: "g.V('Sale').group().by('orderYear').by(values('revenue').sum())", p99: 500 },
];
const ROUNDS = Number(process.env.SLO_ROUNDS || 40);

const post = async (path, body) => {
  const started = process.hrtime.bigint();
  const response = await fetch(API + path, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });
  const text = await response.text();
  const millis = Number(process.hrtime.bigint() - started) / 1e6;
  return { status: response.status, millis, text };
};
const percentile = (sorted, fraction) => sorted[Math.min(sorted.length - 1,
  Math.max(0, Math.ceil(fraction * sorted.length) - 1))];
const round = (value) => Math.round(value * 100) / 100;

// ---- 1. measure, interleaved ---------------------------------------------------------------------------
const samples = new Map(CLASSES.map((entry) => [entry.id, []]));
const errors = [];
for (let round_ = 0; round_ < ROUNDS; round_ += 1) {
  for (const entry of CLASSES) {
    const answer = await post('/api/v1/query', {
      graphName: entry.graph, query: entry.query, includeResult: true,
    });
    if (answer.status !== 200) {
      errors.push(`${entry.id} -> ${answer.status} ${answer.text.slice(0, 80)}`);
      continue;
    }
    samples.get(entry.id).push(answer.millis);
  }
}

// warm-up: the first call of each class pays for a compiled query and a cold page, so it is reported and then
// excluded - an SLO is about steady state, and saying so is more honest than a p99 that depends on the first
// request (the exclusion is one sample out of ROUNDS, and it applies to every class equally)
console.log(`${ROUNDS} rounds per class, interleaved`);
for (const entry of CLASSES) {
  const times = samples.get(entry.id).slice(1).sort((a, b) => a - b);
  if (!times.length) {
    check(`${entry.id} ${entry.what} answers`, false, 'no successful sample');
    continue;
  }
  const p50 = percentile(times, 0.5);
  const p95 = percentile(times, 0.95);
  const p99 = percentile(times, 0.99);
  const detail = `p50 ${round(p50)} ms, p95 ${round(p95)} ms, p99 ${round(p99)} ms (objective p99 < ${entry.p99} ms)`;
  check(`${entry.id} ${entry.what}: ${detail}`, p99 <= entry.p99);
}
check('no query failed during the measurement (S5: error rate 0%)', errors.length === 0,
  errors.slice(0, 2).join(' | '));

// ---- 2. the exposition a rule depends on ---------------------------------------------------------------
const exposition = await fetch(`${API}/api/v1/metrics/prometheus`).then((response) => response.text());
const metrics = new Set([...exposition.matchAll(/^([a-zA-Z_:][a-zA-Z0-9_:]*)/gm)].map((match) => match[1]));
check('the exposition carries the latency, error-rate and gate metrics the objectives use',
  ['phoenixgraph_query_latency_language_gremlin_p99_millis', 'phoenixgraph_query_failed_language_gremlin_source_http_total',
    'phoenixgraph_query_count_language_gremlin_source_http_total', 'phoenixgraph_requests_refused_total',
    'phoenixgraph_requests_admitted_total', 'phoenixgraph_storage_edge_read_failed_total',
    'phoenixgraph_storage_vertex_write_failed_total', 'phoenixgraph_storage_kv_write_failed_total']
    .every((name) => metrics.has(name)),
  [...metrics].filter((name) => name.startsWith('phoenixgraph_')).length + ' phoenixgraph_* metrics');

// ---- 3. the rules and the document agree ---------------------------------------------------------------
const rules = readFileSync('deploy/prometheus/phoenixgraph-rules.yml', 'utf8');
const slo = readFileSync('docs/SLO.md', 'utf8');
const ruleNames = [...rules.matchAll(/-\s*alert:\s*(\S+)/g)].map((match) => match[1]);

check('every alert has a severity, a for, and a runbook pointer',
  ruleNames.length >= 6 && ruleNames.every((name) => {
    const block = rules.slice(rules.indexOf('- alert: ' + name));
    const end = block.indexOf('- alert: ', 10);
    const body = end < 0 ? block : block.slice(0, end);
    return /severity:/.test(body) && /for:/.test(body) && /runbook:/.test(body);
  }), ruleNames.join(', '));

check('the document lists every alert the rules define',
  ruleNames.every((name) => slo.includes(name)),
  ruleNames.filter((name) => !slo.includes(name)).join(', ') || 'all present');

const usedMetrics = [...new Set([...rules.matchAll(/phoenixgraph_[a-z0-9_]+/g)].map((match) => match[0]))];
const unknown = usedMetrics.filter((name) => !metrics.has(name));
check('every metric an alert names exists in the live exposition', unknown.length === 0,
  unknown.length ? 'unknown: ' + unknown.join(', ') : usedMetrics.length + ' references, all real');

// Every threshold in the rules is one the document states, and vice versa: a rule nobody can trace back to a
// sentence is a rule somebody deletes at 3am. Only the millisecond thresholds are compared - the gate and
// storage rules threshold at zero, which is not a latency claim.
const ruleThresholds = [...rules.matchAll(/> ([0-9]+)/g)].map((match) => Number(match[1])).filter((ms) => ms >= 10);
for (const threshold of new Set(ruleThresholds)) {
  check(`the rule threshold ${threshold} ms is stated in the document`,
    slo.includes('< ' + threshold + ' ms'), threshold + ' ms');
}
for (const entry of CLASSES) {
  check(`${entry.id} threshold ${entry.p99} ms is stated in the document`,
    slo.includes('< ' + entry.p99 + ' ms'), entry.p99 + ' ms');
}
check('the document says which numbers are measured and which are declared',
  /Measured \(demo instance/.test(slo) && /not measured/.test(slo));
check('the document says the numbers are end to end', /end to end/.test(slo));

console.log(failures
  ? `\n${failures} FAILURES`
  : 'the four query classes meet their objectives, and the SLO document, the alert rules and the live exposition agree');
process.exit(failures ? 1 : 0);
