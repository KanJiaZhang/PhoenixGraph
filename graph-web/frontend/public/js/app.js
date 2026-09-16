(() => {
  'use strict';

  // ---- vertex type styling (Lucide stroke icons + refined palette) ----
  const COLORS = { Account: '#4f46e5', IP: '#0d9488', Phone: '#d97706' };
  const DEFAULT_COLOR = '#64748b';

  // Lucide icon geometry (MIT), embedded so no CDN is needed at runtime.
  // Each icon is a list of SVG child elements described as {tag, attrs}.
  const ICONS = {
    Account: [
      { tag: 'circle', attrs: { cx: 12, cy: 8, r: 5 } },
      { tag: 'path', attrs: { d: 'M20 21a8 8 0 0 0-16 0' } },
    ],
    IP: [
      { tag: 'circle', attrs: { cx: 12, cy: 12, r: 10 } },
      { tag: 'path', attrs: { d: 'M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20' } },
      { tag: 'path', attrs: { d: 'M2 12h20' } },
    ],
    Phone: [
      { tag: 'rect', attrs: { width: 14, height: 20, x: 5, y: 2, rx: 2, ry: 2 } },
      { tag: 'path', attrs: { d: 'M12 18h.01' } },
    ],
  };
  const DEFAULT_ICON = [
    { tag: 'path', attrs: { d: 'M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z' } },
  ];

  const colorFor = (label) => COLORS[label] || DEFAULT_COLOR;
  const iconHref = (label) => (ICONS[label] ? `#icon-${label}` : '#icon-default');

  function appendIcon(parent, elements) {
    for (const el of elements) {
      const node = parent.append(el.tag);
      for (const [key, value] of Object.entries(el.attrs)) node.attr(key, value);
    }
  }

  // merged graph model, keyed by "label::id"
  const graph = { vertices: new Map(), edges: new Map() };

  const el = {
    graphSelect: document.getElementById('graphSelect'),
    vertexTypeSelect: document.getElementById('vertexTypeSelect'),
    vertexIdInput: document.getElementById('vertexIdInput'),
    gremlinInput: document.getElementById('gremlinInput'),
    idPanel: document.getElementById('idPanel'),
    gremlinPanel: document.getElementById('gremlinPanel'),
    modeToggle: document.getElementById('modeToggle'),
    queryBtn: document.getElementById('queryBtn'),
    rebalanceBtn: document.getElementById('rebalanceBtn'),
    brushBtn: document.getElementById('brushBtn'),
    resetViewBtn: document.getElementById('resetViewBtn'),
    algoSelect: document.getElementById('algoSelect'),
    canvas: document.getElementById('canvas'),
    vertexCount: document.getElementById('vertexCount'),
    edgeCount: document.getElementById('edgeCount'),
    propsHint: document.getElementById('propsHint'),
    propsContent: document.getElementById('propsContent'),
  };

  let currentGraph = null;
  let svg = null;
  let root = null;
  let simulation = null;
  let selected = null;
  let viewFilter = null; // Set of node keys to show; null = all
  let brushMode = false;
  let currentNodes = []; // last rendered nodes (with x/y), for brush mapping
  let currentLinks = []; // last rendered links (for algorithm runs)
  let algoState = null; // {type:'community', communityOf} or {type:'pagerank', topKeys}
  const positions = new Map(); // node key -> {x, y}, so re-renders keep node placement

  // status line was removed from the stats tab; keep a console trace for debugging
  function setStatus(msg) { if (msg) console.debug('[PhoenixGraph]', msg); }

  // ---- API ----
  async function fetchGraphs() {
    const res = await fetch('/api/graphs');
    if (!res.ok) throw new Error('加载图实例失败: ' + res.status);
    return res.json();
  }

  async function runQuery(query) {
    const res = await fetch('/api/query', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ graphName: currentGraph.name, query, params: {}, returnSubgraph: true }),
    });
    if (!res.ok) {
      throw new Error('查询失败: ' + (await res.text()));
    }
    return res.json();
  }

  // ---- subgraph merge ----
  function merge(subgraph) {
    let addedV = 0;
    let addedE = 0;
    for (const v of (subgraph.vertices || [])) {
      const key = `${v.label}::${v.id}`;
      if (!graph.vertices.has(key)) { graph.vertices.set(key, v); addedV += 1; }
    }
    for (const e of (subgraph.edges || [])) {
      const key = `${e.label}::${e.id}`;
      if (!graph.edges.has(key)) { graph.edges.set(key, e); addedE += 1; }
    }
    return { addedV, addedE };
  }

  // ---- rendering ----
  function ensureSvg() {
    if (svg) return;
    svg = d3.select('#canvas').append('svg').attr('width', '100%').attr('height', '100%');
    root = svg.append('g');

    const defs = svg.append('defs');
    defs.append('marker')
      .attr('id', 'arrow')
      .attr('viewBox', '0 -5 10 10')
      .attr('refX', 15)
      .attr('refY', 0)
      .attr('markerWidth', 5)
      .attr('markerHeight', 5)
      .attr('orient', 'auto')
      .append('path').attr('d', 'M0,-5L10,0L0,5').attr('fill', '#a9b4c6');

    for (const [label, elements] of Object.entries(ICONS)) {
      appendIcon(defs.append('symbol').attr('id', `icon-${label}`).attr('viewBox', '0 0 24 24'), elements);
    }
    appendIcon(defs.append('symbol').attr('id', 'icon-default').attr('viewBox', '0 0 24 24'), DEFAULT_ICON);

    svg.call(d3.zoom()
      .scaleExtent([0.2, 6])
      .filter((event) => {
        if (event.type === 'wheel') return true; // keep wheel zoom
        return !brushMode && !event.ctrlKey && !event.button; // no pan while box-selecting
      })
      .on('zoom', (event) => root.attr('transform', event.transform)));
  }

  function canvasSize() {
    return {
      width: el.canvas.clientWidth || window.innerWidth || 900,
      height: el.canvas.clientHeight || window.innerHeight || 600,
    };
  }

  function render() {
    ensureSvg();
    root.selectAll('*').remove();
    svg.selectAll('.legend').remove();
    svg.selectAll('.brush').remove();
    if (simulation) simulation.stop();

    const { width, height } = canvasSize();

    let nodes = [...graph.vertices.values()].map((v) => {
      const key = `${v.label}::${v.id}`;
      const p = positions.get(key);
      return {
        ...v,
        key,
        x: p ? p.x : width / 2 + (Math.random() - 0.5) * 100,
        y: p ? p.y : height / 2 + (Math.random() - 0.5) * 100,
      };
    });
    const idToKey = new Map();
    for (const n of nodes) {
      if (!idToKey.has(n.id)) idToKey.set(n.id, n.key);
    }
    let links = [...graph.edges.values()].map((e) => ({
      id: e.id, label: e.label,
      source: idToKey.get(e.source) ?? String(e.source),
      target: idToKey.get(e.target) ?? String(e.target),
      properties: e.properties,
      createdTime: e.createdTime,
      lastUpdatedTime: e.lastUpdatedTime,
    }));

    // box-selection filter: keep only selected nodes + edges between them
    if (viewFilter) {
      nodes = nodes.filter((n) => viewFilter.has(n.key));
      links = links.filter((l) => viewFilter.has(l.source) && viewFilter.has(l.target));
    }
    currentNodes = nodes;
    currentLinks = links;

    const linkSel = root.append('g')
      .selectAll('line')
      .data(links)
      .join('line')
      .attr('class', 'edge')
      .attr('marker-end', 'url(#arrow)')
      .on('click', (event, d) => { event.stopPropagation(); selectEdge(d); });

    const nodeSel = root.append('g')
      .selectAll('g')
      .data(nodes)
      .join('g')
      .attr('class', 'node')
      .classed('pr-highlight', (d) => algoState && algoState.type === 'pagerank' && algoState.topKeys.has(d.key))
      .on('click', (event, d) => { event.stopPropagation(); selectVertex(d); })
      .on('dblclick', (event, d) => { event.stopPropagation(); expand(d); });

    nodeSel.append('circle').attr('class', 'ring').attr('r', 14.4);
    nodeSel.append('circle').attr('class', 'body').attr('r', 10.8).attr('fill', (d) => nodeColor(d));
    nodeSel.append('use')
      .attr('href', (d) => iconHref(d.label))
      .attr('x', -5.4).attr('y', -5.4).attr('width', 10.8).attr('height', 10.8);
    nodeSel.append('text')
      .attr('class', 'label')
      .attr('dy', 22)
      .text((d) => String(d.id));

    // Drag a node without re-balancing the whole graph: just move + pin it.
    nodeSel.call(d3.drag()
      .on('start', function (event, d) {
        d.fx = d.x; d.fy = d.y;
        d3.select(this).raise();
      })
      .on('drag', function (event, d) {
        d.fx = event.x; d.fy = event.y;
        d.x = event.x; d.y = event.y;
        d3.select(this).attr('transform', `translate(${event.x},${event.y})`);
        // keep the connected edges attached while dragging
        linkSel.each(function (l) {
          if (l.source === d || l.target === d) {
            d3.select(this)
              .attr('x1', l.source.x).attr('y1', l.source.y)
              .attr('x2', l.target.x).attr('y2', l.target.y);
          }
        });
      })
      .on('end', function (event, d) {
        d.fx = event.x; d.fy = event.y;
      }));

    simulation = d3.forceSimulation(nodes)
      .force('link', d3.forceLink(links).id((d) => d.key).distance(70))
      .force('charge', d3.forceManyBody().strength(-260))
      .force('center', d3.forceCenter(width / 2, height / 2))
      .force('x', d3.forceX(width / 2).strength(0.05))
      .force('y', d3.forceY(height / 2).strength(0.05))
      .force('collide', d3.forceCollide(24))
      .alphaDecay(0.012)   // gentler cooling -> longer, smoother settle
      .velocityDecay(0.55); // more friction -> slower node motion

    simulation.on('tick', () => {
      linkSel
        .attr('x1', (d) => d.source.x)
        .attr('y1', (d) => d.source.y)
        .attr('x2', (d) => d.target.x)
        .attr('y2', (d) => d.target.y);
      nodeSel.attr('transform', (d) => `translate(${d.x},${d.y})`);
      for (const n of nodes) positions.set(n.key, { x: n.x, y: n.y });
    });

    renderLegend();

    applyBrush();

    el.vertexCount.textContent = nodes.length;
    el.edgeCount.textContent = links.length;
    highlight();
  }

  function renderLegend() {
    svg.selectAll('.legend').remove();
    const nodes = currentNodes;
    let items = [];
    if (algoState && algoState.type === 'community') {
      const counts = new Map();
      for (const n of nodes) {
        const cid = algoState.communityOf.get(n.key) ?? 0;
        counts.set(cid, (counts.get(cid) || 0) + 1);
      }
      items = [...counts.entries()].sort((a, b) => a[0] - b[0])
        .map(([cid, cnt]) => ({ color: communityColor(cid), label: `社区 ${cid} · ${cnt} 点` }));
    } else {
      const labels = [...new Set(nodes.map((n) => n.label).filter(Boolean))];
      items = labels.map((label) => ({ color: colorFor(label), label }));
    }
    if (!items.length) return;
    const legend = svg.append('g').attr('class', 'legend').attr('transform', 'translate(16, 60)');
    legend.append('rect')
      .attr('width', 152)
      .attr('height', items.length * 26 + 14)
      .attr('rx', 10)
      .attr('fill', '#ffffff').attr('fill-opacity', 0.95)
      .attr('stroke', '#e4e9f2');
    items.forEach((item, i) => {
      const g = legend.append('g').attr('transform', `translate(11, ${11 + i * 26})`);
      g.append('rect').attr('width', 14).attr('height', 14).attr('rx', 5).attr('fill', item.color);
      g.append('text').attr('x', 22).attr('y', 11.5).text(item.label);
    });
  }

  // ---- algorithm-driven coloring (louvain / cc / pagerank) ----
  function communityColor(cid) {
    const hue = (cid * 137.508) % 360; // golden-angle spacing for distinct hues
    return `hsl(${hue.toFixed(1)}, 62%, 52%)`;
  }

  function nodeColor(d) {
    if (algoState && algoState.type === 'community') {
      const cid = algoState.communityOf.get(d.key);
      return communityColor(cid == null ? 0 : cid);
    }
    return colorFor(d.label);
  }

  function buildAdjacency(nodes, links) {
    const adj = new Map();
    for (const n of nodes) adj.set(n.key, []);
    const keySet = new Set(nodes.map((n) => n.key));
    for (const l of links) {
      const s = typeof l.source === 'object' ? l.source.key : l.source;
      const t = typeof l.target === 'object' ? l.target.key : l.target;
      if (s !== t && keySet.has(s) && keySet.has(t)) {
        adj.get(s).push(t);
        adj.get(t).push(s);
      }
    }
    return adj;
  }

  function connectedComponents(nodes, adj) {
    const comp = new Map();
    const visited = new Set();
    let cid = 0;
    for (const n of nodes) {
      const start = n.key;
      if (visited.has(start)) continue;
      const stack = [start];
      visited.add(start);
      while (stack.length) {
        const k = stack.pop();
        comp.set(k, cid);
        for (const nb of adj.get(k)) {
          if (!visited.has(nb)) { visited.add(nb); stack.push(nb); }
        }
      }
      cid += 1;
    }
    return comp;
  }

  function louvain(nodes, adj) {
    const keys = nodes.map((n) => n.key);
    if (!keys.length) return new Map();
    const cid = new Map();
    const tot = new Map();
    const degree = new Map();
    let m = 0;
    for (const k of keys) {
      const deg = adj.get(k).length;
      degree.set(k, deg);
      cid.set(k, k);
      tot.set(k, deg);
      m += deg;
    }
    m /= 2;
    if (m === 0) {
      const out = new Map();
      keys.forEach((k, i) => out.set(k, i));
      return out;
    }
    let improved = true;
    let passes = 0;
    while (improved && passes < 15) {
      improved = false;
      passes += 1;
      for (const i of keys) {
        const ci = cid.get(i);
        const ki = degree.get(i);
        tot.set(ci, (tot.get(ci) || 0) - ki);
        const kin = new Map();
        for (const nb of adj.get(i)) {
          const c = cid.get(nb);
          kin.set(c, (kin.get(c) || 0) + 1);
        }
        let bestC = ci;
        let bestGain = 0;
        for (const [c, w] of kin) {
          const gain = w / m - (tot.get(c) || 0) * ki / (2 * m * m);
          if (gain > bestGain) { bestGain = gain; bestC = c; }
        }
        cid.set(i, bestC);
        tot.set(bestC, (tot.get(bestC) || 0) + ki);
        if (bestC !== ci) improved = true;
      }
    }
    const mapping = new Map();
    const out = new Map();
    let next = 0;
    for (const k of keys) {
      const c = cid.get(k);
      if (!mapping.has(c)) mapping.set(c, next++);
      out.set(k, mapping.get(c));
    }
    return out;
  }

  function pagerank(nodes, adj) {
    const d = 0.85;
    const N = nodes.length;
    const rank = new Map(nodes.map((n) => [n.key, 1 / N]));
    if (N <= 1) return rank;
    const degree = new Map(nodes.map((n) => [n.key, adj.get(n.key).length]));
    for (let iter = 0; iter < 100; iter += 1) {
      let diff = 0;
      const next = new Map();
      for (const n of nodes) {
        const k = n.key;
        let sum = 0;
        for (const nb of adj.get(k)) {
          const deg = degree.get(nb) || 1;
          sum += rank.get(nb) / deg;
        }
        const val = (1 - d) / N + d * sum;
        next.set(k, val);
        diff += Math.abs(val - rank.get(k));
      }
      for (const n of nodes) rank.set(n.key, next.get(n.key));
      if (diff < 1e-6) break;
    }
    return rank;
  }

  function applyAlgorithm(name) {
    if (!name) {
      algoState = null;
    } else {
      const adj = buildAdjacency(currentNodes, currentLinks);
      if (name === 'cc' || name === 'louvain') {
        const communityOf = name === 'cc' ? connectedComponents(currentNodes, adj) : louvain(currentNodes, adj);
        algoState = { type: 'community', communityOf };
      } else if (name === 'pagerank') {
        const rank = pagerank(currentNodes, adj);
        const sorted = [...rank.entries()].sort((a, b) => b[1] - a[1]);
        const topCount = Math.max(1, Math.ceil(sorted.length * 0.2));
        algoState = { type: 'pagerank', topKeys: new Set(sorted.slice(0, topCount).map((e) => e[0])) };
      }
    }
    refreshAlgoView();
  }

  function refreshAlgoView() {
    if (!root) return;
    root.selectAll('.node').select('circle.body')
      .attr('fill', (d) => nodeColor(d));
    root.selectAll('.node')
      .classed('pr-highlight', (d) => algoState && algoState.type === 'pagerank' && algoState.topKeys.has(d.key));
    renderLegend();
  }

  function clearAlgo() {
    algoState = null;
    el.algoSelect.value = '';
  }

  function applyBrush() {
    svg.selectAll('.brush').remove();
    if (!brushMode) return;
    const { width, height } = canvasSize();
    svg.append('g')
      .attr('class', 'brush')
      .call(d3.brush()
        .extent([[0, 0], [width, height]])
        .on('end', brushed));
  }

  // ---- box selection ----
  function brushed(event) {
    const sel = event.selection;
    if (!sel) return;
    const [[x0, y0], [x1, y1]] = sel;
    const t = d3.zoomTransform(root.node());
    const picked = new Set();
    for (const n of currentNodes) {
      const [sx, sy] = t.apply([n.x, n.y]);
      if (sx >= x0 && sx <= x1 && sy >= y0 && sy <= y1) picked.add(n.key);
    }
    viewFilter = picked.size ? picked : null;
    brushMode = false;
    el.brushBtn.classList.remove('active');
    clearAlgo();
    render();
  }

  // ---- rebalance ----
  function rebalance() {
    if (!simulation) return;
    simulation.nodes().forEach((d) => { d.fx = null; d.fy = null; });
    simulation.alpha(0.6).restart();
  }

  // ---- selection + properties ----
  function selectVertex(d) {
    selected = { type: 'vertex', id: d.id, label: d.label };
    highlight();
    showProps([
      ['类型', d.label],
      ['id', d.id],
      ['createdTime', formatTime(d.createdTime)],
      ['lastUpdatedTime', formatTime(d.lastUpdatedTime)],
      ...Object.entries(d.properties || {}),
    ]);
  }

  function selectEdge(d) {
    selected = { type: 'edge', id: d.id, label: d.label };
    highlight();
    showProps([
      ['类型', d.label],
      ['id', d.id],
      ['source', d.source && d.source.id !== undefined ? d.source.id : d.source],
      ['target', d.target && d.target.id !== undefined ? d.target.id : d.target],
      ['createdTime', formatTime(d.createdTime)],
      ['lastUpdatedTime', formatTime(d.lastUpdatedTime)],
      ...Object.entries(d.properties || {}),
    ]);
  }

  function highlight() {
    root.selectAll('.node').classed('selected', (d) =>
      selected && selected.type === 'vertex' && selected.id === d.id && selected.label === d.label);
    root.selectAll('.edge').classed('selected', (d) =>
      selected && selected.type === 'edge' && selected.id === d.id && selected.label === d.label);
  }

  function showProps(rows) {
    el.propsHint.style.display = 'none';
    const html = rows
      .map(([k, v]) => `<tr><th>${escapeHtml(String(k))}</th><td>${formatValue(v)}</td></tr>`)
      .join('');
    el.propsContent.innerHTML = `<table class="prop-table"><tbody>${html}</tbody></table>`;
  }

  function formatValue(v) {
    if (v === null || v === undefined) return '<span class="muted">—</span>';
    if (typeof v === 'object') return escapeHtml(JSON.stringify(v));
    return escapeHtml(String(v));
  }

  function formatTime(ts) {
    if (!ts) return '—';
    const d = new Date(ts);
    return Number.isNaN(d.getTime()) ? String(ts) : d.toLocaleString();
  }

  function escapeHtml(s) {
    return s.replace(/[&<>"']/g, (c) => (
      { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
    ));
  }

  // ---- double-click expansion: one more hop over every edge type (both) ----
  async function expand(d) {
    const query = `g.V('${d.id}','${d.label}').both()`;
    setStatus(`扩展 ${d.id}: ${query}`);
    try {
      const sub = await runQuery(query);
      const { addedV, addedE } = merge(sub);
      clearAlgo();
      render();
      setStatus(`扩展完成：新增点 ${addedV}、边 ${addedE}（共点 ${graph.vertices.size}、边 ${graph.edges.size}）`);
    } catch (err) {
      setStatus('扩展失败: ' + err.message);
    }
  }

  // ---- query ----
  async function queryCurrent(query) {
    setStatus('查询中…');
    try {
      const sub = await runQuery(query);
      const { addedV, addedE } = merge(sub);
      clearAlgo();
      render();
      setStatus(`查询完成：共点 ${graph.vertices.size}、边 ${graph.edges.size}（新增点 ${addedV}、边 ${addedE}）`);
    } catch (err) {
      setStatus(err.message);
    }
  }

  function onIdQuery() {
    const id = el.vertexIdInput.value.trim();
    if (!id) { setStatus('请输入顶点 ID'); return; }
    const vertexType = el.vertexTypeSelect.value;
    if (!vertexType) { setStatus('当前图没有顶点类型'); return; }
    queryCurrent(`g.V('${id}','${vertexType}')`);
  }

  function onGremlinQuery() {
    const q = el.gremlinInput.value.trim();
    if (!q) { setStatus('请输入 gremlin 查询'); return; }
    queryCurrent(q);
  }

  // ---- init ----
  async function init() {
    const graphs = await fetchGraphs();
    if (!graphs.length) { setStatus('没有可用的图实例'); return; }

    for (const g of graphs) {
      const opt = document.createElement('option');
      opt.value = g.name;
      opt.textContent = g.name;
      el.graphSelect.appendChild(opt);
    }
    onGraphChange(graphs[0]);

    el.graphSelect.addEventListener('change', () => {
      onGraphChange(graphs.find((g) => g.name === el.graphSelect.value));
    });
    el.modeToggle.addEventListener('change', onModeChange);
    el.queryBtn.addEventListener('click', () => {
      if (el.modeToggle.checked) onGremlinQuery();
      else onIdQuery();
    });
    el.vertexIdInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') onIdQuery(); });
    el.gremlinInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) onGremlinQuery();
    });
    el.rebalanceBtn.addEventListener('click', rebalance);
    el.brushBtn.addEventListener('click', () => {
      brushMode = !brushMode;
      el.brushBtn.classList.toggle('active', brushMode);
      applyBrush();
    });
    el.algoSelect.addEventListener('change', () => {
      applyAlgorithm(el.algoSelect.value);
    });
    el.resetViewBtn.addEventListener('click', () => {
      viewFilter = null;
      clearAlgo();
      render();
    });
    document.querySelectorAll('.tab').forEach((t) => t.addEventListener('click', onTabChange));
    window.addEventListener('resize', render);

    render();
  }

  function onGraphChange(g) {
    currentGraph = g;
    graph.vertices.clear();
    graph.edges.clear();
    selected = null;
    viewFilter = null;
    clearAlgo();
    positions.clear();
    el.vertexTypeSelect.innerHTML = '';
    for (const vt of (g.vertexTypes || [])) {
      const opt = document.createElement('option');
      opt.value = vt; opt.textContent = vt;
      el.vertexTypeSelect.appendChild(opt);
    }
    setStatus(`已选择图 ${g.name}（点类型 ${(g.vertexTypes || []).length}、边类型 ${(g.edgeTypes || []).length}）`);
    render();
  }

  function onModeChange() {
    const gremlin = el.modeToggle.checked;
    el.idPanel.hidden = gremlin;
    el.gremlinPanel.hidden = !gremlin;
    if (gremlin) el.gremlinInput.focus();
  }

  function onTabChange(e) {
    document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t === e.target));
    const tab = e.target.dataset.tab;
    document.getElementById('statsPanel').hidden = tab !== 'stats';
    document.getElementById('propsPanel').hidden = tab !== 'props';
  }

  init().catch((err) => { setStatus('初始化失败: ' + err.message); });
})();
