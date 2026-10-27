// needs: browser
// what: captures the screenshots used by docs/INCIDENT-TRIAGE.md (a tool, not a check)
// run:  node verification/capture-incident-triage.mjs [out-dir]
// Captures the two triage screenshots from the live UI (8080): the change window that the ranking blames, drawn
// two hops out, and the failure signature around redis-cache (the two failure modes, the metrics that observe
// them and the deploys that touched them). Ingest the corpus first:
//   node examples/incident-triage/triage.mjs --reingest
import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync, mkdirSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const BROWSERS = [
  process.env.PG_BROWSER,
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  '/usr/bin/microsoft-edge', '/usr/bin/microsoft-edge-stable',
  '/usr/bin/google-chrome', '/usr/bin/google-chrome-stable',
  '/usr/bin/chromium', '/usr/bin/chromium-browser',
].filter(Boolean);
const BROWSER = BROWSERS.find((path) => { try { return statSync(path).isFile(); } catch { return false; } });
if (!BROWSER) {
  console.error('no Chromium-based browser found: set PG_BROWSER=/path/to/msedge (or chrome/chromium)');
  process.exit(2);
}
const PORT = Number(process.env.PG_CDP_PORT) || 9300 + (process.pid % 600);
const OUT = process.argv[2] || 'docs/screenshots';
const profile = mkdtempSync(join(tmpdir(), 'pg-triage-shot-'));
const browser = spawn(BROWSER, ['--headless=new', `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${profile}`, '--no-first-run', '--window-size=1500,950', 'about:blank'],
  { stdio: 'ignore' });
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

let page = null;
for (let i = 0; i < 60 && !page; i++) {
  try {
    page = (await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()).find((t) => t.type === 'page');
  } catch { /* the browser is still starting */ }
  if (!page) await sleep(500);
}
if (!page) {
  console.error('the browser did not expose a page');
  browser.kill();
  process.exit(1);
}
const socket = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve) => socket.addEventListener('open', resolve));
let id = 0;
const pending = new Map();
socket.addEventListener('message', (event) => {
  const message = JSON.parse(event.data);
  const entry = pending.get(message.id);
  if (entry) {
    pending.delete(message.id);
    entry(message);
  }
});
const send = (method, params = {}) => new Promise((resolve) => {
  const messageId = ++id;
  pending.set(messageId, resolve);
  socket.send(JSON.stringify({ id: messageId, method, params }));
});
const evaluate = async (expression) => {
  const answer = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  return answer.result?.result?.value;
};

await send('Page.enable');
await send('Runtime.enable');
await send('Emulation.setDeviceMetricsOverride', { width: 1500, height: 950, deviceScaleFactor: 1.5, mobile: false });
await send('Page.navigate', { url: 'http://localhost:8080/' });
for (let i = 0; i < 60; i++) {
  const ready = await evaluate("!!document.getElementById('graphSelect') && Array.from(document.getElementById('graphSelect').options).some(o => o.value === 'incident-triage')");
  if (ready) break;
  await sleep(500);
}

const shots = [
  {
    // the change window the ranking blames, two hops out: deploy-48 -> churn/redis-cache, and the older
    // deploy-42 that failed the same way in September
    // the change window the ranking blames, one hop out: the six edges deploy-48 actually wrote
    file: 'incident-triage-deploy-48.png',
    graph: 'incident-triage',
    query: "g.V('deploy-48','Entity').bothE('Relates').otherV().dedup().limit(30)",
    wait: 7000,
  },
  {
    // the failure signature: two failure modes on the same datastore, the metrics that observe it, the deploys
    file: 'incident-triage-signature.png',
    graph: 'incident-triage',
    query: "g.V('redis-cache','Entity','cache-key-prefix-churn','Entity','cache-eviction-storm','Entity','redis-cpu-percent','Entity','redis-evictions-rate','Entity').bothE('Relates').otherV().dedup().limit(40)",
    wait: 7000,
  },
];

mkdirSync(OUT, { recursive: true });
for (const shot of shots) {
  console.log('---', shot.file);
  await evaluate(`(() => {
    const select = document.getElementById('graphSelect');
    select.value = ${JSON.stringify(shot.graph)};
    select.dispatchEvent(new Event('change', { bubbles: true }));
  })()`);
  await sleep(1500);
  await evaluate(`(() => {
    const toggle = document.getElementById('modeToggle');
    if (toggle && !toggle.checked) { toggle.checked = true; toggle.dispatchEvent(new Event('change', { bubbles: true })); }
  })()`);
  await sleep(300);
  await evaluate(`(() => {
    const input = document.getElementById('gremlinInput');
    input.value = ${JSON.stringify(shot.query)};
    input.dispatchEvent(new Event('input', { bubbles: true }));
  })()`);
  await sleep(400);
  await evaluate("document.getElementById('queryBtn').click()");
  await sleep(shot.wait);
  try { await evaluate("document.getElementById('rebalanceBtn').click()"); } catch { /* optional */ }
  await sleep(1200);
  try { await evaluate("document.getElementById('resetViewBtn').click()"); } catch { /* optional */ }
  await sleep(1200);
  const rendered = await evaluate(`(() => {
    const canvas = document.getElementById('canvas');
    return { nodes: canvas.querySelectorAll('.node').length, edges: canvas.querySelectorAll('.edge').length };
  })()`);
  console.log('  rendered:', JSON.stringify(rendered));
  const captured = await send('Page.captureScreenshot', { format: 'png' });
  writeFileSync(join(OUT, shot.file), Buffer.from(captured.result.data, 'base64'));
  console.log('  saved', join(OUT, shot.file));
}

socket.close();
browser.kill();
process.exit(0);
