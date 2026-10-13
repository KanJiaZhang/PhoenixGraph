// needs: browser
// what: tree + layered DAG layouts: every edge kept, connected, RTL, menu i18n
// run:  node verification/run.mjs
// Verifies the two dependency layouts of the canvas: the tree (edges kept and connected, four
// orientations, click-to-re-root) and the layered DAG (every edge kept, ranks by longest path, cycles
// broken and drawn dashed). Positions and edge geometry are read back from the DOM, so this checks what
// the picture is, not just that something ran.
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
  console.error('no Chromium-based browser found for ' + 'verify-dag-layout.mjs' + ': set PG_BROWSER=/path/to/msedge (or chrome/chromium)');
  process.exit(2);
}
const PORT = Number(process.env.PG_CDP_PORT) || 9300 + (process.pid % 600);
const profile = mkdtempSync(join(tmpdir(), 'pg-dag-'));
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

/** The drawn vertices with their positions, and the edges with their path data. */
const picture = () => evaluate(`(() => {
  const num = /translate\\(([-+0-9.eE]+),([-+0-9.eE]+)\\)/;
  const nodes = [...document.querySelectorAll('#canvas svg g.node')].map((g) => {
    const m = num.exec(g.getAttribute('transform'));
    return { label: (g.querySelector('text') || {}).textContent || '', x: +m[1], y: +m[2] };
  });
  const edges = [...document.querySelectorAll('#canvas svg path.edge')].map((p) => ({
    d: p.getAttribute('d'), reversed: p.classList.contains('reversed'), curved: /C/.test(p.getAttribute('d') || ''),
  }));
  return { nodes, edges, hint: (document.getElementById('legendCount') || {}).textContent || '' };
})()`);

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
const setLayout = async (value) => {
  await evaluate(`(() => {
    const select = document.getElementById('treeSelect');
    select.value = ${JSON.stringify(value)};
    select.dispatchEvent(new Event('change', { bubbles: true }));
  })()`);
  await sleep(1300);
};
const depthOf = (nodes, step, axis) => new Set(nodes.map((n) => Math.round((axis === 'x' ? n.x : n.y) / step))).size;

await send('Page.enable');
await send('Runtime.enable');
await send('Page.navigate', { url: 'http://localhost:8080/' });
for (let i = 0; i < 60; i++) {
  if (await evaluate("!!document.getElementById('graphSelect') && document.getElementById('graphSelect').options.length > 0")) break;
  await sleep(500);
}

// ---- 1. the tree keeps its edges, and they are connected --------------------------------------------
await runQuery('tpc-h-analytics',
  "g.V('metric/profit','Node').inE('Relation').filter(relation.equals('contributesTo'))"
  + ".otherV().inE('Relation').filter(relation.equals('contributesTo')).otherV().dedup()");
await setLayout('lr');
const tree = await picture();
check('the tree draws a vertex per entity and an edge per relation',
  tree.nodes.length === 11 && tree.edges.length === 10, `${tree.nodes.length}v/${tree.edges.length}e`);
check('every tree edge has a path with real coordinates',
  tree.edges.every((e) => e.d && e.d.length > 8 && !/NaN/.test(e.d)), JSON.stringify(tree.edges[0]));
check('tree edges are curves, not straight lines through the picture',
  tree.edges.every((e) => e.curved), `${tree.edges.filter((e) => e.curved).length}/${tree.edges.length} curved`);
// connected: every edge endpoint must sit on (or very near) one of the vertices
const connected = await evaluate(`(() => {
  const num = /translate\\(([-+0-9.eE]+),([-+0-9.eE]+)\\)/;
  const nodes = [...document.querySelectorAll('#canvas svg g.node')].map((g) => {
    const m = num.exec(g.getAttribute('transform')); return { x: +m[1], y: +m[2] };
  });
  let loose = 0;
  for (const path of document.querySelectorAll('#canvas svg path.edge')) {
    const d = path.getAttribute('d') || '';
    const start = /^M ([^ ]+) ([^ ]+)/.exec(d);
    const end = /([^ ]+) ([^ ]+)$/.exec(d);
    for (const point of [start, end]) {
      if (!point) { loose += 1; continue; }
      const x = +point[1], y = +point[2];
      const near = Math.min(...nodes.map((n) => Math.hypot(n.x - x, n.y - y)));
      if (near > 26) loose += 1; // the node radius plus the edge gap
    }
  }
  return loose;
})()`);
check('every edge endpoint lands on a vertex (the links stay attached)', connected === 0, `${connected} loose ends`);

