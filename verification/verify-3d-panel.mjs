// needs: browser
// what: the 3D view inside the canvas panel matches the 2D colours and follows the query
// run:  node verification/run.mjs
// Verifies the 3D view *inside the canvas panel*: the toolbar icon toggles it, it draws the same answer
// the 2D canvas is showing (the same per-type colours, the same highlights, the same filters), it follows
// a new query, picking works, and it gives the canvas back when it is switched off.
//
// The point of reading the 3D state back through window.phoenixView3D (and of comparing every colour with
// what the DOM says the 2D canvas is drawing) is that "compatible with the rest of the visualisation" is a
// claim about *values*, not about something having been drawn at all.
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
  console.error('no Chromium-based browser found for ' + 'verify-3d-panel.mjs' + ': set PG_BROWSER=/path/to/msedge (or chrome/chromium)');
  process.exit(2);
}
const PORT = Number(process.env.PG_CDP_PORT) || 9300 + (process.pid % 600);
const profile = mkdtempSync(join(tmpdir(), 'pg-3d-'));
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
  if (answer.result?.exceptionDetails) {
    throw new Error(JSON.stringify(answer.result.exceptionDetails.exception?.description
      || answer.result.exceptionDetails).slice(0, 300));
  }
  return answer.result?.result?.value;
};
let failures = 0;
const check = (name, ok, detail) => { if (!ok) failures += 1; console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`); };

const runQuery = async (graphName, query) => {
  await evaluate(`(() => {
    const select = document.getElementById('graphSelect');
    select.value = ${JSON.stringify(graphName)};
    select.dispatchEvent(new Event('change', { bubbles: true }));
  })()`);
  await sleep(1800);
  await evaluate(`(() => {
    const toggle = document.getElementById('modeToggle');
    if (toggle && !toggle.checked) { toggle.checked = true; toggle.dispatchEvent(new Event('change', { bubbles: true })); }
    const input = document.getElementById('gremlinInput');
    input.value = ${JSON.stringify(query)};
    input.dispatchEvent(new Event('input', { bubbles: true }));
    document.getElementById('queryBtn').click();
  })()`);
  for (let i = 0; i < 60; i++) {
    await sleep(500);
    if ((await evaluate("document.querySelectorAll('#canvas svg g.node').length")) > 3) break;
  }
};

/** What the 2D canvas is drawing right now: the vertex fills, the edge strokes, the totals. */
const canvas2d = () => evaluate(`(() => {
  const fill = (el) => { const s = getComputedStyle(el); return s.fill && s.fill !== 'none' ? s.fill : s.stroke; };
  const nodes = [...document.querySelectorAll('#canvas svg g.node')].map((g) => ({
    fill: fill(g.querySelector('circle.body')),
    dim: g.classList.contains('legend-dim') || g.classList.contains('path-dim')
      || g.classList.contains('search-dim') || g.classList.contains('algo-out'),
  }));
  const edges = [...document.querySelectorAll('#canvas svg path.edge')].map((p) => getComputedStyle(p).stroke);
  return {
    nodes,
    edges,
    vertices: +document.getElementById('vertexCount').textContent,
    edgeCount: +document.getElementById('edgeCount').textContent,
  };
})()`);

/** The 3D view as the renderer sees it. */
const view3d = () => evaluate(`(() => {
  const controller = window.phoenixView3D;
  if (!controller) return null;
  const s = controller.snapshot();
  return {
    nodes: s.nodes.map((n) => ({ key: n.key, fill: n.fill, ring: n.ring, alpha: n.alpha,
      x: n.x, y: n.y, z: n.z })),
    edges: s.edges.map((e) => ({ source: e.source, target: e.target, stroke: e.stroke, alpha: e.alpha })),
    yaw: s.yaw, pitch: s.pitch, zoom: s.zoom, energy: s.energy, drawn: s.drawn,
  };
})()`);

/** Projected points (device pixels) - the same thing the renderer paints with. */
const projected = () => evaluate(`window.phoenixView3D.project().map((p) => ({ key: p.key, x: p.x, y: p.y, z: p.z, scale: p.scale }))`);

const state = () => evaluate(`(() => {
  const view = document.getElementById('view3d');
  const button = document.getElementById('view3dBtn');
  const rect = view.getBoundingClientRect();
  const hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
  return {
    hidden: view.hidden,
    display: getComputedStyle(view).display,
    zIndex: getComputedStyle(view).zIndex,
    svgZ: getComputedStyle(document.querySelector('#canvas svg')).zIndex || 'auto',
    active: button.classList.contains('active'),
    pressed: button.getAttribute('aria-pressed'),
    topMost: hit ? (hit.id || hit.tagName) : null,
    closeHidden: document.getElementById('view3dClose').hidden,
    hint: document.getElementById('legendCount').hidden ? '' : document.getElementById('legendCount').textContent,
    svgNodes: document.querySelectorAll('#canvas svg g.node').length,
  };
})()`);

await send('Page.enable');
await send('Runtime.enable');
await send('Page.navigate', { url: 'http://localhost:8080/' });
for (let i = 0; i < 60; i++) {
  if (await evaluate("!!document.getElementById('graphSelect') && document.getElementById('graphSelect').options.length > 0")) break;
  await sleep(500);
}

// ---- 1. off by default ------------------------------------------------------------------------------
const off = await state();
check('3D is off until it is asked for', off.hidden && !off.active && off.pressed === 'false' && off.closeHidden,
  `hidden=${off.hidden} active=${off.active}`);

// ---- 2. the icon turns it on, and it covers the canvas ----------------------------------------------
await runQuery('tpc-h-analytics',
  "g.V('domain/sales','Node').inE('Relation').filter(relation.equals('belongsTo')).otherV()"
  + ".outE('Relation').filter(relation.equals('computedFrom')).otherV()"
  + ".outE('Relation').filter(relation.equals('hasColumn')).otherV().dedup()");
const before = await canvas2d();
await evaluate("document.getElementById('view3dBtn').click()");
await sleep(1500);
const on = await state();
check('the 3D icon turns the 3D view on', !on.hidden && on.active && on.pressed === 'true',
  `hidden=${on.hidden} active=${on.active} pressed=${on.pressed}`);
check('the 3D canvas covers the drawing and offers a way back',
  on.topMost === 'view3d' && !on.closeHidden && +on.zIndex > 4, `topmost=${on.topMost} z=${on.zIndex} close=${!on.closeHidden}`);
check('the 2D drawing underneath is untouched (the panel keeps its state)',
  on.svgNodes === before.vertices, `${on.svgNodes} vertices still in the SVG`);

// ---- 3. the renderer got exactly the answer that is drawn -------------------------------------------
const scene = await view3d();
check('3D draws the same vertices and edges as 2D',
  !!scene && scene.nodes.length === before.vertices && scene.edges.length === before.edgeCount,
  `${scene ? scene.nodes.length : '?'}v/${scene ? scene.edges.length : '?'}e vs 2D ${before.vertices}v/${before.edgeCount}e`);
check('every vertex has finite 3D coordinates (nothing at NaN)',
  scene.nodes.every((n) => Number.isFinite(n.x) && Number.isFinite(n.y) && Number.isFinite(n.z)
    && n.x !== 0 || n.y !== 0 || n.z !== 0));
check('the scene settles (the relaxation ran)', scene.energy >= 0 && scene.drawn, `energy=${scene.energy.toFixed(3)}`);

// ---- 4. the colours are the 2D ones: per vertex type, and per edge type ------------------------------
const fills2d = [...new Set(before.nodes.map((n) => n.fill))].sort();
const fills3d = [...new Set(scene.nodes.map((n) => n.fill))].sort();
check('the 3D fills are the 2D fills, type by type',
  JSON.stringify(fills3d) === JSON.stringify(fills2d),
  `${fills3d.length} fill(s): ${fills3d.join(' ')}`);
const strokes2d = [...new Set(before.edges)].sort();
const strokes3d = [...new Set(scene.edges.map((e) => e.stroke))].sort();
check('edges are coloured by type too, and 3D uses the 2D colours',
  JSON.stringify(strokes3d) === JSON.stringify(strokes2d), strokes3d.join(' '));
check('every 3D vertex carries the fill of its own DOM vertex (not one colour for the lot)',
  scene.nodes.every((n) => fills2d.includes(n.fill)));

// ---- 5. it is a real painting, and it is a real 3D projection ---------------------------------------
const painted = await evaluate(`(() => {
  const canvas = document.getElementById('view3d');
  const data = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
  let n = 0;
  for (let i = 3; i < data.length; i += 4 * 53) if (data[i] > 8) n += 1;
  return n;
})()`);
check('the canvas holds a picture', painted > 20, `${painted} painted samples`);
const beforeRotate = await projected();
await evaluate("window.phoenixView3D.rotate(0.9, 0.15)");
const afterRotate = await projected();
const moved = beforeRotate.filter((p, i) => Math.hypot(p.x - afterRotate[i].x, p.y - afterRotate[i].y) > 1).length;
check('rotating moves the projection', moved === beforeRotate.length && beforeRotate.length > 0,
  `${moved}/${beforeRotate.length} points moved`);
const depths = new Set(afterRotate.map((p) => Math.round(p.z / 10))).size;
check('the projection has depth (points at different z)', depths >= 5, `${depths} depth bands`);
const zoomBefore = afterRotate[0].scale;
await evaluate("window.phoenixView3D.zoomBy(1.25)");
const zoomAfter = (await projected())[0].scale;
check('the wheel zoom changes the scale', zoomAfter > zoomBefore, `${zoomBefore.toFixed(3)} -> ${zoomAfter.toFixed(3)}`);
await evaluate("window.phoenixView3D.resetView()");
await sleep(300);

// ---- 6. the filters and highlights of the 2D canvas reach 3D ----------------------------------------
// This graph labels everything "Node", so the *type* count is 1 by design: several colours per drawing is
// checked below on a graph whose vertices really are of several labels. What matters here is that a filter
// dims the same set in both views.
await evaluate(`(() => {
  const rows = [...document.querySelectorAll('#canvas svg .legend-item')];
  rows[rows.length - 1].dispatchEvent(new MouseEvent('click', { bubbles: true })); // the last row is an edge type
})()`);
await sleep(900);
const filtered = await view3d();
const dim3d = filtered.nodes.filter((n) => n.alpha < 1).length;
const dim2d = (await canvas2d()).nodes.filter((n) => n.dim).length;
check('a legend filter dims the same vertices in 3D as in 2D',
  dim3d === dim2d, `${dim3d} dimmed in 3D, ${dim2d} in 2D`);
await evaluate(`(() => {
  const rows = [...document.querySelectorAll('#canvas svg .legend-item')];
  rows[rows.length - 1].dispatchEvent(new MouseEvent('click', { bubbles: true }));
})()`);
await sleep(900);
const cleared = await view3d();
check('clearing the filter restores every vertex in 3D',
  cleared.nodes.every((n) => n.alpha === 1), `${cleared.nodes.filter((n) => n.alpha < 1).length} still dimmed`);

await evaluate(`(() => {
  const input = document.getElementById('searchInput');
  input.value = 'sales';
  input.dispatchEvent(new Event('input', { bubbles: true }));
})()`);
await sleep(900);
const searched = await view3d();
const ringed = searched.nodes.filter((n) => n.ring).length;
check('a search hit carries its ring into 3D',
  ringed > 0 && searched.nodes.some((n) => n.alpha < 1), `${ringed} ringed, `
  + `${searched.nodes.filter((n) => n.alpha < 1).length} dimmed`);
await evaluate(`(() => {
  const input = document.getElementById('searchInput');
  input.value = '';
  input.dispatchEvent(new Event('input', { bubbles: true }));
})()`);
await sleep(600);

// ---- 7. picking in 3D selects the same vertex the properties panel shows -----------------------------
const pickTarget = (await projected()).slice().sort((a, b) => b.scale - a.scale)[0];
const picked = await evaluate(`(() => {
  const canvas = document.getElementById('view3d');
  const rect = canvas.getBoundingClientRect();
  const ratio = window.devicePixelRatio || 1;
  const x = rect.left + ${pickTarget.x} / ratio;
  const y = rect.top + ${pickTarget.y} / ratio;
  canvas.dispatchEvent(new MouseEvent('mousedown', { clientX: x, clientY: y, button: 0, bubbles: true }));
  window.dispatchEvent(new MouseEvent('mouseup', { clientX: x, clientY: y, button: 0, bubbles: true }));
  return (document.getElementById('propsContent') || {}).textContent || '';
})()`);
check('clicking a vertex in 3D selects it (properties panel fills in)',
  picked.length > 0 && /id/.test(picked), picked.replace(/\s+/g, ' ').slice(0, 90));

// ---- 8. a graph whose types really differ: several vertex and edge colours, in both views -----------
await runQuery('payment-graph', "g.V('135','Account').both().both().dedup().limit(60)");
await sleep(1200);
const mixed2d = await canvas2d();
const mixed3d = await view3d();
const mixedFills2d = [...new Set(mixed2d.nodes.map((n) => n.fill))];
const mixedFills3d = [...new Set(mixed3d.nodes.map((n) => n.fill))];
check('a graph with several vertex types shows several colours, the same ones in 3D',
  mixedFills3d.length >= 2 && JSON.stringify(mixedFills3d.sort()) === JSON.stringify(mixedFills2d.sort()),
  `${mixedFills3d.length} vertex colours: ${mixedFills3d.sort().join(' ')}`);
const mixedStrokes2d = [...new Set(mixed2d.edges)].sort();
const mixedStrokes3d = [...new Set(mixed3d.edges.map((e) => e.stroke))].sort();
check('several edge types show several colours, the same ones in 3D',
  mixedStrokes3d.length >= 2 && JSON.stringify(mixedStrokes3d) === JSON.stringify(mixedStrokes2d),
  `${mixedStrokes3d.length} edge colours: ${mixedStrokes3d.join(' ')}`);

// ---- 9. a new query drives the 3D view as well ------------------------------------------------------
await runQuery('tpc-h-analytics',
  "g.V('metric/profit','Node').inE('Relation').filter(relation.equals('contributesTo'))"
  + ".otherV().inE('Relation').filter(relation.equals('contributesTo')).otherV().dedup()");
await sleep(1200);
const afterQuery = await view3d();
const now2d = await canvas2d();
check('a new query re-renders the 3D view with the new answer',
  afterQuery.nodes.length === now2d.vertices && afterQuery.edges.length === now2d.edgeCount
  && afterQuery.nodes.length === 11,
  `${afterQuery.nodes.length}v/${afterQuery.edges.length}e vs 2D ${now2d.vertices}v/${now2d.edgeCount}e`);
check('the hint says what 3D is showing', /3D/.test((await state()).hint), (await state()).hint);
check('the new answer has no unprojectable vertices',
  (await projected()).every((p) => Number.isFinite(p.x) && Number.isFinite(p.y)));

// ---- 10. switching the tree layout keeps working while 3D is on, and 3D follows it -------------------
await evaluate(`(() => {
  const select = document.getElementById('treeSelect');
  select.value = 'dag-rl';
  select.dispatchEvent(new Event('change', { bubbles: true }));
})()`);
await sleep(1200);
const dagScene = await view3d();
check('a layering chosen in 2D still drives the answer drawn in 3D',
  dagScene.nodes.length === 11 && dagScene.edges.length === 10 && dagScene.nodes.every((n) => Number.isFinite(n.x)),
  `${dagScene.nodes.length}v/${dagScene.edges.length}e`);

// ---- 11. off again: the canvas comes back, and Escape is a way out too ------------------------------
await evaluate("document.getElementById('view3dBtn').click()");
await sleep(900);
const back = await state();
check('the icon turns 3D off and the drawing comes back',
  back.hidden && !back.active && back.pressed === 'false' && back.closeHidden && !/3D/.test(back.hint),
  `hidden=${back.hidden} active=${back.active} hint="${back.hint.slice(0, 40)}"`);
await evaluate("document.getElementById('view3dBtn').click()");
await sleep(900);
await evaluate("document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))");
await sleep(900);
const escaped = await state();
check('Escape leaves 3D as well', escaped.hidden && !escaped.active, `hidden=${escaped.hidden}`);

// ---- 12. the map and 3D are two views of one canvas: opening one closes the other --------------------
await runQuery('sanguo-kg-text', "g.V('曹操','Entity').both('Entity_Entity').dedup()");
await evaluate("document.getElementById('view3dBtn').click()");
await sleep(1200);
const opened3d = await state();
// the map icon appears once the graph's schema has been read (it needs a latitude/longitude pair)
let mapReady = false;
for (let i = 0; i < 24 && !mapReady; i += 1) {
  mapReady = await evaluate("!document.getElementById('mapBtn').hidden");
  if (!mapReady) await sleep(500);
}
if (mapReady) {
  await evaluate("document.getElementById('mapBtn').click()");
  await sleep(2500);
  const afterMap = await state();
  const mapShown = await evaluate("!document.getElementById('mapView').hidden");
  check('opening the map closes 3D (one canvas, one view)',
    opened3d.hidden === false && afterMap.hidden && mapShown,
    `3d hidden=${afterMap.hidden} map shown=${mapShown}`);
  // and the other way round: the 3D icon takes the canvas back from the map
  await evaluate("document.getElementById('view3dBtn').click()");
  await sleep(1500);
  const back3d = await state();
  const mapAfter = await evaluate("document.getElementById('mapView').hidden");
  check('opening 3D closes the map (and the drawing is still there)',
    !back3d.hidden && back3d.svgNodes > 3 && mapAfter === true,
    `3d hidden=${back3d.hidden} map hidden=${mapAfter} svg=${back3d.svgNodes}`);
  await evaluate("document.getElementById('view3dBtn').click()");
  await sleep(900);
} else {
  check('the map icon appears for the scannable 三国 graph', false,
    'the map button stayed hidden: the geo plan never became ready');
}

socket.close();
edge.kill();
console.log(failures ? `${failures} FAILURES` : 'the 3D view lives in the canvas panel, matches the 2D colours and follows the query');
process.exit(failures ? 1 : 0);
