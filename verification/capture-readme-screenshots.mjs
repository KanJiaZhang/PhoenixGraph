// needs: browser
// what: captures the screenshots used by README.md (a tool, not a check)
// run:  node verification/capture-readme-screenshots.mjs [out-dir]
// Captures the two README screenshots from the live UI (8080) and API (8081). The queries are the ones
// the README shows: a vector search followed by a graph walk, and a knowledge-graph neighbourhood.
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
const profile = mkdtempSync(join(tmpdir(), 'pg-readme-shot-'));
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
  const ready = await evaluate("!!document.getElementById('graphSelect') && document.getElementById('graphSelect').options.length > 0");
  if (ready) break;
  await sleep(500);
}

const shots = [
  {
    file: 'graph-vector-query.png',
    graph: 'payment-graph-rocksdb-edge',
    query: "g.V(1001,'Account').nearest(5).outE('Payment').otherV().dedup().limit(40)",
    wait: 6000,
  },
  {
    file: 'knowledge-graph-context.png',
    graph: 'sanguo-kg-rocksdb-edge',
    query: "g.V('关羽','Entity').bothE().otherV().dedup().limit(18)",
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
  await sleep(1200);
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
  await sleep(1000);
  try { await evaluate("document.getElementById('resetViewBtn').click()"); } catch { /* optional */ }
  await sleep(1200);
  const rendered = await evaluate(`(() => {
    const canvas = document.getElementById('canvas');
    return {
      nodes: canvas.querySelectorAll('.node').length,
      edges: canvas.querySelectorAll('.edge').length,
    };
  })()`);
  console.log('  rendered:', JSON.stringify(rendered));
  const captured = await send('Page.captureScreenshot', { format: 'png' });
  writeFileSync(join(OUT, shot.file), Buffer.from(captured.result.data, 'base64'));
  console.log('  saved', join(OUT, shot.file));
}

socket.close();
browser.kill();
process.exit(0);
