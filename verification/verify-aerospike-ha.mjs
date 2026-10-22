// needs: service
// what: high availability (M3-1) - a real two-node Aerospike cluster with replication-factor 2, an engine on
//       top of it, and a killed node: reads, writes and answers have to survive it, and replication has to
//       catch up when the node returns
//
//   docker compose -f deploy/aerospike-cluster/docker-compose.yml up -d      # the cluster this drives
//
// The engine runs as a container *inside the cluster's network* on purpose, and that is not a test detail: an
// Aerospike node tells its clients which addresses to use next, and inside Docker those are container
// addresses that a process on the host cannot reach ("Peers not reachable: 172.18.0.3 3000" - the first
// version of this drill failed exactly there). Anything talking to the cluster belongs on the same network.
//
// The engine writes to a *drill* set of its own (ha_drill_v1) so nothing here touches the data the other
// checks use. The acceptance criterion is the one in docs/ROADMAP-1.0.md M3-1: kill a storage node, and the
// query still answers the same thing.
import { execFileSync } from 'node:child_process';
import { readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const NL = String.fromCharCode(10);
const NETWORK = 'phoenixgraph-aerospike-mesh';
const ENGINE = 'pg-ha-engine';
const SET = 'ha_drill_v1';
const JRE = 'eclipse-temurin:21-jre';
const HOST_PORT = Number(process.env.PG_HA_PORT) || 8600 + (process.pid % 40);
let failures = 0;
const check = (name, ok, detail) => {
  if (!ok) failures += 1;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const docker = (args, options = {}) => execFileSync('docker', args,
  { encoding: 'utf8', env: { ...process.env, MSYS2_ARG_CONV_EXCL: '*' }, ...options });
const quiet = (args) => {
  try {
    return docker(args, { stdio: ['ignore', 'pipe', 'ignore'] });
  } catch {
    return '';
  }
};

// ---- 0. the cluster ------------------------------------------------------------------------------------
const containerFor = (name) => quiet(['ps', '-q', '-f', `name=^${name}$`]).trim().split(NL).filter(Boolean)[0];
const node1 = containerFor('aerospike-1');
const node2 = containerFor('aerospike-2');
if (!node1 || !node2) {
  console.log('skip the drill cluster is not running (start it with: '
    + 'docker compose -f deploy/aerospike-cluster/docker-compose.yml up -d); '
    + 'the nightly CI job starts it, so this check does run there');
  process.exit(0);
}
const info = (container, command) => docker(['exec', container, 'asinfo', '-v', command]).trim();
const clusterSize = () => {
  const parts = info(node1, 'statistics').split(';').filter((part) => part.startsWith('cluster_size='));
  return parts.length ? Number(parts[0].split('=')[1]) : -1;
};
check('the cluster reports two nodes', clusterSize() === 2, `cluster_size=${clusterSize()}`);
const replication = info(node1, 'namespace/test').split(';')
  .filter((part) => part.startsWith('effective_replication_factor='));
check('the namespace replicates every record twice',
  replication.length === 1 && replication[0].endsWith('=2'), replication.join(' '));

// ---- 1. an engine container on the cluster's network ---------------------------------------------------
quiet(['rm', '-f', ENGINE]);
const jar = readdirSync('graph-http/target')
  .filter((name) => /^graph-http-.*\.jar$/.test(name) && !name.endsWith('.original'))
  .map((name) => ({ name, mtime: statSync(join('graph-http/target', name)).mtimeMs }))
  .sort((a, b) => b.mtime - a.mtime)
  .map((entry) => entry.name)[0];
if (!jar) {
  check('the engine jar exists (run ./mvnw -DskipTests package first)', false, 'graph-http/target is empty');
  process.exit(1);
}
const started = docker(['run', '-d', '--name', ENGINE, '--network', NETWORK,
  '-p', `${HOST_PORT}:8081`,
  '-v', `${join(process.cwd(), 'graph-http', 'target').replace(/\\/g, '/')}:/jars:ro`,
  JRE,
  'java', '-jar', `/jars/${jar}`,
  '--phoenixgraph.storage.seeds=aerospike-1:3000,aerospike-2:3000',
  `--phoenixgraph.storage.vertex-set=${SET}`,
  '--phoenixgraph.storage.schema=payment-graph',
  '--phoenixgraph.gemini.first-port=19700',
], { stdio: ['ignore', 'pipe', 'pipe'] });
void started;
process.on('exit', () => quiet(['rm', '-f', ENGINE]));

const base = `http://localhost:${HOST_PORT}`;
const engineLog = () => {
  try {
    return docker(['logs', ENGINE], { stdio: ['ignore', 'pipe', 'pipe'] });
  } catch (failure) {
    return String(failure.stdout || '') + String(failure.stderr || '');
  }
};
let up = false;
for (let i = 0; i < 120 && !up; i += 1) {
  try {
    await fetch(`${base}/api/v1/graphs`, { signal: AbortSignal.timeout(2000) });
    up = true;
  } catch {
    const state = quiet(['inspect', '-f', '{{.State.Running}}', ENGINE]).trim();
    if (state === 'false') break;
    await sleep(500);
  }
}
check('an engine container serves a graph from the drill cluster', up,
  up ? `localhost:${HOST_PORT}` : engineLog().split(NL).slice(-2).join(' | '));
if (!up) {
  console.log(failures ? 'the drill could not start' : '');
  process.exit(1);
}

const post = async (path, body) => {
  const response = await fetch(base + path, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });
  return { status: response.status, text: await response.text() };
};
const answer = async (query) => {
  const response = await post('/api/v1/query', { graphName: 'payment-graph', query, includeResult: true });
  if (response.status !== 200) return `error(${response.status})`;
  const body = JSON.parse(response.text);
  return JSON.stringify(body.result === undefined ? body : body.result);
};

// a small graph of our own: one hub, four spokes, and the edges between them
for (let i = 0; i < 5; i += 1) {
  const written = await post('/api/v1/vertices', {
    id: `ha-${i}`, vertexSchemaName: 'Account', properties: [{ index: 0, value: 'ACTIVE' }],
  });
  if (written.status !== 200) check(`writing ha-${i}`, false, written.text.slice(0, 120));
}
for (let i = 1; i < 5; i += 1) {
  const linked = await post('/api/v1/edges', {
    edgeId: `ha-edge-${i}`, edgeSchemaName: 'Payment', source: 'ha-0', destination: `ha-${i}`, properties: [],
  });
  if (linked.status !== 200) check(`linking ha-0 -> ha-${i}`, false, linked.text.slice(0, 120));
}
const neighbours = "g.V('ha-0','Account').out('Payment').dedup().count()";
const before = await answer(neighbours);
check('the small graph is readable before anything is killed', before === '[4]', before);
const mastersOnNode2 = () => info(node2, 'namespace/test').split(';')
  .filter((part) => /^(master|prole)_objects=/.test(part)).join(' ');
check('both nodes hold records (the replica is not an empty peer)',
  /(master|prole)_objects=([1-9][0-9]*)/.test(mastersOnNode2()), mastersOnNode2());

// ---- 2. kill a storage node ----------------------------------------------------------------------------
quiet(['stop', 'aerospike-2']);
console.log('    killed aerospike-2');
await sleep(2000);
// Failover is not instant: the client notices the node is gone and the cluster re-elects the master partitions
// of the dead node. The drill allows a bounded window for that, and then insists on the same answer.
let duringKill = 'error';
let recoveredAfter = 0;
for (let i = 0; i < 30; i += 1) {
  duringKill = await answer(neighbours);
  if (duringKill === before) {
    recoveredAfter = (i + 1) * 1000;
    break;
  }
  await sleep(1000);
}
check('with one node down the answer is unchanged', duringKill === before, `${duringKill} (before ${before})`);
check('and failover completed within a bounded window', recoveredAfter > 0 && recoveredAfter <= 30000,
  `recovered after ${recoveredAfter} ms`);
check('the surviving node knows it is alone', clusterSize() === 1, `cluster_size=${clusterSize()}`);

const writtenWhileDown = await post('/api/v1/vertices', {
  id: 'ha-killed', vertexSchemaName: 'Account', properties: [{ index: 0, value: 'ACTIVE' }],
});
check('writes still work with one node down', writtenWhileDown.status === 200,
  `${writtenWhileDown.status} ${writtenWhileDown.text.slice(0, 80)}`);
check('and the write is readable while the node is still down',
  (await answer("g.V('ha-killed','Account').count()")) === '[1]');

// ---- 3. bring it back ----------------------------------------------------------------------------------
quiet(['start', 'aerospike-2']);
console.log('    restarted aerospike-2');
let clusterBack = 0;
for (let i = 0; i < 45; i += 1) {
  if (clusterSize() === 2) {
    clusterBack = (i + 1) * 1000;
    break;
  }
  await sleep(1000);
}
check('the cluster forms again when the node comes back', clusterBack > 0, `after ${clusterBack} ms`);

// replication only catches up once the node is in the cluster again: wait for it to hold records, then check
let replicationBack = 0;
for (let i = 0; i < 45; i += 1) {
  const recorded = mastersOnNode2();
  if (/objects=([1-9][0-9]*)/.test(recorded)) {
    replicationBack = (i + 1) * 1000;
    break;
  }
  await sleep(1000);
}
check('the returning node receives its share of the data (replication catches up)',
  replicationBack > 0, `after ${replicationBack} ms, ${mastersOnNode2()}`);
check('the answer is still the same, and the vertex written while it was down is there',
  (await answer(neighbours)) === before && (await answer("g.V('ha-killed','Account').count()")) === '[1]',
  await answer(neighbours));

// ---- 4. and the other direction: the *seed* node is the one that dies -----------------------------------
// A deployment whose configuration names one seed has a single point of failure at *start-up*; a running client
// has discovered the other nodes and keeps working. This is worth showing rather than claiming, and
// docs/OPERATIONS.md says what to do about it.
quiet(['stop', 'aerospike-1']);
console.log('    killed aerospike-1 (the seed)');
await sleep(2000);
let seedDown = 'error';
for (let i = 0; i < 30; i += 1) {
  seedDown = await answer(neighbours);
  if (seedDown === before) break;
  await sleep(1000);
}
check('a running engine keeps answering when the node it was told about is gone', seedDown === before,
  `${seedDown} (before ${before})`);
quiet(['start', 'aerospike-1']);
for (let i = 0; i < 45; i += 1) {
  if (clusterSize() === 2) break;
  await sleep(1000);
}
check('the seed comes back into the cluster', clusterSize() === 2, `cluster_size=${clusterSize()}`);

quiet(['rm', '-f', ENGINE]);
await sleep(500);
console.log(`\nthe drill used the set ${SET} in the drill cluster; drop it with:`);
console.log(`  docker exec aerospike-1 asinfo -v "truncate:namespace=test;set=${SET}"`);

console.log(failures
  ? `\n${failures} FAILURES`
  : 'a node can be lost: reads, writes and answers survive it, and replication catches up when it returns');
process.exit(failures ? 1 : 0);
