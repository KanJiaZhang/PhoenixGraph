// needs: browser
// what: captures docs/screenshots/sanguo-map.png (a tool, not a check)
// run:  node verification/run.mjs
// Captures docs/screenshots/sanguo-map.png: the map *inside* the visualization panel (select the sanguo
// graph, click the map icon, wait for the points and the basemap, shoot).
import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync, statSync } from 'node:fs';
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
  console.error('no Chromium-based browser found for ' + 'capture-sanguo-map.mjs' + ': set PG_BROWSER=/path/to/msedge (or chrome/chromium)');
  process.exit(2);
}
const PORT = Number(process.env.PG_CDP_PORT) || 9300 + (process.pid % 600);
const OUT = process.argv[2] || 'docs/screenshots/sanguo-map.png';
const profile = mkdtempSync(join(tmpdir(), 'pg-panel-shot-'));
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
  return answer.result?.result?.value;
};

await send('Page.enable');
await send('Runtime.enable');
await send('Emulation.setDeviceMetricsOverride', { width: 1500, height: 950, deviceScaleFactor: 1, mobile: false });
await send('Page.navigate', { url: 'http://localhost:8080/' });
for (let i = 0; i < 60; i++) {
  const ready = await evaluate("!!document.getElementById('graphSelect') && document.getElementById('graphSelect').options.length > 0");
  if (ready) break;
  await sleep(500);
}
await evaluate(`(() => {
  const select = document.getElementById('graphSelect');
  select.value = 'sanguo-kg-text';
  select.dispatchEvent(new Event('change', { bubbles: true }));
})()`);
await sleep(2000); // the schema read behind the icon decision
await evaluate("document.getElementById('mapBtn').click()");
for (let i = 0; i < 50; i++) {
  await sleep(500);
  const markers = await evaluate("document.querySelectorAll('#mapView path.leaflet-interactive').length");
  const tiles = await evaluate("document.querySelectorAll('#mapView .leaflet-tile-loaded').length");
  const note = await evaluate("document.getElementById('mapNote').textContent");
  if (markers > 0 && (tiles > 0 || /素色背景/.test(note))) break;
}
await sleep(2500); // tiles finishing, fitBounds animation
const shot = await send('Page.captureScreenshot', { format: 'png' });
writeFileSync(OUT, Buffer.from(shot.result.data, 'base64'));
console.log('wrote', OUT,
  '| markers:', await evaluate("document.querySelectorAll('#mapView path.leaflet-interactive').length"),
  '| tiles:', await evaluate("document.querySelectorAll('#mapView .leaflet-tile-loaded').length"),
  '| note:', JSON.stringify(await evaluate("document.getElementById('mapNote').textContent")));
socket.close();
edge.kill();
process.exit(0);
