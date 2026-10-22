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

  /** A stable hash of a string, for the places a value must not jump between renders. */
  function hashOf(text) {
    let hash = 0;
    for (let i = 0; i < text.length; i += 1) hash = (hash * 31 + text.charCodeAt(i)) | 0;
    return Math.abs(hash) % 100000;
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
      vertexType: '顶点',
      builder: '图管理 · 建模',
      vertexIdPlaceholder: '输入顶点 ID，多个用逗号分隔，如 888, 1000, 1001',
      gremlinToggle: '切换 Gremlin 查询输入框',
      scriptMode: '脚本',
      scriptToggle: 'Groovy 脚本模式（def 变量 / print / 多条语句）',
      gremlinScriptPlaceholder: "def ids = g.V(1001,'Account').values('id').toList()\nprint(ids)",
      query: '查询',
      rebalance: '自动平衡力图',
      brush: '框选过滤',
      showAll: '显示全部',
      algorithm: '算法',
      cc: '连通分量',
      louvain: 'Louvain',
      pagerank: 'PageRank',
      degree: '度中心性',
      closeness: '接近中心性',
      betweenness: '介数中心性',
      eigenvector: '特征向量中心性',
      algoScore: '{algo}：同类型的点上就地计算，分数越高颜色越暖；最高的前 20% 加金色虚线环（{n} 个点里 {top} 个，最高：{best}）',
      mapTitle: '地图（带经纬度的实体）',
      mapOpenTitle: '关闭地图，回到图可视化',
      mapBack: '返回图可视化',
      mapLoading: '正在读取经纬度…',
      mapEmpty: '这张图里没有带经纬度的实体',
      mapNoGeo: '这个图实例没有经纬度属性，也没有整类扫描（按键读取的存储），所以没有地图',
      mapFailed: '地图数据加载失败：',
      mapPointsQuery: '本次查询返回的 {n} 个带经纬度的点',
      mapPointsGraph: '图里全部 {n} 个带经纬度的实体',
      mapShowAll: '显示全部',
      mapShowQuery: '只看查询结果',
      mapBasemap: '底图：{provider}',
      mapBasemapFallback: '底图：{provider}（OpenStreetMap 不可达）；点位已由 WGS84 换算到 GCJ-02',
      mapBasemapNone: '底图瓦片都不可达，已用素色背景代替；点位、缩放与弹窗照常可用',
      mapNoLeaflet: '地图库没有加载（/vendor/leaflet/leaflet.js）',
      mapPlace: '都城/城市',
      mapEvent: '战役/事件',
      mapEntity: '实体',
      mapYear: '（{from}–{to}年）',
      mapYearSingle: '（{from}年）',
      treeLayout: '树形 / DAG 布局',
      treeOff: '布局',
      treeGroup: '树形（生成树）',
      dagGroup: 'DAG 分层（保留全部边）',
      treeLr: '树 · 左→右',
      treeRl: '树 · 右→左',
      treeTb: '树 · 上→下',
      treeBt: '树 · 下→上',
      treeRadial: '树 · 放射',
      dagLr: 'DAG · 左→右',
      dagRl: 'DAG · 右→左',
      dagTb: 'DAG · 上→下',
      dagBt: 'DAG · 下→上',
      treeHint: '{mode}：{n} 个点 / {e} 条边，根是「{root}」—— 点任意点可换根（双击仍是展开一跳）',
      dagHint: '{mode}：{n} 个点 / {e} 条边（含全部边，虚线 {r} 条是为断开环而反向的）—— 分层按最长路径，层内按重心排序',
      view3d: '3D 视图',
      view3dOpenTitle: '关闭 3D，回到平面图',
      view3dBack: '返回图可视化',
      view3dHint: '3D：{n} 个点 / {e} 条边 · 颜色与高亮跟 2D 一致 · 拖动旋转、滚轮缩放 · 单击选中（树/DAG 下换根）、双击展开一跳',
      algoHomogeneous: '该图混了多种点（另有 {others}），{algo} 只在最多的 {type}（{n} 个点）上运算；其它类型的点被投影成 {type} 之间的 {links} 条边',
      projectedEdge: '投影边',
      searchPlaceholder: '搜索点 / 边关键词',
      searchNoMatch: '当前画布上没有匹配「{q}」的点或边（搜索只作用于画布内）',
      clearSearch: '清除搜索',
      stats: '统计',
      props: '属性',
      metrics: '指标',
      metricsHint: '正在读取系统指标…',
      metricsRefresh: '刷新',
      metricsEmpty: '还没有任何指标：先查一次，或切换到一个用过的图',
      metricsStorage: '存储读写',
      metricsQuality: '写入质量',
      metricsQueries: '查询',
      metricsLatency: '延迟',
      metricsTotal: '累计',
      metricsToday: '今天',
      metricsItems: '条数',
      metricsFailed: '失败',
      metricsSource: '来源',
      metricsLanguage: '语言',
      metricsTranslated: '翻译成 KV 查询',
      metricsVertexRead: '读点',
      metricsVertexWrite: '写点',
      metricsEdgeRead: '读边',
      metricsEdgeWrite: '写边',
      metricsKeyValue: 'KV 往返',
      metricsEvicted: '边溢出',
      metricsStaleVertex: '点更新 stale',
      metricsStaleEdge: '边更新 stale',
      metricsRetries: '重试',
      metricsDeletes: '删除',
      metricsCount: '次数',
      metricsMean: '平均',
      metricsMax: '最大',
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
      ingest: '文档入库',
      ingestTitle: '把文档变成知识图谱',
      ingestHint: '支持 Markdown / TXT / HTML。PDF 请用 tools/ingest-docs.mjs（依赖 pdftotext）或命令行入库。',
      ingestGraph: '新图名称',
      ingestRulePack: '抽取规则包',
      ingestFiles: '文档文件',
      ingestDictionary: '实体字典（可选 JSON）',
      ingestAliases: '别名映射（可选 JSON）',
      ingestSubmit: '开始入库',
      ingestRunning: '正在抽取并写入…',
      ingestDone: '入库完成',
      ingestNoFiles: '请选择至少一个文本文件',
      ingestNoGraph: '请填写图名称',
      ingestBadJson: '字典或别名不是合法 JSON',
      ingestFailed: '入库失败',
      type: '类型',
      community: '社区',
      count: '点',
      legendSelected: '图例选中',
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
      valueClipHint: '值太长，点击展开全文',
      valueClipCollapse: '点击收起',
      valueTooLong: '…（后面还有 {n} 个字符，展开也只显示前 20000 个）',
      displayName: '显示名',
      queryLanguage: '查询语言（当前只有 Gremlin；Cypher 暂时隐藏）',
      examples: '示例查询',
      fuzzyExample: '模糊关联示例（模式 → 团伙 → 出款 → 收款方）',
      rows: '结果',
      rowsHint: '查询结果（元素列表 / 值 / 行）会显示在这里',
      rowsValue: '值',
      rowsType: '类型',
      rowsId: 'ID',
      rowsProperties: '属性',
      typeCount: '数量',
      rowsEmpty: '查询没有返回任何行',
      rowsTruncated: '表格只显示前 {shown} 行，共 {total} 行（用 limit() 缩小结果）',
      rowsDuplicates: '{n} 行，其中 {d} 行与前面的行是同一个元素（同一条边被两个端点各输出一次；用 dedup() 去重）',
      responseTooLarge: '响应过大，未完整传回（已接收 {size}）。请加 limit() 或缩小查询范围。',
      responseCapped: '结果超过 {n} 项，已截断；请用 limit() 或更小的范围。',
      canvasCleared: '查询失败，画布已清空（避免把上一次的结果当成这次的结果）',
      cypherError: 'Cypher 查询失败',
      valueTitle: '查询结果值',
      valueRows: '行',
      valueItems: '项',
      valueDistinct: '个不同值',
      hubBounded: '该点是聚合大点，已只读取最近的 {n} 条边',
      valueCopy: '复制结果值',
    },
    en: {
      title: 'PhoenixGraph Visualizer',
      graph: 'Graph',
      vertexType: 'Vertex',
      builder: 'Graph builder',
      vertexIdPlaceholder: 'Vertex ID(s), comma separated, e.g. 888, 1000, 1001',
      gremlinToggle: 'Toggle Gremlin query input',
      scriptMode: 'Script',
      scriptToggle: 'Groovy script mode (def variables / print / several statements)',
      gremlinScriptPlaceholder: "def ids = g.V(1001,'Account').values('id').toList()\nprint(ids)",
      query: 'Query',
      rebalance: 'Auto rebalance',
      brush: 'Box selection',
      showAll: 'Show all',
      algorithm: 'Algorithm',
      cc: 'Components',
      louvain: 'Louvain',
      pagerank: 'PageRank',
      degree: 'Degree',
      closeness: 'Closeness',
      betweenness: 'Betweenness',
      eigenvector: 'Eigenvector',
      algoScore: '{algo}: computed in place on the vertices of one type - the warmer the colour the higher the score, and the top {top} of {n} carries the gold ring (highest: {best})',
      mapTitle: 'Map (entities with coordinates)',
      mapOpenTitle: 'Close the map and go back to the graph',
      mapBack: 'Back to the graph',
      mapLoading: 'Reading the coordinates…',
      mapEmpty: 'This graph has no entity with coordinates',
      mapNoGeo: 'This instance has no latitude/longitude properties and its store cannot scan a whole type, so it has no map',
      mapFailed: 'Loading the map data failed: ',
      mapPointsQuery: 'the {n} points with coordinates this query returned',
      mapPointsGraph: 'all {n} entities of the graph that carry coordinates',
      mapShowAll: 'Show all',
      mapShowQuery: 'Only the query result',
      mapBasemap: 'Basemap: {provider}',
      mapBasemapFallback: 'Basemap: {provider} (OpenStreetMap unreachable); points converted from WGS84 to GCJ-02',
      mapBasemapNone: 'No basemap tile was reachable - plain background instead; points, zoom and popups still work',
      mapNoLeaflet: 'The map library did not load (/vendor/leaflet/leaflet.js)',
      mapPlace: 'Capital / city',
      mapEvent: 'Battle / event',
      mapEntity: 'Entity',
      mapYear: ' ({from}-{to} AD)',
      mapYearSingle: ' ({from} AD)',
      treeLayout: 'Tree / DAG layout',
      treeOff: 'Layout',
      treeGroup: 'Tree (spanning)',
      dagGroup: 'Layered DAG (every edge kept)',
      treeLr: 'Tree · left to right',
      treeRl: 'Tree · right to left',
      treeTb: 'Tree · top to bottom',
      treeBt: 'Tree · bottom to top',
      treeRadial: 'Tree · radial',
      dagLr: 'DAG · left to right',
      dagRl: 'DAG · right to left',
      dagTb: 'DAG · top to bottom',
      dagBt: 'DAG · bottom to top',
      treeHint: '{mode}: {n} vertices / {e} edges, rooted at "{root}" - click any vertex to re-root (a double click still expands one hop)',
      dagHint: '{mode}: {n} vertices / {e} edges (every edge kept; {r} drawn dashed because it runs backwards to break a cycle) - ranks by longest path, orders each layer by barycenter',
      view3d: '3D view',
      view3dOpenTitle: 'Leave 3D and go back to the flat graph',
      view3dBack: 'Back to the graph',
      view3dHint: '3D: {n} vertices / {e} edges · colours and highlights match 2D · drag to rotate, wheel to zoom · click to select (re-roots in a tree/DAG), double click expands one hop',
      algoHomogeneous: 'The graph mixes vertex types ({others}), so {algo} ran on the largest type only - {type} ({n} vertices) - with the other types projected into {links} edges between them',
      projectedEdge: 'projected edge',
      searchPlaceholder: 'Search vertices / edges',
      searchNoMatch: 'Nothing on the canvas matches "{q}" (the search only covers what is drawn)',
      clearSearch: 'Clear search',
      stats: 'Stats',
      props: 'Properties',
      metrics: 'Metrics',
      metricsHint: 'Reading system metrics…',
      metricsRefresh: 'Refresh',
      metricsEmpty: 'Nothing recorded yet: run a query, or pick a graph that has been used',
      metricsStorage: 'Storage traffic',
      metricsQuality: 'Write quality',
      metricsQueries: 'Queries',
      metricsLatency: 'Latency',
      metricsTotal: 'total',
      metricsToday: 'today',
      metricsItems: 'items',
      metricsFailed: 'failed',
      metricsSource: 'source',
      metricsLanguage: 'language',
      metricsTranslated: 'translated to KV queries',
      metricsVertexRead: 'vertex reads',
      metricsVertexWrite: 'vertex writes',
      metricsEdgeRead: 'edge reads',
      metricsEdgeWrite: 'edge writes',
      metricsKeyValue: 'key-value trips',
      metricsEvicted: 'edge evictions',
      metricsStaleVertex: 'stale vertex updates',
      metricsStaleEdge: 'stale edge updates',
      metricsRetries: 'retries',
      metricsDeletes: 'deletes',
      metricsCount: 'count',
      metricsMean: 'mean',
      metricsMax: 'max',
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
      ingest: 'Ingest documents',
      ingestTitle: 'Turn documents into a knowledge graph',
      ingestHint: 'Markdown / TXT / HTML. For PDF use tools/ingest-docs.mjs (pdftotext) or the CLI.',
      ingestGraph: 'New graph name',
      ingestRulePack: 'Rule pack',
      ingestFiles: 'Document files',
      ingestDictionary: 'Entity dictionary (optional JSON)',
      ingestAliases: 'Alias map (optional JSON)',
      ingestSubmit: 'Ingest',
      ingestRunning: 'Extracting and writing…',
      ingestDone: 'Ingestion finished',
      ingestNoFiles: 'Choose at least one text file',
      ingestNoGraph: 'Enter a graph name',
      ingestBadJson: 'The dictionary or alias map is not valid JSON',
      ingestFailed: 'Ingestion failed',
      type: 'Type',
      community: 'Community',
      count: 'vertices',
      legendSelected: 'Legend',
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
      valueClipHint: 'Long value - click to read it in full',
      valueClipCollapse: 'Click to collapse',
      valueTooLong: '... ({n} more characters; expanding shows the first 20000)',
      displayName: 'Display name',
      queryLanguage: 'Query language (Gremlin only for now; Cypher is hidden)',
      examples: 'Example queries',
      fuzzyExample: 'Fuzzy linking example (pattern -> ring -> payments -> receiver)',
      rows: 'Rows',
      rowsHint: 'The answer - a list of elements, values or rows - shows up here',
      rowsValue: 'Value',
      rowsType: 'Type',
      rowsId: 'ID',
      rowsProperties: 'Properties',
      typeCount: 'Count',
      rowsEmpty: 'The query returned no rows',
      rowsTruncated: 'The table shows the first {shown} of {total} rows - add a limit() to narrow it',
      rowsDuplicates: '{n} rows, {d} of which repeat an element listed above (an edge both of its endpoints produced: add dedup() to collapse them)',
      responseTooLarge: 'The response was too large to transfer intact (received {size}). Add a limit() or narrow the query.',
      responseCapped: 'The answer was capped at {n} items - add a limit() or narrow the query.',
      canvasCleared: 'The query failed, so the canvas was cleared (the previous answer is not this one)',
      cypherError: 'Cypher query failed',
      valueTitle: 'Query result',
      valueRows: 'row(s)',
      valueItems: 'item(s)',
      valueDistinct: 'distinct',
      hubBounded: 'This vertex is a hub whose edges live in the aggregated tables; only its newest {n} edges were read',
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
    document.querySelectorAll('[data-i18n-aria-label]').forEach((n) => { n.setAttribute('aria-label', t(n.dataset.i18nAriaLabel)); });
    document.querySelectorAll('[data-i18n-placeholder]').forEach((n) => { n.placeholder = t(n.dataset.i18nPlaceholder); });
    // an <optgroup> has no text node to set: its label is an attribute, so the layout menu used to stay
    // in Chinese under an English UI (the two groups were the only untranslated labels left)
    document.querySelectorAll('[data-i18n-label]').forEach((n) => { n.label = t(n.dataset.i18nLabel); });
    document.querySelectorAll('.lang-opt').forEach((n) => n.classList.toggle('active', n.dataset.lang === lang));
    renderLegend();
    refreshExamples(); // the example titles are per language too
  }

  // merged graph model, keyed by "label::id"
  const graph = { vertices: new Map(), edges: new Map() };

  const el = {
    graphSelect: document.getElementById('graphSelect'),
    vertexTypeSelect: document.getElementById('vertexTypeSelect'),
    vertexIdInput: document.getElementById('vertexIdInput'),
    gremlinInput: document.getElementById('gremlinInput'),
    scriptToggle: document.getElementById('scriptToggle'),
    scriptToggleWrap: document.getElementById('scriptToggleWrap'),
    idPanel: document.getElementById('idPanel'),
    gremlinPanel: document.getElementById('gremlinPanel'),
    modeToggle: document.getElementById('modeToggle'),
    languageSelect: document.getElementById('languageSelect'),
    exampleSelect: document.getElementById('exampleSelect'),
    examplesBtn: document.getElementById('examplesBtn'),
    fuzzyBtn: document.getElementById('fuzzyBtn'),
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
    treeSelect: document.getElementById('treeSelect'),
    searchInput: document.getElementById('searchInput'),
    searchClear: document.getElementById('searchClear'),
    legendCount: document.getElementById('legendCount'),
    toast: document.getElementById('toast'),
    exportBtn: document.getElementById('exportBtn'),
    canvas: document.getElementById('canvas'),
    canvasWrap: document.getElementById('canvasWrap'),
    mapBtn: document.getElementById('mapBtn'),
    mapView: document.getElementById('mapView'),
    mapNote: document.getElementById('mapNote'),
    mapClose: document.getElementById('mapClose'),
    view3d: document.getElementById('view3d'),
    view3dBtn: document.getElementById('view3dBtn'),
    view3dClose: document.getElementById('view3dClose'),
    vertexCount: document.getElementById('vertexCount'),
    edgeCount: document.getElementById('edgeCount'),
    typeBreakdown: document.getElementById('typeBreakdown'),
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
    ingestBtn: document.getElementById('ingestBtn'),
    ingestModal: document.getElementById('ingestModal'),
    ingestGraph: document.getElementById('ingestGraph'),
    ingestRulePack: document.getElementById('ingestRulePack'),
    ingestFiles: document.getElementById('ingestFiles'),
    ingestDictionary: document.getElementById('ingestDictionary'),
    ingestAliases: document.getElementById('ingestAliases'),
    ingestSubmit: document.getElementById('ingestSubmit'),
    ingestStatus: document.getElementById('ingestStatus'),
    ingestResult: document.getElementById('ingestResult'),
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
  /** The tree layout of the canvas: '' (force layout), 'lr', 'tb', 'bt' or 'radial'. */
  let treeMode = '';
  /** The vertex the tree grows from (null: the first drawn one, or the first picked). */
  let treeRootKey = null;
  let legendFilter = null; // { mode: 'label'|'community', values: Set<string> }
  let picked = []; // up to two clicked vertex keys (shortest-path endpoints)
  let pathState = null; // { pathNodes, pathEdges, shortestNodes, shortestEdges }
  let displayFields = {}; // vertex label -> display property names (from /api/graphs)
  let rowsReady = false;  // whether a Cypher query already produced rows (the tab appears then)

  /**
   * Example queries per language and graph, offered in the picker under the query
   * box. The Cypher ones exercise the supported subset: id matches, both directions,
   * two hops, filters, aggregation and variable-length paths.
   */
  // The fuzzy example button is hidden until the feature settles: the endpoint it uses
  // (GET /api/fuzzy/demo) and the pattern examples stay, only the button is off.
  const SHOW_FUZZY_EXAMPLE = false;

  // The Groovy script mode of the query box: statements, variables and print over the same
  // traversals. It is meant for administrators - IS_ADMIN is the gate and SHOW_SCRIPT_MODE the
  // feature flag, and both are open while the feature settles. Flipping IS_ADMIN to false hides
  // the toggle for everyone (the backend still auto-detects a script from its text).
  const IS_ADMIN = true;
  const SHOW_SCRIPT_MODE = IS_ADMIN;

  const EXAMPLES = {
    cypher: {
      'payment-graph': [
        ['135 账户本体', 'Account 135 itself', "MATCH (a:Account {id:'135'}) RETURN a.id, a.accountType, a.accountStatus, a.firstName, a.lastName"],
        ['135 的出账（一跳）', '135\'s outgoing payments (one hop)', "MATCH (a:Account {id:'135'})-[p:Payment]->(b:Account) RETURN b.id AS receiver, p.status ORDER BY receiver LIMIT 10"],
        ['按状态聚合出账', 'Outgoing payments by status', "MATCH (a:Account {id:'135'})-[p:Payment]->(b:Account) RETURN p.status AS status, count(*) AS n ORDER BY n DESC"],
        ['135 的双向支付关系', '135 and its payment partners (both directions)', "MATCH (a:Account {id:'135'})-[p:Payment]-(b:Account) RETURN b.id, p.status LIMIT 10"],
        ['两跳：135 → 中间账户 → 目标', 'Two hops: 135 -> middle account -> target', "MATCH (a:Account {id:'135'})-[:Payment]->(:Account)-[:Payment]->(c:Account) RETURN DISTINCT c.id LIMIT 10"],
        ['变长路径 1..3 跳', 'Variable-length path, 1..3 hops', "MATCH (a:Account {id:'135'})-[:Payment*1..3]->(c:Account) RETURN DISTINCT c.id LIMIT 20"],
        ['过滤：活跃对手 + 状态', 'Filter: active counterparty + status', "MATCH (a:Account {id:'135'})-[p:Payment]->(b:Account) WHERE b.accountStatus = 'ACTIVE' AND p.status = 'SUCCESS' RETURN b.id, b.accountType LIMIT 10"],
        ['参数化模板（${id}）', 'Parameterised template (${id})', "MATCH (a:Account {id: '${id}'}) RETURN a.id, a.accountType, a.accountStatus"],
        ['参数化模板（$id）', 'Parameterised template ($id)', "MATCH (a:Account {id: $id})-[p:Payment]->(b:Account) RETURN b.id, p.status LIMIT 5"],
        ['聚合：每个账户的入账数', 'Aggregate: incoming payments per account', "MATCH (b:Account {id:'1035'})<-[p:Payment]-(a:Account) RETURN a.id AS payer, count(*) AS n ORDER BY n DESC LIMIT 10"],
        ['跨 schema：账户的手机号', 'Cross-schema: an account\'s phone', "MATCH (ph:Phone)-[:PhoneToAccount]->(a:Account {id:'1000'}) RETURN ph.cnCode, ph.phoneNumber"],
        ['两段路径的连接（join）', 'Joining two paths', "MATCH (a:Account {id:'1000'})<-[:IPToAccount]-(ip:IP), (ip)-[:IPToAccount]->(b:Account) RETURN ip.id AS ip, b.id AS account LIMIT 10"],
      ],
      'kg-entity-graph': [
        ['实体概念书本体', 'Entity concept_book_new_9 itself', "MATCH (e:Entity {id:'concept_book_new_9'}) RETURN e.name, e.shard"],
        ['按 shard 聚合', 'Neighbours by shard', "MATCH (e:Entity {id:'concept_book_new_9'})-[r:Entity_Entity]->(x:Entity) RETURN x.shard AS shard, count(*) AS n ORDER BY n DESC"],
        ['关系与反向关系', 'Relation and its inverse', "MATCH (e:Entity {id:'concept_book_new_9'})-[r:Entity_Entity]->(x:Entity) RETURN r.relation AS relation, r.xrelation AS inverse, x.id LIMIT 10"],
        ['过滤某个关系', 'Filter by one relation', "MATCH (e:Entity {id:'concept_mammal_animals_2'})-[r:Entity_Entity]->(x:Entity) WHERE r.relation = 'concept:specializationof' RETURN x.id LIMIT 20"],
        ['两跳同 shard 关系', 'Two hops within shards', "MATCH (e:Entity {id:'concept_mammal_animals_2'})-[:Entity_Entity]->(:Entity)-[:Entity_Entity]->(y:Entity) RETURN DISTINCT y.id LIMIT 20"],
      ],
      'sanguo-kg': [
        ['人物本体（曹操）', 'Person vertex (Cao Cao)', "MATCH (e:Entity {id:'曹操_3'}) RETURN e.name, e.entityType, e.gender, e.courtesyName"],
        ['出边关系与中文关系名', 'Outgoing relations with the Chinese label', "MATCH (e:Entity {id:'曹操_3'})-[r:Entity_Entity]->(x:Entity) RETURN r.relation, r.relationLabel, x.name LIMIT 20"],
        ['按关系过滤：曹操的主公关系', 'Filter by relation: Cao Cao as lord', "MATCH (e:Entity {id:'曹操_3'})-[r:Entity_Entity]->(x:Entity) WHERE r.relation = 'lord' RETURN x.name AS subject, r.relationLabel AS relation LIMIT 20"],
        ['反向关系：谁效力曹操', 'Inverse: who serves Cao Cao', "MATCH (e:Entity {id:'曹操_9'})<-[r:Entity_Entity]-(x:Entity) WHERE r.relation = 'minister' RETURN x.name LIMIT 20"],
        ['按关系家族聚合', 'Aggregate by relation family', "MATCH (e:Entity {id:'曹操_3'})-[r:Entity_Entity]->(x:Entity) RETURN r.relationFamily AS family, count(*) AS n ORDER BY n DESC"],
        ['两跳：曹嵩 → 曹操 → 曹昂', 'Two hops: grandfather to grandson', "MATCH (a:Entity {id:'曹嵩_8'})-[:Entity_Entity]->(b:Entity)-[:Entity_Entity]->(c:Entity) RETURN DISTINCT b.name AS son, c.name AS grandson LIMIT 20"],
        ['出场章回（诸葛亮）', 'Chapters Zhuge Liang appears in', "MATCH (p:Entity {id:'诸葛亮_3'})-[r:Entity_Entity]->(c:Entity) WHERE r.relation = 'appearsIn' RETURN c.name LIMIT 25"],
        ['谁效力魏', 'Who serves the state of Wei', "MATCH (p:Entity)-[r:Entity_Entity]->(f:Entity {id:'魏_8'}) WHERE r.relation = 'serves' RETURN p.name, p.entityType LIMIT 20"],
        ['曹操的同族（亲属家族）', 'Cao Cao\'s clan (kindship family)', "MATCH (e:Entity {id:'曹操_5'})-[r:Entity_Entity]->(x:Entity) WHERE r.relationFamily = '亲属' RETURN r.relationLabel AS relation, x.name LIMIT 20"],
        ['曹操的子女（亲属家族）', 'Cao Cao\'s children (kindship family)', "MATCH (e:Entity {id:'曹操_6'})<-[r:Entity_Entity]-(x:Entity) WHERE r.relationFamily = '亲属' RETURN r.relationLabel AS relation, x.name LIMIT 20"],
      ],
      'sanguo-kg-text': [
        ['方案 C：谁是五虎上将', 'Plan C: who are the Five Tiger Generals', "MATCH (p:Entity)-[r:Entity_Entity]->(f:Entity {id:'五虎上将'}) WHERE r.relation='holds' RETURN p.name AS general"],
        ['方案 C：谁是五虎将之首', 'Plan C: who leads the Five Tiger Generals', "MATCH (p:Entity)-[r:Entity_Entity]->(f:Entity {id:'五虎上将之首'}) WHERE r.relation='holds' RETURN p.name"],
        ['方案 C：刘备的军师（文本抽取）', 'Plan C: Liu Bei\'s strategists (text-extracted)', "MATCH (l:Entity {id:'刘备'})<-[r:Entity_Entity]-(p:Entity) WHERE r.relation='strategistOf' RETURN p.name AS strategist"],
        ['方案 C：关羽斩杀过谁', 'Plan C: whom Guan Yu killed', "MATCH (p:Entity {id:'关羽'})-[r:Entity_Entity]->(x:Entity) WHERE r.relation='killed' RETURN x.name"],
        ['方案 C：谁斩杀了关羽', 'Plan C: who killed Guan Yu', "MATCH (p:Entity {id:'关羽'})<-[r:Entity_Entity]-(x:Entity) WHERE r.relation='killed' RETURN x.name AS killer"],
        ['方案 C：关羽的传记提到了谁', 'Plan C: who Guan Yu\'s biography mentions', "MATCH (p:Entity {id:'关羽'})-[r:Entity_Entity]->(x:Entity) WHERE r.relation='mentions' RETURN x.name LIMIT 12"],
        ['方案 ③：关羽提到哪些事件', 'Plan C-3: which events Guan Yu names', "MATCH (p:Entity {id:'关羽'})-[r:Entity_Entity]->(e:Entity) WHERE r.relation='mentions' AND e.entityType='Event' RETURN e.name"],
        ['方案 ③：谁提到过五关斩六将', 'Plan C-3: who names the five passes', "MATCH (p:Entity)-[r:Entity_Entity]->(e:Entity {id:'过五关斩六将'}) WHERE r.relation='mentions' RETURN p.name"],
      ],
      'sanguo-kg-rocksdb-edge': [
        ['人物本体（属性）', 'Person vertex with its attributes', "MATCH (e:Entity {id:'曹操'}) RETURN e.name, e.entityType, e.gender, e.courtesyName"],
        ['出边关系与中文关系名', 'Outgoing relations with the Chinese label', "MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) RETURN r.relation, r.relationLabel, x.name LIMIT 20"],
        ['按关系过滤：曹操的主公关系', 'Filter by relation: Cao Cao as lord', "MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) WHERE r.relation = 'lord' RETURN x.name AS subject, r.relationLabel AS relation LIMIT 20"],
        ['反向：马谡的主公（用 xrelation）', 'Inverse: Ma Su\'s lord (via xrelation)', "MATCH (e:Entity {id:'马谡'})-[r:Entity_Entity]->(x:Entity) WHERE r.xrelation = 'lord' RETURN x.name AS lord, r.relationLabel AS relation"],
        ['按关系家族聚合', 'Aggregate by relation family', "MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) RETURN r.relationFamily AS family, count(*) AS n ORDER BY n DESC"],
        ['两跳邻居：曹嵩 → 曹操 → X', 'Two hops from Cao Song through Cao Cao', "MATCH (a:Entity {id:'曹嵩'})-[:Entity_Entity]->(b:Entity)-[:Entity_Entity]->(c:Entity) RETURN DISTINCT b.name AS middle, c.name AS twoHop LIMIT 20"],
        ['出场章回（诸葛亮）', 'Chapters Zhuge Liang appears in', "MATCH (p:Entity {id:'诸葛亮'})-[r:Entity_Entity]->(c:Entity) WHERE r.relation = 'appearsIn' RETURN c.name LIMIT 25"],
        ['谁效力魏（跨类型：人物 → 势力）', 'Who serves Wei (person -> faction)', "MATCH (p:Entity)-[r:Entity_Entity]->(f:Entity {id:'魏'}) WHERE r.relation = 'serves' RETURN p.name, p.entityType LIMIT 20"],
        ['两个实体之间有什么关系', 'What relates two entities', "MATCH (a:Entity {id:'刘备'})-[r:Entity_Entity]-(b:Entity {id:'诸葛亮'}) RETURN r.relation AS relation, r.relationLabel AS label"],
        ['曹操的子女（亲属家族）', 'Cao Cao\'s children (kindship family)', "MATCH (e:Entity {id:'曹操'})<-[r:Entity_Entity]-(x:Entity) WHERE r.relationFamily = '亲属' RETURN r.relationLabel AS relation, x.name LIMIT 20"],
        ['两次：指定关系链 刘备-主公-诸葛亮-师傅-姜维', 'Two hops: a named relation chain', "MATCH (a:Entity {id:'刘备'})-[r1:Entity_Entity]->(b:Entity)-[r2:Entity_Entity]->(c:Entity) WHERE r1.relation='lord' AND r2.relation='master' RETURN b.name AS mid, c.name AS end"],
        ['两次：共同邻居（刘备与曹操）', 'Two hops: a common neighbour', "MATCH (a:Entity {id:'刘备'})-[:Entity_Entity]-(m:Entity)-[:Entity_Entity]-(b:Entity {id:'曹操'}) RETURN DISTINCT m.name"],
        ['两次：同势力同僚（徐庶）', 'Two hops: colleagues in the same faction', "MATCH (a:Entity {id:'徐庶'})-[r1:Entity_Entity]->(f:Entity)<-[r2:Entity_Entity]-(b:Entity) WHERE r1.relation='serves' AND r2.relation='serves' RETURN count(DISTINCT b.name) AS colleagues"],
        ['两次：同回出场（诸葛亮）', 'Two hops: co-appearance in a chapter', "MATCH (a:Entity {id:'诸葛亮'})-[r1:Entity_Entity]->(ch:Entity)<-[r2:Entity_Entity]-(b:Entity) WHERE r1.relation='appearsIn' AND r2.relation='appearsIn' RETURN count(DISTINCT b.name) AS sameChapter"],
        ['两次：路径强度 Top10', 'Two hops: paths per entity (top 10)', "MATCH (a:Entity {id:'刘备'})-[:Entity_Entity]-(b:Entity)-[:Entity_Entity]-(c:Entity) RETURN c.name AS name, count(*) AS paths ORDER BY paths DESC LIMIT 10"],
        ['三次：刘备三跳到曹操的路径数', 'Three hops: walks from Liu Bei to Cao Cao', "MATCH (a:Entity {id:'刘备'})-[:Entity_Entity]-(b:Entity)-[:Entity_Entity]-(c:Entity)-[:Entity_Entity]-(d:Entity {id:'曹操'}) RETURN count(*) AS paths"],
        ['三次：祖孙三代（司马懿→司马昭→司马炎）', 'Three hops: three generations', "MATCH (a:Entity {id:'司马懿'})<-[r1:Entity_Entity]-(s:Entity)<-[r2:Entity_Entity]-(g:Entity) WHERE r1.relation='son' AND r2.relation='son' RETURN DISTINCT g.name"],
        ['问答：关羽和曹操是什么关系？', 'Q: how are Guan Yu and Cao Cao related?', "MATCH (a:Entity {id:'关羽'})-[r1:Entity_Entity]->(f:Entity)<-[r2:Entity_Entity]-(b:Entity {id:'曹操'}) WHERE r1.relation='serves' AND r2.relation='serves' RETURN DISTINCT f.name"],
        ['问答：刘备和曹操是什么关系？', 'Q: how are Liu Bei and Cao Cao related?', "MATCH (a:Entity {id:'刘备'})-[r1:Entity_Entity]->(p:Entity)<-[r2:Entity_Entity]-(b:Entity {id:'曹操'}) WHERE r1.relation='lord' AND r2.relation='lord' RETURN DISTINCT p.name"],
        ['问答：刘备手下谁是从曹操那过来的？', 'Q: who under Liu Bei came from Cao Cao?', "MATCH (a:Entity {id:'曹操'})-[r1:Entity_Entity]->(p:Entity)<-[r2:Entity_Entity]-(b:Entity {id:'刘备'}) WHERE r1.relation='lord' AND r2.relation='lord' RETURN DISTINCT p.name AS defector"],
        ['问答：曹操的同族有谁？', 'Q: who is in Cao Cao\'s clan?', "MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]-(x:Entity) WHERE r.relation='same_clan' RETURN DISTINCT x.name"],
        ['问答：诸葛亮的哥哥在哪个国家？', 'Q: which state is Zhuge Liang\'s brother in?', "MATCH (e:Entity {id:'诸葛亮'})-[r:Entity_Entity]-(x:Entity)<-[:Entity_Entity]-(l:Entity {id:'孙权'}) WHERE r.relationFamily='亲属' RETURN DISTINCT x.name"],
        ['问答：谁效力魏（魏国阵营人数）', 'Q: how many people serve Wei?', "MATCH (p:Entity)-[r:Entity_Entity]->(f:Entity {id:'魏'}) WHERE r.relation='serves' RETURN count(*) AS members"],
      ],
      'hnsw-sift': [
        ['4242 向量本体', 'Vector 4242 itself', "MATCH (v:Vector {id:'4242'}) RETURN v.name, v.bucket, v.category"],
        ['同 bucket 的邻居（属性过滤）', 'Neighbours in one bucket (property filter)', "MATCH (v:Vector {id:'4242'})-[r:vector_0]->(w:Vector) WHERE w.bucket = 3 RETURN w.id, w.name LIMIT 20"],
        ['按 bucket 聚合邻居', 'Neighbours by bucket', "MATCH (v:Vector {id:'4242'})-[r:vector_0]->(w:Vector) RETURN w.bucket AS bucket, count(*) AS n ORDER BY n DESC"],
        ['按 category 聚合邻居', 'Neighbours by category', "MATCH (v:Vector {id:'4242'})-[r:vector_0]->(w:Vector) RETURN w.category AS category, count(*) AS n ORDER BY n DESC"],
        ['层 0 的邻居数（计数）', 'Layer-0 neighbour count', "MATCH (v:Vector {id:'4242'})-[r:vector_0]->(w:Vector) RETURN count(*) AS neighbours"],
      ],
      'hnsw-dynamic': [
        ['300 文档本体', 'Document 300 itself', "MATCH (d:Doc {id:'300'}) RETURN d.name"],
        ['300 的层 0 邻居', '300\'s layer-0 neighbours', "MATCH (d:Doc {id:'300'})-[r:embedding_0]->(n:Doc) RETURN n.id, n.name LIMIT 10"],
      ],
    },
    gremlin: {
      // a hub with 300k payments lives in the aggregated Aerospike Edge_Out/Edge_In tables:
      // such a vertex can only be read inside a creation-time window
      // the data agent's semantic layer (and its data instance): the metric/schema trees are what the
      // tree layout in the toolbar is for - pick 树 · 左→右 / 上→下 / 下→上 / 放射 and read the hierarchy
      'tpc-h-analytics': [
        ['树 · profit 的三层归因树（11 点，用工具栏树形布局）', 'Tree - the three-level attribution tree of profit (11 vertices; use the toolbar tree layout)',
          "g.V('metric/profit','Node').inE('Relation').filter(relation.equals('contributesTo')).otherV().inE('Relation').filter(relation.equals('contributesTo')).otherV().dedup()"],
        ['树 · 语义层主干：业务域 → 指标 → 表 → 字段（39 点）', 'Tree - the semantic backbone: domain -> metrics -> tables -> columns (39 vertices)',
          "g.V('domain/sales','Node').inE('Relation').filter(relation.equals('belongsTo')).otherV().outE('Relation').filter(relation.equals('computedFrom')).otherV().outE('Relation').filter(relation.equals('hasColumn')).otherV().dedup()"],
        ['树 · 血缘：revenue 的表与字段', 'Tree - lineage: the tables and columns behind revenue',
          "g.V('metric/revenue','Node').outE('Relation').filter(relation.equals('computedFrom') || relation.equals('usesColumn')).otherV().dedup()"],
        ['指标定义 revenue', 'The definition of revenue', "g.V('metric/revenue','Node').values('aggFunction','expression','unit')"],
        ['归因上游：谁驱动 revenue（六个）', 'Attribution upstream: the six metrics that drive revenue',
          "g.V('metric/revenue','Node').inE('Relation').filter(relation.equals('contributesTo')).otherV().values('name')"],
        ['join 路径：外键 → 主键', 'Join paths: foreign keys -> primary keys',
          "g.V('column/lineitem.l_orderkey','Node').outE('Relation').filter(relation.equals('references')).otherV().values('name')"],
      ],
      'tpc-h-data': [
        ['树 · 星型模型：Sale 的七类维度边', 'Tree - the star: the seven dimension edges around Sale',
          "g.V('sale_0','Sale').both().dedup()"],
        ['大区净收入', 'Net revenue by region', "g.V('Sale').group().by('region').by(values('revenue').sum())"],
        ['按年净收入 / 毛收入 / 折扣额', 'Per year: net revenue / gross / discount',
          "g.V('Sale').group().by('orderYear').by(values('gross').sum())"],
        ['雪花链 join（4 跳到大区）', 'Snowflake join (four hops to the region)',
          "g.V('sale_0','Sale').out('ofOrder').out('placedBy').out('customerNation').out('nationRegion').values('name')"],
      ],
      'payment-graph-as-edge': [
        ['大点 · 一天窗口内的前 500 笔支付（Aerospike-Edge）', 'Hub - first 500 payments in a one-day window (Aerospike-Edge)', "g.V('9000001','Account').createdBetween('2025-06-01','2025-06-02').inE('Payment').limit(500)"],
        ['大点 · 某周窗口的笔数', 'Hub - payments in a one-week window', "g.V('9000001','Account').createdBetween('2025-11-01','2025-11-07').inE('Payment').count()"],
        ['大点 · 不限时间会被提示加窗口', 'Hub - without a window it asks for one', "g.V('9000001','Account').inE('Payment').count()"],
        // --- the big-vertex demo: the three tiers of a big vertex -----------------------------------
        // cold (the aggregated Edge_Out / Edge_In tables, older than 25h), near-line (the last day, in
        // TTL'd KVs pre-partitioned from the volume) and online (the inline tail, under the schema's
        // degree cap). A read is routed by the spans its window overlaps and the tiers are merged by
        // edge id, newest copy winning. Written by AerospikeBigVertexDemoTest; the relative bounds
        // (-10m / -23h / -400d) are resolved when the query runs, so they always mean "the last ...".
        ['大点① · 不带时间窗：读被拒绝并要求加窗口',
          'Big vertex 1 - no window: the read is refused and asks for one',
          "g.V('9000002','Account').inE('Payment')"],
        ['大点② · 只看在线（最近 10 分钟）',
          'Big vertex 2 - online only (the last 10 minutes)',
          "g.V('9000002','Account').createdBetween('-10m','*').inE('Payment').values('id','status')"],
        ['大点③ · 近线 + 在线（最近 23 小时，两处合并）',
          'Big vertex 3 - near-line + online (the last 23 hours, merged)',
          "g.V('9000002','Account').createdBetween('-23h','*').inE('Payment').values('id')"],
        ['大点④ · 只看冷数据（离线聚合表）',
          'Big vertex 4 - a cold window (the offline aggregated tables alone)',
          "g.V('9000002','Account').createdBetween('-360d','-330d').inE('Payment').limit(20).values('id','status')"],
        ['大点⑤ · 冷 + 近线 + 在线（全合并，同一 id 取最新）',
          'Big vertex 5 - cold + near-line + online merged, newest copy of an id wins',
          "g.V('9000002','Account').createdBetween('-400d','*').inE('Payment').limit(10).values('id','status')"],
        ['大点⑥ · 三处合并后的总数',
          'Big vertex 6 - how many edges the merged window holds',
          "g.V('9000002','Account').createdBetween('-400d','*').inE('Payment').count()"],
        ['大点⑦ · 还没有冷数据的大点（近线 + 在线）',
          'Big vertex 7 - a big vertex that has nothing cold yet',
          "g.V('9000003','Account').createdBetween('-23h','*').inE('Payment').values('id')"],
        ['大点⑧ · 安静的大点：全在冷数据里',
          'Big vertex 8 - a big and quiet vertex: everything is cold',
          "g.V('9000004','Account').createdBetween('-400d','*').inE('Payment').count()"],
        ['大点⑨ · 出度方向的大点（付款方，三处合并）',
          'Big vertex 9 - the outgoing side tiers the same way (a big payer)',
          "g.V('9000005','Account').createdBetween('-400d','*').outE('Payment').limit(10).values('id')"],
        ['大点⑩ · 未超过出/入度上限的点：不需要时间窗',
          'Big vertex 10 - a vertex under the cap needs no window',
          "g.V('9000006','Account').inE('Payment').values('id')"],
        ['大点⑪ · 近线按 volume 预划分（按状态分组）',
          'Big vertex 11 - the near-line day is pre-partitioned from the volume (grouped by status)',
          "g.V('9000002','Account').createdBetween('-23h','*').inE('Payment').groupCount('status')"],
      ],
      // the same payment data in RocksDB-Edge: every account carries a location (an S2 geo index) and
      // a behaviour profile. The geo step answers here; the vector step needs the HNSW index, which
      // lives in the Aerospike edge bins, so the vector examples are on payment-graph.
      'payment-graph-rocksdb-edge': [
        // this instance of the demo data keys its accounts by number (the loader writes long ids), so the
        // examples use 1001 rather than '1001'
        ['向量 · 最相似的 5 个账户', 'Vector - the 5 most similar accounts', "g.V(1001,'Account').nearest(5)"],
        ['向量 · 相似账户的出款', 'Vector - the payments of the similar accounts', "g.V(1001,'Account').nearest(5).outE('Payment')"],
        ['向量 → 收款方 → 再找相似', 'Vector -> receivers -> their lookalikes', "g.V(1001,'Account').nearest(5).out('Payment').dedup().nearest(3).values('id')"],
        ['GEO + 向量 + 图 · 5km 内账户的相似账户及其出款',
          'GEO + vector + graph - lookalikes of the accounts within 5 km, and what they pay',
          "g.V(1001,'Account').circle('{\"latitude\":31.2304,\"longitude\":121.4737}', 5000).limit(10).nearest(3).outE('Payment')"],
        ['GEO + 向量 · 5km 内账户的相似账户',
          'GEO + vector - the lookalikes of the accounts within 5 km',
          "g.V(1001,'Account').circle('{\"latitude\":31.2304,\"longitude\":121.4737}', 5000).limit(5).nearest(2).out('Payment').dedup().limit(20)"],
        ['GEO · 人民广场 5km 内最近 10 个账户', 'GEO - the 10 nearest accounts within 5 km of People\'s Square', "g.V(1001,'Account').circle('{\"latitude\":31.2304,\"longitude\":121.4737}', 5000).limit(10)"],
        ['GEO · 这 10 个账户的出款', 'GEO - the outgoing payments of those 10 accounts', "g.V(1001,'Account').circle('{\"latitude\":31.2304,\"longitude\":121.4737}', 5000).limit(10).outE('Payment')"],
        ['GEO · 这 10 个账户的收款方', 'GEO - the receivers those 10 accounts pay', "g.V(1001,'Account').circle('{\"latitude\":31.2304,\"longitude\":121.4737}', 5000).limit(10).out('Payment').dedup().limit(10)"],
        ['GEO · 带距离（米）', 'GEO - with the distance in metres', "g.V(1001,'Account').circle('{\"latitude\":31.2304,\"longitude\":121.4737}', 5000).limit(5).values('id','distanceMeters')"],
        // --- 按属性的查找：单机 vs 分布式 -----------------------------------------------------------
        // 这个图的表是 hash(id):id 的有序表，所以一个 hash 区间就是一个连续 key 区间：g.V(DIST)/g.E(DIST)
        // 把整个扫描按区间切成 N 份，每份一个 mapper 线程（属性条件与后续遍历都在 mapper 里做，再 outE 等），
        // 需要全集的步骤（order/limit/count/groupCount…）由 reducer 在多份输出上做一次。
        // 同一条查询去掉 DIST 就是单进程全表扫描，两者答案一致（已由 GremlinDistributedScanTest 逐个对比）。
        ['属性查找 · 单机扫描（全部 FROZEN 账户）',
          'Attribute lookup - in-process scan (every FROZEN account)',
          "g.V('Account').has('accountStatus','FROZEN').values('id')"],
        ['属性查找 · 分布式（g.V(DIST)，每个 mapper 一个分区）',
          'Attribute lookup - distributed (g.V(DIST), one mapper per partition)',
          "g.V(DIST,'Account').has('accountStatus','FROZEN').values('id')"],
        ['属性查找 · 分布式 + 后续遍历（FROZEN 账户的出款）',
          'Attribute lookup - distributed scan, then a hop (what the FROZEN accounts pay)',
          "g.V(DIST,'Account').has('accountStatus','FROZEN').outE('Payment').values('id','status')"],
        ['属性查找 · 类型由 has 给出（分布式）',
          'Attribute lookup - the type comes from has(...), distributed',
          "g.V(DIST).has('Account','accountStatus','FROZEN').count()"],
        ['属性查找 · 边属性（分布式，成功后接 outE）',
          'Attribute lookup on edges - distributed, then another hop',
          "g.E(DIST,'Payment').has('status','SUCCESS').limit(20).values('id','amount')"],
        ['属性查找 · 边属性（类型由 has 给出的分布式形式）',
          'Attribute lookup on edges - type given by has(...), distributed',
          "g.E(DIST).has('Payment','status','FAILED').groupCount('status')"],
        // --- 分布式扫描 + 分布式算法（一个 DIST 管整条查询） ---------------------------------------
        // g.V(DIST) 在起点说了“这条查询跑在分布式上”：前半段（分区扫描、属性条件、bothE 扩展）在 mapper 里，
        // 后半段的算法（cc/louvain/pagerank/sssp/bfs 都是全局步骤）交给 Gemini 集群，输入是 mapper 展开出来的子图。
        // 去掉 DIST 就是单机：扫描在本进程、算法用内存实现，两者答案一致（组件号只是标签，按“哪些点同组”对比）。
        ['分布式算法 · 单机：FROZEN 账户的支付图连通分量',
          'Distributed algorithm - in-process: connected components of the FROZEN accounts payments',
          "g.V().has('Account','accountStatus','FROZEN').bothE('Payment').cc()"],
        ['分布式算法 · 分布式：DIST 管扫描+扩展+cc（一个 DIST 到底）',
          'Distributed algorithm - DIST covers the scan, the expansion and cc',
          "g.V(DIST).has('Account','accountStatus','FROZEN').bothE('Payment').cc()"],
        ['分布式算法 · 同一个 DIST 换成 pagerank',
          'Distributed algorithm - the same DIST with pagerank',
          "g.V(DIST,'Account').bothE('Payment').pagerank().unfold().limit(10)"],
        ['中心度 · 扫描 + 集群：FROZEN 账户支付图的介数中心性（采样 8 个源点）',
          'Centrality - a DIST scan and the cluster: betweenness of the FROZEN payments, 8 sampled sources',
          "g.V(DIST).has('Account','accountStatus','FROZEN').bothE('Payment').otherV().dedup().betweenness(DIST, 8)"],
        // 中心度：不带 DIST 在本进程算，带 DIST 交给 Gemini 集群；closeness/betweenness 按源点逐个跑
        // （betweenness(DIST, k) 采样 k 个源点，是 Brandes-Pich 式的近似，k 省略时全跑＝精确）。
        ['中心度 · 度中心性（本进程）', 'Centrality - degree, in this process',
          "g.V('135','Account').bothE('Payment').otherV().degree()"],
        ['中心度 · 介数中心性（集群，采样 8 个源点）', 'Centrality - betweenness on the cluster, 8 sampled sources',
          "g.V('135','Account').bothE('Payment').otherV().betweenness(DIST, 8)"],
        ['中心度 · 特征向量中心性（集群）', 'Centrality - eigenvector on the cluster',
          "g.V('135','Account').bothE('Payment').otherV().eigenvector(DIST, 20)"],
      ],
      'geo-merchants-rocksdb-inline': [
        ['GEO · 1km 内最近的 10 家商户', 'GEO - 10 nearest merchants within 1 km', "g.V('1','Merchant').circle('{\"latitude\":31.2304,\"longitude\":121.4737}', 1000).limit(10)"],
        ['GEO · 500m 内的咖啡店 + 距离(米)', 'GEO - coffee shops within 500 m + distance (m)', "g.V('1','Merchant').circle('{\"latitude\":31.2304,\"longitude\":121.4737}', 500).has('category','coffee').values('name','distanceMeters')"],
        ['GEO · 2km 内有多少商户', 'GEO - how many merchants within 2 km', "g.V('1','Merchant').circle('{\"latitude\":31.2304,\"longitude\":121.4737}', 2000).count()"],
      ],
      'geo-merchants-rocksdb-edge': [
        ['GEO · 1km 内最近的 10 家商户（RocksDB-Edge）', 'GEO - 10 nearest merchants within 1 km (RocksDB-Edge)', "g.V('1','Merchant').circle('{\"latitude\":31.2304,\"longitude\":121.4737}', 1000).limit(10)"],
        ['GEO · 300m 内全部商户的距离(米)', 'GEO - distance (m) of every merchant within 300 m', "g.V('1','Merchant').circle('{\"latitude\":31.2304,\"longitude\":121.4737}', 300).values('distanceMeters')"],
      ],
      'payment-graph': [
        ['135 的出账（一跳）', '135\'s outgoing payments (one hop)', "g.V('135','Account').outE('Payment').limit(10)"],
        ['135 双向两跳', '135, two hops over Payment (both directions)', "g.V('135','Account').both('Payment').both('Payment').dedup().limit(50)"],
        ['相似账户：向量最近 5 个', 'Similar accounts: the 5 nearest by vector', "g.V('1001','Account').nearest(5)"],
        ['相似账户 → 它们的出款', 'Similar accounts -> their outgoing payments', "g.V('1001','Account').nearest(5).outE('Payment')"],
        ['相似 → 收款方 → 再找相似', 'Similar -> receivers -> their lookalikes', "g.V('1001','Account').nearest(5).out('Payment').dedup().nearest(3)"],
        ['账户的手机号', 'an account\'s phone', "g.V('1000','Account').in('PhoneToAccount')"],
        ['模糊关联①：同邮箱域名+国家+/16+名字词根', 'Fuzzy link 1: same mail domain + country + /16 + name stem', "g.V().fuzzyLink(0, '{\"email\":\"19880101@mail.ru\",\"phone\":\"+8613800138001\",\"ip\":\"10.20.31.7\",\"username\":\"zhang1988\"}')"],
        ['模糊关联①→账户→出款→收款方', 'Fuzzy link 1 -> account -> payment -> receiver', "g.V().fuzzyLink(0, '{\"email\":\"19880101@mail.ru\",\"phone\":\"+8613800138001\",\"ip\":\"10.20.31.7\",\"username\":\"zhang1988\"}').out('FuzzyLinkedTo').outE('Payment').otherV()"],
        ['模糊关联②：同/24+卡BIN+邮箱前四位', 'Fuzzy link 2: same /24 + card BIN + first four mail characters', "g.V().fuzzyLink(1, '{\"email\":\"44100101@qq.com\",\"ip\":\"10.30.40.11\",\"cardNumber\":\"6217000090009001\"}').out('FuzzyLinkedTo')"],
      // the event sequence patterns: the event list goes in, the group (the pattern id and its key) comes out.
      // ev_a1 and ev_a2 share the ordered session; ev_a6 satisfies the two-sided-window patterns, ev_a7 is tied
      // by the reason (pattern 4 only), ev_a8 by the cookie (pattern 4 only) and ev_a9 is a web login (pattern 3
      // only); ev_a1/a2/a5 also carry the three fields anyFields links on (pattern 5).
      ['事件序列②：事件列表 → 行为组', 'Event sequence 2: an event list -> the group it belongs to',
        "g.V().fuzzyLink(2, '{\"Login\":{\"ID\":\"ev_a1\",\"IP\":\"10.122.13.14\",\"Type\":\"Mobile\",\"EventTime\":1790147176281},\"BalanceCheck\":{\"ID\":\"ev_a1\",\"IP\":\"10.122.13.14\",\"EventTime\":1790147177281},\"Payment\":{\"ID\":\"ev_a1\",\"USD\":1000,\"CBIN\":\"035\",\"EventTime\":1790147178281},\"Dispute\":{\"ID\":\"ev_a1\",\"DisputeReason\":\"NotMyCard\",\"IP\":\"10.122.13.14\",\"EventTime\":1790147179281},\"Logout\":{\"ID\":\"ev_a1\",\"EventTime\":1790147239281}}').values('id')"],
      ['事件序列②：这一组里的账号', 'Event sequence 2: the accounts in that group',
        "g.V().fuzzyLink(2, '{\"Login\":{\"ID\":\"ev_a1\",\"IP\":\"10.122.13.14\",\"Type\":\"Mobile\",\"EventTime\":1790147176281},\"BalanceCheck\":{\"ID\":\"ev_a1\",\"IP\":\"10.122.13.14\",\"EventTime\":1790147177281},\"Payment\":{\"ID\":\"ev_a1\",\"USD\":1000,\"CBIN\":\"035\",\"EventTime\":1790147178281},\"Dispute\":{\"ID\":\"ev_a1\",\"DisputeReason\":\"NotMyCard\",\"IP\":\"10.122.13.14\",\"EventTime\":1790147179281},\"Logout\":{\"ID\":\"ev_a1\",\"EventTime\":1790147239281}}').out('FuzzyLinkedTo').values('id')"],
      ['事件序列③：双向窗口 + 争议地址=登录地址', 'Event sequence 3: two-sided window, the dispute from the login address',
        "g.V().fuzzyLink(3, '{\"Login\":{\"ID\":\"ev_a6\",\"IP\":\"10.141.20.30\",\"Type\":\"Mobile\",\"EventTime\":1790147176281},\"BalanceCheck\":{\"ID\":\"ev_a6\",\"IP\":\"10.141.20.30\",\"EventTime\":1790147177281},\"Payment\":{\"ID\":\"ev_a6\",\"USD\":1000,\"CBIN\":\"035\",\"Cookie\":\"ck_a6\",\"EventTime\":1790147176781},\"Dispute\":{\"ID\":\"ev_a6\",\"DisputeReason\":\"NotMyCard\",\"IP\":\"10.141.20.30\",\"Cookie\":\"ck_a6\",\"EventTime\":1790147179781}}').values('id')"],
      ['事件序列③：这一组里的账号', 'Event sequence 3: the accounts in that group',
        "g.V().fuzzyLink(3, '{\"Login\":{\"ID\":\"ev_a6\",\"IP\":\"10.141.20.30\",\"Type\":\"Mobile\",\"EventTime\":1790147176281},\"BalanceCheck\":{\"ID\":\"ev_a6\",\"IP\":\"10.141.20.30\",\"EventTime\":1790147177281},\"Payment\":{\"ID\":\"ev_a6\",\"USD\":1000,\"CBIN\":\"035\",\"Cookie\":\"ck_a6\",\"EventTime\":1790147176781},\"Dispute\":{\"ID\":\"ev_a6\",\"DisputeReason\":\"NotMyCard\",\"IP\":\"10.141.20.30\",\"Cookie\":\"ck_a6\",\"EventTime\":1790147179781}}').out('FuzzyLinkedTo').values('id')"],
      ['事件序列④：Mobile + (地址或 Cookie 或 NotMyCard)', 'Event sequence 4: mobile login, dispute tied by address, cookie or reason',
        "g.V().fuzzyLink(4, '{\"Login\":{\"ID\":\"ev_a6\",\"IP\":\"10.141.20.30\",\"Type\":\"Mobile\",\"EventTime\":1790147176281},\"BalanceCheck\":{\"ID\":\"ev_a6\",\"IP\":\"10.141.20.30\",\"EventTime\":1790147177281},\"Payment\":{\"ID\":\"ev_a6\",\"USD\":1000,\"CBIN\":\"035\",\"Cookie\":\"ck_a6\",\"EventTime\":1790147176781},\"Dispute\":{\"ID\":\"ev_a6\",\"DisputeReason\":\"NotMyCard\",\"IP\":\"10.141.20.30\",\"Cookie\":\"ck_a6\",\"EventTime\":1790147179781}}').out('FuzzyLinkedTo').values('id')"],
      ['事件序列⑤：anyFields 找出三个事件共有的字段', 'Event sequence 5: anyFields, the fields the three events agree on',
        "g.V().fuzzyLink(5, '{\"Login\":{\"ID\":\"ev_a1\",\"IP\":\"10.122.13.14\",\"Type\":\"Mobile\",\"Cookie\":\"ck_a1\",\"EventTime\":1790147176281},\"BalanceCheck\":{\"ID\":\"ev_a1\",\"IP\":\"10.122.13.14\",\"EventTime\":1790147177281},\"Payment\":{\"ID\":\"ev_a1\",\"IP\":\"10.122.13.14\",\"Type\":\"Mobile\",\"USD\":1000,\"CBIN\":\"035\",\"Cookie\":\"ck_a1\",\"EventTime\":1790147178281},\"Dispute\":{\"ID\":\"ev_a1\",\"DisputeReason\":\"NotMyCard\",\"IP\":\"10.122.13.14\",\"Type\":\"Mobile\",\"Cookie\":\"ck_a1\",\"EventTime\":1790147179281}}').values('id')"],
      ['事件序列⑤：这一组里的账号', 'Event sequence 5: the accounts in that group',
        "g.V().fuzzyLink(5, '{\"Login\":{\"ID\":\"ev_a1\",\"IP\":\"10.122.13.14\",\"Type\":\"Mobile\",\"Cookie\":\"ck_a1\",\"EventTime\":1790147176281},\"BalanceCheck\":{\"ID\":\"ev_a1\",\"IP\":\"10.122.13.14\",\"EventTime\":1790147177281},\"Payment\":{\"ID\":\"ev_a1\",\"IP\":\"10.122.13.14\",\"Type\":\"Mobile\",\"USD\":1000,\"CBIN\":\"035\",\"Cookie\":\"ck_a1\",\"EventTime\":1790147178281},\"Dispute\":{\"ID\":\"ev_a1\",\"DisputeReason\":\"NotMyCard\",\"IP\":\"10.122.13.14\",\"Type\":\"Mobile\",\"Cookie\":\"ck_a1\",\"EventTime\":1790147179281}}').out('FuzzyLinkedTo').values('id')"],
      ['事件序列：某账号属于哪些行为组', 'Event sequence: which behaviour groups an account belongs to',
        "g.V('ev_a1','Account').in('FuzzyLinkedTo').values('id')"],
      ['脚本：先取一批 id，再统计', 'Script: keep the ids, then count them',
        "def ids = g.V(1001, 'Account').nearest(5).values('id').toList()\nprint(ids)\nprint(ids.size())"],
      ['两跳路径 path()', 'Two-hop paths with path()',
        "g.V(1001,'Account').out('Payment').out('Payment').path()"],
      ['脚本：for + if + 闭包', 'Script: for, if and a closure',
        "def ids = [1000, 1001, 1002]\ndef out = []\nfor (id in ids) {\n  def n = g.V(id, 'Account').outE('Payment').count()\n  if (n.get(0) > 0) { out = out.add(id + ':' + n.get(0)) }\n}\ndef edges = g.V('1000','Account').outE('Payment').toList()\ndef statuses = edges.collect { it.value('status') }\nprint('out: ' + out)\nprint('statuses: ' + statuses)\nout"],
      ['脚本：sender→receiver 的 4 跳路径（双向 both）',
        'Script: the four-hop paths between a sender and a receiver (both directions)',
        "def sender = 1000\ndef receiver = 1006\n\ndef sPaths = g.V(sender, 'Account').both('Payment').bothE('Payment').path().toList()\ndef rPaths = g.V(receiver, 'Account').both('Payment').bothE('Payment').path().toList()\n\ndef common = sPaths.endIds().retainAll(rPaths.endIds())\ndef midObjects = g.V(common, 'Account').toList()\n\ndef four = sPaths.joinOnEnd(rPaths, midObjects)\n\ndef answer = []\nanswer = answer.addAll(sPaths)\nanswer = answer.addAll(rPaths)\nanswer = answer.addAll(four)\nprint('sender two-hop paths: ' + sPaths.size())\nprint('receiver two-hop paths: ' + rPaths.size())\nprint('common mids: ' + common)\nprint('middle objects: ' + midObjects.size())\nprint('four-hop paths: ' + four.size())\nanswer"],
      ['脚本：四跳 path —— 不看方向 vs 看交易方向（一次返回两个结果）',
        'Script: four-hop paths, direction ignored vs payer-to-payee (two results in one call)',
        "def sender = 1000\ndef receiver = 1006\n\n// only two traversals: both('Payment').bothE('Payment') already holds the out half and the in\n// half of every directed edge, and each edge carries the side it was read from\ndef sAny = g.V(sender, 'Account').both('Payment').bothE('Payment').path().toList()\ndef rAny = g.V(receiver, 'Account').both('Payment').bothE('Payment').path().toList()\n\n// (1) four hops that ignore direction: all of them\ndef anyMids = sAny.endIds().retainAll(rAny.endIds())\ndef anyObjs = []\nif (anyMids.size() > 0) { anyObjs = g.V(anyMids, 'Account').toList() }\ndef anyFour = sAny.joinOnEnd(rAny, anyObjs)\n\n// (2) four hops that follow the money: the sender half must be two out hops and the receiver\n// half two in hops - picked out of the same walks, so nothing is read a second time\ndef sOut = sAny.findAll { p -> p.get(1).value('side') == 'out' && p.get(3).value('side') == 'out' }\ndef rIn = rAny.findAll { p -> p.get(1).value('side') == 'in' && p.get(3).value('side') == 'in' }\ndef dirMids = sOut.endIds().retainAll(rIn.endIds())\ndef dirObjs = []\nif (dirMids.size() > 0) { dirObjs = g.V(dirMids, 'Account').toList() }\ndef dirFour = sOut.joinOnEnd(rIn, dirObjs)\n\nprint('direction ignored, four-hop: ' + anyFour.size() + '  (from ' + sAny.size() + ' x ' + rAny.size() + ' both-walks)')\nprint('payer -> payee, four-hop:    ' + dirFour.size() + '  (from ' + sOut.size() + ' out x ' + rIn.size() + ' in halves)')\n\n[anyFour, dirFour]"],
      ],
      'kg-entity-graph': [
        ['概念书的全部关系', 'All relations of concept_book_new_9', "g.V('concept_book_new_9','Entity').bothE().limit(20)"],
        ['按关系过滤再跳一跳', 'Filter by relation, then one hop', "g.V('concept_mammal_animals_2','Entity').bothE().filter(relation.equals('concept:specializationof') || xrelation.equals('concept:specializationof')).otherV()"],
      ],
      'sanguo-kg': [
        ['一个分片的全部关系（曹操_3）', 'All relations of one shard', "g.V('曹操_3','Entity').bothE()"],
        ['一个人全部分片的关系', 'Every shard of one person', "g.V('曹操_2','曹操_3','曹操_5','曹操_6','曹操_7','曹操_8','曹操_9','Entity').batch(20).bothE()"],
        ['按关系过滤（主公）', 'Filter by relation (lord)', "g.V('曹操_3','Entity').bothE().filter(relation.equals('lord') || xrelation.equals('lord'))"],
        ['过滤后跳一跳（去重）', 'Filter, then one hop, deduplicated', "g.V('曹操_3','Entity').bothE().filter(relation.equals('lord')).otherV().dedup()"],
        ['反向：谁是曹操的臣子', 'Inverse: who serves Cao Cao', "g.V('曹操_9','Entity').inE().filter(relation.equals('minister')).otherV().dedup()"],
        ['姜维的师傅（师承家族）', 'Jiang Wei and his master', "g.V('姜维_7','Entity').bothE().filter(relation.equals('apprentice') || xrelation.equals('apprentice')).otherV().dedup()"],
        ['诸葛亮出场章回数', 'How many chapters Zhuge Liang appears in', "g.V('诸葛亮_3','Entity').bothE().filter(relation.equals('appearsIn')).otherV().count()"],
        ['按关系家族聚合', 'Count the relations by family', "g.V('曹操_2','曹操_3','曹操_5','曹操_6','曹操_7','曹操_8','曹操_9','Entity').bothE().groupCount('relationFamily')"],
        ['魏有多少人效力', 'How many people serve Wei', "g.V('魏_8','Entity').inE().filter(relation.equals('serves')).otherV().dedup().count()"],
        ['两跳：曹嵩 → 曹操 → 曹昂', 'Two hops: grandfather to grandson', "g.V('曹嵩_8','Entity').bothE().filter(relation.equals('father')).otherV().bothE().filter(relation.equals('father')).otherV().dedup()"],
      ],
      'sanguo-kg-rocksdb-edge': [
        ['实体属性（点查）', 'Entity with its attributes (point lookup)', "g.V('曹操','Entity').values('name','entityType','gender','courtesyName')"],
        ['一个人的全部关系（一条记录，不再分片）', 'Every relation of one person, in one record', "g.V('曹操','Entity').bothE()"],
        ['邻居去重（社交圈）', 'Distinct neighbours (the person\'s circle)', "g.V('曹操','Entity').bothE().otherV().dedup()"],
        ['按关系过滤：曹操的主公关系', 'Filter by relation: Cao Cao as lord', "g.V('曹操','Entity').outE().filter(relation.equals('lord'))"],
        ['一跳：曹操手下的将领', 'One hop: the generals under Cao Cao', "g.V('曹操','Entity').outE().filter(relation.equals('lord')).otherV().dedup()"],
        ['反向惯用法：刘备的臣属', 'Inverse idiom: who serves Liu Bei', "g.V('刘备','Entity').bothE().filter(relation.equals('lord') || xrelation.equals('lord')).otherV().dedup()"],
        ['师承：姜维的师傅', 'Mentorship: Jiang Wei\'s master', "g.V('姜维','Entity').bothE().filter(relation.equals('master') || xrelation.equals('master')).otherV().dedup()"],
        ['按关系家族聚合', 'Relations grouped by family', "g.V('曹操','Entity').bothE().groupCount('relationFamily')"],
        ['两跳：曹嵩 → 曹操 → 曹昂（祖孙）', 'Two hops: grandfather to grandson', "g.V('曹嵩','Entity').outE().filter(relation.equals('father')).otherV().outE().filter(relation.equals('father')).otherV().dedup()"],
        ['势力成员数：魏', 'Faction size: how many serve Wei', "g.V('魏','Entity').inE().filter(relation.equals('serves')).otherV().dedup().count()"],
        ['两次：指定关系链 刘备-主公-诸葛亮-师傅-姜维', 'Two hops: a named relation chain', "g.V('刘备','Entity').outE().filter(relation.equals('lord')).otherV().outE().filter(relation.equals('master')).otherV().dedup().values('name')"],
        ['两次：共同邻居（刘备与曹操）', 'Two hops: a common neighbour', "g.V('刘备','Entity').both('Entity_Entity').dedup().where(both('Entity_Entity').hasId('曹操')).values('name')"],
        ['两次：同势力同僚（徐庶）', 'Two hops: colleagues in the same faction', "g.V('徐庶','Entity').outE().filter(relation.equals('serves')).otherV().inE().filter(relation.equals('serves')).otherV().dedup().count()"],
        ['两次：同回出场（诸葛亮）', 'Two hops: co-appearance in a chapter', "g.V('诸葛亮','Entity').outE().filter(relation.equals('appearsIn')).otherV().inE().filter(relation.equals('appearsIn')).otherV().dedup().count()"],
        ['两次：路径强度（刘备两跳能到谁）', 'Two hops: paths per entity', "g.V('刘备','Entity').both('Entity_Entity').both('Entity_Entity').groupCount().by('name')"],
        ['三次：刘备三跳到曹操的路径数', 'Three hops: walks from Liu Bei to Cao Cao', "g.V('刘备','Entity').repeat(both('Entity_Entity')).times(3).hasId('曹操').count()"],
        ['三次：祖孙三代（司马懿→司马昭→司马炎）', 'Three hops: three generations', "g.V('司马懿','Entity').inE().filter(relation.equals('son')).otherV().inE().filter(relation.equals('son')).otherV().dedup().values('name')"],
        ['问答：关羽和曹操是什么关系？', 'Q: how are Guan Yu and Cao Cao related?', "g.V('关羽','Entity').outE().filter(relation.equals('serves')).otherV().dedup().where(inE().filter(relation.equals('serves')).otherV().hasId('曹操')).values('name')"],
        ['问答：刘备和曹操是什么关系？', 'Q: how are Liu Bei and Cao Cao related?', "g.V('刘备','Entity').outE().filter(relation.equals('lord')).otherV().dedup().where(inE().filter(relation.equals('lord')).otherV().hasId('曹操')).values('name')"],
        ['问答：三国三大主公是谁？', 'Q: who are the three lords?', "g.V('Entity').inE().filter(relation.equals('lord')).otherV().groupCount().by('name')"],
        ['问答：刘备手下谁是从曹操那过来的？', 'Q: who under Liu Bei came from Cao Cao?', "g.V('曹操','Entity').outE().filter(relation.equals('lord')).otherV().dedup().where(inE().filter(relation.equals('lord')).otherV().hasId('刘备')).values('name')"],
        ['问答：曹操的同族有谁？', 'Q: who is in Cao Cao\'s clan?', "g.V('曹操','Entity').bothE().filter(relation.equals('same_clan')).otherV().values('name')"],
        ['问答：诸葛亮和司马懿相隔多远？', 'Q: how far apart are Zhuge Liang and Sima Yi?', "g.V('诸葛亮','Entity').repeat(both('Entity_Entity')).times(2).hasId('司马懿').count()"],
        // 地图：经纬度读成列（valueMap）→ 结果面板出坐标，点工具栏地图图标在 panel 里画出这些点
        ['地图 · 全部 26 个带经纬度的实体（点工具栏地图图标）', 'Map - all 26 entities with coordinates (then click the map icon in the toolbar)',
          "g.V('Entity').has('latitude').valueMap('name','entityType','eventYear','latitude','longitude')"],
        ['地图 · 赤壁之战的位置', 'Map - where the battle of Red Cliffs was',
          "g.V('赤壁之战','Entity').valueMap('name','eventYear','latitude','longitude')"],
      ],
      'sanguo-kg-text': [
        ['方案 C：谁是五虎上将（自动抽到）', 'Plan C: who are the Five Tiger Generals (auto-extracted)', "g.V('五虎上将','Entity').inE().filter(relation.equals('holds')).otherV().values('name')"],
        ['方案 C：五虎将之首', 'Plan C: the head of the Five Tiger Generals', "g.V('五虎上将之首','Entity').inE().filter(relation.equals('holds')).otherV().values('name')"],
        ['方案 C：刘备的军师是谁', 'Plan C: Liu Bei\'s strategist', "g.V('刘备','Entity').inE().filter(relation.equals('strategistOf')).otherV().values('name')"],
        ['方案 C：曹操的谋士是谁', 'Plan C: Cao Cao\'s advisor', "g.V('曹操','Entity').inE().filter(relation.equals('advisorOf')).otherV().values('name')"],
        ['方案 C：关羽斩杀过谁', 'Plan C: whom Guan Yu killed', "g.V('关羽','Entity').outE().filter(relation.equals('killed')).otherV().values('name')"],
        ['方案 C：谁斩杀了关羽', 'Plan C: who killed Guan Yu', "g.V('关羽','Entity').inE().filter(relation.equals('killed')).otherV().values('name')"],
        ['方案 C：关羽与谁交战', 'Plan C: whom Guan Yu fought', "g.V('关羽','Entity').bothE().filter(relation.equals('fought')).otherV().dedup().values('name')"],
        ['方案 C：关羽的传记提到了谁', 'Plan C: who Guan Yu\'s biography mentions', "g.V('关羽','Entity').outE().filter(relation.equals('mentions')).limit(12).otherV().values('name')"],
        ['方案 ③：关羽提到哪些典故/事件', 'Plan C-3: which episodes Guan Yu\'s biography names', "g.V('关羽','Entity').outE().filter(relation.equals('mentions')).otherV().has('entityType','Event').values('name')"],
        ['方案 ③：谁的传记提到过五关斩六将', 'Plan C-3: whose biography names the five passes', "g.V('过五关斩六将','Entity').inE().filter(relation.equals('mentions')).otherV().values('name')"],
        ['方案 ③：关羽的器物（青龙偃月刀）', 'Plan C-3: Guan Yu\'s weapon', "g.V('关羽','Entity').outE().filter(relation.equals('mentions')).otherV().has('entityType','Artifact').values('name')"],
        // 地图：valueMap 把经纬度读成列 → 结果面板出坐标，点工具栏的地图图标就在 panel 里画出这些点
        // （地图默认画图里全部 26 个带坐标的实体，说明区可以切回「只看查询结果」）
        ['地图 · 全部 26 个带经纬度的实体（点工具栏地图图标）', 'Map - all 26 entities with coordinates (then click the map icon in the toolbar)',
          "g.V('Entity').has('latitude').valueMap('name','entityType','eventYear','latitude','longitude')"],
        ['地图 · 六座都城/城市', 'Map - the six capitals and cities',
          "g.V('Entity').has('entityType','Place').has('latitude').valueMap('name','latitude','longitude')"],
        ['地图 · 公元 210 年之后的战役（6 场）', 'Map - the battles after 210 AD (6 of them)',
          "g.V('Entity').has('entityType','Event').has('latitude').has('eventYear', gt(210)).valueMap('name','eventYear','latitude','longitude')"],
        ['地图 · 赤壁之战的位置', 'Map - where the battle of Red Cliffs was',
          "g.V('赤壁之战','Entity').valueMap('name','eventYear','latitude','longitude')"],
      ],
      'hnsw-sift': [
        ['4242 最近的 20 个向量', '20 nearest vectors to 4242', "g.V('4242','Vector').nearest(20)"],
        ['带属性过滤的向量检索', 'Vector search with a property filter', "g.V('4242','Vector').nearest(20).has('bucket', 3)"],
      ],
      'hnsw-dynamic': [
        ['300 最近的 5 个向量', '5 nearest vectors to 300', "g.V('300','Doc').nearest(5)"],
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
        if (field === '$id') { parts.push(String(d.id)); continue; } // schema displays the id itself
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
        source: 'ui',        // the dashboard wants to see where its queries come from
        script: scriptModeOn() ? true : undefined, // undefined = the backend decides from the text
      }),
    });
    if (!res.ok) throw await queryError(res, query);
    const body = await res.text();
    let result;
    try {
      result = body ? JSON.parse(body) : null;
    } catch (ignored) {
      // the body was cut off before it was complete: the answer was too big to transfer intact
      throw new Error(t('responseTooLarge').replace('{size}', sizeText(body.length)));
    }
    const capped = res.headers.get('X-PhoenixGraph-Truncated');
    if (capped) toast(t('responseCapped').replace('{n}', capped));
    return result;
  }

  /** A byte count as a short human string (used when a response was too large). */
  function sizeText(bytes) {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
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

  /** Open the document-ingestion form and suggest a graph name that does not exist yet. */
  function openIngest() {
    if (!el.ingestModal) return;
    const existing = new Set([...el.graphSelect.options].map((option) => option.value));
    let name = 'uploaded-docs';
    for (let i = 2; existing.has(name); i += 1) name = `uploaded-docs-${i}`;
    el.ingestGraph.value = name;
    el.ingestFiles.value = '';
    el.ingestStatus.textContent = '';
    el.ingestResult.hidden = true;
    el.ingestResult.textContent = '';
    el.ingestModal.hidden = false;
    el.ingestGraph.focus();
    el.ingestGraph.select();
  }

  /** The text of one uploaded file; HTML is reduced to its text so the extractor sees sentences. */
  async function uploadedText(file) {
    const raw = await file.text();
    if (/\.html?$/i.test(file.name)) {
      const document = new DOMParser().parseFromString(raw, 'text/html');
      return document.body ? document.body.textContent : raw;
    }
    return raw;
  }

  async function submitIngest() {
    if (!el.ingestSubmit) return;
    const graphName = el.ingestGraph.value.trim();
    if (!graphName) {
      el.ingestStatus.textContent = t('ingestNoGraph');
      return;
    }
    const files = [...(el.ingestFiles.files || [])];
    if (files.length === 0) {
      el.ingestStatus.textContent = t('ingestNoFiles');
      return;
    }
    let dictionary = {};
    let aliases = {};
    try {
      if (el.ingestDictionary.value.trim()) dictionary = JSON.parse(el.ingestDictionary.value);
      if (el.ingestAliases.value.trim()) aliases = JSON.parse(el.ingestAliases.value);
    } catch (ignored) {
      el.ingestStatus.textContent = t('ingestBadJson');
      return;
    }
    const documents = [];
    for (const file of files) {
      const text = (await uploadedText(file)).trim();
      if (!text) continue;
      documents.push({
        id: file.name.replace(/\.[^.]+$/, ''),
        title: file.name,
        text,
      });
    }
    if (documents.length === 0) {
      el.ingestStatus.textContent = t('ingestNoFiles');
      return;
    }

    el.ingestSubmit.disabled = true;
    el.ingestStatus.textContent = t('ingestRunning');
    el.ingestResult.hidden = true;
    try {
      const response = await fetch('/api/agent/ingest', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          graph: graphName,
          description: `Uploaded from the UI: ${documents.length} document(s)`,
          documents,
          dictionary,
          aliases,
          apply: true,
          rulePack: el.ingestRulePack.value,
        }),
      });
      const raw = await response.text();
      let body;
      try {
        body = raw ? JSON.parse(raw) : {};
      } catch (ignored) {
        body = { error: raw };
      }
      if (!response.ok) throw new Error(body.error || raw);
      const quality = body.quality || {};
      const percent = (value) => (value === undefined || value === null
        ? 'n/a'
        : `${(Number(value) * 100).toFixed(0)}%`);
      el.ingestResult.hidden = false;
      el.ingestResult.textContent = `${t('ingestDone')}: ${body.entities} entities, ${body.relations} relations\n`
        + `provenance ${percent(quality.provenanceRate)} · type agreement ${percent(quality.typeAgreement)}`
        + ` · linked ${percent(quality.entityLinkRate)}\n`
        + (body.notes || []).join('\n');
      el.ingestStatus.textContent = '';
      toast(t('ingestDone'));
      const graphs = await fetchGraphs();
      const graph = graphs.find((candidate) => candidate.name === graphName);
      if (graph) {
        if (![...el.graphSelect.options].some((option) => option.value === graphName)) {
          const option = document.createElement('option');
          option.value = graph.name;
          option.textContent = graph.name + (graph.storage && graph.storage !== 'Aerospike'
            ? ' · ' + graph.storage : '');
          el.graphSelect.appendChild(option);
        }
        el.graphSelect.value = graphName;
        onGraphChange(graph);
      }
    } catch (error) {
      el.ingestStatus.textContent = `${t('ingestFailed')}: ${error.message}`;
    } finally {
      el.ingestSubmit.disabled = false;
    }
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
      // a deterministic starting position: the same query twice draws the same picture, which is
      // what makes two runs comparable (and parallel edges land on the same arcs)
      const jitterX = (hashOf(key) % 1000) / 1000;
      const jitterY = (hashOf(key + '|y') % 1000) / 1000;
      return {
        ...v,
        key,
        x: p ? p.x : width / 2 + (jitterX - 0.5) * 100,
        y: p ? p.y : height / 2 + (jitterY - 0.5) * 100,
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
    assignEdgeArcs(links);
    currentNodes = nodes;
    currentLinks = links;

    const linkSel = root.append('g')
      .selectAll('path')
      .data(links)
      .join('path')
      .attr('class', 'edge')
      .style('--edge-color', (d) => edgeColor(d))
      .classed('reversed', (d) => !!d.reversed)
      .attr('marker-end', 'url(#arrow)')
      .on('click', (event, d) => { event.stopPropagation(); selectEdge(d); });

    const nodeSel = root.append('g')
      .selectAll('g')
      .data(nodes)
      .join('g')
      .attr('class', 'node')
      .classed('pr-highlight', (d) => !!algoState && !!algoState.topKeys && algoState.topKeys.has(d.key))
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
    const dag = treeMode.startsWith('dag');
    if (dag) {
      // a layered DAG keeps every edge and ranks the vertices by dependency - the shape of the semantic
      // layer (contributesTo / computedFrom / usesColumn), where a spanning tree would drop the cross links
      layoutDag(nodes, links, treeMode);
    } else if (treeMode) {
      // a tree is a different question: where does each vertex sit in the hierarchy, not how do the
      // forces settle. The hierarchy is the spanning tree of what was drawn (breadth first from the
      // root), and the orientation is the reader's choice.
      layoutTree(nodes, links);
    } else {
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

      simulation.on('tick', () => {
        linkSel.each(function (d) { setEdgeGeometry(this, d); });
        nodeSel.attr('transform', (d) => `translate(${d.x},${d.y})`);
        for (const n of nodes) positions.set(n.key, { x: n.x, y: n.y });
      });
      simulation.on('end', () => fitView(true));
    }
    linkSel.each(function (d) { setEdgeGeometry(this, d); });
    // the layout decides which edges run backwards, so the class has to follow the layout, not the join
    linkSel.classed('reversed', (d) => !!d.reversed);
    nodeSel.attr('transform', (d) => `translate(${d.x},${d.y})`);
    for (const n of nodes) positions.set(n.key, { x: n.x, y: n.y });
    renderLegend();

    applyBrush();

    el.vertexCount.textContent = nodes.length;
    el.edgeCount.textContent = links.length;
    renderTypeCounts(nodes, links);
    highlight();
    applySearch();
    applyLegendFilter();
    applyPathFilter();
    applyPickedHighlight();
    fitView(false);
    if (treeMode) { refreshTreeHint(); }
    // the 3D view draws the same answer: pick up the colours and the highlights that were just applied
    syncView3D();
  }

  // ---- tree layout: the hierarchy the drawn subgraph makes ----------------------------------------

  /**
   * Lays the drawn subgraph out as a tree: the spanning tree of the graph, breadth first from the root,
   * through {@code d3.tree()} and then into the chosen orientation.
   *
   * A graph is not a tree, so this is a *reading* of it: the first path that reaches a vertex owns it, and
   * the edges outside the spanning tree are still drawn but do not order anything. That is what makes it
   * useful for the shapes that are trees in practice - an attribution chain (metric -> the metrics that
   * drive it), a schema (domain -> table -> column), a call graph - and why the root matters: the same
   * graph read from another root is another hierarchy, which is what a click on a vertex changes.
   */
  function layoutTree(nodes, links) {
    const byKey = new Map(nodes.map((node) => [node.key, node]));
    const adjacency = new Map(nodes.map((node) => [node.key, []]));
    for (const link of links) {
      const sourceKey = typeof link.source === 'object' ? link.source.key : link.source;
      const targetKey = typeof link.target === 'object' ? link.target.key : link.target;
      if (!adjacency.has(sourceKey) || !adjacency.has(targetKey) || sourceKey === targetKey) continue;
      // the geometry reads a link's endpoints as vertices, so a layout that skips the force simulation
      // has to resolve the keys itself - otherwise every path is drawn at NaN (the bug the tree layout
      // had until the edges were looked at instead of only the vertices)
      link.source = byKey.get(sourceKey);
      link.target = byKey.get(targetKey);
      adjacency.get(sourceKey).push(targetKey);
      adjacency.get(targetKey).push(sourceKey);
      link.layout = null;
      link.reversed = false;
    }

    // the root is what the reader picked, else the first drawn vertex; every component gets a tree, so a
    // disconnected answer is a forest laid out side by side rather than one tree with orphans
    const roots = [];
    if (treeRootKey && byKey.has(treeRootKey)) roots.push(treeRootKey);
    for (const node of nodes) {
      if (!roots.includes(node.key)) roots.push(node.key);
    }
    const seen = new Set();
    const forest = [];
    for (const root of roots) {
      if (seen.has(root)) continue;
      const queue = [root];
      seen.add(root);
      const children = new Map([[root, []]]);
      while (queue.length) {
        const key = queue.shift();
        for (const next of adjacency.get(key) || []) {
          if (seen.has(next)) continue;
          seen.add(next);
          children.get(key).push(next);
          children.set(next, []);
          queue.push(next);
        }
      }
      forest.push(children);
    }

    // count the leaves of every subtree, so a sibling group is as tall as what hangs under it
    const leaves = (children, key) => {
      const kids = children.get(key) || [];
      if (!kids.length) return 1;
      return kids.reduce((sum, kid) => sum + leaves(children, kid), 0);
    };

    let offset = 0;
    // the whole forest's leaf count, so the radial layout spreads its components around the circle instead
    // of stacking them on the same spokes
    const globalLeaves = Math.max(1, forest.reduce((sum, children) =>
      sum + leaves(children, [...children.keys()][0]), 0));
    for (const children of forest) {
      const rootKey = [...children.keys()][0];
      const hierarchy = { key: rootKey, children: [] };
      const build = (node, key) => {
        for (const kid of children.get(key) || []) {
          const child = { key: kid, children: [] };
          node.children.push(child);
          build(child, kid);
        }
      };
      build(hierarchy, rootKey);

      const root = d3.hierarchy(hierarchy, (node) => node.children);
      // A tidy tree numbers the leaves left to right and puts every parent in the middle of its children.
      // The number has to be *absolute* (one counter for the whole tree): numbering each sibling group
      // from zero - the obvious first version - makes the groups of different parents overlap, and two
      // vertices of the same depth end up drawn on top of each other.
      let nextLeaf = 0;
      root.eachAfter((node) => {
        if (!node.children || !node.children.length) {
          node.slot = nextLeaf;
          nextLeaf += 1;
          return;
        }
        const first = node.children[0];
        const last = node.children[node.children.length - 1];
        node.slot = (first.slot + last.slot) / 2;
      });

      const depthStep = treeMode === 'radial' ? 92 : 150;
      const leafStep = treeMode === 'radial' ? 0 : 56;
      const walk = (node, depth) => {
        const target = byKey.get(node.data.key);
        const along = offset + node.slot; // the leaf slot, in the whole forest's numbering
        if (target) {
          if (treeMode === 'radial') {
            // radial: the depth is the radius, the position among the leaves the angle
            const angle = ((along + 0.5) / globalLeaves) * Math.PI * 2 - Math.PI / 2;
            target.x = Math.cos(angle) * (depth * depthStep + 40);
            target.y = Math.sin(angle) * (depth * depthStep + 40);
          } else if (treeMode === 'lr') {
            target.x = depth * depthStep;
            target.y = along * leafStep;
          } else if (treeMode === 'rl') {
            // the same hierarchy read right to left: the depth runs the other way, the leaves keep their
            // order, so a lineage that is written "the thing depends on ..." still flows towards the left
            target.x = -depth * depthStep;
            target.y = along * leafStep;
          } else if (treeMode === 'tb') {
            target.x = along * leafStep;
            target.y = depth * depthStep;
          } else { // 'bt': the same tree read from the leaves up
            target.x = along * leafStep;
            target.y = -depth * depthStep;
          }
        }
        (node.children || []).forEach((child) => walk(child, depth + 1));
      };
      walk(root, 0);
      offset += Math.max(1, nextLeaf);
    }

    // the edges of the spanning tree get the elbow that says "parent to child"; the rest stay straight,
    // because they are a graph's extra edges and drawing them as a hierarchy would be a lie
    const treeEdges = new Set();
    for (const children of forest) {
      for (const [key, kids] of children) {
        for (const kid of kids) treeEdges.add(`${key}->${kid}`);
        for (const kid of kids) treeEdges.add(`${kid}->${key}`);
      }
    }
    for (const link of links) {
      const source = typeof link.source === 'object' ? link.source.key : link.source;
      const target = typeof link.target === 'object' ? link.target.key : link.target;
      link.tree = treeEdges.has(`${source}->${target}`);
      link.layout = link.tree ? 'hierarchy' : null;
      link.reversed = false;
    }
  }
  /**
   * Lays the drawn subgraph out as a **layered DAG** (Sugiyama in three steps, without the library):
   *
   * <ol>
   *   <li><b>break the cycles</b> - a depth-first walk marks the edges that close a cycle; they are drawn
   *       <em>dashed</em> and laid out in the direction that keeps the rest acyclic, so the reader can see
   *       which edges a layered picture had to reinterpret;</li>
   *   <li><b>rank</b> by longest path (sources at layer 0, every other vertex one past its deepest
   *       predecessor), so an edge always runs at least one layer down and lineage reads downwards;</li>
   *   <li><b>order</b> each layer by the barycenter of its neighbours, swept down and up a few times, which
   *       is the cheap half of crossing minimisation - and then give the vertices their coordinates.</li>
   * </ol>
   *
   * Unlike the tree layout this keeps **every** edge: a DAG is what the semantic layer is
   * (`contributesTo`, `computedFrom`, `usesColumn`), and a spanning tree would have to drop the cross
   * links that make it a DAG at all. Edges that span more than one layer are drawn straight through with
   * the same smooth link, which is the honest picture: the layer assignment is a ranking, not a distance.
   */
  function layoutDag(nodes, links, mode) {
    const byKey = new Map(nodes.map((node) => [node.key, node]));
    const out = new Map(nodes.map((node) => [node.key, []]));
    const allEdges = [];
    for (const link of links) {
      const sourceKey = typeof link.source === 'object' ? link.source.key : link.source;
      const targetKey = typeof link.target === 'object' ? link.target.key : link.target;
      link.layout = null;
      link.reversed = false;
      if (!byKey.has(sourceKey) || !byKey.has(targetKey) || sourceKey === targetKey) continue;
      // resolve the endpoints the way the force simulation does: the geometry needs vertices, not keys
      link.source = byKey.get(sourceKey);
      link.target = byKey.get(targetKey);
      link.layout = 'dag';
      const edge = { link, source: sourceKey, target: targetKey };
      allEdges.push(edge);
      out.get(sourceKey).push(edge);
    }

    // 1. depth first, marking the edges that close a cycle (they run backwards in the layering)
    const state = new Map();
    const back = new Set();
    const visit = (key) => {
      state.set(key, 1);
      for (const edge of out.get(key) || []) {
        const seen = state.get(edge.target) || 0;
        if (seen === 1) {
          back.add(edge);
          edge.link.reversed = true;
          continue;
        }
        if (seen === 0) visit(edge.target);
      }
      state.set(key, 2);
    };
    for (const node of nodes) {
      if (!state.get(node.key)) visit(node.key);
    }
    const dagEdges = allEdges.filter((edge) => !back.has(edge));

    // 2. longest-path ranking (Kahn: a vertex is ranked once its predecessors are)
    const indegree = new Map(nodes.map((node) => [node.key, 0]));
    for (const edge of dagEdges) indegree.set(edge.target, indegree.get(edge.target) + 1);
    const successors = new Map(nodes.map((node) => [node.key, []]));
    for (const edge of dagEdges) successors.get(edge.source).push(edge.target);
    const rank = new Map(nodes.map((node) => [node.key, 0]));
    const queue = nodes.filter((node) => indegree.get(node.key) === 0).map((node) => node.key);
    for (let head = 0; head < queue.length; head += 1) {
      const key = queue[head];
      for (const next of successors.get(key)) {
        rank.set(next, Math.max(rank.get(next), rank.get(key) + 1));
        indegree.set(next, indegree.get(next) - 1);
        if (indegree.get(next) === 0) queue.push(next);
      }
    }

    // 3. order within the layers: barycenter sweeps, down then up, a few times
    const depths = Math.max(0, ...nodes.map((node) => rank.get(node.key)));
    const layers = [];
    for (let depth = 0; depth <= depths; depth += 1) {
      layers.push(nodes.filter((node) => rank.get(node.key) === depth).map((node) => node.key));
    }
    const successorsOf = new Map(nodes.map((node) => [node.key, []]));
    const predecessorsOf = new Map(nodes.map((node) => [node.key, []]));
    for (const edge of allEdges) {
      const from = edge.link.reversed ? edge.target : edge.source;
      const to = edge.link.reversed ? edge.source : edge.target;
      successorsOf.get(from).push(to);
      predecessorsOf.get(to).push(from);
    }
    const orderIn = (list, neighbours, index) => {
      const mean = new Map(list.map((key, at) => [key, at]));
      return [...list].sort((a, b) => {
        const around = (key) => {
          const keys = neighbours.get(key).filter((other) => index.has(other));
          if (!keys.length) return mean.get(key);
          return keys.reduce((sum, other) => sum + index.get(other), 0) / keys.length;
        };
        return around(a) - around(b) || mean.get(a) - mean.get(b);
      });
    };
    for (let sweep = 0; sweep < 4; sweep += 1) {
      for (let depth = 1; depth <= depths; depth += 1) {
        const index = new Map(layers[depth - 1].map((key, at) => [key, at]));
        layers[depth] = orderIn(layers[depth], predecessorsOf, index);
      }
      for (let depth = depths - 1; depth >= 0; depth -= 1) {
        const index = new Map(layers[depth + 1].map((key, at) => [key, at]));
        layers[depth] = orderIn(layers[depth], successorsOf, index);
      }
    }

    // 4. coordinates: the layer is one axis, the position in the layer the other, each layer centred
    const vertical = mode === 'dag-tb' || mode === 'dag-bt';
    const near = vertical ? 64 : 170; // spacing along the layer
    const depthStep = vertical ? 170 : 150;
    const widest = Math.max(1, ...layers.map((layer) => layer.length));
    layers.forEach((layer, depth) => {
      const start = (widest - layer.length) / 2;
      layer.forEach((key, at) => {
        const node = byKey.get(key);
        const along = (start + at) * near;
        if (mode === 'dag-lr' || mode === 'dag-rl') {
          // right to left is the same layering with the depth axis negated, so the crossing order the
          // barycenter sweeps found is preserved instead of being mirrored into a different crossing count
          node.x = (mode === 'dag-rl' ? -1 : 1) * depth * depthStep;
          node.y = along;
        } else if (mode === 'dag-tb') {
          node.x = along;
          node.y = depth * depthStep;
        } else { // 'dag-bt'
          node.x = along;
          node.y = -depth * depthStep;
        }
      });
    });
  }

  /** Turns a layout mode on (or off) and lays out what is drawn with it. */
  function applyTreeMode(value) {
    treeMode = value || '';
    if (!treeMode) {
      treeRootKey = null;
      render();
      return;
    }
    if (!treeMode.startsWith('dag') && !treeRootKey) {
      treeRootKey = (picked[0] || (currentNodes[0] && currentNodes[0].key)) || null;
    }
    render();
  }

  /** Says what the tree (or the DAG) view is showing: the mode, the size, and the root it grew from. */
  function refreshTreeHint() {
    if (!treeMode || !el.legendCount) return;
    const root = currentNodes.find((node) => node.key === treeRootKey);
    const name = treeMode === 'lr' ? 'treeLr' : treeMode === 'rl' ? 'treeRl' : treeMode === 'tb' ? 'treeTb'
      : treeMode === 'bt' ? 'treeBt'
      : treeMode === 'radial' ? 'treeRadial'
        : treeMode === 'dag-lr' ? 'dagLr' : treeMode === 'dag-rl' ? 'dagRl'
          : treeMode === 'dag-tb' ? 'dagTb' : 'dagBt';
    const reversed = currentLinks.filter((link) => link.reversed).length;
    el.legendCount.hidden = false;
    el.legendCount.textContent = (treeMode.startsWith('dag') ? t('dagHint') : t('treeHint'))
      .replace('{mode}', t(name))
      .replace('{n}', String(currentNodes.length))
      .replace('{e}', String(currentLinks.length))
      .replace('{r}', String(reversed))
      .replace('{root}', root ? displayLabel(root) : '—');
  }

  // stop each line at the node edge so the arrow head stays visible above the node
  const EDGE_NODE_GAP = 13;
  // how far apart the arcs of one vertex pair are (px)
  const EDGE_ARC_STEP = 7;

  /**
   * The most neighbours one vertex of another type may contribute to a projection. Projecting a
   * vertex means connecting all its kept neighbours pairwise, so a hub of 80 accounts would add 3160
   * edges: beyond this the vertex is skipped instead (the result stays readable and finite).
   */
  const PROJECTION_MAX = 40;

  /**
   * Gives every edge of the same vertex pair its own arc.
   *
   * <p>Several edges can connect the same two vertices (an account pays the same receiver more than
   * once, and a pair can be walked in both directions). Drawn as straight lines they sit on top of
   * each other and only one is visible, so the group is fanned out symmetrically around the chord:
   * one edge stays straight, three give -1/0/+1 and so on. The pair is unordered, so a direction
   * and its reverse are fanned too instead of overlapping.
   */
  /**
   * The per-type breakdown under the two totals: one aligned row per vertex type and per edge type,
   * with its count and its share of what is drawn. The totals say how big the picture is; these rows
   * say what it is made of - a query that only walked Accounts looks very different from one that
   * also touched IPs and Phones.
   */
  function renderTypeCounts(nodes, links) {
    if (!el.typeBreakdown) return;
    const byType = (items) => {
      const counts = new Map();
      for (const item of items) counts.set(item.label, (counts.get(item.label) || 0) + 1);
      return [...counts.entries()]
        .sort((a, b) => b[1] - a[1] || String(a[0]).localeCompare(String(b[0])));
    };
    const section = (title, entries, total) => {
      if (!entries.length) return '';
      const rows = entries.map(([label, n]) => `<div class="type-row">
          <span class="type-name" title="${escapeHtml(String(label))}">${escapeHtml(String(label))}</span>
          <span class="type-count">${n}</span>
          <span class="type-share">${total ? Math.round((n / total) * 100) : 0}%</span>
        </div>`).join('');
      return `<div class="type-section">
        <div class="type-row type-head">
          <span>${escapeHtml(title)}</span><span class="type-count">${t('typeCount')}</span><span class="type-share">%</span>
        </div>${rows}
      </div>`;
    };
    el.typeBreakdown.innerHTML = nodes.length || links.length
      ? section(t('vertices'), byType(nodes), nodes.length) + section(t('edges'), byType(links), links.length)
      : '';
  }

  function assignEdgeArcs(links) {
    const keyOf = (endpoint) => (endpoint && typeof endpoint === 'object' ? endpoint.key : endpoint);
    const groups = new Map();
    for (const l of links) {
      const s = keyOf(l.source);
      const t = keyOf(l.target);
      const key = s < t ? `${s}|${t}` : `${t}|${s}`;
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(l);
    }
    for (const group of groups.values()) {
      if (group.length < 2) {
        group[0].arc = 0;
        continue;
      }
      // a stable order, so the arcs do not swap places between renders
      group.sort((a, b) => (a.label === b.label
        ? String(a.id).localeCompare(String(b.id)) : a.label.localeCompare(b.label)));
      const middle = (group.length - 1) / 2;
      group.forEach((l, i) => { l.arc = (i - middle) * EDGE_ARC_STEP; });
    }
  }

  function setEdgeGeometry(el, l) {
    const x1 = l.source.x, y1 = l.source.y, x2 = l.target.x, y2 = l.target.y;
    const dx = x2 - x1, dy = y2 - y1;
    const len = Math.hypot(dx, dy) || 1;
    const off = Math.max(0, Math.min(EDGE_NODE_GAP, len / 2 - 0.5));
    const ux = dx / len, uy = dy / len;
    const sx = x1 + ux * off, sy = y1 + uy * off;
    const ex = x2 - ux * off, ey = y2 - uy * off;
    if (l.layout && !l.arc) {
      // a hierarchy or a layered DAG gets a smooth link that leaves its source along one axis and arrives
      // at its target along the same one - the shape d3's linkHorizontal/linkVertical draw, which is what
      // makes an edge of a layout read as *connected* rather than as a straight line through the picture
      const horizontal = treeMode === 'lr' || treeMode === 'rl'
        || treeMode === 'dag-lr' || treeMode === 'dag-rl';
      const c1x = horizontal ? (sx + ex) / 2 : sx;
      const c1y = horizontal ? sy : (sy + ey) / 2;
      const c2x = horizontal ? (sx + ex) / 2 : ex;
      const c2y = horizontal ? ey : (sy + ey) / 2;
      el.setAttribute('d', `M ${sx} ${sy} C ${c1x} ${c1y} ${c2x} ${c2y} ${ex} ${ey}`);
      return;
    }
    const arc = l.arc || 0;
    if (!arc) {
      el.setAttribute('d', `M ${sx} ${sy} L ${ex} ${ey}`);
      return;
    }
    // quadratic curve bulging sideways; the marker follows its end tangent, so the
    // arrow head still points at the target
    const cx = (sx + ex) / 2 - uy * arc;
    const cy = (sy + ey) / 2 + ux * arc;
    el.setAttribute('d', `M ${sx} ${sy} Q ${cx} ${cy} ${ex} ${ey}`);
  }

  // ---- legend filter (vertex type, edge type or community) ----
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
    const rows = root.selectAll('.node');
    const edges = root.selectAll('.edge');
    if (!legendFilter) {
      rows.classed('legend-hit', false).classed('legend-dim', false);
      edges.classed('legend-hit', false).classed('legend-dim', false);
      showLegendCount(null);
      scheduleSyncView3D(); // clearing a filter is a change of state too: the 3D view has to see it
      return;
    }
    const communityOf = algoState && algoState.type === 'community' ? algoState.communityOf : null;
    // an edge selection keeps the endpoints of those edges visible, so a highlighted edge is not
    // left hanging between two dimmed nodes
    const highlightedEndpoints = new Set();
    if (legendFilter.mode === 'edge') {
      edges.each(function (d) {
        if (!legendFilter.values.has(String(d.label))) return;
        highlightedEndpoints.add(endpointKey(d.source));
        highlightedEndpoints.add(endpointKey(d.target));
      });
    }
    const nodeHit = (d) => (legendFilter.mode === 'label'
      ? legendFilter.values.has(legendKey(d))
      : legendFilter.mode === 'edge'
        ? highlightedEndpoints.has(d.key) // the endpoints of a highlighted edge stay visible
        : !!communityOf && legendFilter.values.has(String(communityOf.get(d.key))));
    const keepEdge = (d) => (legendFilter.mode === 'edge'
      ? legendFilter.values.has(String(d.label))
      : nodeHit(d.source) && nodeHit(d.target));
    rows.classed('legend-hit', nodeHit).classed('legend-dim', (d) => !nodeHit(d));
    edges.classed('legend-hit', keepEdge).classed('legend-dim', (d) => !keepEdge(d));
    // how much the selection actually covers, in the bottom-left corner
    showLegendCount({ vertices: rows.filter(nodeHit).size(), edges: edges.filter(keepEdge).size() });
    scheduleSyncView3D();
  }

  /** The bottom-left chip that says what the legend selection covers (null hides it). */
  function showLegendCount(counts) {
    if (!el.legendCount) return;
    if (!counts) {
      el.legendCount.hidden = true;
      if (el.valuePanel) el.valuePanel.classList.remove('shifted');
      return;
    }
    el.legendCount.textContent = `${t('legendSelected')} ${counts.vertices} ${t('vertices')} · `
      + `${counts.edges} ${t('edges')}`;
    el.legendCount.hidden = false;
    // the query-result window shares that corner: make room for the chip
    if (el.valuePanel) el.valuePanel.classList.add('shifted');
  }

  function applyPickedHighlight() {
    if (!root) return;
    root.selectAll('.node').classed('picked', (d) => picked.includes(d.key));
    scheduleSyncView3D();
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
      scheduleSyncView3D();
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
    scheduleSyncView3D();
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

  /**
   * Exports everything that is drawn as one CSV: a {@code kind} column says whether a row is a
   * vertex or an edge, so both always arrive together. (Two files at once were blocked by the
   * browser often enough that the edges never showed up - which read as "the edge is missing".)
   */
  function exportCsv() {
    const rows = [['kind', 'id', 'label', 'displayName', 'source', 'target',
      'createdTime', 'lastUpdatedTime', 'properties']];
    for (const n of currentNodes) {
      rows.push(['vertex', n.id, n.label, displayLabel(n), '', '',
        n.createdTime, n.lastUpdatedTime, n.properties || {}]);
    }
    for (const l of currentLinks) {
      rows.push(['edge', l.id, l.label, '', endpointId(l.source), endpointId(l.target),
        l.createdTime, l.lastUpdatedTime, l.properties || {}]);
    }
    downloadCsv('phoenixgraph.csv', toCsv(rows));
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
        if (!communityOf.has(n.key)) continue; // not part of the analysed subset (a mixed-type graph)
        const cid = communityOf.get(n.key);
        counts.set(cid, (counts.get(cid) || 0) + 1);
      }
      items = [...counts.entries()].sort((a, b) => a[0] - b[0]).map(([cid, cnt]) => ({
        color: communityColor(cid),
        label: truncate(`${t('community')} ${cid} · ${cnt} ${t('count')}`, 20),
        mode: 'community',
        value: cid,
        kind: 'vertex',
      }));
    } else {
      // one entry per legend group: the vertex type, split further by relation shard
      // when the id carries a "_<n>" suffix (so a sharded graph shows its shards), with how many
      // vertices it has - the edge entries below count theirs the same way
      const groups = new Map();
      for (const n of nodes) {
        const key = legendKey(n);
        if (!groups.has(key)) groups.set(key, { key, node: n, count: 0 });
        groups.get(key).count += 1;
      }
      items = [...groups.values()].map((g) => ({
        color: colorFor(g.node.label, g.node.id),
        label: truncate(`${g.key} · ${g.count}`, 20),
        mode: 'label',
        value: g.key,
        kind: 'vertex',
      }));
    }
    // then one entry per edge type: selecting it highlights those edges (and keeps their
    // endpoints), selecting it again clears the selection. No heading - a blank row separates the
    // two groups instead.
    const edgeGroups = new Map();
    for (const l of currentLinks) edgeGroups.set(l.label, (edgeGroups.get(l.label) || 0) + 1);
    let firstEdge = true;
    for (const [label, n] of [...edgeGroups.entries()]
      .sort((a, b) => b[1] - a[1] || String(a[0]).localeCompare(String(b[0])))) {
      items.push({
        color: colorFor(label, String(label)),
        label: truncate(`${label} · ${n}`, 20),
        mode: 'edge',
        value: String(label),
        kind: 'edge',
        gap: firstEdge,
      });
      firstEdge = false;
    }
    if (!items.length) return;
    const legend = svg.append('g').attr('class', 'legend').attr('transform', 'translate(14, 60)');
    const ROW = 24;
    const GAP = 12;
    const height = items.length * ROW + 14 + (firstEdge ? 0 : GAP);
    legend.append('rect')
      .attr('width', 158)
      .attr('height', height)
      .attr('rx', 11)
      .attr('fill', '#ffffff').attr('fill-opacity', 0.95)
      .attr('stroke', '#e4e9f2');
    let y = 8;
    items.forEach((item) => {
      if (item.gap) y += GAP;
      const active = !!legendFilter && legendFilter.mode === item.mode
        && legendFilter.values.has(String(item.value));
      const g = legend.append('g')
        .attr('class', 'legend-item')
        .attr('transform', `translate(9, ${y})`)
        .classed('active', active);
      y += ROW;
      g.on('click', (event) => { event.stopPropagation(); toggleLegendFilter(item.mode, item.value); });
      g.on('mousedown', (event) => event.stopPropagation()); // don't start a canvas pan
      g.append('rect').attr('class', 'bg').attr('width', 140).attr('height', 20).attr('rx', 7);
      if (item.kind === 'edge') {
        // an edge swatch is a line, so the legend says at a glance that it is one
        g.append('line').attr('class', 'swatch')
          .attr('x1', 3).attr('y1', 10).attr('x2', 17).attr('y2', 10)
          .attr('stroke', item.color).attr('stroke-width', 2.6).attr('stroke-linecap', 'round');
        g.append('text').attr('x', 25).attr('y', 14).text(item.label);
        return;
      }
      g.append('rect').attr('class', 'swatch')
        .attr('x', 3).attr('y', 3).attr('width', 14).attr('height', 14).attr('rx', 5).attr('fill', item.color);
      g.append('text').attr('x', 25).attr('y', 14).text(item.label);
    });
  }

  // ---- algorithm-driven coloring (louvain / cc / pagerank / the centralities) ----
  function communityColor(cid) {
    const hue = (cid * 137.508) % 360; // golden-angle spacing for distinct hues
    return `hsl(${hue.toFixed(1)}, 62%, 52%)`;
  }

  /**
   * A centrality is one number per vertex, so the canvas shows it as a heat: the lowest score of the
   * analysed set is cold, the highest is warm - the same set whose top 20% carries the gold ring.
   */
  function scoreColor(score) {
    const range = algoState && algoState.scoreRange;
    if (!range || !(range.max > range.min)) {
      return '#f6c85f'; // every vertex scored the same: one colour says it
    }
    const t = (score - range.min) / (range.max - range.min);
    return d3.interpolateYlOrRd(0.12 + 0.8 * t);
  }

  /**
   * The colour of an edge: by its relation type, through the same hash-of-label colour the legend's edge
   * swatch uses. Before this every edge was one grey, which made the legend claim a colour the drawing did
   * not have - and left a graph whose edges are of several kinds (Payment, owns, contributesTo) with no way
   * to tell them apart by looking. It reaches the stylesheet as the `--edge-color` custom property, so the
   * highlight rules (search/legend/path) keep overriding it the way they always did.
   */
  function edgeColor(l) {
    return colorFor(l.label, String(l.label));
  }

  function nodeColor(d) {
    if (algoState && algoState.type === 'community') {
      if (!algoState.communityOf.has(d.key)) {
        return '#c9d1e0'; // not part of the analysed subset (another vertex type)
      }
      return communityColor(algoState.communityOf.get(d.key));
    }
    if (algoState && algoState.type === 'score') {
      if (!algoState.scores.has(d.key)) {
        return '#c9d1e0'; // outside the analysed subset, like a community run on a mixed graph
      }
      return scoreColor(algoState.scores.get(d.key));
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

  /** The hop distance from a source to every vertex it reaches (one breadth-first walk). */
  function bfsDistances(adj, source) {
    const distance = new Map([[source, 0]]);
    const queue = [source];
    for (let head = 0; head < queue.length; head += 1) {
      const v = queue[head];
      const next = distance.get(v) + 1;
      for (const w of adj.get(v) || []) {
        if (!distance.has(w)) { distance.set(w, next); queue.push(w); }
      }
    }
    return distance;
  }

  /** Degree centrality: how many edges of the drawn subgraph touch a vertex. */
  function degreeScores(nodes, adj) {
    const scores = new Map();
    for (const n of nodes) scores.set(n.key, (adj.get(n.key) || []).length);
    return scores;
  }

  /**
   * Closeness centrality in the Wasserman-Faust form: a vertex of a small component is measured
   * against the vertices it can reach rather than against the whole graph.
   */
  function closenessScores(nodes, adj) {
    const scores = new Map();
    for (const n of nodes) {
      const distance = bfsDistances(adj, n.key);
      let sum = 0;
      for (const d of distance.values()) sum += d;
      const reach = distance.size - 1; // without the vertex itself, which sits at zero
      scores.set(n.key, reach <= 0 || sum <= 0 ? 0 : (reach * reach) / ((nodes.length - 1) * sum));
    }
    return scores;
  }

  /** Betweenness centrality by Brandes' algorithm, normalised by the number of pairs. */
  function betweennessScores(nodes, adj) {
    const scores = new Map(nodes.map((n) => [n.key, 0]));
    for (const n of nodes) brandesFrom(adj, n.key, scores);
    const pairs = ((nodes.length - 1) * (nodes.length - 2)) / 2;
    const out = new Map();
    for (const [key, raw] of scores) out.set(key, pairs <= 0 ? 0 : raw / 2 / pairs);
    return out;
  }

  /** One source of Brandes: the shortest-path counts, then the dependencies back down the levels. */
  function brandesFrom(adj, source, scores) {
    const distance = new Map([[source, 0]]);
    const sigma = new Map([[source, 1]]);
    const delta = new Map();
    const predecessors = new Map();
    const order = [source];
    for (let head = 0; head < order.length; head += 1) {
      const v = order[head];
      for (const w of adj.get(v) || []) {
        if (!distance.has(w)) { distance.set(w, distance.get(v) + 1); order.push(w); }
        if (distance.get(w) === distance.get(v) + 1) {
          sigma.set(w, (sigma.get(w) || 0) + sigma.get(v));
          if (!predecessors.has(w)) predecessors.set(w, []);
          predecessors.get(w).push(v);
        }
      }
    }
    for (let i = order.length - 1; i >= 0; i -= 1) {
      const w = order[i];
      for (const v of predecessors.get(w) || []) {
        const share = (1 + (delta.get(w) || 0)) * (sigma.get(v) / sigma.get(w));
        delta.set(v, (delta.get(v) || 0) + share);
      }
      if (w !== source) scores.set(w, scores.get(w) + (delta.get(w) || 0));
    }
  }

  /**
   * Eigenvector centrality by power iteration over the drawn links, thirty rounds and a unit vector at
   * the end - the same normalisation the server-side step applies.
   */
  function eigenvectorScores(nodes, adj) {
    const incoming = new Map(nodes.map((n) => [n.key, []]));
    for (const [key, targets] of adj) {
      for (const target of targets) {
        if (incoming.has(target)) incoming.get(target).push(key);
      }
    }
    let x = new Map(nodes.map((n) => [n.key, 1]));
    for (let iteration = 0; iteration < 30; iteration += 1) {
      const next = new Map();
      for (const n of nodes) {
        let sum = 0;
        for (const u of incoming.get(n.key)) sum += x.get(u) || 0;
        next.set(n.key, sum);
      }
      x = unitVector(next);
    }
    return x;
  }

  /** A score vector of unit L2 length, signed so its largest entry is positive. */
  function unitVector(scores) {
    let squares = 0;
    let largest = 0;
    for (const value of scores.values()) {
      squares += value * value;
      if (Math.abs(value) > Math.abs(largest)) largest = value;
    }
    const norm = Math.sqrt(squares);
    const sign = largest < 0 ? -1 : 1;
    const out = new Map();
    for (const [key, value] of scores) out.set(key, norm === 0 ? 0 : (value / norm) * sign);
    return out;
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
        // A component, or a community, is a group of one kind of vertex: on a graph that mixes
        // Account, IP and Phone a "component" would just be everything reachable through an
        // address, which is not what either algorithm promises. So they run on the largest single
        // type, and the UI says which one and dims the rest.
        const subset = largestTypeSubgraph(currentNodes, currentLinks);
        if (subset.skippedTypes.length) {
          const message = t('algoHomogeneous')
            .replaceAll('{algo}', t(name === 'cc' ? 'cc' : 'louvain'))
            .replaceAll('{type}', String(subset.label))
            .replaceAll('{n}', String(subset.nodes.length))
            .replaceAll('{others}', subset.skippedTypes.join(' / '))
            .replaceAll('{links}', String(subset.projected));
          toast(message, 5200);
          setStatus(message);
        }
        const communityOf = name === 'cc'
          ? connectedComponents(subset.nodes, subset.adj)
          : louvain(subset.nodes, subset.adj);
        algoState = { type: 'community', communityOf };
      } else if (name === 'pagerank') {
        const rank = pagerank(currentNodes, adj);
        const sorted = [...rank.entries()].sort((a, b) => b[1] - a[1]);
        const topCount = Math.max(1, Math.ceil(sorted.length * 0.2));
        algoState = { type: 'pagerank', topKeys: new Set(sorted.slice(0, topCount).map((e) => e[0])) };
      } else if (CENTRALITIES.includes(name)) {
        // a centrality is a property of one kind of vertex, exactly like a component: on a graph that
        // mixes Account, IP and Phone a closeness score would only say which addresses everything hangs
        // off. So they run on the largest single type too, with the other types projected into edges
        // between the kept vertices, and the canvas dims what was not analysed.
        const subset = largestTypeSubgraph(currentNodes, currentLinks);
        if (subset.skippedTypes.length) {
          const message = t('algoHomogeneous')
            .replaceAll('{algo}', t(name))
            .replaceAll('{type}', String(subset.label))
            .replaceAll('{n}', String(subset.nodes.length))
            .replaceAll('{others}', subset.skippedTypes.join(' / '))
            .replaceAll('{links}', String(subset.projected));
          toast(message, 5200);
          setStatus(message);
        }
        const scores = centralityScores(name, subset.nodes, subset.adj);
        const sorted = [...scores.entries()].sort((a, b) => b[1] - a[1]);
        const topCount = Math.max(1, Math.ceil(sorted.length * 0.2));
        const topKeys = new Set(sorted.slice(0, topCount).map((e) => e[0]));
        const scoreRange = [...scores.values()].reduce(
          (range, value) => ({ min: Math.min(range.min, value), max: Math.max(range.max, value) }),
          { min: 0, max: 0 });
        algoState = { type: 'score', scores, topKeys, scoreRange };
        const best = sorted.length ? nodeById(sorted[0][0]) : null;
        const summary = t('algoScore')
          .replaceAll('{algo}', t(name))
          .replaceAll('{top}', String(topCount))
          .replaceAll('{n}', String(scores.size))
          .replaceAll('{best}', best ? displayLabel(best) : '-');
        if (!subset.skippedTypes.length) {
          setStatus(summary);
        }
      }
    }
    refreshAlgoView();
  }

  /** The centralities the toolbar can colour the canvas with. */
  const CENTRALITIES = ['degree', 'closeness', 'betweenness', 'eigenvector'];

  /** One of the four centrality scores over the vertices and adjacency a run is about. */
  function centralityScores(name, nodes, adj) {
    if (name === 'degree') return degreeScores(nodes, adj);
    if (name === 'closeness') return closenessScores(nodes, adj);
    if (name === 'betweenness') return betweennessScores(nodes, adj);
    return eigenvectorScores(nodes, adj);
  }

  /** The drawn vertex with an id, for a message that names the top of a ranking. */
  function nodeById(key) {
    return currentNodes.find((n) => n.key === key) || null;
  }

  // ---- the map view: the entities of the selected graph that carry coordinates ---------------------
  //
  // It lives inside the canvas panel (not on a page of its own): the toolbar shows a map icon when the
  // selected instance can answer the question - its schema has a latitude/longitude pair on a vertex
  // type and its store can scan a whole type (a keyed store cannot, so those instances get no icon).
  // Clicking swaps the canvas for the map; clicking again brings the picture back.

  let mapOpen = false;
  /** What the selected graph's map needs (null when it has none). */
  let mapPlan = null;
  /** Per-graph schema plans, so switching back and forth does not re-read the schema. */
  const geoPlans = new Map();
  /** Per-graph "does it hold a single coordinate", so the probe runs once. */
  const geoHasData = new Map();
  /** Where the basemap stands ({kind, provider}) and how the points are doing ({kind, n|message}). */
  let mapBasemap = null;
  let mapPointsState = null;
  /** The rows of the last answer: a query that read coordinates drives the map with them. */
  let lastAnswerRows = null;
  /** What the open map shows: 'query' (the last answer's points) or 'all' (the graph's own). */
  let mapSource = 'all';

  /**
   * What a graph's map needs: the vertex type carrying latitude/longitude, the property a point is
   * labelled with and the extra properties worth reading (the kind of entity, the years of an event).
   * Null when no type has a coordinate pair - then there is nothing to draw.
   */
  function geoPlanOf(schema) {
    for (const type of (schema && schema.vertexSchemas) || []) {
      const properties = (type.properties || []).map((p) => p.name);
      const has = (name) => properties.some((p) => p && p.toLowerCase() === name);
      if (!has('latitude') || !has('longitude')) { continue; }
      return {
        type: type.name,
        latitude: properties.find((p) => p && p.toLowerCase() === 'latitude'),
        longitude: properties.find((p) => p && p.toLowerCase() === 'longitude'),
        label: ((type.properties || []).find((p) => p.display) || {}).name || null,
        extras: ['entityType', 'eventYear', 'eventYearEnd'].filter((name) => properties.includes(name)),
      };
    }
    return null;
  }

  /** The selected graph's map plan, read from its schema once. */
  async function geoPlanFor(graphName) {
    if (geoPlans.has(graphName)) { return geoPlans.get(graphName); }
    let plan = null;
    try {
      const response = await fetch(`/api/schema/${encodeURIComponent(graphName)}`);
      plan = response.ok ? geoPlanOf(await response.json()) : null;
    } catch (err) {
      plan = null;
    }
    geoPlans.set(graphName, plan);
    return plan;
  }

  /** Whether a store can answer a whole-type scan (the ordered tables can, the keyed ones cannot). */
  function storageScans(storage) {
    return String(storage || '').startsWith('RocksDB');
  }

  /**
   * Shows or hides the map icon for the selected instance. Three things have to hold, and the icon is
   * simply absent when they do not - a graph without coordinates gets no map button at all:
   * <ol>
   *   <li>the schema declares a latitude/longitude pair on a vertex type;</li>
   *   <li>the store can scan that type (an Aerospike instance answers by key only, and
   *       {@code g.V('Entity')} is refused there);</li>
   *   <li>the graph actually holds one coordinate: a declared property with no data behind it would
   *       open an empty map, which reads as a broken button rather than as an empty graph.</li>
   * </ol>
   */
  async function refreshMapButton() {
    closeMap();
    el.mapBtn.hidden = true;
    mapPlan = null;
    if (!currentGraph) { return; }
    const plan = await geoPlanFor(currentGraph.name);
    if (!plan || !storageScans(currentGraph.storage)) { return; }
    if (!(await graphHasCoordinates(currentGraph.name, plan))) { return; }
    mapPlan = plan;
    el.mapBtn.hidden = false;
  }

  /** Whether the instance holds at least one entity with coordinates (probed once per graph). */
  async function graphHasCoordinates(graphName, plan) {
    if (geoHasData.has(graphName)) { return geoHasData.get(graphName); }
    let found = false;
    try {
      const query = "g.V('" + plan.type + "').has('" + plan.latitude + "').limit(1).valueMap('"
        + plan.latitude + "','" + plan.longitude + "')";
      const response = await fetch('/api/query', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ graphName: graphName, language: 'gremlin', query: query,
          includeResult: true, returnSubgraph: false }),
      });
      const body = await response.json();
      const rows = Array.isArray(body) ? body : (body && body.result) || [];
      found = !body.error && rows.length > 0;
    } catch (err) {
      found = false;
    }
    geoHasData.set(graphName, found);
    return found;
  }

  function toggleMap() {
    if (mapOpen) { closeMap(); } else { openMap(); }
  }

  async function openMap() {
    if (!mapPlan) { toast(t('mapNoGeo')); return; }
    if (!window.PhoenixGeoMap || !window.PhoenixGeoMap.available()) {
      mapBasemap = { kind: 'noleaflet' };
      renderMapNote();
      toast(t('mapNoLeaflet'));
      return;
    }
    mapOpen = true;
    if (view3dOpen) closeView3D(); // one canvas, one view: 3D gives way to the map as much as the other way round
    mapBasemap = null;
    mapPointsState = { kind: 'loading' };
    renderMapNote();
    el.mapView.hidden = false;
    if (el.mapClose) { el.mapClose.hidden = false; }
    el.canvasWrap.classList.add('map-open');
    el.mapBtn.classList.add('active');
    el.mapBtn.setAttribute('aria-pressed', 'true');
    el.mapBtn.dataset.i18nTitle = 'mapOpenTitle';
    el.mapBtn.title = t('mapOpenTitle');
    hideValue(); // the floating value window belongs to the canvas underneath
    await showMapPoints('auto');
  }

  /**
   * Puts points on the map. `source` is 'auto' (the last answer's points when it has any, else every
   * coordinate of the graph), 'query' (the last answer) or 'all' (the graph's own list).
   */
  async function showMapPoints(source) {
    const fromQuery = geoAnswerPoints(mapPlan);
    const wanted = source === 'auto' ? (fromQuery.length ? 'query' : 'all') : source;
    if (wanted === 'query' && fromQuery.length) {
      mapSource = 'query';
      applyMapPoints(fromQuery);
      return;
    }
    // the graph's own list is a query of the map's own: it has to be read before it can be drawn
    mapSource = 'all';
    mapPointsState = { kind: 'loading' };
    renderMapNote();
    try {
      const points = await geoPoints(mapPlan);
      if (!mapOpen) { return; } // closed while the query ran
      applyMapPoints(points);
    } catch (err) {
      mapPointsState = { kind: 'failed', message: err && err.message ? err.message : String(err) };
      renderMapNote();
      if (window.PhoenixGeoMap) { window.PhoenixGeoMap.hide(el.mapView); }
    }
  }

  /** Hands the points to the map component and says in the note what was drawn. */
  function applyMapPoints(points) {
    mapPointsState = points.length
      ? { kind: 'points', n: points.length, source: mapSource }
      : { kind: 'empty' };
    renderMapNote();
    const shown = window.PhoenixGeoMap.show(el.mapView, points, { onNote: onMapBasemap });
    if (!shown) { closeMap(); }
  }

  /**
   * The points of the last answer: its rows that carry the coordinate columns. A query that read them
   * (a {@code valueMap('name','latitude','longitude')}) maps the very rows it returned; one that read
   * something else leaves the map with the graph's own list.
   */
  function geoAnswerPoints(plan) {
    if (!plan || !lastAnswerRows) { return []; }
    return lastAnswerRows
      .filter((row) => row && typeof row === 'object' && !Array.isArray(row))
      .map((row) => geoPoint(plan, row))
      .filter(Boolean);
  }

  function closeMap() {
    if (!mapOpen) { return; }
    if (window.PhoenixGeoMap) { window.PhoenixGeoMap.hide(el.mapView); }
    el.mapView.hidden = true;
    if (el.mapClose) { el.mapClose.hidden = true; }
    el.mapNote.hidden = true;
    el.canvasWrap.classList.remove('map-open');
    el.mapBtn.classList.remove('active');
    el.mapBtn.setAttribute('aria-pressed', 'false');
    el.mapBtn.dataset.i18nTitle = 'mapTitle';
    el.mapBtn.title = t('mapTitle');
    mapOpen = false;
    mapBasemap = null;
    mapPointsState = null;
    // the canvas was covered while it may have been resized: draw it again
    render();
  }

  /**
   * Every entity of the graph that carries a coordinate, read with the schema's own names (nothing about
   * sanguo is hard-coded: any graph with a latitude/longitude pair gets a map).
   */
  async function geoPoints(plan) {
    const columns = [plan.label, plan.latitude, plan.longitude].concat(plan.extras).filter(Boolean);
    const query = "g.V('" + plan.type + "').has('" + plan.latitude + "').valueMap("
      + columns.map((column) => "'" + column + "'").join(',') + ")";
    const response = await fetch('/api/query', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        graphName: currentGraph.name,
        language: 'gremlin',
        query: query,
        includeResult: true,
        returnSubgraph: false,
      }),
    });
    const body = await response.json();
    if (body && body.error) { throw new Error(body.error); }
    if (!response.ok) { throw new Error('HTTP ' + response.status); }
    const rows = Array.isArray(body) ? body : (body && body.result) || [];
    return rows.map((row) => geoPoint(plan, row)).filter(Boolean);
  }

  /** One row of the answer as a point: without usable coordinates the point is dropped. */
  function geoPoint(plan, row) {
    const latitude = propertyNumber(row[plan.latitude]);
    const longitude = propertyNumber(row[plan.longitude]);
    if (!isFinite(latitude) || !isFinite(longitude)) { return null; }
    const kind = scalarValue(row.entityType) || plan.type;
    const from = propertyNumber(row.eventYear);
    const to = propertyNumber(row.eventYearEnd);
    const note = isFinite(from)
      ? t(isFinite(to) ? 'mapYear' : 'mapYearSingle').replace('{from}', String(from)).replace('{to}', String(to))
      : '';
    return {
      name: String(scalarValue(row[plan.label]) || ''),
      latitude: latitude,
      longitude: longitude,
      kind: kind,
      kindLabel: t(kind === 'Place' ? 'mapPlace' : (kind === 'Event' ? 'mapEvent' : 'mapEntity')),
      note: note,
    };
  }

  /** A property value that may arrive as a one-element list (a multi-valued property). */
  function scalarValue(value) {
    return Array.isArray(value) ? value[0] : value;
  }

  function propertyNumber(value) {
    const scalar = scalarValue(value);
    return scalar === null || scalar === undefined || scalar === '' ? NaN : Number(scalar);
  }

  /** The basemap the component ended up with (it falls back when OSM is unreachable). */
  function onMapBasemap(message) {
    mapBasemap = message || null;
    renderMapNote();
  }

  /** The note under the map: what was drawn, which basemap is behind it, and how to switch scope. */
  function renderMapNote() {
    const lines = [];
    if (mapPointsState) {
      if (mapPointsState.kind === 'points') {
        lines.push(t(mapPointsState.source === 'query' ? 'mapPointsQuery' : 'mapPointsGraph')
          .replace('{n}', String(mapPointsState.n)));
      } else if (mapPointsState.kind === 'failed') {
        lines.push(t('mapFailed') + mapPointsState.message);
      } else {
        lines.push(t(mapPointsState.kind === 'empty' ? 'mapEmpty' : 'mapLoading'));
      }
    }
    if (mapBasemap) {
      if (mapBasemap.kind === 'provider' || mapBasemap.kind === 'fallback') {
        const template = mapBasemap.kind === 'fallback' ? 'mapBasemapFallback' : 'mapBasemap';
        lines.push(t(template).replace('{provider}', String(mapBasemap.provider || '')));
      } else if (mapBasemap.kind === 'none') {
        lines.push(t('mapBasemapNone'));
      } else if (mapBasemap.kind === 'noleaflet') {
        lines.push(t('mapNoLeaflet'));
      }
    }
    // a query that returned coordinates can drive the map, and the note offers the other scope
    const switchable = geoAnswerPoints(mapPlan).length > 0;
    const toggle = switchable
      ? `<button type="button" class="note-link" data-map-source="${mapSource === 'query' ? 'all' : 'query'}">`
        + escapeHtml(t(mapSource === 'query' ? 'mapShowAll' : 'mapShowQuery')) + '</button>'
      : '';
    el.mapNote.innerHTML = lines.map((line) => `<div>${escapeHtml(line)}</div>`).join('') + toggle;
    el.mapNote.hidden = !lines.length && !toggle;
  }

  /** The note's scope switch: "all 26" or "only this query's points". */
  function onMapNoteClick(event) {
    const button = event.target.closest && event.target.closest('[data-map-source]');
    if (button) { showMapPoints(button.dataset.mapSource); }
  }

  // ---- 3D view: the same answer, from an angle, inside the panel ------------------------------------
  //
  // 3D is a *mode of the canvas*, not a page of its own (it used to be graph-3d.html, which meant a
  // second data path, a second palette and no shared highlighting). The renderer (js/graph3d.js) knows
  // nothing about graphs or queries: this side resolves what the 2D canvas is showing - the fill of every
  // vertex, the stroke of every edge, the alpha a filter left behind, the ring a highlight drew - and
  // hands those computed colours over. That is what makes a legend filter, a search hit, a shortest path
  // or a PageRank heat look the same in 3D as in 2D: they *are* the same values.

  let view3dOpen = false;
  let view3dController = null;
  let sync3dQueued = false;

  function ensureView3D() {
    if (view3dController || !window.PhoenixGraph3D || !el.view3d) return view3dController;
    view3dController = window.PhoenixGraph3D.mount(el.view3d, {
      autoRelax: true, // relax on animation frames, so a new answer visibly settles
      onPick: (key) => {
        const node = currentNodes.find((n) => n.key === key);
        if (node) handleNodePick(node);
      },
    });
    // what a test (or a probe) reads back, the way the old standalone 3D page exposed window.graph3d
    window.phoenixView3D = view3dController;
    return view3dController;
  }

  function toggleView3D() {
    if (view3dOpen) closeView3D(); else openView3D();
  }

  function openView3D() {
    if (!ensureView3D()) { toast(t('view3d')); return; }
    if (mapOpen) closeMap(); // one canvas, one view: the map and 3D cannot both cover it
    view3dOpen = true;
    el.view3d.hidden = false;
    if (el.view3dClose) el.view3dClose.hidden = false;
    el.canvasWrap.classList.add('view3d-open');
    el.view3dBtn.classList.add('active');
    el.view3dBtn.setAttribute('aria-pressed', 'true');
    el.view3dBtn.dataset.i18nTitle = 'view3dOpenTitle';
    el.view3dBtn.title = t('view3dOpenTitle');
    hideValue(); // the floating value window belongs to the canvas underneath
    // a canvas that was hidden has no size: measure it now that it is displayed, then draw
    view3dController.resize();
    syncView3D();
  }

  function closeView3D() {
    if (!view3dOpen) return;
    view3dOpen = false;
    if (view3dController) view3dController.stop();
    el.view3d.hidden = true;
    if (el.view3dClose) el.view3dClose.hidden = true;
    el.canvasWrap.classList.remove('view3d-open');
    el.view3dBtn.classList.remove('active');
    el.view3dBtn.setAttribute('aria-pressed', 'false');
    el.view3dBtn.dataset.i18nTitle = 'view3d';
    el.view3dBtn.title = t('view3d');
    el.legendCount.hidden = true;
    // the canvas was covered while it may have been resized, and the hint above belongs to 3D
    render();
  }

  /**
   * Reads the *rendered* state of the 2D canvas: one record per vertex and per edge with the colour, the
   * alpha and the ring it currently has. Reading the DOM instead of recomputing the filters means 3D can
   * never disagree with 2D about what is dimmed, hit or selected - including for the filters added later.
   */
  function readCanvasState() {
    const nodes = [];
    const links = [];
    const dimmed = new Set(['legend-dim', 'path-dim', 'search-dim', 'algo-out']);
    if (!root) return { nodes, links };
    root.selectAll('.node').each(function (d) {
      const element = this;
      const body = element.querySelector('circle.body');
      const ring = element.querySelector('circle.ring');
      const isDim = [...dimmed].some((name) => element.classList.contains(name));
      let stroke = null;
      if (ring && !isTransparent(getComputedStyle(ring).stroke)) stroke = getComputedStyle(ring).stroke;
      else if (element.classList.contains('legend-hit') || element.classList.contains('search-hit')
        || element.classList.contains('path-hit') || element.classList.contains('path-shortest')) {
        stroke = getComputedStyle(body).stroke;
      }
      nodes.push({
        key: d.key,
        id: d.id,
        label: displayLabel(d),
        fill: body ? getComputedStyle(body).fill : nodeColor(d),
        alpha: isDim ? 0.18 : 1,
        ring: isDim ? null : stroke,
        seed: { x: d.x, y: d.y },
      });
    });
    root.selectAll('.edge').each(function (d) {
      const style = getComputedStyle(this);
      const endpoint = (side) => {
        const value = d[side];
        return value && typeof value === 'object' ? value.key : value;
      };
      const opacity = Number(style.strokeOpacity);
      links.push({
        source: endpoint('source'),
        target: endpoint('target'),
        stroke: style.stroke,
        // an edge starts at half opacity in 2D (that is what the stylesheet says), so 3D normalises it:
        // 0.5 is "nothing special", anything lower is a filter that dimmed it
        alpha: Number.isFinite(opacity) ? Math.min(1, opacity / 0.5) : 1,
        width: Number(style.strokeWidth) || 1.44,
        dashed: !!d.reversed, // the DAG's cycle-breaking edges are dashed in 2D; keep that in 3D
      });
    });
    return { nodes, links };
  }

  function isTransparent(color) {
    const text = String(color || '');
    return !text || text === 'none' || text === 'transparent' || /^rgba\(0,\s*0,\s*0,\s*0\)$/.test(text);
  }

  /** Hands the current answer to the 3D renderer. Called after every render and after every filter. */
  function syncView3D() {
    if (!view3dOpen || !ensureView3D()) return;
    const { nodes, links } = readCanvasState();
    view3dController.resize();
    view3dController.setData(nodes, links);
    view3dController.start();
    refreshView3DHint();
  }

  /** Coalesces the many state changes one interaction makes (a click dims, selects and re-renders). */
  function scheduleSyncView3D() {
    if (!view3dOpen || sync3dQueued) return;
    sync3dQueued = true;
    const run = () => { sync3dQueued = false; syncView3D(); };
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(run); else run();
  }

  /** Says what the 3D view is showing, in the same place the tree/DAG hint uses. */
  function refreshView3DHint() {
    if (!view3dOpen || !el.legendCount) return;
    el.legendCount.hidden = false;
    el.legendCount.textContent = t('view3dHint')
      .replace('{n}', String(currentNodes.length))
      .replace('{e}', String(currentLinks.length));
  }

  /**
   * The largest single vertex type, with the other types folded away, and the types that were left
   * out. Connected components and Louvain only mean something within one kind of vertex, so a mixed
   * graph is narrowed to the type with the most vertices - and the vertices of the other types are
   * *projected* into edges between the kept ones: two accounts that share an IP address become
   * neighbours, which is what an analyst means by "accounts linked through this address". Without the
   * projection every account would be a component of its own, because no edge joins two accounts
   * directly. A graph of one type comes back unchanged, with nothing skipped and nothing projected.
   */
  function largestTypeSubgraph(nodes, links) {
    const byType = new Map();
    for (const n of nodes) {
      if (!byType.has(n.label)) byType.set(n.label, []);
      byType.get(n.label).push(n);
    }
    let best = null;
    for (const [label, group] of byType) {
      if (!best || group.length > best.nodes.length) best = { label, nodes: group };
    }
    if (!best) {
      return { nodes, links, adj: buildAdjacency(nodes, links), label: null, skippedTypes: [], projected: 0 };
    }
    if (byType.size === 1) {
      return { nodes, links, adj: buildAdjacency(nodes, links), label: best.label, skippedTypes: [], projected: 0 };
    }

    const keep = new Set(best.nodes.map((n) => n.key));
    const keptLinks = [];
    const through = new Map(); // a vertex of another type -> the kept vertices it reaches
    for (const l of links) {
      const source = endpointKey(l.source);
      const target = endpointKey(l.target);
      const sourceKept = keep.has(source);
      const targetKept = keep.has(target);
      if (sourceKept && targetKept) {
        keptLinks.push({ id: l.id, label: l.label, source, target });
      } else if (sourceKept !== targetKept) {
        const bridge = sourceKept ? target : source;
        const reached = sourceKept ? source : target;
        if (!through.has(bridge)) through.set(bridge, new Set());
        through.get(bridge).add(reached);
      }
    }
    let projected = 0;
    for (const [bridge, reached] of through) {
      const group = [...reached];
      if (group.length < 2 || group.length > PROJECTION_MAX) {
        continue; // a hub of another type would turn into a clique: skip it rather than explode
      }
      for (let i = 0; i < group.length; i += 1) {
        for (let j = i + 1; j < group.length; j += 1) {
          keptLinks.push({
            id: `projected:${bridge}:${group[i]}:${group[j]}`,
            label: t('projectedEdge'),
            source: group[i],
            target: group[j],
          });
          projected += 1;
        }
      }
    }
    return {
      nodes: best.nodes,
      links: keptLinks,
      adj: buildAdjacency(best.nodes, keptLinks),
      label: best.label,
      skippedTypes: [...byType.keys()].filter((label) => label !== best.label),
      projected,
    };
  }

  function refreshAlgoView() {
    if (!root) return;
    root.selectAll('.node').select('circle.body')
      .attr('fill', (d) => nodeColor(d));
    root.selectAll('.node')
      .classed('pr-highlight', (d) => !!algoState && !!algoState.topKeys && algoState.topKeys.has(d.key))
      // a vertex outside the analysed subset (community detection or a centrality on a mixed graph) is
      // dimmed, so the picture says which vertices the colours are about
      .classed('algo-out', (d) => !!algoState
        && ((algoState.type === 'community' && !algoState.communityOf.has(d.key))
          || (algoState.type === 'score' && !algoState.scores.has(d.key))));
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
    handleNodePick(d);
  }

  /**
   * What a click on a vertex does, from either canvas: re-root the tree (the vertices are the hierarchy's
   * nodes), select it and open its properties - or, on a double click, expand one hop. 3D picks a vertex
   * through hit testing instead of the DOM, but this is the part that decides what the click *means*, so
   * both views behave identically.
   */
  function handleNodePick(d) {
    // in the tree layout a vertex is a root as much as it is an element: clicking it re-reads the whole
    // drawing from there (and still selects it, because the properties panel is the other question)
    if (treeMode) { treeRootKey = d.key; }
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
    if (treeMode) { render(); } // the tree is read from the new root right away
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
    scheduleSyncView3D();
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

  // The searchable fields of one element: its label, its id and one field per property value (plus
  // whatever the caller adds - an edge adds the ids of the vertices it connects).
  function elementFields(d, extra) {
    if (d.__fields === undefined) {
      const fields = [d.label, d.id, ...extra];
      for (const [key, value] of Object.entries(d.properties || {})) fields.push(key, flattenValue(value));
      d.__fields = fields.filter(Boolean).map((field) => String(field).toLowerCase());
    }
    return d.__fields;
  }

  // A token matches a field when the field contains it, or when the token reads as an abbreviation
  // of that field (the field's first letter, then the token's remaining letters in order, so "pmt"
  // finds "payment"). Anchoring the abbreviation keeps "ada" out of "radia" and "10018" out of
  // "pay_rep_1001_1008_0", which a free subsequence would happily match.
  function fieldMatches(field, token) {
    if (field.includes(token)) return true;
    return field.charCodeAt(0) === token.charCodeAt(0) && fuzzyMatch(token, field);
  }

  function elementMatches(d, extra, tokens) {
    const fields = elementFields(d, extra);
    return tokens.every((token) => fields.some((field) => fieldMatches(field, token)));
  }

  /** The search term the "nothing matches" hint was last shown for, so typing does not repeat it. */
  let lastNoMatch = null;

  function applySearch() {
    const query = el.searchInput.value.trim().toLowerCase();
    el.searchClear.hidden = !query;
    const nodes = root.selectAll('.node');
    const edges = root.selectAll('.edge');
    if (!query) {
      nodes.classed('search-hit', false).classed('search-dim', false);
      edges.classed('search-hit', false).classed('search-dim', false);
      lastNoMatch = null;
      scheduleSyncView3D();
      return;
    }
    const tokens = query.split(/\s+/).filter(Boolean);

    const hitNodes = new Set();
    nodes.each(function (d) { if (elementMatches(d, [], tokens)) hitNodes.add(d.key); });

    const hitEdges = new Set();
    const adjacent = new Set(); // endpoints of a matched edge, and neighbours of a matched vertex
    const contextEdges = new Set(); // edges that touch a matched vertex stay visible
    edges.each(function (d) {
      const key = d.label + '::' + d.id;
      const s = endpointKey(d.source);
      const t = endpointKey(d.target);
      if (elementMatches(d, [s, t], tokens)) {
        hitEdges.add(key);
        if (s) adjacent.add(s);
        if (t) adjacent.add(t);
      }
      if (hitNodes.has(s) || hitNodes.has(t)) contextEdges.add(key);
    });
    // a matched vertex keeps its neighbours - and the edges to them - visible, so the hit is read in
    // context instead of floating alone (the same courtesy a matched edge already gets)
    edges.each(function (d) {
      const key = d.label + '::' + d.id;
      if (!contextEdges.has(key)) return;
      const s = endpointKey(d.source);
      const t = endpointKey(d.target);
      if (s) adjacent.add(s);
      if (t) adjacent.add(t);
    });

    nodes
      .classed('search-hit', (d) => hitNodes.has(d.key))
      .classed('search-dim', (d) => !hitNodes.has(d.key) && !adjacent.has(d.key));
    edges
      .classed('search-hit', (d) => hitEdges.has(d.label + '::' + d.id))
      .classed('search-dim', (d) => !hitEdges.has(d.label + '::' + d.id)
        && !contextEdges.has(d.label + '::' + d.id));

    // the search only looks at what is drawn: say so when nothing here matches, rather than
    // leaving the user with a canvas that merely went dim
    if (!hitNodes.size && !hitEdges.size) {
      if (query.length >= 2 && lastNoMatch !== query) {
        lastNoMatch = query;
        toast(t('searchNoMatch').replace('{q}', query));
      }
    } else {
      lastNoMatch = null;
    }
    scheduleSyncView3D();
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
    const text = typeof v === 'object' ? JSON.stringify(v) : String(v);
    if (text.length <= VALUE_CLIP) return escapeHtml(text);
    // A long value - a text blob, a nested object, the properties column - would make one cell
    // unreadable and the table hundreds of lines tall. The cell shows a prefix and carries the whole
    // text, so one click opens it (see onValueClipClick); beyond VALUE_EXPAND_MAX even that is
    // abbreviated, because a ten-megabyte string in the DOM freezes the panel it was meant to explain.
    const full = text.length > VALUE_EXPAND_MAX
      ? text.slice(0, VALUE_EXPAND_MAX) + t('valueTooLong').replace('{n}', String(text.length - VALUE_EXPAND_MAX))
      : text;
    const short = text.slice(0, VALUE_CLIP) + '…';
    return `<span class="value-clip" data-short="${escapeHtml(short)}" data-full="${escapeHtml(full)}"`
      + ` title="${escapeHtml(t('valueClipHint'))}">${escapeHtml(short)}</span>`;
  }

  /** How many characters of one value a cell shows before it abbreviates the rest. */
  const VALUE_CLIP = 140;

  /** How much of a long value the click reveals: the DOM does not need the whole of a huge blob. */
  const VALUE_EXPAND_MAX = 20000;

  /**
   * Opens or closes an abbreviated value in a cell. The table (or the property list) keeps both forms
   * in the element itself, so opening one costs nothing and closing it puts the short form back.
   */
  function onValueClipClick(event) {
    const clip = event.target.closest && event.target.closest('.value-clip');
    if (!clip) return;
    const open = clip.dataset.open !== '1';
    clip.textContent = open ? clip.dataset.full : clip.dataset.short;
    clip.dataset.open = open ? '1' : '0';
    clip.classList.toggle('open', open);
    clip.title = t(open ? 'valueClipCollapse' : 'valueClipHint');
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
  // A hub keeps its edges in the aggregated edge tables (Aerospike-Edge) and can only be read
  // inside a creation-time window, so an expansion of such a vertex asks for a bounded slice:
  // a window wide enough to hold anything, plus a cap the storage stops at (the tables are
  // ordered by creation time). The window's two dates are the widest the step accepts.
  const HUB_WINDOW = { from: '1970-01-01T00:00:00', to: '2100-01-01T00:00:00' };
  const HUB_EDGE_LIMIT = 200;

  /** Whether an error says the vertex lives in the aggregated tables and needs a window. */
  function needsTimeWindow(error) {
    const message = String((error && error.message) || error || '');
    return message.includes('createdBetween') || message.includes('creation-time window');
  }

  /** The bounded form of a traversal: the same steps, inside a window, capped. */
  function boundedByWindow(query, limit) {
    const seed = query.match(/^g\.V\(([^)]*)\)/);
    if (!seed) return null;
    const steps = query.slice(seed[0].length);
    return `g.V(${seed[1]}).createdBetween('${HUB_WINDOW.from}','${HUB_WINDOW.to}')`
      + `${steps}.limit(${limit})`;
  }

  /**
   * Runs a traversal the UI built itself (an expansion), retrying it bounded when the vertex
   * turned out to be a hub: the retry reads the oldest {@link HUB_EDGE_LIMIT} edges of the
   * window and says so, instead of failing with the storage's explanation.
   */
  async function runExpansionQuery(query, label) {
    try {
      return { result: await runQuery(query), bounded: false };
    } catch (err) {
      const bounded = needsTimeWindow(err) ? boundedByWindow(query, HUB_EDGE_LIMIT) : null;
      if (!bounded) throw err;
      setStatus(`${label}：该点是大点，改为读取最近的 ${HUB_EDGE_LIMIT} 条（可用 createdBetween 指定时间范围）`);
      toast(t('hubBounded').replace('{n}', String(HUB_EDGE_LIMIT)));
      return { result: await runQuery(bounded), bounded: true };
    }
  }

  async function expand(d) {
    const query = `g.V('${d.id}','${d.label}').both()`;
    setStatus(`扩展 ${d.id}: ${query}`);
    try {
      const { result, bounded } = await runExpansionQuery(query, `扩展 ${d.id}`);
      // the response carries the subgraph next to the traversers: merge the subgraph part
      const { addedV, addedE } = merge(responseSubgraph(result));
      clearAlgo();
      render();
      setStatus(`扩展完成：新增点 ${addedV}、边 ${addedE}（共点 ${graph.vertices.size}、边 ${graph.edges.size}）`
        + (bounded ? `（大点，仅读取前 ${HUB_EDGE_LIMIT} 条）` : ''));
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

  /**
   * Runs a query the UI generated; when the seed is a hub the storage asks for a time window and
   * this retries it bounded (see {@link runExpansionQuery}) instead of showing an error.
   */
  async function queryCurrentBounded(query) {
    try {
      const { result, bounded } = await runExpansionQuery(query, '查询');
      if (!bounded) {
        showQueryResult(result, query);
        return;
      }
      showQueryResult(result, query);
      setStatus(`查询完成（大点，仅读取前 ${HUB_EDGE_LIMIT} 条）`);
    } catch (err) {
      queryFailed(err);
    }
  }

  async function queryCurrent(query) {
    setStatus('查询中…');
    try {
      showQueryResult(await runQuery(query), query);
    } catch (err) {
      queryFailed(err);
    }
  }

  /**
   * A query that failed has no answer, so the canvas must not keep showing the previous one: that is
   * how a filter that errored looked like it had matched the vertices of the query before it. The
   * canvas is emptied (the error modal carries the details).
   */
  function queryFailed(err) {
    setStatus('查询失败: ' + err.message);
    if (graph.vertices.size || graph.edges.size) {
      resetGraph();
      render();
      toast(t('canvasCleared'));
    }
    showQueryError(err);
  }

  /** Puts one answer on the canvas: the subgraph, the rows tab and the value window. */
  function showQueryResult(result, query) {
    // the answer is a picture: it belongs on the canvas, so the map (if it was up) steps aside
    if (mapOpen) { closeMap(); }
    // Cypher answers with columns/rows plus the subgraph; Gremlin with the subgraph,
    // optionally next to the traversers it produced (includeResult)
    const subgraph = responseSubgraph(result);
    resetGraph();
    const { addedV, addedE } = merge(subgraph);
    const value = responseValue(result);
    // remembered so a query that read coordinates can drive the map (see geoAnswerPoints)
    lastAnswerRows = Array.isArray(value) ? value : null;
    // A query whose answer is not a picture - a count, a groupCount, a list of ids, an elementMap - has
    // its answer in the result tab, and the canvas only shows what the traversal walked on the way. The
    // sidebar goes to the result tab itself then, instead of leaving the user on a stats tab that says
    // nothing about the number they asked for. A traversal that answers with vertices or edges stays on
    // the canvas: there the picture <em>is</em> the answer.
    let rowsOnly = false;
    if (result && result.columns) {
      rowsReady = true;
      refreshRowsTab();
      showRows(result.columns, result.rows || []);
    } else if (Array.isArray(value)) {
      showGremlinRows(value);
      rowsOnly = value.length > 0 && !elementValues(value);
    } else {
      clearRows();
    }
    showValue(value, result);
    clearAlgo();
    render();
    if (rowsOnly) {
      activateTab('rows');
    } else if (emptyCanvas() && !(result && result.rows && result.rows.length)) {
      // nothing was drawn and no rows came back: the query found nothing, and the toast says so
      toast(t('emptyResult'));
    }
    const rows = result && result.rows ? `、行 ${result.rows.length}` : '';
    const answer = Array.isArray(value) ? `结果 ${value.length} 项、` : '';
    setStatus(`查询完成：${answer}子图 点 ${graph.vertices.size}、边 ${graph.edges.size}${rows}（新增点 ${addedV}、边 ${addedE}）`);
  }

  /** Whether the canvas holds nothing at all (the answer is not a picture). */
  function emptyCanvas() {
    return !graph.vertices.size && !graph.edges.size;
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
  /**
   * Whether one value is a graph element (a vertex or an edge traverser).
   */
  function isElementValue(value) {
    return !!value && typeof value === 'object' && value.id !== undefined
      && (value.label !== undefined || value.type !== undefined);
  }

  /**
   * Whether an answer is made of graph elements (vertices or edges). A subgraph is already on
   * the canvas, so the floating window stays out of the way for those: it is there for the
   * answers a graph cannot show - a count, a list of values, a projection.
   */
  function elementValues(list) {
    if (!list.length) return false;
    return list.every(isElementValue);
  }

  function showValue(value, result) {
    if (!el.valuePanel) return;
    if (value === undefined) { hideValue(); return; }
    // vertices and edges are what the canvas is for: the window only counts them (the result tab
    // lists them), which is the same rule every other answer follows
    if (value && value.columns && value.rows.length && elementValues(
      value.columns.map((column) => value.rows[value.rows.length - 1][column]))) {
      hideValue(); // a Cypher projection of vertices: the canvas shows them
      return;
    }
    const summary = summarizeValue(value, result);
    el.valueTitle.textContent = t('valueTitle');
    el.valueBody.innerHTML = summary.body;
    el.valueDetail.innerHTML = summary.detail || '';
    el.valuePanel.hidden = false;
    el.valueCopy.textContent = '⧉';
    el.valueCopy.title = t('valueCopy');
  }
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
  /**
   * The window's body for a list of traversers. One value is shown itself (a {@code count()}); more
   * than one gets the **counts only** - "1620 项 · 111 个不同值" - because the result tab lists the
   * values themselves, and repeating a preview here only competes with it.
   */
  function summarizeValues(values, unit) {
    if (!values.length) return { body: `0 ${t(unit)}`, detail: '' };
    const labels = values.map(valueLabel);
    const distinct = [...new Set(labels)];
    // one scalar (a count(), a single value) is shown itself; one *element* is not, because a bare
    // vertex id says nothing about the answer being one row - the result tab lists it either way
    if (labels.length === 1 && !isElementValue(values[0])) {
      return { body: `<span class="value-scalar">${labels[0]}</span>`, detail: '' };
    }
    const body = `<span class="value-scalar value-small">${values.length} ${t(unit)}`
      + ` · ${distinct.length} ${t('valueDistinct')}</span>`;
    return { body, detail: '' };
  }

  /** The window's content for one query answer (Gremlin traversers or Cypher rows). */
  function summarizeValue(value, result) {
    if (value && value.columns) {
      const rows = value.rows;
      const columns = value.columns;
      if (!rows.length) return { body: `0 ${t('valueRows')}`, detail: '' };
      const detail = `${rows.length} ${t('valueRows')} · ${escapeHtml(columns.join(', '))}`;
      if (columns.length === 1) {
        // one column: the answer is that column's values, so the window counts them
        return { body: summarizeValues(rows.map((row) => row[columns[0]]), 'valueItems').body, detail };
      }
      // several columns: the rows are the answer and the result tab lists them
      return { body: `<span class="value-scalar value-small">${rows.length} ${t('valueRows')}</span>`, detail };
    }
    return summarizeValues(Array.isArray(value) ? value : [value], 'valueItems');
  }

  function hideValue() {
    if (el.valuePanel) el.valuePanel.hidden = true;
  }

  /**
   * Shows a Gremlin answer in the result tab: one row per returned value, the way a Cypher
   * projection is shown. The answer to a traversal of vertices/edges is the subgraph on the canvas,
   * so that one clears the tab instead (see showQueryResult).
   */
  /**
   * The properties of one answer element. A traverser may carry none - a hop reads only the ids when
   * nothing after it needs the properties - while the subgraph that was drawn for the same query read
   * them in full. The table shows what the picture has, so the column is not empty for a query whose
   * own answer was ids.
   */
  function propertiesOfElement(value) {
    if (value.properties && Object.keys(value.properties).length) return value.properties;
    const key = `${value.label || value.type}::${value.id}`;
    const drawn = graph.vertices.get(key) || graph.edges.get(key);
    return (drawn && drawn.properties) || value.properties || {};
  }

  /**
   * Renders a Gremlin answer in the result tab. A list of graph elements gets one row per element
   * (its type, its id and its properties), so the answer of an {@code outE(...).toList()} is
   * readable next to the picture; anything else gets a single value column. A {@code count()} shows
   * up as one row, which is what makes the number comparable with the totals in the stats tab.
   */
  function showGremlinRows(values) {
    rowsReady = true;
    refreshRowsTab();
    if (elementValues(values)) {
      const label = t('rowsType');
      const id = t('rowsId');
      const properties = t('rowsProperties');
      showRows([label, id, properties], values.map((value) => ({
        [label]: value.label || value.type || '',
        [id]: value.id,
        [properties]: propertiesOfElement(value),
      })));
      // an element that two traversers produced - an edge whose two endpoints are both in the answer
      // - is listed once per traverser: `count()` counts them, the rows show them, and `dedup()`
      // collapses them. Saying so is the difference between "a duplicate bug" and a stated rule.
      const distinct = new Set(values.map((value) => `${value.label || value.type}::${value.id}`)).size;
      if (distinct < values.length) {
        el.rowsHint.textContent = t('rowsDuplicates')
          .replace('{n}', String(values.length)).replace('{d}', String(values.length - distinct));
      }
      return;
    }
    const column = t('rowsValue');
    showRows([column], values.map((value) => ({ [column]: value })));
  }

  /** How many rows the result table renders before it stops and says how many were left. */
  const ROWS_RENDER_MAX = 2000;

  /** Renders the rows of a Cypher result - or of a Gremlin answer - as a table in the "rows" tab. */
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
    // a capped answer can be hundreds of thousands of rows: drawing them all would freeze the tab,
    // so draw a readable prefix and say how many there are in total
    const shown = rows.length > ROWS_RENDER_MAX ? rows.slice(0, ROWS_RENDER_MAX) : rows;
    for (const row of shown) {
      const tr = document.createElement('tr');
      for (const column of columns) {
        const td = document.createElement('td');
        td.innerHTML = formatValue(row[column]);
        tr.appendChild(td);
      }
      el.rowsTable.appendChild(tr);
    }
    el.rowsHint.style.display = '';
    el.rowsHint.textContent = rows.length > shown.length
      ? t('rowsTruncated').replace('{shown}', String(shown.length)).replace('{total}', String(rows.length))
      : t('rowsHint');
    el.rowsTableWrap.hidden = false;
    // The query's tab choice belongs to the user, so an answer that also has a picture only gets a
    // marker that there is something to read. A rows-only answer has no picture to look at, and the
    // caller switches to this tab itself (see showQueryResult).
    if (!document.querySelector('.tab[data-tab="rows"]').classList.contains('active')) {
      markRowsUnread(true);
    }
  }

  /** Marks the result tab as holding an unread answer (a dot next to its label). */
  function markRowsUnread(unread) {
    const tab = document.querySelector('.tab[data-tab="rows"]');
    if (tab) tab.classList.toggle('unread', unread);
  }

  /** Empties the rows table (a new query, or a Gremlin one). */
  function clearRows() {
    rowsReady = false;
    refreshRowsTab();
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
    // one hop, so the box shows exactly the edges a both() query would: the same payment edges, the
    // same single-direction IP/Phone links - not a second hop's worth of neighbours on top
    queryCurrentBounded(`g.V(${quoted},'${vertexType}').both()`);
  }

  function onGremlinQuery() {
    const q = el.gremlinInput.value.trim();
    if (!q) { setStatus(t('enterGremlin')); return; }
    queryCurrent(q);
  }

  /** Fills the query box with an example query of the selected language and graph. */
  /**
   * Fills the example picker for the selected graph. An example is
   * {@code [titleZh, titleEn, query]} - the title follows the interface language, the query is the
   * same in both (and is what the picker hands to the query box).
   *
   * <p>The picker is not shown until its button asks for it: it is a helper, not a thing to trip
   * over, so a query change (a different graph, a different language) only refills it.
   */
  function refreshExamples() {
    if (!el.exampleSelect) return;
    const language = currentLanguage();
    const byGraph = (EXAMPLES[language] || {})[currentGraph ? currentGraph.name : ''] || [];
    const keep = el.exampleSelect.value;
    el.exampleSelect.innerHTML = '';
    const placeholder = document.createElement('option');
    placeholder.value = '';
    placeholder.textContent = t('examples');
    el.exampleSelect.appendChild(placeholder);
    for (const entry of byGraph) {
      const [titleZh, titleEn, query] = entry.length === 3 ? entry : [entry[0], entry[0], entry[1]];
      const option = document.createElement('option');
      option.value = query;
      option.textContent = lang === 'en' ? titleEn : titleZh;
      el.exampleSelect.appendChild(option);
    }
    if (byGraph.some((e) => e[e.length - 1] === keep)) el.exampleSelect.value = keep;
    if (byGraph.length === 0) {
      el.exampleSelect.hidden = true;
      el.examplesBtn.hidden = true; // no examples for this graph: no button to open them either
    } else {
      el.examplesBtn.hidden = false; // only the button shows; toggleExamples() opens the picker
    }
  }

  /**
   * The fuzzy linking example: asks the graph for a pattern that carries an example context and runs
   * the query it describes - which for the payment demo walks from the fragments of a context to the
   * ring, its outgoing payments and the account they reach.
   *
   * The button only shows up for a graph that has such a pattern, so it is never a button that
   * returns nothing.
   */
  async function refreshFuzzyButton() {
    if (!el.fuzzyBtn || !currentGraph) return;
    el.fuzzyBtn.hidden = true;
    if (!SHOW_FUZZY_EXAMPLE) {
      return; // the button is hidden for now: flip this to put it back
    }
    try {
      const res = await fetch('/api/fuzzy/demo?graph=' + encodeURIComponent(currentGraph.name));
      if (!res.ok) return;
      const demo = await res.json();
      el.fuzzyBtn.hidden = !demo.query;
      el.fuzzyBtn.dataset.query = demo.query || '';
      el.fuzzyBtn.dataset.title = demo.title || '';
      el.fuzzyBtn.title = t('fuzzyExample') + (demo.title ? ' · ' + demo.title : '');
    } catch (err) {
      el.fuzzyBtn.hidden = true;
    }
  }

  /** Loads the graph's fuzzy example into the query box and runs it. */
  async function runFuzzyExample() {
    const query = el.fuzzyBtn?.dataset.query;
    if (!query) return;
    if (currentLanguage() === 'cypher') {
      el.languageSelect.value = 'gremlin';
      onLanguageChange();
    }
    el.gremlinInput.value = query;
    autoGrowGremlin();
    await queryCurrent(query);
  }

  /** Switching the language also swaps the placeholder and the example list. */
  function onLanguageChange() {
    const cypher = currentLanguage() === 'cypher';
    el.gremlinInput.placeholder = cypher
      ? "MATCH (a:Account {id:'135'})-[p:Payment]->(b:Account) RETURN b.id, p.status LIMIT 10"
      : "";
    el.gremlinInput.classList.toggle('cypher', cypher);
    if (!cypher) applyScriptMode(); // the groovy subset is for Gremlin only
    clearRows(); // also hides the result tab: it belongs to Cypher answers
    refreshRowsTab();
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
    refreshRowsTab(); // the result tab is Cypher only: hidden until a Cypher query runs
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
    // the Groovy script toggle is an administrator affordance, open while the feature settles
    if (el.scriptToggleWrap) {
      el.scriptToggleWrap.hidden = !SHOW_SCRIPT_MODE;
    }
    if (el.scriptToggle) {
      el.scriptToggle.checked = localStorage.getItem('pg.script') === '1';
      el.scriptToggle.addEventListener('change', () => {
        localStorage.setItem('pg.script', el.scriptToggle.checked ? '1' : '0');
        applyScriptMode();
        el.gremlinInput.focus();
      });
    }
    el.languageSelect.addEventListener('change', onLanguageChange);
    el.examplesBtn.addEventListener('click', toggleExamples);
    el.ingestBtn.addEventListener('click', openIngest);
    el.ingestSubmit.addEventListener('click', submitIngest);
    el.fuzzyBtn.addEventListener('click', runFuzzyExample);
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
        renderMapNote(); // the map's note is built from descriptors, so it follows the language too
      });
    });
    if (el.treeSelect) {
      el.treeSelect.addEventListener('change', () => applyTreeMode(el.treeSelect.value));
    }
    el.mapBtn.addEventListener('click', toggleMap);
    if (el.mapClose) el.mapClose.addEventListener('click', closeMap);
    if (el.mapNote) el.mapNote.addEventListener('click', onMapNoteClick);
    if (el.view3dBtn) el.view3dBtn.addEventListener('click', toggleView3D);
    if (el.view3dClose) el.view3dClose.addEventListener('click', closeView3D);
    document.querySelectorAll('.tab').forEach((t) => t.addEventListener('click', onTabChange));
    // one abbreviated value opens where it is drawn, in either list
    if (el.rowsTable) el.rowsTable.addEventListener('click', onValueClipClick);
    if (el.propsContent) el.propsContent.addEventListener('click', onValueClipClick);
    const metricsRefresh = document.getElementById('metricsRefresh');
    if (metricsRefresh) metricsRefresh.addEventListener('click', () => loadMetrics());
    document.querySelectorAll('[data-close]').forEach((node) => {
      node.addEventListener('click', () => closeModal(node.dataset.close));
    });
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape' && el.errorModal && !el.errorModal.hidden) {
        closeModal('errorModal');
      } else if (event.key === 'Escape' && el.ingestModal && !el.ingestModal.hidden) {
        closeModal('ingestModal');
      } else if (event.key === 'Escape' && mapOpen) {
        closeMap(); // the map is a view of its own: Escape is the way back from it as well
      } else if (event.key === 'Escape' && view3dOpen) {
        closeView3D(); // and so is 3D - checked after the map, which cannot be open at the same time
      }
    });
    if (el.errorCopy) {
      el.errorCopy.addEventListener('click', () => copyText(errorReport()));
    }
    window.addEventListener('resize', render);
    window.addEventListener('resize', () => { if (view3dOpen) { ensureView3D().resize(); } });

    applyI18n();
    render();
  }

  function onGraphChange(g) {
    currentGraph = g;
    refreshFuzzyButton().catch(() => {});
    // the map icon follows the instance: only a graph with coordinates behind a scanning store has one
    refreshMapButton().catch(() => {});
    // the metrics panel follows the graph selection
    if (!document.getElementById('metricsPanel').hidden) {
      loadMetrics();
    }
    displayFields = g.displayProperties || {};
    // another graph's map, another list of points: the last answer does not carry over
    lastAnswerRows = null;
    // and another graph's tree: the root belonged to the vertices of the previous drawing
    treeRootKey = null;
    if (el.treeSelect) { el.treeSelect.value = ''; }
    treeMode = '';
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
    if (gremlin) { autoGrowGremlin(); applyScriptMode(); el.gremlinInput.focus(); }
  }

  /** Whether the query box is in Groovy script mode (the toggle, when the feature is shown). */
  function scriptModeOn() {
    return !!(SHOW_SCRIPT_MODE && el.scriptToggle && el.scriptToggle.checked);
  }

  /** The placeholder follows the mode: the script example when it is on, a traversal when it is off. */
  function applyScriptMode() {
    if (!el.gremlinInput || currentLanguage() === 'cypher') return;
    el.gremlinInput.placeholder = scriptModeOn()
      ? t('gremlinScriptPlaceholder')
      : "g.V(888,'Account').batch(40).both('Payment').both('Payment')";
  }

  // grow the Gremlin textarea with its content (Enter / new lines) up to the CSS max-height
  function autoGrowGremlin() {
    const ta = el.gremlinInput;
    if (!ta) return;
    ta.style.height = 'auto';
    ta.style.height = `${Math.min(ta.scrollHeight + 2, 280)}px`;
  }


  // ---------------------------------------------------------------- system metrics

  /** The metrics endpoint of the backend this page talks to. */
  const METRICS_PATH = '/api/metrics';

  let metricsLoading = false;

  /** Reads the metrics of the selected graph and paints the panel. */
  async function loadMetrics() {
    const graph = el.graphSelect && el.graphSelect.value;
    const body = document.getElementById('metricsBody');
    const name = document.getElementById('metricsGraph');
    const updated = document.getElementById('metricsUpdated');
    if (!body) return;
    if (!graph) {
      body.innerHTML = `<p class="muted">${t('metricsEmpty')}</p>`;
      return;
    }
    if (name) name.textContent = graph;
    if (metricsLoading) return;
    metricsLoading = true;
    try {
      const res = await fetch(`${METRICS_PATH}/${encodeURIComponent(graph)}`);
      if (res.status === 404) {
        body.innerHTML = `<p class="muted">${t('metricsEmpty')}</p>`;
        if (updated) updated.textContent = '';
        return;
      }
      const data = await res.json();
      renderMetrics(data);
      if (updated) updated.textContent = data.updatedAt ? new Date(data.updatedAt).toLocaleTimeString() : '';
    } catch (err) {
      body.innerHTML = `<p class="muted">${t('metricsEmpty')}</p>`;
    } finally {
      metricsLoading = false;
    }
  }

  /** A number with thousands separators, or a dash. */
  function metricNumber(value) {
    if (value === null || value === undefined) return '—';
    return Number(value).toLocaleString();
  }

  /** One metric row: label, today's number and the total. */
  function metricRow(label, entry) {
    if (!entry) return '';
    const items = entry.itemsTotal === undefined ? ''
      : `<span class="metric-sub">${metricNumber(entry.itemsToday)} / ${metricNumber(entry.itemsTotal)} ${t('metricsItems')}</span>`;
    const failed = entry.failedTotal ? `<span class="metric-bad">${metricNumber(entry.failedTotal)} ${t('metricsFailed')}</span>` : '';
    return `<div class="metric-row">
      <span class="metric-label">${label}</span>
      <span class="metric-values">
        <span class="metric-today">${metricNumber(entry.today)}</span>
        <span class="metric-dash">/</span>
        <span class="metric-total">${metricNumber(entry.total)}</span>
      </span>
      ${items}${failed}
    </div>`;
  }

  /** A card with a title and a few rows. */
  function metricCard(title, rows) {
    return `<div class="metric-card"><h4>${title}</h4>${rows}</div>`;
  }

  /** Paints the whole panel from the endpoint's answer. */
  function renderMetrics(data) {
    const body = document.getElementById('metricsBody');
    if (!body) return;
    const storage = data.storage || {};
    const quality = data.quality || {};
    const queries = data.queries || {};
    const counters = data.counters || {};
    const isEmpty = Object.keys(counters).length === 0;

    const storageCard = metricCard(t('metricsStorage'),
      metricRow(t('metricsVertexRead'), storage.vertexRead)
      + metricRow(t('metricsVertexWrite'), storage.vertexWrite)
      + metricRow(t('metricsEdgeRead'), storage.edgeRead)
      + metricRow(t('metricsEdgeWrite'), storage.edgeWrite)
      + metricRow(t('metricsKeyValue'), storage.keyValue));

    const qualityCard = metricCard(t('metricsQuality'),
      metricRow(t('metricsEvicted'), quality.evicted)
      + metricRow(t('metricsStaleVertex'), quality.staleVertexWrites)
      + metricRow(t('metricsStaleEdge'), quality.staleEdgeWrites)
      + metricRow(t('metricsRetries'), quality.retries));

    // query counts, one column per language and source
    const byLanguage = queries.byLanguageAndSource || {};
    const queryRows = Object.entries(byLanguage).map(([language, sources]) => {
      const parts = Object.entries(sources).map(([source, count]) =>
        `<span class="metric-chip">${source} <b>${metricNumber(count)}</b></span>`).join('');
      return `<div class="metric-row">
        <span class="metric-label">${t('metricsLanguage')} ${language}</span>
        <span class="metric-values">${parts}</span>
      </div>`;
    }).join('');
    const latency = queries.latency;
    const latencyRow = latency ? `<div class="metric-row metric-latency">
        <span class="metric-label">${t('metricsLatency')} (${metricNumber(latency.count)} ${t('metricsCount')})</span>
        <span class="metric-values latency-values">
          <span><i>avg</i>${latency.meanMs} ms</span>
          <span><i>p50</i>${latency.p50Ms} ms</span>
          <span><i>p95</i>${latency.p95Ms} ms</span>
          <span><i>p99</i>${latency.p99Ms} ms</span>
          <span><i>max</i>${latency.maxMs} ms</span>
        </span>
      </div>` : '';
    const translated = queries.translated || {};
    const translatedRow = `<div class="metric-row">
        <span class="metric-label">${t('metricsTranslated')}</span>
        <span class="metric-values latency-values">
          <span><i>${t('metricsVertexRead')}</i>${metricNumber(translated.vertexReads)}</span>
          <span><i>${t('metricsEdgeRead')}</i>${metricNumber(translated.edgeReads)}</span>
          <span><i>${t('metricsKeyValue')}</i>${metricNumber(translated.keyValueReads)}</span>
        </span>
      </div>`;
    const queryCard = metricCard(t('metricsQueries'), queryRows + latencyRow + translatedRow);

    body.innerHTML = isEmpty
      ? `<p class="muted">${t('metricsEmpty')}</p>`
      : storageCard + qualityCard + queryCard;
  }

  function onTabChange(e) {
    activateTab(e.target.dataset.tab);
  }

  /** Whether the result tab is available: only Cypher answers have rows to show. */
  function rowsAvailable() {
    return rowsReady;
  }

  /** Shows the side panel for the given tab ('stats' / 'props' / 'rows'). */
  function activateTab(tab) {
    const wanted = tab === 'rows' && !rowsAvailable() ? 'stats' : tab;
    if (wanted === 'rows') markRowsUnread(false);
    document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.dataset.tab === wanted));
    document.getElementById('statsPanel').hidden = wanted !== 'stats';
    document.getElementById('propsPanel').hidden = wanted !== 'props';
    document.getElementById('rowsPanel').hidden = wanted !== 'rows';
    document.getElementById('metricsPanel').hidden = wanted !== 'metrics';
    if (wanted === 'metrics') {
      loadMetrics();
    }
  }

  /** Shows or hides the result tab (it only exists for Cypher results). */
  function refreshRowsTab() {
    const tab = document.querySelector('.tab[data-tab="rows"]');
    if (tab) tab.hidden = !rowsAvailable();
    if (!rowsAvailable() && !document.getElementById('rowsPanel').hidden) {
      activateTab('stats');
    }
  }

  init().catch((err) => { setStatus('初始化失败: ' + err.message); });
})();
