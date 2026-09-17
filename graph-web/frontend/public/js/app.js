(() => {
  'use strict';

  // ---- vertex type styling (Lucide stroke icons + refined palette) ----
  const COLORS = {
    Account: '#4f46e5',
    IP: '#0d9488',
    Phone: '#d97706',
    Entity: '#6366f1',
    Vector: '#7c3aed',
  };
  // Bright fallback palette: unknown vertex types cycle through it instead of all
  // sharing one grey, so a single-type graph still reads as vividly as payment-graph.
  const PALETTE = [
    '#6366f1', '#06b6d4', '#f59e0b', '#ec4899', '#10b981',
    '#8b5cf6', '#ef4444', '#0ea5e9', '#84cc16', '#f97316',
  ];
  // Relation shards: an id ending in "_<n>" (the knowledge graph's
  // floorMod(relation.hashCode(), 10) suffix) gets its own bright colour, which
  // also makes the ten shards visible at a glance.
  const SHARD_COLORS = [
    '#6366f1', '#06b6d4', '#f59e0b', '#ec4899', '#10b981',
    '#8b5cf6', '#ef4444', '#0ea5e9', '#84cc16', '#f97316',
  ];
  const DEFAULT_COLOR = '#6366f1';

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
    Entity: [
      { tag: 'circle', attrs: { cx: 5.5, cy: 5.5, r: 2.5 } },
      { tag: 'circle', attrs: { cx: 18.5, cy: 18.5, r: 2.5 } },
      { tag: 'path', attrs: { d: 'M7.5 7.5 16.5 16.5' } },
    ],
    // a vector: three stacked components
    Vector: [
      { tag: 'circle', attrs: { cx: 12, cy: 6, r: 2 } },
      { tag: 'circle', attrs: { cx: 6, cy: 18, r: 2 } },
      { tag: 'circle', attrs: { cx: 18, cy: 18, r: 2 } },
      { tag: 'path', attrs: { d: 'M12 6 6 18M12 6l6 12M6 18h12' } },
    ],
  };
  const DEFAULT_ICON = [
    { tag: 'path', attrs: { d: 'M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z' } },
  ];

  /**
   * The colour of a vertex: an explicit colour per vertex type, the relation
   * shard encoded in the id ("_<n>"), or a bright colour from the palette picked
   * deterministically from the label so a graph with unknown types stays vivid.
   */
  function colorFor(label, id) {
    if (COLORS[label]) return COLORS[label];
    const shard = shardOf(id);
    if (shard !== null) return SHARD_COLORS[shard % SHARD_COLORS.length];
    const text = String(label || '');
    let hash = 0;
    for (let i = 0; i < text.length; i += 1) hash = (hash * 31 + text.charCodeAt(i)) | 0;
    return PALETTE[Math.abs(hash) % PALETTE.length];
  }

  /** The trailing "_<digit>" of an id (the knowledge graph's relation shard), or null. */
  function shardOf(id) {
    const match = /_(\d)$/.exec(String(id == null ? '' : id));
    return match ? Number(match[1]) : null;
  }
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
      vertexIdPlaceholder: '输入顶点 ID，多个用逗号分隔，如 888, 1000, 1001',
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
      queryErrorTitle: 'Gremlin 查询失败',
      queryErrorHint: '请检查 Gremlin Query 是否写正确',
      errorMessage: '错误信息',
      errorQuery: '出错查询',
      errorStackTrace: '异常堆栈',
      copy: '复制',
      copied: '已复制',
      close: '关闭',
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
      emptyResult: '查询结果为空（未找到该点）',
      displayName: '显示名',
      queryLanguage: '查询语言（Gremlin / Cypher）',
      examples: '示例查询',
      rows: '结果',
      rowsHint: 'Cypher 查询的行结果会显示在这里',
      rowsEmpty: '查询没有返回任何行',
      cypherError: 'Cypher 查询失败',
      valueTitle: '查询结果值',
      valueRows: '行',
      valueItems: '项',
      valueDistinct: '个不同值',
      valueCopy: '复制结果值',
    },
    en: {
      title: 'PhoenixGraph Visualizer',
      graph: 'Graph',
      vertexType: 'Vertex type',
      vertexIdPlaceholder: 'Vertex ID(s), comma separated, e.g. 888, 1000, 1001',
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
      queryErrorTitle: 'Gremlin query failed',
      queryErrorHint: 'Please check that the Gremlin query is written correctly',
      errorMessage: 'Error',
      errorQuery: 'Failing query',
      errorStackTrace: 'Stack trace',
      copy: 'Copy',
      copied: 'Copied',
      close: 'Close',
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
      emptyResult: 'No result (vertex not found)',
      displayName: 'Display name',
      queryLanguage: 'Query language (Gremlin / Cypher)',
      examples: 'Example queries',
      rows: 'Rows',
      rowsHint: 'Rows returned by a Cypher query show up here',
      rowsEmpty: 'The query returned no rows',
      cypherError: 'Cypher query failed',
      valueTitle: 'Query result',
      valueRows: 'row(s)',
      valueItems: 'item(s)',
      valueDistinct: 'distinct',
      valueCopy: 'Copy the value',
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
    languageSelect: document.getElementById('languageSelect'),
    exampleSelect: document.getElementById('exampleSelect'),
    examplesBtn: document.getElementById('examplesBtn'),
    valuePanel: document.getElementById('valuePanel'),
    valueTitle: document.getElementById('valueTitle'),
    valueBody: document.getElementById('valueBody'),
    valueDetail: document.getElementById('valueDetail'),
    valueCopy: document.getElementById('valueCopy'),
    valueClose: document.getElementById('valueClose'),
    rowsHint: document.getElementById('rowsHint'),
    rowsTable: document.getElementById('rowsTable'),
    rowsTableWrap: document.getElementById('rowsTableWrap'),
    rowsPanel: document.getElementById('rowsPanel'),
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
    errorModal: document.getElementById('errorModal'),
    errorMessage: document.getElementById('errorModalMessage'),
    errorQuery: document.getElementById('errorModalQuery'),
    errorQueryRow: document.getElementById('errorModalQueryRow'),
    errorTrace: document.getElementById('errorModalTrace'),
    errorTraceBox: document.getElementById('errorModalTraceBox'),
    errorHint: document.getElementById('errorModalHint'),
    errorCopy: document.getElementById('errorModalCopy'),
  };

  let currentGraph = null;
  let svg = null;
  let root = null;
  let simulation = null;
  let zoomBehavior = null;
  let selected = null;
  let viewFilter = null; // Set of node keys to show; null = all
  let brushMode = false;
  let currentNodes = []; // last rendered nodes (with x/y), for brush mapping
  let currentLinks = []; // last rendered links (for algorithm runs)
  let algoState = null; // {type:'community', communityOf} or {type:'pagerank', topKeys}
  let legendFilter = null; // { mode: 'label'|'community', values: Set<string> }
  let picked = []; // up to two clicked vertex keys (shortest-path endpoints)
  let pathState = null; // { pathNodes, pathEdges, shortestNodes, shortestEdges }
  let displayFields = {}; // vertex label -> display property names (from /api/graphs)

  /**
   * Example queries per language and graph, offered in the picker under the query
   * box. The Cypher ones exercise the supported subset: id matches, both directions,
   * two hops, filters, aggregation and variable-length paths.
   */
  const EXAMPLES = {
    cypher: {
      'payment-graph': [
        ['135 账户本体', "MATCH (a:Account {id:'135'}) RETURN a.id, a.accountType, a.accountStatus, a.firstName, a.lastName"],
        ['135 的出账（一跳）', "MATCH (a:Account {id:'135'})-[p:Payment]->(b:Account) RETURN b.id AS receiver, p.status ORDER BY receiver LIMIT 10"],
        ['按状态聚合出账', "MATCH (a:Account {id:'135'})-[p:Payment]->(b:Account) RETURN p.status AS status, count(*) AS n ORDER BY n DESC"],
        ['135 的双向支付关系', "MATCH (a:Account {id:'135'})-[p:Payment]-(b:Account) RETURN b.id, p.status LIMIT 10"],
        ['两跳：135 → 中间账户 → 目标', "MATCH (a:Account {id:'135'})-[:Payment]->(:Account)-[:Payment]->(c:Account) RETURN DISTINCT c.id LIMIT 10"],
        ['变长路径 1..3 跳', "MATCH (a:Account {id:'135'})-[:Payment*1..3]->(c:Account) RETURN DISTINCT c.id LIMIT 20"],
        ['过滤：活跃对手 + 状态', "MATCH (a:Account {id:'135'})-[p:Payment]->(b:Account) WHERE b.accountStatus = 'ACTIVE' AND p.status = 'SUCCESS' RETURN b.id, b.accountType LIMIT 10"],
        ['参数化模板（${id}）', "MATCH (a:Account {id: '${id}'}) RETURN a.id, a.accountType, a.accountStatus"],
        ['参数化模板（$id）', "MATCH (a:Account {id: $id})-[p:Payment]->(b:Account) RETURN b.id, p.status LIMIT 5"],
        ['聚合：每个账户的入账数', "MATCH (b:Account {id:'1035'})<-[p:Payment]-(a:Account) RETURN a.id AS payer, count(*) AS n ORDER BY n DESC LIMIT 10"],
        ['跨 schema：账户的手机号', "MATCH (a:Account {id:'1000'})-[:AccountToPhone]->(ph:Phone) RETURN ph.cnCode, ph.phoneNumber"],
        ['两段路径的连接（join）', "MATCH (a:Account {id:'1000'})-[:AccountToIP]->(ip:IP), (ip)-[:IPToAccount]->(b:Account) RETURN ip.id AS ip, b.id AS account LIMIT 10"],
      ],
      'kg-entity-graph': [
        ['实体概念书本体', "MATCH (e:Entity {id:'concept_book_new_9'}) RETURN e.name, e.shard"],
        ['按 shard 聚合', "MATCH (e:Entity {id:'concept_book_new_9'})-[r:Entity_Entity]->(x:Entity) RETURN x.shard AS shard, count(*) AS n ORDER BY n DESC"],
        ['关系与反向关系', "MATCH (e:Entity {id:'concept_book_new_9'})-[r:Entity_Entity]->(x:Entity) RETURN r.relation AS relation, r.xrelation AS inverse, x.id LIMIT 10"],
        ['过滤某个关系', "MATCH (e:Entity {id:'concept_mammal_animals_2'})-[r:Entity_Entity]->(x:Entity) WHERE r.relation = 'concept:specializationof' RETURN x.id LIMIT 20"],
        ['两跳同 shard 关系', "MATCH (e:Entity {id:'concept_mammal_animals_2'})-[:Entity_Entity]->(:Entity)-[:Entity_Entity]->(y:Entity) RETURN DISTINCT y.id LIMIT 20"],
      ],
      'hnsw-sift': [
        ['4242 向量本体', "MATCH (v:Vector {id:'4242'}) RETURN v.name, v.bucket, v.category"],
        ['同 bucket 的邻居（属性过滤）', "MATCH (v:Vector {id:'4242'})-[r:vector_0]->(w:Vector) WHERE w.bucket = 3 RETURN w.id, w.name LIMIT 20"],
        ['按 bucket 聚合邻居', "MATCH (v:Vector {id:'4242'})-[r:vector_0]->(w:Vector) RETURN w.bucket AS bucket, count(*) AS n ORDER BY n DESC"],
        ['按 category 聚合邻居', "MATCH (v:Vector {id:'4242'})-[r:vector_0]->(w:Vector) RETURN w.category AS category, count(*) AS n ORDER BY n DESC"],
        ['层 0 的邻居数（计数）', "MATCH (v:Vector {id:'4242'})-[r:vector_0]->(w:Vector) RETURN count(*) AS neighbours"],
      ],
      'hnsw-dynamic': [
        ['300 文档本体', "MATCH (d:Doc {id:'300'}) RETURN d.name"],
        ['300 的层 0 邻居', "MATCH (d:Doc {id:'300'})-[r:embedding_0]->(n:Doc) RETURN n.id, n.name LIMIT 10"],
      ],
    },
    gremlin: {
      'geo-merchants-rocksdb-inline': [
        ['GEO · 1km 内最近的商户', "g.V('1','Merchant').geoNear('{\"lat\":31.2304,\"lon\":121.4737,\"radiusKm\":1}').limit(10)"],
        ['GEO · 500m 内的咖啡店 + 距离', "g.V('1','Merchant').geoNear('{\"lat\":31.2304,\"lon\":121.4737}', 0.5).has('category','coffee').values('name','distanceKm')"],
        ['GEO · 2km 内有多少商户', "g.V('1','Merchant').geoNear('{\"lat\":31.2304,\"lon\":121.4737,\"radiusKm\":2}').count()"],
      ],
      'geo-merchants-rocksdb-edge': [
        ['GEO · 1km 内最近的商户（RocksDB-Edge）', "g.V('1','Merchant').geoNear('{\"lat\":31.2304,\"lon\":121.4737,\"radiusKm\":1}').limit(10)"],
        ['GEO · 300m 内全部商户的距离', "g.V('1','Merchant').geoNear('{\"lat\":31.2304,\"lon\":121.4737}', 0.3).values('distanceKm')"],
      ],
      'payment-graph': [
        ['135 的出账（一跳）', "g.V('135','Account').outE('Payment').limit(10)"],
        ['135 双向两跳', "g.V('135','Account').both('Payment').both('Payment').dedup().limit(50)"],
        ['账户的手机号', "g.V('1000','Account').out('AccountToPhone')"],
      ],
      'kg-entity-graph': [
        ['概念书的全部关系', "g.V('concept_book_new_9','Entity').bothE().limit(20)"],
        ['按关系过滤再跳一跳', "g.V('concept_mammal_animals_2','Entity').bothE().filter(relation.equals('concept:specializationof') || xrelation.equals('concept:specializationof')).otherV()"],
      ],
      'hnsw-sift': [
        ['4242 最近的 20 个向量', "g.V('4242','Vector').nearest(20)"],
        ['带属性过滤的向量检索', "g.V('4242','Vector').nearest(20).has('bucket', 3)"],
      ],
      'hnsw-dynamic': [
        ['300 最近的 5 个向量', "g.V('300','Doc').nearest(5)"],
      ],
    },
  };
  const positions = new Map(); // node key -> {x, y}, so re-renders keep node placement

  const DISPLAY_LABEL_MAX = 22;

  /**
   * The text a node is labelled with: the values of the schema's display
   * properties joined by a space (e.g. "Ada Lovelace", "+86 138-1000"), or the
   * bare id when the vertex type declares none / the values are missing.
   */
  function displayLabel(d) {
    const fields = displayFields[d.label];
    if (fields && fields.length) {
      const props = d.properties || {};
      const parts = [];
      for (const field of fields) {
        const value = props[field];
        if (value === null || value === undefined || value === '') continue;
        parts.push(flattenValue(value));
      }
      if (parts.length) return truncate(parts.join(' '), DISPLAY_LABEL_MAX);
    }
    return String(d.id);
  }

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
      body: JSON.stringify({
        graphName: currentGraph.name,
        query,
        params: {},
        language: currentLanguage(),
        returnSubgraph: true,
        includeResult: true, // the answer (a count, values, rows) next to the graph
      }),
    });
    if (!res.ok) throw await queryError(res, query);
    return res.json();
  }

  /** The selected query language (the picker next to the query box). */
  function currentLanguage() {
    return el.languageSelect && el.languageSelect.value === 'cypher' ? 'cypher' : 'gremlin';
  }

  /**
   * Builds an Error carrying the backend's error payload (message, exception and
   * stack trace), so the UI can show what exactly went wrong with the query.
   */
  async function queryError(res, query) {
    const raw = await res.text();
    let payload = null;
    try {
      payload = raw ? JSON.parse(raw) : null;
    } catch (ignored) {
      payload = null;
    }
    const message = (payload && payload.error) || raw || `HTTP ${res.status}`;
    const error = new Error(message);
    error.detail = {
      status: res.status,
      query,
      message,
      exception: (payload && payload.exception) || null,
      stackTrace: (payload && payload.stackTrace) || [],
      hint: (payload && payload.hint) || null,
    };
    return error;
  }

  /** Shows the failing query, the error message and its stack trace in a modal. */
  function showQueryError(error) {
    if (!el.errorModal) return;
    const detail = (error && error.detail) || { message: String((error && error.message) || error) };
    el.errorMessage.textContent = detail.message || '';
    el.errorQuery.textContent = detail.query || '';
    el.errorQueryRow.hidden = !detail.query;

    const frames = [];
    if (detail.exception) {
      frames.push(detail.message ? `${detail.exception}: ${detail.message}` : detail.exception);
    }
    for (const line of (detail.stackTrace || [])) {
      if (!frames.includes(line)) frames.push(line);
    }
    el.errorTrace.textContent = frames.join('\n');
    el.errorTraceBox.hidden = frames.length === 0;
    el.errorTraceBox.open = false;
    el.errorHint.textContent = detail.hint ? `${t('queryErrorHint')} — ${detail.hint}` : t('queryErrorHint');

    el.errorModal.hidden = false;
    el.errorCopy.focus();
  }

  function closeModal(id) {
    const node = document.getElementById(id);
    if (node) node.hidden = true;
  }

  /** The text of the currently shown error, for the copy button. */
  function errorReport() {
    const lines = [el.errorMessage.textContent];
    if (!el.errorQueryRow.hidden) lines.push('', `${t('errorQuery')}: ${el.errorQuery.textContent}`);
    if (!el.errorTraceBox.hidden) lines.push('', el.errorTrace.textContent);
    return lines.join('\n');
  }

  async function copyText(text) {
    try {
      await navigator.clipboard.writeText(text);
      toast(t('copied'));
    } catch (ignored) {
      toast(t('copied'));
    }
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

    zoomBehavior = d3.zoom()
      .scaleExtent([0.15, 6])
      .filter((event) => {
        if (event.type === 'wheel') return true; // keep wheel zoom
        return !brushMode && !event.ctrlKey && !event.button; // no pan while box-selecting
      })
      .on('zoom', (event) => root.attr('transform', event.transform));
    svg.call(zoomBehavior);
  }

  // zoom/pan so the whole subgraph is centred and fully visible
  function fitView(animate = false) {
    if (!svg || !zoomBehavior || !currentNodes.length) return;
    const { width, height } = canvasSize();
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (const n of currentNodes) {
      if (n.x < minX) minX = n.x;
      if (n.x > maxX) maxX = n.x;
      if (n.y < minY) minY = n.y;
      if (n.y > maxY) maxY = n.y;
    }
    const pad = 72;
    const dx = Math.max(maxX - minX, 1);
    const dy = Math.max(maxY - minY, 1);
    const scale = Math.max(0.15, Math.min((width - pad * 2) / dx, (height - pad * 2) / dy, 2.2));
    const transform = d3.zoomIdentity
      .translate(width / 2 - (scale * (minX + maxX)) / 2, height / 2 - (scale * (minY + maxY)) / 2)
      .scale(scale);
    if (animate) svg.transition().duration(450).call(zoomBehavior.transform, transform);
    else svg.call(zoomBehavior.transform, transform);
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
    let links = [];
    for (const e of graph.edges.values()) {
      const source = idToKey.get(e.source);
      const target = idToKey.get(e.target);
      if (!source || !target) continue; // endpoint is missing from the displayed subgraph
      links.push({
        id: e.id, label: e.label, source, target,
        properties: e.properties,
        createdTime: e.createdTime,
        lastUpdatedTime: e.lastUpdatedTime,
      });
    }

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
      .on('click', onNodeClick);

    nodeSel.append('circle').attr('class', 'ring').attr('r', 14.4);
    nodeSel.append('circle').attr('class', 'body').attr('r', 10.8).attr('fill', (d) => nodeColor(d));
    nodeSel.append('use')
      .attr('href', (d) => iconHref(d.label))
      .attr('x', -5.4).attr('y', -5.4).attr('width', 10.8).attr('height', 10.8);
    nodeSel.append('text')
      .attr('class', 'label')
      .attr('dy', 22)
      .text((d) => displayLabel(d));

    // Drag a node without re-balancing the whole graph: just move + pin it.
    nodeSel.call(d3.drag()
      .on('start', function (event, d) {
        d.fx = d.x; d.fy = d.y;
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

    const big = nodes.length > 1000; // scale down forces/iterations for very large subgraphs
    simulation = d3.forceSimulation(nodes)
      .force('link', d3.forceLink(links).id((d) => d.key).distance(big ? 24 : 70))
      .force('charge', d3.forceManyBody().strength(big ? -55 : -260))
      .force('center', d3.forceCenter(width / 2, height / 2))
      .force('x', d3.forceX(width / 2).strength(0.05))
      .force('y', d3.forceY(height / 2).strength(0.05))
      .velocityDecay(0.55)   // more friction -> slower, gentler motion
      .alphaDecay(0.02)
      .stop();
    if (!big) simulation.force('collide', d3.forceCollide(24));

    // lay out synchronously so the first paint is already settled, then fit to view
    const ticks = big ? 180 : 400;
    for (let i = 0; i < ticks; i += 1) simulation.tick();
    linkSel.each(function (d) { setEdgeGeometry(this, d); });
    nodeSel.attr('transform', (d) => `translate(${d.x},${d.y})`);
    for (const n of nodes) positions.set(n.key, { x: n.x, y: n.y });

    simulation.on('tick', () => {
      linkSel.each(function (d) { setEdgeGeometry(this, d); });
      nodeSel.attr('transform', (d) => `translate(${d.x},${d.y})`);
      for (const n of nodes) positions.set(n.key, { x: n.x, y: n.y });
    });
    simulation.on('end', () => fitView(true));

    renderLegend();

    applyBrush();

    el.vertexCount.textContent = nodes.length;
    el.edgeCount.textContent = links.length;
    highlight();
    applySearch();
    applyLegendFilter();
    applyPathFilter();
    applyPickedHighlight();
    fitView(false);
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

  /** Legend group of a vertex: its type, plus the relation shard when the id carries one. */
  function legendKey(d) {
    const shard = shardOf(d.id);
    return shard === null ? String(d.label) : `${d.label} · #${shard}`;
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
      ? legendFilter.values.has(legendKey(d))
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
    const vRows = [['id', 'label', 'displayName', 'createdTime', 'lastUpdatedTime', 'properties']];
    for (const n of currentNodes) {
      vRows.push([n.id, n.label, displayLabel(n), n.createdTime, n.lastUpdatedTime, n.properties || {}]);
    }
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
      // one entry per legend group: the vertex type, split further by relation shard
      // when the id carries a "_<n>" suffix (so a sharded graph shows its shards)
      const groups = new Map();
      for (const n of nodes) {
        const key = legendKey(n);
        if (!groups.has(key)) groups.set(key, { key, node: n });
      }
      items = [...groups.values()].map((g) => ({
        color: colorFor(g.node.label, g.node.id),
        label: truncate(g.key, 20),
        mode: 'label',
        value: g.key,
      }));
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
    return colorFor(d.label, d.id);
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
    simulation.alpha(0.45).restart();
  }

  // ---- selection + properties ----
  let lastNodeClick = { key: null, at: 0 };

  // single click selects (and toggles a shortest-path pick); a quick second click expands one hop
  function onNodeClick(event, d) {
    event.stopPropagation();
    const now = Date.now();
    if (lastNodeClick.key === d.key && now - lastNodeClick.at < 380) {
      lastNodeClick = { key: null, at: 0 };
      // the first click of this double-click picked the node — undo it so picking
      // two shortest-path endpoints and expanding don't interfere
      if (picked.includes(d.key)) {
        picked = picked.filter((k) => k !== d.key);
        applyPickedHighlight();
      }
      expand(d);
      return;
    }
    lastNodeClick = { key: d.key, at: now };
    selectVertex(d);
  }

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
      [t('displayName'), displayLabel(d)],
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
    activateTab('props'); // selecting an element brings its properties into view
  }

  /** Clears the properties panel back to its placeholder (e.g. after a new query). */
  function clearProps() {
    el.propsContent.innerHTML = '';
    el.propsHint.style.display = '';
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
      showQueryError(err);
    }
  }

  // ---- query ----
  // a new query replaces the canvas content instead of accumulating on top of it
  function resetGraph() {
    graph.vertices.clear();
    graph.edges.clear();
    positions.clear();
    selected = null;
    viewFilter = null;
    legendFilter = null;
    pathState = null;
    picked = [];
    clearProps(); // the previously selected element no longer exists
    clearRows();
    hideValue();
  }

  async function queryCurrent(query) {
    setStatus('查询中…');
    try {
      const result = await runQuery(query);
      // Cypher answers with columns/rows plus the subgraph; Gremlin with the subgraph,
      // optionally next to the traversers it produced (includeResult)
      const subgraph = responseSubgraph(result);
      resetGraph();
      const { addedV, addedE } = merge(subgraph);
      if (result && result.columns) showRows(result.columns, result.rows || []);
      else clearRows();
      const value = responseValue(result);
      showValue(value, result);
      clearAlgo();
      render();
      const empty = !graph.vertices.size && !(result && result.rows && result.rows.length);
      if (empty) toast(t('emptyResult'));
      const rows = result && result.rows ? `、行 ${result.rows.length}` : '';
      setStatus(`查询完成：共点 ${graph.vertices.size}、边 ${graph.edges.size}${rows}（新增点 ${addedV}、边 ${addedE}）`);
    } catch (err) {
      setStatus(err.message);
      showQueryError(err);
    }
  }

  /** The subgraph part of a response (the response itself is one for a plain Gremlin query). */
  function responseSubgraph(result) {
    if (!result) return null;
    if (result.subgraph) return result.subgraph;
    if (result.vertices) return result;
    return { vertices: [], edges: [] };
  }

  /**
   * The value part of a response: the traversers of a Gremlin query, or the
   * columns/rows of a Cypher one. `undefined` when the request did not ask for it.
   */
  function responseValue(result) {
    if (!result) return undefined;
    if (result.columns) return { columns: result.columns, rows: result.rows || [] };
    if (result.result !== undefined) return result.result;
    if (Array.isArray(result)) return result;
    return undefined;
  }

  /**
   * Shows the query's answer in a small floating window: a single number (`count()`)
   * is shown large, a short list verbatim, a long one as a count plus a preview, and a
   * Cypher projection as its row count plus the first row (the rows tab has the rest).
   */
  function showValue(value, result) {
    if (!el.valuePanel) return;
    if (value === undefined) { hideValue(); return; }
    const summary = summarizeValue(value, result);
    el.valueTitle.textContent = t('valueTitle');
    el.valueBody.innerHTML = summary.body;
    el.valueDetail.innerHTML = summary.detail || '';
    el.valuePanel.hidden = false;
    el.valueCopy.textContent = '⧉';
    el.valueCopy.title = t('valueCopy');
  }

/** How many distinct values the window lists before it gives up and shows "…". */
  const VALUE_PREVIEW = 8;

  /** A vertex/edge traverser is shown by its id; anything else by its text. */
  function valueLabel(v) {
    if (v === null || v === undefined) return '—';
    if (typeof v !== 'object') return escapeHtml(String(v));
    if (v.id !== undefined && (v.label || v.type)) return escapeHtml(String(v.id));
    return escapeHtml(JSON.stringify(v));
  }

  /**
   * Renders a list of traversers as the window's body: one value large, several
   * values listed with their count, and repeated values collapsed onto one value
   * with a multiplier (a `values('status')` over 50 edges reads "ACTIVE ×50"
   * rather than "50").
   */
  function summarizeValues(values, unit) {
    if (!values.length) return { body: `0 ${t(unit)}`, detail: '' };
    const labels = values.map(valueLabel);
    const distinct = [...new Set(labels)];
    if (distinct.length === 1) {
      const detail = values.length > 1 ? `${values.length} ${t(unit)}` : '';
      return { body: `<span class="value-scalar">${distinct[0]}</span>`, detail };
    }
    const shown = distinct.slice(0, VALUE_PREVIEW);
    const suffix = distinct.length > VALUE_PREVIEW ? ' …' : '';
    const body = `<span class="value-scalar value-small">${shown.join(' · ')}${suffix}</span>`;
    const detail = `${values.length} ${t(unit)} · ${distinct.length} ${t('valueDistinct')}`;
    return { body, detail };
  }

  /** The window's content for one query answer (Gremlin traversers or Cypher rows). */
  function summarizeValue(value, result) {
    if (value && value.columns) {
      const rows = value.rows;
      const columns = value.columns;
      if (!rows.length) return { body: `0 ${t('valueRows')}`, detail: '' };
      // the first row is the value; the rows tab carries the rest
      const first = rows[0];
      const summary = summarizeValues(rows.length === 1 ? columns.map((c) => first[c]) : [first], 'valueItems');
      const detail = `${rows.length} ${t('valueRows')} · ${escapeHtml(columns.join(', '))}`;
      return { body: summary.body, detail };
    }
    return summarizeValues(Array.isArray(value) ? value : [value], 'valueItems');
  }

  function hideValue() {
    if (el.valuePanel) el.valuePanel.hidden = true;
  }

  /** Renders the rows of a Cypher result as a table in the "rows" tab. */
  function showRows(columns, rows) {
    if (!el.rowsTable) return;
    el.rowsTable.innerHTML = '';
    if (!rows.length) {
      el.rowsTableWrap.hidden = true;
      el.rowsHint.style.display = '';
      el.rowsHint.textContent = t('rowsEmpty');
      return;
    }
    const head = document.createElement('tr');
    for (const column of columns) {
      const th = document.createElement('th');
      th.textContent = column;
      head.appendChild(th);
    }
    el.rowsTable.appendChild(head);
    for (const row of rows) {
      const tr = document.createElement('tr');
      for (const column of columns) {
        const td = document.createElement('td');
        td.innerHTML = formatValue(row[column]);
        tr.appendChild(td);
      }
      el.rowsTable.appendChild(tr);
    }
    el.rowsHint.style.display = 'none';
    el.rowsTableWrap.hidden = false;
    activateTab('rows');
  }

  /** Empties the rows table (a new query, or a Gremlin one). */
  function clearRows() {
    if (!el.rowsTable) return;
    el.rowsTable.innerHTML = '';
    el.rowsTableWrap.hidden = true;
    el.rowsHint.style.display = '';
    el.rowsHint.textContent = t('rowsHint');
  }

  /**
   * Splits the id input into ids: comma / full-width comma / semicolon / whitespace
   * separated, so several seed vertices can be queried at once.
   */
  function parseIds(raw) {
    return String(raw == null ? '' : raw)
      .split(/[,，;；\s]+/)
      .map((id) => id.trim())
      .filter(Boolean);
  }

  function onIdQuery() {
    const ids = parseIds(el.vertexIdInput.value);
    if (!ids.length) { setStatus(t('enterId')); return; }
    const vertexType = el.vertexTypeSelect.value;
    if (!vertexType) { setStatus(t('noVertexType')); return; }
    const quoted = ids.map((id) => `'${id}'`).join(',');
    setStatus(`${ids.length} seed id(s): ${quoted}`);
    queryCurrent(`g.V(${quoted},'${vertexType}').both().both()`);
  }

  function onGremlinQuery() {
    const q = el.gremlinInput.value.trim();
    if (!q) { setStatus(t('enterGremlin')); return; }
    queryCurrent(q);
  }

  /** Fills the query box with an example query of the selected language and graph. */
  function refreshExamples() {
    if (!el.exampleSelect) return;
    const language = currentLanguage();
    const byGraph = (EXAMPLES[language] || {})[currentGraph ? currentGraph.name : ''] || [];
    el.exampleSelect.innerHTML = '';
    const placeholder = document.createElement('option');
    placeholder.value = '';
    placeholder.textContent = t('examples');
    el.exampleSelect.appendChild(placeholder);
    for (const [title, query] of byGraph) {
      const option = document.createElement('option');
      option.value = query;
      option.textContent = title;
      el.exampleSelect.appendChild(option);
    }
    el.exampleSelect.hidden = byGraph.length === 0;
  }

  /** Switching the language also swaps the placeholder and the example list. */
  function onLanguageChange() {
    const cypher = currentLanguage() === 'cypher';
    el.gremlinInput.placeholder = cypher
      ? "MATCH (a:Account {id:'135'})-[p:Payment]->(b:Account) RETURN b.id, p.status LIMIT 10"
      : "g.V(888,'Account').batch(40).both('Payment').both('Payment')";
    el.gremlinInput.classList.toggle('cypher', cypher);
    clearRows();
    refreshExamples();
    hideExamples();
  }

  /** The example picker only shows up when the little button asks for it. */
  function toggleExamples() {
    if (!el.exampleSelect) return;
    if (el.exampleSelect.hidden) {
      el.exampleSelect.hidden = false;
      el.examplesBtn.classList.add('active');
      el.examplesBtn.setAttribute('aria-expanded', 'true');
      el.exampleSelect.focus();
    } else {
      hideExamples();
    }
  }

  function hideExamples() {
    if (!el.exampleSelect) return;
    el.exampleSelect.hidden = true;
    el.examplesBtn.classList.remove('active');
    el.examplesBtn.setAttribute('aria-expanded', 'false');
  }

  // ---- init ----
  async function init() {
    const graphs = await fetchGraphs();
    if (!graphs.length) { setStatus(t('noGraph')); return; }

    for (const g of graphs) {
      const opt = document.createElement('option');
      opt.value = g.name;
      // the storage implementation is part of the identity of an instance: the same graph
      // can be served by Aerospike, RocksDB-Inline or RocksDB-Edge at the same time
      opt.textContent = g.storage && g.storage !== 'Aerospike'
        ? g.name + ' · ' + g.storage
        : g.name;
      el.graphSelect.appendChild(opt);
    }
    onGraphChange(graphs[0]);

    el.graphSelect.addEventListener('change', () => {
      onGraphChange(graphs.find((g) => g.name === el.graphSelect.value));
    });
    el.modeToggle.addEventListener('change', onModeChange);
    el.languageSelect.addEventListener('change', onLanguageChange);
    el.examplesBtn.addEventListener('click', toggleExamples);
    el.exampleSelect.addEventListener('change', () => {
      const query = el.exampleSelect.value;
      if (!query) return;
      el.gremlinInput.value = query;
      autoGrowGremlin();
      el.gremlinInput.focus();
      hideExamples(); // the picker got out of the way again
    });
    el.valueClose.addEventListener('click', hideValue);
    el.valueCopy.addEventListener('click', async () => {
      await copyText(el.valueBody.textContent || '');
      el.valueCopy.textContent = '✓';
    });
    el.queryBtn.addEventListener('click', () => {
      if (el.modeToggle.checked) onGremlinQuery();
      else onIdQuery();
    });
    el.vertexIdInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') onIdQuery(); });
    el.gremlinInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) onGremlinQuery();
    });
    el.gremlinInput.addEventListener('input', autoGrowGremlin); // grow as lines are added
    onLanguageChange();
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
    document.querySelectorAll('[data-close]').forEach((node) => {
      node.addEventListener('click', () => closeModal(node.dataset.close));
    });
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape' && el.errorModal && !el.errorModal.hidden) {
        closeModal('errorModal');
      }
    });
    if (el.errorCopy) {
      el.errorCopy.addEventListener('click', () => copyText(errorReport()));
    }
    window.addEventListener('resize', render);

    applyI18n();
    render();
  }

  function onGraphChange(g) {
    currentGraph = g;
    displayFields = g.displayProperties || {};
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
    clearRows();
    refreshExamples();
    hideExamples();
    setStatus(`已选择图 ${g.name}（点类型 ${(g.vertexTypes || []).length}、边类型 ${(g.edgeTypes || []).length}）`);
    render();
  }

  function onModeChange() {
    const gremlin = el.modeToggle.checked;
    el.idPanel.hidden = gremlin;
    el.gremlinPanel.hidden = !gremlin;
    if (gremlin) { autoGrowGremlin(); el.gremlinInput.focus(); }
  }

  // grow the Gremlin textarea with its content (Enter / new lines) up to the CSS max-height
  function autoGrowGremlin() {
    const ta = el.gremlinInput;
    if (!ta) return;
    ta.style.height = 'auto';
    ta.style.height = `${Math.min(ta.scrollHeight + 2, 280)}px`;
  }

  function onTabChange(e) {
    activateTab(e.target.dataset.tab);
  }

  /** Shows the side panel for the given tab ('stats' or 'props'). */
  function activateTab(tab) {
    document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.dataset.tab === tab));
    document.getElementById('statsPanel').hidden = tab !== 'stats';
    document.getElementById('propsPanel').hidden = tab !== 'props';
    document.getElementById('rowsPanel').hidden = tab !== 'rows';
  }

  init().catch((err) => { setStatus('初始化失败: ' + err.message); });
})();