// ---- 2. the DAG keeps *every* edge and ranks by dependency ------------------------------------------
await setLayout('dag-lr');
const dag = await picture();
check('the DAG keeps every edge (no spanning tree)', dag.edges.length === 10, `${dag.edges.length} edges`);
check('the DAG ranks the vertices into layers', depthOf(dag.nodes, 150, 'x') >= 3,
  `${depthOf(dag.nodes, 150, 'x')} layers`);
check('the DAG says how it ranked', /DAG/.test(dag.hint) && /条边/.test(dag.hint), dag.hint);

await setLayout('dag-tb');
const dagTb = await picture();
check('DAG top-to-bottom moves the ranks onto y', depthOf(dagTb.nodes, 170, 'y') >= 3,
  `${depthOf(dagTb.nodes, 170, 'y')} layers`);
await setLayout('dag-bt');
const dagBt = await picture();
check('DAG bottom-to-top is the mirror', Math.min(...dagBt.nodes.map((n) => n.y)) < Math.min(...dagTb.nodes.map((n) => n.y)),
  `tb minY=${Math.min(...dagTb.nodes.map((n) => n.y))}, bt minY=${Math.min(...dagBt.nodes.map((n) => n.y))}`);

// ---- 3. a graph with cycles: the DAG breaks them and draws them dashed ------------------------------
await runQuery('sanguo-kg-rocksdb-edge', "g.V('曹操','Entity').both('Entity_Entity').dedup()");
await setLayout('dag-lr');
const cyclic = await picture();
check('a cyclic graph still keeps every edge',
  cyclic.edges.length === 225, `${cyclic.edges.length} edges of 225`);
check('the cycle-breaking edges are drawn dashed and still present',
  cyclic.edges.filter((e) => e.reversed).length > 0 && cyclic.edges.length === 225,
  `${cyclic.edges.filter((e) => e.reversed).length} reversed of ${cyclic.edges.length} drawn`);
check('every DAG edge is still a connected curve',
  cyclic.edges.every((e) => e.d && /[CQ]/.test(e.d) && !/NaN/.test(e.d)),
  `${cyclic.edges.filter((e) => /[CQ]/.test(e.d || '')).length}/${cyclic.edges.length} curved, no NaN`);
check('the hint counts the reversed edges', /虚线/.test(cyclic.hint), cyclic.hint);

// ---- 4. right to left: the same hierarchy read from the other side ----------------------------------
// The RTL modes exist because a lineage is often written the other way round (the thing depends on ...).
// What must hold is that they are the *same* layout mirrored on the depth axis - not a different tree with
// different crossing order - and that the mirrored edges are still attached.
await runQuery('tpc-h-analytics',
  "g.V('metric/profit','Node').inE('Relation').filter(relation.equals('contributesTo'))"
  + ".otherV().inE('Relation').filter(relation.equals('contributesTo')).otherV().dedup()");
await setLayout('lr');
const treeLr = await picture();
await setLayout('rl');
const treeRl = await picture();
check('tree right-to-left keeps the same vertices and edges',
  treeRl.nodes.length === treeLr.nodes.length && treeRl.edges.length === treeLr.edges.length,
  `${treeRl.nodes.length}v/${treeRl.edges.length}e`);
