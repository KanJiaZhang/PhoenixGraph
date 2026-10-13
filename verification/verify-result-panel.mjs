// needs: browser
// what: the result panel (clipped values, rows tab, value window)
// run:  node verification/run.mjs
// Drives the real UI in headless Edge over CDP: runs a rows-only query and a query with a long value,
// and checks the panel behaviour (auto Rows tab, clipped value that opens on click).
import { spawn } from 'node:child_process';
import { mkdtempSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// The browser is found, not hard-coded: PG_BROWSER wins, then the usual install paths.
const BROWSERS = [
  process.env.PG_BROWSER,
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  '/usr/bin/microsoft-edge', '/usr/bin/microsoft-edge-stable',
  '/usr/bin/google-chrome', '/usr/bin/google-chrome-stable',
  '/usr/bin/chromium', '/usr/bin/chromium-browser',
].filter(Boolean);
const EDGE = BROWSERS.find((path) => { try { return statSync(path).isFile(); } catch { return false; } });
if (!EDGE) {
  console.error('no Chromium-based browser found for ' + 'verify-result-panel.mjs' + ': set PG_BROWSER=/path/to/msedge (or chrome/chromium)');
  process.exit(2);
}
const PORT = Number(process.env.PG_CDP_PORT) || 9300 + (process.pid % 600);
const profile = mkdtempSync(join(tmpdir(), 'pg-cdp-'));
const edge = spawn(EDGE, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`,
  '--no-first-run', '--no-default-browser-check', '--window-size=1600,1000', 'about:blank'],
  { stdio: 'ignore' });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let page = null;
for (let i = 0; i < 60 && !page; i++) {
  try {
    const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
    page = list.find((t) => t.type === 'page');
  } catch { /* not up yet */ }
  if (!page) await sleep(500);
}
if (!page) { console.log('FAIL: Edge did not come up'); process.exit(1); }

const socket = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve) => socket.addEventListener('open', resolve));
let id = 0;
const pending = new Map();
socket.addEventListener('message', (event) => {
  const message = JSON.parse(event.data);
  const entry = pending.get(message.id);
  if (entry) { pending.delete(message.id); entry(message); }
});
const send = (method, params = {}) => new Promise((resolve) => {
  const messageId = ++id;
  pending.set(messageId, resolve);
  socket.send(JSON.stringify({ id: messageId, method, params }));
});
const evaluate = async (expression) => {
  const answer = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (answer.result?.exceptionDetails) throw new Error(JSON.stringify(answer.result.exceptionDetails));
  return answer.result?.result?.value;
};

let failures = 0;
const check = (name, ok, detail) => { if (!ok) failures += 1; console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`); };

await send('Page.enable');
await send('Runtime.enable');
await send('Page.navigate', { url: 'http://localhost:8080/' });
for (let i = 0; i < 60; i++) {
  const ready = await evaluate("!!document.getElementById('gremlinInput') && document.getElementById('graphSelect').options.length");
  if (ready) break;
  await sleep(500);
}
check('the UI booted', (await evaluate("document.querySelectorAll('.tab').length")) === 4);

const run = async (query, graphName = 'payment-graph') => {
  const baseline = await evaluate("document.getElementById('rowsTable').querySelectorAll('tr').length + '|' + document.getElementById('toast').textContent");
  await evaluate(`(() => {
    const select = document.getElementById('graphSelect');
    const option = [...select.options].find((o) => o.value === ${JSON.stringify(graphName)});
    select.value = option.value;
    select.dispatchEvent(new Event('change', { bubbles: true }));
    const toggle = document.getElementById('modeToggle');
    if (toggle && !toggle.checked) { toggle.checked = true; toggle.dispatchEvent(new Event('change', { bubbles: true })); }
    const input = document.getElementById('gremlinInput');
    input.value = ${JSON.stringify(query)};
    input.dispatchEvent(new Event('input', { bubbles: true }));
    document.getElementById('queryBtn').click();
  })()`);
  for (let i = 0; i < 50; i++) {
    await sleep(300);
    const seen = await evaluate("document.getElementById('rowsTable').querySelectorAll('tr').length + '|' + document.getElementById('toast').textContent");
    if (seen !== baseline) break;
  }
  await sleep(600); // let the render and the tab switch settle
};

// ---- 1. a rows-only answer: the sidebar switches itself to the result tab ---------------------------
await run("g.V('135','Account').bothE('Payment').count()");
const activeTab = await evaluate("document.querySelector('.tab.active').dataset.tab");
check('a rows-only answer (count()) opens the result tab on its own', activeTab === 'rows', `active=${activeTab}`);
const rowCount = await evaluate("document.getElementById('rowsTable').querySelectorAll('tr').length");
check('the rows are drawn', rowCount >= 2, `rows (with header)=${rowCount}`);
check('the tab is not marked unread while it is open',
  !(await evaluate("document.querySelector('.tab[data-tab=\"rows\"]').classList.contains('unread')")));
const toastText = await evaluate("document.getElementById('toast').textContent");
check('no "no result" toast for a rows-only answer', !toastText.includes('为空') && !toastText.includes('No result'), toastText);

// ---- 2. a graph answer still stays on the canvas, with the result tab marked unread ------------------
await run("g.V('135','Account').bothE('Payment').limit(5)");
const graphTab = await evaluate("document.querySelector('.tab.active').dataset.tab");
check('a query with a picture stays on the stats tab', graphTab === 'stats', `active=${graphTab}`);

// ---- 3. a long value is abbreviated, and one click opens it -----------------------------------------
await run("g.V('1001','Account').elementMap()");
const clipped = await evaluate(`(() => {
  const clip = document.querySelector('#rowsTable .value-clip');
  if (!clip) return null;
  return { short: clip.textContent.length, full: clip.dataset.full.length,
           hasDots: clip.textContent.endsWith('…'), open: clip.classList.contains('open') };
})()`);
check('a long value arrives abbreviated', !!clipped && clipped.hasDots && clipped.full > clipped.short,
  clipped ? `short=${clipped.short} full=${clipped.full}` : 'no clipped cell found');
if (clipped) {
  await evaluate("document.querySelector('#rowsTable .value-clip').click()");
  const opened = await evaluate(`(() => { const clip = document.querySelector('#rowsTable .value-clip');
    return { length: clip.textContent.length, open: clip.classList.contains('open') }; })()`);
  check('one click opens the whole value', opened.open && opened.length > clipped.short,
    `length ${clipped.short} -> ${opened.length}`);
  await evaluate("document.querySelector('#rowsTable .value-clip').click()");
  const closed = await evaluate(`(() => { const clip = document.querySelector('#rowsTable .value-clip');
    return { length: clip.textContent.length, open: clip.classList.contains('open') }; })()`);
  check('and one more click puts the short form back', !closed.open && closed.length === clipped.short);
}

// ---- 4. the same abbreviation in the property panel -------------------------------------------------
await run("g.V('135','Account').bothE('Payment').limit(5)");
const propsShown = await evaluate(`(() => {
  const node = document.querySelector('#canvas svg g.node');
  if (!node) return 'no node drawn';
  node.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  return document.getElementById('propsPanel').hidden;
})()`);
check('selecting an element brings its properties up', propsShown === false, String(propsShown));
const propsClip = await evaluate("document.querySelectorAll('#propsContent .value-clip').length");
console.log(`     (the property list clips ${propsClip} long value(s) the same way)`);

socket.close();
edge.kill();
console.log(failures ? `${failures} FAILURES` : 'the result panel behaves as asked');
process.exit(failures ? 1 : 0);
