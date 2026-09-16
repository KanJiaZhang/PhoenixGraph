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

  // ---- i18n (zh / en) ----
  const I18N = {
    zh: {
      title: 'PhoenixGraph 可视化',
      graph: '图实例',
      vertexType: '顶点类型',
      vertexIdPlaceholder: '输入顶点 ID，如 888',
      gremlinToggle: '切换 Gremlin 查询输入框',
      query: '查询',
      rebalance: '自动平衡力图',
      brush: '框选过滤',
      showAll: '显示全部',
      algorithm: '算法',
      cc: '连通分量',
      searchPlaceholder: '搜索点 / 边关键词',
      clearSearch: '清除搜索',
      stats: '统计',
      props: '属性',
      vertices: '顶点',
      edges: '边',
      propsHint: '点击图中某点或某边查看其全部属性',
      type: '类型',
      community: '社区',
      count: '点',
      enterId: '请输入顶点 ID',
      noVertexType: '当前图没有顶点类型',
      enterGremlin: '请输入 gremlin 查询',
      noGraph: '没有可用的图实例',
      shortestPath: '最短路径',
      exportCsv: '导出 CSV',
      needTwo: '请先点击两个点，再选择「最短路径」',
      pickedTwo: '已选 2 个点，选择「最短路径」查看',
      exported: '已导出',
      noPath: '两点之间没有路径',
    },
    en: {
      title: 'PhoenixGraph Visualizer',
      graph: 'Graph',
      vertexType: 'Vertex type',
      vertexIdPlaceholder: 'Vertex ID, e.g. 888',
      gremlinToggle: 'Toggle Gremlin query input',
      query: 'Query',
      rebalance: 'Auto rebalance',
      brush: 'Box selection',
      showAll: 'Show all',
      algorithm: 'Algorithm',
      cc: 'Components',
      searchPlaceholder: 'Search vertices / edges',
      clearSearch: 'Clear search',
      stats: 'Stats',
      props: 'Properties',
      vertices: 'Vertices',
      edges: 'Edges',
      propsHint: 'Click a vertex or edge to view all its properties',
      type: 'Type',
      community: 'Community',
      count: 'vertices',
      enterId: 'Enter a vertex ID',
      noVertexType: 'This graph has no vertex types',
      enterGremlin: 'Enter a gremlin query',
      noGraph: 'No graph available',
      shortestPath: 'Shortest path',
      exportCsv: 'Export CSV',
      needTwo: 'Pick two vertices first, then choose Shortest path',
      pickedTwo: '2 vertices picked — choose Shortest path',
      exported: 'Exported',
      noPath: 'No path between the two vertices',
    },
  };
  let lang = localStorage.getItem('pg.lang') === 'en' ? 'en' : 'zh';
  const t = (key) => (I18N[lang] && I18N[lang][key]) || I18N.zh[key] || key;

  function applyI18n() {
    document.documentElement.lang = lang === 'zh' ? 'zh-CN' : 'en';
    document.title = t('title');
    document.querySelectorAll('[data-i18n]').forEach((n) => { n.textContent = t(n.dataset.i18n); });
    document.querySelectorAll('[data-i18n-title]').forEach((n) => { n.title = t(n.dataset.i18nTitle); });
    document.querySelectorAll('[data-i18n-placeholder]').forEach((n) => { n.placeholder = t(n.dataset.i18nPlaceholder); });
    document.querySelectorAll('.lang-opt').forEach((n) => n.classList.toggle('active', n.dataset.lang === lang));
    renderLegend();
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
    searchInput: document.getElementById('searchInput'),
    searchClear: document.getElementById('searchClear'),
    toast: document.getElementById('toast'),
    exportBtn: document.getElementById('exportBtn'),
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
  let legendFilter = null; // { mode: 'label'|'community', values: Set<string> }
  let picked = []; // up to two clicked vertex keys (shortest-path endpoints)
  let pathState = null; // { pathNodes, pathEdges, shortestNodes, shortestEdges }
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
    const addMarker = (id, color) => {
      defs.append('marker')
        .attr('id', id)
        .attr('viewBox', '0 0 10 10')
        .attr('refX', 8.6)
        .attr('refY', 5)
        .attr('markerWidth', 7)
        .attr('markerHeight', 7)
        .attr('orient', 'auto')
        .append('path')
        .attr('d', 'M1.5 1.9 L8.6 5 L1.5 8.1') // soft chevron head
        .attr('fill', 'none')
        .attr('stroke', color)
        .attr('stroke-width', 2)
        .attr('stroke-linecap', 'round')
        .attr('stroke-linejoin', 'round');
    };
    addMarker('arrow', '#9aa6ba');
    addMarker('arrow-hit', '#a78bfa');
    addMarker('arrow-shortest', '#22c55e');

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
          if (l.source === d || l.target === d) setEdgeGeometry(this, l);
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
      linkSel.each(function (d) { setEdgeGeometry(this, d); });
      nodeSel.attr('transform', (d) => `translate(${d.x},${d.y})`);
      for (const n of nodes) positions.set(n.key, { x: n.x, y: n.y });
    });

    renderLegend();

    applyBrush();

    el.vertexCount.textContent = nodes.length;
    el.edgeCount.textContent = links.length;
    highlight();
    applySearch();
    applyLegendFilter();
    applyPathFilter();
    applyPickedHighlight();
  }

  // stop each line at the node edge so the arrow head stays visible above the node
  const EDGE_NODE_GAP = 13;
  function setEdgeGeometry(el, l) {
    const x1 = l.source.x, y1 = l.source.y, x2 = l.target.x, y2 = l.target.y;
    const dx = x2 - x1, dy = y2 - y1;
    const len = Math.hypot(dx, dy) || 1;
    const off = Math.max(0, Math.min(EDGE_NODE_GAP, len / 2 - 0.5));
    const ux = dx / len, uy = dy / len;
    el.setAttribute('x1', x1 + ux * off);
    el.setAttribute('y1', y1 + uy * off);
    el.setAttribute('x2', x2 - ux * off);
    el.setAttribute('y2', y2 - uy * off);
  }

  // ---- legend filter (vertex type or community) ----
  function toggleLegendFilter(mode, value) {
    const key = String(value);
    if (!legendFilter || legendFilter.mode !== mode) legendFilter = { mode, values: new Set() };
    if (legendFilter.values.has(key)) legendFilter.values.delete(key);
    else legendFilter.values.add(key);
    if (!legendFilter.values.size) legendFilter = null;
    renderLegend();
    applyLegendFilter();
  }

  function endpointLabel(endpoint) {
    return endpoint && typeof endpoint === 'object' ? endpoint.label : null;
  }

  function applyLegendFilter() {
    if (!root) return;
    const nodes = root.selectAll('.node');
    const edges = root.selectAll('.edge');
    if (!legendFilter) {
      nodes.classed('legend-hit', false).classed('legend-dim', false);
      edges.classed('legend-hit', false).classed('legend-dim', false);
      return;
    }
    const communityOf = algoState && algoState.type === 'community' ? algoState.communityOf : null;
    const hit = (d) => (legendFilter.mode === 'label'
      ? legendFilter.values.has(d.label)
      : !!communityOf && legendFilter.values.has(String(communityOf.get(d.key))));
    const keepEdge = (d) => hit(d.source) && hit(d.target);
    nodes.classed('legend-hit', hit).classed('legend-dim', (d) => !hit(d));
    edges.classed('legend-hit', keepEdge).classed('legend-dim', (d) => !keepEdge(d));
  }

  function applyPickedHighlight() {
    if (!root) return;
    root.selectAll('.node').classed('picked', (d) => picked.includes(d.key));
  }

  function truncate(text, max) {
    return text.length > max ? `${text.slice(0, max - 1).trimEnd()}…` : text;
  }

  // ---- transient toast ----
  let toastTimer = null;
  function toast(message, ms = 2600) {
    if (!el.toast) return;
    el.toast.textContent = message;
    el.toast.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.toast.classList.remove('show'), ms);
  }

  // ---- shortest path between two picked vertices ----
  const pairKey = (u, v) => (u < v ? `${u}|${v}` : `${v}|${u}`);

  function buildPathGraph(nodes, links) {
    const adj = new Map();
    const keySet = new Set(nodes.map((n) => n.key));
    for (const n of nodes) adj.set(n.key, []);
    for (const l of links) {
      const s = endpointKey(l.source);
      const e = endpointKey(l.target);
      if (!keySet.has(s) || !keySet.has(e) || s === e) continue;
      const edge = `${l.label}::${l.id}`;
      adj.get(s).push({ to: e, edge });
      adj.get(e).push({ to: s, edge });
    }
    return adj;
  }

  function bfsDistance(adj, from) {
    const dist = new Map([[from, 0]]);
    const queue = [from];
    for (let i = 0; i < queue.length; i += 1) {
      for (const { to } of adj.get(queue[i]) || []) {
        if (!dist.has(to)) { dist.set(to, dist.get(queue[i]) + 1); queue.push(to); }
      }
    }
    return dist;
  }

  // enumerate simple paths up to maxLen (bounded by cap) — small, safe caps
  function enumeratePaths(adj, from, to, maxLen, cap) {
    const paths = [];
    const path = [];
    const seen = new Set();
    const edgeTo = new Map();
    const walk = (u, depth) => {
      if (paths.length >= cap) return;
      path.push(u);
      if (u === to) {
        paths.push({
          nodes: path.slice(),
          edges: path.slice(1).map((v, i) => edgeTo.get(pairKey(path[i], v))),
        });
        path.pop();
        return;
      }
      if (depth >= maxLen) { path.pop(); return; }
      seen.add(u);
      for (const { to: v, edge } of adj.get(u) || []) {
        if (seen.has(v)) continue;
        edgeTo.set(pairKey(u, v), edge);
        walk(v, depth + 1);
        if (paths.length >= cap) break;
      }
      seen.delete(u);
      path.pop();
    };
    walk(from, 0);
    return paths;
  }

  function computeShortestPath() {
    pathState = null;
    if (picked.length < 2) { toast(t('needTwo')); return; }
    const [a, b] = picked;
    const adj = buildPathGraph(currentNodes, currentLinks);
    const dist = adj.has(a) ? bfsDistance(adj, a) : new Map();
    if (!dist.has(b)) { toast(t('noPath')); return; }
    const shortest = dist.get(b);
    const paths = enumeratePaths(adj, a, b, shortest + 2, 400);
    if (!paths.length) { toast(t('noPath')); return; }
    const pathNodes = new Set();
    const pathEdges = new Set();
    const shortestNodes = new Set();
    const shortestEdges = new Set();
    for (const p of paths) {
      const isShortest = p.nodes.length - 1 === shortest;
      for (const nk of p.nodes) { pathNodes.add(nk); if (isShortest) shortestNodes.add(nk); }
      for (const ek of p.edges) { if (ek) { pathEdges.add(ek); if (isShortest) shortestEdges.add(ek); } }
    }
    pathState = { pathNodes, pathEdges, shortestNodes, shortestEdges };
    toast(`${t('shortestPath')}: ${shortest} hop · ${paths.length} paths`);
  }

  function applyPathFilter() {
    if (!root) return;
    const nodes = root.selectAll('.node');
    const edges = root.selectAll('.edge');
    if (!pathState) {
      nodes.classed('path-hit', false).classed('path-shortest', false).classed('path-dim', false);
      edges.classed('path-hit', false).classed('path-shortest', false).classed('path-dim', false);
      return;
    }
    const { pathNodes, pathEdges, shortestNodes, shortestEdges } = pathState;
    const edgeIdOf = (d) => `${d.label}::${d.id}`;
    nodes
      .classed('path-shortest', (d) => shortestNodes.has(d.key))
      .classed('path-hit', (d) => !shortestNodes.has(d.key) && pathNodes.has(d.key))
      .classed('path-dim', (d) => !pathNodes.has(d.key));
    edges
      .classed('path-shortest', (d) => shortestEdges.has(edgeIdOf(d)))
      .classed('path-hit', (d) => !shortestEdges.has(edgeIdOf(d)) && pathEdges.has(edgeIdOf(d)))
      .classed('path-dim', (d) => !pathEdges.has(edgeIdOf(d)));
  }

  // ---- CSV export ----
  function csvCell(value) {
    const s = value === null || value === undefined ? ''
      : (typeof value === 'object' ? JSON.stringify(value) : String(value));
    return `"${s.replace(/"/g, '""')}"`;
  }

  function toCsv(rows) { return rows.map((r) => r.map(csvCell).join(',')).join('\r\n'); }

  function downloadCsv(filename, content) {
    const blob = new Blob(['\ufeff' + content], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  function endpointId(endpoint) {
    if (endpoint && typeof endpoint === 'object') return endpoint.id;
    return typeof endpoint === 'string' && endpoint.includes('::') ? endpoint.split('::')[1] : endpoint;
  }

  function exportCsv() {
    const vRows = [['id', 'label', 'createdTime', 'lastUpdatedTime', 'properties']];
    for (const n of currentNodes) vRows.push([n.id, n.label, n.createdTime, n.lastUpdatedTime, n.properties || {}]);
    const eRows = [['id', 'label', 'source', 'target', 'createdTime', 'lastUpdatedTime', 'properties']];
    for (const l of currentLinks) {
      eRows.push([l.id, l.label, endpointId(l.source), endpointId(l.target),
        l.createdTime, l.lastUpdatedTime, l.properties || {}]);
    }
    downloadCsv('phoenixgraph-vertices.csv', toCsv(vRows));
    downloadCsv('phoenixgraph-edges.csv', toCsv(eRows));
    toast(`${t('exported')}: ${currentNodes.length} ${t('vertices')} · ${currentLinks.length} ${t('edges')}`);
  }

  function renderLegend() {
    if (!svg) return;
    svg.selectAll('.legend').remove();
    const nodes = currentNodes;
    const communityOf = algoState && algoState.type === 'community' ? algoState.communityOf : null;
    let items = [];
    if (communityOf) {
      const counts = new Map();
      for (const n of nodes) {
        const cid = communityOf.get(n.key) ?? 0;
        counts.set(cid, (counts.get(cid) || 0) + 1);
      }
      items = [...counts.entries()].sort((a, b) => a[0] - b[0]).map(([cid, cnt]) => ({
        color: communityColor(cid),
        label: truncate(`${t('community')} ${cid} · ${cnt} ${t('count')}`, 20),
        mode: 'community',
        value: cid,
      }));
    } else {
      const labels = [...new Set(nodes.map((n) => n.label).filter(Boolean))];
      items = labels.map((label) => ({ color: colorFor(label), label, mode: 'label', value: label }));
    }
    if (!items.length) return;
    const legend = svg.append('g').attr('class', 'legend').attr('transform', 'translate(14, 60)');
    legend.append('rect')
      .attr('width', 158)
      .attr('height', items.length * 26 + 14)
      .attr('rx', 11)
      .attr('fill', '#ffffff').attr('fill-opacity', 0.95)
      .attr('stroke', '#e4e9f2');
    items.forEach((item, i) => {
      const active = !!legendFilter && legendFilter.mode === item.mode
        && legendFilter.values.has(String(item.value));
      const g = legend.append('g')
        .attr('class', 'legend-item')
        .attr('transform', `translate(9, ${9 + i * 26})`)
        .classed('active', active);
      g.on('click', (event) => { event.stopPropagation(); toggleLegendFilter(item.mode, item.value); });
      g.on('mousedown', (event) => event.stopPropagation()); // don't start a canvas pan
      g.append('rect').attr('class', 'bg').attr('width', 140).attr('height', 22).attr('rx', 7);
      g.append('rect').attr('class', 'swatch')
        .attr('x', 3).attr('y', 4).attr('width', 14).attr('height', 14).attr('rx', 5).attr('fill', item.color);
      g.append('text').attr('x', 25).attr('y', 15).text(item.label);
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
    pathState = null;
    if (!name) {
      algoState = null;
    } else if (name === 'shortestpath') {
      algoState = null;
      computeShortestPath();
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
    applyLegendFilter();
    applyPathFilter();
    applyPickedHighlight();
    renderLegend();
  }

  function clearAlgo() {
    algoState = null;
    pathState = null;
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
    const pickedKeys = new Set();
    for (const n of currentNodes) {
      const [sx, sy] = t.apply([n.x, n.y]);
      if (sx >= x0 && sx <= x1 && sy >= y0 && sy <= y1) pickedKeys.add(n.key);
    }
    viewFilter = pickedKeys.size ? pickedKeys : null;
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
    if (picked.includes(d.key)) picked = picked.filter((k) => k !== d.key);
    else picked = picked.length >= 2 ? [d.key] : [...picked, d.key];
    if (picked.length === 2) toast(t('pickedTwo'));
    highlight();
    applyPickedHighlight();
    showProps([
      [t('type'), d.label],
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
      [t('type'), d.label],
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

  // ---- keyword search (fuzzy) over the displayed subgraph ----
  function flattenValue(v) {
    if (v === null || v === undefined) return '';
    if (Array.isArray(v)) return v.map(flattenValue).join(' ');
    if (typeof v === 'object') return Object.values(v).map(flattenValue).join(' ');
    return String(v);
  }

  // case-insensitive subsequence match, so "pmt" matches "payment" (fuzzy)
  function fuzzyMatch(query, text) {
    let qi = 0;
    for (let i = 0; i < text.length && qi < query.length; i += 1) {
      if (text.charCodeAt(i) === query.charCodeAt(qi)) qi += 1;
    }
    return qi === query.length;
  }

  function endpointKey(endpoint) {
    return endpoint && typeof endpoint === 'object' ? endpoint.key : endpoint;
  }

  function elementHaystack(d, extra) {
    if (d.__hay === undefined) {
      const parts = [d.label, d.id, ...extra];
      for (const [key, value] of Object.entries(d.properties || {})) parts.push(key, flattenValue(value));
      d.__hay = parts.filter(Boolean).join(' ').toLowerCase();
    }
    return d.__hay;
  }

  function applySearch() {
    const query = el.searchInput.value.trim().toLowerCase();
    el.searchClear.hidden = !query;
    const nodes = root.selectAll('.node');
    const edges = root.selectAll('.edge');
    if (!query) {
      nodes.classed('search-hit', false).classed('search-dim', false);
      edges.classed('search-hit', false).classed('search-dim', false);
      return;
    }
    const tokens = query.split(/\s+/).filter(Boolean);
    const hits = (hay) => tokens.every((token) => fuzzyMatch(token, hay));

    const hitNodes = new Set();
    nodes.each(function (d) { if (hits(elementHaystack(d, []))) hitNodes.add(d.key); });

    const hitEdges = new Set();
    const adjacent = new Set(); // endpoints of matched edges stay visible
    edges.each(function (d) {
      const key = d.label + '::' + d.id;
      if (hits(elementHaystack(d, [endpointKey(d.source), endpointKey(d.target)]))) {
        hitEdges.add(key);
        const s = endpointKey(d.source);
        const t = endpointKey(d.target);
        if (s) adjacent.add(s);
        if (t) adjacent.add(t);
      }
    });

    nodes
      .classed('search-hit', (d) => hitNodes.has(d.key))
      .classed('search-dim', (d) => !hitNodes.has(d.key) && !adjacent.has(d.key));
    edges
      .classed('search-hit', (d) => hitEdges.has(d.label + '::' + d.id))
      .classed('search-dim', (d) => !hitEdges.has(d.label + '::' + d.id));
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
    if (!id) { setStatus(t('enterId')); return; }
    const vertexType = el.vertexTypeSelect.value;
    if (!vertexType) { setStatus(t('noVertexType')); return; }
    queryCurrent(`g.V('${id}','${vertexType}').both().both()`);
  }

  function onGremlinQuery() {
    const q = el.gremlinInput.value.trim();
    if (!q) { setStatus(t('enterGremlin')); return; }
    queryCurrent(q);
  }

  // ---- init ----
  async function init() {
    const graphs = await fetchGraphs();
    if (!graphs.length) { setStatus(t('noGraph')); return; }

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
    el.exportBtn.addEventListener('click', exportCsv);
    el.searchInput.addEventListener('input', applySearch);
    el.searchInput.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') { el.searchInput.value = ''; applySearch(); }
    });
    el.searchClear.addEventListener('click', () => {
      el.searchInput.value = '';
      applySearch();
      el.searchInput.focus();
    });
    el.resetViewBtn.addEventListener('click', () => {
      viewFilter = null;
      clearAlgo();
      render();
    });
    document.querySelectorAll('.lang-opt').forEach((node) => {
      node.addEventListener('click', () => {
        lang = node.dataset.lang === 'en' ? 'en' : 'zh';
        localStorage.setItem('pg.lang', lang);
        applyI18n();
      });
    });
    document.querySelectorAll('.tab').forEach((t) => t.addEventListener('click', onTabChange));
    window.addEventListener('resize', render);

    applyI18n();
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