check('tree right-to-left mirrors the depth axis (everything at x <= 0, the root nearest 0)',
  Math.max(...treeRl.nodes.map((n) => n.x)) <= 0.001
  && Math.min(...treeRl.nodes.map((n) => n.x)) < -200
  && Math.abs(Math.max(...treeRl.nodes.map((n) => n.x))) < 0.001,
  `x range ${Math.min(...treeRl.nodes.map((n) => n.x)).toFixed(0)}..${Math.max(...treeRl.nodes.map((n) => n.x)).toFixed(0)}`);
check('tree right-to-left leaves the sibling order alone (y is the same reading)',
  JSON.stringify([...treeRl.nodes.map((n) => n.y)].sort((a, b) => a - b))
  === JSON.stringify([...treeLr.nodes.map((n) => n.y)].sort((a, b) => a - b)));
check('tree right-to-left keeps its edges attached and finite',
  treeRl.edges.every((e) => e.d && /C/.test(e.d) && !/NaN/.test(e.d)));
check('the hint names the right-to-left reading', /右→左/.test(treeRl.hint), treeRl.hint);

await setLayout('dag-lr');
const dagLr = await picture();
await setLayout('dag-rl');
const dagRl = await picture();
check('DAG right-to-left keeps every edge', dagRl.edges.length === dagLr.edges.length && dagRl.edges.length === 10,
  `${dagRl.edges.length} edges`);
check('DAG right-to-left mirrors the layers and keeps their count',
  Math.max(...dagRl.nodes.map((n) => n.x)) <= 0.001 && depthOf(dagRl.nodes, 150, 'x') === depthOf(dagLr.nodes, 150, 'x')
  && depthOf(dagRl.nodes, 150, 'x') >= 3,
  `${depthOf(dagRl.nodes, 150, 'x')} layers`);
check('DAG right-to-left edges are connected curves',
  dagRl.edges.every((e) => e.d && /[CQ]/.test(e.d) && !/NaN/.test(e.d)));

// ---- 5. the layout menu follows the language --------------------------------------------------------
// The two <optgroup> labels are attributes, not text nodes, so they were the one part of the menu that
// stayed Chinese under an English UI (applyI18n had no data-i18n-label branch).
const menu = () => evaluate(`(() => ({
  groups: [...document.querySelectorAll('#treeSelect optgroup')].map((g) => g.label),
  options: [...document.querySelectorAll('#treeSelect option')].map((o) => o.textContent),
  html: document.documentElement.lang,
  stored: localStorage.getItem('pg.lang'),
}))()`);
const zhMenu = await menu();
check('the layout menu reads Chinese by default',
  zhMenu.groups.includes('树形（生成树）') && zhMenu.options.some((s) => /右→左/.test(s)),
  JSON.stringify(zhMenu.groups));
// the language switch is the 中/EN button pair in the header (the other select is the *query* language)
await evaluate("document.querySelector('.lang-opt[data-lang=\"en\"]').click()");
await sleep(1200);
const enMenu = await menu();
check('the layout menu translates its groups under an English UI',
  enMenu.groups.includes('Tree (spanning)') && enMenu.groups.includes('Layered DAG (every edge kept)'),
  `lang=${enMenu.html} stored=${enMenu.stored} ${JSON.stringify(enMenu.groups)}`);
check('the layout choices translate too, right-to-left included',
  enMenu.options.includes('Tree · right to left') && enMenu.options.includes('DAG · right to left')
  && enMenu.options.includes('Tree · radial'), JSON.stringify(enMenu.options));
await setLayout('rl');
const enHint = await picture();
check('the hint follows the language as well', /right to left/.test(enHint.hint) && /vertices/.test(enHint.hint), enHint.hint);
await evaluate("document.querySelector('.lang-opt[data-lang=\"zh\"]').click()");
await sleep(1200);
const backZh = await menu();
check('switching back returns the Chinese menu',
  backZh.groups.includes('树形（生成树）') && backZh.html === 'zh-CN', `${backZh.html} ${JSON.stringify(backZh.groups)}`);

socket.close();
edge.kill();
console.log(failures ? `${failures} FAILURES` : 'tree and DAG layouts keep every edge, connected, in every orientation, right-to-left included');
process.exit(failures ? 1 : 0);
