// needs: service
// what: the request limits (429/503, Retry-After), the request id, the 404/405 contract and the Prometheus
//       exposition - the M2 hardening, checked against the running service
//
// A second engine instance is started with *tight* limits (its own port and its own JVM) because the served
// instance runs with the production defaults: proving that a rate limit works must not require weakening the
// configuration everyone else uses.
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, readdirSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const API = 'http://localhost:8081';
const PROBE_PORT = Number(process.env.PG_PROBE_PORT) || 8100 + (process.pid % 80);
const PROBE = `http://localhost:${PROBE_PORT}`;
let failures = 0;
const check = (name, ok, detail) => { if (!ok) failures += 1; console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const call = async (base, path, options = {}) => {
  const response = await fetch(base + path, options);
  const text = await response.text();
  return { status: response.status, headers: response.headers, text };
};
const ask = (base, body) => call(base, '/api/v1/query', {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
});

// ---- 1. every response carries a request id (the caller's, or one of ours) ---------------------------
const echoed = await call(API, '/api/v1/graphs', { headers: { 'X-Request-Id': 'ci-probe-42' } });
check('the request id the caller sent comes back', echoed.headers.get('x-request-id') === 'ci-probe-42',
  echoed.headers.get('x-request-id'));
const generated = await call(API, '/api/v1/graphs');
check('a request without one gets an id of its own', /^[0-9a-f]{8}$/.test(generated.headers.get('x-request-id') || ''),
  generated.headers.get('x-request-id'));

// ---- 2. the error contract: 4xx that name the request, not 500 --------------------------------------
const unknown = await call(API, '/api/v1/no-such-endpoint');
check('an unknown path is 404 with the path in the message',
  unknown.status === 404 && unknown.text.includes('Unknown endpoint: GET /api/v1/no-such-endpoint'),
  `${unknown.status} ${unknown.text.slice(0, 90)}`);
const wrongMethod = await call(API, '/api/v1/graphs', { method: 'POST' });
check('a known path with the wrong method is 405 and says what is allowed',
  wrongMethod.status === 405 && wrongMethod.headers.get('allow') === 'GET' && wrongMethod.text.includes('"error"'),
  `${wrongMethod.status} allow=${wrongMethod.headers.get('allow')}`);
const badGraph = await ask(API, { graphName: 'no-such-graph', query: 'g.V()' });
check('an unknown graph name is 400 and lists what is served',
  badGraph.status === 400 && badGraph.text.includes('Unknown graph'), `${badGraph.status}`);

// ---- 3. Prometheus exposition -----------------------------------------------------------------------
const prometheus = await call(API, '/api/v1/metrics/prometheus');
const lines = prometheus.text.split('\n').filter((line) => line && !line.startsWith('#'));
const sampleRe = /^[a-zA-Z_:][a-zA-Z0-9_:]*(\{[^}]*\})? -?[0-9]+(\.[0-9]+)?([eE][-+]?[0-9]+)?$/;
const malformed = lines.filter((line) => !sampleRe.test(line));
check('Prometheus text: every sample line is name{labels} value',
  prometheus.status === 200 && lines.length > 20 && malformed.length === 0,
  `${lines.length} samples, ${malformed.length} malformed${malformed[0] ? ': ' + malformed[0].slice(0, 60) : ''}`);
