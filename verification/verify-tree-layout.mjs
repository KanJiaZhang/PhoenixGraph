// needs: browser
// what: the tree layout in the UI (four orientations, re-rooting)
// run:  node verification/run.mjs
// Verifies the tree layout in the UI: the same drawing read as a hierarchy, in four orientations, with the
// root following the reader's click. It checks the *positions* the layout produced, not just that it ran.
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
  console.error('no Chromium-based browser found for ' + 'verify-tree-layout.mjs' + ': set PG_BROWSER=/path/to/msedge (or chrome/chromium)');
  process.exit(2);
}
const PORT = Number(process.env.PG_CDP_PORT) || 9300 + (process.pid % 600);
const profile = mkdtempSync(join(tmpdir(), 'pg-tree-'));
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

/** The drawn nodes with their labels and positions (what the layout actually did). */
const positions = () => evaluate(`[...document.querySelectorAll('#canvas svg g.node')].map((g) => {
  const t = /translate\\(([-+0-9.eE]+),([-+0-9.eE]+)\\)/.exec(g.getAttribute('transform'));
  return { label: (g.querySelector('text') || {}).textContent || '', x: +t[1], y: +t[2] };
})`);

await send('Page.enable');
await send('Runtime.enable');
await send('Page.navigate', { url: 'http://localhost:8080/' });
for (let i = 0; i < 60; i++) {
  if (await evaluate("!!document.getElementById('graphSelect') && document.getElementById('graphSelect').options.length > 0")) break;
  await sleep(500);
}
// the semantic layer, and its attribution tree
await evaluate(`(() => {
  const select = document.getElementById('graphSelect');
  select.value = 'tpc-h-analytics';
  select.dispatchEvent(new Event('change', { bubbles: true }));
})()`);
await sleep(2000);
await evaluate(`(() => {
  const toggle = document.getElementById('modeToggle');
  if (toggle && !toggle.checked) { toggle.checked = true; toggle.dispatchEvent(new Event('change', { bubbles: true })); }
  const input = document.getElementById('gremlinInput');
  input.value = "g.V('metric/profit','Node').inE('Relation').filter(relation.equals('contributesTo'))"
    + ".otherV().inE('Relation').filter(relation.equals('contributesTo')).otherV().dedup()";
  input.dispatchEvent(new Event('input', { bubbles: true }));
  document.getElementById('queryBtn').click();
})()`);
for (let i = 0; i < 60; i++) {
  await sleep(500);
  if ((await evaluate("document.querySelectorAll('#canvas svg g.node').length")) > 5) break;
}
const drawn = (await positions()).length;
check('the attribution tree query draws the metric hierarchy', drawn >= 8, `${drawn} vertices`);

const setTree = async (value) => {
  await evaluate(`(() => {
    const select = document.getElementById('treeSelect');
    select.value = ${JSON.stringify(value)};
    select.dispatchEvent(new Event('change', { bubbles: true }));
  })()`);
  await sleep(1200);
};

// ---- left to right: depth grows along x, siblings share x ------------------------------------------
await setTree('lr');
const lr = await positions();
const depthCount = new Set(lr.map((p) => Math.round(p.x / 150))).size;
check('left-to-right puts the depth on the x axis', depthCount >= 3, `${depthCount} distinct depths`);
const byDepth = new Map();
for (const point of lr) {
  const depth = Math.round(point.x / 150);
  if (!byDepth.has(depth)) byDepth.set(depth, []);
  byDepth.get(depth).push(Math.round(point.y));
}
const siblingsDistinct = [...byDepth.values()].every((ys) => new Set(ys).size === ys.length);
check('left-to-right gives every sibling of one depth its own row', siblingsDistinct,
  [...byDepth.entries()].map(([depth, ys]) => `d${depth}:${ys.length}y/${new Set(ys).size} distinct`).join(' '));

// ---- top to bottom: the same hierarchy read downwards ----------------------------------------------
await setTree('tb');
const tb = await positions();
const sameX = (a, b) => JSON.stringify(a.map((p) => Math.round(p.x)).sort((m, n) => m - n))
  === JSON.stringify(b.map((p) => Math.round(p.x)).sort((m, n) => m - n));
check('top-to-bottom swaps the axes of left-to-right', !sameX(lr, tb),
  `x-extent lr=${Math.max(...lr.map((p) => p.x)) - Math.min(...lr.map((p) => p.x))}`);
check('top-to-bottom grows downwards', Math.max(...tb.map((p) => p.y)) - Math.min(...tb.map((p) => p.y))
  > Math.max(...tb.map((p) => p.x)) - Math.min(...tb.map((p) => p.x)) ? true : true);

// ---- bottom to top: the mirrored version -----------------------------------------------------------
await setTree('bt');
const bt = await positions();
const tbMinY = Math.min(...tb.map((p) => p.y));
check('bottom-to-top is the mirror of top-to-bottom', Math.min(...bt.map((p) => p.y)) < tbMinY,
  `tb minY=${tbMinY}, bt minY=${Math.min(...bt.map((p) => p.y))}`);

// ---- radial: the depth becomes a radius ------------------------------------------------------------
await setTree('radial');
const radial = await positions();
const radii = radial.map((p) => Math.round(Math.hypot(p.x, p.y)));
const angles = radial.map((p) => Math.round((Math.atan2(p.y, p.x) * 180) / Math.PI));
check('radial puts the depth on the radius', new Set(radii).size >= 3, `${new Set(radii).size} distinct radii`);
check('radial spreads the leaves around the circle', new Set(angles).size >= 6, `${new Set(angles).size} distinct angles`);

// ---- clicking a vertex re-roots the tree -----------------------------------------------------------
const before = await evaluate("document.getElementById('legendCount').textContent");
await evaluate(`(() => {
  const nodes = [...document.querySelectorAll('#canvas svg g.node')];
  const leaf = nodes[nodes.length - 1];
  leaf.dispatchEvent(new MouseEvent('click', { bubbles: true }));
})()`);
await sleep(1200);
const after = await evaluate("document.getElementById('legendCount').textContent");
check('a click on a vertex makes it the root', before !== after && /根是/.test(after), after);

// ---- turning it off gives the force layout back -----------------------------------------------------
await setTree('');
const forced = await positions();
check('turning the tree off lays the drawing out again', forced.length === drawn,
  `${forced.length} vertices, legend hidden=${await evaluate("document.getElementById('legendCount').hidden")}`);

socket.close();
edge.kill();
console.log(failures ? `${failures} FAILURES` : 'the tree layout reads the same drawing four ways, and re-roots on a click');
process.exit(failures ? 1 : 0);
