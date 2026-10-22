// needs: service
// what: authentication and authorization - 401 without a key, 403 for the wrong role or the wrong graph, 200
//       for what the key may do - plus the audit trail of all of it
//
// A second instance is started with security enabled and three keys (a scoped writer, a reader and an admin),
// because the served instance runs open on purpose: proving that a key is required must not require breaking
// the demo. The keys live in a temporary configuration file, which is also how the documentation says to
// configure them (`--spring.config.additional-location`).
import { spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const OPEN = 'http://localhost:8081';
const PORT = Number(process.env.PG_SECURE_PORT) || 8200 + (process.pid % 60);
const SECURE = `http://localhost:${PORT}`;
let failures = 0;
const check = (name, ok, detail) => { if (!ok) failures += 1; console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// "up" means "answered at all": on a secured instance the readiness probe is itself a 401, which is the
// correct answer and not a reason to keep waiting
const isUp = async () => {
  try {
    await fetch(`${SECURE}/api/v1/graphs`, { signal: AbortSignal.timeout(2000) });
    return true;
  } catch {
    return false;
  }
};

const call = async (base, path, { token, ...options } = {}) => {
  const headers = { ...(options.headers || {}) };
  if (token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(base + path, { ...options, headers });
  const text = await response.text();
  return { status: response.status, headers: response.headers, text };
};
const query = (base, token, body) => call(base, '/api/v1/query', {
  method: 'POST', token, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
});

// ---- the instance with keys ---------------------------------------------------------------------------
const { createHash } = await import('node:crypto');
const opsDigest = createHash('sha256').update('ops-token').digest('hex');
const dir = mkdtempSync(join(tmpdir(), 'pg-secure-'));
const config = join(dir, 'security.yml');
writeFileSync(config, [
  'phoenixgraph:',
  '  security:',
  '    enabled: true',
  '    keys:',
  '      - name: scoped-writer',
  '        token: scope-token',
  '        role: writer',
  '        tenant: acme',
  '        graphs: [payment-graph]',
  '      - name: reader',
  '        token: read-token',
  '        role: reader',
  '      - name: ops',
  // the digest form: sha256("ops-token"), so the file never holds the token; a deployment computes it with
  //   printf %s ops-token | sha256sum
  `        sha256: ${opsDigest}`,
  '        role: admin',
  '',
].join('\n'));
const auditFile = join(dir, 'audit.jsonl');

const jar = readdirSync('graph-http/target')
  .filter((name) => /^graph-http-.*\.jar$/.test(name) && !name.endsWith('.original'))
  .map((name) => ({ name, mtime: statSync(join('graph-http/target', name)).mtimeMs }))
  .sort((a, b) => b.mtime - a.mtime)
  .map((entry) => entry.name)[0];
if (!jar) {
  check('the engine jar exists (run ./mvnw -DskipTests package first)', false, 'graph-http/target is empty');
  process.exit(1);
}
const engine = spawn('java', ['-jar', `graph-http/target/${jar}`,
  `--server.port=${PORT}`,
  `--spring.config.additional-location=file:${config.replace(/\\/g, '/')}`,
  `--phoenixgraph.storage.rocksdb-root=${mkdtempSync(join(tmpdir(), 'pg-secure-rocksdb-'))}`,
  `--phoenixgraph.audit.file=${auditFile.replace(/\\/g, '/')}`,
  '--phoenixgraph.gemini.first-port=19300',
], { stdio: ['ignore', 'pipe', 'pipe'] });
let log = '';
engine.stdout.on('data', (chunk) => { log += chunk; });
engine.stderr.on('data', (chunk) => { log += chunk; });
const stop = () => { try { engine.kill(); } catch { /* already gone */ } };
process.on('exit', stop);

let up = false;
for (let i = 0; i < 120 && !up; i += 1) {
  up = await isUp();
  if (!up) await sleep(500);
}
check('an instance with API keys starts', up, up ? SECURE : log.split('\n').slice(-3).join(' | '));

if (up) {
  const digestLogin = await call(SECURE, '/api/v1/graphs', { token: 'ops-token' });
  check('a key configured as its sha256 (never in clear) is accepted', digestLogin.status === 200,
    `status=${digestLogin.status} digest=${opsDigest.slice(0, 12)}…`);

  // ---- 401: no key, wrong key -------------------------------------------------------------
  const anonymous = await call(SECURE, '/api/v1/graphs');
  check('a request without a key is 401 with the Bearer challenge',
    anonymous.status === 401 && anonymous.headers.get('www-authenticate') === 'Bearer realm="phoenixgraph"'
    && anonymous.text.includes('Authorization: Bearer'),
    `${anonymous.status} ${anonymous.headers.get('www-authenticate')}`);
  const wrong = await call(SECURE, '/api/v1/graphs', { token: 'not-a-key' });
  check('an unknown key is 401 and the audit calls the caller "unknown" (never the token)',
    wrong.status === 401, `${wrong.status}`);

  // ---- reader: may query, may not write, may not see the audit ----------------------------
  const readQuery = await query(SECURE, 'read-token', { graphName: 'payment-graph', query: "g.V(1000,'Account').limit(1)", includeResult: true });
  check('a reader key may run a query (a query is a POST that reads)', readQuery.status === 200, `${readQuery.status}`);
  const readWrite = await call(SECURE, '/api/v1/graphs/payment-graph/vertices', {
    method: 'POST', token: 'read-token', headers: { 'Content-Type': 'application/json' }, body: '{"id":"probe-1"}',
  });
  check('the same reader key may not write', readWrite.status === 403 && readWrite.text.includes("'reader'"),
    `${readWrite.status} ${readWrite.text.slice(0, 80)}`);
  const readAudit = await call(SECURE, '/api/v1/audit/recent', { token: 'read-token' });
  check('and may not read the audit trail', readAudit.status === 403, `${readAudit.status}`);

  // ---- a scoped writer: allowed on its graph, refused on another --------------------------
  const scopedOk = await query(SECURE, 'scope-token', { graphName: 'payment-graph', query: "g.V(1000,'Account').limit(1)", includeResult: true });
  check('a scoped writer works on the graph it is scoped to', scopedOk.status === 200, `${scopedOk.status}`);
  const scopedNo = await query(SECURE, 'scope-token', { graphName: 'tpc-h-data', query: "g.V('Sale').limit(1).values('name')", includeResult: true });
  check('and is refused (403) on another graph', scopedNo.status === 403 && scopedNo.text.includes('tpc-h-data'),
    `${scopedNo.status} ${scopedNo.text.slice(0, 90)}`);
  const schemaNo = await call(SECURE, '/api/v1/schema', {
    method: 'POST', token: 'scope-token', headers: { 'Content-Type': 'application/json' }, body: '{}',
  });
  check('a writer may not author schemas (that needs admin)',
    schemaNo.status === 403 && schemaNo.text.includes("'admin'"), `${schemaNo.status}`);

  // ---- the audit trail -------------------------------------------------------------------
  const audit = await call(SECURE, '/api/v1/audit/recent?limit=50', { token: 'ops-token' });
  const entries = audit.status === 200 ? JSON.parse(audit.text).entries : [];
  const keys = new Set(entries.map((entry) => entry.key));
  check('an admin key reads the audit trail', audit.status === 200 && entries.length >= 5,
    `${audit.status} ${entries.length} entries`);
  check('the audit view is durable and names the tenant',
    JSON.parse(audit.text).durable === true
    && entries.some((entry) => entry.key === 'scoped-writer' && entry.tenant === 'acme'),
    `durable=${JSON.parse(audit.text).durable}`);
  check('the trail names the keys that called, including the refused ones',
    keys.has('reader') && keys.has('scoped-writer') && (keys.has('unknown') || keys.has('anonymous')),
    [...keys].join(', '));
  check('the trail carries method, path, status and graph',
    entries.every((entry) => entry.method && entry.path && typeof entry.status === 'number' && entry.graph)
    && entries.some((entry) => entry.status === 403) && entries.some((entry) => entry.status === 401),
    JSON.stringify(entries[0]));
  check('no token ever appears in the trail',
    !audit.text.includes('read-token') && !audit.text.includes('scope-token') && !audit.text.includes('ops-token'));
  check('a query in the trail is clipped', entries.filter((entry) => entry.query !== '-')
    .every((entry) => entry.query.length <= 201));
}

// ---- the served instance is still open by default (so the demo and the suite keep working) -------------
const openInstance = await call(OPEN, '/api/v1/graphs');
check('the served instance still answers without a key (security off by default)',
  openInstance.status === 200, `${openInstance.status}`);

// ---- the MCP plugin can use a key too -------------------------------------------------------------
const mcp = spawnSync('node', ['tools/mcp-server.mjs', '--list'], {
  env: { ...process.env, PHOENIXGRAPH_API: SECURE, PHOENIXGRAPH_API_KEY: 'ops-token' },
  encoding: 'utf8',
  timeout: 120000,
});
check('the MCP server reaches a secured instance with PHOENIXGRAPH_API_KEY',
  mcp.status === 0 && /find_entities/.test(mcp.stdout || ''),
  `${mcp.status}: ${(mcp.stderr || mcp.stdout || '').trim().split('\n').slice(0, 4).join(' | ')}`);

stop();
await sleep(500);
console.log(failures
  ? `\n${failures} FAILURES`
  : 'keys are enforced before any work happens, roles and graph scopes hold, and every call is audited');
process.exit(failures ? 1 : 0);
