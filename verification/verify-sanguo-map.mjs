// needs: browser
// what: the map view inside the visualization panel (icon gate, back button)
// run:  node verification/run.mjs
// Verifies the map view inside the visualization panel (no separate page any more): the toolbar icon
// only appears for an instance that can answer the question, clicking it swaps the canvas for the map,
// and clicking it again brings the picture back.
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
  console.error('no Chromium-based browser found for ' + 'verify-sanguo-map.mjs' + ': set PG_BROWSER=/path/to/msedge (or chrome/chromium)');
  process.exit(2);
}
const PORT = Number(process.env.PG_CDP_PORT) || 9300 + (process.pid % 600);
const profile = mkdtempSync(join(tmpdir(), 'pg-panel-map-'));
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
  if (m.method === 'Fetch.requestPaused') {
    const body = m.params.request.postData || '';
    // the coordinate probe (a one-row read of the coordinate columns) can be answered here, so the
    // "schema says yes, data says no" case is testable without touching any graph
    if (body.includes('limit(1)') && body.includes('valueMap')) {
      send('Fetch.fulfillRequest', {
        requestId: m.params.requestId, responseCode: 200,
        responseHeaders: [{ name: 'content-type', value: 'application/json' }],
        body: Buffer.from('[]').toString('base64'),
      });
    } else {
      send('Fetch.continueRequest', { requestId: m.params.requestId });
    }
  }
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
/**
 * Whether an element is really painted. `hidden` alone does not say: the user agent's [hidden] rule is
 * `display: none`, but an author rule that sets display (toolbar buttons are `display: grid`) beats it,
 * which is exactly how the map icon and its back button stayed on screen outside map mode.
 */
const visible = (id) => evaluate(`(() => {
  const el = document.getElementById('${id}');
  if (!el) return false;
  return el.hidden !== true && getComputedStyle(el).display !== 'none' && el.getClientRects().length > 0;
})()`);
const pickGraph = async (name) => {
  await evaluate(`(() => {
    const select = document.getElementById('graphSelect');
    select.value = ${JSON.stringify(name)};
    select.dispatchEvent(new Event('change', { bubbles: true }));
  })()`);
  await sleep(1500); // the schema read behind the icon decision
};

await send('Page.enable');
await send('Runtime.enable');
await send('Page.navigate', { url: 'http://localhost:8080/' });
for (let i = 0; i < 60; i++) {
  const ready = await evaluate("!!document.getElementById('graphSelect') && document.getElementById('graphSelect').options.length > 0");
  if (ready) break;
  await sleep(500);
}

// ---- 1. the icon follows the instance ----------------------------------------------------------
check('no map icon for the payment graph (no coordinates in its schema)',
  (await visible('mapBtn')) === false);

await pickGraph('sanguo-kg');
check('no map icon for the keyed sanguo instance (Aerospike cannot scan a type)',
  (await visible('mapBtn')) === false);

await pickGraph('sanguo-kg-text');
check('the sanguo text graph offers the map icon',
  (await visible('mapBtn')) === true);

// ---- 2. clicking it shows the map inside the panel ----------------------------------------------
await evaluate("document.getElementById('mapBtn').click()");
for (let i = 0; i < 50; i++) {
  await sleep(500);
  const markers = await evaluate("document.querySelectorAll('#mapView path.leaflet-interactive').length");
  const tiles = await evaluate("document.querySelectorAll('#mapView .leaflet-tile-loaded').length");
  const note = await evaluate("document.getElementById('mapNote').textContent");
  const loading = note.indexOf('正在') === 0;
  // wait for the settled state: points drawn, basemap decided, and the note already naming the source
  if (markers > 0 && !loading && (tiles > 0 || /素色背景/.test(note)) && /图里全部/.test(note)) break;
}
const opened = {
  mapHidden: (await visible('mapView')) === false,
  markers: await evaluate("document.querySelectorAll('#mapView path.leaflet-interactive').length"),
  tiles: await evaluate("document.querySelectorAll('#mapView .leaflet-tile-loaded').length"),
  note: await evaluate("document.getElementById('mapNote').textContent"),
  active: await evaluate("document.getElementById('mapBtn').classList.contains('active')"),
  mapOpenClass: await evaluate("document.getElementById('canvasWrap').classList.contains('map-open')"),
  canvasOnlyDimmed: await evaluate("getComputedStyle(document.querySelector('#toolbar #algoSelect')).pointerEvents"),
};
check('the map opens in the panel', opened.mapHidden === false && opened.markers > 0,
  `markers=${opened.markers} tiles=${opened.tiles}`);
check('an explicit way back is offered while the map is up',
  (await visible('mapClose')) === true
  && /返回图可视化/.test(await evaluate("document.getElementById('mapClose').textContent")));