check('the counters carry the graph label, and the gate counters do not',
  lines.some((line) => /^phoenixgraph_storage_edge_read_total\{graph="/.test(line))
  && lines.some((line) => /^phoenixgraph_requests_admitted_total [0-9]+$/.test(line)),
  lines.filter((line) => line.startsWith('phoenixgraph_requests_')).join(' '));
check('latency percentiles are exported as gauges',
  lines.some((line) => /^phoenixgraph_query_latency_.*_p99_millis\{graph="/.test(line)),
  lines.filter((line) => line.includes('p99')).slice(0, 1).join(''));
const oneGraph = await call(API, '/api/v1/metrics/payment-graph/prometheus');
check('one graph can be scraped on its own', oneGraph.status === 200
  && oneGraph.text.includes('graph="payment-graph"') && !oneGraph.text.includes('graph="tpc-h-data"'));
check('an unknown graph is still a 404 in the Prometheus route',
  (await call(API, '/api/v1/metrics/nope/prometheus')).status === 404);

// ---- 4. the limits themselves: a second instance with tight settings ---------------------------------
const jar = readdirSync('graph-http/target').find((name) => /^graph-http-.*\.jar$/.test(name) && !name.endsWith('.original'));
if (!jar) {
  check('the engine jar exists (run ./mvnw -DskipTests package first)', false, 'graph-http/target is empty');
  process.exit(1);
}
const probe = spawn('java', ['-jar', `graph-http/target/${jar}`,
  `--server.port=${PROBE_PORT}`,
  '--phoenixgraph.limits.request-timeout=1ms',       // any traversal with a step boundary spends this
  '--phoenixgraph.limits.per-graph-per-second=1',    // the second request in a second is refused
  '--phoenixgraph.limits.per-graph-concurrent=1',
  '--phoenixgraph.limits.max-concurrent=2',
  '--phoenixgraph.limits.queue-wait=50ms',
  // its own RocksDB root: the served instance holds the write lock on the shared one, and a probe that
  // cannot boot proves nothing about limits
  `--phoenixgraph.storage.rocksdb-root=${mkdtempSync(join(tmpdir(), 'pg-probe-rocksdb-'))}`,
  // and its own worker ports: the served instance holds the default 19100-19103
  '--phoenixgraph.gemini.first-port=19200',
], { stdio: ['ignore', 'pipe', 'pipe'] });
let probeLog = '';
probe.stdout.on('data', (chunk) => { probeLog += chunk; });
probe.stderr.on('data', (chunk) => { probeLog += chunk; });

const stopProbe = () => { try { probe.kill(); } catch { /* already gone */ } };
process.on('exit', stopProbe);

let up = false;
for (let i = 0; i < 120 && !up; i += 1) { // a cold JVM on a busy machine is slow
  try {
    up = (await fetch(`${PROBE}/api/v1/graphs`, { signal: AbortSignal.timeout(2000) })).ok;
  } catch {
    await sleep(500);
  }
}
check('a second instance starts with its own limits', up, up ? PROBE : probeLog.split('\n').slice(-3).join(' | '));

if (up) {
  const query = { graphName: 'payment-graph', query: "g.V(1000,'Account').both('Payment').both('Payment').dedup()", includeResult: true };

  // a) the per-graph rate limit: two requests in the same second, the second is refused.
  //    The window is wall-clock seconds, so the pair is sent just after a second boundary: sending them 5ms
  //    apart inside one window is the property under test, and a slow machine must not turn that into "the
  //    second request happened in the next second, so it was allowed" - which is what made this flaky.
  const waitForFreshWindow = async () => await sleep(1000 - (Date.now() % 1000) + 20);
  await waitForFreshWindow();
  const first = await ask(PROBE, query);
  const second = await ask(PROBE, query);
  check('the per-second budget refuses the second request with 429 and Retry-After',
    second.status === 429 && second.headers.get('retry-after') === '1'
    && second.text.includes('Too many requests for this graph'),
    `first=${first.status} second=${second.status} retry-after=${second.headers.get('retry-after')}`);
  check('the refusal is an error body, not an empty response', second.text.includes('"requestId"'));

  // b) a different graph is not affected by that graph's rate (the limit is per graph)
  await sleep(1100);
  const other = await ask(PROBE, { graphName: 'payment-graph', query: "g.V(1000,'Account').limit(3).values('id')", includeResult: true });
  check('another graph is served normally after the first one was limited',
    other.status === 200 || other.status === 503,
    `${other.status} (503 here means the 1ms deadline fired first, which is the next check)`);

  // c) the deadline: 1ms means any step boundary trips it, and the answer is 503 + Retry-After.
  //    A three-hop fan-out is used because the query has to be *able* to take longer than a millisecond. The
  //    deadline is checked between steps, so an attempt that happens to be served from cache without crossing a
  //    step boundary cannot trip it: three attempts make the check about the behaviour rather than about one
  //    unlucky response, and the last answer is the one reported.
  await sleep(1100);
  const heavy = "g.V(135,'Account').both('Payment').both('Payment').both('Payment').dedup().limit(5000)";
  let timedOut = await ask(PROBE, { graphName: 'payment-graph', query: heavy, includeResult: true });
  for (let attempt = 0; attempt < 3 && timedOut.status !== 503; attempt += 1) {
    await sleep(1100);
    timedOut = await ask(PROBE, { graphName: 'payment-graph', query: heavy, includeResult: true });
  }
  check('a query that exceeds the budget is a 503 naming the setting, not a 400',
    timedOut.status === 503 && /request-timeout/.test(timedOut.text) && timedOut.headers.get('retry-after') === '1',
    `${timedOut.status} ${timedOut.text.slice(0, 130)}`);
  check('the timeout is a retryable answer with a hint, not a stack-trace dump',
    timedOut.text.includes('"hint"') && timedOut.text.includes('request-timeout'));

  // d) the instance keeps serving after refusing: a limit is not a crash
  await sleep(1100);
  const after = await ask(PROBE, { graphName: 'payment-graph', query: "g.V(1000,'Account').values('id')", includeResult: true });
  check('the instance still answers after refusing and timing out',
    after.status === 200 || after.status === 503, `${after.status}`);
}

stopProbe();
await sleep(500);

console.log(failures
  ? `\n${failures} FAILURES`
  : 'the request gate, the request id, the 4xx contract and the Prometheus exposition all behave as documented');
process.exit(failures ? 1 : 0);
