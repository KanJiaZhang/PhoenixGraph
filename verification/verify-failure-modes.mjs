// needs: service + the engine jar (a second instance is started per case)
// what: how the API behaves when the *deployment* is broken rather than the request. A store that is not there
//       at all, a store that accepts the connection and then never answers, a body over the size limit, a query
//       over the length limit, and 8 clients creating the same vertex at once.
//
// Why this is its own check: these are the failures an operator meets at 3am, and each one has a wrong answer
// that looks reasonable. A node that refuses to start because it cannot reach the store turns a degraded
// deployment into an outage; a 500 for "the store did not answer" tells a retry policy to give up; a request
// that hangs on a stalling store occupies a slot until the instance is useless; a body that is buffered anyway
// is how the filter that protects the instance becomes the reason it runs out of heap.
//
// The two store cases run against their own instance (own port, own RocksDB root) so the served one keeps the
// defaults and this check can prove the failure modes without weakening the configuration everyone else uses.
//
// What is *not* here: recovery after the store returns. That needs a store to come back, which is what
// verification/verify-aerospike-ha.mjs does with a real two-node cluster (kill a node, watch it rejoin).
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { mkdtempSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const API = 'http://localhost:8081';
const GRAPH = 'payment-graph';
let failures = 0;
const check = (name, ok, detail) => {
  if (!ok) failures += 1;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`);
};
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const call = async (base, path, options = {}) => {
  const response = await fetch(base + path, options);
  return { status: response.status, headers: response.headers, text: await response.text() };
};
const ask = (base, body) => call(base, '/api/v1/query', {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
});

const jar = readdirSync('graph-http/target')
  .find((name) => /^graph-http-.*\.jar$/.test(name) && !name.endsWith('.original'));
if (!jar) {
  check('the engine jar exists (run ./mvnw -DskipTests package first)', false, 'graph-http/target is empty');
  process.exit(1);
}

/** Starts an engine instance and waits for its health endpoint; returns a handle. */
const startEngine = async (label, extraArgs, timeoutSeconds = 90) => {
  const port = 8200 + (process.pid % 60) + Math.floor(Math.random() * 20);
  const probe = `http://localhost:${port}`;
  const child = spawn('java', ['-jar', `graph-http/target/${jar}`,
    `--server.port=${port}`,
    `--phoenixgraph.storage.rocksdb-root=${mkdtempSync(join(tmpdir(), 'pg-fail-rocksdb-'))}`,
    `--phoenixgraph.gemini.first-port=${19400 + (port % 100)}`,
    ...extraArgs], { stdio: ['ignore', 'pipe', 'pipe'] });
  let log = '';
  child.stdout.on('data', (chunk) => { log += chunk; });
  child.stderr.on('data', (chunk) => { log += chunk; });
  const started = Date.now();
  for (let i = 0; i < timeoutSeconds * 2; i += 1) {
    try {
      if ((await fetch(`${probe}/api/v1/health`, { signal: AbortSignal.timeout(2000) })).ok) {
        return { label, probe, child, log: () => log, started: true, seconds: (Date.now() - started) / 1000,
          stop: () => { try { child.kill(); } catch { /* already gone */ } } };
      }
    } catch {
      await sleep(500);
    }
  }
  return { label, probe, child, log: () => log, started: false, seconds: timeoutSeconds,
    stop: () => { try { child.kill(); } catch { /* already gone */ } } };
};

// ---- 1. a store that is not there at all ---------------------------------------------------------------
console.log('--- the store does not answer');
// 3399 nothing listens: this is the "the database host is wrong, or the store is down" case
const dead = await startEngine('dead store', ['--phoenixgraph.storage.host=127.0.0.1',
  '--phoenixgraph.storage.port=3399'], 60);
process.on('exit', dead.stop);
check('an instance whose store is unreachable still starts (a degraded node is not an outage)',
  dead.started, dead.started ? `up in ${dead.seconds.toFixed(1)}s` : dead.log().split('\n').slice(-3).join(' | '));
if (dead.started) {
  check('its health endpoint answers: "the process is alive" is a different question from "the store answers"',
    (await call(dead.probe, '/api/v1/health')).status === 200);
  check('its graph list answers from configuration',
    (await call(dead.probe, '/api/v1/graphs')).status === 200);

  const read = await ask(dead.probe, { graphName: GRAPH, query: "g.V(1000,'Account')", includeResult: true });
  check('a read against the unreachable store is 503, not 500 (it is retryable, not a bug)',
    read.status === 503, `${read.status} ${read.text.slice(0, 110)}`);
  check('and it says when to retry', read.headers.get('retry-after') === '1',
    `Retry-After=${read.headers.get('retry-after')}`);
  check('and the hint says the instance is alive and points at the runbook',
    read.text.includes('retry') && read.text.includes('health'), read.text.slice(0, 140));
  check('a 503 body carries no stack trace',
    !read.text.includes('stackTrace'), read.text.slice(0, 80));

  const write = await call(dead.probe, '/api/v1/vertices', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: 'zz-fail-1', vertexSchemaName: 'Account', properties: [{ index: 1, value: 'ACTIVE' }] }),
  });
  check('a write against the unreachable store does not claim SUCCESS',
    !write.text.includes('"status":"SUCCESS"'), `${write.status} ${write.text.slice(0, 90)}`);

  // the availability point of the whole design: the graphs that do *not* need that store keep working
  const rocks = await ask(dead.probe, { graphName: 'sanguo-kg-rocksdb-edge',
    query: "g.V('Entity').limit(1).count()", includeResult: true });
  check('a RocksDB graph on the same instance still answers (one broken store is not a broken node)',
    rocks.status === 200, `${rocks.status} ${rocks.text.slice(0, 80)}`);
  const metrics = await call(dead.probe, '/api/v1/metrics/prometheus');
  check('and the metrics endpoint still answers, so the node can be scraped',
    metrics.status === 200 && metrics.text.includes('phoenixgraph_'), `${metrics.status}`);
  check('the store failure is visible in the log, not only in the response',
    /Storage unavailable|AerospikeException|Connection refused/.test(dead.log()),
    (dead.log().split('\n').find((line) => /Storage unavailable|AerospikeException|Connection refused/.test(line)) || '')
      .slice(0, 110));
}

