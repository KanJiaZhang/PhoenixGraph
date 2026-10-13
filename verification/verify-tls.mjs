// needs: service
// what: HTTPS - a real self-signed keystore, a client that trusts it, a client that refuses it (proving
//       verification is on), and no plain-HTTP API left on that port
//
// The keystore is generated here with keytool (the JDK is already a prerequisite), so the check exercises the
// configuration an operator would actually write rather than a fixture committed to the repository. A second
// instance serves it: the demo instance on 8081 stays plain HTTP.
import { execFileSync, spawn } from 'node:child_process';
import { existsSync, mkdtempSync, readdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import https from 'node:https';
import tls from 'node:tls';

const PORT = Number(process.env.PG_TLS_PORT) || 8300 + (process.pid % 60);
const KEYSTORE_PASSWORD = 'changeit-in-a-test';
const OTHER_PASSWORD = 'a-different-password';
let failures = 0;
const check = (name, ok, detail) => { if (!ok) failures += 1; console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const javaHome = process.env.JAVA_HOME || '';
const keytool = javaHome && existsSync(join(javaHome, 'bin', 'keytool.exe')) ? join(javaHome, 'bin', 'keytool.exe')
  : javaHome && existsSync(join(javaHome, 'bin', 'keytool')) ? join(javaHome, 'bin', 'keytool') : 'keytool';

const dir = mkdtempSync(join(tmpdir(), 'pg-tls-'));
const keystore = join(dir, 'phoenixgraph.p12');

// ---- 1. an operator's keystore ------------------------------------------------------------------------
execFileSync(keytool, ['-genkeypair', '-alias', 'phoenixgraph', '-keyalg', 'RSA', '-keysize', '2048',
  '-storetype', 'PKCS12', '-keystore', keystore, '-storepass', KEYSTORE_PASSWORD,
  '-dname', 'CN=localhost, OU=phoenixgraph, O=test', '-validity', '30',
  '-ext', 'SAN=dns:localhost,ip:127.0.0.1'], { stdio: 'ignore' });
check('a self-signed keystore can be generated with keytool', existsSync(keystore));

// ---- 2. an instance serving HTTPS ----------------------------------------------------------------------
const jar = readdirSync('graph-http/target').find((name) => /^graph-http-.*\.jar$/.test(name) && !name.endsWith('.original'));
if (!jar) {
  check('the engine jar exists (run ./mvnw -DskipTests package first)', false, 'graph-http/target is empty');
  process.exit(1);
}
const configFile = join(dir, 'tls.yml');
writeFileSync(configFile, [
  'server:',
  '  port: ' + PORT,
  '  ssl:',
  '    enabled: true',
  `    key-store: ${keystore.replace(/\\/g, '/')}`,
  '    key-store-type: PKCS12',
  '    key-alias: phoenixgraph',
  '    # the password comes from the environment, not the file: PHOENIXGRAPH_TLS_PASSWORD below',
  '    key-store-password: ${PHOENIXGRAPH_TLS_PASSWORD}',
  '',
].join('\n'));

const engine = spawn('java', ['-jar', `graph-http/target/${jar}`,
  `--spring.config.additional-location=file:${configFile.replace(/\\/g, '/')}`,
  `--phoenixgraph.storage.rocksdb-root=${mkdtempSync(join(tmpdir(), 'pg-tls-rocksdb-'))}`,
  '--phoenixgraph.gemini.first-port=19400',
], { stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, PHOENIXGRAPH_TLS_PASSWORD: KEYSTORE_PASSWORD } });
let log = '';
engine.stdout.on('data', (chunk) => { log += chunk; });
engine.stderr.on('data', (chunk) => { log += chunk; });
const stop = () => { try { engine.kill(); } catch { /* already gone */ } };
process.on('exit', stop);

/** A request over HTTPS, with the certificate checked the way the flag says. */
const tlsRequest = (path, { verify }) => new Promise((resolve) => {
  const request = https.request({
    host: 'localhost', port: PORT, path, method: 'GET',
    rejectUnauthorized: verify,
    ca: verify ? undefined : undefined,
    timeout: 5000,
  }, (response) => {
    let body = '';
    response.on('data', (chunk) => { body += chunk; });
    response.on('end', () => resolve({ status: response.statusCode, body }));
  });
  request.on('error', (error) => resolve({ status: 0, error: error.code || error.message }));
  request.end();
});

let up = false;
for (let i = 0; i < 120 && !up; i += 1) {
  const answer = await tlsRequest('/api/v1/graphs', { verify: false });
  up = answer.status !== 0 || (answer.error && answer.error !== 'ECONNREFUSED');
  if (!up) await sleep(500);
}
check('an instance serves HTTPS on its own port', up, up ? `https://localhost:${PORT}` : log.split('\n').slice(-3).join(' | '));

if (up) {
  const insecure = await tlsRequest('/api/v1/graphs', { verify: false });
  check('a client that does not verify certificates gets the API (the server side is right)',
    insecure.status === 200 && insecure.body.includes('payment-graph'), `status=${insecure.status}`);

  const verifying = await tlsRequest('/api/v1/graphs', { verify: true });
  check('a client that verifies certificates refuses the self-signed one (verification is really on)',
    verifying.status === 0 && /SELF_SIGNED|UNABLE_TO_VERIFY|DEPTH_ZERO/i.test(String(verifying.error)),
    String(verifying.error));

  // the certificate presented is the one we generated, not a container default
  const certificate = await new Promise((resolve) => {
    const socket = tls.connect({ host: 'localhost', port: PORT, rejectUnauthorized: false }, () => {
      resolve(socket.getPeerCertificate());
      socket.end();
    });
    socket.on('error', () => resolve(null));
  });
  check('the certificate is the generated one (CN=localhost, issuer = itself)',
    certificate && String(certificate.subject && certificate.subject.CN) === 'localhost'
    && String(certificate.issuer && certificate.issuer.CN) === 'localhost',
    certificate ? `CN=${certificate.subject && certificate.subject.CN} issuer=${certificate.issuer && certificate.issuer.CN}` : 'no certificate');

  // a POST body over TLS still reaches the engine
  const queryOverTls = await new Promise((resolve) => {
    const body = JSON.stringify({ graphName: 'payment-graph', query: "g.V(1000,'Account').limit(1)", includeResult: true });
    const request = https.request({ host: 'localhost', port: PORT, path: '/api/v1/query', method: 'POST',
      rejectUnauthorized: false, timeout: 8000,
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) } },
    (response) => {
      let text = '';
      response.on('data', (chunk) => { text += chunk; });
      response.on('end', () => resolve({ status: response.statusCode, text }));
    });
    request.on('error', (error) => resolve({ status: 0, text: String(error.message) }));
    request.write(body);
    request.end();
  });
  check('a query works over TLS, body and all',
    queryOverTls.status === 200 && queryOverTls.text.includes('1000'),
    `status=${queryOverTls.status} ${queryOverTls.text.slice(0, 80)}`);

  // plain HTTP on a TLS port is not an API. Tomcat answers it with a 400 (it saw something that is not a TLS
  // handshake) - the point is not the exact code but that no API response comes back.
  const plain = await fetch(`http://localhost:${PORT}/api/v1/graphs`, { signal: AbortSignal.timeout(4000) })
    .then(async (response) => ({ status: response.status, body: await response.text() }))
    .catch(() => ({ status: 0, body: '' }));
  check('plain HTTP on the TLS port never serves the API',
    plain.status !== 200 && !plain.body.includes('payment-graph'),
    `http status=${plain.status} body=${plain.body.slice(0, 40)}`);

  // a wrong keystore password must fail loudly (the password is an environment variable, not a file value)
  const wrong = spawn('java', ['-jar', `graph-http/target/${jar}`,
    `--spring.config.additional-location=file:${configFile.replace(/\\/g, '/')}`,
    `--server.port=${PORT + 1}`,
    `--phoenixgraph.storage.rocksdb-root=${mkdtempSync(join(tmpdir(), 'pg-tls-rocksdb2-'))}`,
    '--phoenixgraph.gemini.first-port=19410',
  ], { stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, PHOENIXGRAPH_TLS_PASSWORD: OTHER_PASSWORD } });
  let wrongLog = '';
  wrong.stdout.on('data', (chunk) => { wrongLog += chunk; });
  wrong.stderr.on('data', (chunk) => { wrongLog += chunk; });
  const exited = await new Promise((resolve) => {
    const timer = setTimeout(() => resolve(false), 25000);
    wrong.on('exit', () => { clearTimeout(timer); resolve(true); });
  });
  wrong.kill();
  check('a wrong keystore password stops the service instead of serving something else',
    exited && /password|keystore|UnrecoverableKeyException|IOException/i.test(wrongLog),
    exited ? wrongLog.split('\n').filter((line) => /password|keystore|ERROR/i.test(line)).slice(-1)[0] || 'exited' : 'still running');
}

stop();
await sleep(500);
console.log(failures
  ? `\n${failures} FAILURES`
  : 'TLS is configurable from a real keystore, the API works over it, and verification is not switched off');
process.exit(failures ? 1 : 0);
