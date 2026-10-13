// needs: service
// what: the schema format version (M3-4) end to end: the API's verdict on a stamped, an unstamped and a
//       future-version file, plus a service that keeps running while it refuses the file from the future
import { execFileSync, spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const API = 'http://localhost:8081';
const NL = String.fromCharCode(10);
let failures = 0;
const check = (name, ok, detail) => {
  if (!ok) failures += 1;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const version = async (json) => {
  const response = await fetch(API + '/api/v1/schema/version', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: json,
  });
  return { status: response.status, body: await response.json() };
};

// ---- 1. what the API says about three files ------------------------------------------------------------
const stamped = readFileSync('graph-http/src/main/resources/payment-graph.json', 'utf8');
const stampedAnswer = await version(stamped);
check('a schema stamped by this build is reported compatible',
  stampedAnswer.status === 200 && stampedAnswer.body.compatible === true
  && stampedAnswer.body.declaredVersion === stampedAnswer.body.supportedThrough
  && stampedAnswer.body.graph === 'payment-graph',
  JSON.stringify(stampedAnswer.body));

const unstamped = stamped.replace(/\n\s*"schemaVersion": \d+,/, '');
check('the fixture really has no version field', !unstamped.includes('schemaVersion'));
const unstampedAnswer = await version(unstamped);
check('a file written before the field existed is read as version 1 and accepted',
  unstampedAnswer.body.compatible === true && unstampedAnswer.body.declaredVersion === 1
  && unstampedAnswer.body.graph === 'payment-graph',
  JSON.stringify(unstampedAnswer.body));

const future = stamped.replace(/"schemaVersion": \d+/, '"schemaVersion": 99');
const futureAnswer = await version(future);
check('a file from the future is refused, naming both versions',
  futureAnswer.body.compatible === false && /schemaVersion 99/.test(futureAnswer.body.problem || '')
  && /reads up to/.test(futureAnswer.body.problem || ''),
  futureAnswer.body.problem || JSON.stringify(futureAnswer.body));

// ---- 2. what a service does with a directory that holds both --------------------------------------------
const work = mkdtempSync(join(tmpdir(), 'pg-schema-version-'));
const schemas = join(work, 'schemas');
execFileSync('mkdir', ['-p', schemas]);
const template = JSON.parse(readFileSync('graph-http/src/main/resources/geo-merchants-rocksdb-edge.json', 'utf8'));
const asFile = (name, document) => {
  writeFileSync(join(schemas, name + '.json'), JSON.stringify(document, null, 2));
};
asFile('drill-old', {
  ...template, id: 2001, name: 'drill-old', storage: { ...template.storage, path: 'drill-old' },
});
// the same schema with the field removed, as an older build would have written it
const { schemaVersion, ...withoutField } = template;
asFile('drill-versionless', {
  ...withoutField, id: 2002, name: 'drill-versionless',
  storage: { ...template.storage, path: 'drill-versionless' },
});
asFile('drill-future', {
  ...template, id: 2003, name: 'drill-future', schemaVersion: 99,
  storage: { ...template.storage, path: 'drill-future' },
});

const jar = readdirSync('graph-http/target').find((name) => /^graph-http-.*\.jar$/.test(name) && !name.endsWith('.original'));
const port = Number(process.env.PG_VERSION_PORT) || 8500 + (process.pid % 60);
const child = spawn('java', ['-jar', `graph-http/target/${jar}`,
  `--server.port=${port}`,
  `--phoenixgraph.storage.rocksdb-root=${join(work, 'root').replace(/\\/g, '/')}`,
  `--phoenixgraph.storage.schema-dir=${schemas.replace(/\\/g, '/')}`,
  '--phoenixgraph.gemini.first-port=19600',
], { stdio: ['ignore', 'pipe', 'pipe'] });
let log = '';
child.stdout.on('data', (chunk) => { log += chunk; });
child.stderr.on('data', (chunk) => { log += chunk; });
process.on('exit', () => { try { child.kill(); } catch { /* gone */ } });

const base = `http://localhost:${port}`;
let up = false;
for (let i = 0; i < 120 && !up; i += 1) {
  try {
    await fetch(`${base}/api/v1/graphs`, { signal: AbortSignal.timeout(2000) });
    up = true;
  } catch {
    await sleep(500);
  }
}
check('a service whose schema directory holds a file from the future still starts', up,
  up ? base : log.split(NL).slice(-3).join(' | '));

if (up) {
  const graphs = await (await fetch(`${base}/api/v1/graphs`)).text();
  check('the versioned and the versionless schema are both served',
    graphs.includes('drill-old') && graphs.includes('drill-versionless'));
  check('the schema from the future is not served', !graphs.includes('drill-future'));
  check('and the log says why, naming both versions',
    /drill-future\.json/.test(log) && /schemaVersion 99/.test(log),
    (log.split(NL).filter((line) => line.includes('drill-future')).slice(-1)[0] || 'no line').slice(0, 160));
}
child.kill();
await sleep(500);

// ---- 3. the repository's own schemas are stamped ---------------------------------------------------------
const checked = execFileSync('node', ['tools/schema-version.mjs', '--check', 'graph-http/src/main/resources'],
  { encoding: 'utf8' });
check('every schema in the repository carries the current version',
  /0 unstamped/.test(checked) && /0 refused/.test(checked),
  checked.trim().split(NL).slice(-1)[0]);

console.log(failures
  ? `\n${failures} FAILURES`
  : 'the schema format is versioned: old files load, new files are stamped, and the future is refused by name');
process.exit(failures ? 1 : 0);