// ---- 2. a store that accepts the connection and never answers -------------------------------------------
console.log('--- the store accepts the connection and stalls');
// A stalling store is worse than a dead one: the TCP handshake succeeds, so "connection refused" never fires,
// and without a bounded connect/socket timeout the client waits forever. The client policy sets one (1000 ms),
// and this case proves the instance still starts and still answers - the alternative is a node that hangs
// during boot and takes no traffic at all.
const stallPort = 8300 + (process.pid % 50);
const stalling = createServer((socket) => {
  // the client gives up mid-handshake, which resets the socket: without this listener Node treats the reset as
  // an unhandled error and kills the check (the store it is imitating would do the same, and a real store logs it)
  socket.on('error', () => { /* the store's connection died: exactly what is being simulated */ });
});
await new Promise((resolve) => stalling.listen(stallPort, '127.0.0.1', resolve));
const stalled = await startEngine('stalling store', ['--phoenixgraph.storage.host=127.0.0.1',
  `--phoenixgraph.storage.port=${stallPort}`], 60);
process.on('exit', () => { stalled.stop(); stalling.close(); });
check('an instance whose store only accepts and stalls also starts, within its socket timeout',
  stalled.started, stalled.started
    ? `up in ${stalled.seconds.toFixed(1)}s`
    : `did not start in ${stalled.seconds}s: ${stalled.log().split('\n').slice(-3).join(' | ')}`);
if (stalled.started) {
  const stalledRead = await ask(stalled.probe, { graphName: GRAPH, query: "g.V(1000,'Account')", includeResult: true });
  check('a read against the stalling store comes back as 503 rather than hanging',
    stalledRead.status === 503, `${stalledRead.status} ${stalledRead.text.slice(0, 90)}`);
}
stalled.stop();
stalling.close();

// ---- 3. the two request-size limits --------------------------------------------------------------------
console.log('--- request sizes');
const big = await call(API, '/api/v1/query', {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ graphName: GRAPH, query: "g.V(1000,'Account')", params: { pad: 'A'.repeat(2 * 1024 * 1024) } }),
});
check('a 2 MiB body is 413 and names the limit (it is never buffered)',
  big.status === 413 && big.text.includes('max-body-bytes'), `${big.status} ${big.text.slice(0, 120)}`);
check('the 413 body has the same shape as every other error',
  big.text.includes('"exception"') && big.text.includes('"hint"') && big.text.includes('"requestId"'),
  big.text.slice(0, 120));
const normal = await ask(API, { graphName: GRAPH, query: "g.V(1000,'Account').count()", includeResult: true });
check('a normal request still works after the oversized one (nothing was left half-read)',
  normal.status === 200 && normal.text.includes('[1]'), `${normal.status} ${normal.text.slice(0, 60)}`);
const longQuery = await ask(API, { graphName: GRAPH,
  query: "g.V(1000,'Account')" + '.limit(1)'.repeat(12000) });
check('a query of 108k characters is 400 and names the limit',
  longQuery.status === 400 && longQuery.text.includes('max-query-length'),
  `${longQuery.status} ${longQuery.text.slice(0, 120)}`);

// ---- 4. concurrent identical writes --------------------------------------------------------------------
console.log('--- 8 clients create the same vertex at once');
const contended = `zz-contended-${Date.now()}`;
const bodies = Array.from({ length: 8 }, (_, index) => call(API, '/api/v1/vertices', {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ id: contended, vertexSchemaName: 'Account',
    properties: [{ index: 1, value: 'ACTIVE' }, { index: 2, value: `client-${index}` }] }),
}));
const results = await Promise.all(bodies);
check('every concurrent create is accepted or reported stale - none is a 500',
  results.every((result) => result.status === 200), results.map((result) => result.status).join(','));
check('and the vertex ends up existing exactly once, with one of the written values',
  (await ask(API, { graphName: GRAPH, query: `g.V('${contended}','Account').count()`, includeResult: true }))
    .text.includes('[1]'),
  (await ask(API, { graphName: GRAPH, query: `g.V('${contended}','Account').count()`, includeResult: true }))
    .text.slice(0, 60));
await sleep(200);
const values = await ask(API, { graphName: GRAPH, query: `g.V('${contended}','Account').values('accountType')`,
  includeResult: true });
check('the stored value is one of the eight that were sent (last writer wins, nothing is interleaved)',
  /client-[0-7]/.test(values.text) && (values.text.match(/client-/g) || []).length === 1, values.text.slice(0, 90));
await call(API, `/api/v1/vertices/${contended}`, { method: 'DELETE' });

console.log('');
console.log(failures === 0
  ? 'a broken store degrades instead of crashing, the node keeps serving what it can, and the size limits hold'
  : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