check('the points are the sanguo coordinates (26 of them, as the standalone page showed)',
  opened.markers === 26, `markers=${opened.markers}`);
check('a basemap is behind them (OSM, or 高德 after the fallback)', opened.tiles > 0, `tiles=${opened.tiles}`);
check('the note says what was drawn and which basemap', /图里全部 26 个带经纬度的实体/.test(opened.note) && /底图/.test(opened.note),
  JSON.stringify(opened.note));
check('the icon is marked as open', opened.active === true);
check('the canvas-only tools step aside while the map is up',
  opened.mapOpenClass === true && opened.canvasOnlyDimmed === 'none', opened.canvasOnlyDimmed);

// ---- 3. the back button returns to the graph, and so does Escape --------------------------------
await evaluate("document.getElementById('mapClose').click()");
await sleep(1200);
const closed = {
  mapHidden: (await visible('mapView')) === false,
  closeHidden: await visible('mapClose') === false,
  noteHidden: await evaluate("document.getElementById('mapNote').hidden"),
  active: await evaluate("document.getElementById('mapBtn').classList.contains('active')"),
  canvasSvg: await evaluate("document.querySelectorAll('#canvas svg').length"),
  canvasOnlyDimmed: await evaluate("getComputedStyle(document.querySelector('#toolbar #algoSelect')).pointerEvents"),
  iconStillThere: (await visible('mapBtn')) === true,
};
check('the back button closes the map and the canvas is back',
  closed.mapHidden === true && closed.closeHidden === true && closed.canvasSvg === 1
  && closed.active === false && closed.noteHidden === true, JSON.stringify(closed));
check('the canvas tools work again', closed.canvasOnlyDimmed !== 'none', closed.canvasOnlyDimmed);
check('the icon stays for this instance', closed.iconStillThere === true);

// Escape is the second way back
await evaluate("document.getElementById('mapBtn').click()");
for (let i = 0; i < 40; i++) {
  await sleep(400);
  if ((await evaluate("document.querySelectorAll('#mapView path.leaflet-interactive').length")) > 0) break;
}
check('the map is open again for the Escape check',
  (await visible('mapView')) === true);
await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
await sleep(1200);
check('Escape returns to the graph as well',
  (await visible('mapView')) === false
  && (await evaluate("document.querySelectorAll('#canvas svg').length")) === 1);

// ---- 4. a query takes the picture back (the map steps aside) ------------------------------------
await evaluate("document.getElementById('mapBtn').click()");
await sleep(2500);
await evaluate(`(() => {
  const toggle = document.getElementById('modeToggle');
  if (toggle && !toggle.checked) { toggle.checked = true; toggle.dispatchEvent(new Event('change', { bubbles: true })); }
  const input = document.getElementById('gremlinInput');
  input.value = "g.V('成都','Entity').bothE('Entity_Entity').limit(20)";
  input.dispatchEvent(new Event('input', { bubbles: true }));
  document.getElementById('queryBtn').click();
})()`);
await sleep(6000);
check('running a query puts the map away and draws the answer',
  (await visible('mapView')) === false
  && (await evaluate("document.querySelectorAll('#canvas svg g.node').length")) > 0,
  `nodes=${await evaluate("document.querySelectorAll('#canvas svg g.node').length")}`);

// ---- 5. a graph whose schema declares coordinates but holds none gets no icon --------------------
await send('Fetch.enable', { patterns: [{ urlPattern: '*api/query*', requestStage: 'Request' }] });
await send('Page.reload');
for (let i = 0; i < 60; i++) {
  if (await evaluate("!!document.getElementById('graphSelect') && document.getElementById('graphSelect').options.length > 0")) break;
  await sleep(500);
}
await evaluate(`(() => {
  const select = document.getElementById('graphSelect');
  select.value = 'sanguo-kg-text';
  select.dispatchEvent(new Event('change', { bubbles: true }));
})()`);
await sleep(2500);
check('no icon when the coordinate probe comes back empty',
  (await visible('mapBtn')) === false);
await send('Fetch.disable');
await send('Page.reload');
for (let i = 0; i < 60; i++) {
  if (await evaluate("!!document.getElementById('graphSelect') && document.getElementById('graphSelect').options.length > 0")) break;
  await sleep(500);
}
await evaluate(`(() => {
  const select = document.getElementById('graphSelect');
  select.value = 'sanguo-kg-text';
  select.dispatchEvent(new Event('change', { bubbles: true }));
})()`);
await sleep(2500);
check('and the icon is back once the real probe answers',
  (await visible('mapBtn')) === true);

socket.close();
edge.kill();
console.log(failures ? `${failures} FAILURES` : 'the map lives in the visualization panel and toggles both ways');
process.exit(failures ? 1 : 0);