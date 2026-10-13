// needs: browser
// what: a geo query drives the map
// run:  node verification/run.mjs
// Verifies the "a geo query drives the map" flow the example list offers: pick sanguo-kg-text, run a
// valueMap query that reads latitude/longitude, click the map icon, and check that the map draws exactly
// those points (with the note offering the graph's own full list, and back).
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
  console.error('no Chromium-based browser found for ' + 'verify-geo-query-map.mjs' + ': set PG_BROWSER=/path/to/msedge (or chrome/chromium)');
  process.exit(2);
}
const PORT = Number(process.env.PG_CDP_PORT) || 9300 + (process.pid % 600);
const profile = mkdtempSync(join(tmpdir(), 'pg-geo-query-'));
const edge = spawn(EDGE, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`,
  '--no-first-run', '--window-size=1500,950', 'about:blank'], { stdio: 'ignore' });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let page = null;
for (let i = 0; i < 60 && !page; i++) {
  try { page = (await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()).find((t) => t.type === 'page'); } catch {}
  if (!page) await sleep(500);
}
const socket = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r) => socket.addEventListener('open', r));
let id = 0;
const pending = new Map();
socket.addEventListener('message', (event) => {
  const m = JSON.parse(event.data);
  const entry = pending.get(m.id);
  if (entry) { pending.delete(m.id); entry(m); }
});
const send = (method, params = {}) => new Promise((resolve) => {
  const messageId = ++id;
  pending.set(messageId, resolve);
  socket.send(JSON.stringify({ id: messageId, method, params }));
});
const evaluate = async (expression) => {
  const answer = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (answer.result?.exceptionDetails) throw new Error(JSON.stringify(answer.result.exceptionDetails).slice(0, 300));
  return answer.result?.result?.value;
};
let failures = 0;
const check = (name, ok, detail) => { if (!ok) failures += 1; console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`); };
const markers = () => evaluate("document.querySelectorAll('#mapView path.leaflet-interactive').length");
const note = () => evaluate("document.getElementById('mapNote').textContent");

await send('Page.enable');
await send('Runtime.enable');
await send('Page.navigate', { url: 'http://localhost:8080/' });
for (let i = 0; i < 60; i++) {
  if (await evaluate("!!document.getElementById('graphSelect') && document.getElementById('graphSelect').options.length > 0")) break;
  await sleep(500);
}
await evaluate(`(() => {
  const select = document.getElementById('graphSelect');
  select.value = 'sanguo-kg-text';
  select.dispatchEvent(new Event('change', { bubbles: true }));
})()`);
await sleep(1800);

// the example the answer points at: the battles after 210 AD (six of them)
await evaluate(`(() => {
  const toggle = document.getElementById('modeToggle');
  if (toggle && !toggle.checked) { toggle.checked = true; toggle.dispatchEvent(new Event('change', { bubbles: true })); }
  const input = document.getElementById('gremlinInput');
  input.value = "g.V('Entity').has('entityType','Event').has('latitude').has('eventYear', gt(210))"
    + ".valueMap('name','eventYear','latitude','longitude')";
  input.dispatchEvent(new Event('input', { bubbles: true }));
  document.getElementById('queryBtn').click();
})()`);
for (let i = 0; i < 40; i++) {
  await sleep(500);
  if ((await evaluate("document.getElementById('rowsTable').querySelectorAll('tr').length")) > 1) break;
}
const rows = await evaluate("document.getElementById('rowsTable').querySelectorAll('tr').length - 1");
check('the geo query answers with rows in the result tab', rows === 6, `rows=${rows}`);

await evaluate("document.getElementById('mapBtn').click()");
for (let i = 0; i < 40; i++) {
  await sleep(400);
  if ((await markers()) > 0) break;
}
check('the map draws exactly the six points the query returned', (await markers()) === 6, `markers=${await markers()}`);
check('the note says the points came from the query', /本次查询返回的 6 个/.test(await note()), JSON.stringify(await note()));
check('the note offers the graph\'s own list', /显示全部/.test(await note()));
check('the query\'s points need no fresh read of the graph (they are already in hand)',
  /6 个/.test(await note()));

// switch to the graph's own 26, then back to the query's 6
await evaluate("document.querySelector('#mapNote [data-map-source]').click()");
for (let i = 0; i < 40; i++) {
  await sleep(400);
  if ((await markers()) === 26) break;
}
check('"show all" draws the graph\'s 26 entities', (await markers()) === 26, `markers=${await markers()}`);
check('the note now says it is the graph\'s list', /图里全部 26 个/.test(await note()), JSON.stringify(await note()));
await evaluate("document.querySelector('#mapNote [data-map-source]').click()");
for (let i = 0; i < 20; i++) {
  await sleep(400);
  if ((await markers()) === 6) break;
}
check('and the switch takes it back to the query\'s six', (await markers()) === 6, `markers=${await markers()}`);

socket.close();
edge.kill();
console.log(failures ? `${failures} FAILURES` : 'a query that reads coordinates puts its own points on the map');
process.exit(failures ? 1 : 0);
