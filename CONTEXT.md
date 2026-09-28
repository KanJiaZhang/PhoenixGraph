> **继续开发请先看 [HANDOVER.md](HANDOVER.md)**（当前状态 / 启动步骤 / 明天的任务 / 已知问题）。

# PhoenixGraph 开发上下文（断点续做手记）

> 本文件用于保存当前工作上下文，方便下次回到本目录后立即继续开发。
> 分支：`develop`；远程：`github.com:KanJiaZhang/PhoenixGraph.git`。
> 基线提交：`97c3519` → `3261122`（develop，已 push）；本文件随当前改动一并提交。

---

## 1. 当前状态（本轮收尾时）

- **构建**：`./mvnw -q -DskipTests compile` 通过；`QueryApiIntegrationTest` 2 个用例全绿（2/0/0）。
- **本地环境**：Aerospike 单机 `localhost:3000`，namespace `test`；集成测试读 `AEROSPIKE_HOST/PORT/NAMESPACE`（默认 localhost:3000/test），无 Aerospike 时自动 `skip`。
- **运行中的服务**（本机曾手动启动，提交代码后如需复现按第 8 节命令重启）：
  - `graph-http`：`java -jar graph-http/target/graph-http-*.jar --phoenixgraph.storage.vertex-set=http_rich_v2`（端口 8081）
  - `graph-web`：`java -jar graph-web/target/graph-web-*.jar`（端口 8080，代理 `/api/query`、`/api/graphs` 到 graph-http）
- **当前默认数据**：set `http_demo_v1`（`application.yml` 的 `phoenixgraph.storage.vertex-set` 默认值；`QueryApiIntegrationTest#demoStarGraphViaApi` 播种，先 `truncate`）：  - **小图 hub 135** → 50 个一跳 Account（1000..1049），这 50 个互相交易（环 `i→i+1` + `i→i+7` + `i→i+13`，共 150 条 Payment），每账户 2 IP + 2 Phone，并含反向 `IPToAccount`/`PhoneToAccount`；`g.V('135','Account').both().both()` 子图 = **251 点 / 500 边**。
  - **大图 hub 150**（5000 点压测）→ 100 个一跳 Account（2000..2099），每账户 39 IP + 10 Phone（`AccountToPhone` 出度上限 10）；`g.V('150','Account').both().both()` = **5000** 跳 / 子图 **5001 点 / 5000 边**（JSON ~1.5MB，后端 ~0.5s）。
- **旧数据**：set `http_rich_v2`（由 `layeredBothHopQueryViaApi` 播种）：888 分层图，两跳 `both()` 子图 421 点 / 430 边。
- **知识图谱数据**：set `kg_nell995_v2` + schema `kg-entity-graph`（NELL-995，113,553 点 / 149,678 边，按 relation 分片，见第 12/13 节）。**默认 `application.yml` 同时加载 payment-graph 与 kg-entity-graph**，UI 下拉框可切换。
- **中文知识图谱数据**：set `kg_sanguo_v1` + schema `sanguo-kg`（《三国演义》OpenKG 文档数据集：**4,487 篇**人物文档 + OWL 本体 + 本地补充三元组 `triples-extra.csv` → RDF → 6,202 实体 / 21,162 边，同样按 relation 分片，但边上带中文关系名/关系家族）。**见 [SANGUO-KG.md](SANGUO-KG.md)**，默认 `application.yml` 也已挂上。
- **Account 节点含 List/Map 属性**（见第 9 节踩坑）：`accountUsage`(id=4, list<string>) 与 `accountLimited`(id=5, map<string>)。

---

## 2. 项目结构（Maven 多模块，Java 21）

| 模块 | 职责 |
|---|---|
| `graph-core` | 核心抽象：`Graph`/`Vertex`/`Edge`/`Property`/`MetaProperty` |
| `graph-schema` | `GraphSchema`/`VertexSchema`/`EdgeSchema`/`EdgeEndpointSchema`、`SchemaValidator`（9 项校验）、JSON 编解码（`SchemaJsonCodec` 支持 `{"list":"STRING"}`/`{"map":"STRING"}`） |
| `graph-storage` | 存储抽象 + 内存实现 + `BusinessLog`/`ErrorLog`/`RetryingWriter` |
| `graph-storage-aerospike` | `AerospikeVertexStore`/`AerospikeEdgeStore`/`AerospikeAdjacentEdgeStore`/`AerospikeEdgeTableStore` |
| `graph-query` | 存储无关查询抽象（`query.storage` 包：`GraphQuery` + `VertexQueryResult`/`EdgeQueryResult`/`EdgeSide`） |
| `graph-query-aerospike` | `AerospikeGraphQuery implements GraphQuery` |
| `graph-query-gremlin` | Gremlin 子集遍历引擎（见第 4 节） |
| `graph-compute-engine` | BFS / PageRank（内存算法，独立于查询引擎） |
| `graph-http` | Spring Boot REST：写入（batch/vertex/edge）+ 查询 `POST /api/v1/query` + `GET /api/v1/graphs`；schema 部署 `payment-graph.json` |
| `graph-web` | 可视化前端（静态资源 + `ProxyController` 反向代理）；`frontend/` 为 Node 开发模式，`src/main/resources/static/` 为生产源 |
| `graph-batch-load` / `graph-mapreduce` | CSV 批量导入 |
| `graph-integration-test` | 真实 Aerospike 集成测试 + benchmark |

---

## 3. 核心数据模型（务必记住）

- **点**：record key = vertexId；属性 bin = `vp_<index>`（值）+ `vpt_<index>`（属性 lastModifiedTime）；meta bin `vp_-2`（createdTime）/ `vp_-1`（lastUpdatedTime）。
- **边**：反范式化存在点 record 上，bin = `ve_<edgeSchemaId>_<dir>`（dir: 0=both, 1=in, 2=out）。
  - 边值（CDT list）= `[lastUpdatedTime, createdTime, otherVertexId, {propIndex -> [value, propCreatedTime, propLastUpdatedTime]}]`。
  - **otherVertexId 是遍历关键**（反范式化只存了对端点，由此能 2 跳）。
- **写入 CAS**：点/边所有 properties 按 `lastUpdatedTime`（点用 `lastModifiedTime`）做 per-property CAS——新则写、旧则忽略并记 business log。
- **List/Map 属性**：直接 `Value.get(List)`/`Value.get(Map)` 写进 CDT bin，读回即 `List`/`Map`，往返正常（已由 `QueryApiIntegrationTest` 断言锁住）。
- **显示字段（display property）**：`PropertySchema` 带一个 `display` 布尔标记（默认 false），JSON 里写 `"display": true`（省略即 false；`PropertyDto` 用 `@JsonInclude(NON_NULL)`，所以 `min/max/minLength/...` 为 null 时也不再输出，schema 文件更干净）。
  - `VertexSchema.displayProperties()/displayPropertyNames()` 按**声明顺序**返回被标记的属性；id/meta 属性永不在其中。
  - `/api/v1/graphs` 的 `GraphInfo` 新增 `displayProperties`（顶点类型名 → 显示属性名列表），UI 用它给点打标签。
  - `SchemaValidator` 只发 **warning**（不报错）：某点 schema 没声明显示字段 / 显示字段不是 scalar 类型。
  - 当前 payment-graph：Account = `firstName`+`lastName`，Phone = `cnCode`+`phoneNumber`，IP = `ipAddress`（IP 是顺带加上的，否则 IP 点只能显示裸 id）。
  - **Phone 属性 id 已调整**：`cnCode`=1、`phoneNumber`=2（这样显示顺序是「+86 138-6000」而不是反的）。

---

## 4. Gremlin 引擎（`graph-query-gremlin`，重点模块）

**关键类**：

- `GremlinQuery`：解析器（`g.V(ids..., name)` 多起点、`{ $logic }` 尾块、多行、全角逗号、`parseSteps`）。
- `GremlinGraphQuery`：执行器 + **编译缓存**（`CompiledQuery`：解析 + 逻辑展开 + 步骤解析 + 属性投影计划缓存，按查询字符串 key；`registerStep`/`registerLogic` 会失效缓存）。**全局重写** `X(label).count()` → `XE(label).count()`（只数边不读点）。
- `GremlinContext`：执行上下文（`batchSize`、`readProperties`（null=不读/[]=全量/非空=指定 bin）、`vertexCache`、`edgeCache`、`readVerticesCached`/`readAllEdgesBatchCached` 去重+缓存、**自适应 batch size**（`adaptBatchSize(fanOut, filtered)`，`clamp(fanOut/5, current, max=100)`，显式 `batch/barrier` 或 `has` 后不调整）、**子图采集**（`recordVertex`/`recordEdge`/`buildSubgraph`）。
- `GremlinSteps`：内置步骤（`out/in/both`、`outE/inE/bothE`、`has`、`values`、`elementMap`、`count/limit/dedup`、`batch/barrier`）；**`out/in/both/outE/inE/bothE` 支持 0 参数 = 所有边类**（`readAllEdgeClasses` + `edge.edgeName()` 逐边解析邻居 schema）；**`otherV()`** = 从边跳到它的另一端点（`bothE(R).filter(...).otherV()`，边 traverser 只带 `otherVertexId`，所以只能去“另一端”）。
- **并行批量读**（本轮新增）：`GremlinContext` 把每次多 id 读拆成**每批 ≤ `batch(n)`** 的批次并行发出（`readBatches`，ForkJoinPool.commonPool，默认 `DEFAULT_PARALLELISM = min(8, CPU)`，`parallelism(1)` 可关；单批走内联不走线程池）。`batchSize` 默认 **20**。
  - **seed 并行**：`g.V(id1,id2,id3)` 另用 `seedBatches`，把 id 均分到最多 `parallelism` 批（每批仍 ≤ `batch(n)`），所以 3 个 seed → 3 个并行请求；100 个 seed + `batch(20)` → ≤ 8 个请求。入口是 `GremlinContext.readSeedVertices`（由 `GremlinGraphQuery.start` 调用）。
- **子图剪枝**：`filter`/`has` 丢弃的元素会调 `ctx.dropElement` 从采集结果里移除（`bothE().filter(...)` 才不会把过滤掉但被读过的边也画出来）；边的另一端点存在 `edgeEndpointVertices`，只在“仍有存活的边引用它”时才进子图，避免悬挂点。
- `GremlinStepRegistry`：步骤注册表（可扩展）。
- `NeighborVertexResolver`：由 `EdgeSchema` 的 source/target vertex 名推导邻居 schema（`from(GraphSchema)`）。
- `Subgraph`：子图结果（`Vertex(label,id,properties,createdTime,lastUpdatedTime)`、`Edge(label,id,source,target,properties,createdTime,lastUpdatedTime)`）。
- `GremlinAlgorithms`：内存图算法 `pagerank/louvain/cc`（**只支持同构点**，异构点暂不支持）。

**已支持语法**：

```text
g.V(123, 888, 'Account').batch(40).out('Payment').in('PhoneToAccount')
g.V(888, 'Account').batch(30).out('Payment').inE('PhoneToAccount') { $logic }
g.V('v1','v2','person').out('knows').has('age', 30).values('name').elementMap().count()
g.V(888,'Account').batch(40).both().both()          # 无参 both = 所有边类型
g.V(888,'Account').batch(40).both('Payment').both('Payment').count()
g.V('6_xxx','Entity6').bothE().filter(relation.equals('concept:atdate') || xrelation.equals('concept:atdate'))
```

**`filter(表达式)` 步骤**（`GremlinFilterExpression`，支持 OR/AND 与属性比较）：

```text
expression := conjunction (('||' | 'or') conjunction)*        # OR
conjunction := predicate (('&&' | 'and') predicate)*         # AND
predicate  := key | key op value | key.method('value')
op         := '=' | '==' | '!='
method     := equals/eq | ne | contains | startsWith | endsWith
```

- 值可用 `'...'` / `"..."` 引号（引号里的 `||`/`&&`/括号不会被拆），也可裸写；比较走 `GremlinSteps.matches`（数字感知，否则按字符串）。
- `filter` 引用的属性**自动进入读取投影计划**（`neededProperties`），所以 `bothE().filter(...)` 只读 `relation`/`xrelation` 两个 bin。
- `GremlinStepRegistry` 允许把多个参数当作 OR 备选：`filter(a='x', b='y')` == `filter(a='x' || b='y')`。

---

## 5. graph-http 查询 API

- `POST /api/v1/query`：body `QueryRequest{graphName, query, params, returnSubgraph}`；`${param}` 替换；`returnSubgraph=true` 返回 `Subgraph`（点/边含 created/lastUpdated 时间戳），否则返回 traverser 列表。
- `GET /api/v1/graphs`：`SchemaController` 返回 `GraphInfo{name, vertexTypes, edgeTypes}`。
- HTTP 压缩默认开启（`server.compression.enabled`）。
- schema 部署：`graph-http/src/main/resources/payment-graph.json`（Account/IP/Phone + Payment/PhoneToAccount/AccountToPhone(3)/AccountToIP(4)）；`StorageConfig` CWD→classpath 回退并注册。

---

## 6. 前端（graph-web，Node.js + d3.js）

- **静态资源**：`graph-web/frontend/public/{index.html,css/style.css,js/app.js}`，并**拷贝**进 `graph-web/src/main/resources/static/`（生产）；`static/vendor/d3/d3.min.js` 由 `frontend/node_modules/d3/dist/d3.min.js` 拷贝（不走 CDN）。
- `ProxyController` 把 `/api/query`、`/api/graphs` 转发到 `phoenixgraph.http-url`（`WebConfig` 提供 `RestTemplate`），同源免 CORS。
- **交互已实现**：图实例下拉；ID 查询（顶点类型下拉 + ID 输入，**默认走两跳 `g.V(id,type).both().both()` 并展示两跳子图**）＋ Gremlin toggle 切 textarea（选中时 textarea 位于第二行、**满宽**、高 56px（为之前的 2 倍）、右边缘与「查询」按钮对齐）；单一「查询」按钮固定第一排右侧；d3 力导向（center + forceX/Y + resize 重排）；双击点 `both()` 扩一跳合并；单击点/边看属性（含 createdTime/lastUpdatedTime）；拖拽点只移动固定该点不重排（边跟着走）+ ↻ 手动平衡按钮；⛶ 框选过滤（只显示框内点及两端都在框内的边，⊞ 恢复全部）；Legend 固定左上（在工具栏下方 y=60）。
- **国际化（zh/en）**：`I18N` 字典 + `t(key)` + `applyI18n()`（遍历 `data-i18n` / `data-i18n-title` / `data-i18n-placeholder`）；顶栏右侧「中 / EN」分段切换，选择持久化到 `localStorage['pg.lang']`；图例/属性面板/统计等动态文案也走 `t()`（共 25 个 key，页面 17 个静态钩子全覆盖）。
- **图例点击过滤**：图例项可点击（顶点类型模式按 `label`；算法社区模式按 community id）；命中 → `legend-hit`（白描边+发光），其余 `legend-dim`（opacity 0.12），边需两端都命中才保留；再点一次取消；图例项 `mousedown` 阻止冒泡避免误触平移；社区图例文字按宽度截断（`truncate(text, 20)`，如「Community 0 · 6 vert…」）。
- **最短路径**：算法下拉新增 `shortestpath`；单击两个点（`picked`，紫色 ring、满 2 个后提示）后在下列表选「最短路径」→ BFS 求最短跳数，再 DFS 枚举 ≤ 最短+2 跳的简单路径（上限 400 条）；所有路径 `path-hit`（紫），最短路径 `path-shortest`（绿、更粗+发光，专用箭头 `#arrow-shortest`），其余 `path-dim`。
- **CSV 导出**：统计 tab 内低调按钮（`.export-btn`）→ 导出当前展示子图的 `phoenixgraph-vertices.csv` 与 `phoenixgraph-edges.csv`（id/label/时间戳/属性 JSON，UTF-8 BOM）。
- **箭头/边几何**：`setEdgeGeometry()` 把每条边两端各收缩 `EDGE_NODE_GAP=13`，箭头落在节点外侧不再被盖；箭头改为柔和 chevron marker（`#arrow` / `#arrow-hit` / `#arrow-shortest`，CSS `marker-end` 控制），边宽 1.44（=1.8×0.8，选中 2.88）。
- **Logo 方案目录**：`static/logos/logo-1..8.svg` + `logos/preview.html`（8 个「凤凰+图」方案，当前用 #1）；语言开关 `.lang-opt.active` 已弱化为白底+浅阴影。
- **Toast**：画布底部浮层 `#toast`，用于「请先点击两个点」「已导出」等瞬时反馈。
- **显示字段渲染**：点标签不再只显示 id，而是 `displayLabel(d)` = 该点 schema 的 display 属性值用**空格**拼接（如 Account → 「Linus Hopper」、Phone → 「+86 138-6000」、IP → 「10.0.5000.1」），超过 `DISPLAY_LABEL_MAX=22` 字符截断；没有显示字段/值缺失时退回显示 id。显示字段名来自 `/api/graphs` 的 `displayProperties`（`onGraphChange` 存入 `displayFields`）。属性面板新增「显示名」行；CSV 顶点表新增 `displayName` 列。
- **查询替换（非累加）**：`queryCurrent()` 先 `resetGraph()`（清 vertices/edges/positions/selected/viewFilter/legendFilter/pathState/picked）再 `merge()`，所以换 id / 换 gremlin 会重画而不残留；双击扩展 `expand()` 仍累加合并。
- **双击扩展**：不用原生 `dblclick`（会被 d3-drag `raise()` 重排 DOM 搞挂），改 `onNodeClick` 手动判定（同 key 380ms 内再点→`expand`）。
- **自动适配视图**：`fitView()` 求所有点包围盒并设 zoom transform，使整个子图居中且完整；渲染时**同步 `simulation.tick()`**（>1000 点跑 180 次、其余 400 次）先跑完布局再首帧绘制 + `fitView(false)`，↻ 平衡后 `simulation.on('end')→fitView(true)`。大图（>1000 点）自动降 `charge`/`link distance` 并去掉 collide，避免卡顿。
- **其他 UI**：查询按钮 `#queryBtn` CSS `text-transform: uppercase` → 「QUERY」；Gremlin textarea 用 `autoGrowGremlin()` 随输入/回车自动长高（上限 280px，`resize:none`）；连线时跳过端点不在展示子图中的边（避免 NaN）；空结果弹 toast。
- **算法下拉（框选旁）**：`louvain` / `连通分量(cc)`（同社区同色，`communityColor` 用 golden-angle HSL）/ `pagerank`（金色虚线环高亮 Top 20% 点）；算法在**当前显示子图**上就地计算、不重排；图变化（查询/扩展/框选/重置/切换图实例）时自动 `clearAlgo()`。
- **视觉设计（本轮改版）**：**Logo = 凤凰+图**——火焰渐变（`#fbbf24→#fb7185→#c084fc`）的翅/身/尾羽线条 + 白色节点圆点 + 髻冠，置于深靛紫渐变圆角方块（`.brand .logo`）；另有 8 个备选方案见 `static/logos/`（`preview.html` 可对比）；`static/favicon.svg` 为标签页图标；Lucide(MIT) 描边图标内嵌（Account=`user-round`、IP=`globe`、Phone=`smartphone`、默认=`hexagon`、工具栏=`refresh-cw`/`scan`/`maximize-2`）——`app.js` 里 `ICONS`/`DEFAULT_ICON` 为 `{tag,attrs}` 数组，经 `appendIcon` 生成 `<symbol>`，由 CSS `.node use`（`stroke: currentColor`）着色；靛紫渐变主色 + 玻璃拟态顶栏（backdrop-filter）+ 点阵画布 + 统计卡片（`.stat-grid`/`.stat-card`）；**节点尺寸基准** r=10.8 / ring=14.4 / icon=10.8（collide 24、label dy 22），**边** stroke 1.8（选中 3.6）；力导向 `alphaDecay(0.012)` + `velocityDecay(0.55)`（更缓和），↻ 平衡用 `alpha(0.6)`。
- **画布搜索（模糊）**：画布右上角 `#searchBox`（`#searchInput` + `#searchClear`，Escape 清空）；`applySearch()` 对当前显示子图做**大小写不敏感的子序列模糊匹配**（空格分词、AND 语义），匹配字段含 `label / id / 属性名 / 属性值 / 边端点 id`；命中元素加 `search-hit`（天蓝发光），未命中 `search-dim`（opacity 0.15），命中边的两端保持可见；haystack 缓存在 `d.__hay`；`render()` 末尾自动重放搜索。
- **前端源码漂移风险**：`frontend/` 与 `static/` 目前是**拷贝关系**，改动后必须同步拷贝（见第 8 节命令）。
- **结果累加 vs 替换**：早期 `merge()` 只累加，导致换查询仍显示旧点边；已改为查询先 `resetGraph()`。
- **双击失效**：d3-drag 的 `raise()` 在每次 mousedown 重排 DOM，原生 `dblclick` 不可靠；已改为手动双击判定并移除 `raise()`。
- **地图缩放不适配**：新图/大子图会超出画布；`fitView()` 已保证自动居中适配。
- **幽灵点**：查询不存在的 id（如 888 在新数据集）时，`buildSubgraph` 原来会把读不到的 id 也当成点输出（空属性）→ 已改为跳过 null 读结果；前端也跳过端点缺失的边。
- **双击 vs 短路径选点冲突**：双击的第一下会把点加入 `picked`，导致最短路选点被污染 → 双击分支里已撤销该 pick。
- **`AccountToPhone` 出度上限 10**：播种时超过 10 条的会被 CDT 淘汰，造数时需遵守 schema 的 maxOutgoingEdges。

---

## 7. 已完成的性能结论（供对比）

- `g.V(888,'Account').batch(30).out('Payment').in('PhoneToAccount')` ≈ 9.8~13 ms（Aerospike 本地）。
- 点缓存（`both().both()` 去重 100 个 888）：cache ON ≈ 13ms vs OFF ≈ 26ms（约 2×）。
- 批大小 10→20→30→50→100：约 29→22→19→17→14 ms（收益递减，甜点 ~30-50）。
- `inE` 比 `in` 快约 28%（少一层点读取）。
- `X(label).count()` 改写为 `XE(label).count()` 后避免了读点，显著降低大扇出计数开销。

---

## 8. 构建 / 测试 / 运行命令

```bash
# 全量编译（跳过测试）
./mvnw -q -DskipTests compile

# 打包 http + web（含依赖）
./mvnw -q -pl graph-http,graph-web -am -DskipTests package

# 集成测试 / benchmark（需本地 Aerospike 3000，namespace=test）
AEROSPIKE_HOST=localhost AEROSPIKE_PORT=3000 AEROSPIKE_NAMESPACE=test \
  ./mvnw -pl graph-integration-test -am test \
  -Dtest=GremlinFanoutBatchBenchmark -Dsurefire.failIfNoSpecifiedTests=false

# 查询层端到端 + 播种 http_rich_v2（Account 含 list/map）
AEROSPIKE_HOST=localhost AEROSPIKE_PORT=3000 AEROSPIKE_NAMESPACE=test \
  ./mvnw -pl graph-http -am test -Dtest=QueryApiIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false

# 前端：改完 frontend/public 后必须同步拷贝到 static 再打包
cp graph-web/frontend/public/index.html graph-web/src/main/resources/static/index.html
cp graph-web/frontend/public/css/style.css  graph-web/src/main/resources/static/css/style.css
cp graph-web/frontend/public/js/app.js      graph-web/src/main/resources/static/js/app.js

# 前端 Node 开发模式（8082）
cd graph-web/frontend && npm install && npm start

# 启动服务
java -jar graph-http/target/graph-http-*.jar --phoenixgraph.storage.vertex-set=http_rich_v2   # 8081
java -jar graph-web/target/graph-web-*.jar                                                     # 8080
```

benchmark 类（`graph-integration-test` 下）：`GremlinQueryLatencyTest`、`GremlinFanoutBatchBenchmark`、`GremlinMultiHopBenchmark`（set=`rich_vertices_v2`）、`GremlinCompiledLogicBenchmark`、`AerospikeQueryIntegrationTest`、`GremlinBothCountBenchmark`；共享造数 `QueryDataSeeder`（Account/IP/Phone + Payment/PhoneToAccount）。

---

## 9. 踩坑 / 已修复记录（重要）

- `Unknown property 'status' in vertex schema 'IP'`：按需 bin 读取在 `has` 遇到不存在属性时抛异常 → 已处理（跳过未知属性）。
- `SchemaValidator` 不可变错误列表 `UnsupportedOperationException` → 改 `new ArrayList<>(...)`。
- 边属性投影误作用于顶点 hop（`out('Payment').values('accountStatus')` 报错）→ **仅 `outE/inE/bothE` 用 `edgeReadProperties`，顶点 hop 中间边传 `List.of()`**。
- `both()` 无参先前报 `both expects exactly 1 argument` 是 graph-http jar 过旧 → 重打包解决。
- 全边路子图缺边记录 → `readAllEdgeClasses` 调用 `ctx.recordEdges`。
- **`List<String>`/`Map<String,String>` 没写进 Account 节点**：不是存储问题，是 `QueryApiIntegrationTest.accountProps` 漏写了 id 4/5；已补 `List.of(...)`/`Map.of(...)` 并在测试中加断言。
- **两跳 `both().both()` 子图 500 NPE**：`AerospikeVertexStore.decode` 对 `record.bins == null` 未防护——Aerospike 批量读对**不存在的 key** 会返回非 null 但 `bins==null` 的 record（`BatchPolicy.respondAllKeys` 默认 true）。已加 `isMissing(record)` 守卫（点/边 store 的批量读、delete、upsert 分支），并对 `decideUpdates`/`mergedPropertyMax`/`readEdgeMap` 加空 bins 防护。触发根源是播种数据里 L1 账户的 `AccountToIP`/`AccountToPhone` 边指向不存在的 `9100..9109`/`7100..7109`（顶点其实是 `9001..9010`/`7001..7010`）——已修正边目标，并在播种前 `truncate` 清空 set 保证干净。
- 框选之前失效：d3-zoom 的拖拽平移与 d3-brush 冲突 → zoom `filter` 在 `brushMode` 时禁用平移（`!brushMode && !event.ctrlKey && !event.button`），且框选开关用 `applyBrush()` 不重跑力导向。
- graph-web `frontend/` 与 `static/` 为拷贝关系，改一处必须同步（易漂移）。

---

## 10. 关键文件索引（续做时先看这些）

- `graph-query-gremlin/.../GremlinGraphQuery.java`（compile/optimizeSteps/planProperties/neededProperties/executeCompiled/executeSubgraph/runCompiled）
- `graph-query-gremlin/.../GremlinSteps.java`（readAllEdges/readAllEdgeClasses/readNeighbourVertices/edgeReadProperties）
- `graph-query-gremlin/.../GremlinContext.java`（vertexCache/edgeCache/readAllEdgesBatchCached/adaptBatchSize/collectSubgraph/recordVertex/recordEdge/buildSubgraph）
- `graph-query-gremlin/.../GremlinStepRegistry.java`、`GremlinAlgorithms.java`、`Subgraph.java`
- `graph-http/.../QueryController.java`、`QueryService.java`、`QueryRequest.java`、`SchemaController.java`、`GraphInfo.java`、`StorageConfig.java`、`GraphService.java`（`checkPropertyValue` + `DataQualityValidator`）
- `graph-web/.../ProxyController.java`、`WebConfig.java`；`graph-web/frontend/public/js/app.js`（含前端算法 louvain/cc/pagerank）
- `graph-storage-aerospike/.../AerospikeVertexStore.java`（mergeVertexUpserts/upsertVertices 合并）、`AerospikeEdgeStore.java`（upsertEdges 聚合、upsertBatchInto）
- `graph-schema/.../SchemaValidator.java`、`FileSystemGraphSchemaManager.java`、`EdgeSchema.java`（含 `EdgeEndpointSchema`）
- 资源：`graph-http/src/main/resources/payment-graph.json`；`graph-web/src/main/resources/static/`（生产静态资源）；`docker-compose.yml`、各 `Dockerfile`、`.dockerignore`

---

## 11. 待办 / 下一步（续做重点）

1. **Docker 验证**：本机无法访问 Docker Hub，未实跑镜像构建；有外网时跑 `docker compose up --build`（Aerospike 空库需用 batch API 播种）。
2. **统一前端源**：把 `frontend/public` 与 `static` 合并为单一源（Maven 阶段拷贝或删 Node 开发目录），消除双份漂移。
3. 可选优化：`accountLimited` Map 的 CDT per-entry 独立 lastUpdatedTime；边属性读取 bin 级裁剪；`has('unknownProperty')` 宽容语义；`compiledCache` LRU 上限；`repeat/times`、真流式 barrier；按 `GremlinMultiHopBenchmark` slowest 继续优化。
4. Docker 内数据播种脚本（`http_rich_v2` 分层图 + IP/Phone）。
5. 前端双击扩展走 `both()`，`${id}` 含单引号未转义需加强。

---

## 12. 知识图谱 Demo（NELL-995 → `kg-entity-graph`，按 relation 分片）

**建模（v2，本轮改）**：只有 **一种点 `Entity`**、**一种有向边 `Entity_Entity`**；把「同一个实体」按 **relation 的 hash** 分片到 10 份，让每个分片自己承担一部分关系，避免所有边挤在一条点上。

### Schema（`graph-http/src/main/resources/kg-entity-graph.json`，由脚本生成）

- 生成脚本：`node tools/generate-kg-schema.js`（改结构就改脚本重跑；**不要手改 JSON**）。
- **1 个点 schema**：`Entity`；属性 `name`（id=1，**display 字段**）、`shard`（id=2，LONG）；id schema 类型 **STRING**。
- **1 条边 schema**：`Entity_Entity`，`DIRECTED`，`maxOutgoingEdges=2000` / `maxIncomingEdges=2000`；边属性 `relation`（id=3）、`xrelation`（id=4）。
- 每个三元组只存**一条有向边** `head→tail`（`AerospikeEdgeStore.upsertEdges` 同时写 head 的 out bin 与 tail 的 in bin），两端都能查到。

### id 方案（`KnowledgeGraphIds`）——按 relation 分片

```text
shard    = floorMod(relation.hashCode(), 10)   // 0..9，用 floorMod 避免负 hash
vertexId = entityName + "_" + shard            // 例：concept_mammal_animals_2
label    = "Entity"                            // 只有一种点
edge     = "Entity_Entity"                     // 只有一种边
```

- 三元组 `(h, r, t)` 的**源点和目标点用同一个 shard**（都来自 `r`），所以**一个关系的所有边都落在同一对分片里**，10 个分片之间互不相连。
- 同一个实体名会在它参与的每个 shard 里各出现一次（实测 149,678 条三元组 → **113,553 个点**）。
- 查询时客户端自己拼 id，一条命令：
  ```bash
  node -e "const h=s=>{let x=0;for(const c of s)x=(Math.imul(31,x)+c.charCodeAt(0))|0;return ((x%10)+10)%10};console.log(process.argv[1]+'_'+h(process.argv[2]))" "concept_mammal_animals" "concept:specializationof"
  ```

### xrelation（反向关系）

`xrelation = inverse(relation)`，由数据集**自动推导**（`KnowledgeGraphDataset.deriveInverseRelations`）：统计 `(h,r,t)` 与 `(t,r',h)` 共现，取支持度最大（≥10）的 `r'` 作为反向；**只接受互为最优的配对**（保证 `inverse` 是**对合**：`inverse(inverse(r))==r`），其余视为自反。结果：200 个关系里 **6 对**真反向（`bookwriter↔agentcreated`、`worksfor↔organizationhiredperson`…），188 个自反。对合性保证 `filter(relation.equals(R) || xrelation.equals(R))` 能从任意一端、任意方向命中 R。

### 数据集

- 上游 `thunlp/OpenKE` 的 `benchmarks/NELL-995`（75,492 实体 / 200 关系 / 149,678 三元组）。
- 本地：`graph-integration-test/src/test/resources/kg/nell995/*.txt.gz`（gzip 1.5MB + `README.md`）；重下：`./tools/download-nell995-dataset.sh`。
- `KnowledgeGraphDatasetTest` 校验规模、分片规则、逆关系对合性（**不需要 Aerospike**，秒级）。

### 播种

```bash
# 全量（113,553 点 / 149,678 边，8 线程约 58s；已播种自动跳过，KG_RESEED=true 强制）
AEROSPIKE_NAMESPACE=test ./mvnw -pl graph-integration-test -am test \
    -Dtest=KnowledgeGraphIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
# 小样本冒烟（写进独立 set kg_nell995_v2_s5000）
KG_TRIPLE_LIMIT=5000 ... 同上
```

环境变量：`KG_TRIPLE_LIMIT`（0=全量，默认 0）、`KG_PARALLELISM`（默认 8）、`KG_RESEED`、`AEROSPIKE_VERTEX_SET`。
`KnowledgeGraphSeeder`：点 500/批、边 1000/批；并发写同 bin 会 generation 冲突，所以 store 用 `maxWriteAttempts=10` + 每 chunk 失败重发 4 次 + **静默 BusinessLog/ErrorLog**。

### 可直接粘贴到可视化界面（实测）

| 用途 | vertex id | gremlin |
|---|---|---|
| **推荐**（41 点 / 40 边） | `concept_mammal_animals_2` | `g.V('concept_mammal_animals_2','Entity').bothE()` |
| 关系过滤 | 同上 | `g.V('concept_mammal_animals_2','Entity').bothE().filter(relation.equals('concept:specializationof') \|\| xrelation.equals('concept:specializationof'))` |
| 压测大点（2237 度） | `concept_book_new_9` | `g.V('concept_book_new_9','Entity').bothE()` |

> 注意：`bothE()`（无参）在这种建模下比 `both('Entity_Entity')` 更直观；两者等价（只有一种边）。

### 12.1 《三国演义》知识图谱（`sanguo-kg`，中文文档数据集）

第二套知识图谱，数据与管线都与 NELL 那套独立，完整说明见 [SANGUO-KG.md](SANGUO-KG.md)。

- **数据**：OpenKG《三国演义》部分，三个文档文件（**4,487 篇**人物文档 `renwu_process_v1.csv`、60 个关系的
  OWL 本体 `theory.owl`、153 条 curated 三元组 `triples.csv`），gzip 在
  `graph-integration-test/src/test/resources/kg/sanguo/`，重下：`tools/download-sanguo-dataset.sh`。
- **解析成 RDF**：`SanguoOntology`（解析 OWL 的类/关系/逆关系/domain/range/OWL 特性，并归纳出 11 个
  粗粒度关系家族）+ `SanguoDataset`（读人物文档，派生 父亲/母亲/兄弟/配偶/孩子/效力/官职/籍贯/出场）
  + `SanguoRdf`/`SanguoRdfGenerator`（TBox + ABox，物化逆关系与对称关系）。产物冻结在
  `src/test/resources/kg/sanguo/rdf/`：`sanguo-ontology.ttl`（486 语句）、`sanguo-kg.ttl`、`sanguo-kg.nt`、
  `VALIDATION.md`，由 `SanguoRdfTest` 逐字节比对，所以不会漂移。
- **校验**：`SanguoRdfValidator` 查 10 条规则（谓词已声明、无悬挂 IRI、domain/range、非自反、非对称、
  逆/对称闭包、对合性、声明逆关系、重复三元组）；3,395 条 domain/range 通过、**0 违反**、523 条无法判定
  （关系端点人物没有文档，性别未知）。校验实际抓出了 3 个 bug：匿名 `owl:inverseOf` 漏读、军阀势力与
  人物同名（33 条 `rdfs:range` 违反 + 自环）、人物属性谓词未在 TBox 声明。
- **存储**：schema `sanguo-kg`（`graph-http/src/main/resources/sanguo-kg.json`，由
  `node tools/generate-sanguo-schema.js` 生成）：一个点类型 `Entity`（`name/shard/entityType/gender/
  courtesyName/historicity/history/novel`）+ 一个有向边类型 `Entity_Entity`（`relation/xrelation/
  relationLabel/relationFamily`，度上限 2000）。**属性名避开 `id/label/type/source/target`**（查询层的元键，
  重名会被遮蔽）。分片规则与 `kg-entity-graph` 一致：`name + "_" + floorMod(relation.hashCode(), 10)`，
  1,102 实体 → 1,637 条点记录 / 2,447 条边，10 个分片里 9 个有边。
- **播种/查询**：`SanguoGraphIntegrationTest`（点 500/批、边 1000/批、8 线程约 2.3s，已播种自动跳过，
  `KG_RESEED=true` 强制）；`SanguoExampleQueries` 是 20 条实例查询（Gremlin 10 + Cypher 10）的可执行真源，
  `SanguoExampleQueriesTest` 每条都跑并断言有结果，UI 下拉框同源。
- **服务**：默认 profile 已挂 `sanguo-kg`（set `kg_sanguo_v1`）；单图 profile `--spring.profiles.active=sanguo`。
- **RocksDB-Edge 双胞胎**：`sanguo-kg-rocksdb-edge`（schema id=5，同一个生成脚本产出，带 `storage` 块）
  存**同一份数据**，但**点不做 hash**——点 id 就是实体名（`曹操` 一条记录 = 整个邻域，而不是
  `曹操_2…曹操_9` 十条），并且只存 2,298 条源关系（RocksDB-Edge 把一条边同时挂在源点出表和目标点
  入表，不需要复制逆/对称闭包；Aerospike 版 2,447 条）。`SanguoRocksDbLoader` 装载（1,102 点 /
  2,298 边，126 ms），`SanguoRocksDbQueries` + `SanguoRocksDbEdgeTest` 跑 20 条典型查询并**打印结果**，
  还和 Aerospike 实例做同问题答案对齐（lord 22 / serves 92 / appearsIn 78 / father 1 全部一致）。
- **Cypher↔Gremlin 与多跳查询**：`SanguoRocksDbQueries.CYPHER_TO_GREMLIN`（10 条：把 §5.3 的 Cypher
  逐条翻译成 Gremlin，两种语言都跑并并排打印）与 `MULTI_HOP`（12 条：两跳/三跳的关系链、共同邻居、
  同势力、同回出场、路径强度、可达性、三跳环、跨势力、祖孙三代、混合关系链）。测试把两种语言的
  计数对齐断言（刘备两跳邻居 202、徐庶同僚 124、诸葛亮同回 46、刘备→曹操 2 跳 1 条 / 3 跳 295 条）。
  例句也进了 UI；两份 Markdown 表由测试写到 `target/sanguo-rdf/`。详见 [SANGUO-KG.md](SANGUO-KG.md) §5.7/§5.8。
- **⚠ 数据集原来是截断的**：仓库里的 `renwu_process_v1.csv.gz` 只有上游**前 331 行**（截断在半行，
  合法 gzip 所以测试全过）。上游同文件有 **4,487 人**。补齐后 base 1,102→**6,202** 实体、2,298→**21,050** 关系，
  RDF 4,946→**46,632** 三元组；补回来的是**刘备**、**整个曹操谋士群**（郭嘉/荀彧/荀攸/贾诩/刘晔/董昭…）
  与**过五关六将**。`tools/download-sanguo-dataset.sh` 现在**校验行数与结尾换行**。
  同时暴露并修掉 4 个真 bug（`无` 占位符、自环、同名人 `[势力]` 后缀被剥、单元格空格/`|` 混用）
  和 2 类抽取误报（密诏/企图等**意图**被当成事实、`X军师Y` 取错人）。详见 [SANGUO-KG.md](SANGUO-KG.md) §0。
- **方案 ④：关系的时间维度**（`year`/`chapter`/`seq`/`provenance` 四个边属性，三个图都加）：
  `year`/`chapter` 是**两个端点共现的区间**（文档只给人时间，不给关系时间），`seq` 是**叙述序**
  （`字段序*1000+句子序`）。实测：徐庶∩刘备 与 徐庶∩曹操 的回数区间**完全相同**（35-48），
  所以“先刘备还是先曹操”**只能由 `seq` 回答**：1002（效力于刘备）< 1003（北投曹操）。详见 [SANGUO-KG.md](SANGUO-KG.md) §7.8。
- **方案 C（新图 `sanguo-kg-text`，RocksDB-Edge）**：读被结构化解析忽略的两列（`历史`/`小说`），用
  **词典 + 正则**抽取（无模型，可复现）：`mentions`(22,951, 含 42 条典故边) / `holds`(111) / `killed`(300) / `fought`(136) /
  `strategistOf`(5) / `advisorOf`(27) = **23,530 条关系**，另新增 **100 个职衔 + 75 个人物 + 28 个典故**。
  图规模：**6,444 实体 / 44,576 关系**（base 为 6,202/21,050，**+242 / +23,526**）。两个新关系家族：`提及`、`冲突`。
  **方案 ③（同一张 text 图）**：把传记里的**典故**建成节点而不虚构参与者 —— 新增 3 个类型
  `Event`(事件,21) / `Artifact`(器物,3) / `Idiom`(成语,4) 共 **28 个节点**，边是 `传记主人 --mentions--> 典故`。
  一张**数据驱动**的典故表（每个条目都真的出现，**最长匹配优先**，`败走麦城` 不会退化成 `走麦城`）。
  `过五关斩六将` 因此成为节点，而 `孔秀/韩福/孟坦/卞喜/王植/秦琪/六将` **一律没有虚构**（测试断言它们不在实体表里，
  且 28 个典故名与 base 实体零碰撞）—— 这就是方案 ③（抽取）与方案 ②（引入外部知识）的分界线。
  > ⚠ Gremlin 引擎没实现 `within(...)`，写了会**静默返回空**：筛类型要用 `has('entityType','Event')`。
  > ⚠ RocksDB-Edge **只存源方向边、不存逆边**（44,576 而非闭包 44,820）：`司马懿` 的臣边要用
  > `inE().filter(relation.equals('lord'))`，用 `outE().filter(relation.equals('minister'))` 会空 ——
  > 曹操 --lord--> 司马懿 是存了的（臣共 22 人），且 `advisorOf`(谋士,师承) 与 `minister`(臣,君臣)
  > 是**两个关系族**，不能互推。郭嘉则有 curated 的 `lord` 但**没有传记**（荀彧/贾诩/刘晔/蒋济/董昭 也没有），
  > 全库「谋士」只出现 5 次。详见 [SANGUO-KG.md](SANGUO-KG.md) §7.2.3。
  它**自动**抽到了方案 B 手工加的「五虎上将」（含之首），并回答了 base 图答不了的：刘备的军师=**徐庶/庞统**、
  曹操的谋士=**司马懿**、关羽斩杀过**颜良/文丑/华雄**、谁杀了关羽=**孙权**、关羽传记提到了谁。
  单字简称（「忠与**羽**连战」）用 `ALIAS` 表还原；带 provenance 的报告在
  [SANGUO-KG-TEXT-EXTRACTION.md](SANGUO-KG-TEXT-EXTRACTION.md)。详见 [SANGUO-KG.md](SANGUO-KG.md) §7。
  > 过程中修了两个真 bug：① `SanguoDataset.of()` 原来只转一次关系，`mentions` 会先把 `中郎将`/`第1回`/`乌戈国`
  > 建成 `Person`，后面的 `holds`/`appearsIn` 就改不过来了（93 条 `range` 违反）→ 改成**两趟：先把属性关系的
  > 目标定型，再处理人物–人物关系**。② `mentions` 会把势力名（东汉/魏）塞进「人物名集合」，触发“势力与人物同名
  > 就加 `势力` 后缀”的规则，把 `东汉` 拆成 `东汉(Person)`+`东汉势力(Faction)` → 构建“人物名集合”时**只认人物–人物关系**。
  > ③ `韩馥曰：“吾有上将潘凤，可斩华雄。”`（未然转述）会产生反向的 `潘凤--killed-->华雄`，
  > 而真正的 `华雄--killed-->潘凤`（`被华雄斩了`）反而漏了 → 新增**被动式规则** `被X（所）斩|杀|诛`，
  > 并让**转述/引语句不认主动式**（被动式方向语法确定，仍保留）。`killed` 17→19（多出 华雄→潘凤、曹操→高顺）。
  > ④ `曹操手下谋士程昱` 里「手下」不在允许前缀（只有 `帐下|之|的`）→ 程昱漏了；补上 `麾下|手下|部下`
  > 后又修两个副作用：贪婪捕获 `程昱识破` 要走 `personTarget()` 归一；`if/else` 会把 `谋士之一`（解析不出人名）
  > 当成命中而吃掉兜底，使 司马懿 的边消失 → 改成「先试他指，解析不出再试自指」。`advisorOf` 1→2。
- **本地补充三元组（上游缺口）**：`kg/sanguo/triples-extra.csv`（8 条，明文）补的是「本体有类型、上游没写事实」
  那类缺口 —— 方案 B 把「五虎上将」当成职位（`关羽 --holds--> 五虎上将之首`、张飞/赵云/马超/黄忠 → `五虎上将`），
  另补 `刘备 --lord--> 关羽/张飞`（上游只记了「义弟」）。层因分析、诊断查询、三种修复方案都在
  `SANGUO-KG-QUERIES.md` §9；实测：`g.V('五虎上将','Entity').inE().filter(relation.equals('holds')).otherV()` → 五虎将。
  > 边 id 也改成了**由三元组本身导出**（`head|relation|tail`），而不是下标 —— 原来按下标当 id，
  > 插入一条三元组会让后面所有边的 id 平移，重复播种时旧边会以旧 id 留在 bins 里变成重复边
  >（实测 `曹操` 的度从 220 涨到 280）。
- **查询手册（单文件，方便复制）**：[SANGUO-KG-QUERIES.md](SANGUO-KG-QUERIES.md) —— 把全部查询汇总成一个文件：
  22 条问答式查询（如「关羽和曹操是什么关系？」「刘备的手下有谁是从曹操那边叛逃过来的？」）、
  12 条两跳/三跳、20 条典型、10 条 Cypher→Gremlin 转换；每条都有 Gremlin +（如可行的）Cypher +
  **实测答案**，开头还有两份「一行一条」的纯查询清单（`sanguo-queries.gremlin` / `sanguo-queries.cypher`
  也单独存了一份）。由 `SanguoRocksDbEdgeTest#writesTheQueryCookbook` 生成：它把目录里的**每一条**查询
  在这张图上跑一遍、记下回答、断言非空，再写出文件 —— 所以手册不会和代码走偏。
  详见 [SANGUO-KG.md](SANGUO-KG.md) 第 5 节。

---

## 13. 多图实例 + 查询错误回传（本轮新增）

### 多图共存（payment-graph 与 kg-entity-graph 同时加载）

- `phoenixgraph.storage.graphs` 声明**附加图**（每个图一个 Aerospike set），主图仍是 `schema` / `vertex-set`：
  ```yaml
  phoenixgraph.storage:
    vertex-set: http_demo_v1
    schema: payment-graph
    graphs:
      - schema: kg-entity-graph
        vertex-set: kg_nell995_v2
  ```
- 新增 `GraphProperties`（配置项）、`GraphBinding`（schema + 该 set 的 vertex/edge store）、`GraphResolver`（`require(name)` / `schemas()`）、`GraphRegistry`（按配置顺序保存，`Map.copyOf` 会乱序所以用 `LinkedHashMap + unmodifiableMap`）。
- `QueryService` 按 graphName 取 binding 并**每个图缓存一个 `GremlinGraphQuery`**；`QueryService.ambient(vs, es)` 是给单图/测试用的静态工厂（**注意：Spring 会因两个构造器报 “No default constructor found”，所以做成静态工厂而不是重载构造器**）。
- `SchemaController` 改为从 `QueryService.graphs()`（即 registry）取，所以 `/api/v1/graphs` 按配置顺序返回两个图，UI 下拉框直接能切。
- 只想跑知识图谱：`--spring.profiles.active=kg`（`application-kg.yml` 覆盖成单图）。

### 查询错误 → UI 弹窗

- 后端 `ApiExceptionHandler`（`@RestControllerAdvice`）：`QueryException` / `IllegalArgumentException` → **400**，其它异常 → **500**，body 统一为
  ```json
  { "error": "...", "exception": "全类名", "stackTrace": ["...", "..."], "hint": "Check the Gremlin query syntax and the request parameters" }
  ```
- `ProxyController` 本来就把上游错误 body 原样转发，所以 graph-web 侧不用改。
- 前端 `runQuery()` 非 2xx 时 `await queryError(res, query)` 组装 `Error.detail`（status/query/message/exception/stackTrace/hint），`showQueryError()` 弹出**模态框**：错误信息（红）、出错查询原文、可折叠的深色堆栈、提示语「请检查 Gremlin Query 是否写正确」，带「复制 / 关闭」，支持 Esc、点遮罩、右上角 × 关闭。i18n 新增 `queryErrorTitle/queryErrorHint/errorMessage/errorQuery/errorStackTrace/copy/copied/close`。

### 前端配色（默认视图更亮）

- `COLORS` 增加 `Entity: '#6366f1'`；`DEFAULT_COLOR` 从灰 `#64748b` 改为亮 `#6366f1`。
- 新增 `PALETTE`（10 色亮色）与 `SHARD_COLORS`（10 色，与 PALETTE 同色系），`DEFAULT_ICON` 之外给 `Entity` 加了「两个圆 + 连线」的图标。
- `colorFor(label, id)` 三级优先：已知类型固定色 → id 结尾 `_<数字>`（知识图谱的 relation 分片）取 `SHARD_COLORS[shard]` → 未知类型按 label hash 从 `PALETTE` 取色（不再全灰）。
- 图例同步支持分片：`legendKey(d)` = `label` 或 `label · #shard`，点击过滤按该分组（payment-graph 无 `_<数字>` 后缀，行为不变）。

---

## 14. 并行 seed 查询 + 多 id 输入（本轮新增）

### 1) seed 节点并行化（引擎）

- 需求例句：`g.V(id1, id2, id3, 'Account').batch(20).bothE('Payment')` → 对 id1/id2/id3 **并行**发起查询，批大小用 `batch(n)`（默认 **20**）。
- 实现：`GremlinContext`:
  - `batches(ids)`：把多 id 读**按 `batch(n)` 精确切批**（保持 `batch(n)` = 单次存储请求大小这个契约），再交给 `readBatches` **并行**执行（并发度 `parallelism()`，默认 `min(8, CPU)`；只有 1 批或 `parallelism(1)` 时内联执行，无额外开销）。
  - `seedBatches(ids)`：**只用于 `g.V(...)` 的 seed**——把 id 均分到最多 `parallelism` 批（每批仍 ≤ `batch(n)`），因此 3 个 id → 3 个并行请求、100 个 id + `batch(20)` → ≤8 个请求。入口 `GremlinContext.readSeedVertices`（`GremlinGraphQuery.start` 调用）。
  - 顺序保证：`readBatches` 按批序返回并逐一拼接，结果顺序与串行一致；异常原样重抛（`QueryException` 不会被包一层）。
  - 缓存/子图 map 仍是单线程写：并行任务只读存储，缓存与 `recordEdges` 都在主线程拼接阶段做。
- 可调：`GremlinGraphQuery.parallelism(n)`（1 = 关并行）。
- 注意：**只有需要 seed 属性的查询才会读 seed**（`g.V(ids).bothE()` 只需 id → 跳过记录读，这是原有的最小化优化），所以测 seed 分批得用 `values('name')` 这类步骤。

### 2) UI 支持多 id 输入

- `#vertexIdInput` 现在接受多个 id，分隔符支持**英文逗号 / 全角逗号 / 分号 / 空白**：`concept_country_germany_0, concept_country_germany_1 …`。
- `parseIds()` 拆分 + trim + 去空，然后拼 `g.V('id1','id2',…,'Entity').both().both()` 交给原来的两跳查询；placeholder 与 i18n 提示已改成“多个用逗号分隔”。

### 3) 子图剪枝（顺带修的真问题）

原来 `returnSubgraph` 采集“遍历碰到的所有东西”：`bothE().filter(...)` 把过滤掉、但**被读过**的边也画出来了，于是 Q1 和 Q2 的子图一模一样（看起来像过滤器失效）。
现在 `filter`/`has` 丢弃元素时调 `GremlinContext.dropElement` 把它们从采集结果里删掉；`recordEdges` 记录的“边的另一端点”改存 `edgeEndpointVertices`，**只在还有存活的边引用它时才进子图**（不会留悬挂点）。

### 4) 重新生成的验证查询（`concept_country_germany` 在全部 10 个 shard 都有点）

```bash
SEEDS=$(node -e "const s=[];for(let i=0;i<10;i++)s.push(\"'concept_country_germany_\"+i+\"'\");console.log(s.join(','))")
```

| # | 用途 | gremlin（在 UI 里粘贴整行） | 实测 |
|---|---|---|---|
| Q1 | **10 个 seed（0-9）+ 全部边** | `g.V($SEEDS,'Entity').batch(20).bothE()` | 123 点 / 118 边 |
| Q2 | **关系过滤 → 跳到第一跳的点** | `g.V($SEEDS,'Entity').batch(20).bothE().filter(relation.equals('concept:sportschoolincountry') \|\| xrelation.equals('concept:sportschoolincountry')).otherV()` | 16 点 / **6 边**（6 个运动项目） |
| Q3 | **再多一跳的关系过滤 + 查询** | `…Q2….bothE().filter(relation.equals('concept:sportfansincountry') \|\| xrelation.equals('concept:sportfansincountry')).otherV()` | 70 点 / 99 边 |
| Q3b | 变体：两跳同一关系 | `…bothE().filter(relation.equals('concept:countrylocatedingeopoliticallocation') \|\| xrelation.equals('concept:countrylocatedingeopoliticallocation')).otherV()…（重复一次）` | 815 点 / 890 边 |
| Q4 | **seed → entity → entity 两跳** | `g.V($SEEDS,'Entity').batch(20).both().both()` | 7164 点 / 18736 边（~0.5s） |

（Q2 第一跳拿到的名字：`concept_sport_soccer / skiing / american_football / ski / golf / football`。）

**重要性质**：一个分片里的点只有“hash 到该分片的关系”的边，所以**两跳查询必须用同一 shard 的关系**（Q3 的 R2 与 R1 同 shard：`sportschoolincountry` 与 `sportfansincountry` 都 hash 到 shard 1）。跨 shard 的关系过滤会返回空。

---

## 15. HNSW 向量索引（graph-hnsw）

把 HNSW（Hierarchical Navigable Small World）**直接用图存**：点 = 向量，边 = 层级邻居关系。新模块 `graph-hnsw`（纯算法 + 端口），Aerospike 适配器在 `graph-query-aerospike`，Gremlin 步骤在 `graph-query-gremlin`。

### 15.1 层级方案（`HnswLevels`）

| 概念 | 规则 |
|---|---|
| 层数 | `topLevel(size) = ceil(log2(size))`，层 `0..topLevel` → `topLevel+1` 个**双向边类** |
| 例 | `size = 4096 = 2^12` → 13 个边类 `vector_0 … vector_12`（对应用户说的"13 种双向边"）|
| 边类命名 | `vector_<level>`（`HnswLevels.edgeName(l)`）|
| 点所在最高层 | `level = floor(-log2(u))`，`u ∈ (0,1]` 由 **id 的 splitmix64 哈希**导出（确定性，重建/多进程一致）|
| 分布 | `P(level = l) = 2^-(l+1)` → 第 0 层约一半的点、第 1 层约四分之一…**顶层约 1 个点**（就是搜索的入口）|

实测（SIFT small 10,000 行）：`0:5112 1:2503 2:1239 3:637 4:318 5:159 6:71 7:32 8:14 9:8 10:4 11:1 12:1` —— 底层包含全部 id，顶层只有 1 个点 ✓。

**边 = 双向（BIDIRECTIONAL）**，两个端点各存一份（`ve_<edgeSchemaId>_0` bin），所以一次读就能拿到某点在某层的全部邻居。度上限：HNSW 自己裁剪到 `m = 15`（第 0 层 `2m = 30`），schema 里声明 `maxDegree = 2×`（60 / 30）作为兜底——store 的 `evictOldest` 因此永远不会先于 HNSW 裁剪触发。

### 15.2 端口与适配器

| 端口（graph-hnsw） | Aerospike 适配器（graph-query-aerospike） | 说明 |
|---|---|---|
| `HnswStore` | `AerospikeHnswStore` | 分层邻居读写；`neighboursBatch` 走 `getEdgesBatch`（原生批读），`linkBatch`/`unlinkBatch` 走 `upsertEdges`/`deleteEdges`（**一次插入的所有边只发一批请求**，store 内部按 (vertex, bin) 聚合，每个 bin 只读改写一次）|
| `HnswVectorSource` | `GraphQueryVectorSource`（通用，基于 `GraphQuery`） | 批量读 `vector` 属性；构建时用 `HeapHnswVectorSource`（全量向量驻内存，10k×128 float = 5MB）|
| `HnswMetadataStore` | `AerospikeHnswMetadataStore` | **要求 5 的"特定 Aerospike Keyvalue"**：单条记录 `hnsw_meta/hnsw:<VertexType>`，存 metric/m/efConstruction/efSearch/dims/size/topLevel + **入口点列表（id + 层数，≤10，高层在前）**|

`HnswIndex` 只用这些端口，所以同一套代码在内存（`InMemoryHnswStore`，单测）和 Aerospike 上都能构建/查询。`CachingHnswStore` 是**写穿透**的邻居缓存装饰器——构建时读远多于写，加上它以后一次插入的存储往返降到个位数。

### 15.3 插入（`HnswIndex.insert`，按数据集行序从第 1 行到第 N 行）

1. `level = levelOf(id)`（指数衰减抽样）
2. 从**入口点**（最高层那 1 个点，或注册表里的 ≤10 个）开始，**逐层贪心下降**到 `level + 1`（每层只走一步局部最优，不展开）
3. 从 `min(level, 入口层)` 往下到 `0`，每层：
   - 用 `efConstruction`（默认 100）做**最佳优先搜索**得到候选 W；
   - 用**邻居选择启发式**（候选 c 只有在比已选集合里所有点都更靠近参照点时保留，不够再用最近的落选者补满）选出 ≤ `m = 15`（第 0 层 ≤30）个邻居；
   - 与这些点**双向连边**，并对每个刚才连过的邻居**重新裁剪**（超过 `maxLinks` 就按同一启发式删掉最差的边）——对应用户说的"超过 efConstruction 的边删除"；
   - 用 W 里最近的点作为下一层的入口
4. 若 `level` 足够高，把 `(id, level)` 写进入口点注册表（≤10，高层优先）——**下次查询直接从它开始迭代**

一次插入的所有 link/unlink 先在 `Session` 里缓冲（用一层 overlay 让同一次插入内的裁剪能看到刚建的边），最后 `linkBatch` + `unlinkBatch` 各一次落库；元数据记录每 200 次插入（或入口点变化时）写一次。

### 15.4 搜索

```
入口点(≤10) → 最高层贪心下降到第 1 层 → 第 0 层 ef=efSearch 最佳优先 → 最近的 k 个
```
每次操作一个 `Session`：批量预取邻居向量、按 id 缓存距离与邻居表 → **每个展开节点约 2 次存储往返**（邻居列表 + 向量批读）。`nearest(k)` 会排除点自身。

### 15.5 Gremlin 步骤 `nearest`

```
g.V('4242','Vector').nearest(20)         # 最近的 20 个向量
g.V('4242','Vector').nearest(20, 200)    # 候选表 200（更准、更慢）
g.V('4242','Vector').nearest(20).values('name').count()
g.V('4242','Vector').nearest(20).elementMap()
```
- 步骤按当前 traverser 的**点类型**解析索引（`GremlinGraphQuery.hnswIndex(index)` 注册，`QueryService` 用 `AerospikeHnswIndexFactory` 自动打开"schema 里有 `vector_0`+`vector_1` 且元数据存在"的索引），所以一个实例可以同时服务多个向量图；没配索引时报 `No HNSW index is configured for vertex type 'X'`。
- 结果是**顶点**（属性齐全，可继续 `values/has/filter/count`），并记进子图 → UI 直接画出来。
- 在 `GremlinGraphQuery` 里 `nearest` 被当作 hop（属性投影/自适应批大小按 hop 处理）。

### 15.6 演示数据集（SIFT small）

```bash
tools/download-siftsmall-dataset.sh            # → .datasets/siftsmall（ftp://ftp.irisa.fr/local/texmex/corpus/siftsmall.tar.gz, 5.3MB）
node tools/generate-hnsw-schema.js --size 10000 --m 15 --dimensions 128
# → graph-http/src/main/resources/hnsw-sift.json（1 个 Vector 点 + 15 个 vector_* 双向边，勿手改）
```

数据集格式：`.fvecs`/`.ivecs` = 每条记录 `int32 维度` + `维度个 float32`（小端）；10,000×128 底库、100×128 查询、100×100 精确答案（`siftsmall_groundtruth.ivecs`）。`HnswDatasetLoader` 解析（流式测试也用它），`HnswGraphIntegrationTest` 端到端跑通：

```bash
AEROSPIKE_HOST=localhost AEROSPIKE_PORT=3000 AEROSPIKE_NAMESPACE=test \
HNSW_DATASET_DIR=/tmp/siftsmall_dl/siftsmall HNSW_LIMIT=0 HNSW_VERTEX_SET=hnsw_sift_v1 HNSW_FORCE=true \
./mvnw -pl graph-integration-test -am test -Dtest=HnswGraphIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```
（可用 env：`HNSW_LIMIT`（0=全部）、`HNSW_M`、`HNSW_EF_CONSTRUCTION`、`HNSW_EF_SEARCH`、`HNSW_VERTEX_SET`、`HNSW_FORCE`、`HNSW_DATASET_DIR`；测试会自己 truncate + 播种 + 建图 + 校验。）

点模型：`Vector`（id = 行号**字符串**，与其它 demo 图一致；`name` = `sift-<row>` 且是 display 属性；`vector` = `list<double>`）。

### 15.7 实测结果（SIFT small，Aerospike localhost:3000）

| 指标 | 2,000 行冒烟（hnsw_smoke） | **10,000 行全量（hnsw_sift_v1）** |
|---|---|---|
| 层数 | 0..11（12 个边类） | **0..14（15 个边类 `vector_0..vector_14`）** |
| 建图 | 152s（13.2 次插入/秒） | **868s ≈ 14.5 分钟（11.5 次插入/秒）** |
| 写入量 | 87.6k 边 / 48.9k 裁剪 | **448,207 条边 / 250,804 次裁剪 / 44.8M 次距离计算** |
| 层级直方图 | `0:1020 1:488 2:240 3:123 4:66 5:35 6:16 7:6 8:3 9:2 10:1` | `0:4971 1:2494 2:1249 3:666 4:313 5:159 6:75 7:37 8:15 9:10 10:3 11:5 12:2 13:0 14:1` |
| 顶层点数 | 1 | **1**（层 14 只有 1 个点 = 搜索入口）；层 0 含全部 10,000 个 id |
| 层 0 度数 | 抽样 200 点，最大 30，无孤立点 | 抽样 200 点，最大 30，无孤立点 |
| 召回（ef=50，100 条查询） | recall@20 = 1.000（对子集内暴力解） | **recall@10 = 0.992，recall@20 = 0.988**；ef=200 → 0.989 |
| ground truth 校验 | —（子集跳过） | **暴力解与数据集自带 ground truth 一致度 1.000**（验证 `.fvecs/.ivecs` 解析） |
| 查询延迟 | 冷 88ms（p50 88） | 热 89ms / **冷 93ms**（p50 94, max 122） |
| Gremlin `nearest(20)` | `g.V('1000',...)` → 20 点，20/20 精确，95ms | `g.V('5000',...)` → 20 点，**20/20 精确**，88ms |

schema 默认 `efSearch = 30`（见第 16 节）时的同一套索引：**recall@20 = 0.983**、热 64ms / 冷 74ms（ef=200 → 0.989）；`nearest(20)` 命中 19/20，剩下一个是被 REST 写进来的 `20000`（行 5000 的副本，距离相同、不在数据集 ground truth 里）。

```
[hnsw] entry points: [7434@14, 3614@12, 7802@12, 4873@11, 5566@11, 8033@11, 8074@11, 9622@11, 1674@10, 4715@10]
[hnsw] gremlin : g.V('5000','Vector').nearest(20) -> [6320, 3790, 5673, 6412, 7996, 9720, 5697, 9853, 1890, 7093,
                 2481, 4914, 3816, 189, 4566, 8704, 7417, 6319, 5988, 6824]   (20/20 exact, 88 ms)
```

> 召回对比用"对**已索引行**暴力求解的精确 top-k"：2,000 行子集里大多数查询的真邻居根本不在索引内（子集内"最近邻"的对比度低），所以子集召回 1.000 并不代表 SIFT 的难度；**10,000 行的 0.988 才是与数据集自带 ground truth 对比的真实质量**（两者在数据一致时吻合度 1.000）。
> 建图速度瓶颈是 Aerospike 的读改写次数：每次插入 ~44 条新边 + ~24 次裁剪，`upsertEdges`/`deleteEdges` 聚合后每个被触及的 (vertex, bin) 仍需一次读改写。要再快就得换成 CDT 的 `MapOperation.put` 服务端单次操作（当前 store 用客户端读改写以支持度上限淘汰与按属性 CAS）。
> 查询延迟（~90ms）来自每次展开约 2 次存储往返（邻居列表 + 向量批读），ef=50 时约 50~60 次展开。

### 15.8 端到端查询（HTTP / UI）

`application.yml` 默认同时挂三个图：`payment-graph`（http_demo_v1）、`kg-entity-graph`（kg_nell995_v2）、`hnsw-sift`（hnsw_sift_v1）；`--spring.profiles.active=hnsw` 只挂向量图（见 `application-hnsw.yml`）。
```bash
curl -s -X POST http://localhost:8080/api/query -H 'content-type: application/json' -d '{
  "graphName":"hnsw-sift",
  "query":"g.V('\''4242'\'','\''Vector'\'').nearest(20)",
  "returnSubgraph":true }'
```
UI：图实例下拉里选 `hnsw-sift`，顶点类型 `Vector`，ID 框填行号 → 纯 ID 查询走 `g.V('id','Vector').both().both()`（会同时走所有层，能直接看出 HNSW 的层次结构：高层边连得很远、底层边连着近邻）；要最近的 20 个向量就切到 Gremlin 框输入 `g.V('4242','Vector').nearest(20)`。`Vector` 在前端有固定亮色（`#7c3aed`）和专门图标。

---

## 16. schema 驱动的向量索引（isVector / 自动建边 / 写入即索引）

第 15 节的 HNSW 是"手工建图"：schema 里显式列出 `vector_0..vector_14`，由 `HnswGraphIntegrationTest` 逐行插入。这一节把它变成**由 schema 声明、由写入驱动**的能力。

### 16.1 `isVector` 声明（要求 1）

```json
{ "id": 2, "name": "embedding", "type": { "list": "DOUBLE" },
  "isVector": true, "metric": "COSINE", "m": 8, "efConstruction": 64, "efSearch": 24 }
```

| 字段 | 含义 | 默认 |
|---|---|---|
| `isVector` | 该属性是**向量**；写入时自动建索引，查询可用 `nearest(k)` | `false` |
| `metric` | `L2` / `COSINE` / `INNER_PRODUCT` | `L2` |
| `m` | 每层保留的邻居数（第 0 层 2m） | `15` |
| `efConstruction` | 建索时候选表大小 | `100` |
| `efSearch` | 查询时候选表大小（可用 `nearest(k, ef)` 临时覆盖） | **`30`** |

- 模型：`PropertySchema.isVector()` / `vectorConfig()`（record `VectorIndexConfig`），`PropertyDto` + `SchemaJsonCodec` 读写，`VertexPropertySchema.vector(...)` 协变 fluent。
- **校验**（`SchemaValidator`，只 warning/error 不静默）：向量属性必须是数字 list（`list<double>/list<float>/list<object>`，非数字 → error）；`list<object>` 给 warning（读代价高）；一个点类型声明多个向量属性给 warning（各自一套层边）。
- **配置优先级**：`metric/m/efConstruction` 在建过索引后以**元数据记录**为准（它们决定图长什么样），`efSearch` **始终**取 schema（查询期旋钮）→ `AerospikeHnswIndexFactory.config(declared, metadata)`。

### 16.2 自动创建索引边（要求 2）

`VectorIndexSchemas`（graph-schema）：

- **命名**：向量属性 `embedding` 的第 `l` 层 = 边类 `embedding_<l>`（`layerEdgeName`）。所以属性名叫 `vector` 时就是第 15 节的 `vector_0..vector_12` ✓ 完全兼容已有数据。
- **自动创建**：`ensureLayer(schema, vertex, property, level)` 缺哪层建哪层 —— 边为 **BIDIRECTIONAL 自环类型**（`source=target=该点类型`），idSchema STRING，`maxDegree = 2 × (level==0 ? 2m : m)`（比 HNSW 自己的上限宽一倍，store 的 `evictOldest` 只做兜底）。
- **id 稳定性**：已存在的层边**保留自己的 id**，新层用"当前最大 id + 1"顺延 —— 因为 Aerospike 的 bin 名里带 edge schema id（`ve_<id>_0`），id 漂移就会换 bin。同一份 schema 反复启动得到的 id 完全一致。
- **动态增长**：索引以 `dynamicSize(true)` 打开时，层数跟着数据走 —— `topLevel(size) = ceil(log2 size)`，**每翻一倍加一层**；写入前调 `HnswLayerProvisioner`（= `AerospikeHnswIndexFactory.provisioner(schema)`）把该层边类建出来。打开一个已有索引时，会把元数据承诺的层全部补齐（否则下降时会读到不存在的层）。
- **读路径容错**：`AerospikeHnswStore` 对"schema 里不存在的层"返回空邻居（那一层从来没写过东西），**写路径仍然报错**（写之前一定先 provision）——所以一个纯查询进程即使层还没建也不会 500，只会少走一层。
- **启动预热**：`VectorIndexManager.@PostConstruct` 为每个向量属性建好需要的层（schema 已声明的层数，或元数据里的 topLevel 取大）。实测：`hnsw-dynamic.json` **一个层边都没声明**，服务启动后 `/api/v1/graphs` 立刻显示 `embedding_0..embedding_10`（11 个）✓。

### 16.3 写入即索引（要求 3）

```
POST /api/v1/vertices  {id, vertexSchemaName, properties:[{index:1,value:"name"},{index:2,value:[...]}]}
   → AerospikeVertexStore.upsertVertex(...)      # 先落库
   → VectorIndexManager.indexVertex(graph, type, id)
        → 该点类型每个 isVector 属性的 HnswIndex.insertIfPresent(id)   # 读回向量 → HNSW 插入
```
- `GraphService` 在 `upsertVertex`（以及 batch 里的每个顶点）写入成功后触发；`insertIfPresent` 在"这次写没带向量"时静默跳过，`WriteOutcome` 非成功不索引（`GraphServiceTest` 有 2 个用例覆盖）。
- 索引失败会让写请求返回错误（顶点已落库），不做静默吞掉。
- **实测**（8083，`hnsw-dynamic` profile）：`Doc 300` 的 embedding 复制成 `Doc 900` 写入 → 200；`g.V('900','Doc').nearest(5)` → **300 排第一**（距离 0），子图 6 点 / 4 边；新点度数 20（第 0 层 16 + 第 1 层 4）。同一套逻辑写进 10k 的 SIFT 图（`Vector/20000` = 行 5000 的副本）：`g.V('5000','Vector').nearest(3)` 第一条就是 `sift-copy-of-5000` ✓。
- 因此 10k 的 `hnsw_sift_v1` 现在有 **10,001** 个点（元数据 size 也是 10001）：集成测试按 `existing.size() >= size` 判定可复用（写入只会往图里加点，不影响前 10,000 行的召回测量），并在日志里提示多出来的点数。

### 16.4 入口点：写 Aerospike + 内存缓存 1 分钟（要求 3）

- 入口点（≤10 个，id + 层数）+ `metric/m/efConstruction/efSearch/dims/size/topLevel` 都存在**单条元数据记录**：`hnsw_meta/hnsw:<VertexType>`（一个点类型多个向量属性时是 `hnsw:<VertexType>:<property>`，首个属性保持老 key，兼容已建索引）。
- `CachedHnswMetadataStore`（默认 `DEFAULT_TTL = 1 分钟`）：读**每分钟最多回源一次**，`write` 直写穿透并就地更新缓存 → 构建/写入侧永远看到自己的最新状态。
- `HnswIndex.search()` 每次搜索前 `refreshEntryPoints()`：从（带 TTL 的）元数据读一次，**合并**入口点（按 id 取层数更高者、降序截断到 10）与 size —— 于是**另一个实例写入的新点，本实例最多 1 分钟后就能作为搜索起点**（有单测：writer 插入 300 点后 reader 不重开索引即可搜到）。

### 16.5 查询（要求 5）

`nearest(k)` / `nearest(k, ef)` **不变**（第 15.5 节）；`efSearch` 默认从 schema 读（默认 30），显式第二参数仍然优先。索引按**当前 traverser 的点类型**解析，`VectorIndexManager` 把每个点类型的索引注册进 `GremlinGraphQuery`（写路径与查询路径**共用同一批索引实例**，所以刚写完的点立刻可查）。

### 16.6 端到端验证（profile 与图）

| profile / 端口 | 图 | 说明 |
|---|---|---|
| 默认（8081） | `payment-graph` + `kg-entity-graph` + **`hnsw-sift`**（10k，schema 显式 15 层）+ **`hnsw-dynamic`**（600，schema 只有 `isVector`） | UI 里都能选 |
| `--spring.profiles.active=hnsw` | 仅 `hnsw-sift`（只读查询 Demo） | |
| `--spring.profiles.active=hnsw-dynamic`（8083） | 仅 `hnsw-dynamic`，**作为主图**（写接口在这里） | 写入即索引的 Demo |

```bash
# 生成"只声明 isVector、不声明任何层边"的 schema
node tools/generate-hnsw-schema.js --size 600 --layers 0 --vertex Doc --vector-property embedding \
  --graph hnsw-dynamic --id 8 --metric COSINE --m 8 --ef-construction 64 --ef-search 24 \
  --out graph-http/src/main/resources/hnsw-dynamic.json
```

集成测试新增 `HnswGraphIntegrationTest#growsAVectorIndexFromWritesOnAFreshSchema`：schema 零层边 → 边写边索引 600 个向量 → 断言层边被自动建到 `embedding_10`、元数据（入口点/metric/efConstruction/efSearch）正确、冷读 recall@20=0.987（ef=24）、`g.V('300','Doc').nearest(20)` 20/20 精确。

### 16.7 明天要做：向量检索 + 属性过滤（`has`/`filter` 组合）

**问题**：`g.V(id,'Vector').nearest(20)` 是**纯向量**最近邻；业务上往往是"在满足属性条件（如 `status=ACTIVE`、`category=X`、`price<100`）的点里找最近的 20 个"。直接把 `nearest` 和 `has` 串起来（`…nearest(20).has('category','x')`）**不保证**凑够 20 个：过滤发生在取完 k 个之后，命中率取决于属性分布，极端情况一个都不剩。

**可选方案（明天讨论/实现）**：
1. **放大候选 + 后过滤**（最简单、今天就能用）：`nearest(k × f).has(...).limit(k)`，`f` 按选择率估（如 3~10）。要保证召回就必须给 `f` 定一个上界并接受最坏情况退化。
2. **efSearch 自适应**：`nearest(k, ef)` 里把 `ef` 放大到 `k / selectivity`，把过滤"下沉"到搜索的候选表里（HNSW 支持 filtered search：候选不满足条件时不进结果但仍可继续扩张）。这才是"能保证查到 20 个"的正解 —— 需要给 `HnswIndex.search` 加一个 `Predicate<Object>`（满足性判据，需按需读属性 → 可批读），以及在候选表提前收敛（`ef` 候选全被判掉）时的处理策略。
3. **预过滤 + 子图内精确检索**：先用属性索引/`has` 选出候选集合（可能很大），再在其中做精确检索 —— 对小候选集最准，但要有属性索引支撑。
4. **落库为向量 + 属性联合索引**：把热点过滤属性编码进层边/元数据（如按 category 分层建索引）—— 成本高，除非过滤维度固定且少。

建议顺序：先把 (1) 做成"文档化的近似方案"，同时把 (2) 的 `Predicate` 下沉做进 `HnswIndex.search`（`GremlinSteps.nearest` 之后紧跟 `has`/`filter` 时自动把条件传下去，并给出 `ef` 放大策略），(3)(4) 视过滤维度稳定性再定。

---

## 17. 属性过滤的向量检索 + 索引重建 + schema 约束收紧

这一节是第 16 节之后的五件事：重复边 id 只记 bizlog、索引重建、一个点类型只能有一个向量属性、给测试索引数据加 string/int 业务属性、以及把属性过滤**下沉**进 HNSW 搜索。

### 17.1 重复边 id 记 bizlog，不再让验证崩掉（要求 1）

`GraphVerifier` 原本把顶点 1001 两个 edge class 的出边用 `Collectors.toMap(edgeId, ...)` 合并，遇到"老数据 + 新数据共用一个 edge id"（`QueryApiIntegrationTest` 通过 REST 往共享默认 set 写过的 Payment `e2`）直接抛 `IllegalStateException: Duplicate key e2`。

现在：**后出现的条目胜出**（两个 bin 的读取顺序是确定的，即当前 schema 的那份胜出），重复的 id 写一条 **business log**，里面带上两边完整数据：

```
BusinessLogEntry[operation=verifyGraph, target=1001, success=false, details={
  edgeId=e2,
  kept=otherVertex=2001, lastUpdatedTime=…, createdTime=…, properties={1=…},
  duplicate=otherVertex=1002, lastUpdatedTime=…, createdTime=…, properties={5=…,6=…,3=…},
  reason=duplicate edge id in the out-edges of one vertex}]
```

运维拿到这条日志就能定位要清理的记录；测试不再因脏数据而红（`AerospikeIntegrationTest` 现在 2/2 通过）。

### 17.2 重建索引（要求 2）

`POST /api/v1/vector-index/rebuild`（body 可选 `{graph, vertexSchemaName, vectorProperty}`，缺省=主图/该点类型唯一的向量属性）+ `GET /api/v1/vector-index` 列出每个向量索引的配置与状态。

`VectorIndexManager.rebuild(...)` 的步骤：

1. **重新加载数据**：ids 有两个来源 —
   - **走底层图**：从元数据入口点 BFS **layer 0**（`neighboursBatch`，批量读）。layer 0 按构造包含全部被索引过的点，所以**老数据（记录里没存 key）也能全部枚举**；
   - **扫描**：`AerospikeVertexStore.scanVerticesWithProperty(...)`（一次 scan，`sendKey` 取回记录 key）。为此**写策略统一打开 `sendKey`**（`WritePolicy`/`BatchWritePolicy`），新写入的记录自带 key，scan 就能直接给出 id —— 两条来源取并集，因此"建索引之后又批量导入的点"也会被重建纳入。
   - 向量按 500 一批 `readVertices(...)` 读回堆内存（`HeapHnswVectorSource`），构建期间零额外读。
2. **删除旧索引**：`AerospikeEdgeStore.clearEdgeClasses(layerSchemas, ids)` —— 每个点一次 `put`，把该属性所有层的 3 个方向 bin 写成**空 map**（`RecordExistsAction.UPDATE_ONLY`，不存在的点不会凭空建记录），然后 `AerospikeHnswMetadataStore.delete()` 删元数据记录。
3. **重建边**：用 schema/元数据里的配置（metric/m/efConstruction 以元数据为准）新建 `HnswIndex`（`overwrite(true)` + 堆向量源），按序插入，最后一并 flush 元数据。
4. **换血**：`indexesByGraph` 丢弃该图重新打开，且 `QueryService` 的引擎通过 `hnswIndexSource(...)` **按查询实时解析索引**（不再是构造时固定一份），所以重建后无需重启/重建引擎，下一次查询就用新图。

实测（8083 动态图）：`{"vertices":601,"layers":11,"links":13821,"pruned":7468,"seconds":26.9}`，重建后 `nearest(3)` 返回与重建前完全相同的邻居；601 而不是 600 —— 多出来的那个正是**建索引之后**通过 REST 写进来的点，说明重新加载没有被"旧索引范围"限制住。

### 17.3 一个点类型只能有一个向量属性（要求 3）

`SchemaValidator` 把"同一个点类型声明多个 `isVector` 属性"从 warning 改成 **error**：

```
vertex schema 'Doc' declares 2 vector properties (a, b); a vertex type may have at most one (isVector=true)
```

理由：层边以属性名命名，查询步骤按点类型解析索引，两个向量属性会有歧义。`VectorIndexSchemas`/工厂里的多属性 key 逻辑（`hnsw:<Type>:<prop>`）保留为防御性代码，但正常输入进不到那里。

### 17.4 给测试索引数据加业务属性（要求 4.1）

`tools/generate-hnsw-schema.js` 新增 `--bucket-values` / `--category-values`（默认 10/10），给向量点类型加上：

| 属性 | 类型 | 取值 | 选择性 |
|---|---|---|---|
| `bucket` (id 3) | INT | `row % 10` | 10% |
| `category` (id 4) | STRING | `CATEGORIES[(row / 3) % 10]`（不同分区） | 10% |

`HnswDatasetLoader.seedAttributes(store, schema, rows, batch, progress)` **单独导入**这两个属性（只写 bins，不碰向量），集成测试在跑过滤实验前调用一次（10,000 行 14s），并且写属性时顺带把记录 key 存进 Aerospike（见 17.2）。

> 踩过的坑（值得记住）：单元测试里我最初用 `i % 10` 生成 bucket，而合成数据是 20 个簇（`i % 20`）—— 于是**同一个簇里所有点的 bucket 相同**，过滤属性与向量空间强相关。这种情况下"最近 100 个里符合过滤条件的 = 0"，任何过滤方案都会退化。真实业务里属性与向量往往是**弱相关**的，所以过滤器实验必须用**与几何无关**的属性（集成测试用真实 SIFT 行号取模，单元测试改用固定种子 PRNG）。

### 17.5 `efSearch` 默认回到 50（要求 4.2）

`VectorIndexConfig.DEFAULT_EF_SEARCH = 50`、`HnswConfig.defaults()` 也是 50；schema 里不写就用这个值，`nearest(k, ef)` 仍可临时覆盖。`hnsw-sift.json` 现在显式写 `efSearch: 50`。

### 17.6 把 `has` / `filter` 下沉进 HNSW 搜索（要求 4.3）

**问题**：`g.V(id,'Vector').nearest(20).has('bucket', 3)` 若按"先取 20 个最近、再筛"，10% 选择率下平均只剩 2 个（实测 10k 图上 `nearest(20)` 里只有 4 个 bucket=3）。

**做法**（三层）：

1. **`HnswFilter` 端口**（graph-hnsw）：`propertyNames()`（搜索要顺带批读哪些属性）+ `selectivity()`（0=未知，搜索自己估）+ `test(vertexId, properties)`；`maxEf(ef,k)` 给候选表上限（默认 `max(ef,k)×20`）。
2. **搜索下沉**（`HnswIndex.searchLayerFiltered`）：候选表保存**最好的 efTarget 个候选（匹配与否都算）**，结果只从匹配的里面取；**不匹配的点照样进候选表、照样被扩张**（"不满足的候选不进结果表但仍参与扩张"）。
   - `efTarget` 初始 = `clamp(ceil(k / selectivity), ef, maxEf)`（**ef 按选择率放大到 k/selectivity**），selectivity 未知时先用 `max(ef,k)`；
   - 一次遍历后若匹配数 < k，用**实测通过率**重新估 `k/passRate` 并放大（至少翻倍）重试，最多 4 次、上限 `maxEf`；通过率为 0 时不做无意义推断，直接翻倍增长；
   - 被检查的顶点数有硬上限（`maxEf × 6`，下限 1000），所以"什么都匹配不上"的过滤器只会**少给结果**，不会变成全图扫描。
   - 顺带把向量与过滤属性**合并成一次批读**（`HnswVectorSource.elements(ids, extraProperties)` → `GraphQueryVectorSource` 一次 `readVertices` 投多个 bin），过滤查询的每节点往返次数与不带过滤时相同。
3. **查询语言自动下沉**（`GremlinGraphQuery.compile`）：`nearest` **后面紧跟**的 `has`/`filter` 步骤被吸收成过滤谓词（`GremlinSteps.hnswFilter(conditions)`，语义与那两个步骤一致：属性缺失=不匹配、数字按数值比较），并从计划里移除；`nearest` 之后隔了别的步骤（如 `values`）就不下沉，保持原语义（"这 20 个里符合条件的"）。

**实测**（10,000×128 SIFT，`bucket` 10% 选择率，ef=50，k=20，各 80 条查询，对**过滤后的精确 top-20**算召回）：

| 方案 | recall@20 | 延迟 |
|---|---|---|
| **下沉过滤** | **0.998** | 419 ms/查询（候选表放大到 200，约 3× 普通查询） |
| 先取 20 再过滤 | 0.143 | 137 ms/查询（快，但答案基本是错的） |

单元测试（2000 点合成数据，PRNG 属性）`pushed-down=0.990 vs post-filter=0.108`；查询语言侧 `g.V('4242','Vector').nearest(20).has('bucket',3)` → **20 个点全部 bucket=3**（`nearest(20)` 原始结果里只有 4 个），字符串属性 `has('category','music')` 同理。

**取舍**：过滤越狠，候选表越大、越慢（`k/selectivity` 是硬道理）。目前是"先按声明的选择率一次到位、不够再按实测通过率放大"；如果调用方能给准确的选择率（统计信息/采样），第一次就能取到正确大小、避免重试。

---

## 18. 有向边的存储布局（source 存出边 / target 存入边）与两跳验证

### 18.1 布局

`upsertEdge` 对 DIRECTED 边写两条记录（`AerospikeEdgeStore.sourceDirection/destinationDirection`）：

| 记录 | bin | 内容 |
|---|---|---|
| **source 的顶点记录** | `ve_<edgeSchemaId>_2`（`EDGE_DIR_OUT`）| 一个 CDT map，key = edgeId，value 里 `otherVertexId` = **destination** |
| **target 的顶点记录** | `ve_<edgeSchemaId>_1`（`EDGE_DIR_IN`）| 同样以 edgeId 为 key，但 `otherVertexId` = **source** |

UNDIRECTED / BIDIRECTIONAL 边则两边都写 `ve_<id>_0`（`EDGE_DIR_BOTH`）。因此**有向边没有任何"共享 bin"**：
`getEdges(v, schema, EDGE_DIR_BOTH)` 对有向边永远是空 —— 这一条本身就是"方向被真正分开存了"的证明（只有无向边才会落到 `_0` bin）。

查询层的 `EdgeSide.BOTH` 不是读 `_0` bin，而是**OUT + IN 各读一次再合并**（`AerospikeGraphQuery.readEdgesForVertex`），所以 `both()`/`bothE()` 在有向图上依然能看到两个方向。

### 18.2 验证（`DirectedEdgeStorageIntegrationTest`）

用 `QueryDataSeeder` 的确定性有向数据（Account 100..199 成环 + 跨 3 的弦：`p_i: 100+i -> 100+(i+1)%100`、`p_i: 100+i -> 100+(i+3)%100`；`pa_i: Phone 300+i -> Account 100+i`），在**独立 set**（默认 `directed_edges_v1`）里做了四层断言：

1. **裸 bin**：直接 `client.get` 记录 → Account 100 的 out-bin = `{p0, p100}`、Account 101 的 in-bin = `{p0, p198}`；`edgeBin(schemaId, OUT/IN)` 正是 `ve_<id>_2` / `ve_<id>_1`。
2. **存储层**：`getEdges("100", Payment, OUT)` = `{p0, p100}`、`IN` = `{p99, p197}`、`BOTH` = **空**；同一条 `p0` 在 source 的 out-bin 里 `otherVertexId=101`、在 target 的 in-bin 里 `otherVertexId=100`；`p0` 不会出现在 source 的 in-bin / target 的 out-bin；`PhoneToAccount`（Phone→Account）在 Account 上只有 in、在 Phone 上只有 out。
3. **查询层**：`readEdges(..., EdgeSide.OUT/IN/BOTH)` 分别给出目标/源/并集；反向读（Account 的 `PhoneToAccount` OUT、Phone 的 `PhoneToAccount` IN）为空。
4. **遍历语言**：
   - 一跳：`out/in/both` 三种方向的确切邻居集；`outE` 的 `side=OUT`、`inE` 的 `side=IN`；
   - 两跳四组合（精确集合 + 计数）：
     `out.out(100) = {102,104,106}`、`in.in(100) = {194,196,198}`、`in.out(100) = {100,102,198}`、`out.in(100) = {100,102,198}`；
     `out().out().count() = in().in().count() = 4`（含重复，104 与 196 各有两条路径），`dedup()` 后为 3；
   - 跨 schema：`g.V('300','Phone').out('PhoneToAccount').out('Payment') = {101,103}`，而 Phone 的 `in('PhoneToAccount')` 为空。

**线上 demo（8081 / `http_demo_v1`，真实 Payment 数据）** 另做了一次与数据无关的镜像校验：随机 10 个种子点收集 **167 条不同的边**，逐条核对
「A 的 `outE('Payment')` 里的边 id，必须出现在 B 的 `inE('Payment')` 里，且 `side=IN`、`otherVertexId=A`」→ **167/167 通过，0 条反向泄漏**；再用写接口造链 `3001→3002→3003` 验证 `out.out(3001)={3003}`、`in.in(3003)={3001}`、`in.out/out.in(3002)={3002}`，删除边后两个端点同时消失。

### 18.3 顺带修掉的一个真 bug

验证过程中发现：`@PathVariable Object id` 形式的接口（`GET|DELETE /api/v1/vertices/{id}`、`PUT|DELETE /api/v1/vertices/{id}/properties/{index}`、`DELETE /api/v1/edges/{edgeId}`）**全部返回 400/500**：

```
Name for argument of type [java.lang.Object] not specified, and parameter name information
not available via reflection. Ensure that the compiler uses the '-parameters' flag.
```

根因：项目编译时没保留方法参数名，Spring 无法按名字绑定 `@PathVariable`。修复两处：

1. 父 `pom.xml` 的 `maven-compiler-plugin` 加 `<parameters>true</parameters>`（Java 8+ 的 `-parameters`）；
2. 这几个 handler 的注解写上显式名字（`@PathVariable("id")` 等），不再依赖编译开关。

新增 `VertexEdgeControllerTest`（6 个 MockMvc 用例，覆盖 `GET|DELETE /vertices/{id}`、`PUT|DELETE …/properties/{index}`、`DELETE /edges/{edgeId}`）——这类按 id 的读写以前没有任何测试覆盖，所以 bug 一直没暴露。

验收：`GET /api/v1/vertices/3001` → 200 带属性；`DELETE /api/v1/edges/e-3001?...` → 200 且两个端点的 out/in 同时为空；`DELETE /api/v1/vertices/{id}` → 200。

---

## 19. openCypher 查询（graph-query-cypher）

新增模块 `graph-query-cypher`：在**与存储无关**的 `GraphQuery` 端口上实现 openCypher 子集（`MATCH … WHERE … RETURN`），与 Gremlin 引擎并列，共用 HTTP/UI。

### 19.1 分层与复用

为了不再复制遍历引擎的公共部分，把两样东西下沉到 `graph-query`（两个引擎共用）：

| 类 | 原位置 | 现位置 | 用途 |
|---|---|---|---|
| `Subgraph` | graph-query-gremlin | `query.result.Subgraph` | 两个引擎统一的"碰过的子图"返回类型（UI 渲染） |
| `NeighborVertexResolver` | graph-query-gremlin | `query.traversal.NeighborVertexResolver` | 由 (边类, 方向) 推**邻居点类型**（边的 target/source 端点），Cypher 的 `(a)-[:T]->(b)` 与 Gremlin 的 `out()` 都需要 |

`graph-http` 依赖 `graph-query-cypher`（并已在 pom/dependencyManagement 注册）。

### 19.2 支持的语法（`CypherParser`，手写递归下降 + 位置化报错）

```
MATCH pattern (',' pattern)* [WHERE expr]
RETURN [DISTINCT] (* | item (',' item)*) [ORDER BY key [ASC|DESC] (',' …)*] [SKIP n] [LIMIT n]
pattern  := node (rel node)*
node     := '(' [var] [':' Label] ['{' key ':' expr, … '}'] ')'
rel      := '-->' | '--' | '<--' | '-[var:TYPE1|TYPE2*1..3]->'   （方向/类型皆可省）
item     := expr [AS alias]
```
- **表达式**：`= <> != < <= > >=`、`AND/OR/NOT`、`IN [...]`、`IS [NOT] NULL`、`CONTAINS`/`STARTS WITH`/`ENDS WITH`/`=~`、括号、列表字面量、`$参数`；
- **函数**：`id/type/labels/toLower/toUpper/trim/length/size/coalesce`；
- **聚合**：`count(*)`、`count(x)`、`count(DISTINCT x)`、`sum/avg/min/max/collect`（投影里含聚合就按非聚合项**隐式分组**）；
- 大小写不敏感、`//` 注释、结尾 `;`；
- **只读**：`CREATE/MERGE/SET/DELETE/REMOVE/WITH/UNWIND/CALL/UNION/OPTIONAL MATCH` 会报明确的 "not supported" ✓。

### 19.3 执行语义（`CypherGraphQuery`）

- **锚点（必须）**：存储没有 label 索引，所以 MATCH 必须有一个"起点" —— 节点带 `id`（`(a:Account {id:'135'})`）或 `WHERE a.id = '135'`，且该变量有点标签；否则报错并给出写法提示。**永不扫全表**。id 支持数字字面量 ↔ 字符串 key 的自动兜底（`{id: 100}` 能找到 key 为 `"100"` 的记录）。
- **匹配**：按步扩展；边按（类型 × 方向）读取（无类型 `-[r]-` 读该点类型的**所有**边类），到端点后用 `NeighborVertexResolver` 定出邻居点类型再读点，最后套用该节点自身的约束（标签 + 内联属性）。每次执行的读都带缓存。
- **谓词下推**：`WHERE` 里所有变量都绑定后**立即**过滤（不是最后统一过滤）。
- **join**：`MATCH p1, p2` 共享变量即连接；某变量已绑定则从它继续扩展，否则用各自的锚点做嵌套循环。
- **变长路径**：BFS 展开 `*min..max`，中间跳绑内部变量，`min..max` 内每一层都绑定目标变量（所以 `*1..2` 同时给出一跳和两跳的结果）；上限 6 跳（防跑飞），变长关系暂不支持命名（`-[r*1..2]->` 报错）。
- **投影**：逐行求值 → 聚合分组 → `DISTINCT` → `ORDER BY`（可用别名/列名，也可用表达式；元素按 id 排序）→ `SKIP` → `LIMIT`。`RETURN *` 按"变量在模式里出现的顺序"给列。
- **子图**：`executeSubgraph` 收集遍历到的点/边（按 label+id 去重）→ `Subgraph`，UI 画图用。

### 19.4 HTTP：query template + parameters

`POST /api/v1/query` 增加 **`language`** 字段（`gremlin` 缺省 / `cypher`）：

```json
{ "graphName": "payment-graph",
  "language": "cypher",
  "query": "MATCH (a:Account {id: $id})-[p:Payment]->(b:Account) RETURN b.id, p.status LIMIT $limit",
  "params": { "id": "135" },
  "returnSubgraph": true }
```
- **两种参数写法都支持**：`${name}` 由 `QueryService` 在文本层替换（两个语言都一样）；Cypher 另外原生解析 `$name`（`CypherParameters`）。参数**按字符串**传（id 就是记录 key，`135` 与 `"135"` 是不同 key）；数值比较仍然成立，因为 `Values.compare` 会把数字与数字字符串按数值比 ✓。
- 响应：`{ "columns": [...], "rows": [ {列: 值}, … ], "subgraph": {vertices, edges} | null }`（`returnSubgraph` 决定 `subgraph` 是否为空）。Gremlin 的响应形状不变 ✓。
- 错误：语法错误带**行列号**（`line 1, column 29: expected ')' …`），未知语言 → `Unknown query language 'sparql'; supported: gremlin, cypher` ✓，都走统一的 400 + 堆栈响应 ✓。

### 19.5 UI：语言切换 + 示例 + 结果表

- 查询框上方新增**语言选择**（Gremlin / Cypher），切换会换 placeholder 并重载示例；
- 查询框下新增**示例下拉**（按"语言 × 图实例"分组，选中即填入文本框）；
- 侧栏新增 **「结果 / Rows」** tab：Cypher 的 `columns/rows` 渲染成表格（Gremlin 查询会清空它）；`returnSubgraph` 的画布渲染与 Gremlin 完全一致 ✓。

### 19.6 生成的 openCypher 实例（payment-graph 135 等，已对线上数据验证）

| 示例 | 查询 | 线上结果（`payment-graph`，模拟数据） |
|---|---|---|
| 135 本体 | `MATCH (a:Account {id:'135'}) RETURN a.id, a.accountType, a.accountStatus, a.firstName, a.lastName` | `135 \| savings \| ACTIVE \| Linus \| Hopper` |
| 出账（一跳） | `MATCH (a:Account {id:'135'})-[p:Payment]->(b:Account) RETURN b.id AS receiver, p.status ORDER BY receiver LIMIT 10` | 50 行（1000, 1001, … 状态 SUCCESS） |
| 按状态聚合 | `… RETURN p.status AS status, count(*) AS n ORDER BY n DESC` | `SUCCESS \| 50` |
| 双向关系 | `MATCH (a:Account {id:'135'})-[p:Payment]-(b:Account) RETURN b.id, p.status LIMIT 10` | 50 行 |
| 两跳 | `MATCH (a:Account {id:'135'})-[:Payment]->(:Account)-[:Payment]->(c:Account) RETURN DISTINCT c.id LIMIT 10` | 50 个不同的两跳点 |
| 变长 1..3 | `… -[:Payment*1..3]->(c:Account) …` | 一跳∪两跳∪三跳 |
| 过滤 | `… WHERE b.accountStatus = 'ACTIVE' AND p.status = 'SUCCESS' RETURN b.id, b.accountType` | 全部 ACTIVE |
| 参数（`$id` / `${id}`） | `MATCH (a:Account {id: $id})-[p:Payment]->(b:Account) RETURN b.id, p.status LIMIT 5` | 3 行，参数 `{"id":"135"}` |
| join 两段路径 | `MATCH (a:Account {id:'1000'})-[:AccountToIP]->(ip:IP), (ip)-[:IPToAccount]->(b:Account) RETURN ip.id AS ip, b.id AS account` | `5000 \| 1000` |
| 跨 schema | `MATCH (a:Account {id:'1000'})-[:AccountToPhone]->(ph:Phone) RETURN ph.cnCode, ph.phoneNumber` | `+86 \| 138-6000` |
| KG：按 shard 聚合 | `MATCH (e:Entity {id:'concept_book_new_9'})-[r:Entity_Entity]->(x:Entity) RETURN x.shard AS shard, count(*) AS n ORDER BY n DESC` | Entity 的 `shard` 分组 |
| HNSW：属性过滤邻居 | `MATCH (v:Vector {id:'4242'})-[r:vector_0]->(w:Vector) WHERE w.bucket = 3 RETURN w.id, w.name LIMIT 20` | 层 0 邻居中 bucket=3 的 |
| HNSW：按 bucket 聚合 | `… RETURN w.bucket AS bucket, count(*) AS n ORDER BY n DESC` | bucket 分组计数 |

**与 Gremlin 的交叉验证（线上同一份数据）**：`out`/`both` 一跳邻居集合、两跳集合、`count(*)` 全部一致 ✓（脚本一次跑完 11 个示例 + 3 个交叉校验 + 4 个错误分支）。

### 19.7 测试

| 测试 | 覆盖 |
|---|---|
| `CypherParserTest`（9） | 语法/AST、方向与多类型、变长、多模式、WHERE 各种算子、聚合与 ORDER BY、位置化报错、拒绝写子句、注释与分号 |
| `CypherGraphQueryTest`（13） | 锚点（内联 id 与 WHERE id）、一跳（出/入/无向/无类型）、两跳与变长、WHERE（数值/字符串/IN/NULL/NOT/OR）、分组聚合（count/sum/avg/min/max/collect）、DISTINCT/SKIP/LIMIT、`RETURN p` 与 `RETURN *`、多模式 join、参数、子图、错误分支 |
| `QueryControllerTest`（8） | `language` 绑定与缺省、Cypher 响应形状（columns/rows/subgraph）、Cypher 错误位置、未知语言 400 |
| `CypherQueryIntegrationTest`（1，Aerospike） | 同一份有向图上 **Cypher 与 Gremlin 结果逐项相等**（出/入/无向一跳、两跳、变长、跨 schema、count、聚合分组）、参数与数字 id 兜底、子图点数边数、无锚点报错 |

### 19.8 目前不支持（明确列出，避免误解）

写操作（`CREATE/MERGE/SET/DELETE`）、`WITH`/`UNWIND`/`OPTIONAL MATCH`/`UNION`/`CALL`、无锚点的全表 `MATCH (n:Label)`、聚合嵌套（`count(*)+1`）、变长关系命名（`-[r*1..2]->`）、`shortestPath`/`APOC`、多跳 `*` 无上界（上限 6 跳）。向量检索目前只有 Gremlin 的 `nearest(k)`；Cypher 侧可以用层边（`-[r:vector_0]->`）做邻居遍历（见 19.6）。

---

## 20. UI：示例查询收进按钮 + 查询结果值浮窗

第 19 节把 Cypher 接进 UI 之后补的两点体验改进。

### 20.1 示例查询默认隐藏（小按钮启用）

示例下拉不再占一整行：默认 `hidden`，查询框右侧多了一个 **小图标按钮**（`#examplesBtn`，书本图标，30×30，`aria-expanded` 同步）——
- 点击展开示例下拉并聚焦，再点（或选中某个示例、切换语言/图实例）就收起 ✓；
- 选中的示例直接填进查询框，`autoGrowGremlin()` 后光标落到文本框 ✓；
- 不占布局、不挤压查询框，整体 UI 与之前完全一样 ✓（`.icon-btn` / `.icon-btn.active` 一套极简样式）。

### 20.2 最终计算值浮窗（`includeResult`）

**问题**：UI 一直用 `returnSubgraph: true`，所以 `g.V('1000','Account').out('AccountToPhone').count()` 只画出了子图，"2" 这个值根本拿不到 ✗。

**后端**：`QueryRequest` 增加 `includeResult`（可空，缺省 false），`QueryService` 多一个入口：

| returnSubgraph | includeResult | 响应 |
|---|---|---|
| false | — | 直接是答案（`[2]` / Cypher 的 columns+rows） |
| true | false | 子图本身 `{vertices, edges}`（**与以前完全一致，向后兼容**） |
| true | true | `{ "result": [...], "subgraph": {...} }`（`QueryResponse`） |

- **一次执行同时产出两者**：`GremlinGraphQuery.executeWithSubgraph(query)` → `Result(values, subgraph)`（`executeSubgraph` 现在委托给它 ✓），所以"顺便要值"不额外跑一遍图 ✓；
- Cypher 的响应本来就带 `columns/rows/subgraph` ✓ 且现在也尊重 `returnSubgraph`（之前无论传什么都带子图）；
- 注意 `Result.values` 用 `Collections.unmodifiableList(new ArrayList<>(…))` 因为 `values('missing')` 会合法地产生 `null`（`List.copyOf` 会 NPE ✗）。

**前端**：
- `runQuery` 现在总是带 `includeResult: true` ✓；
- 响应归一化：`responseSubgraph(result)` / `responseValue(result)`（兼容三种形状：Gremlin 子图、`{result, subgraph}`、Cypher `{columns, rows, subgraph}`）✓；
- **浮窗** `#valuePanel`（画布左下，`z-index` 高于画布、低于弹窗；可关闭、可一键复制、新查询/重置时自动清除），内容由 `summarizeValue` 决定：

| 结果形状 | 浮窗显示 |
|---|---|
| 单个标量（`count()` → `[2]`） | **大号数字** `2` |
| 短列表（≤6） | 值/id 列表（小号） |
| 长列表（如 400 个邻居点） | **数量**（大号）+ 前 6 个预览 |
| Cypher 单行单列 | 大号标量 + "1 行 · phones" |
| Cypher 多行 | 首行值 + "N 行 · 列名"（完整表格在「结果」tab） |
| 空结果 | `0 项` / `0 行` |

`node check-value-ui.cjs`（临时脚本，已删）用**真实 app.js 里抽出来的函数**跑过 7 种形状，输出与上表一致 ✓。

### 20.3 验证

```
# 后端（8081）
g.V('1000','Account').out('AccountToPhone').count() + returnSubgraph + includeResult
  → {"result":[2], "subgraph":3 vertices / 2 edges}
  不带 includeResult → {"vertices":…, "edges":…}（旧形状不变 ✓）
  不带 returnSubgraph → [2] ✓
Cypher + returnSubgraph → {"columns":[…],"rows":[{"phones":2}],"subgraph":3 vertices} ✓

# 前端（8080）
/index.html：examplesBtn ✓、exampleSelect 默认 hidden ✓、valuePanel 默认 hidden ✓
/css/style.css：.icon-btn / .value-panel ✓
/js/app.js：includeResult: true ✓、showValue/summarizeValue/toggleExamples/responseSubgraph/responseValue ✓
```

测试：`GremlinGraphQueryTest`（`executeWithSubgraphReturnsTheAnswerAndTheVisitedGraph`：一次执行同时给出 `count()` 的值与走过的子图 + 新增 null 容忍）、
`QueryControllerTest`（`includeResultReturnsTheAnswerNextToTheSubgraph`：`$.result[0]` 与 `$.subgraph`；`withoutIncludeResultTheSubgraphResponseIsUnchanged`：旧形状不变），全绿 ✓。

### 20.4 第二轮：示例框排版（1/3 宽居中）+ 浮窗显示真正的查询值

**问题 1（后端 bug）**：投影计划 `neededProperties()` 在遇到 `limit` 时**停止扫描**，把 `limit` 当成了终结步骤 ✗。
而 `limit(n)` 只是流式截断、元素原样通过 ✓，所以 `...out('AccountToPhone').limit(1).values('cnCode')` 拿不到属性（返回 `[null]`），
而**不带** `limit` 的同一条查询却正常 ✓ —— 于是用户的

```
g.V('135','Account').both('Payment').both('Payment').limit(1).values('accountStatus')
```

返回 `[null]` ✗（应为 `["ACTIVE"]`）。

- 修复：`neededProperties` 只在 `count()` 处停止扫描（它确实消耗掉元素），`limit()` 继续往后找真正的属性消费者 ✓；
- 副作用是**语义一致**了：`both('Payment').limit(3).values('status')` 与不带 `limit` 一样会报 `Unknown property 'status' in vertex schema 'Account'`（`status` 是 Payment 边的属性，应按 `outE/bothE` 取）✓；
- 由此也解释了一个旧现象：`bothE(...).limit(1).values('status')` 之前"能工作"是因为顶点投影 `null`（跳过读）而**边**投影 `null` 被 `edgeReadProperties` 兜底成"读全部边属性" ✓，纯属巧合。

**问题 2（浮窗显示的不是值）**：长列表（如 350 个 `ACTIVE`）原先在大字位置显示 **数量 350**，看起来像"点的数目" ✗，
用户要的是 **Gremlin 计算出的值** ✓。

新渲染规则（`summarizeValues`）：

| 结果 | 浮窗 |
|---|---|
| `count()` → `[2]` | **2**（大号） |
| 单值 `['ACTIVE']` | **ACTIVE**（大号） |
| **N 个相同值** `['ACTIVE' × 350]` | **ACTIVE**（大号）+ 明细 `350 项` |
| 少量不同值 `['+86','+86']` | `+86`（大号）+ `2 项` |
| ≤8 种不同值 | 值列表 `a · b · c` + `N 项 · M 个不同值` |
| >8 种不同值 | 前 8 个 + `…` + `N 项 · M 个不同值` |
| 顶点/边元素 | 显示其 **id** |
| Cypher 单行 | 首行的值（大号）+ `N 行 · 列名` |

即：**永远优先显示值本身**，数量只作为次要明细 ✓。

**问题 3（示例框排版）**：示例下拉改为
- 查询框独占一行（`.gremlin-row { flex-direction: column }`，`textarea { flex: none }`），
- 示例下拉在查询框**下方**、宽度 = 查询框的 **1/3**（`width: calc(100% / 3); align-self: center`）。

用 **headless Edge + DevTools 协议**实测真实布局（点击 `#modeToggle` → 点击 `#examplesBtn` → 量 `getBoundingClientRect()`）：

```
picker revealed by the button: true      # hidden → 可见 + aria-expanded=true
textareaWidth 1404, pickerWidth 468      # 468 / 1404 = 0.333 → 正好 1/3
pickerBelowBox true, centerOffset 0      # 在查询框正下方且完全居中
```

### 20.5 测试与验证

- `GremlinGraphQueryTest.limitKeepsThePropertiesProjectedForTheFollowingSteps`：`out('knows').limit(1).values('name')` → `["bob"]`、
  `...limit(1).elementMap()` → 全属性、并 `verify` 投影里确实带了 `name` ✓（48 项全绿）；
- 线上（8081）实测：

```
G.both('Payment').both('Payment').limit(1).values('accountStatus')  → ["ACTIVE"]   浮窗：ACTIVE
G.both('Payment').both('Payment').values('accountStatus')           → 350 项       浮窗：ACTIVE + "350 项"
G.both('Payment').both('Payment').limit(1)                          → 顶点         浮窗：135
G.out('AccountToPhone').count()                                     → [2]          浮窗：2
G.out('AccountToPhone').values('cnCode')                            → ["+86","+86"] 浮窗：+86 + "2 项"
```

（浮窗文案由 app.js 中真实的 `summarizeValue` 抽取执行得到，非手写预期。）

---

## 21. RocksDB 存储：`RocksDB-Inline` 与 `RocksDB-Edge`

在 Schema 里声明存储实现，新增三个模块（两个实现 + 一个共享底座）：

```
graph-storage-rocksdb         共享底座：键布局/哈希分区/列族/属性编解码/顶点表/条带锁/LRU
graph-storage-rocksdb-inline  RocksDB-Inline：边内联进「边类型的表」
graph-storage-rocksdb-edge    RocksDB-Edge：边独立成表，按 (方向, 顶点, 时间) 前缀扫描
```

依赖：`org.rocksdb:rocksdbjni:9.11.2`（本机 Windows/JDK 26 原生库加载已实测 ✓，`RocksDB.rocksdbVersion()` = 9.11.2）。

### 21.1 Schema 里选择存储实现

```json
"storage": { "type": "RocksDB-Inline", "path": "payment-graph-rocksdb-inline",
             "regionCount": 512, "maxVersions": 1, "cacheEdges": true }
```

| 字段 | 含义 |
|---|---|
| `type` | `Aerospike`（**默认**，不写 storage 块就是这个） / `RocksDB-Inline` / `RocksDB-Edge`；大小写不敏感，`-`/`_`/空格都当分隔符（`rocksdb_inline` ✓） |
| `path` | RocksDB 目录（RocksDB 两种方案必填；可用部署侧的 `rocksdb-root` 作为相对路径基准） |
| `regionCount` | 键空间划分的区域数，默认 **512**（区域 = `floorMod(hash(key), regionCount)`，由定长哈希前缀决定） |
| `maxVersions` | 每个 property 保留的版本数，默认 **1**（即只留当前版本，`lastUpdatedTime` 作为版本号） |
| `cacheEdges` | 是否缓存已解码的边列表，默认 true |

- `graph-schema` 新增 `GraphStorageType`、`GraphStorageSchema`、`GraphSchema.storage(...)`、DTO `StorageSchemaDto`；
- **向后兼容**：Aerospike 是默认，且默认时不写 `storage` 字段 → 旧的 schema JSON 与 JSON round-trip 完全不变 ✓；
- 校验：RocksDB 缺 `path` → error；`regionCount`/`maxVersions` ≤ 0 → error；**RocksDB-Edge 声明了度数上限 → warning**（该方案没有度数上限，声明会被忽略）。

### 21.2 键与值布局

所有键都以**定长 8 字节哈希**开头（FNV-1a 64，big-endian），这既是散列分区，也是「同一区域的键物理相邻」的来源：

```
顶点   hash(vertexId) : vertexId
边     hash(vertexId) : direction : vertexId : createdTime : edgeId
```

边键里**重复了 vertexId**：它必须落在前缀里，这样「某顶点某方向的边」才是**一次范围扫描**；`createdTime` 用**定长 8 字节 big-endian**（保持字节序 = 时间序），于是时间窗是 `seek` 而不是过滤；`edgeId` 收尾保证唯一。

方向编码两套各自遵守自己的磁盘契约：
- **RocksDB-Edge**（用户规格）：`0` 无向、`1` 出边、`2` 入边；
- **RocksDB-Inline**（沿用 Aerospike bin 命名）：`ve_<edgeSchemaId>_<dir>`，其中 `0` 无向、`1` IN、`2` OUT —— 这样同一份 schema 在两种后端上看到同样的「列名」✓。

顶点记录（每个点类型一张表 = 一个 column family）：

```
idType : createdTime : lastUpdatedTime : propertyCount
每个 property: propertyId : versionCount : [ version : length : value ] ...
```

- `version` 就是该 property 的 `lastUpdatedTime`；**只有更新的 version 才能覆盖**（乱序写入无法回退新值），和 Aerospike 的 per-bin CAS 语义一致；
- 默认只留 1 个版本；`maxVersions > 1` 时旧值作为历史保留，可用 `versions(vertex, property)` 读回（测试覆盖 3 版本 ✓）；
- `idType`（0 string / 1 long / 2 int）保证 **id 类型往返**：`135` 回来还是数字 `135`，`account-42` 还是字符串。

### 21.3 RocksDB-Inline

- 点：每类型一张表（同 21.2）；
- 边：**每种边一张表**（column family = `edge-inline:<EdgeName>`），表内**每个顶点一条记录**，记录里按 **Aerospike bin 名**（`ve_<schemaId>_<dir>`）存该顶点该类型该方向的**全部边**（`edgeId → {otherVertexId, createdTime, lastUpdatedTime, properties[...]}`）；
- 写入/更新：读该顶点的记录 → 按 per-property `lastUpdatedTime` 合并 → **按 schema 的 `maxOutDegree`/`maxInDegree`/`maxDegree` 淘汰最旧的边** → 写回（`KeyLocks` 条带锁保护读-改-写，RocksDB 没有 CAS）；
- 淘汰是**按端、按方向**的（与 Aerospike 实现一致）：源端 OUT bin 淘汰后，目的端 IN bin 里的副本只有它自己的 `maxInDegree` 才会淘汰它（测试里明确记录了这个语义）；
- 一条有向边写两次：source 的 OUT bin + destination 的 IN bin → `out()`/`in()` 各自一次读；无向边写两端同一个 `_0` bin。

### 21.4 RocksDB-Edge

- 点：同一套顶点表（与 Inline 完全相同的布局，可以互相读）；
- 边：**每种边一张独立的边表**（`edge:<EdgeName>`），一条有向边两行（source 的 `direction=1` 行 / destination 的 `direction=2` 行），无向边两端各一行 `direction=0`；
- **没有度数上限**（`maxInDegree`/`maxOutDegree` 被忽略）：1 万个出边的 hub 实测写入/读取正常 ✓；
- **时间窗**是这一层的原生能力：`readEdgesWithin(vertex, edge, side, RocksDbTimeRange.of(from,to), props)` → 扫描范围 `[prefix+from, prefix+to+1)`，上限/下限都可开区间（`ALL`）；
- `both()` = **两个迭代器**（OUT 一个、IN 一个），合并后按 `lastUpdatedTime` 倒序（与 Inline/Aerospike 一致）；
- 边缓存：`(表|顶点|方向|窗口|投影)` 为键的 LRU（4096 条），写/删按 `表|顶点|` 前缀失效 —— 投影必须进键，否则「只读了 status 的缓存」会去回答「同时要 amount 的读」（**这是实现中真实踩到并修掉的 bug** ✓ 有单测/集成测试覆盖）。

### 21.5 复用的东西（要求 2.5 / 3.4）

| 能力 | 复用方式 |
|---|---|
| 批查询 | `readVertices` → `multiGet`；Inline 的 `readEdgesBatch` 一次 `multiGet` 覆盖全部顶点；Edge 的批量是按顶点做有序前缀扫描（本地迭代器，无网络往返） |
| 并行查询 | 完全走 Gremlin 引擎既有的并行 seed 读（`GremlinContext` 的 `ForkJoinPool` 批读）✓ |
| 按需 properties | `SchemaProjections`（新，放在 `graph-query` 共用）把名字解析成 property id，解码时**跳过未请求的属性** |
| 缓存 | 引擎侧点/边缓存照旧 ✓；存储侧再加一层已解码边列表 LRU（Inline 缓存「记录里的 bins」，Edge 缓存「投影后的列表」） |
| 查询接口统一 | 两个方案都实现 `GraphQuery` → **Gremlin 与 Cypher 引擎零改动**即可查询 ✓（`Subgraph`/`NeighborVertexResolver` 早已下沉 `graph-query`） |

### 21.6 HTTP 侧接线

- `GraphBinding` 增加 `graphQuery`（第 6 个分量，前 5 个保持不变 → 无二义性），Aerospike 绑定自动构造 `AerospikeGraphQuery`，RocksDB 绑定用 `GraphBinding.of(schema, query)`；
- `StorageConfig` 按 `schema.storage()` 分流：Aerospike → 原来的 stores；RocksDB → `RocksDbGraphStores.open(schema)`（新类，负责打开/持有/关闭目录，支持 `rocksdb-root` 解析相对路径）；
- `QueryService` 两个引擎都改成 `binding.graphQuery()`；HNSW 分支加 `hasAerospikeStores()` 保护；`VectorIndexManager` 对非 Aerospike 图直接返回空索引（`rebuild` 抛明确错误）；
- `/api/v1/graphs` 的 `GraphInfo` 增加 **`storage`** 字段，UI 的图下拉显示 `payment-graph-rocksdb-inline · RocksDB-Inline` ✓；
- 写接口（`POST /api/v1/vertices|edges|batch`）仍然是 Aerospike 主图的能力（RocksDB 实例的数据由 loader 写入）。

### 21.7 数据装载（payment-graph → 两个 RocksDB 实例）

`graph-integration-test` 的 `PaymentGraphRocksDbLoader`：与 Aerospike 演示集同构的 **151 点 / 400 边**
（hub `135` 支付 50 个账户；50 个账户按 `+1/+7/+13` 成环互付 150 条；每个账户 2 个 IP + 2 个 Phone，并含反向边），
同一份生成器喂给两种方案，所以两边可以**逐条查询对比**：

```
./mvnw -q -pl graph-integration-test -am test -Dtest=RocksDbLiveLoadTest \
       -Drocksdb.live.dir=D:/workspace/eclipse/PhoenixGraph/.rocksdb -Dsurefire.failIfNoSpecifiedTests=false
# loaded payment-graph-rocksdb-inline (RocksDB-Inline) at ...\.rocksdb\payment-graph-rocksdb-inline: 151 vertices, 400 edges
# loaded payment-graph-rocksdb-edge    (RocksDB-Edge)   at ...\.rocksdb\payment-graph-rocksdb-edge:   151 vertices, 400 edges
```

启动服务（默认 `application.yml` 已把这两个图挂进同一个实例 ✓，`rocksdb-root: .rocksdb`）：

```
java -jar graph-http/target/graph-http-*.jar        # 8081：Aerospike + RocksDB 两实例共 6 个图
java -jar graph-web/target/graph-web-*.jar          # 8080：UI
# 只跑 RocksDB：--spring.profiles.active=rocksdb
```

### 21.8 线上实测（8081 / 8080，UI 与 API 同一入口）

两个实例逐条对比，**答案完全一致**：

| 查询 | inline | edge |
|---|---|---|
| `g.V('135','Account').out('Payment').count()` | `[50]` | `[50]` |
| `g.V('135','Account').both('Payment').both('Payment').limit(1).values('accountStatus')` | `["FROZEN"]` | `["FROZEN"]` |
| `g.V('1000','Account').out('AccountToPhone').values('cnCode')` | `["+86"]` | `["+86"]` |
| `g.V('135','Account').out('Payment').out('AccountToIP').values('ipAddress')` | 50 个 IP | 50 个 IP |
| `g.V('1000','Account').outE('Payment').values('amount')` | `[18,12,6]` | `[18,12,6]` |
| `...both('Payment')`（子图） | 51 点/50 边 | 51 点/50 边 |
| Cypher `MATCH (a:Account {id:'135'})-[p:Payment]->(b:Account) RETURN p.status, count(*) ORDER BY n DESC` | `PENDING 17 / SUCCESS 17 / FAILED 16` | 同 |
| Cypher `MATCH (a:Account {id:$id}) RETURN a.id, a.firstName, a.lastName` | `135 / Ada / Lovelace` | 同 |
| Cypher 两跳 `Account→IP→Account` | `10.0.0.0 / 1000` | 同 |

`/api/v1/graphs` 实测：

```
payment-graph                  Aerospike
payment-graph-rocksdb-inline   RocksDB-Inline
payment-graph-rocksdb-edge     RocksDB-Edge
kg-entity-graph / hnsw-sift / hnsw-dynamic   Aerospike
```

### 21.9 测试

| 模块/测试 | 覆盖 |
|---|---|
| `graph-storage-rocksdb` `RocksDbVertexTableTest`（13） | 键布局（定长哈希 + id）、512 区域分布、时间序键、属性编解码（string/long/double/bool/list/map）、按需投影、**版本规则（新覆盖旧、旧不回退、部分更新保留其他属性、`maxVersions=3` 历史）**、批量多读、删除、扫描、id 类型往返 |
| `graph-storage-rocksdb-inline` `RocksDbInlineStoreTest`（15） | bin 名与 Aerospike 一致（`ve_1_2` = OUT）、两向可读、BOTH 合并排序、**maxOut/maxIn 淘汰最旧（按端独立）**、无上限、无向边单 bin、旧写不回退、属性投影、删除两侧、批量=单读、缓存失效 |
| `graph-storage-rocksdb-edge` `RocksDbEdgeStoreTest`（13） | 每条边两行 + 方向、BOTH 两迭代器、**无度数上限（1 万边 hub）**、**时间窗 seek（含开区间/空窗）**、前缀范围隔离、旧写不回退、删除两行、缓存失效（含投影键）、region 覆盖 |
| `graph-integration-test` `RocksDbStorageIntegrationTest`（9） | 同一份数据装进两方案 → **9 条 Gremlin + 5 条 Cypher 查询逐条比对相等**、子图相等、存储层事实（bin 大小/行数/时间窗/批量=单读） |
| `graph-schema` `GraphStorageSchemaTest`（9） | 默认 Aerospike、类型名宽松解析、JSON round-trip（含默认值填充）、缺 path 报错、校验 error/warning |
| `RocksDbLiveLoadTest`（1，需 `-Drocksdb.live.dir`） | 真正落盘装载 + 重新打开 + 跑 Gremlin/Cypher，服务前自检 |

全量 `./mvnw test`：**19 个模块 BUILD SUCCESS**。

---

## 22. GEO 地理位置查询：`isGeo` 声明 + Google S2 索引 + Gremlin `geoNear`

目标：给定经纬度，查出半径内的点（例如「最近的商户」），并返回每个点的距离；Gremlin 里用一条语句搞定，
半径是 step 参数；Aerospike 与两种 RocksDB 实现都支持（索引落在 RocksDB 上）。

### 22.1 Schema：`isGeo` 声明要做 geo index 的 property

```json
{ "id": 5, "name": "location", "type": { "map": "DOUBLE" },
  "isGeo": true, "geoLevel": 16, "geoMaxCells": 32 }
```

| 字段 | 含义 |
|---|---|
| `isGeo` | 该 property 存的是经纬度点 `{lat, lon}`，需要建 geo index（默认 false） |
| `geoLevel` | S2 cell 层级，默认 **16**（约 200m 一格）；越细 → 一格点越少、查询扫的格越多 |
| `geoMaxCells` | 一次查询最多覆盖多少个 cell，默认 **32**（防退化成全表扫描） |

- `GraphSchema`/`PropertySchema` 增加 `isGeo()/geoConfig()/geo(...)`（与 `isVector` 完全对称），
  新增 `GeoIndexConfig`(level, maxCells)、`GeoIndexSchemas`（按点类型列出 geo property、给出 property id）；
- DTO 增加 `isGeo/geoLevel/geoMaxCells`（省略即默认，旧 JSON 不受影响 ✓），JSON round-trip ✓；
- 允许的存储形状（读取时宽松解析，任一即可）：`{"lat":..,"lon":..}`（`latitude/longitude/lng` 也认）、
  GeoJSON 二元数组 `[lon, lat]`、字符串 `"31.2304,121.4737"` ✓。

### 22.2 查询类型（`graph-schema` 的 `schema.geo` 包）

- `GeoPoint(lat, lon)`：范围校验 + **haversine 大圆距离** `distanceKm(other)` + 宽松 `from(Object)`/`parse(String)`；
- `GeoSearch(center, radiusKm, level, maxCells, maxResults)`：`GeoSearch.parse(json, radiusOverride)` 解析 step 的 JSON ✓；
- `GeoHit(vertexName, vertexId, point, distanceKm)`：按距离排序（同距离按 id ✓）；
- `GraphQuery` 新增端口 `geoNear(vertexName, propertyName, GeoSearch)` + `supportsGeoSearch()`（默认抛「不支持」✓）；
- `SchemaProjections` 把 `distanceKm`（`GeoIndexSchemas.DISTANCE_PROPERTY`）当作**计算属性**：不当作 bin 去读，
  但结果元素上带着它 → `values('distanceKm')`、`elementMap()` 都能用 ✓。

### 22.3 RocksDB 的 S2 索引（两种方案共用）

索引表：**每个 geo property 一张表**（column family `geo:<VertexType>:<propertyName>`），
键 = `S2 cellId(8字节, big-endian) : vertexId`，值 = `lat(8B) : lon(8B)`（点也放索引里，
距离只用索引算，命中的点才回点表读 ✓）。

- 写入时**自动维护**：`RocksDbVertexTable` 在写/删顶点的同一个 `WriteBatch` 里加/删索引行
  （点搬家 → 删旧 cell 行、加新 cell 行；删点 → 删索引行 ✓ 有测试覆盖）；
- 查询：`S2Cap` 覆盖圆 → `S2RegionCoverer` 取 cover cell → 每个 cell 扫
  `[cell.rangeMin():, end(cell.rangeMax():))` 区间（**后代 cell 的键都落在祖先的 range 内** ✓）→
  逐点算 haversine → 过滤半径 → 按距离排序 → 取 limit ✓；
- cover 层级 = `min(索引层级, levelForRadius(半径))`（**永不比索引更细**，否则会漏掉更深的点 ✓ 有测试）；
  1km 半径 → level 14（约 1.6km 的格，覆盖 ~9–16 格 ✓）；
- 命中上限 `MAX_CANDIDATES=200000` 兜底 ✓。

Gremlin step：

```
g.V('1','Merchant').geoNear('{"lat":31.2304,"lon":121.4737,"radiusKm":1}').limit(10)
g.V('1','Merchant').geoNear('{"lat":31.2304,"lon":121.4737}', 0.5)        # 半径用 step 参数
g.V('1','Merchant').geoNear('{"lat":31.2304,"lon":121.4737}', 0.5).has('category','coffee').values('name','distanceKm')
```

- 语义与 `nearest` 一致：在**种子点类型**的 geo 索引上搜索，替换当前 traverser ✓；
- 结果带 `distanceKm` 属性 ✓（`elementMap()` 也能看到 ✓）；
- step 参数：`geoNear(pointJson[, radiusKm])`；半径缺省从 JSON 的 `radiusKm/radius/distance` 取 ✓；
- 顺带修了 Gremlin 解析器：JSON 参数里的 `{`/`}`/`(`/`)`/`,`/`.` 不再被当作语法
  （`{` 曾是「逻辑块」的分界 → `geoNear('{...}')` 直接解析失败 ✗）；现在括号匹配、逻辑块定位都跳过引号内内容 ✓。

### 22.4 Aerospike 侧

- 启动时对每个 `isGeo` property 的 bin 尝试创建 **geo2dsphere secondary index**
  （`AerospikeGeoIndex.ensureIndexes`，已存在/社区版不支持都只记日志 ✓ 不算错误 ✓）；
- 查询优先走索引（`Filter.geoWithinRadius`），失败/无索引则**退化为扫描集合 + 逐条算距离**
  （`searchByScan`）→ 同一句 Gremlin 在社区版也能查 ✓；
- 说明：Aerospike 的 geo 索引要求 bin 存 GeoJSON 字符串，而我们的存法是 `{lat,lon}` map，
  所以实践中索引路径主要用于接入既有的 GeoJSON 数据；**持久化的 S2 索引是 RocksDB 两种方案的**（用户选型一致 ✓）。

### 22.5 demo 数据与实测

演示图 `geo-merchants-*`（`Merchant`：name/category/rating/address/**location(isGeo)**/openNow），
240 家店按**黄金角螺旋**铺在人民广场周围：第 i 家距离中心 `0.05 + 0.006·i` km（最远 ~1.5km）✓，
同一份数据写进 **两个 RocksDB 实例**（`geo-merchants-rocksdb-inline` / `geo-merchants-rocksdb-edge`）✓。

```
java ... GeoMerchantDataLoader /path/to/rocksdb-root
# loaded geo-merchants-rocksdb-inline (RocksDB-Inline) ...: 240 merchants, 240 geo index rows
# loaded geo-merchants-rocksdb-edge    (RocksDB-Edge)   ...: 240 merchants, 240 geo index rows
```

线上实测（8081 API / 8080 UI 代理，两个实例**逐条一致**）：

| demo 查询 | 结果 |
|---|---|
| `g.V('1','Merchant').geoNear('{...radiusKm:1}').limit(5).values('name')` | `Coffee #1, Bakery #2, Pharmacy #3, Supermarket #4, Restaurant #5` |
| 同上 `.values('distanceKm')` | `0.0500, 0.0560, 0.0619, …` km（**最近优先** ✓） |
| `…geoNear('{…}', 0.5).has('category','coffee').values('name','distanceKm')` | 26 家咖啡店（500m 内）+ 各自距离 |
| `…geoNear('{...radiusKm:2}').count()` | `240`（全部） |
| 半径 1km / 500m / 300m / 100m 计数 | `159 / 76 / 42 / 9` |
| `…geoNear('{...}').limit(1).elementMap()` | `{id:1, label:Merchant, name:Coffee #1, category:coffee, location:{lat:31.23084968,lon:121.4737}, distanceKm:0.0500}` |

UI：`/api/v1/graphs` 现在带 `geoProperties`（`{"Merchant":["location"]}` ✓），
下拉里两个 geo 实例显示 `… · RocksDB-Inline/Edge` ✓，示例下拉内置 5 条 GEO 示例 ✓，
`geoNear` 的 159 个命中会一并画在画布上（159v ✓）。

初始化（服务启动前）：

```
./mvnw -q -pl graph-integration-test -am test -Dtest='RocksDbLiveLoadTest#loadTheGeoInstancesIntoTheConfiguredDirectory' \
       -Drocksdb.live.dir=D:/workspace/eclipse/PhoenixGraph/.rocksdb -Dsurefire.failIfNoSpecifiedTests=false
java -jar graph-http/target/graph-http-*.jar          # 8081：8 个图（含 2 个 geo 实例）
java -jar graph-web/target/graph-web-*.jar            # 8080：UI
```

### 22.6 测试

| 测试 | 覆盖 |
|---|---|
| `graph-schema` `GeoPropertyTest`（8） | `isGeo`/配置、校验、`GeoIndexSchemas` 查询、JSON round-trip（含默认值）、点解析的 7 种形状、haversine 距离、`GeoSearch.parse` 与错误、`GeoHit` 排序 |
| `graph-storage-rocksdb` `RocksDbGeoIndexTest`（13） | S2 层级/嵌套/range、cover 层级与 cell 预算、**半径过滤与距离（0.5km 进、1.5km 出、5km 出）**、结果上限、**搬家重建索引**、删点删索引、旧版本写入不改索引、非点值不入索引、索引键/值编解码 |
| `graph-query-gremlin` `GremlinGraphQueryTest`（+3） | `geoNear` 两种参数形式、距离属性与 `elementMap`、缺索引/缺半径/坏点的报错 |
| `graph-integration-test` `GeoQueryIntegrationTest`（6） | 240 家店 → 半径 0.2/0.3/0.5/1/1.5/2km 计数、最近 5 家名称与距离、`has` 过滤、`elementMap`、**两个 RocksDB 方案逐条结果相等**、索引行数/cell id |
| `RocksDbLiveLoadTest`（+1） | 真实落盘装载 geo 实例 + 重新打开执行 demo 查询 |

全量 `./mvnw test`：**19 个模块 BUILD SUCCESS**（含全部新测试）。

---

## 23. 四项修正：示例框同列、RocksDB 子图缺边、`circle` 改名 + 米制 + limit 下推、`isNull` target

### 23.1 示例框与查询框同列

`.example-select` 从 `align-self: center` 改为 `align-self: flex-start`：宽度仍是查询框的 **1/3**
（用户上一轮的要求 ✓），但**左边缘与查询框对齐**、直接位于其下方 —— 即两者在**同一列**里 ✓。

headless Edge 实测（8080）：

```
textareaLeft 33, textareaWidth 1404
pickerLeft   33, pickerWidth   468      -> sameLeftEdge true, widthRatio 0.333, below true
```

### 23.2 RocksDB 实例上「只有点没有边」的根因与修复

`g.V('1000','Account').out('AccountToPhone')` 在 Aerospike 上正常画边，在两个 RocksDB 实例上只出现两个点 ✗。

根因：**同一张子图里，点的 id 与边的端点 id 类型不一致**：

| 来源 | id 形态 |
|---|---|
| 子图顶点 | 从存储回读 → **原生类型**（RocksDB 的 `idType` 字节还原成数字 `1000`） |
| 子图边的端点 | 遍历器上记录的 id → 查询里写的是**字符串**（种子点 `g.V('1000',...)` 未读记录时直接沿用请求 id） |

UI 用 `label::id` 做 join，`1000 !== "1000"` → 边被丢掉 ✓。Aerospike 恰好两边都是字符串所以看不出来 ✗。

修复：`Subgraph.Vertex/Edge` 的 id **统一成文本**（构造器里 `String.valueOf`），并且在
`GremlinContext.recordVertex/recordEdge`、顶点端点表、`CypherGraphQuery` 的记录点也一起规范化 ——
这样「同一个点在两条路径下被记录两次」也不会变成两个节点 ✓。

线上实测（8081）：

```
payment-graph                3v 2e   edges whose endpoints are drawn: 2/2
payment-graph-rocksdb-inline 2v 1e   edges whose endpoints are drawn: 1/1
payment-graph-rocksdb-edge   2v 1e   edges whose endpoints are drawn: 1/1
   vertices "1000", "6000"    edges AccountToPhone:"1000"->"6000"
```

### 23.3 `geoNear` → `circle`，半径单位改为**米**，`limit` 下推进索引搜索

1). step 改名 **`circle`**，JSON 键改名 **`radius`**、单位 **米**：

```
g.V('1','Merchant').circle('{"lat":31.2304,"lon":121.4737,"radius":1000}').limit(10)
g.V('1','Merchant').circle('{"lat":31.2304,"lon":121.4737}', 500)        // 半径作为 step 参数（米）
```

连带统一：`GeoSearch.radiusMeters`、`GeoPoint.distanceMeters`（haversine，地球半径 6371008.8m）、
`GeoHit.distanceMeters`、结果附带属性 **`distanceMeters`**（`values('distanceMeters')` /
`elementMap()` 可用 ✓）、S2 `levelForRadius(meters)`、Aerospike `Filter.geoWithinRadius(..., meters)`。

2). **`limit` 下推**（性能问题的根因）：以前是「查出圆内全部点 → 读全部顶点 → 再取 limit」✗，
现在编译期把紧跟 `circle` 的 `limit(k)` 合并进 step 参数（`circle(pointJson, radius, k)` ✓），
搜索侧 `GeoSearch.maxResults=k`：

- `GeoHit.TopK`：扫描覆盖 cell 时用**大小为 k 的最大堆**只保留最近的 k 个（内存有界 ✓）；
- step 只对 k 个命中回读顶点、只把 k 个点收进子图 ✓（`isHop("circle")` 也一并登记，投影计划才正确 ✓）。

实测（两个 geo 实例一致）：

```
limit(10)  -> result 10, subgraph 10v      （修复前 subgraph = 圆内全部 159v ✗）
no limit   -> 159                           （语义不变 ✓）
nearest 3  -> Coffee #1 50.0m, Bakery #2 55.97m, Pharmacy #3 61.93m   （最近优先，米）
```

单测断言了「有 limit 只读 3 个顶点、无 limit 读全部 20 个」✓（`readVertices` 调用次数）。

### 23.4 Edge target 的 `isNull`：允许没有指向的边

Schema（只允许写在 **target** 上）：

```json
"target": { "id": 2, "name": "target", "type": "LONG", "vertex": "Merchant", "isNull": true }
```

语义：target 可以为 `null` 的边**只存出边**，没有点指向它：

| 层 | 行为 |
|---|---|
| 写入 | RocksDB-Inline 只写 source 的 `ve_<schema>_2`（OUT）；RocksDB-Edge 只写 source 的 `direction=1` 行；Aerospike 只写 source 的 bin ✓（`RocksDbEdgeUpsert` 在 schema 不允许时直接报错 ✓） |
| 删除 | 只删 source 那一侧 ✓ |
| `outE()/bothE()` | 正常返回该边，`otherVertexId = null` ✓ |
| `out()/both()` | **不产生遍历器**（没有下一个点 ✓） |
| 子图 | 边保留但 `target = null` ✓（修了 `buildSubgraph` 里 `List.of(source, target)` 遇到 null 抛 NPE 的 bug ✗） |
| 校验 | `isNull` 写在 source → **error** ✓；无向边声明 null target → warning ✓；同时声明入度上限 → warning ✓ |
| JSON | `isNull` 参与 DTO/codec round-trip ✓；`RocksDbIdCodec` 新增 `TYPE_NULL=3`，null 端点不再被写成字符串 `"null"` ✓ |

demo 数据里给两个 geo 实例各加了 `Opens` 边类（Merchant → Merchant，target `isNull`）与 3 条「未确定对手方」的商机 ✓，
线上实测：

```
outE('Opens') = 3 条，otherVertexId = null，带 since/title 属性
out('Opens')  = []        both('Opens') = []
subgraph edges: deal_1:"1"->null, deal_2:"1"->null, deal_3:"1"->null
```

### 23.5 测试

新增/更新：`GremlinGraphQueryTest`（+2：nullable target 的 hop/子图语义、limit 下推的读取次数）、
`GeoQueryIntegrationTest`（+2：limit 只收 k 个点的子图、nullable target 的端到端）、
`RocksDbInlineStoreTest`（+1：nullable target 只写出边）、`GeoPropertyTest`（+1：isNull 校验与 JSON round-trip）、
以及全部 geo 测试改名为 `circle`/米制 ✓。全量 `./mvnw test`：**19 个模块 BUILD SUCCESS**（0 失败）。

---

## 24. UI 三处调整：示例框回同一行右侧、浮窗只显示「非点非边」结果、结果 tab 仅 Cypher

### 24.1 示例框与查询框同一行、靠右、宽度不变

`.gremlin-row` 改回**一行**（`flex-direction: row`），查询框 `flex: 1 1 0`（吃掉剩余宽度），
示例框 `flex: 0 0 auto; width: calc(100% / 3); align-self: flex-start`（靠右、顶对齐 ✓）：

headless Edge 实测（8080，真实点击 `#examplesBtn` 后量 `getBoundingClientRect`）：

```
sameRow true, pickerOnTheRight true, pickerWidth 468, pickerIsOneThirdOfRow 0.333, rowFilled true
```

（468px = 之前竖直排布时的宽度 ✓ 保持不变 ✓。）

### 24.2 浮窗只在「非 Vertex / 非 Edge」结果时出现

`showValue()` 新增判定：结果是**图元素**（对象且含 `id` 且含 `label`/`type`）时**自动隐藏**浮窗
—— 点/边本身已经画在画布上 ✓；Cypher 投影如果每列取出来都是元素也同样隐藏 ✓。
数字/字符串/列表/聚合/`elementMap()` 之外的标量投影照旧显示 ✓。
顺带修正 Cypher 结果浮窗内容：以前多行时会把「整行 Map」当值渲染成 JSON ✗，现在统一取**首行的各列值** ✓。

真实 UI 驱动实测（点击 QUERY）：

| 查询 | 浮窗 | 结果 tab |
|---|---|---|
| `g.V('1000','Account').out('AccountToPhone')`（点列表） | 隐藏 ✓ | 隐藏（Gremlin）✓ |
| `g.V('135','Account').out('Payment').count()` | 显示 `50` ✓ | 隐藏 ✓ |
| `…outE('Payment').limit(3).values('amount')` | 显示 `59 · 58 · 57` ✓ | 隐藏 ✓ |
| Cypher 聚合（多行多列） | 显示 `PENDING · 17`（首行值）+ 明细「3 行 · status, n」✓ | 显示并激活 ✓ |
| 再切回 Gremlin 点列表 | 隐藏 ✓ | 隐藏、自动回到「统计」tab ✓ |

### 24.3 「结果」tab 只在 Cypher 且查询过之后出现

- 新增状态 `rowsReady`（只有 Cypher 响应带 `columns` 时才置真 ✓）；
- `refreshRowsTab()`：`rowsAvailable() = 当前语言是 cypher && rowsReady` 决定 `结果` tab 的
  `hidden` ✓（CSS 补 `.tab[hidden] { display: none; }` ✓）；不可用时若正停在该 tab 会自动切回「统计」✓；
- 触发点：`init()` 初始隐藏 ✓、语言切换 `onLanguageChange()` 隐藏 ✓、`clearRows()`（新查询/重置）隐藏 ✓、
  Cypher 查询返回后显示并激活 ✓。

实测：Gremlin 全程无「结果」tab ✓；Cypher 查询后出现且自动激活 ✓；切回 Gremlin 后消失并回到「统计」✓。

### 24.4 验证与提交

- 用 headless Edge + DevTools 协议**真实驱动页面**（点 `#modeToggle`/`#examplesBtn`/`#queryBtn`、
  切图与切语言）后读取 DOM 状态，得到上表结果 ✓；
- Java 侧本轮未改动（仅 `graph-web/frontend` + 打包静态副本 ✓），`graph-web` 模块测试复跑通过 ✓。

---

## 25. Aerospike-Edge：大点的聚合边表（Edge_Out / Edge_In，三级索引 + 离线批量导入）

### 25.1 动机与声明方式

原来的 Aerospike 方案把边内联在顶点记录里，受**出入度上限**和记录大小限制：一个每年收款 1 亿笔的商户根本放不下 ✗。
新增存储类型 **`Aerospike-Edge`**（仍是 Aerospike ✓，只是边另有归宿 ✓），schema 里声明即可：

```json
"storage": { "type": "Aerospike-Edge" }
```

（`Aerospike` / `RocksDB-Inline` / `RocksDB-Edge` 行为完全不变 ✓ —— 要求 1 ✓。缺陷修复：codec 以前把任何 Aerospike 变体都退化成默认值 ✗，现在 `Aerospike-Edge` 会正确往返 ✓ 有测试 ✓。）

### 25.2 表结构与键（要求 2、3、4、5）

每种边两张表（无向/双向只一张 ✓），表名 `<vertexSet>_edge_out_<edgeSchemaId>` / `_edge_in_<edgeSchemaId>` / `_edge_<edgeSchemaId>` ✓，
再加一张大点标记表 `<vertexSet>_edgeheads` ✓。

键：**`点id|最小createdTime|最大createdTime|层级`**（`0` 最低一级 ✓）；值（CDT）：

| 层级 | 内容 |
|---|---|
| 0 | **最多 2000 条边**：`edgeId → [lastUpdatedTime, createdTime, otherVertexId, {propId:[值,ct,ut]}]`（与内联 bin 完全同构 ✓，便于 merge ✓） |
| 1 | 指向下一级的**区间列表** `[[min,max], ...]`（≤2000 个 ✓）—— 子键可由区间**推出来**，值里不需要存键 ✓ |
| 2 | 同上，把第 1 级的键再索引一遍 ✓ |
| … | 每层折叠到只剩 1 个 root 为止（`build()` 循环 ✓；2000/层 → 4,000,001 条边才需要第 3 级 ✓ 有测试 ✓） |

每层的 `min/max` = 其所有子区间的最小/最大值 ✓，所以 root 的区间覆盖该点全部导入的边 ✓；
大点标记记录 `{v, l=层数, mn, mx, n=边数}` ✓（查询与"是否需要窗口"的依据 ✓，进程内缓存 1 分钟 ✓）。

### 25.3 离线批量导入（要求 3、7）

`AerospikeEdgeBulkImporter`：调用方给一个点的边（任意顺序 ✓）→ 按 `createdTime` 排序切 2000 一条 ✓ →
逐层折叠 ✓ → **生成所有 key/value 后在同一个 batch 里写**（每 500 条一批 ✓，绝不一条边一条边更新 ✓）→
写大点标记 ✓。有向边写两次（source 的 `Edge_Out` + destination 的 `Edge_In` ✓），无向边写两端同一张表 ✓。

实测（真实 Aerospike，`payment_as_edge_v1`）：

```
loaded payment-graph-as-edge into payment_as_edge_v1: 51 vertices, 300000 hub payments,
       2 levels, 351 chunk records, 5 inline updates (4143 ms)
```

（30 万条 → 150 个 level-0 chunk + 1 个 level-1 root（+50 个付款方的 OUT 表共 200 个 ✓）= 351 条记录 ✓，4 秒 ✓。）

### 25.4 查询：窗口 → 逐级下降 → 与内联合并（要求 6）

新增 Gremlin step **`createdBetween(from, to)`**（epoch 毫秒或 UTC 日期，`*` 表示开区间 ✓），
它给后续边读设定窗口（`GremlinContext.edgeTimeRange` ✓），端口层新增 `readEdgesWithin` / `readEdgesBatchWithin` /
`readAllEdgesBatchWithin`（默认实现 = 忽略窗口，原有后端零改动 ✓；RocksDB-Edge 也顺带接上了 ✓）。

大点查询流程：

1. 读大点标记 → **没有窗口就报错并给出提示** ✓（要求 6 ✓）：
   `Vertex '9000001' has 300000 edges of class Payment in the aggregated Edge_In table; ... add createdBetween('2025-01-01','2025-02-01')`
2. 从 root 开始按层下降：只进入**区间与窗口相交**的子块（`selectChildren` ✓，按时间排序 ✓）；
3. 到 level 0 后按 `createdTime ∈ [from,to]` 过滤（2000 条一条记录 ✓），结果按 createdTime 升序 ✓（`limit` 即取最早的那批 ✓）；
4. **与顶点记录里的内联边 merge**（按 `edgeId` 去重，`lastUpdatedTime` 新者胜 ✓）—— 老边被最新修改后存在点里的那份会覆盖聚合表里的旧版 ✓（要求 6 ✓）；
   内联边同样按窗口过滤 ✓。
5. 单次窗口读取上限 50 万条兜底 ✓；无窗口时非大点走原来的内联路径 ✓（行为不变 ✓）。

### 25.5 demo 与实测（要求 7）

`payment-graph-as-edge`（schema id 31 ✓，`vertex-set: payment_as_edge_v1` ✓）：
hub `9000001` 一年 **30 万笔**收款（50 个付款账户 ✓，每天约 822 笔 ✓），全部由批量导入写进聚合表 ✓；
另外把其中 5 笔**再用内联写路径更新**（`REFUNDED` ✓）用来演示 merge ✓。

**demo 查询**（窗口 1 天，取 500 条 ✓）：

```
g.V('9000001','Account').createdBetween('2025-06-01','2025-06-02').inE('Payment').limit(500)
```

线上实测（8081 / 8080）：

| 查询 | 结果 |
|---|---|
| 上面的 demo（1 天窗口 + limit 500） | **500 条** ✓（该窗口共 1644 条） |
| `createdBetween('2025-03-01','2025-03-01').inE('Payment').count()` | `822`（正好一天的量 ✓） |
| `createdBetween('2025-11-01','2025-11-07').inE('Payment').limit(500)` | `500`（窗口内 5753 条 ✓） |
| 同窗口 `elementMap()` | `{id:hub_pay_124110, label:Payment, status:SUCCESS, type:transfer, amount:111, currency:USD}` ✓ |
| `inE('Payment').count()`（无窗口） | 明确报错并提示加 `createdBetween(...)` ✓ |
| `g.V('1000','Account').out('AccountToIP')`（未导入的边） | 走内联路径 ✓ |

UI：图下拉出现 `payment-graph-as-edge · Aerospike-Edge` ✓，示例菜单内置 3 条（一天 500 笔 / 某周计数 / 无窗口报错演示）✓。

### 25.6 测试

| 测试 | 覆盖 |
|---|---|
| `AerospikeEdgeChunksTest`（6，纯逻辑，无需服务器） | 表名/键格式与解析、大点键、**批量导入的逐层折叠（9 条边/每块 2 条 → 5+3+2+1 层，root 覆盖全span）**、`levelsFor`（2000/层 → 30 万=2 层、>400 万=3 层）、窗口选择子块（边界/空/有序） |
| `AerospikeEdgeIntegrationTest`（8，真实 Aerospike，chunk=64 → **三级**） | 导入后大点标记与层数、窗口只返回窗口内的边且时间有序、无窗口报错并带 `createdBetween` 提示、**内联更新覆盖导入版且不重复**、未导入的边走内联、Gremlin 窗口查询与 limit、序列化与内联同构、表名规则 |
| `AerospikeEdgeLiveLoadTest`（1，`-Daerospike.asEdge.set`） | 真实装载 30 万条 demo 并跑文档里的 demo 查询 |
| `GraphStorageSchemaTest`（+1） | `Aerospike-Edge` 的 schema 往返与 `isAerospike/isRocksDb` 语义 |

### 25.7 TODO（要求 8：近线更新，保证内联与批量导入之间没有 gap）

当前读写语义已经**正确**（读时 merge 两条来源 ✓、新写/改写的边走内联并覆盖聚合版 ✓），但仍留三处待改进，
记录为后续工作：

1. **删除**：边被删除时聚合表里的副本仍在（无墓碑 ✗）→ 计划：写内联墓碑（`deleted=true` + lastUpdatedTime ✓）并在 merge 时按墓碑过滤，
   或由周期任务重写受影响的 chunk ✓。
2. **窗口之外的新边**：root 的 `min/max` 只在导入时计算 ✓；之后若在窗口外新增大量边，
   要么它们只在内联（受度数上限约束 ✓），要么需要**增量重聚合**受影响的层（把新 chunk 插入父层并刷新 span ✓）。
3. **重聚合/压缩任务**：把内联中已经稳定的边折叠进 level 0 chunk、刷新上层 span、清理墓碑 ✓（近线任务，允许分钟级延迟 ✓）。

因此，"内联 vs 批量导入" 目前是**读时合并（无 gap 的正确性）** ✓，写入侧的自动化补齐列入上述 TODO ✓。

---

## 26. UI 修正：查询行居中、大点双击扩展自动限流

### 26.1 示例下拉与查询框在同一行的中线上

`.gremlin-row` 的 `align-items` 由 `flex-start` 改为 **`center`**，示例框 `align-self: center` ——
两者现在共用同一行的中线（此前下拉贴着顶部 ✗），宽度仍为行的 **1/3** ✓。

headless Edge 实测（真实点击 `#modeToggle` + `#examplesBtn`）：

```
sameRow true, pickerOnTheRight true, pickerWidth 468, ratio 0.333,
centreOffset 0, centred true
```

### 26.2 双击大点：自动改用「窗口 + limit」的有界扩展

`payment-graph-aerospike-edge` 里的大点（30 万条收款在聚合边表里）双击时报错
「需要指定开始结束时间」✗ —— 因为 `Aerospike-Edge` 的读必须有窗口 ✓，而扩展查询 `g.V(id,'Label').both()` 没有窗口 ✓。

处理方式（前端自动、无需用户改语句）：

1. 先按原样扩展；若错误信息表示需要时间窗口（含 `createdBetween` / `creation-time window` ✓）→ **自动重试有界版本**：
   ```
   g.V('9000001','Account')
     .createdBetween('1970-01-01T00:00:00','2100-01-01T00:00:00')   // 足够宽的有限窗口
     .both().limit(200)
   ```
   并 toast 提示「该点是聚合大点，已只读取最近的 200 条边」✓（i18n ✓）；
2. 同一个 helper 也用于「按 id 扩展」（工具栏 `g.V(ids,'Type').both().both()` ✓）。

**为什么这样够快**（关键在服务端）：新增了 **窗口读的 cap 下推** ——
`createdBetween(...)` 之后第一个 hop 紧跟的 `limit(k)` 会被编译期合并进窗口（`createdBetween(a,b,k)` ✓），
`GremlinContext` 把 cap 随窗口一起传给存储 ✓，存储的新增重载
`readEdgesWithin(..., maxResults)` / `readEdgesBatchWithin(..., maxResults)` / `readAllEdgesBatchWithin(..., maxResults)`
（默认实现 = 读完再截断，其它后端零改动 ✓）在**聚合表按 createdTime 有序**的前提下**读到 k 条就停** ✓，
所以不会再为一个"展开"把整年 30 万条边读出来 ✗。

线上实测（8081）：

| 查询 | 结果 |
|---|---|
| `both()`（无窗口） | 报错并提示加 `createdBetween` ✓ |
| `createdBetween(1970,2100).both().limit(200)` | **200 条 / 200 边 / 114 ms** ✓（下推前：30 万条 / 5776 ms ✗） |
| `createdBetween('2025-06-01','2025-06-02').both().limit(200)` | 200 条 / 50 ms ✓ |

真实 UI 实测（headless Edge 驱动：选中 `payment-graph-as-edge` → 查 `g.V('9000001','Account')` → 画布上双击该点）：

```
before: {v:1, e:0}  ->  after: {v:51, e:200}
modalOpen false      （不再弹错误窗 ✓）
toast: 该点是聚合大点，已只读取最近的 200 条边 ✓
```

顺带修掉一个**既有回归**：`includeResult` 之后扩展查询的响应是 `{result, subgraph}`，
而 `expand()` 一直把整个响应当子图合并 ✗ → 普通点的双击扩展其实什么都没加 ✗；
现在统一走 `responseSubgraph(result)` ✓（有本轮实测覆盖 ✓）。

### 26.3 测试与验证

- `GremlinGraphQueryTest`（+1）：`createdBetween(a,b).both().limit(k)` 时存储收到 **cap=k** 与正确窗口；
  无 `limit` 时 cap=0 ✓；
- `AerospikeEdgeIntegrationTest`（+1）：cap=200 / 7 的窗口读分别返回 200 / 7 条，cap=0 返回整年 5000 条 ✓；
  Gremlin 形式 `…createdBetween('1970-01-01T00:00:00','2100-01-01T00:00:00').both().limit(50).count()` 有界 ✓；
- 全量 `./mvnw test`：**19 个模块 BUILD SUCCESS**。

---

## 27. 系统 metrics：采集、持久化与接口

### 27.1 采集了什么

新增模块 **`graph-metrics`**（唯一依赖 `graph-schema`，不引入任何第三方库），提供：

- `GraphMetrics`：线程安全计数器（`LongAdder`）+ 延迟直方图；每个计数器同时保留**总量**与**按天切片**
  （天用一秒缓存解析，热路径不分配 `LocalDate`）；`increment/recordLatency/snapshot/merge/reset`；
- `LatencyStats`：固定分桶直方图 —— 1 µs 以下 1 ns 一格，以上每个 2 的幂 **16 格**（比值 1.044）；
  记录是一次原子自增，内存固定（1776 个 long/直方图），百分位在桶内**线性插值**；
  实测四种分布（均匀 / 常数 / 指数 / 对数均匀）下 **P50/P90/P95/P99 误差 ≤ 1.7 %**（`PercentileAccuracyTest`）；
- `MetricsSnapshot` / `LatencySnapshot`：不可变快照（含每日切片与桶，可回灌）；
- `MetricsStore`（端口）+ `InMemoryMetricsStore` + `MetricsRegistry`（每图一个实例、按图绑定存储、
  每 30 s 定时 flush、关闭时 flush、支持 `reset`）。

指标清单（名字即接口，`name{k=v}` 形式便于切片）：

| 组 | 指标 |
|---|---|
| 读 | `storage.vertex.read`(+`.items`/`.failed`)、`storage.edge.read`(+…)、`storage.geo.read` |
| 写 | `storage.vertex.write`(+`.items`/`.failed`)、`storage.edge.write`(+…)、`storage.vertex.write.stale`、`storage.edge.write.stale` |
| 质量 | `storage.edge.evicted`（度数溢出）、`storage.write.retry`、`storage.vertex.delete`、`storage.edge.delete` |
| KV | `storage.kv.read.call`/`.items`/`.failed`、`storage.kv.write.call`/`.items`/`.failed` |
| 查询 | `query.count{language,source}`、`query.failed{language,source}`、`query.items`、`query.latency{language}`（直方图） |
| 查询→存储 | `query.storage.vertex.read`、`query.storage.edge.read`、`query.storage.kv.read`、`query.storage.kv.write`、`query.storage.items` |

### 27.2 怎么采（三个漏斗，零散点插桩）

1. **Aerospike 的每一次往返**：新增 `AerospikeOperations`（接口 + `Metered` 实现），把 store 里的
   `client.get/put/delete/operate/scanAll/query/getNodes/createIndex` 全部换到 `db.*`；
   批量调用按 key 数计入 items，scan 用回调计数，异常计入 `kv.*.failed`。
   读还会按“请求的 bin 是否是 `ve_*`／set 是否是 `*_edge_*`”归到 **vertex / edge** ——
   为此把 `AerospikeEdgeStore.getEdges/getEdgesBatch` 改成**只投影该边类的 bin**（少读数据 ✓，指标也更准 ✓）。
2. **RocksDB 的每一次读写**：全部经 `RocksDbHandle`，在那里按 **column family 前缀**
   （`vertex:` / `edge:`+`edge-inline:` / `geo:`）分类计数，batch put/delete 累计、`write(batch)` 一次调用计入一批；
   `metrics` CF 自身不计（否则“指标的指标”会无限自增）。
3. **写的结果（成功 / stale / 失败）**：store 本来就把每次写记进 `BusinessLog`，新增
   **`MetricsBusinessLog`** 装饰器把它翻译成指标（operation 含 "edge" → 边，否则点；
   details 里 `stale`/`conflict` → `*.stale`，其它失败 → `*.failed`），**不用改任何 store 方法**。

查询侧在 `QueryService` 里包一层 `measured(...)`：语言 + 来源（`source` 字段，UI 传 `ui`，默认 `http`）、
延迟直方图、失败计数、返回元素数，以及**本次查询造成的存储流量** ——
用执行前后 `VERTEX_READ`/`EDGE_READ`/`KV_*` 的**差值**，所以并行 seed 读也全部归到该次查询 ✓
（`graph-http/src/test/.../QueryMetricsTest` 用假 store 验证了差值口径）。

### 27.3 持久化（默认存储里各写各的）

| 存储 | 位置 | 形状 |
|---|---|---|
| Aerospike | 新增系统 set **`graph_metrics`**（键 `metrics:<graph>`） | bins：`counters`、`counter_days`（嵌套 map）、`latency_buckets`、`latency_totals`（count/sum/max）、`updated_at` |
| RocksDB（Inline/Edge 均） | **该图自己的目录**里的新 column family **`metrics`**（键 = 图名） | 每行一个数：`c <metric> <total>`、`d <metric> <day> <v>`、`l <metric> <count> <sum> <max> <bucket>:<hits>,…` |

两者都保留**每日切片**，所以“今天”跨重启不被清零；直方图桶也保留，重启后 P95/P99 仍是之前的样本算出来的。
flush 由 `MetricsRegistry` 每 30 s 与关闭时执行（也可 `POST /api/v1/metrics/{graph}/flush` 手动）。

**踩到的两个坑（都已修 + 回归测试）**：
- `reset()` 原先**替换** `GraphMetrics` 实例 → store 手里还是旧实例 → 重置后存储指标全为 0 ✗
  → 改成**原地清空** ✓（`GraphMetrics.reset()`）；
- `bind(store)` 常常发生在 `forGraph()` 之后（store 需要 metrics 才能建、metrics store 又要 store 才拿得到）
  → 首次加载读的是内存空存储 ✗ → `bind` 现在会**立即从新存储合并一次快照** ✓。

### 27.4 HTTP 接口与实测

```
GET  /api/v1/metrics                 # 所有图：分组视图 + 扁平 counters
GET  /api/v1/metrics/{graph}         # 单图：storage / quality / queries（含 P50·P95·P99）/ counters
POST /api/v1/metrics/{graph}/flush   # 立即落盘
POST /api/v1/metrics/{graph}/reset   # 清零（并存删除已落盘快照）
```

线上实测（8081，`payment-graph` Aerospike + `payment-graph-rocksdb-inline`）：

```
storage : vertexRead 104 / edgeRead 3 / keyValue 57 calls · 107 items      （Aerospike）
          vertexRead 2 / items 102 / edgeRead 1 / keyValue 3 · 103 items   （RocksDB）
queries : {"gremlin":{"ui":3},"cypher":{"http":1}}  translated {vertexReads:104, edgeReads:3, keyValueReads:57}
latency : count 3, mean 16 ms, p50 6.0, p95 39.9, p99 39.9, max 38
写：POST /api/v1/vertices → SUCCESS；同一属性用更旧 lastModifiedTime → 409 CONFLICT
          vertexWrite 3 / staleVertexWrites 2
重启：flush → 重启 → “restored from storage: 12 counters, 1 histograms”，
          vertexRead 51 / edgeRead 1 / kv 2 / uiQueries 1 / latency count 1 / p95 28.31 ✓（两套存储都验过）
```

### 27.5 测试

`graph-metrics` 16（计数器/每日切片/快照回灌/注册表/百分位精度）、`MetricsBusinessLogTest` 5、
`RocksDbMetricsStoreTest` 6（含关闭重开、只读不丢桶、metrics CF 不计流量）、`QueryMetricsTest` 5、
`MetricsControllerTest` 5；相关模块全绿（284 个测试）。

---

## 28. 图管理 UI（第一步：系统指标面板）

在既有侧栏上加了一个 **「指标」tab**（`data-tab="metrics"`），切换即拉取
`GET /api/metrics/{graph}`（graph-web 新增代理路由 → `graph-http` 的 `MetricsController`），
显示三张卡片：

- **存储读写**：读点 / 写点 / 读边 / 写边 / KV 往返 —— 每行 `今天 / 累计`，并带 `条数`（items）
  与红色 `失败`；数字用等宽数字（`font-variant-numeric: tabular-nums`），今天用高亮色；
- **写入质量**：边溢出（度数淘汰）、点更新 stale、边更新 stale、重试；
- **查询**：按 `语言` 分行、`来源`（ui/http/algorithm/internal）做成 chip 计数；
  下面一行是 **延迟**（count · avg · p50 · p95 · p99 · max，每项一个 `<i>` 小标签 + 等宽数值），
  再一行是 **翻译成 KV 查询**（读点 / 读边 / KV 往返）。

刷新按钮手动重拉，切换图时若面板可见会自动重拉；没有任何计数时显示「还没有任何指标：先查一次…」。

顺带把 UI 发出的查询打上 `source: 'ui'`（原先会被计成 `http`），这样「查询来源」在面板上真能区分
——headless Edge 实测：在页面上执行 `g.V('135','Account').out('Payment').limit(3)`
→ 面板 chip 由 `ui 2` 变 `ui 3`，延迟 `avg14 / p50 15.2 / p95 26.21 / p99 26.21 / max 26`，
读点 `54 / 54`（今天/累计）。

**还没做（下一步）**：schema 编辑器 —— 在图里加属性/加点/加边、先建实例再构造点边，
提交后生成 graph schema JSON、写入磁盘并直接加载进服务（含「导出保存」按钮）。

---

## 29. 图管理 UI（第二步：建模 / 建图 / 造数据）

新增一个**独立页面** `builder.html`（+ `css/builder.css`、`js/builder.js`，静态资源同样打包进 graph-web），
侧栏仪表盘右上角「图管理」按钮进入。页面分五块：**图实例 → 点类型 → 边类型 → 提交 → 造数据**。

### 29.1 后端的建模接口（`graph-http`）

| 接口 | 作用 |
|---|---|
| `GET /api/v1/schema/{graph}` | 该图的 schema 文件 JSON（导出用） |
| `GET /api/v1/schema/{graph}/draft` | **把已有图描述成编辑器模型**（打开已有图继续改） |
| `GET /api/v1/schema/storage-types` | 可选存储类型（Aerospike / Aerospike-Edge / RocksDB-Inline / RocksDB-Edge） |
| `POST /api/v1/schema/validate` | 只校验，返回 `{valid, errors, warnings}` |
| `POST /api/v1/schema?replace=` | **校验 → 写 `<name>.json` → 注册 schema → 绑定存储 → 立即可查** |
| `POST /api/v1/graphs/{graph}/vertices`、`/edges`（+DELETE） | 往**指定图**写点/边（原先的写接口只服务主图） |

关键设计：

- **编辑器模型 `SchemaDraft`**（graph-http 的 dto 包）：只用页面需要的东西 —— 图名/描述/id 类型/存储/点类型/边类型，
  类型用人类写法（`string`、`int`、`list<double>`、`map<long>`、`enum:A|B`），属性 id 由**声明顺序**自动分配。
  `SchemaDraft.toSchema(id)` 生成引擎用的 `GraphSchema`，`SchemaDraft.toDraft(schema)` 反向描述 ——
  所以「打开已有图 → 改 → 保存」是闭环，JSON 预览就是**将来会被加载的那份**（保存时用 `SchemaJsonCodec` 规范化后再落盘）。
- `GraphSchemaManager` 新增 **`registerOrReplace`**（`register` 仍拒绝重名，避免配置错误；建模页覆盖同名图走新方法）。
- `GraphRegistry` 改为**可变 + 保序**（`ConcurrentHashMap` + 名字列表）：保存后**无需重启**即可查询，
  下拉列表立刻出现新图；`GraphService.forGraph(registry, name)` 让写接口服务任意图（RocksDB 方案暂不支持写，会明确报错）。
- 校验失败是**正常答复**（`valid:false` + 原因），不是 500。

### 29.2 页面行为（headless Edge 实测）

```
打开 /builder.html → 11 个下拉项（10 张图 + 空）、4 种存储类型
填「图名 + 点 Person(name, display) + 边 Knows(Person→Person)」
  → 校验：通过（提示未声明度数上限）
  → 保存并加载：已保存并加载 ui-built-094364，文件 D:\...\ui-built-094364.json
  → 下拉里立刻出现 ui-built-094364
造数据：写入点 p1(name=Ada) → SUCCESS
查询：g.V('p1','Person').values('name') → ["Ada"]（子图也带回来了）
导出 JSON：浏览器下载 <name>.json
```

另外：`POST /api/v1/schema` 对已存在的图返回 400 并提示「加 replace 覆盖」；`replace=true` 后 200 ✓。

### 29.3 测试

`SchemaDraftTest` 9 项：属性序号按声明顺序、display/vector/geo 标志双向保留、边端点与 `isNull`、
存储类型与 RocksDB 参数、draft↔schema 往返、类型解析（含错误信息）、空名报错、**生成的 schema 能通过 `SchemaValidator`**。
（顺带修掉一个真问题：draft 里声明的 `idType` 原先没应用到点 id schema 上，现在会。）

---

## 30. payment-graph（Aerospike）的核对与 benchmark

新增两个集成测试（真实 Aerospike，缺数据/缺服务自动 skip；数据来自 `http_demo_v1`，可用
`-Dpayment.vertexSet=` 覆盖；schema 直接读 `graph-http/src/main/resources/payment-graph.json` 保持一致）：

### 30.1 `PaymentGraphConsistencyTest`（12 项，全绿）

| 检查 | 做法 |
|---|---|
| 一跳出边 | `outE('Payment')` 与 store 解码出的 `ve_1_2` bin **逐条比对**：条数、edgeId 集合、对端顶点集合、`status` 值 |
| 一跳入边 | 同上，比 `ve_1_1` bin（account `135` 与 `1000` 两个起点） |
| `both()` | = 出 bin + 入 bin 的并集 |
| **原始记录 vs store** | 直接用 `AerospikeClient.get` 取 bin，**bin 的 key 集合 == store 返回的 edgeId 集合** |
| 点属性 | Gremlin 读出的属性与 `readVertex` 的存储值逐字段一致 |
| 两跳（`Payment→Payment`、`AccountToIP→IPToAccount`、`AccountToPhone→PhoneToAccount`） | 每一跳的条数都与**中间顶点自己的 bin** 对齐，逐条展开验证 |
| 两跳可达集 | `out('AccountToPhone').out('PhoneToAccount')` 的结果集 == 从 bin 手工推导的集合 |
| `has('status','SUCCESS')` | 计数 == bin 里 `status=SUCCESS` 的条数（顺带发现属性 id 是 **3** 不是 1 —— 现在从 schema 取，不硬编码） |
| `filter(status.equals('PENDING'))` | 与 `has(...)` 同值 |
| `limit/dedup/values` | `limit(3)` 三条、`limit(3).values('status')` 仍投影出 3 个值、`dedup()` == 去重后的对端数 |
| Cypher 分组 | `RETURN p.status, count(*)` 的分组计数 == bin 里按 status 统计的结果 |

### 30.2 `PaymentGraphQueryBenchmarkTest`（10 条查询，每次 3 预热 + 15 次计时）

延迟用 `graph-metrics` 的直方图（mean/p50/p95/p99/max），**代价用同一次运行里 store 的指标差值**：
点读条目、边读条目、KV 往返次数。实测（本机 Aerospike，单位 ms / 每 query）：

```
query                                             mean     p50     p95     p99  v-reads e-reads kv-trips
1. V(135) outE(Payment).count()                    2.4     2.3     3.7     3.7        0       1       1
2. V(135) bothE(Payment).count()                   2.7     2.8     3.4     3.4        0       2       2
3. V(135) out(Payment).count()                     1.7     1.7     2.2     2.2        0       1       1
4. V(135) outE(Payment).limit(10)                  1.5     1.5     1.9     1.9        0       1       1
5. V(135) outE(Payment).has(status=SUCCESS).count() 1.6    1.6     1.9     1.9        0       1       1
6. V(135) outE(Payment).limit(20).values(status)    1.9    1.8     2.9     2.9        0       1       1
7. V(1000) out(AccountToIP).out(IPToAccount)        5.1    5.0     7.3     7.3        1       3       3
8. V(1000) out(AccountToPhone).out(PhoneToAccount)  3.1    3.1     4.2     4.2        0       3       2
9. V(1000) bothE(Payment).dedup()                   2.8    2.6     3.8     3.8        0       2       2
10. cypher group by status                         53.2   53.1    60.8    60.8       51       1      52
```

**瓶颈（测试会随表一起打印，每条都用上面的数字说话）**：

1. **过滤不下推**：查询 5 与 1 同价（都是一次往返、一次解码）——`has` 在解码后过滤，
   2 与 5 的差异只是 tag 而已。要更快只能把谓词送进 store（解码时跳过/二级索引）。
2. **`count()` 白解码**：bin 是 CDT map，**不取 values 就知道 size**；查询 1/3 完全可以只读 size。
3. **第二跳不批量**：查询 7/8 的往返数随 fan-out 增长（3 次），跨 hop 边界没有合批；
   把第二跳改成对 frontier 的 `readEdgesBatch` 能直接砍掉往返。
4. **`limit` 不省流量**：查询 4/6 与 1 一样付一次完整 bin 的代价 —— 需要"带上限的读"
   （像聚合边表的窗口 cap 那样）。
5. **投影不省流量**：边属性同在一个 bin，`values('status')` 仍解码所有属性；
   把热点属性拆 bin 或在解码时投影才有收益。
6. **Cypher 聚合 = N+1**：查询 10 一次聚合花了 **52 次往返 / 51 次点读**（hub 的 50 个接收方各读一次），
   约 53 ms，是上面最快的查询的 20 倍；在 store 里按属性分组（解码时聚合）可同时省掉遍历和读。

---

## 31. 「smart」payment 数据：向量 + 地理，三个流

### 31.1 数据与两个实例

新 schema（`tools/generate-smart-payment-schema.js` 生成，两份除 `storage` 外**完全一致**）：

| | 内容 |
|---|---|
| 点 | `Account`（name、accountStatus、city、**embedding 16 维 isVector**、**location isGeo**）、`Merchant`（name、category、embedding、location） |
| 边 | `Payment`（Account→Account，status/amount/currency）、`Visited`（Account→Merchant，times） |

| 实例 | schema id | 存储 | 为什么 |
|---|---|---|---|
| `payment-graph-smart` | 41 | Aerospike（set `payment_smart_v1`） | 向量走 **HNSW**（`POST /api/v1/vector-index/rebuild` 建索引：Account 200 点 9 层 4178 链接 9.9s；Merchant 40 点 7 层 639 链接 1.9s） |
| `payment-graph-smart-rocksdb` | 42 | RocksDB-Inline（`.rocksdb/payment-graph-smart-rocksdb`） | 地理走**真正的 S2 索引**（Aerospike 社区版无 geo 二级索引，会退化为扫描） |

数据由 `SmartPaymentGraphLoader` 生成（固定种子，**故意结构化**）：**200 账户 / 40 商户 / 400 payment / 300 visit**，
16 维向量聚成 **5 个簇**，5 个簇同时对应 **5 个商圈**（人民广场 / 徐家汇 / 陆家嘴 / 静安 / 中山公园），
70% 的 payment 发生在同簇账户之间，visit 只去本商圈商户 —— 所以「相似」和「附近」在数据上是一致的，
三个流的答案才有意义。实测装载：Aerospike 3.2s、RocksDB-Inline 940 条 0.4s。

```bash
./mvnw -q -pl graph-integration-test test -Dtest=SmartPaymentGraphLiveLoadTest \
       -Dsmart.set=payment_smart_v1 -Drocksdb.live.dir=.rocksdb -Dsurefire.failIfNoSpecifiedTests=false
curl -s -X POST localhost:8081/api/v1/vector-index/rebuild -H 'content-type: application/json' \
     -d '{"graph":"payment-graph-smart"}'
```

### 31.2 三个流（实测）

```
向量：g.V('a7','Account').nearest(5).values('city')
      → ["Xujiahui","Xujiahui","Xujiahui","Xujiahui","Xujiahui"]   （5 个近邻全在同簇，数据设计生效 ✓）

流 1  最相似用户 → 他们的 payment
      g.V('a7','Account').nearest(5).outE('Payment')            → 10 条边（子图 16v/13e）
      …limit(5).values('status')                                 → ["SUCCESS","SUCCESS","FAILED",…]

流 2  payment → 每个接收方最相似的 5 个账户
      g.V('a7','Account').out('Payment').dedup().count()         → [3]（a7 付给 3 个不同账户）
      g.V('a7','Account').out('Payment').dedup().nearest(3)      → 8 个点，全在 Xujiahui 簇

流 3  给定经纬度 → 最近商户 → 每个商户最相似 5 个
      （RocksDB-Inline，真 S2 索引）g.V('m1','Merchant')
        .circle('{"lat":31.2304,"lon":121.4737,"radius":3000}').limit(5)   → 5 家商户，全 category=coffee
      （Aerospike）同一句也可用（扫描兜底），结果一致 ✓
        .circle(…).limit(3).nearest(2)                            → 4 家商户（3 家 + 它们的近邻，去重后）
```

两个存储的**同一句地理查询返回完全相同的距离**（117.96 / 197.04 / 207.70 m ✓），
`distanceMeters` 只出现在**结果**里（计算属性不进子图，两套存储行为一致）。

### 31.3 顺带修掉一个引擎 bug：同名向量属性的层边类冲突

`VectorIndexSchemas.ensureLayer` 用 `<属性名>_<层>` 给 HNSW 层边命名，**只按属性名**，
于是 `Account.embedding` 与 `Merchant.embedding` 撞车 → `Duplicate edge schema id 3`（第二个属性的层边拿到了第一个的 id）✗。
修复：新增 `layerEdgeNameFor(schema, vertex, property, level)` —— 平名字空闲（或本来就属于该点类型）就沿用
`<属性名>_<层>`，被别的点类型占用时改用 `<点类型>_<属性名>_<层>`；`layerSchema`、`layerEdge`、`nextEdgeId`
三处统一走它。**已声明层边的 schema 不受影响**（hnsw-sift 的 `vector_0..14` 原样保留 ✓），
`VectorLayerNamingTest` 3 项覆盖：单属性沿用平名字、同名属性各自成套、手写层边不被改名。

（这也是为什么数据里两个点类型都用 `embedding` 才能跑起来 —— 现在跑起来了 ✓。）

---

## 32. 按 benchmark 的结论做优化（第一轮）

第 30 节的六条瓶颈，这一轮修掉三条最值钱的，并补上回归测试；benchmark 的说明文字也随之改成
「已修 / 待修」两段（数字仍在每次运行时打印）。

### 32.1 修掉的三条

| 优化 | 做法 | 效果（payment-graph，Aerospike） |
|---|---|---|
| **Cypher 聚合的 N+1** | `CypherGraphQuery` 新增「哪些变量真的需要属性」分析：投影里以 `<var>.<属性>`（`id` 除外）出现、WHERE/ORDER BY 用到、内联约束约束到非 id 属性、或直接把变量本身返回（`RETURN b`，调用方要画它）才算需要。不需要的变量（含被 `count(*)` 聚合的目标点）**只按 id+label 绑定，不读记录**。分析既走表达式树也走**源码文本**（正则 `<var>\.(?!id)`），所以将来新增表达式类型也不会悄悄漏读/多读 | 查询 10：**53.2 ms / 51 次点读 / 52 次往返 → ~3 ms / 1 次点读 / 2 次往返**（那 1 次是种子点的存在性检查） |
| **`count()` 白解码** | 新端口 `GraphQuery.countEdges(vertexId, vertexName, edgeName, side)`（默认读后取 size）；Aerospike 用 **bin 的 map size** 直接回答（不解码 50 条边）。编译器把 `<hop>E('x').count()`（中间无 `has/filter`）改写成单步 `countOutE/countInE/countBothE`；**要采集子图时自动退回读边**，所以 UI 画图不受影响 | 查询 1：2.4 → 1.6 ms；查询 3（`out(...).count()` 也被改写成 countOutE）：1.7 → **0.9 ms** |
| **投影仍解码全部属性** | `AerospikeEdgeStore.deserializeEdgeValue/getEdges/getEdgesBatch` 增加「只解码请求的属性 id」重载，`AerospikeGraphQuery` 把解析好的投影传进去（值本来就在同一个 bin 里，省的是对象构造） | 查询 6 的解码量随投影变小（往返不变，符合第 30 节第 5 条的判断） |

### 32.2 回归测试

- `GremlinGraphQueryTest`：两条 count 改写测试改为断言**单步** `countOutE/countInE/countBothE`，并断言
  store 的 `countEdges` 被调用（而不是 `readEdges`）；
- `CypherGraphQueryTest` 新增 3 项：**聚合关系属性不读目标点**（只读 1 次种子）、
  `RETURN b.accountType` 仍读点、`RETURN b` 仍带属性返回。

### 32.3 还剩的（已写进 benchmark 输出）

1. 边属性过滤在解码之后做（查询 5 ≈ 查询 1）→ 把谓词下沉进 store；
2. `limit` 仍付整个 bin 的代价（查询 4）→ 带上限的读；
3. 两跳 = 两跳的往返（7/8，已经是批读，剩下的是往返本身）；
4. 按类型计数还需要锚点（`g.V(id,label)`）。

---

## 34. 按 benchmark 的结论做优化（第二轮）：条件与上限下沉进 store

第 30 节剩下的第 1、2 条（过滤不下推、`limit` 付整个 bin 的代价）这一轮做掉了，
并且第一次让 benchmark 能**量出**省下来的工作：新增 `storage.edge.decoded` /
`storage.edge.filtered` 两个计数器（store 把 bin 里的边缘变成对象的条数 / 被条件丢掉、
根本没被构造的条数）。hub 的 bin 里恒有 50 条边，所以「解码了几条」才是有意义的数字。

| 查询 | 优化前 decoded | 现在 decoded | 说明 |
|---|---|---|---|
| 1/2/3 计数（`outE/bothE/out().count()`） | 50 | **0** | bin 的 map size 就是答案 |
| 4 `outE(Payment).limit(10)` | 50 | **10** | 上限即读的上限，选最新的 10 条 |
| 5 `outE(Payment).has(status,'SUCCESS').count()` | 50 | **0** | 条件在走 bin 时就判定 |
| 6 `outE(Payment).limit(20).values('status')` | 50 | **20** | 上限 + 投影 |
| 7/8 两跳 | 3 / 2 | 3 / 2 | 这两跳的边必须读（要拿端点） |
| 10 Cypher 分组 | 50 | 50 | 它确实要每条 payment 的 `status` |

延迟仍然被一次往返主导（1.0–1.7 ms，与优化前同一量级）——所以这一轮的收益写在
「构建了多少对象」那一列，而不是毫秒列。

### 34.1 端口与实现

- **`EdgePropertyFilter`**（`graph-query/storage`）：`List<String> propertyNames` + 
  `Predicate<Map<String,Object>> matches`，即「一条只读这些属性、并且**可以在解码时判定**的条件」✓；
- **`GraphQuery`** 新增（都有默认实现，后端零改动即可保持正确）：
  `readEdges(id, name, edgeName, side, props, filters, maxResults)`、
  `readEdgesBatch(..., filters, maxResults)`、`countEdges(id, name, edgeName, side, filters)`；
  默认实现「读完之后在内存里过滤 + 截断」✓；
- **能力开关 `supportsEdgeReadPushdown()`**（默认 `false` ✓）：只有声明 `true` 的 store 才会收到
  条件/上限；Aerospike 返回 `true` ✓。这个开关让「优化」与「要求」分开——不支持的 store
  走老路径 ✓，也不打扰既有的 mock/fake 测试（Mockito 对 default 方法返回默认值 ✓）；
- **`AerospikeEdgeStore.EdgeRead`**（`projection` / `filterProperties` / `filter` / `maxResults`）：
  `decodeEdges` 先用 `rawPropertyValues`（只取条件要读的属性，不构造边）测条件 → 再按上限选
  **最新的 k 条**（`newest()` 用有界选择 + `PriorityQueue`，不再整 bin 排序 ✗）→ 只对留下的边反序列化 ✓；
  `countEdges(EdgeRead)` 带条件时**一条边都不构造** ✓；`getEdgesFiltered/getEdgesBatchFiltered` 是新的入口 ✓
  （旧的 `getEdges`/`getEdgesBatch` 保持原样 ✓）；
- **Gremlin 编译期折叠**（`GremlinGraphQuery.pushEdgeConditions`）：边跳后面**紧邻**的 `has`/`filter`
  与 `limit` 折进该跳（`outE(x, conditions, cap)`），`…has(…).count()` 直接变成带条件的 `countOutE` ✓；
  只在**有边类名**时折叠（`bothE()` = 全部边类没法压进一个 bin 读 ✗），也只在 store 声明支持时折叠 ✓；
  中间夹着 `batch`/`barrier` 不折（不干扰它们）✓。

### 34.2 `limit` 不再往子图里留边

顺带修掉一个不一致：`limit(k)` 以前只是截断结果、**不**把多余的边从子图里去掉 ✗，
于是「答案 3 条边、画出来 50 条」✗；而下沉后的读只会记录它读到的 k 条 ✓。
现在 `limit` 也调用 `ctx.dropElement(...)`（和 `has`/`filter`、以及带 cap 的窗口读同一规则）✓——
这一点是靠新的**等价测试**逼出来的 ✓（见下）。

### 34.3 回归测试

- **`GremlinGraphQueryTest`**：`outE('x').count()` 之类在**不声明能力的 store** 上必须走老路径
  （保证不变量），并新增 `aCountWithoutAnEdgeClassStillReadsTheEdges`（上一轮那条回归）✓；
- **`PaymentGraphConsistencyTest.pushedConditionsAndCapsAgreeWithThePlainRead`**（新增，真实 Aerospike）：
  同一批查询分别跑在「声明支持下沉的 store」与「`supportsEdgeReadPushdown()=false`、其余全部转发的包装 store」上，
  断言**结果、子图的值、以及画出来的边数**三处完全一致 ✓。这是让下沉保持诚实的测试：
  11 条查询（`has`/`filter`/带条件计数/`limit`/`limit`+投影/条件+上限/`inE`/两跳首跳）✓。
- 过程中抓到两个真 bug：① 折叠后的 pushdown 列表与 step 对齐错了（`Collections.nCopies` 预填 + `add` ✗）；
  ② `AerospikeGraphQuery.readEdgesBatch` 4 参 → 6 参 → 4 参**无限递归** ✗（改成转发 `readEdgesBatchNative` ✓）。

---

## 33. UI 修正：主页查询行不再错行、建模页配色

### 33.1 主页

- **图管理入口改成纯图标**（`#builderBtn`，放在 `.actions` 里与语言切换、示例、查询按钮同组），
  不再作为 `idPanel` 前面的独立块 —— 之前它把「顶点类型 + 顶点 ID 输入」和右侧按钮挤成两行 ✗；
- 标签 **「顶点类型」→「顶点」**（英文 `Vertex type → Vertex`），少占地方；
- **示例下拉限宽**：仍是查询框的 1/3，但加 `max-width: 320px` 与省略号，宽屏下不再无限长。

headless Edge 实测（1600×950，ID 模式）：`sameRow true`、`noOverlap true`（ID 输入右边界 905px，按钮组左边界 1136px）、
`pageOverflow false`、`builderIsIcon true`、`builderInActions true`、`examplesMaxWidth 320px`、标签 `顶点` ✓。

### 33.2 建模页配色

根因：`builder.css` 里写的是**深色兜底**（`var(--panel, #151b24)`、`var(--input-bg, #0d1219)`），
而仪表盘是**浅色主题**（`--bg:#eef1f8`、`--text:#0f172a`）——于是卡片/输入框拿到深底、文字拿到深色 → 看不见 ✗。
现在整套改用仪表盘的变量并配**浅色兜底**：页面 `--bg`、卡片 `--surface`、边框 `--border-2`、
主按钮 `--accent` 白字、结果块用浅绿/浅红、JSON 预览浅灰底 + `--text-2`、toast 深底白字。

headless Edge 实测：`body bg rgb(238,241,248) / color rgb(15,23,42)`（可见 ✓）、卡片白底 ✓、
输入框白底深字 ✓、预览浅灰底 `rgb(247,249,253)` ✓、5 张卡片标题 `1 · 图实例 | 2 · 点 | 3 · 边 | 4 · 提交 | 5 · 造数据` ✓、
无 JS 报错 ✓、图下拉 12 项 ✓。顺带把建模页的「点类型/边类型」也统一成「点/边」。

### 32.4 回归：`both()`（不带边类）= 全部边类

上一条 count 改写引入过一个真 bug：`both().count()` 这类**不带边类**的计数也被改写成了 `countBothE`，
而「全部边类」不是某一个 bin 能回答的 → store 返回 0 ✗（`QueryApiIntegrationTest` 的
`g.V('150','Account').both().both().count()` 期望 5000 得到 0 才暴露出来）。
修复：`GremlinSteps.countEdges` 在 `edgeName == null` 时**退回读边**（子图采集时同样退回）；
新增 `GremlinGraphQueryTest.aCountWithoutAnEdgeClassStillReadsTheEdges` 断言这一点。
带边类的 `<hop>E('x').count()` 仍然走快路径 ✓。

---

## 35. 模糊关联（Fuzzy Linking）

### 35.1 要解决的问题

金融风控里，知道系统会做关联的欺诈分子会刻意打散**显眼**的特征（姓名、手机号、卡号各不相同），
但很难不打散**细碎**的习惯：同一家邮箱服务商、同一段 IP、`zhang`+数字的用户名、同一张卡的 BIN。
模糊关联就是把这些片段变成一个**节点**：片段一致的账户算出的 key 相同，于是落在同一个
`FuzzyLink` 点上，查询可以从模式直接走到团伙。

### 35.2 新模块 `graph-fuzzy`

| 类 | 作用 |
|---|---|
| `FuzzyContext` / `FuzzyContexts` | context 的读取：字段名**大小写/下划线无关** + 一组别名（`ip`↔`ipAddress`、`phone`↔`phoneNumber`、`cardNumber`↔`card`…），值按文本读（`13800138000.0` 这种 JSON 数字读回 `13800138000`）；`fromJson` 读客户端传来的对象，`fromJsonAndProperties` 用点自身的属性补全 |
| `FuzzyFunctions` | 函数库（`signatures()` 供编辑器展示）：`raw/lower/upper/trim`、`digits/first/last`、`alphaPrefix/digitSuffix`、`regex`（有捕获组取第一组，否则取整个匹配）、`emailDomain/emailName`、`ip2/ip3/ip4`（去端口与 CIDR 后缀）、`countryCode`（**只认 `+`/`00` 开头的 E.164**，按一张常见国家码表最长匹配，表里没有就不生效——猜出来的国家码会把仅长得像的号码连在一起）、`phonePrefix`、`cardBin`、`hash`（SHA-256 前 n 位十六进制，key 不必携带原值）、`opt`（可缺片段 → `*`）；函数可**嵌套**（`lower(emailDomain(email))`） |
| `FuzzyExpression` | 递归下降解析 `part ('+' part)*`，part 是调用或引号字面量，参数是字段名/字面量/数字/嵌套调用；求值返回每段的片段；**任何一段取不到值 → 整个模式不生效**（返回 `null`）；`explain(context)` 返回逐段结果 + key，供编辑器预览 |
| `FuzzyKey` | key 归一化：小写、非字母数字/`._-` 压缩成 `_`、超过 120 字符换成 `前24字符~sha256前32位`（仍确定） |
| `FuzzyPattern` / `FuzzyPatterns` / `FileFuzzyPatterns` | 模式的记录与 JSON 读写（`{ "<graph>": [ … ] }`）；`FileFuzzyPatterns` 是**按图一个文件一份**的注册表：写入后立刻生效、磁盘改动按时间戳探测、每秒最多读一次、坏模式报错时带上模式号 |
| `FuzzyLinks` / `FuzzyLink` | 计划的**唯一**出口：`plan(pattern, context)` → `FuzzyLink(patternId, patternText, key, fragments, linkVertexId)`，读写两侧都用它，所以 id 天然一致 |
| `FuzzySchema` | 约定名（`FuzzyPattern` / `FuzzyLink` / `FuzzyLinkedTo`）、`pattern_<id>`、`fl_<pattern>_<key>`、`<link>|<member>` 边 id、`isSupported(schema)` |

模式是**配置**（像 schema 一样），放在工作目录的 `fuzzy-patterns.json` 里，管理页改完即生效——
图里只存**链接**（`FuzzyLink` 点与边），不存模式定义本身 ✗（单一事实来源）。

### 35.3 查询步 `fuzzyLink`

- 注册在 `GremlinGraphQuery`，参数 `fuzzyLink(patternId[, contextJson])`；模式 id **允许 0**（新增
  `nonNegativeInt` ✗）；未知模式、非法 context 都包装成 `QueryException`（UI 得到 400 + 消息 ✓）；
- context = JSON ∪ 上游点的属性（JSON 优先）；**空种子也行**（`g.V().fuzzyLink(...)`）→
  为此放宽了 `GremlinQuery.parse`：`g.V()` 允许没有 id/name（原来是直接报错 ✗）；
- 找不到 link 点（没人算过这个 key）或模式不适用 → **空结果**（不是错误：节点是被成员创建出来的 ✓）；
- 投影计划新增一条：`fuzzyLink` 会产生点，且**不带 context 参数时要读上游点的全部属性**（否则
  `g.V('a1','Account').fuzzyLink(0)` 拿不到 email/ip ✓）。

### 35.4 Schema 与数据

`payment-graph.json` 追加（既有 id 全不动 ✓ 只 +51 行）：

- `Account` 增加 `username`/`email`/`phoneNumber`/`cardNumber`/`ipAddress`（模式读的原始属性）；
  `username` 是 display 属性（`email` 不是 ✗，`QueryApiIntegrationTest` 的期望同步更新为
  `[firstName, lastName, username]`）；
- 点 `FuzzyPattern`(4)、点 `FuzzyLink`(5)、边 `FuzzyLinkedTo`(6)（`FuzzyLink` → `Account`，DIRECTED，
  **出度/入度都是 1000** ✓，idSchema 用字符串 `<link>|<account>`）。

`FuzzyLinkDemoLoader`（graph-integration-test）造两圈数据：**A 圈 6 个**（都 `mail.ru`、`+86`、
`10.20.x.x`、`zhangNNNN`，其余全不同）、**B 圈 4 个**（`10.30.40.x`、卡 BIN `621700`、
邮箱本地部分 `4410xxxx`）、**对照组 6 个**（什么都不共享）+ 收款方 `fz_receiver`；每人给收款方
打两笔款。种子模式 0/1 也写进图（`pattern_0`/`pattern_1`）。

实测（真实 Aerospike，`http_demo_v1`）：`17 accounts, 15 link nodes ({0=6, 1=4}), 26 link edges, 32 payments` ✓
——即两圈各自归并成一个节点（6/4 个成员 ✓），对照组各自单点 ✓。

### 35.5 HTTP 与 UI

`/api/v1/fuzzy/*`（`FuzzyController` + `FuzzyPatternStore` + `FuzzyLinkingService`）：

| 接口 | 作用 |
|---|---|
| `GET /patterns?graph=` | 模式列表（含每个模式读的字段、文件路径；停用的也列出 ✓） |
| `GET /functions` | 函数库签名 + 说明（编辑器帮助） |
| `POST /validate` | 校验表达式并按 context 预览：`valid/errors/partCount/fields/key/linkVertexId/steps[]`；**解析失败是 `valid:false` 而不是 400** ✓ |
| `POST /patterns` / `DELETE /patterns/{graph}/{id}` | 新建/覆盖、删除 |
| `POST /graphs/{graph}/plan` | 只算不写（预览会生成哪些 key） |
| `POST /graphs/{graph}/links` | 真正写：用 `GraphService` 的普通写路径写 link 点 + 边，幂等 ✓ |

`ApiExceptionHandler` 现在也把 `FuzzyPatternException` 当 400 ✓；`QueryService` 通过
`@Autowired(required=false)` 的 setter 注入 `FuzzyPatternStore`，给每张图的 Gremlin 引擎挂上注册表
（`engine.fuzzyPatterns(...)`）；`ProxyController` 转发 `/api/fuzzy/**`。

前端：主页示例下拉新增 3 条模糊关联示例 ✓；建模页新增卡片「6 · 模糊关联模式」——列出模式、
填表达式 + 样例 context 校验并预览（逐段值 + key + link 点）、保存/删除、查看可用函数、
「关联该图上某点」。headless Edge 实测：6 张卡片 ✓、打开 payment-graph 列出 2 个模式 ✓、
预览得到 `key：mail.ru_86_10.20` + 每段值 ✓、保存后 2→3 ✓、删除后回到 2 ✓。

### 35.6 测试

- `graph-fuzzy`：`FuzzyExpressionTest`(13) + `FuzzyLinksTest`(14)——片段/不生效/opt/嵌套/regex 分组/
  IP 端口与 CIDR/国家码表与裸号码/hash/长 key/计划/停用/别名/JSON 往返/坏文档/文件读写/签名；
- `graph-query-gremlin`：`GremlinFuzzyLinkTest`(11)——同片段两账户同节点 ✓、从账户自身属性查 ✓、
  `values('key')` 投影 ✓、完整链路（link → 账户 → 付款 → 收款方 + 子图 4 条边 ✓）、第二个模式 ✓、
  模式不适用/无人算过 → 空 ✓、未知模式/缺参/非对象 context 的错误消息 ✓；
- `graph-http`：`FuzzyControllerTest`(12)——模式 CRUD、校验预览、坏表达式 `valid:false`、
  context 来自点、点不存在报错、未知图 400、函数库、停用模式仍可见；
- `graph-integration-test`：`FuzzyLinkIntegrationTest`(10，真实 Aerospike)——schema 的 1000/1000 与
  端点、两圈大小、模式找到的正是那一圈（不含另一圈与对照组）、第二个模式找 B 圈、账户自省、
  link 点与成员双向一致、完整链路 12 笔付款 + 子图边数、`limit(3).values('amount')`、
  无关 context 为空、key 稳定。

### 35.7 已知限制 / 下一步

1. 关联只在**写入时**发生（loader / `POST /links`）；把 `Account` 的写路径自动挂钩（写点即建链）还没做 ——
   需要 `VertexController` 在写成功后按图的模式补一次 link 写入；
2. 模式匹配是**逐条**的：没有为高频片段建反向索引（`key → 成员`），所以「反向找节点」靠确定性 id ✓
   已经够快，但「按 key 前缀找所有节点」需要扫描 ✗；
3. `opt(...)` 生成的 `*` 片段会被算进 key（有意为之：它表示「这个片段缺失」），多个不同缺失组合可能撞在一起，
   需要更严格的语义时可以给 `opt` 加标记位；
4. `countryCode` 依赖内置国家码表（约 45 个常见码），表外号码的模式不生效 —— 可以改成可配置；
5. UI 还没有「一键把某点关联」的入口（只有建模页的表单），示例里也没有 pattern→accounts→payments 的图形化解释。

### 35.8 修正与补充（同日追加）

**① `regex` 的回车/反斜杠 bug（用户报的那个「udf regex 没实现」）**

现象：`regex(username, '^\d+$')` 恒为 `null` ✗ —— 解析器把引号里的 `\X` 一律当转义（`\d` → `d`）✗，
于是正则变成 `^d+$` ✗，静默不匹配（看起来就是「这个函数没实现」）。修复：**反斜杠只在转义引号本身和 `\` 时
才吃掉**，其余原样保留 ✓（`\d`、`\w`、`\s`、`\.` 都能到引擎 ✓）。

顺带把 `regex` 补成 `regex(field, 'pattern'[, group])`：

- 不给 group：有捕获组取第一组、否则取整个匹配（原行为 ✓）；
- 给了 group：取第 N 组（`0` = 整个匹配 ✓，`groupIndex` 允许 0 ✓）；
- 组号超出捕获组数量 → **即使当前值不匹配也报错** ✓（这是模式写错了，不是数据问题 ✓），
  消息里带上正则和实际组数 ✓；匹配到的片段仍然会 `lower()` 后**拼进 key** ✓（「匹配了把 regex 表达式拼到 pattern 上」✓ —
  片段是**匹配到的值**，不是正则原文本身，否则所有同形状的用户名会被并到一起 ✗）。

新增测试：`aRegularExpressionKeepsItsBackslashes`（`\d{4}` / `([a-z]+)\d+` / `@(\w+)\.(\w+)`）、
`aRegularExpressionCanBeAskedForAGroup`（第 2 组、第 0 组、越界报错）、`aQuoteInsideAValueIsStillEscapable`（`'it\'s'`）。

**② 两个演示模式现在真的用上正则**（key 不变，所以数据/节点 id 全不变 ✓）：

- 模式 0：`…+'_'+regex(username, '^([a-z]+)')`（原来是 `alphaPrefix(username)`）→ 片段仍是 `zhang` ✓；
- 模式 1：`…+'_'+regex(emailName(email), '^(\d{4})')`（原来是 `^( [0-9]{4} )`）→ 片段仍是 `1988` ✓，
  且顺带验证了 `\d` 的修复 ✓。

**③ 模式自带「怎么试」**：`FuzzyPattern` 增加三个可选字段（都随模式一起写进 `fuzzy-patterns.json` ✓）：

| 字段 | 作用 |
|---|---|
| `example` | 一段样例 context（对象），编辑器与 UI 示例按钮都用它 —— 模式自带可跑的例子 ✓ |
| `demoTitle` | 这个例子展示什么（例如 `pattern -> ring -> payments -> receiver`） |
| `demoSteps` | 跟在 `g.V().fuzzyLink(id, ctx)` 后面的步骤（缺省 `.out('FuzzyLinkedTo')`） |

**④ UI 示例按钮**：`GET /api/v1/fuzzy/demo?graph=` 返回第一个**带 example 的启用模式**的
`{patternId, title, context, key, linkVertexId, query}`（query = 链接步 + `demoSteps` ✓）；
主页工具栏新增图标按钮 `#fuzzyBtn`（与「示例」「图管理」同组 ✓）：**只在当前图有这类模式时才显示** ✓，
点击即把 query 填进查询框并执行（Cypher 模式下自动切回 Gremlin ✓）。i18n zh/en ✓；
`ProxyController` 转发 `/api/fuzzy/demo` ✓。

headless Edge 实测（payment-graph，模式 0 的 demoSteps 是完整链路）：按钮可见 ✓、tooltip 带 title ✓、
点击后查询框填入 `g.V().fuzzyLink(0, '{…}').out('FuzzyLinkedTo').outE('Payment').otherV()` ✓、
画布 **8 个点 / 18 条边** ✓（= link + 6 个团伙账户 + 收款方；6 条 FuzzyLinkedTo + 12 笔 Payment ✓）。

测试：`graph-fuzzy` 34 项（新增 example/demo 往返、模式自带例子能算出 link、无例子时不猜 ✓）、
`FuzzyControllerTest` 14 项（新增 `/demo` 两个 ✓）。curl 实测：
`{"patternId":0,"title":"pattern -> ring -> payments -> receiver","key":"mail.ru_86_10.20_zhang","query":"g.V().fuzzyLink(0, '{…}')…"}` ✓。

### 35.9 regex 的语义改为「形状」而不是「抽值」（同日追加，按用户反馈）

**改动的理由**：抽值会让 `zhang1988` 和 `zhang1979` 关联 ✓，但同样满足「字母+数字」形状的 `xia1999`
落在自己的节点上 ✗ —— 正则想表达的其实是「**形状**」，所以形状才是片段。

- **`regex(field, 'pattern'[, group])`（改语义）**：**要求**值满足该形状（不满足 → 整个模式不生效 ✓），
  但写入 key 的片段是**形状本身**：`re_<可读形状>_<sha256 前 8 位>` ✓
  —— 可读部分让人看出这个片段代表哪个子模式 ✓，哈希后缀保证两个归一化后相撞的形状（`^([a-z]+)` 与
  `^[a-z]+` ✓）仍然是两个片段 ✓（`twoShapesThatNormaliseAlikeStayApart` 断言这一点 ✓）；给了 group
  只做**校验**（越界报错 ✓），不改片段 ✓。
- **`regexValue(field, 'pattern'[, group])`（新函数）**：原来的语义 —— 把**匹配到的值**（有捕获组取第一组 ✓
  或按 group 取 ✓）作为片段 ✓。
- 演示模式：模式 0 用 `regexValue(username, '^([a-z]+)')` ✓（key 不变 → `mail.ru_86_10.20_zhang` ✓，
  两圈数据与既有断言不受影响 ✓）；模式 1 用 `regex(emailName(email), '^\d{4}')` ✓（形状 ✓，
  key 变成 `10.30.40_621700_re_d_4_8aafc7c1` ✓，仍然是 1 个节点 4 个成员 ✓）。

真实 Aerospike 实测（形状语义生效 ✓）：

| 查询 | 结果 |
|---|---|
| 模式 1 + 种子 context（`44100101@qq.com` / `10.30.40.11` / 卡 …9001） | 节点 `fl_1_10.30.40_621700_re_d_4_8aafc7c1` + 4 个成员 ✓ |
| 模式 1 + **全新** context（`99990101@qq.com` / `10.30.40.99` / 卡 …9099） | **同一个节点** ✓ —— 形状相同就关联 ✓（这就是用户要的） |
| 模式 0 + 全新 context（`xia1999`） | 算出自己的 key（`mail.ru_86_10.20_xia`）→ 无成员 → 空 ✓ 与 `zhang*` 不混 ✓ |

### 35.10 UI 两处调整（按用户反馈）

1. **模糊关联图标先隐藏**：`app.js` 增加开关 `SHOW_FUZZY_EXAMPLE = false`（一行即可恢复 ✓），
   `refreshFuzzyButton()` 直接返回、按钮保持 `hidden` ✓；`/api/v1/fuzzy/demo` 接口与模式里的
   `example`/`demoSteps` **保留** ✓（仍被测试覆盖 ✓）。
2. **图实例下拉宽度 = 原来的 3/4**：`#graphSelect { width: 238px; }`（实测 317px → **238px** ✓）；
   headless 实测同时确认：查询行仍然同一行 ✓、不重叠 ✓、页面无横向滚动 ✓、模糊按钮 `hidden: true` ✓。

---

## 36. 分布式图算法（Gemini 复刻）：`graph-gemini`

依据 Gemini（Zhu et al., **OSDI '16**）与其官方实现（`thu-pacman/GeminiGraph`，C++/MPI）在本项目里做的
Java 复刻：**每个 worker 持有图的一个分片（点+边）并在内存中计算，worker 之间用 HTTP 交换消息，
既能在本进程起（每个 worker 一个端口），也能作为独立程序启动（对应 YARN/脚本拉起）**。

### 36.1 论文里照搬的部分

| Gemini 原文 | 本实现 |
|---|---|
| **chunk-based 分区**：点数组按 **连续 chunk** 切开，一个 chunk 一个 worker；边按下面两条规则**存两份** | `GeminiPartitioner.chunk`：`cut[i]` 单调递增的连续区间（`Plan.ownerOf(v)` 一次范围判断 ✓，点状态数组用全局 dense id 直接索引 ✓） |
| 稀疏（push）边集 `E^S_i = {(src,dst) | dst ∈ V_i}`，稠密（pull）边集 `E^D_i = {(src,dst) | src ∈ V_i}` | `Partition.sparse/dense` ✓：`sparse` = 目的地落在本 chunk 的边（本 worker 持有它的**出边**，即 mirror），`dense` = 源落在本 chunk 的边 ✓；测试断言「每条边两份、每份的端点归属正确」✓ |
| **locality-aware chunking**：按 `α·|V_i| + |E^D_i|` 平衡（α 默认 `8·(p-1)`） | 同式 ✓（两遍细化：先按点数切，再用前缀和把切点挪向平衡位置 ✓）；测试对比「只平衡边」的退化配置 ✓ |
| **稀疏/稠密自适应**：`sparse = activeEdges < |E|/20`（Ligra 的规则，源码 `core/graph.hpp` 原文） | `GeminiWorker.sparseMode(activeEdges)` ✓，`activeEdges` = 活跃点**全局出度**之和（由各 worker 上报后由 launcher 汇总 ✓），每个 superstep 全局选一次模式 ✓，模式序列在 `Stats.modes` 里可查 ✓ |
| **signal/slot 抽象**：`sparseSignal/sparseSlot` 与 `denseSignal/denseSlot` + `reduce` | `GeminiComputation` 接口 ✓（`initialValue/startsActive/sparseSignal/sparseSlot/denseSignal/denseSlot/aggregate/share/converged`）；`sparseSlot` 拿到的是**本 worker 为某个 mirror 持有的那些出边**（论文里 mirror 走本地出边的语义 ✓），`denseSignal` 只读**本 worker 拥有的源**（论文的 pull 语义 ✓） |
| **mirror/master**：点由一个 worker 拥有，其它 worker 只持有它的**边** | `WorkerLayout`: `owns(v)` / 每个 mirror 的 owner（`mirrorOwner` ✓）/ `pushTargets(v)` = 「拥有 v 出边的 worker 集合」（消息往哪发 ✓ —— 这是论文 ring 调度的 `send_buffer[i]` 在 HTTP 上的对应物 ✓） |
| 两个索引增强：**bitmap 辅助的 CSR**（稀疏模式跳过没有本地出边的 mirror）、**doubly-compressed CSC** | `WorkerLayout.hasOutEdges(v)`（一条位测试就丢掉无关消息 ✓）与 `denseVerticesInOrder()`（只列出**有**本地入边的点 ✓） |
| **BSP + 每 peer 批量消息**（原文按 partition 组的 ring 收发） | 一个 superstep 两半：`exchange`（每个 worker 把本轮消息**按 peer 批量** POST 到对端 `/gemini/messages`，对端先**缓存**不阻塞 ✓，自己那份直接本地投递 ✓）+ `compute`（应用到达的消息 + 本地密集计算 ✓）；launcher 顺序驱动，所以不存在互相等待 ✓ |
| **mini-chunk = 64 点** + 工作窃取 | `GeminiWorker.MINI_CHUNK = 64` 常量 ✓（本地边处理以 64 点为粒度分派 ✓；一个进程=一个 worker，所以没有 socket 级子分区 ✓ 文档里说明为简化点） |
| 五个应用（PR/CC/SSSP/BFS/BC） | `GeminiAlgorithms`：**pagerank、louvain、cc、sssp、bfs** ✓（BC 未实现 ✗；Louvain 是本项目加的分析算法 ✓）；PR/SSSP 的 sparse+dense 两半都写 ✓（与官方 `toolkits/*.cpp` 同构 ✓），Louvain 只用 push ✓（论文允许只提供一种模式 ✓） |

### 36.2 与论文不同的地方（都在代码注释里写明）

1. **传输层**：MPI → HTTP/JSON（每个 worker 一个 JDK `HttpServer` ✓，零依赖 ✓）。批量消息、ring 顺序的语义保留，
   但没有做 **计算/通信重叠**（论文里通信线程与计算线程并行 ✗）—— launcher 顺序驱动，一个 superstep
   的 exchange 全部返回后才 compute ✓（简单、无死锁 ✓）。
2. **one process = one worker**：NUMA/socket 级子分区与 socket 内共享内存 ✗（论文的另一半是单机多核优化 ✓）。
3. **稠密模式不做镜像值广播** ✗：论文里 pull 从「本 worker 拥有的源」出发 ✓，部分聚合发给目的地 owner 合并 ✓ ——
   所以**不需要**保存远端点的值副本（我的 `WorkerState.values` 只对拥有的点有效 ✓）；这一点是刻意简化的 ✓
   （少了镜像值数组与同步 ✓），代价是 dense 模式下**每条入边贡献都被 partial 消息承载** ✓（和论文的语义一致 ✓）。
4. **Louvain 的收敛细节**（论文没有这个算法）：一轮内所有点同时决策会互相「追标签」✗ → 采用
   **只向更小 id 的社区合并**（社区以最小成员命名 ✓，与串行更新的结果一致 ✓ 且 id 单调下降 → 必然终止 ✓）；
   另加**从第一轮起就下发全局社区权重** ✓（否则第一轮没有分辨率惩罚 → 过度合并 ✗）和**确定性 tie-break**（按社区 id 升序 ✓）。
5. **本地引用实现**用于对照：`GeminiClusterTest` 里手写单进程 PR/CC/BFS 参考实现，逐一较值 ✓。

### 36.3 这一轮踩到并修掉的真 bug（每条都有测试守住）

| 现象 | 根因 | 修法 |
|---|---|---|
| 4-worker 的 CC 只传播一跳就收敛、PR 值只有参考值的 1/4 | **对端 partial 被覆盖**：`inbox.putAll` 以点为键 ✗，一个点有多个源分片时会丢掉只剩最后一个 partial | 收件箱改成 `点 -> List<partial>` ✓，`compute` 对**每个** partial 调用一次 `denseSlot`（min/sum/map 合并由算法自己决定 ✓） |
| PR 值变成 `1 + 上一轮值` | 累加直接写在**当前值**上 ✗（论文是 `next` 累加后再 swap ✓） | `WorkerState` 增加 **pending 缓冲** + `commit()`（superstep 末尾一次 swap ✓） |
| CC 的 `(long) message` 抛 `ClassCastException` | 消息/值过了 **JSON**：`Long` 可能变 `Double` ✗ | 算法一律用 `asLong/asDouble(Number)` 读 ✓（并让整数消息在 wire 上保持整数 ✓） |
| BFS 的 dense signal NPE | 读了**目的地**的值 ✗（目的地可能是 mirror，值不在本 worker ✓） | dense signal 只读本 worker 的源 ✓（是否已访问由 owner 的 slot 判断 ✓） |
| Louvain 社区数爆炸、`totalWeight` 每轮 ×4 | launcher 把 **所有** aggregate 键**相加** ✗（`totalWeight` 是全局常量 ✗） | 只对 `community:*` 求和 ✓，其余键原样传递（文档写明 ✓） |
| 图比 worker 少时 `ArrayIndexOutOfBounds` | 切点搜索窗口可能出现 `high < low` ✗ | `positionOf` 夹紧窗口 + 切点单调化 ✓ |
| **跑不完（1800s 超时）** | 每次请求都 `HttpClient.newBuilder()` ✗ → 上千个 selector 线程与连接池 ✗ | 全进程**一个共享 client** ✓ + worker 线程池按核数放大（exchange 处理里会嵌套请求 ✓） |
| 集群测试里「拿不到 worker」而只有连接被关闭 | handler 里抛异常时连接被直接关掉 ✗ | 所有端点包一层 `answer(...)` → 500 + `{error, exception, where}`（**带 worker 侧栈帧** ✓，分布式里没有别的线索 ✓） |

### 36.4 Gremlin 的 `DIST` 形式

* `g.V('135','Account').bothE('Payment').otherV().pagerank(DIST, 10)` ✓（实测见下）
* `g.V(ids,'Account').cc(DIST)` / `louvain(DIST[, iterations])` / `sssp(DIST[, rootId])` / `bfs(DIST[, rootId])`
* 语义：**只有 DIST 才用分布式实现** ✓；没有配置 cluster 时**直接报错**（明确说不会退回单机 ✓）。
  不带 `DIST` 时走原来的内存实现（pagerank/louvain/cc ✓；sssp/bfs 只有分布式形式 ✓，会明确报错 ✓）。
* 计算对象 = **遍历已经到达的子图**（`GeminiGraph.of(graphQuery, scope, directed)` ✓）：顶点来自当前 traverser
  （点，或边的另一端 ✓），边类来自经过的边（`bothE('Payment')` → 只算 Payment ✓），去存储读诱导子图 ✓ ——
  与内存版算法步**同一张图** ✓，可以互相校验 ✓。
* **已知限制（下一步）**：`g.E('payment').pagerank(DIST)`（**空种子 + 只给边类** = 全图某类边 ✗）还不支持 ✗ ——
  它需要一个「按类扫全图」的端口（`graph-query` 目前只有按 id 读 ✓；这与第 30 节「按类型计数需要扫描计划」是同一件事 ✓）。
  现在需要用**点种子**写法（上面的形式 ✓）。

### 36.5 实测（4 个 worker，本机不同端口）

* 单元/集成：`graph-gemini` **26 项**（分区/双表示/镜像/模式规则/直接执行/HTTP/4 worker 集群/子进程 worker ✓）、
  `GremlinDistStepsTest` **12 项**（DIST 五种步、无 cluster 报错、非 DIST 参数报错、根不在子图报错、内存版仍在 ✓）
* 集群跑（16 点、40 边；4 worker）：CC 7 supersteps 收敛 ✓、PR 20 轮与单进程参考值逐一相符（1e-6 ✓）、
  SSSP/BFS 深度一致 ✓、Louvain 第一轮收敛（clique 一个社区 ✓）
* **真实 payment-graph 走 HTTP API**：`g.V('135','Account').bothE('Payment').otherV().pagerank(DIST, 10)`
  → **50 个点**、分数和 2,509,590、**2.8 秒**（含从 Aerospike 读 50 条 Payment 边 + 4 worker 加载 + 10 轮 ✓）
* 服务启动即起 4 个 worker：`curl 127.0.0.1:19100..19103/gemini/status` 全 200 ✓
  （`phoenixgraph.gemini.workers/first-port/mode` 可配，`mode=process` 时每个 worker 是独立 JVM ✓，
  实测 `GeminiProcessWorkerTest` 2 个独立进程跑通同一个环 ✓）

### 36.6 代码地图

```
graph-gemini/
  GeminiGraph            输入图：dense id 映射、边表、从 GraphQuery 读诱导子图（Scope：点、边类、权重属性）
  GeminiPartitioner      chunk 分区（α·|V|+|E^D| 平衡）+ 每分片的 sparse/dense 边集
  GeminiLauncherLayout   每个 worker 的 payload：拥有的点、双向边、全局出度、镜像 owner、push 目标
  WorkerLayout           一个 worker 的 CSR(稀疏,bitmap)/CSC(稠密,压缩) + peer 表
  WorkerState            按全局 dense id 的取值数组 + pending 缓冲（next/commit）+ 活跃集
  GeminiWorker           exchange/compute 两半 + 聚合 + 统计
  GeminiWorkerServer     JDK HttpServer 端点：status/active/layout/exchange/messages/compute/values/aggregate/share/halt
  GeminiWorkerMain       独立 worker 程序（--worker/--port/--workers/--load）
  GeminiWire/GeminiClient wire 格式 + 一个共享 HttpClient
  GeminiCluster          启动（LOCAL/PROCESS）、装载、驱动 superstep、汇总结果与统计
  GeminiAlgorithms       pagerank/louvain/cc/sssp/bfs（signal/slot 形式）
graph-query-gremlin/
  GremlinDistAlgorithms  DIST 标记解析 + 子图 scope + 调 cluster
  GremlinGraphQuery      geminiCluster(...) + 五种算法的两种形式注册
graph-http/
  StorageConfig          geminiCluster bean（workers/first-port/mode）
  QueryService           engine.geminiCluster(...)
```

---

## 37. 从「一个类」开始查：扫描端口，OLTP 与 OLAP 分开

用户的要求：**基于 rocksdb-edge 版本**实现

```groovy
g.E('payment').pagerank(DIST)
g.V('Account').filter(accountStatus.equals('ACTIVE')).bothE('payment').cc(DIST)
```

即「从边开始遍历」与「从点开始遍历 + filter」，并明确：**Aerospike 定位 OLTP，RocksDB-Edge 定位 OLAP**。

### 37.1 缺口在哪：查询引擎只会「按键读」

`g.V('135','Account')` 之所以能跑，是因为引擎手上有一串 id，`GraphQuery` 的每个方法都以「一个键」为参数；
`g.E('payment')` 没有任何键可以传（一条边的身份是「两个端点 + 创建时间」），
所以第二种写法此前一律被解析器拒绝（`g.E('...')` 连头都不认）。这与第 30 节「按类型计数需要扫描计划」是**同一个缺口**。

### 37.2 解法：能力方法（capability），而不是契约（contract）

- `GraphQuery` 新增 **`supportsScan()`**（默认 `false`）+ 三个可选方法：
  `scanVertices(type, props, max)` / `scanEdges(class, props, max)` / `countScanEdges(class)` / `countScanVertices(type)`
  （默认抛 `QueryException`，说明「本 store 不能扫」）。
- 与第 34 节的 `supportsEdgeReadPushdown()` 同一套路：**默认 false ⇒ 既有后端零改动**，谁有能力谁 override。
  本次只有 **RocksDB-Edge** override（`true`）；Aerospike 与 RocksDB-Inline 仍是「只按 id 读」。
- 遍历在**开始之前**就问能力：不能扫 → 报「`g.V('Account') starts from a whole type, which
  AerospikeGraphQuery cannot read: a keyed store answers by id. Scan a type on a store whose tables are
  ordered ranges (the RocksDB-Edge scheme is the analytical one), or name the ids instead.`」
  （把「该用哪个 store」写进错误信息里，因为这才是用户真正需要知道的事）。

### 37.3 RocksDB-Edge：一次迭代器 = 一个类

- 点表本来就是 `hash(id):id` ⇒ `scanVertices` 遍历一个 column family，id 从**键**里来（不读值），
  属性按投影解码（`RocksDbVertexTable.scan/count`）。
- 边表每个类一个 CF，键是 `hash(owner):direction:owner:createdTime:edgeId`，
  一份边**存两行**（源侧 OUT、目的侧 IN；无向边两端各一行 direction=0）。扫描只保留：
  - **有向类**：`direction == OUT` 那一行（一条边一行，不用读值就能判定）；
  - **无向类**：`direction == 0` 且 `owner <= peer`（两行互为镜像，谁也不知道谁是源，
    于是约定 id 小的那端持有——自环只有一行，恒保留）。
- **owner 怎么从键里读出来**：键里 id 与 edgeId 都是变长，中间切不开；但行的**值**里有 createdTime，
  它的 8 个字节在键里唯一出现一次 ⇒ owner = 方向字节与这 8 字节之间、edgeId = 之后的部分；
  再用 `hash(owner)` 与键前缀核对 ⇒ 这个切分是**被验证过的**唯一可能（`RocksDbKeys.edgeKeyParts`）。
  这比在值里再存一份 owner 好：不动磁盘格式、不需要重导数据。
- owner 读回来是文本，而 store 的其它读都返回调用方的原始类型 ⇒ 扫描按**端点声明的类型**还原
  （`LONG → Long` 等；无向类若两端类型不同，用「peer 不是哪一端」来判断）。

### 37.4 Gremlin：两种新起点

- 解析器：头从 `g.V(` 放宽为 `g.[VE](`；
  `g.V('Account')`（**一个参数 = 点类型**，不是 id）→ 类型扫描；`g.E('Payment')`（一个参数 = 边类）→ 类扫描；
  `g.V()` 仍是「无起点」（fuzzyLink 那类自己产点的步骤用）。
  `g.V(1, )` 依然报错（结尾逗号说明名字漏了，不能被当成「类型 1」）。
- 起点扫描在 `GremlinGraphQuery.start(...)` 里执行，投影沿用同一套「后继步骤需要什么」的计划：
  `g.V('Account').filter(accountStatus.equals('ACTIVE'))` 只读 `accountStatus`；
  纯 `count()` 时不需要属性，但扫描没有 id 可退回，所以仍走一遍记录（投影为空 = 全属性）。
- **`g.E('class')` 的边带着两端**：`GremlinEdge` 增加 `sourceVertexId`（仅扫描来时有值），
  `otherVertexId` 仍是「远端」，于是 `otherV()/values()` 与边跳之后完全一致，
  而算法步骤能拿到整条边（之前只有远端 ⇒ `bothE(...).cc(DIST)` 会漏掉起点那批点）。
- 顺手补上的一处**真 bug**：点跳产生的边此前把「这是谁的边」丢在扁平化里 ⇒ 算法步骤只能看到邻居。
  现在 `readEdgesByVertex` 保留「哪个点读出来的」并写进 `GremlinEdge.sourceVertexId`，
  于是 `g.V('Account').filter(...).bothE('Payment').cc(DIST)` 的答案里**包含那些活跃账户本身** ✓。

### 37.5 分布式步骤的两个修（都是跑真数据才暴露的）

1. **PageRank 没有归一化**（第 36 轮遗留）：`sparseSignal` 推的是「整个值」而不是「份额」，
   `result` 用的是 `1-d+d*sum` 而不是 `(1-d)/|V|+d*sum`，且**没有任何出度除法** ⇒
   真数据上 4 worker × 10 轮 = 3.7e6（内存版 sum=1.0）。修法：
   - 推/拉都除以**全局出度**（`WorkerState.outDegree` 取自 layout，因为一个分片只持有该点「目的地在自己这边」的那部分出边）；
   - 累加型算法（`accumulative()`，PageRank 为 true）在 commit 前**清空本分片的值**：
     「本轮没有任何贡献」= 0，而不是保留上一轮/初值（这正是 hub 一直等于初值 1.0 的原因）；
   - `result` 需要 |V| ⇒ `GeminiCluster.run` 把 `vertices/edges` 注入 params（launcher 唯一知道全局的地方），
     `GeminiComputation.result(vertex, value, state)` 随之带上 state。
   - 修后：真数据上分布式与内存版 **1e-18** 一致（sum 都是 1.0）；测试里加了 sum≈1 的断言，`GeminiClusterTest` 的
     单进程参考实现也改写成同一公式。
2. **一个「改了代码但行为不变」的坑**：`package`（非 clean）时 Spring Boot 的 fat jar 里嵌的
   `graph-gemini` jar 是**旧的**（fat jar 的 BOOT-INF 残留 + 增量 repackage）⇒ 服务一直跑旧算法。
   判定方法：`unzip -p graph-http/target/...jar BOOT-INF/lib/graph-gemini-*.jar | md5sum` 与
   `graph-gemini/target/*.jar` 对比；修法：**`clean package`**（并先 taskkill 占 jar 的进程）。

### 37.6 实测（真实 RocksDB-Edge 实例，4 个 worker 在 19100–19103）

| 查询 | 结果 | 耗时 |
|---|---|---|
| `g.E('Payment').pagerank(DIST, 10)` | 51 点（全部端点），sum=1.0，与内存版 1e-18 一致 | 372 ms |
| `g.V('Account').filter(accountStatus.equals('ACTIVE')).bothE('Payment').cc(DIST)` | 51 点，**1** 个连通分量，17 个活跃账户都在答案里 | 154 ms |
| `g.E('Payment').sssp(DIST, '135')` | 51 点，hub=0、50 个账户=1 | 69 ms |
| `g.V('Account').count()` / `g.E('Payment').count()` | 51 / 200（走表，不构造对象） | 12–18 ms |
| `g.V('Account').filter(...).count()` | 17 | 12 ms |
| Aerospike 上的 `g.E('Payment').pagerank(DIST)` | 报「starts from a whole type … RocksDB-Edge is the analytical one」 | 24 ms |
| RocksDB-Inline 上的 `g.V('Account').count()` | 同上（只按 id 读） | 6 ms |

### 37.7 语义与限制（写进 README 的那几句）

- **类起点 = 诱导子图**：算法步骤算的还是「遍历到达的点 + 经过的边类」的诱导子图（与内存版同一套），
  所以 `g.E('Payment').pagerank(DIST)` 与「把这些端点 id 全列出来的写法」结果一致 ✓（测试断言到 1e-6）。
- 扫描把 id 还原成**存储类型**（Long），查询文本是字符串 ⇒ 引擎在**建图与算法取根**时按文本兜底匹配
  （`GeminiGraph.of` 的 byText、`GremlinAlgorithms` 的 canonicalIds、`sssp(DIST,'135')` 的 root 查找），
  否则「用查询里的 id 做种子」会看到 0 条边。
- 还没做：`g.V().hasLabel(...)` / 全类 `bothE()` 扫描、`g.V('Account').count()` 的
  「不构造对象计数」改写（store 侧 `countScanVertices/countScanEdges` 已经就绪）、扫描的时间窗口下推
  （createdTime 不是前缀 ⇒ 只能过滤）、Aerospike 侧永远不做扫描（定位就是 OLTP）。

---

## 38. Aerospike 查询路径的准确性与时延：参考读数器、20 条查询、窗口下沉

用户的要求（09-20）：在 **Aerospike 版本**上生成 **10 个一跳**与 **10 个两跳**（一半到点、一半到边）查询，
结合 property/filter 验证**准确性**，然后做**性能测试**展示每条查询的时延，并**总结后面的优化点**。

### 38.1 准确性怎么保证：从存储记录算出来的期望值

新增测试辅助 `AerospikeHopSuite` 里有一个**参考读数器**：它只依赖存储层
（`AerospikeEdgeStore.getEdges` / `AerospikeVertexStore.readVertex`）与 schema（属性 id → 名字），
**不使用查询引擎的任何代码**，在 Java 里手工组合跳、条件、上限、投影与去重，算出「应该是什么」。
20 条查询的期望值都是这么来的，所以：

* 数据变了测试仍然有效（不是写死的数字）；
* 引擎漏边 / 错判条件 / 取错投影 / 截断错一端 ⇒ 报「哪条查询、差在哪里」（多重集比较：忽略顺序、保留重复）；
* 上限类查询额外断言「大小 = k 且都在全集内」。

一跳 10 条覆盖：正向、反向、边、点、投影（边属性 / 顶点属性）、条件下沉、条件 + 计数、计数改写、上限、时间窗口；
两跳 10 条覆盖：Payment 两跳、共用 IP / 共用手机号、两跳 + 投影、双向 + 去重；到边的五条里有中间条件 + `otherV()`、
两跳计数、两跳上限。**20 条全部与存储记录一致**（4 项测试，约 0.7 s）。

参考读数器自己踩的两个坑（写进注释）：判定型边的 `both` 在存储层是两行 ⇒ 参考必须像引擎一样把 OUT+IN 拼起来
（「单个 both 读」只适合无向布局）；`both(...)` 的第二跳也必须按 `both` 展开（一开始只按 out 展开，2.5 偏小）。

### 38.2 时延与流量：指标语义先说清

`AerospikeHopQueryLatencyTest` 对 23 次测量（20 条查询 + `batch(1)` + `parallelism(8)` + 一条
「条件不匹配」对照）各做 5 次预热 + 20 次计时，打印**时延表**与**存储流量表**：

* `decoded` = **真正构造出来的边**（条件/上限/窗口/计数之后剩下的）；
* `filtered` = **走过 bin 但没有构造**的边；
* `kv-calls` = 存储往返次数；`v-items`/`e-items` = 顶点/边记录条数。

安静机器上：一跳 p50 0.54–3.57 ms（中位 0.9），两跳 1.22–5.16 ms（中位 2.3）；同一批查询在机器繁忙时会到 1.5 倍，
**流量列是确定性的**，适合当回归基准。

关键读数与结论：

| 读数 | 说明 |
|---|---|
| 1.1: 4 次调用 / 51 items；2.4: 9 次调用 / 151 items | **每跳的读是批量的**（50 个邻居点 1 次批量 get），扇出没有退化成「每顶点一次往返」 |
| 1.6 / 1.7 的 `decoded=0` | 计数**不构造对象**（`out(...).count()` 连顶点读都省了：v-items=0） |
| 1.5c 的 `decoded=0 / filtered=50` | 条件**在解码循环里判**（这条对照是专门造的：demo 里没有 invoice） |
| 1.8 的 `decoded=5 / filtered=45` | 上限把构造量压到 k，但**仍走完整个 bin** |
| 1.4 / 2.4 / 2.8 的 `decoded=50/150/150` | 顶点跳为了拿「对端 id」把每条边都构造了 ⇒ **最值得做的优化** |
| 2.6 `batch(1)` 4 次 vs 默认 2 次调用 | 批量有效；在 50 源规模上时延无差 ⇒ 默认值即可 |

注意一个容易读错的地方：1.5 的 `decoded=50 / filtered=0` **不是**没下沉，而是 demo 里 hub 的 50 笔付款**全是 SUCCESS**
（独立查询确认 `has('status','SUCCESS').count()=50`、`has('type','invoice').count()=0`）。

### 38.3 优化点（按性价比，逐条对着上表的一列）

1. **顶点跳不要构造边**：加 `readNeighbours(...)`（或让 `EdgeRead` 支持「只读对端」），顶点跳只需
   `(边类, 方向, 对端 id)`。预期 1.4 的 decoded 50 → 0，2.4/2.8 的 150 → 50/100（与计数改写的思路同源）。
2. **时间窗口的可寻址表示**：本轮已把窗口下沉进解码（§38.4），但仍是「走整 bin 再筛」；按时间有序的副表示可把窗口变成前缀读。
3. **`limit(k)` 的有序索引**：现在是有界选择 O(n log k)（1.8 的 filtered=45），有序索引可退化成 O(k)。
4. **条件的选择性**：超大 bin 上 `has(p,v).count()` 可写时维护**摘要 bin**（取值计数）变成 O(1)，或走二级索引/倒排键。
5. **跨查询 bin 缓存**：现在只有执行内缓存（20 次相同查询 = 20 份流量）；按 (顶点, 边类, 方向) 缓存 + `lastUpdatedTime` 失效，
   代价是容量与失效正确性。度量：`kv-calls` 应降到接近 0。
6. **一跳最贵的是邻居顶点批量读**（1.1 3.7 ms vs 1.2 0.85 ms）：最终答案是点且调用方不需要全部属性时，按 **display 属性**
   或由调用方给投影读取（现在是「无后续消费者 ⇒ 读全部」，1.1/1.4 的 v-items=50 对宽顶点是浪费）。会改变 API 返回的属性集合，需与前端约定。
7. **batch/parallelism**：50 源规模上无差别（measured），保持默认；扇出到数百上千时再验证。
8. **度量成本可忽略**：零构造查询的 p50 已 0.55–0.64 ms ⇒ 计数器不在关键路径上；支配时延的是往返次数与条数。

### 38.4 本轮实现的窗口下沉

* `AerospikeEdgeStore.EdgeRead` 增加 `TimeRange window`：`hasWindow()/inWindow(t)/withWindow(...)`，
  `isPlain()` 把「有窗口」也算成不 plain；工厂方法默认 `TimeRange.ALL`（行为不变）。
* `decodeEdges` **先按窗口过滤**（读 raw entry 的第 2 个字段 = createdTime，**不反序列化**），再做条件、
  再做「最新 k 条」的有界选择 ⇒ 窗口外的边根本不构造；`countEdges(..., EdgeRead)` 同样先过滤窗口（计数仍零构造）。
* `AerospikeGraphQuery` 覆盖 `readEdgesWithin(...)` 与 `readEdgesBatchWithin(..., maxResults)`：
  窗口进 `EdgeRead`；批量路径用 `getEdgesBatchFiltered`（一次原生批读 + 每个 bin 内联窗口）；无窗口时与原路径一致。
* **语义**：`createdBetween` 放在**跳之前**才能收窄读（窗口在 bin 读取时已知）；放在跳之后只能是对已读内容的报告。
* 测试 `AerospikeEdgeWindowPushdownTest`（自带 scratch set `payment_window_probe`）：3 条相隔一天的边，
  窗口 [T0, T0+day] 解出 2 条、走过 1 条（`EDGE_DECODED`+2、`EDGE_FILTERED`+1），`TimeRange.ALL` 仍 3 条；
  查询层断言「1 ms 窗口只剩第一条」。demo 数据所有边同一时间戳 ⇒ 时延上看不出差别，所以这条验证放在自造数据上。

---

## 39. 子图必须与回答一致：被丢弃的点要连边一起丢；IP/Phone 的 id 改成值

UI 报的六个问题归到三个根因：一个引擎 bug（子图画多了边）与两个数据模型问题（关联只写一半方向、IP/Phone 用数字 id）。

### 39.1 引擎：子图 = 被保留的元素，而不是被读过的元素

复现：`g.V(1014,Account).both(Payment).limit(3).count()` 回答 3，但子图 8 点 / 7 边。

原因：顶点跳读的是**起点那一个 bin**（1014 的 7 条边全部读出来，这是存储模型决定的），`limit(3)` 只丢掉多出来的**点**
（`dropElement` 只从 `subgraphVertices` 删），而 `recordEdges` 记下的 7 条边一条没删；构建子图时又会把
「只有边才知道的对端」补回成点 ⇒ 画出来的图比回答大。

修法（都在 `GremlinContext`）：

1. `dropElement(vertex)` 同时从 `edgeEndpointVertices` 删除该点 —— 它已不是遍历的一部分，
   不能再作为某条存活边的对端被补回来；
2. `buildSubgraph()` 裁剪：一条边只有在**两个端点都还在**（活着的点，或某条存活边的对端）时才进入子图；
   随后只物化**存活边**引用到的对端（避免被 filter 掉的边留下悬挂对端）；
3. 端点比较统一按**文本**（查询里的 id 是文本，扫描返回的是存储类型），与 37.7 的 id 处理一致。

不变式：**子图是被保留的元素（点 + 与它们相连的边）**，不是「被读过的元素」。没有 cap 的同一查询仍是 7 边（回答也是 7），
所以这个规则不会削减正常查询的画布；它同时修好了「has/filter 丢掉一个点后，它的边还画在图上」。
回归用例：`GremlinGraphQueryTest.aCappedVertexHopDrawsOnlyTheEdgesOfTheVerticesItKept` 与 `aFilterThatDropsAVertexDropsItsEdgesToo`。

### 39.2 数据：一对关联写两个方向

schema 为 IP/Phone 各准备了**两个类**（`AccountToIP` + `IPToAccount`、`AccountToPhone` + `PhoneToAccount`），
目的是两端都能直接遍历；但 Aerospike 的 demo seeder 只给每对关联的**一个方向**写了反向边
（`ipa_i` 只对应 ip0、`pta_i` 只对应 ph0）⇒ 按类遍历看到 1 条，UI 的展开 `both()`（所有类）看到 2 条，
用户看到的就是「查询和 UI 不一致」。修法：两个方向都写（两个 IP、两个 Phone 全写；大扇出 hub 150 同理）。
取舍：写入量翻倍，换来 `both()` 与按类查询结果一致（一致性优先）。

### 39.3 数据：IP/Phone 的 id 就是值

IP/Phone 原来自增数字 id（5000+/6000+），真正的值放在属性里 ⇒ 需要维护 id→值 映射，而且**两个类型的 id 空间会撞车**
（6029 既是 Phone 又是 IP），于是「用 6029 当 IP 查不到关联」看起来像 bug。改法：

* schema：4 个 payment 变体里 `IP.idSchema`/`Phone.idSchema` → `STRING`，并同步四个端点字段
  （`AccountToIP.target`、`IPToAccount.source`、`AccountToPhone.target`、`PhoneToAccount.source`；Account 一侧仍是 LONG）；
* demo 数据：IP id = 地址（`10.20.31.15`），Phone id = 号码（`+86-138-10014`），属性值与 id 同值
  （仍是 display 属性），`cnCode` 从号码前缀取；
* 两个生成器一起改：`QueryApiIntegrationTest`（Aerospike demo）与 `PaymentGraphRocksDbLoader`（RocksDB 实例 + 一致性测试的同一份 demo）。

### 39.4 demo set 换名：http_demo_v1 → http_demo_v2

这台机器上 Aerospike 的 truncate 不可用（代码里已写「truncate 可能被禁用」），旧 set 里留着数字 id 的老记录，
会让「用 6029 当 IP 查询」这类问题继续出现。因此把 demo 播到**干净的新 set** http_demo_v2（application.yml 与测试默认值一起改），
并重新播种 demo + 模糊关联数据。旧 set 不再被引用。

### 39.5 复验

```text
limit(3).count()                    ->  answer=[3]   子图 4 点 / 3 边
both(PhoneToAccount).count()        ->  [2]  = UI 双击 both() 看到的 phone 数
both(AccountToIP).count()           ->  [2]  = both(IPToAccount).count()
g.V(10.20.31.15,IP).out(IPToAccount).values(firstName)  ->  [Radia]
g.V(6029,IP) / g.V(6029,Phone) 的 elementMap()          ->  []
```

三个图实例（Aerospike demo、RocksDB-Inline、RocksDB-Edge）都用同一套 schema + 数据复验，RocksDB 两个实例已重灌（151 点 / 400 边）。

### 39.6 遗留

* `g.V(不存在的 id, Type).count()` 仍返回 1（起点的快路径不读记录，见 38 的性能设计）——要修得让纯计数也读种子；
* `payment-graph-as-edge` 的 demo loader 仍写数字 IP/Phone id（schema 已声明 STRING）：不影响功能，但约定不一致；
* 旧 set http_demo_v1 的历史记录可清理。

---

## 40. 顶点跳只读对端；不存在的种子不再计数；seeder 的 id 全部改成值

第 38 节的测量指出三件事：顶点跳为拿一个「对端 id」把每条边都构造出来（1.4 的 decoded=50、2.4 的 150），
纯计数的种子从不读记录（所以不存在的 id 也返回 1），以及只有两个生成器改成了「id 就是值」。这一节是这三项的落地。

### 40.1 邻居读：又一个能力开关

```text
graph-query          NeighbourRef（边类/边 id/方向/对端 id/时间戳）
                     readNeighbours / readNeighboursBatch（默认＝读边再取身份）
                     supportsNeighbourRead()（默认 false）
graph-storage        EdgeHeader（edgeId/peer/createdTime/lastUpdatedTime）
graph-storage-aerospike  getEdgeHeaders / getEdgeHeadersBatch（只读存储值头部，不反序列化属性，不构造边）
graph-query-aerospike    supportsNeighbourRead()=true + 两个方法（方向拆分、最新在前，与读边同序）
graph-query-gremlin      hopVertices 在「边类有名 + 不收集子图 + store 声明能力」时走邻居读；
                         GremlinContext.readNeighboursBatch：去重 + 并行批量 + 独立的邻居缓存
```

**为什么是能力开关**：与本项目既有的 `supportsEdgeReadPushdown()`、`supportsScan()` 一致 ——
默认实现保证任何后端（以及测试替身）仍然正确，愿意优化的 store 才声明；traversal 先问再走。

**为什么 `EdgeHeader` 在 `graph-storage`**：`graph-storage-aerospike` 看不到 `graph-query`（分层同 `StoredEdgeValue`→`EdgeQueryResult`）。

**为什么 UI 画图仍读边**：子图里的边要带属性（边浮窗），所以收集子图时保持原路径，画布内容一字未变；
省下的是纯查询路径（`execute`）—— 也正是第 38 节基准测的那条路径。

**指标语义**：邻居读刻意不增加 `EDGE_DECODED`（它的含义是「构造出来的边数」，不是「读到的边数」）。

效果（确定性流量列）：1.1/1.4 的 decoded 50→0，2.1 12→0，2.4 150→0（kv-calls 9→7），2.5 99→0，2.6 12→9，2.9 3→0；
以边结尾的跳（2.8、1.2/1.3/1.5/1.8/1.10）不变 —— 那些边的对象本身就是答案。20 条准确性用例全过。

### 40.2 种子的存在性：只在「计划根本不读它」时校验

起点默认不读记录（一跳只需要 id，这是性能设计），所以 `g.V(不存在的id).count()` 曾返回 1。
编译期新增 `verifySeeds`：当且仅当计划里的步骤**全是** `count/limit/dedup/batch/barrier`（或没有步骤）时，
起点做一次存在性校验。跳（包括被改写成 `countOutE` 的计数）、投影、算法步骤都会读种子或它的邻域，
缺失的种子自然给出空结果，所以它们保持快路径。

### 40.3 seeder 的 id 收尾

把还会写 IP/Phone 的地方全部对齐到「id 就是值」：`QueryDataSeeder`（含其边端点）与三个 Gremlin 基准，
以及写死数字 id 的 `DirectedEdgeStorageIntegrationTest`（改用 `QueryDataSeeder.phoneNumber(0)` 构造查询与断言）。

### 40.4 未做

* RocksDB 的两个 store 仍用默认实现（读边再取身份）：正确，但没省属性解码 —— 给 `RocksDbEdgeRowCodec` 加一个 header 解码即可；
* UI 画图路径仍构造边（为边浮窗的属性）；
* 第 38 节其余优化点（`limit` 的有序表示、摘要 bin、跨查询 bin 缓存、display 属性投影）仍在。

---

## 41. 两跳 benchmark：时延跟着源的数量走，「按源计数」改成批量计数

第 38 节测的是一跳与少量两跳；这一节是一次更大规模的两跳测量（多种起点、每跳换边类、条件、截断、比率、3 个 batch 值），
它把「计数」这条路径的瓶颈量化了出来，并因此做了一次优化。

### 41.1 设计：24 条遍历 × 3 个 batch

```text
AerospikeTwoHopBenchmarkTest
  24 条 = 20 条计数（10 条第二跳到边 / 10 条到点）+ 4 条返回元素（让 batch 的差异可见）
  每条 3 个 batch：batch(1) / batch(25) / batch(100)，插在第一跳之后的 %BATCH% 位置
  起点：135（50 笔付款）、150（100 账户 × 39 IP + 10 电话）、1014/1025（环上）、一个 IP、一个电话
  形状：每跳各自边类；条件放在第一跳顶点（accountStatus）或第二跳边（status=SUCCESS）；
        otherV() 中转、dedup()、第一跳 limit(5) 截断
  比率：每条配一个分母（通常是第一跳计数），报告 answer/base
  subgraph 大小：计时之外单独收集一次（与 batch 无关），报告顶点/边数
  答案：全部与 AerospikeHopSuite 的参考读数器核对（第 38 节那套）
```

### 41.2 测出来的三条

1. **时延跟着源的数量走**：kv-calls 与 p50 几乎线性相关 —— 小 subgraph（<100 元素）2 次往返 / 2.4 ms，
   中（100–1000）4 次 / 6.3 ms，大（>1000）5 次 / 11.2 ms；未优化时同样的大查询是 **101 次往返 / 18–58 ms**。
2. **batch(n) 之前只影响读、不影响计数**（计数是逐源一次往返 ⇒ 三个 batch 值毫无差别）；
   优化后 batch(1) 41 次、batch(25) 3 次、batch(100) 2 次，p50 10.8 → 4.1 → 4.7 ms。
3. **decoded 语义清晰**：顶点跳恒为 0（邻居读只取头部，第 40 节），边跳等于 limit（如 120、500）。

### 41.3 批量计数（本轮实现）

```text
graph-query        countEdgesBatch(edgeName, direction, vertexIds, filters)   默认＝逐源求和
graph-storage-aerospike  countEdgesBatch(vertexIds, schemaId, dirCode, EdgeRead)  一次原生 batch get + countInRecord
graph-query-aerospike    countEdgesBatch(...)  每个方向一次批读
graph-query-gremlin      GremlinContext.countEdges(...)  无时间窗时按 batch(n) 分组（batches + 并行）
```

`countInRecord` 从 bin 的形状计数（不构边），条件/窗口在 raw entry 上判 —— 与逐源版本语义完全一致，只是把往返合并。
效果：E3（100 个源）101 → 5/2 次往返、p50 18.9 → 5.0 ms；E4 101 → 5/2、12.6 → 3.9 ms；E1（51 个源）51 → 3/2、10.2 → 3.7 ms；
**答案不变**（20 条准确性用例、一致性用例、benchmark 的参考答案全过）。

### 41.4 未做

* 边的**读**（非计数）仍按引擎自带的批处理（X3 的 41 次往返），可以让它显式按 batch(n) 分组；
* 再往下就是服务端聚合（UDF）：目前已经是「一次多键读 + 本地计数」的形态；
* 第 38 节其余优化点（limit 的有序表示、摘要 bin、跨查询 bin 缓存、display 属性投影）仍在。

---

## 42. 两跳 Payment 到点的近线基准：22 条 Gremlin 写法 + 混合负载

§41 是一次「多起点、多 batch」的广度测量；这一节沿深度做一次**同一条两跳语义的多种 Gremlin 写法**的对照，
全部以点结尾（Payment > Payment，最后一跳产点），并加上一轮混合负载当作近线基线。

### 42.1 设计

```text
AerospikeTwoHopPaymentBenchmarkTest
  22 条遍历，全部两跳 Payment、结尾是点（out/in/both 或 otherV 产点；末尾可跟 count/dedup/limit/values/elementMap）
  step 组合：方向、边跳+otherV、has(status) 在边上、filter(accountStatus) 在中间点上、dedup、limit(20)、
           count、dedup.count、跳之间的 batch(10)/barrier(10)、values/elementMap 取点属性
  近线特征：起点覆盖 hub 135 与环上 1000/1014/1025/1049；每条 3 预热 + 12 计时，报分布；
           最后 22 条 × 4 轮 = 88 次混合执行（背靠背）作为工作负载基线
  准确性：每条与参考读数器核对（同样的两跳、条件、去重、截断）
  报告：先打印每条 Gremlin 原文，再给答案大小、mean/p50/p95/p99/max 与 v-items/e-items/kv-calls
```

### 42.2 读数（ms，`http_demo_v2`）

| 计划 | 答案 | p50 | kv-calls | 说明 |
|---|---|---|---|---|
| hub `out>out` | 150 | 6.82 | 5 | 50 个源 ⇒ 5 次往返 |
| 环账户 `out>out` | 9 | 2.75 | 3 | 3 个源 ⇒ 3 次 |
| hub `outE>otherV>out` | 150 | 6.82 | 5 | 同义写法同价（边对象按需构造） |
| hub `outE has(status)>otherV>out` | 150 | 5.42 | 5 | 条件命中全部 ⇒ 不省 |
| 1014 `out>filter(accountStatus=FROZEN)>out` | 0 | 1.93 | 2 | 条件命中 0 ⇒ 只对命中的源展开 |
| 1000 `both>both` | 92 | 4.98 | 7 | 双向翻倍源与边 |
| hub `out>out>count` | 150 | 2.88 | 4 | 计数最省（v-items=0） |
| hub `out>out>limit(20)` | 20 | 3.67 | 2 | 截断在读点之前（v-items=0） |
| hub `out>out>dedup` | 50 | 5.77 | 5 | 去重是内存操作，读不变 |
| hub `out>out>values(firstName)` | 150 | 4.59 | 5 | 取属性要读点 |
| hub `out>batch(10)>out` | 150 | 5.37 | 7 | 显式分批多一次往返（5 批 + 种子 + 一跳） |
| 混合负载（22 条 × 4 轮） | — | — | — | 88 次 369 ms，平均 4.19 ms/次 |

### 42.3 结论

1. **成本模型就是「一跳 = 若干次往返」**：源越多往返越多（50 源 5 次、3 源 3 次），
   同义写法（`out>out` 与 `outE>otherV>out`）成本一致 —— 因为顶点跳不再构造边（§40）。
2. **条件的收益等于它的选择率**：命中 0 ⇒ 第二跳没有源（2 次往返 / 1.9 ms）；命中全部 ⇒ 与无条件同价。
3. **结尾 step 决定最后一步的读**：`count()` 不读点、`limit(k)` 在读点之前截断，
   而 `dedup()/values()/elementMap()` 需要点本身 ⇒ 回到 5 次往返。
4. **批量参数在两跳里收益有限**（7 次 vs 5 次往返，时延持平）—— 它的价值在计数路径（§41）。
5. **近线基线**：这套 22 条计划的混合吞吐是 4.19 ms/次（同一台机器、同一份 demo 数据）。

### 42.4 未做

* 一跳 + 两跳混跑（更接近真实仪表盘：点开看一跳、再展开两跳）还没有；
* `where()` / `order().by()` / `group().by()` 等 step 尚未实现，实现后应补进这套基准。

---

## 43. 每一跳都用 both：同一条两跳语义的方向对照

§42 把两跳 Payment 的各种 Gremlin 写法测了一遍（每跳一个方向）；这一节把**每一跳都换成 `both`**再测同一批形状，
并与单方向写法并排对照 —— 因为「多方向」在这个图上是实打实的规模变化（hub 的第二跳 150 → 350 个到达点，环上账户 9 → 92），
它同时也检验了「顶点跳只读对端」「按源批量计数」在多源场景下的表现。

### 43.1 做法

```text
AerospikeTwoHopPaymentBenchmarkTest.bothQueries()   20 条，全部 both(Payment) x 2 跳、结尾取点
  形状：both/both、bothE+otherV+both、bothE has(status)+otherV+both、both+filter(accountStatus)+both、
        dedup、limit(20)、count、dedup.count、跳之间 batch(10)/barrier(10)、values/elementMap
  与单方向的 22 条同一批：同起点、同预热/计时次数、同混合负载、同样与参考读数器核对（参考也按 BOTH 展开）
  报告新增一张对照表：把每个 (seed, 形状) 的单方向与 both 两种写法并排放（p50、kv-calls、到达点数）
```

### 43.2 结论

1. **换 both 的代价 ≈ 源的数量放大**：环上账户 3 个源（3 次往返、2.3–3.3 ms）→ 7 个（7 次、5.1–5.8 ms），到达点 9 → 92；
   hub 的第二跳翻倍（150 → 350），6.99 → 7.69 ms（5 → 7 次往返）。每多一个源多一次往返，与 §41/§42 的成本模型一致。
2. **同义写法同价**（`bothE > otherV > both` 与 `both > both` 在 hub 上都是 ~8.4 ms / 7 次），
   说明「顶点跳不构造边」在双向场景同样成立。
3. **条件按选择率生效**：hub 的 ACTIVE 命中全部（350 / 8 次往返），1014 的 FROZEN 命中 0（0 个答案 / 2 次往返 / 1.9 ms）。
4. **结尾 step 的规律不变**：count 最省（v-items=0，hub 4.35 ms / 8 次、1014 3.01 ms / 4 次）；limit 在读点之前截断；
   dedup/values/elementMap 要读点（~8 ms / 7 次）。
5. **batch/barrier 仍多花往返**（10 vs 默认 7 次），时延持平；批量参数的收益仍在计数路径（§41）。
6. **近线基线**：一跳方向 4.43 ms/次、每跳 both 5.76 ms/次；按「到达点」归一化后 both 更划算（约 0.02–0.03 ms/到达点 vs 0.03–0.05 ms）。

### 43.3 过程中的一个问题（助手的 bug，不是引擎）

第一版对照里 `both > filter(accountStatus) > both` 期望 350、实测 50：原因是构造 both 变体的助手**漏了第二跳**
（只生成了 `.both('Payment').filter(...)`）。在服务上直接验证得到 350 ✓，修好后 20 条 both 变体全部与参考一致 ——
这正是「每条查询的期望值都从存储记录独立算出来」的价值：少写一跳会立刻暴露。

### 43.4 未做

* 一跳 + 两跳混跑（更接近真实仪表盘）；
* 边读也按 batch(n) 分组、自适应默认 batch 上调（§41.4）；
* `where()` / `order().by()` / `group().by()` 实现后补进这套基准。

---

## 44. 换成 150 这个点（星型 hub）再测一遍

§42/§43 的起点是 hub 135 与环上账户；这一节把**同样的查询换成 150 这个星型 hub**（100 笔付款出去，每个账户 39 个 IP + 10 个电话）。
150 的形状决定了测什么：它的账户之间**没有付款**（`out("Payment").out("Payment")` = 0），它真正有数据的是 IP/电话第二跳（3900 / 1000 条边，demo 里最大的子图）。

### 44.1 做法

```text
1. 把 150 加进 Payment 组的起点列表（P-150 out>out、B-150 both>both），并排进原有 23 条与 21 条 both 变体；
2. 新增 starQueries()：12 条，第二跳换成 AccountToIP / AccountToPhone，形状 = 无条件 / both / outE has(status) / filter(accountStatus) /
   dedup / limit(500) / count / dedup.count / batch(10) / barrier(100) / values(ipAddress)；
3. 答案照旧与参考读数器核对（参考的第二跳按同一类、同一方向展开）。
```

### 44.2 三条结论（150 才看得出来的）

1. **空第二跳很便宜**：P-150 的 Payment 第二跳没有源（answer=0），只花 2 次往返 / 4.13 ms；
   B-150 的 both 第二跳回到 150 自己（100 个答案）要 5 次 / 9.96 ms —— 「有源才花钱」。
2. **上限是最强的杠杆**：同一 3900 条星型查询，`limit(500)` = 2 次往返 / 7.6 ms，完整读取 = 197 次 / 48 ms；
   `count()` = 6 次 / 3.7 ms（§41 的批量计数在这里体现得最明显：100 个源从「逐源一次往返」变成几次）。
3. **`dedup()` 会白白读点，`batch(10)` 帮倒忙，`barrier(100)` 才对**：
   * `dedup().count()` = 197 次 / 38 ms ✗（去重被当成需要全部属性 → 3900 个顶点被读出，其实只要 id）
     ⇒ 明确优化：**`dedup` 不要要求属性投影**（预计 197 → ~6 次、38 → ~4 ms）；
   * `batch(10)` = 392 次 / 64 ms（把 100 个源切成 10 批，边读与点读各乘一遍）✗，
     `barrier(100)` = 41 次 / 26.7 ms ✓ —— 源多时要更少的批，不是更多；
   * 这解释了 §10 里 batch(1) 最慢、batch(100) 最快的方向：批量参数是「合批」而不是「拆批」。

### 44.3 混合负载

```text
MIXED PASS (单方向 23 条 x 4 轮):  92 次 312 ms，平均 3.39 ms/次
MIXED PASS (每跳 both 21 条 x 4 轮): 84 次 480 ms，平均 5.71 ms/次
MIXED PASS (星型 150 第二跳 IP/电话 12 条 x 4 轮): 48 次 1724 ms，平均 35.9 ms/次
```

### 44.4 未做（新增）

* **`dedup` 的投影**：让去重只需要 id（当前会触发 3900 次顶点读）；
* **批量方向自适应**：默认值在「源多、点读多」的星型查询上不如显式 `barrier(100)`；
* 既有遗留：一跳 + 两跳混跑、边读按 batch 合批、`limit` 有序表示、摘要 bin、跨查询 bin 缓存、display 属性投影。

---

## 45. 落地三条建议：dedup 不再读属性、自适应合批、一跳+两跳混合负载

§44 末尾列了三条下一步，这一节做完。

### 45.1 dedup 只按 id 去重，不再要求全属性投影

**根因**：`neededProperties()` 把 `dedup` 与 `elementMap` 一起归进「需要全部属性」→ 结果顶点被全部读出（星型查询 3900 次顶点读），
而 dedup 只需要 id。顺带修掉隐患：原来 `dedup` 用 record 相等性（含属性 map），同一个点以不同投影出现会被当成两个。

**改动**：`GremlinSteps.dedup()` 用 `ElementKey(label, id)` 去重（标量按值、保留首见顺序）；
`neededProperties()` 里 `dedup` 改为「穿过继续找后面的消费者」（不再 all=true）。

**效果**（S-150，p50 ms / kv-calls / v-items）：
```text
out > out(AccountToIP) > dedup       36.35 / 197 / 3900  ->  7.73 / 2 / 0
out > out(AccountToIP) > dedup > count  38.27 / 197 / 3900 -> 6.29 / 2 / 0
星型混合负载                          35.9 ms/次 -> 17.7 ms/次
```
回归：新增 2 个单测（身份去重 + 跳过属性读 / 后面的 values 仍只投影那一个键），query-gremlin 132 → 134 全绿。

### 45.2 自适应批量：默认更少的、更大的往返

§44 发现 `batch(10)` 帮倒忙（392 次）、`barrier(100)` 最优（41 次），而默认停在 20（197 次）：
自适应公式 `fanOut / ADAPTIVE_DIVISOR` 在 DIVISOR=5 时对 fanOut=100 只能得到 20，等于没上提。

**改动**：`DEFAULT_BATCH_SIZE` 20 → 50、`ADAPTIVE_DIVISOR` 5 → 2。因为 `Math.max(batchSize, scaled)` 只增不减，
这个改动只影响 fanOut > 40 的跳；小查询（源少）仍一个批量拿全。

**效果**（S-150，p50 ms / kv-calls）：
```text
out > out(AccountToIP)            48.23 / 197 -> 24.64 / 80   （约 2 倍）
both > both(AccountToIP)          42.64 / 199 -> 26.74 / 82
out > filter(accountStatus) > out 38.45 / 202 -> 27.26 / 82
out > out(AccountToIP) > values   41.24 / 197 -> 23.59 / 80
barrier(100)（对照）              20.45 /  41 （默认已追到很近）
星型混合负载                      35.9 ms/次 -> 17.7–18.8 ms/次
```
回归：一跳时延、两跳 benchmark、两跳 Payment、准确性 20/20 全绿；两个硬编码旧常量的单测已按新值更新。

### 45.3 一跳 + 两跳混合负载

新增 `oneHopQueries()`（8 条一跳，同样与参考核对）并与单方向两跳交替跑混合 pass：
```text
MIXED PASS (one-hop + two-hop dashboard): 124 次（31 条 x 4 轮）309 ms，平均 2493 us/次
```

### 45.4 未做

* 星型默认 80 次 vs barrier(100) 41 次还有 ~4 ms：可考虑同一跳内顶点物化用更大的批（与边读分开）；
* 混跑还可把 both/星型两组也交替进去；
* 既有遗留：`limit` 有序表示、摘要 bin、跨查询 bin 缓存、display 属性投影。

---

## 46. 仪表盘十问：UI/数据/引擎的一致性修复

### 46.1 引擎

* **limit 早停**：`limit` 直接跟在顶点跳后折进跳的 cap（邻居读按源分批、够了就停；边读把 cap 传进 store），
  不再「全量迭代完再截断」。顶点 `has/filter` 仍把 limit 留在后面（条件先跑）。
* **多类 hop**：`out/in/both/outE/inE/bothE('A','B',...)` 按参数顺序走多个类；多类跳不重写为单类计数、不接下推条件。

### 46.2 数据模型（单向 + 共享，demo set `http_demo_v3`）

* 不再写 AccountToIP/AccountToPhone 镜像，只写 **IPToAccount（IP→Account）** 与 **PhoneToAccount（Phone→Account）** →
  修复「UI 双向箭头」「`both()` 与三类之和不等」（#14：99 vs 50）。
* **共享 IP/Phone**：60 个 IP 各连 3–80 个账户、24 个 Phone 各连 2–10 个账户（确定性）；另有 10.99.255.2→3 个账户等显式多账户示例。
* **Phone 显示名**：`phoneNumber` 存国内号段，display = "+86 138-69999"，不再重复 +86。
* 参考读数器/精度套件（20/20）/基准/一致性测试全切到 v3，方向从 `out(AccountToIP)` 改为 `in(IPToAccount)`。

### 46.3 UI

* id 输入框从 `both().both()`（两跳）改为 `both()`（一跳），展示的边与 `both(Payment)` 等直连查询一致；
  `showQueryResult` 每次 `resetGraph()`，新 id 替换旧图。

### 46.4 结果与未做

```text
gremlin 136 / integration 88 / http 69 全绿（293）
新测试 demoSharedGraphViaApi（both()=三类之和、多类 hop 一致、多账户 IP、phone 显示名）
提交 5d85ce0（多类 hop + limit 早停）、0cd295d（单向 + 共享 + 输入框一跳）
```

**未做（改进项）**：display 属性 `$id`（当 display == id 时不存重复 displayName）：
schema JSON 顶点加 `"displayName": "$id"` → `displayPropertyNames()` 返回 `["$id"]`；UI `displayLabel` 直接渲染 id；
IP/Phone 的 schema 改 `$id`（`ipAddress`/国内号属性保留给 `values()`）。

**运维**：服务要看到新数据需重新 `clean package -am` 构建并把 payment-graph 的 set 指到 `http_demo_v3`。

---

## 47. 仪表盘七问：边方向与去重、重复支付、搜索高亮、方向提示、成对边 ID、filter/map 闭包

用户在 UI 上提了七件事：1001 的 IP/Phone 边画成双向且数目翻倍、135/1001 之间同一对端点应有好多笔 Payment、
搜索框高亮乱选、方向写错只给 0 items 不解释、IP/Phone 边要按 pair 做 ID、以及 TinkerPop 风格的
`filter { ... }` / `map { ... }` 闭包（外加 `it.get().value('x')`）报错。逐条如下。

### 47.1 边方向与去重：数据只写一个方向，UI 自然画单向（#1）

第 46 节已把 Aerospike demo 改成单向（§46.2），但服务还指着 `http_demo_v2`，而 RocksDB 的两个实例
仍由 `PaymentGraphRocksDbLoader` 写入 `AccountToIP` + `IPToAccount`（以及 Phone 的一对）镜像边 ——
于是同一个 link 有两条反向的边，UI 画成双向箭头，`both()` 把两条都数进去（1001：15 条 = 11 条 link + 4 条镜像）。

修法是把"数据只写一个方向"补齐，而不是让 UI 猜哪条是镜像：

* `application.yml` 的 payment-graph（Aerospike）指到 `http_demo_v3`（primary 与两个 RocksDB 图都改）；
* `PaymentGraphRocksDbLoader.paymentEdges` 只写 `IPToAccount` / `PhoneToAccount`（200 payments + 50 + 50 = 300 条边，
  原来是 400）；schema 里那两个镜像类保留声明（老数据的 bin 名不受影响），只是不再有数据；
* v3 seeder 里显式补上 `+86-138-69999 -> 1001`，于是 1001 既有 IP 也有 Phone 的单向 link（用户就是拿它举例的）。

实测（服务在跑，`g.V('1001','Account').both()`）：`edges: 54 {Payment: 37, PhoneToAccount: 1, IPToAccount: 16}`，
没有 `AccountTo*`；两个 RocksDB 图同样 `{Payment: 7, PhoneToAccount: 1, IPToAccount: 1}`、镜像 0 条；
`both().count() == both('Payment','IPToAccount','PhoneToAccount').count()`（都 54）。

### 47.2 同一个 <source,target> 上的多笔 Payment（#2）

`QueryApiIntegrationTest.seedSharedGraph` 新增一组"重复支付"：135 → 1000/1001/1002/1003（4 对，各 5–8 笔）、
1001 → 1002/1008/1014（3 对，各 5–10 笔），共 55 笔，id 是 `pay_rep_<src>_<dst>_<k>`、状态按 `k % 3` 混 PENDING/SUCCESS。
hub 135 的出边从 50 变成 **76**（`PaymentGraphConsistencyTest` 的 `assumeTrue` 与断言同步改成 76）；
`AerospikeHopSuite` 的期望值本来就是参考读数器现算的，所以只有描述文字里的"50"变旧。

### 47.3 搜索高亮：逐字段匹配，不再把整个元素拼成一串（#3）

`applySearch` 原来把 label + id + 所有属性值**拼成一个字符串**再做子序列匹配，于是
"linus" 能在 "account 1000 … personal … savings" 里凑出 l-i-n-u-s，"ada" 能在 "addr-1000 personal" 里凑出 a-d-a，
"10018" 能在边 "1001 … 1008" 里凑出 1-0-0-1-8 —— 全都高亮。

现在 `elementFields` 给每个元素一组字段（label、id、每个属性值、边再加两个端点 id），
`fieldMatches` = 字段包含 token **或** token 是**该字段**的子序列（"pmt"→"payment" 仍然可用），
多个 token 可以落在不同字段（"hopper barbara" ✓）。用 node 复算过用户的四个例子（Linus/Ada/10018/1001）全对。

### 47.4 IP/Phone 边的 id 就是端点对（#5）

`PhoneToAccount` / `IPToAccount` 的边 id 从 `sip_3_7` 这类序号改成 **`<phone|ip>:<account>`**，
schema 里这两个类的 `idSchema.type` 从 `LONG` 改成 `STRING`（只有描述性的用处，存储层不看它）。
于是同一对端点再写一次就是同一个 id（覆盖，不是新增），§47.1 的 "link 只算一次" 在数据层面也成立。

### 47.5 方向写错时告诉用户该用 out 还是 in（#4）

`g.V('+86-138-10008','Phone').in('PhoneToAccount')` 以前安静地返回 0 items。新增
`GremlinGraphQuery.schema(GraphSchema)` + `checkHopDirections`：从声明的起点类型出发，沿单类 hop 维护
"当前可能的点类型"，若某跳的方向与该类声明的 source/target 不相交就**报错并给出方向**：

```
in('PhoneToAccount') cannot match Phone: PhoneToAccount runs Phone -> Account,
so use out('PhoneToAccount') or both('PhoneToAccount')
```

只在 schema 已知时检查（测试里直接 new 出来的引擎没有 schema → 行为不变）；`both()`、无类名、多类、
`otherV()`/`nearest`/`circle` 之后（类型未知）都不检查，所以只会拦下必然为空的查询。

### 47.6 `filter { ... }` / `map { ... }` 与 `it.get().value(...)`（#6、#7）

两个 bug 的根因不同：

* **`filter( it.get().value('accountStatus').equals('Active') )` 报"找不到 property accountStatus"**：
  表达式被当成裸 key（`it.get().value('accountStatus')` 整串成了一个属性名），投影计划拿它去 schema 里查。新增
  `GremlinElementExpression`，先把 Groovy 取值规范化成引擎语法：`it.get().value('k')`→`k`、
  `it.get().label()`→`@label`、`it.get().id()`→`@id`（两个伪 key 由元素本身回答，且不进属性投影）。
  顺带把字符串比较改成**忽略大小写**（`GremlinSteps.matches`）：`equals('Active')` 命中 `ACTIVE`。
* **`filter { ... }` / `map { ... }` 报 "Malformed step (missing '(')"**：解析器只认 `(`，
  而且 `parse` 会先把**任何** `{` 当成"结尾 logic 块"吃掉，`.filter { … }` 就只剩 `.filter`。现在
  `parseSteps` 同时接受 `(` 与 `{`（闭包体整体作为一个参数）；"logic 块"只在 `{` 的前一个非空白字符是
  `)` 或 `}` 时才成立（`.out(knows) { $logic }` ✓、`.filter { … }` ✗）。
* 新增 **`map` 步**（`GremlinSteps.map`，注册在 `GremlinStepRegistry`）：`map { it.get().value('accountStatus') }`
  / `map { it.get().label() }` / `map { it.get().id() }`，投影成标量；不是单个取值就明确报错。

实测（8081 在跑）：
```
g.V(150,'Account').both('Payment').filter( it.get().value('accountStatus').equals('Active') ).count()  -> [100]
g.V(150,'Account').filter {it.get().label() == 'Account'}.count()                                       -> [1]
g.V(150,'Account').both('Payment').map { it.get().value('accountStatus') }.dedup()                      -> ["ACTIVE"]
g.V('+86-138-10008','Phone').in('PhoneToAccount')                                                       -> 400 + 方向提示
```

### 47.7 回归与运维

* 全量 `./mvnw test`（Aerospike 在跑，`AEROSPIKE_*` 按 §3）：**BUILD SUCCESS**，integration 88 条
  （4 skipped：需要 `-Drocksdb.live.dir` / HNSW 数据集的现场用例）；gremin 146、http 69、schema 38 …
* RocksDB 边数断言随镜像边消失从 400 → 300（`RocksDbLiveLoadTest` / `RocksDbStorageIntegrationTest`），
  `inlineStore.edgeCount("Payment")` 仍是 400（每条 payment 按两个端点各存一次）；
  RocksDB 的 demo 查询从 `out('AccountToIP')`/`out('AccountToPhone')` 改成 `in('IPToAccount')`/`in('PhoneToAccount')`。
* 现场：`clean install` 重建 fat jar → 重启 8081/8080（8083 那个 hnsw-dynamic profile 需要
  `--phoenixgraph.gemini.first-port=19110`，否则与主实例的 19100–19103 冲突）；v3 由
  `QueryApiIntegrationTest.demoSharedGraphViaApi` 播种，RocksDB 由 `RocksDbLiveLoadTest` 写 `.rocksdb`。

### 47.8 未做

* UI 仍是"数据里有什么就画什么"：如果某个 store 真的同时存了镜像边，UI 还是画两条（现在四个 demo 图都不会）。
  要彻底与存储无关，得给 schema 加"镜像类"声明并让子图/UI 折叠 —— 没有数据需要它，所以没做。
* `map` 只支持单个取值（不支持 `it.get().value('a') + ' ' + it.get().value('b')`）；
  `filter { }` 不支持 `!`、算术、`and`/`or` 以外的 Groovy 语法（`&&`/`||`/`and`/`or` 可用）。
* 上一轮遗留仍未做：display 属性 `$id`、`limit` 有序表示、摘要 bin、跨查询 bin 缓存。

---

## 48. 仪表盘四问（二）：图例选中计数、搜索更严、平行边成弧、filter/map 表达式语言

用户接着报了四件事：点图例（点类型 / 社区）后左下角要显示选了多少点多少边；搜索 Alan 仍会把 Ken Hopper
一起点亮（以及 Ada、10018 的老毛病）；1001 和 135 之间有多条 Payment，UI 却只画一条线；`map`/`filter`
写多行 + 三元表达式又被拒绝。逐条如下。

### 48.1 图例选中：左下角的点数 / 边数（#8）

`applyLegendFilter` 本来只是加 `legend-hit`/`legend-dim` 两个 class。现在它同时统计命中的点数与两端都命中的
边数，交给新的 `showLegendCount()`：左下角出现一个 chip —— `图例选中 N 点 · M 边`（中英双语的
`legendSelected` 键），没有选中就隐藏。那个角落原来是"结果值浮窗"（`#valuePanel`）的位置，所以 chip
可见时给它加 `shifted` 类（CSS `bottom: 66px`），两者不重叠。

### 48.2 搜索：逐字段之后，缩写还得从字段开头开始（#9）

第 47.3 节把"整串拼接 + 子序列"改成"逐字段 + 子序列"，但**自由子序列**仍然太松：

| 查询 | 误命中 | 为什么 |
|---|---|---|
| `ada` | Account 1014 (Radia) | r**a**d**ia** —— a-d-a 是 "radia" 的子序列 |
| `10018` | 11 条 `pay_rep_1001_1008_*` | id 里的 1-0-0-1 加后面的 8，是子序列 |
| `alan` | （第 47 节已修好；用户看到的还是旧页面，见 48.5） | 整串拼接 |

现在的规则是：**字段包含 token**，或者 **token 是该字段的缩写** —— token 的第一个字符必须落在字段的第一个字符上，
其余字符按顺序出现在后面（"pmt" → "payment" 仍然成立）。实测（1001 一跳子图，node 复算）：

```
alan   -> 1008(Alan),1044(Alan)                  （不含 1002 Ken Hopper）
ada    -> -                                       （原来含 1014 Radia）
10018  -> -                                       （原来含 11 条边）
hopper -> 1001,135,1000,1002,1038                 （真命中）
ken    -> 1002
1001   -> 1001 + 与它相连的边
pmt    -> 37 条 Payment 边                         （模糊缩写没丢）
```

### 48.3 同一对端点的多条边画成弧（#10）

边原来是 `<line>`，同一对端点上的多条边完全重合，只看得到一条（用户：135 和 1001 之间有多条 Payment
却只有一条线）。现在：

* 边改成 `<path>`；画之前 `assignEdgeArcs(links)` 按**无序端点对**分组，组内按 label + id 稳定排序，
  第 i 条的偏移 `arc = (i - (n-1)/2) * 26px`（1 条=直、3 条=-26/0/+26、5 条=-52/-26/0/+26/+52）；
  同一条链的正反两个方向落在同一组，因此也不会互相压住。
* `setEdgeGeometry` 写 `d`：`arc == 0` 时 `M..L..`（与原来的直线一致），否则用二次曲线
  `M.. Q (中点 + 垂直偏移) ..`；箭头 marker 跟着曲线末端的切线，仍然指向目标点。
* node 拖拽时照旧重算几何（`setEdgeGeometry(this, l)`），点击选中 / 图例 / 搜索 / 路径高亮的 class 逻辑不变
  （`.edge` 上的 CSS 对 path 一样生效）。

用 node 复算过：4 条同向 + 1 条反向的 5 条边得到 5 条互不相同的路径。

### 48.4 `filter` / `map` 换成表达式语言（#11）

用户要的是这个（多行 + 三元）：

```groovy
g.V(1001,'Account').bothE('Payment').map {
    it.get().value('status') == 'SUCCESS' ? 'S' : 'P'
}
```

原来的 `map` 只接受"单个取值"（`pathKey` 一看有空格就报 `map expects a single element accessor`），
`filter` 的语法也只有"属性键 + 比较"。现在两者共用一个递归下降的表达式语言（新文件
`GremlinExpression`，`GremlinFilterExpression` 变成它的薄封装）：

```
expression := conditional
conditional:= or ( '?' expression ':' conditional )?     // 右结合、只算命中的一支
or / and   :=  || / or 与 && / and（and 更紧）
equality   := == = != 以及词形运算符 equals|eq|ne|notEquals|contains|startsWith|endsWith
relational := < <= > >=
additive   := +（数字相加，其余按字符串拼接）
unary      := ! | not | -
postfix    := primary ( '.' method '(' 参数 ')' )*
primary    := '(' expr ')' | it.get().value('k') | it.get().label() | it.get().id()
            | '...' | "..." | 数字 | true | false | null | 属性名
```

几点设计：

* **向后兼容**：老写法（`relation.equals('a') || xrelation = 'b' && extra`、`relation equals r1`、
  `relation EQ 'a'`、裸 `xrelation` 表示"存在"）全部照旧 —— 词形运算符的右侧是**裸 token 当字面量**
  （老语法的语义），并且 `GremlinFilterExpressionTest` 的 10 条用例一条没改就全绿。
* **比较语义**沿用 `GremlinSteps.matches`：数字按数值比、字符串大小写不敏感；属性缺失时 `!=` 成立。
  作为条件时"真"的定义是"非 null 且非 false"，所以裸属性仍然是"存在"。
* **属性键顺序**很重要（它就是读 bin 的顺序）：测试立刻抓到一个 bug —— 我一开始用 `Set.copyOf` 存键，
  顺序变成哈希序，投影列表与预期不符，读到的属性对不上。改成 `LinkedHashSet` 的不可变视图。
* **词形运算符**一开始也漏了映射：`wordOperator()` 返回 "equals"，而比较函数的 switch 只认 "=="，
  于是 `relation equals r1` 恒为 false。现在统一在比较函数里规范化。
* **下推的保护**：边读会把 `filter` 折进 store 的解码条件，而那个条件只看得见属性（没有 label/id）。
  因此新增 `GremlinSteps.canPushDown(conditions)`：表达式读了 `@label`/`@id` 就不折（`nearest` 的
  HNSW 过滤同理），条件留在计划里正常执行。`GremlinGraphQueryTest` 里加了一条 "id 条件不被折进边读"
  的用例（mock 的 `supportsEdgeReadPushdown()` 返回 true，折进去就会多出一条答案）。
* 实测（8081）：

```
g.V(1001,'Account').bothE('Payment').map { it.get().value('status') == 'SUCCESS' ? 'S' : 'P' }.dedup()  -> ["S","P"]
g.V(1001,'Account').bothE('Payment').filter { it.get().value('status') == 'SUCCESS' }.count()           -> [24]
g.V(1001,'Account').bothE('Payment').filter { !it.get().value('status').equals('SUCCESS') }.count()     -> [13]
g.V(1001,'Account').bothE('Payment').filter { it.get().label() == 'Payment' && it.get().value('status') != 'FAILED' }.count() -> [37]
g.V(1001,'Account').bothE('Payment').map { it.get().value('status') + '/' + it.get().id() }.limit(2)    -> ["SUCCESS/pay_c1","SUCCESS/pay_r0"]
```

### 48.5 一个容易被忽略的运维点：浏览器缓存

#9 里"Alan 仍命中 Ken Hopper"在修好第 47 节之后**已经可以复现不了**（用 node 拿真实子图复算过：旧实现
命中 8 个点，新实现只命中 2 个 Alan 账户）。原因是浏览器还拿着旧的 `app.js`：静态资源只带 `Last-Modified`，
没有 `Cache-Control`，浏览器会按启发式规则在几分钟内直接用缓存。

现在两个服务的 `application.yml` 都加了

```yaml
spring:
  web:
    resources:
      cache:
        cachecontrol:
          no-cache: true
```

于是 `js/app.js` 响应带 `Cache-Control: no-cache`，浏览器每次都会带 `If-Modified-Since` 回来问一次
（没变就是 304，几乎不花时间），改完 UI 刷新就能看到。注意 **UI 是 `graph-web`（8080）在发**，
它的 `application.yml` 与 `graph-http` 是两个文件。

### 48.6 回归与未做

全量 `./mvnw test`：**BUILD SUCCESS**，逐模块与第 47 节相同，仅 `graph-query-gremlin` 从 146 涨到
**150**（新增：多行三元 map、多行比较 filter、拼接 map、id 条件不下推、malformed 表达式）。

未做：

* `map` 仍是"一条表达式对一个元素一个值"：没有聚合（`sum`/`count`）、没有 `project`/`by` 那种多列形状；
* 表达式没有字符串函数（`toUpperCase`/`substring`/`length`）与集合操作；
* `value('id')` 仍被当作属性名（要用 `it.get().id()`），报错信息来自 schema，不是表达式；
* 47.8 的遗留仍然在：UI 对"真的同时存了镜像边"的 store 不做折叠、display 属性 `$id`、
  `limit` 有序表示、摘要 bin、跨查询 bin 缓存。

---

## 49. 仪表盘五问（三）：终结步、多边外观、带变量的闭包

用户接着报了三件事：`toList` 这类"toX"步不支持；同源多边在 UI 上是黑底、颜色和其它边不一致、弧度太大；
`map` 里写 `def tmp = it.get(); tmp.value('status') == ... ? ... : ...` 报 `unexpected 't'`。逐条如下。

### 49.1 终结步：`toList` 与同伴（#12）

TinkerPop 的终结步在这里要么是"引擎本来就这么返回"，要么是"收集方式不同"，所以实现很薄：

| 步 | 语义 |
|---|---|
| `toList()` / `toIterator()` | 原样返回（引擎的答案本来就是一个 list） |
| `toSet()` | 每个遍历器一次，首次出现顺序（元素按 label+id，标量按值）——与 `dedup()` 同一实现 |
| `toBulkSet()` | 每个不同的遍历器出现在一行 `{value, label?, count}`，首次出现顺序 |
| `next()` / `next(n)` | 前 1 个 / 前 n 个 |
| `iterate()` / `none()` / `discard()` | 遍历照跑，答案是空的 |

三处需要知道它们是"透明的"（`GremlinGraphQuery.TERMINAL_STEPS`）：

* **属性投影**：`g.V(id).toList()` 应该是"这个点本身"，所以终结步像 `dedup`/`limit` 一样不消耗属性 ——
  `neededProperties` 跳过它们。于是 `g.V(id).toList()` 读全部属性，`values('status').toList()` 只读 status，
  `outE('x').toList()` 与裸 `outE('x')` 完全一样。
* **种子校验**：`g.V('不存在的id').toList()` 必须给空（而不是派出一个幽灵点），所以终结步算 `isSeedPassThrough`。
* **方向检查**：它们不改变当前点类型，检查继续往下走。

`toList(3)` 这类带参数的写法明确报错（"expects no arguments"）。

实测（8081，1001 的 37 条出款边）：

```
...bothE('Payment').toList().count()                     -> [37]
...bothE('Payment').values('status').toSet()             -> ["SUCCESS","PENDING"]
...bothE('Payment').values('status').toBulkSet()         -> [{"value":"SUCCESS","count":24},{"value":"PENDING","count":13}]
...bothE('Payment').values('status').next()              -> ["SUCCESS"]
...bothE('Payment').values('status').next(3)             -> ["SUCCESS","SUCCESS","SUCCESS"]
...bothE('Payment').iterate()                            -> []
```

### 49.2 多边外观：曲线的 `fill` 与弧距（#13）

第 48.3 节把边从 `<line>` 换成 `<path>` 时漏了一件事：**SVG 的 path 默认 `fill: black`**。
直线段（`M..L..`）面积为 0 看不出来，而曲线（`M..Q..`）和弦围出的月牙区域被填成黑色 —— 用户看到的
"同源多边背景色黑色"就是它。CSS 的 `.edge` 加一句 `fill: none`，曲线里的边就只剩描边，颜色与其它边
完全一致（同一个 `stroke`）。

弧度也按用户要求减半：`EDGE_ARC_STEP` 26px → **13px**（5 条边从 -52/-26/0/+26/+52 变成
-26/-13/0/+13/+26）。

### 49.3 闭包里的语句与变量（#14）

用户写的是：

```groovy
g.V(1001,'Account').bothE('Payment').map {
    def tmp =  it.get();
    tmp.value('status') == 'SUCCESS' ? 'S' : 'P';
}
```

`unexpected 't'` 来自三个缺失的能力：闭包不是一条表达式而是一小段**程序**；元素本身要能赋给变量；
`value('status')` 要能作用在变量上。现在：

* **程序**：`program()` 解析"语句序列"，`;` 或**换行**分隔，值是最后一条语句。换行分隔用的是 ASI 式判断 ——
  只有当换行**前一个字符能结束语句**（`)`、`]`、`}`、引号、字母数字下划线）**且**换行**后一个字符能开始语句**
  （字母、数字、引号、`(`）时才算分隔。所以
  ```
  def tmp = it.get()
  tmp.value('status') == 'SUCCESS'
  ```
  是两条语句（无需 `;`），而
  ```
  it.get().value('a') ==
      'x'
  ```
  仍是一条表达式。三元跨行也没问题，因为换行在 `conditional()` 内部就被跳过了。
* **变量**：`def name = expr` 声明；不带 `def` 的 `name = expr` **只有 name 是已声明的变量**才算赋值 ——
  因为老语法用 `relation = 'r1'` 表示相等比较，这一条被 `GremlinFilterExpressionTest` 立刻抓到
  （三个用例从 false 变 true）。`primary()` 先查变量表，再当属性名。
* **元素**：`it.get()`（以及裸 `it`）求值为元素本身（伪键 `@it`）；`value('k')`/`values('k')`/`label()`/`id()`/`key()`
  是"元素方法"，作用在任何求值为元素的接收者上（`tmp.value('status')`、`tmp.label()`）。
  字面量现在是 `Const`，所以 `tmp.value('status')` 的键在**编译期**就知道并进入投影计划（否则 store 不会读它）。
  链式变量也行：`def s = t.value('status'); s == 'SUCCESS' ? 'ok' : 'no'`。
* **下推**：`@it` 与 `label()`/`id()`/`key()` 都算"读了元素身份"（`readsElementIdentity()`），
  因此 `filter { def t = it.get(); ... }` 不会被折进 store 的解码条件（保守但正确）。

实测（8081）：

```
map { def tmp =  it.get(); tmp.value('status') == 'SUCCESS' ? 'S' : 'P'; }.dedup()   -> ["S","P"]
filter { def t = it.get(); t.value('status') == 'SUCCESS' }.count()                 -> [24]
filter { def t = it.get(); t.label() == 'Payment' && t.id().contains('pay_rep') }.count() -> [30]
map { def t = it.get(); t.label() }.dedup()                                        -> ["Payment"]
filter(status = 'SUCCESS').count()                                                 -> [24]（老语法不受影响）
```

### 49.4 回归与未做

全量 `./mvnw test`：**BUILD SUCCESS**；`graph-query-gremlin` 从 150 涨到 **156**（新增：变量 map / 变量 filter /
终结步 / toBulkSet / 终结步的种子校验与参数校验）。

未做（沿用 §48.6）：`map` 无聚合与多列；表达式无字符串函数（`toString`/`substring`/`toUpperCase`）；因此
上面那条 `t.id().toString()` 会明确报 "'toString' takes one argument"；`value('id')` 仍按属性名；
UI 不做镜像边折叠、display 属性 `$id`、`limit` 有序表示、摘要 bin、跨查询 bin 缓存。

---

## 50. 第五批：顶点记录的类型、last-write-wins 复验、链接不写账户、regex 形状复验、两跳再压测

### 50.1 顶点记录带上自己的类型（#15）

用户报的现象：UI 选 IP、输入 `+86-138-69999`（其实是 Phone），能查到这个点、也能看到它的三条边，但
**点的属性面板显示 IP**（`ipAddress: "+86"` —— 手机号的国家码被当成 IP 地址读了出来）。选 Phone 输入 IP 的 id 同理。

根因很清楚：`AerospikeGraphQuery.readVertex(id, vertexName, ...)` **只信调用方给的类型名** —— 它用那个
schema 把属性 bin（按索引存）翻译成名字，而记录里没有任何东西说明自己是什么。id 本身不带类型，
所以"用 IP 的 schema 读一条 Phone 记录"就得到上面那一幕。RocksDB 的两个 store 不受影响：它们的顶点
按类型分 column family（`vertex:<类型>`），用 IP family 读 Phone 的 id 根本读不到记录，返回空 ✓（实测）。

修法是让记录自己说是谁：

* `AerospikeVertexStore` 新增一个普通 bin `vt`（**不是** `vp_` 前缀，所以属性解码不会碰它），
  写入时从 `VertexUpsert.vertexSchema().name()`（以及 `upsertVertex(Vertex)` 的 `vertex.schema().name()`、
  `GraphService.upsertVertex` 的请求类型）取类型名。批量与单条两条写路径都带上它。
* `StoredVertex` 加一个 `vertexType` 组件（旧的 4 参构造器保留，老记录/测试不受影响）；
  `propertyBins()` **必须**把 `vt` 拼进每次读的 bin 列表 —— 漏了这一句时改了也读不到（第一版就是这么错的）。
* `AerospikeGraphQuery` 读到记录后先看它的类型：与请求的类型不同就用**记录的类型**重新读一次
  （投影也换成那个类型的属性），返回 `VertexQueryResult(真实类型, ...)`；老记录没有 `vt`、或 `vt` 指向
  一个 schema 里没有的类型时，退回请求的名字（向后兼容）。

实测（重启后的 8081）：

```
g.V('+86-138-69999','IP')    -> Phone:+86-138-69999 {"cnCode":"+86","phoneNumber":"138-69999"}
g.V('10.99.0.1','Phone')     -> IP:10.99.0.1        {"ipAddress":"10.99.0.1"}
g.V('10.99.0.1','IP')        -> IP:10.99.0.1        {"ipAddress":"10.99.0.1"}   （本来就对）
```

注意：`vt` 是写记录时写进去的，所以**已有的老数据要重新播种一次**才有类型（本次已重播 `http_demo_v3`）。
写路径里 `updateVertexProperty`（按属性号的单属性更新）不带类型，它创建的记录仍然退回请求的类型。

### 50.2 last-write-wins：新的验证与结论（#16）

要验证的是："同一个点或同一条边的同一个属性，两次写入的 `lastUpdatedTime` 不同，**无论谁先到**，
库里留下的都是 `lastUpdatedTime` 最新的那个值"。新增集成测试 `LastWriteWinsIntegrationTest`（自带
scratch set，写真实 Aerospike，两方向各来一遍）：

```
点属性：新先到 -> 旧被拒（CONFLICT，值/时间不变）；再来一个更新的 -> 接受
点属性：旧先到 -> 新后到 -> 接受，留下新值
点属性：时间戳相同 -> 不算更新（CONFLICT），值不变
边属性：同上三种情形（新先到 / 旧先到 / 相同时间戳）
```

**结论：数据规则成立** —— 点用 `isNewer(incoming, stored) = incoming > stored`，边用
`incoming.lastUpdatedTime() > old.lastUpdatedTime()`，都是"严格更新才覆盖"，所以最终值总是最新时间戳的那个，
与到达顺序无关 ✓。

一处**语义差异（不是数据 bug）**记录在案：**边**写入即便所有属性都 stale 也返回 `SUCCESS`（bin 确实被写了一次，
stale 的只在业务日志里记 `stale=true`），而**点**写入会返回 `CONFLICT`。要让两侧一致，得把"这条边是否全 stale"
从 `upsertBatchInto` 的 bin 级结果里带出来再合并，属于接口语义改动，本轮没做（测试里明确断言了这一点）。

### 50.3 链接只写 link 点和 link 边，不碰账户（#17）

先说结论：**代码路径里本来就没有把 context 写进账户的地方** —— `fuzzyLink` 这个 step 只读 link 点，
`FuzzyLinkingService.link` 只写 `linkVertex(...)`（FuzzyLink 点）与 `linkEdge(...)`（FuzzyLinkedTo 边），
`FuzzyController` 的 `/links` 只是解析 context（顶点属性会**补进 context** 供模式读取，方向是"读"不是"写"）。
用真实服务实测：给 `fz_a1` 带一个 schema 里根本没有的 key（`deviceId`/`extraKey`）去 link，账户属性
**一模一样**（前后两次 `readVertex` 的属性名、值、createdTime、lastUpdatedTime 全等），link 点与边正常生成 ✓。

为了让这条约定以后不会被悄悄破坏，新增集成测试 `FuzzyLinkingServiceIntegrationTest`（真实 Aerospike、
真实 `FuzzyLinkingService`、自己的 scratch set）：写一个账户 → 带额外 key 的 context 调 `link()` →
断言 link 点存在、link 边存在，且账户的属性集合、每个值、createdTime、lastUpdatedTime **全部不变**。

> 补充说明：demo 数据里账户身上那些 `email`/`phoneNumber`/`cardNumber`/`ipAddress` 是**账户自己的属性**
> （schema 里就声明了它们），模式正是靠读这些属性才能从"点自身"出发建立链接（`g.V('fz_a1').fuzzyLink(0)`）；
> 它们不是链接时从 context 写进去的。如果用户的意思是要 demo 账户不带这些片段，那会牺牲"从点自省团伙"
> 这个演示；需要确认。

### 50.4 regex 形状语义：复验 + 重新生成数据（#18）

用户说"regex pattern 的生成方式"要改进，并要"如果还是存在同样的问题就修复并重新生成 fuzzyLink 的数据"，
但**"以下问题"这段没贴出来**。先按 §35.9 的验收点逐条复验（8081 实测）：

| 验收点 | 结果 |
|---|---|
| `regex` 把**形状**写进 key（不是抽出来的值） | ✓ 节点 id `fl_1_10.30.40_621700_re_d_4_8aafc7c1`（`re_d_4_8aafc7c1` = `^\d{4}` 的形状） |
| 同形状、**全新值**也连到同一个节点 | ✓ `fuzzyLink(1, {email 99990101@qq.com, ip 10.30.40.99, card …9999})` → 同一个 `fl_1_…` 节点 |
| `regexValue` 仍然抽值（模式 0 的 `^([a-z]+)`） | ✓ key 仍是 `mail.ru_86_10.20_zhang`；换成 `xia1999` 得到 `…_xia`（另一个节点，符合"按值分组"） |
| `\d`/`\w` 转义没被吃掉（§35.8 的老 bug） | ✓ `'^\d{4}'` 正常匹配 4 位；不匹配时模式不生效（`12ab@…` → 无链接） |
| 形状的可读段 + hash 让"长得像但不同"的正则分开 | ✓ 有单测（`^([a-z]+)` vs `^[a-z]+` 的 key 不同） |

数据侧：本次**已经把 fuzzy demo 数据整批重新生成**（`demoSharedGraphViaApi` 先 truncate 了
`http_demo_v3`，随后 `FuzzyLinkDemoLoader` 重播 17 个账户 / 15 个 link 点 / 26 条 link 边），
所以现在库里不存在上一代形状留下的节点 ✓。

### 50.5 两跳再压测：150 的 payment→payment 与 payment→IP（#19）

在 `AerospikeTwoHopPaymentBenchmarkTest`（真实 Aerospike、`http_demo_v3`、每条 3 次预热 + 12 次计时、
答案都与参考读数器核对）的 star 段（种子 `150`）上补到 **20 条遍历**：除原有的 `out>in(IPToAccount)`、
`both>`、`outE has(status) > otherV >`、`filter(accountStatus)`、`dedup`、`limit(500)`、`count`、
`dedup>count`、`batch(10)`、`barrier(100)`、`in(PhoneToAccount)>count`、`values(ipAddress)` 之外，新增
`toList()`、`map { it.get().value('ipAddress') }`、多类二跳 `in('IPToAccount','PhoneToAccount')`、
边跳中转 `bothE('IPToAccount').otherV()`、`has(ipAddress)>count`、`limit(100)`、`dedup>limit(50)`、
以及 `out('Payment').out('Payment')>count`。

150 段实测（p50 ms / kv-calls / 答案条数）：

```
out > in(IPToAccount)                      7.47 / 4  / 1662
both > in(IPToAccount)                     8.65 / 5  / 1662
outE has(status) > otherV > in(IPToAccount) 6.88 / 4 / 1662
out > filter(accountStatus) > in(IPToAccount) 8.65 / 6 / 1662
out > in(IPToAccount) > dedup              5.24 / 2  / 61
out > in(IPToAccount) > count              4.00 / 3  / 1662
out > in(IPToAccount) > dedup > count      4.46 / 2  / 61
out > in(IPToAccount) > limit(100)         4.52 / 3  / 100
out > in(IPToAccount) > has(ipAddress)>count 6.95 / 4 / 1662
out > in(IPToAccount) > values(ipAddress)  6.95 / 4  / 1662
out > in(IPToAccount) > toList             6.95 / 4  / 1662
out > in(IPToAccount) > map(ipAddress)     6.55 / 4  / 1662
out > in(IPToAccount,PhoneToAccount) > count 7.60 / 5 / 1757
out > bothE(IPToAccount) > otherV          9.96 / 6  / 1662
out > in(PhoneToAccount) > count           2.62 / 3  / 95
out > out(Payment) > count                 2.69 / 3  / 0        （它付的账户不出款）
```

以及 Payment→Payment 的对照（同一份报告里）：

```
B-150 both > both                          8.39 / 5 / 100      （150 双向两跳只有 100 条）
P-150 out > out                            3.01 / 2 / 0
P-135 out > out                            6.03 / 3 / 396
P-1000 out > out                           2.36 / 3 / 33
```

结论（与 §45 一致，没有新的退化）：

* **往返次数决定时延**：`count` 3 次往返 4.0 ms，物化 1662 个点 4 次 7.5 ms，`bothE>otherV` 6 次 10.0 ms；
  `batch(10)` 把往返抬到 9 次（6.4 ms），`barrier(100)` 3 次（6.5 ms）。
* **150 的"两跳"要以 IP/Phone 收尾才有量**：它付的 100 个账户自己不出款（`out>out` = 0），
  但每个账户挂着共享 IP，所以 `out('Payment').in('IPToAccount')` 是 1662 条、61 个不同 IP。
* `dedup`（2 次往返、5.2 ms）与 `limit(k)`（cap 下推、4.5 ms）都能把 1662 压到几十条，
  是仪表盘上最划算的写法；`count`/`dedup>count` 不构造顶点（v-items=0）。
* 混合负载：star 段 20 条 × 4 轮 80 次执行 520 ms（6.5 ms/次）；一跳+两跳混跑 124 次 352 ms（2.8 ms/次）。

### 50.6 回归

全量 `./mvnw test`：**BUILD SUCCESS**，731 条（skipped 4），逐模块：schema 74 / core 13 / metrics 18 /
storage 17 / storage-aerospike 38 / storage-rocksdb 32 / query 15 / rocksdb-inline 16 / rocksdb-edge 21 /
gemini 26 / fuzzy 37 / hnsw 33 / **query-aerospike 27（+4）** / gremlin 156 / cypher 25 / compute 11 /
**http 70（+1）** / batch 9 / mapreduce 3 / **integration 90（+2）**。

### 50.7 未做 / 待确认

* **#18 的"以下问题"没有贴出来**：按 §35.9 的验收点复验全过（见 50.4），数据也整批重生成过；
  如果用户看到的仍是一个具体反例，需要把那句话补上（哪个模式、哪个 context、期望什么）。
* **#17 的口径**：如果用户其实是要 demo 账户**不带** `email/phoneNumber/cardNumber/ipAddress` 这些片段，
  那要同时放弃"从点自身属性建立链接"的演示（`g.V(id).fuzzyLink(0)`），需要确认。
* 边写入"全 stale 也返回 SUCCESS"的语义（50.2），与点侧的 CONFLICT 不一致，尚未统一。
* `vt` 只在新写的记录里，`updateVertexProperty` 单独创建的记录没有类型（退回请求类型）。
* 47.8 / 48.6 的老遗留不变（UI 不做镜像边折叠、display 属性 `$id`、`limit` 有序表示、摘要 bin、跨查询缓存、
  `map` 无聚合、表达式无字符串函数）。

---

## 51. 第六批：Gremlin 结果的「结果」面板、弧距再减半、类型是过滤条件、regex 形状的正题

### 51.1 Gremlin 的结果进「结果」面板（#20）

用户要的是：`…map { … }.toList()` 这类**不是图元素**的答案（count、values、elementMap、一列字符串）要出现在
侧栏的「结果」面板里；而返回遍历器（点/边）的查询保持原样 —— 只画画布，不显示那个面板。

改动（`graph-web/frontend/public/js/app.js`）：

* `rowsAvailable()` 不再要求 `cypher`（原来「结果」tab 只对 Cypher 开放），只看 `rowsReady`。
* `showQueryResult` 里分流：Cypher 的 `columns/rows` 照旧；否则若 `responseValue(result)` 是数组且
  `!elementValues(...)`（即不是点/边），交给新的 `showGremlinRows(values)` —— 一列一行（`rowsValue` =
  「值」/「Value」），对象用 JSON 渲染（`elementMap()`、`toBulkSet()` 的行也就能看）；是图元素则
  `clearRows()`（面板关掉，画布照旧）。
* i18n：`rowsHint` 改成「Gremlin 的结果值 / Cypher 的行结果会显示在这里」。

实测：`g.V(1001,'Account').bothE('Payment').map { def tmp = it.get(); tmp.value('status') == 'SUCCESS' ? 'S' : 'P' }.toList()`
→ `["S","S",…]` 共 37 项，面板一行一项；`g.V(1001,'Account').both()` 仍然只出画布。

### 51.2 弧距再减半（#21）

`EDGE_ARC_STEP` 13px → **7px**（用户反馈还是偏大）。

### 51.3 类型是过滤条件：读记录必须符合声明的类型（#22）

现象：UI 选 IP（或任何非 Account 类型）输入 `1001`，仍然画出以 account 1001 为顶点的子图；用户期望
"这个 IP 的值应该查不到结果"。（第 50.1 节的 `vt` 让"记录自己说类型"成为可能，当时选择的是**改标签**；
用户这次明确了取向：类型应当是**过滤**。）

两处改动：

1. **存储层（严格读）**：`AerospikeGraphQuery.readVertex/readVertices` 现在要求记录的 `vt` 与请求的类型一致，
   不一致就当作**没有这个顶点**（原来会按真实类型重新读一次并改标签）。这同时让 Aerospike 与 RocksDB 行为
   一致 —— RocksDB 的顶点表按类型分 family，本来就读不到（§50.1 实测过）。没有 `vt` 的老记录、或 `vt` 指向
   schema 里不存在的类型时，仍然按请求的类型读（向后兼容）。
2. **引擎层（无类名的首跳先校验种子）**：只做第 1 点还不够 —— `g.V('1001','IP').both()` 的首跳会读该记录的
   **每一个边 bin**（`both()` 没有类名），而一条 Account 记录本来就有那些 bin，于是"以 IP 身份"走完了 Account
   的邻域。所以 `verifySeeds` 增加一种情形：**首跳是 `out/in/both/outE/inE/bothE` 且没有类名**时，种子先读一遍
   （`untypedFirstHop`）。种子读不到/类型不符 → 遍历从"没有"开始（结果空、子图空）。

实测（8081）：

```
g.V("1001","IP").both()        -> result=[]  vertices=0 edges=0      （原来是 account 1001 的子图）
g.V("1001","IP")               -> result=[]  vertices=0 edges=0
g.V("1001","Account").both()   -> 正常
g.V("+86-138-69999","Phone").both() -> 正常（三个账户）
g.V("+86-138-69999","IP").both()    -> result=[]  vertices=0 edges=0
```

**代价与取舍**：`verifySeeds` 只在"首跳没有类名"或"计划只剩直通步"时打开，所以**命名类的查询一个往返都没多**
（压测的 kv-calls 列与改动前一致：`H-135 out` 2、`S-150 out>in(IPToAccount)` 4、`S-150 …dedup` 2…）。
新加的无类名对照 `g.V('135','Account').both().count()` 是 **12 次往返**（每类两个方向的边 bin 各一次 +
校验种子 1 次），这也是 `both()` 本来的代价。

> 为什么不做"无条件校验种子"：那会给每个查询都加一个往返，并且要改 13 个钉住"种子不读"这个性能设计的既有
> 断言（`fanOutUsesNativeBatchReadChunkedByBatchSize`、`intermediateHopsSkipVertexReads`、`nearestPushes*` …）。
> 更彻底的省法是把 `vt` 一起塞进边读的 bin 投影（同一次往返里知道类型），代价是改 6 个 store 方法与查询层的
> 批读/邻居读路径 —— 列入未做。

### 51.4 regex 不该抽值 —— 原话终于贴出来了（#18 的正题）

用户这次补上了那句话："regex的pattern不应该提取具体值，这样 zha 和 xia 都能匹配正则表达式，但是不能关联，
应当保留正则的公式并作为子pattern写入，修改一下"。

第 35.9 节当时确实做了 `regex`（公式/形状）与 `regexValue`（抽值）两个函数，但为了"key 不变、断言不受影响"，
**演示模式 0 选的是 `regexValue`** —— 于是用户在自己的演示里仍然看到 zha 与 xia 不关联，问题"还在"。
现在把演示模式 0 改成公式语义：

```
emailDomain(email)+'_'+countryCode(phone)+'_'+ip2(ip)+'_'+regex(username, '^[a-z]+')
```

（`FuzzyDemo.SHARED_HABITS` 与运行中的 **`fuzzy-patterns.json`** 同步修改，描述/名称也一起改；
新增 `FuzzyDemo.NAME_SHAPE` 给测试用，避免硬编码形状 token。）

验证：

* 单测 `twoDifferentNameWordsOfOneShapeLinkUp`：`zhang1988` 与 `xia1999`（同域名/国家//16）同一个 link id ✓；
* 集成 `theNameWordItselfDoesNotDecideTheLink`：用 `xia1999` 的 context 查，命中第一圈的 6 个账户 ✓；
* 线上（重播数据后）：两个 context 都得到同一个节点
  `fl_0_mail.ru_86_10.20_re_a_z_6759fdcb`（`re_a_z_…` 就是 `^[a-z]+` 的形状）✓；
* `regexValue` 保留（按值分组仍有场景），只是不再出现在演示模式里。

数据按 §50.4 的方式整批重播（先 truncate 再 `FuzzyLinkDemoLoader`），所以库里没有旧的 `…_zhang` 节点 ✓。

### 51.5 回归与压测

全量 `./mvnw test`：**BUILD SUCCESS**（731 → **734**：gremlin +1、integration +1、fuzzy +1；skipped 4）。

两跳压测重跑（`AerospikeTwoHopPaymentBenchmarkTest`，同一台机器，压测时三个服务 JVM 也在跑，**时延列明显受负载影响**，
按项目惯例看**确定性流量列**）：

```
H-135 out（命名类）            2 kv（未变）
H-135 both（命名类）           3 kv（未变）
H-135 both（无类名，新增对照） 12 kv = 6 个边 bin + 各方向 + 1 次种子校验
S-150 out > in(IPToAccount)    4 kv（未变）   p50 11–13 ms（负载下；安静时同查询 6.7–7.5 ms）
S-150 … > dedup                2 kv（未变）
S-150 … > count                3 kv（未变，v-items=0）
S-150 … > limit(500)           4 kv（未变，e-items 101→51 = cap 下推）
S-150 in(IPToAccount,PhoneToAccount) > count  5 kv（多类二跳）
S-150 bothE(IPToAccount) > otherV             6 kv / 201 e-items
```

结论与 §50.5 一致：往返次数决定时延；命名类查询没有因为这次改动多花一次往返；无类名的 `both()` 本来就要把
每一类的边 bin 读一遍（12 次），校验种子只占其中 1 次。

### 51.6 未做 / 待确认

* **把 `vt` 塞进边读**（消除无类名首跳的 +1 往返）——需要改 6 个 store 方法与查询层批读/邻居读；
* 命名类首跳 + 类型不符的记录仍可能读出该记录同类的边 bin（`g.V('1001','IP').in('IPToAccount')`）；要彻底
  按类型过滤，得让 store 知道期望类型（同上一条）；
* 「结果」面板对 Gremlin 是**一列**（值/对象）；聚合或多列要等 `map` 有聚合能力；
* 47.8 / 48.6 / 50.7 的老遗留不变。

---

## 52. 第六批：Aerospike-Edge 的大点三层存储（冷 / 近线 / 在线）

用户要求在 **Aerospike-Edge** 上继续改进大点方案（不是内联 Aerospike）。**兼容性约束**：schema 的存储改回
`Aerospike`（内联）时，所有代码与逻辑退化为内联方式 —— 新加的类只在 `GraphStorageType.AEROSPIKE_EDGE` 下被
`StorageConfig` 装配，`AerospikeGraphQuery` / `AerospikeEdgeStore`（内联）一行未动。

### 52.1 模型：三个时间层 + 一个判定

```
cold     (-∞, now-24h]          离线 Edge_Out / Edge_In 多级表（已有 §51 之前的 AerospikeEdgeChunks）
nearline (now-24h, now]         TTL 7 天的近线 KV（本次新增）
online   [onlineEarliest, +∞)   内联 ve_<schema>_<dir> bin（已有，受 maxInDegree/maxOutDegree 限制）
大点判定  离线出/入度 > schema 的 maxInDegree / maxOutDegree（本次新增 EdgeTiering.isBig）
```

一条读按它的时间窗与这三层覆盖区间的交集路由（`EdgeTiering.classify`），再把各层的答案按边 id 合并（后写者胜，
与内联 bin 同一条规则）。这一条规则就得到用户读方案的六个情形：窗在在线层内→只读在线；窗全在过去→只读冷
（忽略近线/在线）；跨冷+近线→两层；跨三层→三层。**兼容退化**：`AerospikeEdgeGraphQuery` 的三参构造器保持旧
行为（冷+内联无条件合并），旧测试原样通过；带 nearline 的四参构造器才启用分层路由。

### 52.2 逐条映射（用户 8 点 → 代码）

| 用户要求 | 实现 |
|---|---|
| 1. 冷数据离线直接生成 KV（edge list / edge index），覆盖写入；每天更新最新一天 KV（所有 delta） | `AerospikeEdgeChunks.build`（level0 边表 + 上层索引）+ `AerospikeEdgeChunkStore.writeChunks`（批量覆盖写）；**delta** = `AerospikeEdgeChunkStore.rewriteWindow`（读最新一天的 level-0 chunk、按边 id 合并 delta、重分该天、重build 上层索引；其它天的 level-0 不动） |
| 1b. 统计每个大点按月/按年的 volume | `AerospikeVolumeStore`（每条 (edgeClass,dir,vertex) 一个记录，bin `m_yyyyMM` / `y_yyyy`，离线算好绝对值写入，幂等） |
| 2.1 近线按 volume 预划分：`月量/30/4000` → N 个 KV | `AerospikeNearline.partitionCount(monthlyVolume)`（1 亿/30/4000=833，cap 1000） |
| 2.2 一个索引 KV 存 N 个数据 KV 的 key（bin 0..999） | `AerospikeNearlineStore.prePartition`（索引 KV bin 0..n-1 各存一个 data key + bin `n`=个数） |
| 2.3 新近线边按索引写入相近 KV | `AerospikeNearlineStore.write`（`hash(edgeId) % N` 落分区，边按 id 覆盖） |
| 2.4 近线 KV TTL 7 天 | `AerospikeNearline.TTL_SECONDS = 7*24*3600`，写时 `WritePolicy.expiration` |
| 3. 在线数据存点邻接表，有出/入度限制 | 已有 `AerospikeEdgeStore`（`maxInDegree`/`maxOutDegree` 超限逐出最旧） |
| 5. 大点判定 = 离线出/入度超 schema 上限 | `EdgeTiering.isBig(degree, maxDegree)`；`AerospikeEdgeOfflineJob.daily` 汇总 |
| 读 1. 必须带时间范围 | 已有（hub 无窗报错）；新增：近线有数据也算 hub（`nearline.hasData`） |
| 读 2–6. 在线→近线→冷、按窗合并 | `AerospikeEdgeGraphQuery.readEdgesWithin`（tiered 分支） |

离线入口 `AerospikeEdgeOfflineJob.daily`：写 volume → 按最新月量预划分近线 → 把当天 delta 折进冷表 → 返回
`big` 判定。

### 52.3 关键决策

* **近线 KV 键加 (edgeClass, direction)**：用户示例写的是 `1_NL_Index_Date`，但一个点的不同边类/方向不能混在
  一个 KV（冷表就是按类+方向分 set 的同一理由）。键 `{vertexId}_NL_Index_{edgeSchemaId}_{dir}_{yyyyMMdd}` 与
  数据 `{vertexId}_NL_{edgeSchemaId}_{dir}_{yyyyMMdd}_{i}`，`_NL_Index_/_NL_` 命名保留。
* **时间边界**：cold = `(-∞, now-24h)`、nearline = `[now-24h, now]`，边界落在近线（24h 整不算冷）。`classify`
  里 cold 上界用 `nearlineFrom-1`（毫秒分辨率）。
* **近线只存索引、不预建数据 KV**：数据 KV 由普通近线写路径按需创建（`write` 读到 N 后落到分区、懒建 KV）；
  离线只负责根据 volume 预划分写索引。
* **在线层覆盖 = `[earliestCreatedTime, +∞)`**：在线 bin 里最旧边的 createdTime 是下界；窗全在冷区时不读它
  （`window.toTime() <= now-24h` 短路），实现用户"忽略在线"且不白读一次。
* **语义变化（有意）**：旧版"内联更新永远盖过冷副本"变成"冷只读冷、delta 由离线折进冷"（用户读方案第 4 条）。
  旧三参构造器保留旧语义；`StorageConfig` 对新图用四参 tiered 构造器。近线 KV 保留 7 天但读路径只在最近 24h
  用它们（用户两处数字照单全收）。

### 52.4 测试与回归

* 纯逻辑单测（无 Aerospike）：`EdgeTieringTest`（big 判定 + 6 种路由）、`AerospikeNearlineTest`（分区数、
  稳定分区、键/TTL）、`AerospikeEdgeChunksTest.buildLevels`（父层重建 = build 的父层）。
* 集成（`AerospikeEdgeTieringIntegrationTest`，实时 Aerospike）：近窗=在线+近线合并 43 条、冷窗=只冷、跨三
  层合并、离线 job（volume / 预划分 / 冷 delta / big 判定）。`AerospikeEdgeIntegrationTest`（旧三参路径）9/9
  原样通过 = 兼容性证明。
* 全量 `./mvnw test`：**BUILD SUCCESS，0 失败 / 0 错误**（新增约 12 单测 + 4 集成）。

### 52.5 未做 / 后续

* 冷 delta 的 `rewriteWindow` 会重写整条索引链（读全宽度 level-1 拿所有 level-0 span 再 `buildLevels`），
  `O(level0 数量)`；对每天更新的最新一天够用，全量重算时延未测。
* 近线读当前按天整读该天所有分区（一次 batch get）；没有按"更细时间片"索引，1 亿边/天读全部 1000 个 KV 是
  本方案固有代价（用户原样设计）。
* 在线 bin 的逐出按 `lastUpdatedTime` 而非 `createdTime`，与"在线层=最新 createdTime"的口径有细微差别，
  文档化即可。
* 边写入 stale 返回 CONFLICT（点侧）/ SUCCESS（边侧批量）仍不一致（§50 遗留）。

---

## 53. 第七批：stale 写统一、结果面板复核、150 的两跳扩展数据与 benchmark

### 53.1 stale 写统一为 `SUCCESS_STALE`（#23）

之前"全 stale"在点侧返回 `CONFLICT`、在边侧（批量）返回 `SUCCESS`（只记日志）。现在统一成一个新结果
`WriteOutcome.SUCCESS_STALE`（"什么都没写，但记录已经是新的，不是错、也不是要重试的冲突"）：

* 点单条 `upsertVertex`、点批量 `upsertVertices`：全 stale → `SUCCESS_STALE`（原 `CONFLICT`）；
* 边单条 `upsertInto`、边批量 `upsertBatchInto`/`upsertEdges`/`upsertEdgesOnVertex`：全 stale →
  `SUCCESS_STALE`（批量路径现在把每个 bin 里 stale 的 edge id 带出来，逐边合并两个端点的结果：
  两边都 stale → `SUCCESS_STALE`，一边 stale 一边新写 → `SUCCESS`，任一 `FAILED` → `FAILED`）。
* 消费侧：HTTP `WriteResponses` 把 `SUCCESS_STALE` 映射成 **200 OK**；CSV 批量加载把 `SUCCESS_STALE`
  计入 `succeeded`（不是 conflict 也不是 failed）。`MetricsBusinessLog` 不依赖 `CONFLICT`（看的是
  日志里的 `stale=true`/`conflict=true`），指标不变。`CONFLICT` 保留给真正的 CAS/代数冲突（`toOutcome`）。
* 测试：`LastWriteWinsIntegrationTest` 的点/边 stale 断言全部改为 `SUCCESS_STALE`。全量测试绿。

> RocksDB 侧同一条"点全 stale→CONFLICT、边全 stale→SUCCESS"的不一致**本轮未动**（用户指认的是 Aerospike
> 这一处；RocksDB 的 `RocksDbVertexTable`/`RocksDbEdgeStore` 若要同样统一，改动面更大，列为后续）。

### 53.2 「结果」面板复核（#25）

用户报 `g.V(1001,'Account').bothE('Payment').map {...}.toList()` 的结果还是没进「结果」面板。用
**headless Edge 驱动真实 UI** 复核（不是看代码、不是看 curl）：该查询在 gremlin 模式下点击「查询」后

```
rowsPanel.hidden=false  rowsTable 38 行（表头"值" + 37 行 S/P）  结果 tab 激活  浮窗 "S · P"
```

**功能是好的**，第 51.1 的修法确实生效了。问题在浏览器缓存了旧 `app.js`（`no-cache` 只会"用前再验证"，
已打开的旧页面不会自动重取）。措施：两个 `application.yml` 的静态资源缓存从 `no-cache` 改成
**`no-store`**（每次导航都重取），并提醒用户**硬刷新（Ctrl+Shift+R）**。没有其它代码问题。

### 53.3 150 的两跳扩展数据（#24）

新增 `AerospikeTwoHopExpansion`（集成测试侧 loader，`payment-graph.json` 的 Account 用**字符串 id**，
与 demo 一致）：150 → K 个一层账户，每个一层账户 → L 个二层账户，于是
`g.V('150','Account').both('Payment').both('Payment')` 的二跳 = **K×(L+1)**（每个一层账户的 both 有 L 条出
边 + 1 条回到 150 的入边）。每跳再挂少量 IP/Phone 边（150 各 1 条，每个一层账户各 1 条）。写在**独立
vertex set**（先 truncate），不动 `http_demo_v3`（避免破坏既有 benchmark 的断言）。

```
K=50, L=99  → 二跳 5000 条边、100 个不同账户（99 个二层 + 150 自己）
```

`AerospikeTwoHopExpansionLiveLoadTest` 把 5000 条边的扩展种进 `payment_twohop_exp` 并跑那条查询：实测
**5000 条边 / 100 个不同账户** ✓。

### 53.4 两跳 benchmark：200 → 500 → 1000 → 2000 → 5000（#26）

`AerospikeTwoHopExpansionBenchmarkTest`：五个档位各自独立 set 加载，各跑 7 条遍历
（`both>both` / `…count` / `…dedup>count` / `out>out` / `out>out>count` / `both>bothE` / `…limit(100)`），
预热 3 + 计时 10，报告 mean/p95/p99/max + v-items/e-items/decoded/kv-calls。实测（Aerospike，安静）：

```
both>both            200→5000  6.79→14.62 ms  kv 5→6    e-items 22→102  decoded 0
both>both>count      200→5000  4.89→ 9.79 ms  kv 4→4    e-items 22→102  decoded 0
out>out              200→5000  4.10→11.37 ms  kv 3→4
both>bothE           200→5000  3.94→16.29 ms  kv 4→4    decoded 200→5000
both>both>limit(100) 200→5000  4.37→12.62 ms  kv 5→6    （limit 仍读满整个 bin）
```

**分析与优化空间**：

1. **第二跳是批量读**：kv-calls 与 reach 无关（`both>both` 从 200 到 5000 都是 5~6 次往返，因为一层账户的
   邻居头是 native batch get）。latency 随 reach 增长是**子线性**的（reach 25×、latency 2×），不是往返数在涨，
   而是每个 bin 的**载荷**（一个一层账户的 both bin 从 20 条涨到 100 条）在涨。
2. **`both>bothE` 在构造边**：decoded = reach（5000 条边逐条建对象）→ 16.3 ms。只跳不取边就用顶点跳
   （`both>both`，decoded=0），引擎已经这么下推（§50.4/§50.5 的老结论）。
3. **`limit` 仍读满整个 bin**（`limit(100)` 的 e-items 与不 limit 相同）：内联 bin 没有"按时间的前 k 条"
   有序表示，cap 只截结果不省读。→ 与 §48.6/§50.5 的"`limit` 有序表示 / 摘要 bin"是同一个待办。
4. **`count` 读的是 bin 的形状**（decoded=0 ✓），但仍要把整份 bin 载荷拉回来：给 (顶点,边类,方向) 存一个
   **摘要计数 bin** 能把 count 变成 O(1) 且不拉 bin（老遗留）。

结论：引擎层（批量读、顶点跳不构边、count 不构边）已把往返数压平；剩下的成本是**载荷大小**与**按需截断**，
对应老待办"摘要 bin + limit 有序表示"。

### 53.5 回归

全量 `./mvnw test` **BUILD SUCCESS，0 失败 / 0 错误**（新增：LWW 断言语义更新、两跳扩展 loader、
两跳 benchmark、两跳 live-load）。

---

## 54. 第八批：表达式的字符串函数、display `$id`、count 交给服务端

第八批做的是 §51.6 / §53.4 里"明天要做"的那组：表达式能力、display 属性、以及 count 的载荷优化。

### 54.1 表达式里的字符串（与数值）函数

`filter` / `map` 的表达式语言现在可以在**任何值**上调用：

```
length()  toUpperCase()  toLowerCase()  trim()  toString()  isEmpty()  abs()
substring(from[, to])  replace(from, to)  indexOf(needle)
```

对 `it.get().value('k')`、裸属性、字面量、以及另一个调用的结果都成立（内部统一走 `String.valueOf`）。
`substring`/`indexOf` 按 Groovy 语义：**负索引从尾部数、越界夹紧**（`substring(-2)` 是最后两个字符）。
接收者是 null（属性缺失）→ 结果仍是 null，与比较运算"缺失值不等于任何值"的规则一致。

顺手把 §48.6 点名的 `value('id')` 疙瘩修了：`value('id')`/`value('$id')` 就是 `id()`、
`value('label')`/`value('$label')` 就是 `label()`——两种写法都认（`it.get().value('id')` 走访问器正则，
`def t = it.get(); t.value('id')` 走元素方法）。这样报错不再来自 schema。

实测/测试：新增 `GremlinStringFunctionTest` 7 条（变换、负索引/夹紧、链式、`abs` 保留整型/浮点、
缺失值仍缺失、`value('id')`/`value('label')`、错误 arity/未知方法）。`graph-query-gremlin` 全绿。

### 54.2 display 属性 `$id`（§46.4）

`VertexSchema` 新增 `displayName`，值 `$id`（常量 `VertexSchema.DISPLAY_ID`）表示"用 id 标这个点"：

* `displayPropertyNames()` 在 `displayName == "$id"` 时返回 `["$id"]`（优先于 property 上的 `display:true`）；
* `displayProperties()` 不变（点自己的属性仍可被 `values()` 读）；
* JSON 顶点新增 `displayName` 字段（`@JsonInclude(NON_NULL)`，没设就不落盘，schema 文件保持精简）；
* UI `displayLabel` 遇到 `$id` 直接渲染 `d.id`。

测试：`DisplayPropertyTest` +2（`$id` 覆盖 property 标记、JSON 往返 + 只出现一次 `displayName`）。
**没有把 demo 的 IP/Phone 改成 `$id`**——那会改 demo 的显示名并动到既有断言；改成一行 JSON 即可（`"displayName": "$id"`）。

### 54.3 count 交给服务端算（§53.4 的"摘要 bin"，换个更好的做法）

§53.4 说 count 虽然不构边（`decoded=0`）但仍要把**整份 bin** 拉回来，建议存一个摘要计数 bin。更好的做法是
**不存任何东西**：Aerospike 的 `MapOperation.size(bin)` 能在服务端把 CDT map 的条目数算出来，返回的是
**一个数字**而不是那张 map。

* `countEdges(vertex, class, dir)`（无 filter、无时间窗）→ `db.mapSize(key, bin)`（一次只读 CDT operate，按 bin 类别记读）；
* `countEdgesBatch(vertices, …)`（同上）→ `db.mapSizes(keys, bin)`：**一次 `BatchRead` 批量 operate**（含 CDT op 的
  批读必须用 `BatchRead`，`BatchWrite` 会报 "batch write operations do not contain a write"），一次往返，多把数字；
* 有 filter / 有时间窗时仍走原来的"读 bin 再走一遍"（窗口/条件本来就要看每条的值），行为不变。

**好处**：不用维护摘要 bin（不会与写入方漂移、不用改 upsert/delete/bulk-import 四处），但同样的效果——
count 的**载荷**从"整张 map"降到"一个数"。缺失的 bin/记录返回 0（Aerospike 对缺 bin 的 CDT op 会回一个
`bins == null` 的记录，要判空）。

实测（`AerospikeTwoHopExpansionLiveLoadTest`，真实 Aerospike）：`150` 的一跳 count = **50**、
`both` count = **50**、两跳 count = **4950**，与数据算出来的完全一致。

> 注意口径：benchmark 的 `kv-calls`/`e-items` 数的是**记录条数**，不是字节，所以表里 count 行看着没变；
> 省下的是线缆上的字节（一个 hub 的 bin 可能有上百条边）。这也是为什么这一条单列为"载荷优化"。

### 54.4 仍然没做（§51.6 余下）

* **把 `vt` 塞进边读**（消除无类名首跳的 +1 往返、并把命名类首跳也按类型过滤）——要动查询层的批读/邻居读
  与 store 的一组方法，本轮未做；
* UI 对"真的同时存了镜像边"的 store 折叠（§47.8）——没有数据需要它；
* `limit` 的有序表示（cap 不读满 bin）——需要有序布局（如按时间排序的 CDT list + `ListOperation.getByIndexRange`），
  是比 count 大得多的改动。

### 54.5 回归

全量 `./mvnw test` **BUILD SUCCESS，0 失败 / 0 错误**（新增 gremlin 7、schema 2，另有两跳 live-load 的 count 断言）。

---

## 55. 第九批：用户报的 12 件事（UI / 引擎 / 响应上限）

用户一次报 12 条。下面按条目写**根因**（能复现的都实测复现了）、**改法**、以及**没能复现的怎么处理**。
复现手段：`curl` 打 8081 + 用 headless Edge 驱动**真实 UI** 读 DOM（`.logs/investigate.js`、`.logs/search-check.js`、`.logs/verify-ui.js`）。

### 55.1 逐条

| # | 现象 | 根因 | 改法 |
|---|---|---|---|
| 2 | `g.V(135,'Account').both().both().both().both()` 报 `Unexpected end of JSON input` | 4 跳从 hub 出去命中**几百万**个 traverser，响应 **685 MB**，浏览器解析不了 | `QueryService` 给答案**设上限** `phoenixgraph.query.max-response-items`（默认 200000，0=不限；traverser 列表、subgraph 点/边都算），超限截断并在响应头 `X-PhoenixGraph-Truncated` 说明；UI 读到就提示"加 limit()"。实测同一条查询：685 MB → **45 MB**，头部 `X-PhoenixGraph-Truncated: 200000` |
| 3 | `... .both() //.both()` 报 `Expected '.' before step` | 引擎不认 Groovy 注释 | `GremlinQuery.stripComments`：解析前剥掉 `//` 行注释与 `/* */` 块注释；**字符串字面量内的标记不算注释**（URL/正则安全），被注释掉的步就当没有 |
| 12 | `...toList().size()` 报 `Unknown Gremlin step: size` | `size` 没注册 | 注册 `size` = `count`（Groovy 写法）；编译器里把 `count`/`size` 统一成 `isCountStep`（种子透传、下推合并、属性规划、边跳计数重写四处），所以 `outE('x').size()` 也会下推 |
| 11 | `...outE('Payment').toList()` 结果集不显示 | `showQueryResult` 只在"**不是**元素列表"时才填「结果」页签——元素列表被当成"画布已经画了" | 只要答案是列表就填「结果」页签；**元素列表按 `类型 / ID / 属性` 三列**一行一个元素（`showGremlinRows`），`pay_r1` 这类 id 一眼可见 |
| 4 | 统计页签只有总数 | 只有两个 `stat-card` | 增加**按类型明细**：顶点各类型 / 边各类型，三列对齐（名称 / 数量 / 占比），CSS grid + `tabular-nums` 对齐数字 |
| 5 | 下拉里的 Cypher 先隐去 | — | `languageSelect` 的 cypher `option` 加 `hidden disabled`（保留在标记里，恢复只改一个属性）；`queryLanguage` 提示文案同步 |
| 6.1 | 示例查询描述不随中英文切换 | 示例标题是**写死的中文**，只有查询语言（gremlin/cypher）分组 | 示例条目改成 `[中文标题, English title, query]`，`refreshExamples` 按界面语言取；`applyI18n` 里补调 `refreshExamples()`（切语言立刻换） |
| 6.2 | Gremlin 前三个示例的 account id 查不到出边 | `g.V('1000','Account').out('AccountToPhone')` —— **v3 demo 的关联是单向 `Phone/IP → Account`**，`AccountToPhone` 根本不存在，返回空 | 改成 `in('PhoneToAccount')`；Cypher 里两条同类示例（`AccountToPhone` / `AccountToIP`）也把方向倒过来 |
| 7 | Legend 只有点，没有边；点选/取消 | legend 只由点生成 | legend 追加**边条目**（线型色块 + `标签 · 条数`，带「边」小标题）；点一下高亮该类边**并保留其两端点**（否则高亮边吊在两个暗点之间），再点一下清除。实测：`边 27 条选中 → edgeHit 1 / edgeDim 27 / 点暗 3（两端点亮）`，再点 `edgeDim 0` |
| 10 | 边在 UI 显示但"不在下载" | `exportCsv` **连续触发两个下载**，浏览器常把第二个（边）拦掉——于是只有点表到了 | 导出**一个文件** `phoenixgraph.csv`，首列 `kind`（vertex/edge）区分，点边**一定一起到** |
| 9 | filter 之后画布上还多出 1002 和 Phone→1002 | **查询失败时画布保留上一次的结果**：用户先跑了一条出现 1002 的查询，接着 filter 那条**报错**（如注释/表达式问题），画布没变 → 看起来像 filter 没生效。实测复现：失败后 `v/e` 仍是上一次的 51/76 | 新增 `queryFailed()`：显式查询失败时**清空画布** + toast 说明（"避免把上一次的结果当成这次的结果"）。实测：失败后 `v=0, e=0` |
| 1 | 搜 69999 只高亮 seed | **未能复现**：`g.V('1001','Account').both()` 后搜 69999 命中的就是 `+86 138-69999`（`phoneNumber=138-69999` 与 id 都含 69999）。可能的感受差异是"命中点周围全暗了" | 命中点的**邻居与相邻边保持可见**（与"命中边保留两端点"一致）；画布内**完全没命中**时 toast 提示"搜索只作用于画布内"，不再只是整屏变暗 |
| 8 | 「结果」显示 49、stats 显示 46 条边，逻辑对吗 | **两个数数的不是一回事**：结果是**答案项数**（traverser，可重复），stats 是**画出的子图**（去重后）。实测同一条：`result=27`、`subgraph edges=28`（多出来的是遍历用的 `PhoneToAccount` 那条），而 `graph.edges` 与画布/导出/统计三者一致 | 不改口径（两者都对），而是**让两个数都看得见**：结果页签列出答案列表，「结果 N 项 · 子图 点 V / 边 E」写进状态行，统计页签给按类型明细。item 10 的"少了边"是导出被拦，已按上表修 |

### 55.2 UI 其它一并修的

* **超大答案不再卡死页签**：`showRows` 最多画 2000 行，并在提示条写"只显示前 N 行，共 M 行"；
* **响应被截断/截断不完整**：`runQuery` 先 `text()` 再 `JSON.parse`，解析失败给出人话（"响应过大，未完整传回（已接收 X MB）"），而不是 `Unexpected end of JSON input`。

### 55.3 只动到"没复现"的地方怎么算

第 1 条实测是好的（搜索命中了那个 Phone）。但没有停在"我这能跑"，而是把它变成**更不容易被误读**：
命中点的上下文（邻居 + 相邻边）保持可见、画布内无命中时明确提示。第 8 条同理——两个数字本来就不同含义，
所以做的是**同屏可见 + 口径写清楚**，而不是把其中一个改成另一个。

### 55.4 测试

* 引擎：`GremlinQueryTest` +4（行注释 / 整行 + 块注释 / 字符串里的注释标记不算 / 只有注释则报错），
  `GremlinGraphQueryTest` +1（`size()` 与 `toList().size()`）；
* HTTP：`QueryControllerTest` 桩/校验改到 `queryCapped`，并新增 **截断头** 用例（11 条）；
* 全量 `./mvnw test` **BUILD SUCCESS，0 失败 / 0 错误**。

### 55.5 未做

* 第 1 条若用户的意图是"**全库搜索**"（不在画布上的点也找出来）——那是新功能（需要按属性建索引或全扫），
  本轮做的是把"画布内搜索"讲清楚；要做请确认。
* 第 8 条若要"结果数与子图数严格一致"，只能让结果去重或把遍历边也算进结果——两种都会丢信息，故未改。

---

## 56. 第十批：算法（13–15）、legend / 页签 / 示例（17–21）、子图把 filter 掉的边留下（22）

### 56.1 `#22`：**真 bug** —— filter 掉的点，它那条"走过"的边还在子图上

用户给的完整语句：

```groovy
g.V('+86-138-69999','Phone').out('PhoneToAccount').filter {it.get().values('id') == 1002}.outE('Payment')
```

实测（修前）子图里有 **两条** `PhoneToAccount`：

```
PhoneToAccount:+86-138-69999:1002   ← 遍历路径上，应该留
PhoneToAccount:+86-138-69999:1003   ← filter 已把 1003 去掉，却还在！
```

**根因**：子图收集是"读到即记"，`filter` 丢掉的是**那个点自己**（`dropElement(vertex)`），而"通向它的那条边"没被丢。
`buildSubgraph` 的裁剪规则是"边的两端都还在遍历里就留"——而 1003 恰好又是 `pay_r2`（1002→1003）的**目标点**，
所以它仍然是"活"的，于是 `Phone→1003` 被这条**太宽松的规则**留了下来。这也是 `#9`（用户上一批报的同一现象）的**真正根因**，
上一批我只做了"失败清空画布"，这次找到并修掉了。

**修法**：顶点跳（`out/in/both`）读的边，本来只是"到对端点的路"。给 `GremlinContext` 加
`recordHopEdge(边, 到达的点)`（`GremlinSteps.readNeighbourVertices` 两处调用），`buildSubgraph` 里：

```
顶点跳的边：只要它到达的某个点还在遍历里 → 留；否则丢
边跳（outE/inE/bothE）的边 / 没记过来源的边：仍用原来的"两端都在"规则
```

这样 `Phone→1003` 的来源只有 1003，而 1003 已不是遍历点 → 丢掉 ✓；`pay_r2` 来自边跳（`.outE('Payment')`）→ 原规则 ✓。

修后实测子图正好是路径：`Phone→1002`、`pay_r2`、`pay_x2`、`pay_y2`。新增引擎用例
`aDroppedVertexTakesItsEdgeWithItEvenWhenAnotherPathKeepsItAlive`（专门构造"被丢的点又被另一条路保活"的形状）。

### 56.2 `#16`：不是非确定性，是**随机初值布局**骗了眼睛

把那条语句在 API 上连跑 **20 次**：`accounts=1001,1002,1003,...`、`1001:27 1002:3`、边集逐条一致 —— **STABLE**。
所以引擎没有非确定性。看起来"一次多边、一次一条边"是因为：

* 初值坐标用了 `Math.random()`，同一查询每次布局不同；
* 1001↔1002 之间有 **10 条平行边**（`pay_r1` + 9 条 `pay_rep_*`），画成弧线；节点位置一变，弧的可见程度差别很大，
  远处看就像"一条边"。

**改法**：初值坐标改成按节点 key 的**稳定哈希**（`hashOf`），同一查询两次画出**完全相同**的图（实测两次 `transform` 逐点相同），
从此"两次看不一样"不会再发生。平行边本身用弧线区分（`EDGE_ARC_STEP`），点选一条边可在「属性」里看它是哪一条。

### 56.3 `#20`：75 是**答案项数**，67 是**去重后的边数**，两个都对

`g.V('+86-138-69999','Phone').out('PhoneToAccount').bothE('Payment')`：

| 数 | 值 | 含义 |
|---|---|---|
| `count()` / 结果项数 | **75** | traverser 数（同一条边被它**两个端点**各输出一次） |
| 结果里的不同边 | **64** | 去重后 |
| 重复项 | **11** | 两端点都在答案里的那些边 |
| 子图边 | **67** | 画出来的（64 + 3 条遍历用的 `PhoneToAccount`） |

Gremlin 语义就是如此（`bothE` 不去重，`dedup()` 才去重）。**行数也是 75**（结果页签照实列）。
改法不是改口径，而是**把它讲明白**：结果页签在元素列表有重复时，提示条直接写
"75 行，其中 11 行与前面的行是同一个元素（同一条边被两个端点各输出一次；用 dedup() 去重）"。

### 56.4 `#13` + `#14` + `#15`：CC / Louvain / PageRank

**#13 异构图**：`cc`/`louvain` 只对"同一种点"有意义（异构图里的"连通分量"其实是"通过地址可达的一切"）。
现在它们：

1. 取**点最多的那一类**运算；
2. 把**其它类型投影成该类点之间的边**（一个 IP 连到的账户两两相连 —— 也就是"共用这个 IP 的账户"），
   否则（只留同类点、不留边）每个点都会各自成一个分量，等于什么都没算；
3. 在 toast + 状态里说明：`该图混了多种点（另有 Account），Louvain 只在最多的 IP（17 个点）上运算；其它类型的点被投影成 IP 之间的 136 条边`；
4. 不在运算范围内的点**变灰 + 变暗**（`algo-out`），legend 的社区条目也只数被运算的点。

投影有上限：一个桥点连接的同类点超过 `PROJECTION_MAX = 40` 就跳过它（否则一个 80 邻居的 IP 会变成 3160 条边）。

**#14 正确性复核**：把 `app.js` 里的 `connectedComponents` / `louvain` / `pagerank` / `buildAdjacency`
**按大括号配对抽出来**在 node 里跑（跑的就是浏览器执行的同一份代码），与独立实现对比：

```
PASS  CC: two components {a,b,c} and {d,e}
PASS  CC: agrees with union-find
PASS  PageRank: the star centre ranks first            hub=0.4757 leaf=0.1311
PASS  PageRank: ranks sum to 1                         sum=1.0000000000000002
PASS  PageRank: agrees with a matrix iteration         maxDiff=1.99e-07
PASS  CC on live data agrees with union-find           UI=[105] UF=[105] (nodes=105)
PASS  Louvain on live data: modularity > 0 and beats one community   Q=0.1713 vs single=0.0000 communities=23 nodes=51
PASS  Louvain assignments are within the graph and partition it      51/51
PASS  Louvain on live data is locally optimal (no single move gains modularity)  best gain = 0.00e+0
PASS  PageRank on live data agrees with a matrix iteration           maxDiff=2.47e-08
PASS  the projection keeps the groups of the raw graph  raw=[40,3] projected=[40,3]
PASS  the projection is a clique per bridge (C(40,2) + C(3,2))  edges=783 skipped=IP
```

独立实现：并查集（CC）、矩阵幂迭代（PageRank）、按定义算模块度 Q（Louvain）。Louvain 除了 Q 更高，还验证了
**局部最优**（任一点挪到邻社区都不增加 Q）—— 这是 Louvain 局部阶段的定义性质，比"跑出了东西"强得多。

**#15 例子**（已在 UI 实测）：

| 算法 | 查询 | 结果 |
|---|---|---|
| **CC** | `g.V('10.99.0.0','10.99.0.1','IP').out('IPToAccount')` | 45 点 / 43 边 → **2 个连通分量（40 + 3 个账户）**：两个 IP 的账户集合**不相交**（`10.99.0.0` 连 40 个、`10.99.0.1` 连 3 个）。因为 CC 只对同类点运算，IP 被投影成账户之间的边，所以分量落在 IP 各自的账户集合上 |
| **Louvain** | `g.V('135','Account').both('Payment').both('Payment').dedup().limit(300)` | 51 个账户 / 250 边 → **23 个社区**，Q=0.1713（单社区 Q=0） |

（顺带一个反例：`g.V('10.99.0.6','10.99.0.8','IP').out('IPToAccount')` 的 105 个点全在**一个**分量里 —— 这两个 IP 的账户集合有交集。）

### 56.5 UI 细节（17 / 18 / 19 / 21）

| # | 改法 |
|---|---|
| **17** | legend 去掉「边」标题；点、边两组之间**空一行**（12px gap）；`legend-heading` 样式删除 |
| **18** | 查询**不再自动跳到「结果」页签**：默认留在「统计」，新的答案给「结果」页签加一个**小圆点**（`unread`），点进去就消掉 |
| **19** | 示例下拉**默认不显示**，只有点图标才展开/收起；某图没有示例时连图标也隐藏 |
| **21** | 点条目也带数量（`Account · 8`），和边条目（`Payment · 7`）一致 |

### 56.6 测试

* 引擎：`GremlinGraphQueryTest` +1（被丢的点带走它的边，即使该点被另一条路保活）；
* 算法正确性：`.logs/verify-algorithms.js`（抽取 + 独立实现对比，12 项全 PASS）；
* UI：`.logs/verify-ui2.js`（headless Edge 读真实 DOM：页签、示例、legend 计数、异构提示、确定性布局）；
* 全量 `./mvnw test` **BUILD SUCCESS，0 失败 / 0 错误**。

### 56.7 未做

* 投影上限 40 是拍的值：桥点邻居更多时**跳过**（宁可少边，也不让浏览器画 3000 条边）。要更准确的语义可以改成
  "按相似度/权重只连前 k 个"，但那需要定义权重，未做。
* `#16` 的平行边仍靠弧线区分（10 条同对端点的边在缩小时确实难数）；如果要"一眼看出几条"，
  需要在小节/边上显示"×10"，本轮未做。

---

## 57. 第十一批：闭包语句（if/else、集合）（28）、`values()` 的元素自身列（27/29）、空答案不画（24）、
## 结果面板只报数目（26/23）、Rows 的属性列（25），以及 135/150 两跳 benchmark

### 57.1 `#28` 两个真 bug：`if (...) { }` 被当成"尾部 logic 块"，闭包语句从不按序执行

**bug 1 —— 解析器**：`indexOfLogicBrace` 找"尾部的 `{ $logic }`"，判据是"`{` 前面是 `)` 或 `}`"。
`map { ...; if (cond) { a } else { b } }` 里 `if (cond)` 后面的 `{` 前面正好是 `)` → 被误判为 logic 块，
于是 `rest` 被截断到那里、子句 `{ a } else { b } }` 被丢弃 → `Unbalanced braces`。
修法：扫描时**记深度**（括号/花括号），只有**深度 0** 的 `{` 才可能是 logic 块。括号内的 `if (cond) {` 不再被误认。

**bug 2 —— 求值顺序**：`program()` 只把语句建成一棵 Value 树并返回**最后一条**语句的值，
所以中间语句（`def list = new ArrayList(); list.add(x)`）**从来没被执行**：
`def l = new ArrayList(); l.add(1); l.size()` 返回 0。修法：`program()` 返回一个**按序执行**的复合值
（每条语句依次求值，返回最后一条的值）。这对"`def` 绑定 + 后续修改"是必须的。

**新增的闭包语句能力**：

* `if (cond) { ... } else { ... }`、`else if` 链、单语句分支（不带花括号）；`if` 的值是**分支的值**，
  没有 `else` 且条件为假时为 null（Groovy 语义）；
* `new ArrayList()` / `new LinkedHashMap()` / `new HashSet()`（全限定名也行；只支持这三种，其它类型明确报错）；
* 集合方法：`add`/`addAll`/`put`/`get`/`size`/`isEmpty`/`contains`/`containsKey`/`join(sep)`。
  集合是**每个元素一份**（每次 `evaluate` 清空一帧），但同一次求值内多次引用共享同一个实例 ——
  `def list = new ArrayList(); list.add(x); list.join('/')` 是**一个**列表。实现是 `ThreadLocal` 的
  per-element frame（编译后的表达式会被并发复用，所以不能用一个可变字段缓存）。

### 57.2 `#27` / `#29`：`values('createdTime'/'lastUpdatedTime'/'source'/'target'/'id')`

`schema` 把 `createdTime`/`lastUpdatedTime` 声明为**元属性**、把 `source`/`target` 声明为**有向边的两端**，
它们都不在 `properties()` 里，所以 `values('lastUpdatedTime')`/`values('target')` 以前报 `Unknown property`。

修法：这些名字成为**元素自身的列**（伪键 `@createdTime`/`@lastUpdatedTime`/`@source`/`@target`，`@id`/`@label` 早已支持）：

* 属性规划器**不再**把这些名字当属性去问 schema（所以不会报未知属性，也不会白读）；
* `values(...)` 步在元素**没有**这个属性时，用元素自身回答：`values('id')`、`values('createdTime')`、
  `values('source')`（有向边按 side 判断：OUT 边从读取点出发，IN 边反向）；
* 表达式里两条路径都通（`it.get().values('createdTime')` 走访问器正则，`def v = it.get(); v.values(...)` 走元素方法——
  后者原来只认 id/label，现在统一走同一个 `resolveKey`）；
* **有同名真实属性时真实属性优先**（`properties.get(key)` 非空就返回它），所以不破坏已有 schema。

### 57.3 `#24`：空答案不画任何东西

`g.V('+86-138-60006','Phone').out('PhoneToAccount').filter{...}.both('Payment')` 返回 0 项，
但子图里还留着 seed 那个点。修法：`executeWithSubgraph` 里**答案为空 → 子图为空**。
理由：seed 只是"路过的"，把它画出来配上"0 项"会让人以为它就是答案，而它恰恰是被排除的那个。

### 57.4 `#23` / `#26`：`toList`/`toSet` 的语义，和"面板只报数目"

* `toList()` → 列表（有序、可重复）：`map{...}.toList()` 实测 **37 项** `["S","S",...,"P"]`；
  `toSet()` → 去重：**2 项** `["S","P"]` —— `toX` 就是把迭代器里的元素收进集合 X，两者语义已一致；
* 面板**只报数目**：多于一项时显示 `2365 项 · 137 个不同值`（`toList` 是 `37 项 · 2 个不同值`），
  不再预览具体值（Rows 页签列具体值）；一项时仍显示它本身（`count()` → `76`）；
  元素答案的面板也**不再隐藏**（现在只报数目，不占地方）。

### 57.5 `#25`：Rows 的"属性"列不再为空

`g.V(1001,'Account').both('Payment').both().dedup()` 的 111 个 traverser 只带 id（跳步在"后面没人要属性"时不投影），
而**子图**为了画图读了完整属性。修法：`propertiesOfElement(value)` —— traverser 自己有属性就用它，
否则从**已画的子图**里取同一个元素（`label::id`）。实测 111 行、0 个空属性列。
（`map { it.get().toString() }` 那种答案是**字符串**列表，落在一列"值"里，值本身就是元素的 toString，没有属性列可言。）

### 57.6 `#30`：输入框接受"正确的 gremlin 片段"

本批的 27/28/29 正是这条的具体内容：`values('lastUpdatedTime')`、`if/else` 闭包、`values('target')`
以前都不是"正确的 gremlin 片段却被拒绝"。现在这些都能跑，结果照常进 Rows 页签（元素三列 / 标量一列）。

### 57.7 135 / 150 两跳 benchmark

`node .logs/bench-two-hop.js 30`（30 次测量、5 次预热、计数器取**查询前后差值**）。查询形状取自本批要求：
`g.V($id,'Account').both('Payment').both().map { it.get() }`，以及若干对照。

**hub 135（一跳 76 笔支付 → 两跳 2365 个 traverser）**

```
query                mean    median   p95     p99     max     kv  vRead  eRead  eDecoded   answer   subgraph   size
A  map { it.get() }  48.56   48.74   55.64   58.89   58.89    29    137    914      2441     2365   137v/1135e   423 KB
B  (no consumer)     61.35   62.23   72.41   77.21   77.21    34    274    914      2441     2365   137v/1135e   705 KB
C  count()           43.12   44.94   47.91   48.68   48.68    29    137    914      2441        1   137v/1135e   227 KB
C2 typed count()     22.69   24.69   28.20   28.60   28.60     5     51    102       500        1    51v/250e     55 KB
D  values(id)        45.12   47.05   48.33   49.08   49.08    29    137    914      2441     2365   137v/1135e   251 KB
E  one property      22.67   25.90   28.02   28.18   28.18     7    102    102       500      987    51v/250e     64 KB
F  batch(1)         102.68   95.22  110.30  341.41  341.41   917    137    914      2441     2365   137v/1135e   423 KB
G  batch(100)        53.50   53.64   62.84   68.43   68.43    17    137    914      2441     2365   137v/1135e   423 KB
H  barrier(40)       46.84   48.42   52.56   58.10   58.10    29    137    914      2441     2365   137v/1135e   423 KB
```

**hub 150（一跳 100 笔 → 两跳 1857 个 traverser）**：同一形状，A `50.11/51.91/56.24/56.79 ms`、29 kv；
F `batch(1)` `113.71 ms`、**1205** kv；G `batch(100)` `56.74 ms`、17 kv；C2 `17.76 ms`、5 kv、200 边。

**优化逐项核对**

| 优化 | 结论 | 证据 |
|---|---|---|
| **批大小** | ✅ 应用的，而且是**最大杠杆** | `batch(1)` 917~1205 次调用、95~114 ms；`batch(100)` 17 次；默认（自适应）**29** 次。往返数降 50 倍，时延降约一半 |
| **自适应批大小** | ✅ 生效但**略保守** | 默认 29 次 vs 显式 `batch(100)` 17 次（默认的块比 100 小）——想更快可以显式 `batch(100)` |
| **按需加载属性** | ✅ 应用的（省的是**载荷**，不是读次数） | `map{it.get()}` → 计划要全部属性，响应 423 KB（135）/529 KB（150）；`values('id')`（伪键，不需要任何属性）→ 251 KB/398 KB；`values('accountStatus')`（一个属性）→ 64 KB。**注意**：顶点记录本身仍要读一次（一个 key 一条记录），所以 `vRead` 不变，省的是线上字节 |
| **count 下推** | ⚠️ **类型化才下推** | `both('Payment').both('Payment').count()` → **500 条边被构造**（其余由 store 数 bin 形状），5 次调用、22.7 ms；而未命名类的 `both().both().count()` → **2441 条边照建**（未命名跳要读每一类，无法下推），43.1 ms |
| **缓存/往返** | ✅ 应用的 | 2365（135）/1857（150）个 traverser，只用了 **29** 次存储调用 → 每次调用摊到 **64~82** 个 traverser；同一顶点同一条边的重复读取由"每次执行一份"的读缓存兜住 |
| **barrier** | ⚠️ **不是真 barrier** | 这个引擎是内存列表式的，`barrier(n)` 与 `batch(n)` 一样只设**批量大小**：`barrier(40)` 与默认同为 29 次（135）；150 上是 41 次（40 < 自适应的块）。要"屏障语义"在当前架构里没有意义 |

### 57.8 测试

* 引擎：新增 `GremlinClosureStatementTest`（11 条：if/else、无 else、else-if 链、`new` + `add`/`join`、
  每个元素一份集合、map 集合、contains/isEmpty、不支持的类型报错、元属性与端点、票据原样的形状、
  带 `if` 的 `map` 不被当成 logic 块），`GremlinGraphQueryTest` +2（`values` 的元素自身列、空答案不画）；
* 全量 `./mvnw test` **BUILD SUCCESS，0 失败 / 0 错误**（gremlin 183）；
* 验证脚本：`.logs/verify-ui3.js`（headless Edge：空答案 0 点、111 行属性非空、面板只报数目）、
  `.logs/bench-two-hop.js`（本节的表）。

### 57.9 未做

* `new` 只支持 ArrayList/HashMap/HashSet（`new TreeMap(cmp)` 之类明确报错不装作支持）；
* `barrier` 仍是"批量大小"的别名（真屏障＝分阶段执行，当前引擎没有阶段概念）；
* `map { it.get().toString() }` 这类答案是字符串，Rows 只有一列"值"（值里含 toString 的内容）——
  如果要把"元素转字符串"也渲染成三列，需要另定义元素文本协议，未做。

---

## 58. 第十二批：fuzzy 节点的 xia 账号、图实例收敛到四个、payment-graph 的向量、RocksDB-Edge 的 GEO、去掉 FuzzyPattern

### 58.1 `#1` FuzzyLink 节点现在同时挂着 zhang 和 xia

`fl_0_mail.ru_86_10.20_re_a_z_6759fdcb` 之前只挂着 6 个 `zhangXXXX`。现在演示数据多了一个
`fz_a7 / xia1999`（`xia1999@mail.ru`、`+8613800138007`、`10.20.66.8`）——pattern 0 的键里放的是正则的**公式**
（`^[a-z]+` 的形状）而不是它匹配到的词，所以第二个名字词落在**同一个** link 节点上：

```
g.V('fl_0_mail.ru_86_10.20_re_a_z_6759fdcb','FuzzyLink').out('FuzzyLinkedTo')
-> fz_a1..fz_a6 (zhang1988/1979/1990/1972/1995/1968) + fz_a7 (xia1999)
```

它的邮箱名前缀不是四位数字，所以 pattern 1（机器生成邮箱 + 卡 BIN）**不**把它算进去，第二个环仍是 4 个。

### 58.2 `#5` 从 payment-graph 的 schema 去掉 FuzzyPattern

`FuzzyPattern` 顶点类型是**遗留**：模式其实住在 `fuzzy-patterns.json`（分析师编辑的文件），
链路的写入只写 `FuzzyLink` 顶点 + `FuzzyLinkedTo` 边（`FuzzyLinkingService` 从来不写 pattern 顶点）。

* `payment-graph.json` 删掉 `FuzzyPattern` 顶点（`FuzzyLink` 保留）；
* `FuzzySchema.isSupported` 相应放宽：只要求 **link 顶点 + link 边**（不要求 pattern 顶点），并在 javadoc 里写明原因；
* `FuzzyLinkDemoLoader` 不再写 pattern 顶点（`patternVertex(...)` 删除）。

实测：`payment-graph` 的顶点类型现在是 `Account, IP, Phone, FuzzyLink`；fuzzy 的 11 条集成测试全绿
（环 0 = 7 个账户、环 1 = 4 个）。

### 58.3 `#2` 图实例收敛到四个

`application.yml` 的 `graphs:` 只留三个（加上 primary 的 `payment-graph` 共四个）：

| 图 | 存储 |
|---|---|
| `payment-graph` | Aerospike（primary） |
| `payment-graph-as-edge` | Aerospike-Edge（聚合边表） |
| `payment-graph-rocksdb-edge` | RocksDB-Edge |
| `kg-entity-graph` | Aerospike（知识图谱） |

其余（`payment-graph-rocksdb-inline`、两个 geo-merchants、`hnsw-sift`、`hnsw-dynamic`、两个 smart）
**注释保留**在同一文件里，想再服务它们只需取消注释 —— 没有删掉任何一个 schema。

### 58.4 `#3` payment-graph：每个账户都有 `accountVector`，HNSW 可查、可扩展

* schema：Account 新增 `accountVector`（id 13，8 维 `list DOUBLE`，`isVector`，COSINE，m 8/efConstruction 64/efSearch 32）；
* 数据：演示播种给每个账户一个**确定性**的 8 维向量，落在 3 个消费画像（现金型/卡型/混合）之一 + 每账户的抖动 ——
  所以 `nearest(k)` 返回的是同一簇的账户，重新加载两次答案不变；
* 索引：`POST /api/v1/vector-index/rebuild {"graph":"payment-graph","vertexSchemaName":"Account","vectorProperty":"accountVector"}`
  → 实测 `vertices=152, layers=9, links=2904, pruned=1472, 6.9s`；`GET /api/v1/vector-index` 显示 `size=152`。

样本查询（**既有相似向量结果，又能继续扩展边和点**）：

```groovy
// 相似账户
g.V('1001','Account').nearest(5)                                  -> 1004, 1007, 1010, 1013, 1016
// 相似账户的出款（向量 → 边）
g.V('1001','Account').nearest(5).outE('Payment')                  -> 15 条 Payment
// 相似 → 收款方 → 再找相似（向量 → 点 → 向量）
g.V('1001','Account').nearest(5).out('Payment').dedup().nearest(3) -> 6 个账户
// 相似 → 双方 → 去重
g.V('1001','Account').nearest(5).both('Payment').dedup().limit(10)
```

### 58.5 `#4` payment-graph-rocksdb-edge：账户带 `location`（GEO 可用）+ `accountVector`；GEO 样本

* schema：Account 新增 `accountVector`（id 8，同参数）+ `location`（id 9，`map DOUBLE`，`isGeo`，S2）；
* 数据：`PaymentGraphRocksDbLoader` 给每个账户写入**确定性**的 4 个片区之一（人民广场/徐家汇/陆家嘴/静安，
  中心 ±1.5 km 抖动）—— 所以 5 km 搜索能捞出"一个街区"的账户。同一份 loader 仍能装 inline 方案
  （只有 schema 声明了这两个属性的图才会写它们）；
* 重载：`.rocksdb/payment-graph-rocksdb-edge` 重新播种（151 点 / 300 边，见 `RocksDbLiveLoadTest`；
  RocksDB 目录被服务占用，重载前要先停服务）。

实测（`node .logs/geo-vector-check.js payment-graph-rocksdb-edge`）：

```
g.V('1001','Account').circle('{"lat":31.2304,"lon":121.4737,"radius":5000}').limit(10)
  -> 10 个账户：1030, 1041, 1023, 1012, 1034, 1001, 1005, 1045, ...
g.V('1001','Account').circle(...).limit(10).outE('Payment')        -> 30 条 Payment（可继续扩展）
g.V('1001','Account').circle(...).limit(10).out('Payment').dedup() -> 它们付给谁
g.V('1001','Account').circle(...).limit(5).values('id','distanceMeters')
  -> 1030, 488.2 米, 1041, 505.9 米, 1023, 660.9 米, ...
```

**向量那一半在这里不可用，且这是既有设计**：索引（HNSW 的层边 + 元数据）住在 Aerospike 的边 bin 里，
`VectorIndexManager` 明确拒绝 RocksDB 图：

```
Graph 'payment-graph-rocksdb-edge' is served by RocksDB-*; a vector index lives in the
Aerospike edge bins and is only available there
```

即 `GET /api/v1/vector-index` 会列出这个图的 `accountVector` 索引但 `size=0`，`nearest` 报
`No HNSW index is configured for vertex type 'Account'`。数据、schema、属性都在，缺的是**索引后端**。
（要真在这张图上做向量检索，需要一个新的索引后端：`HnswStore` + 元数据 + 向量读取都走 RocksDB，
或者把索引托管到 Aerospike 而向量从 RocksDB 读 —— 两条路都要新代码，见 §58.7。）

所以 UI 的示例查询里：`payment-graph` 给向量样本，`payment-graph-rocksdb-edge` 给 GEO 样本（各 3–4 条，双语标题）。

### 58.6 实测与测试

* fuzzy 集成测试 11 条：环 0 变 7 个账户（六个 zhang + 一个 xia），环 1 仍 4 个；去掉 pattern 顶点后
  `isSupported` 仍为真、链路写入照常；
* RocksDB 重载：`payment-graph-rocksdb-inline` / `-edge` 各 151 点 / 300 边 ✓；
* 全量 `./mvnw test` **BUILD SUCCESS，0 失败 / 0 错误**；
* UI（headless Edge 实测）：图下拉 = 4 个，两个图的示例查询分别列出向量 / GEO 样本。

### 58.7 未做 / 待确认

* **RocksDB 图的向量索引**：需要新的索引后端（RocksDB 的 `HnswStore`，或"索引在 Aerospike、向量在 RocksDB"
  的托管方案 + 用查询路径枚举待索引的点）。本轮没做。
* `location` 只加在 RocksDB-Edge 的 Account 上，`payment-graph`（Aerospike）**没有**加地理属性 ——
  按 `#3` 的要求只加了向量。
* 演示数据的两套来源仍不同：`http_demo_v3`（Aerospike，`QueryApiIntegrationTest#demoSharedGraphViaApi`）
  与 `.rocksdb/payment-graph-rocksdb-edge`（`PaymentGraphRocksDbLoader`，135 + 50 账户）。两者是**同一个图的两种装量**
  （同样的表形状、同样的 hub 135），但不是逐行相同。若要逐行一致，把 `seedSharedGraph` 抽成共享 loader 即可。

---

## 59. 第十三批：`both('Payment')` 两跳 + `values(3 个属性)` 的性能测试（100 个 id × 8 轮 = 800 次）

被测查询（用户给的形状，`$id` 是 100 个有效账户 id）：

```groovy
g.V($id,'Account').both('Payment').both('Payment').values('accountUsage','firstName','accountStatus')
```

测试：`graph-integration-test/.../AerospikeHundredSeedsValuesBenchmarkTest`（6 个用例，全部通过；活测试，
Aerospike 不可用或数据没装时自己跳过）。id 的取法：先试 `135/1001/2017/9000001/150/11`，再 `scanAll` 扫
`http_demo_v3` 里 `vt == 'Account'` 的记录（172 条），逐个跑一次查询丢掉空答案的（2 个），取满 100 个。

### 59.1 测量方法（为什么这些数字可信）

* **一条查询一次执行**：`metrics.snapshot()` 前后取差得到这次执行的计数器增量，所以每行都是"每次查询"的量；
* **每次存储调用都记下来**：给 `GraphQuery` 套一层 `Proxy`，按方法名取参数位置（`readVertices(name, ids, props)` 的
  ids 在 1、props 在 2；`readVertices` 与 `readVertex` 只差第 9 个字母，前缀匹配要小心）——得到每次读的
  **批大小**和**属性名列表**，这就是回答第 2、3 问的证据；
* **计数器语义**：`storage.kv.read.call` = 往返次数（一个 batch = 1 次），`vertex.read` / `edge.read` = 命中的记录数
  （`readBatch` 里每个 key 记一次），`edge.decoded` = 真正解码的边数；
* **响应字节**用 Jackson 序列化结果算（`values()` 的结果是纯值，可以序列化；整顶点那组只是参照，字节数仅供参考）。

### 59.2 总览（800 次执行，2026-09-21 收工时实测）

```
延迟     mean 4.96 ms  min 2.52  p50 4.82  p90 6.47  p95 6.96  p99 8.32  max 11.27
吞吐     202 查询/秒        墙钟 3.97 秒
结果     416 项/查询        响应 5.5 KB/查询
流量     kv.read.call 5.90 /查询（max 6）   记录 85.4   顶点 74.4   边 11.0   解码边 0
```

**一次查询只有 ~6 次往返**，因为它不需要"边"：两跳都是**顶点跳**，中间那步只读**邻居引用**（id + 对端 id），
所以 `edge.decoded = 0`，没有任何 `Payment` 边被解码。最慢的种子是 135（7.2 ms）和 150（6.7 ms），最快的几个
`fz_*`（3.5 ms）——差的就是扇出。

### 59.3 问题 1：缓存有效吗？

有效，但**收益有限且要说清楚边界**（同一 workload，缓存开/关各跑 100 个 id）：

```
缓存开   590 次往返   5.15 ms/id
缓存关   700 次往返   5.90 ms/id
省 15.7% 的往返（1.10 次/查询），延迟省约 13%
最忙的种子 150：5 次往返（开）vs 6 次（关）
```

* **作用域是一次执行**：`GremlinContext` 每次 `execute` 新建（`newContext()`），里面的
  `vertexCache`/`edgeCache`/`neighbourCache` 随之作废。有一个用例专门断言"同一 id 连续两次执行的往返次数完全相同"
  ——**这正是写后读不会读到旧值的原因**（没有跨查询缓存，所以也就没有失效问题）。
* **省在哪**：省在"同一执行内重复读到同一个顶点/同一对 (顶点,边类,方向)"。单种子两跳的遍历树几乎是一棵树，
  重复不多，所以整体只有 ~16%；扇出大、回边多的种子（150）省 1/6。
* **它撑住了什么**：一次查询 416 项结果只读了 74 个顶点记录（`readVertices(1) + readVertices(50)` 覆盖 51 个去重
  顶点、`readNeighboursBatch` 覆盖 100 个）——扇出大的时候缓存与批读一起把"每条边一次读"压成"每批一次读"。
* **没做**：跨查询缓存（要处理失效，`vt`/`lastUpdatedTime` 已具备条件，但本轮没做）。所以仪表盘反复刷新同一个
  查询会付全价——这是拿正确性换的，不是遗漏。

### 59.4 问题 2：batch size / barrier size 自适应到合适的值了吗？

自适应规则（`GremlinContext.adaptBatchSize`）：`batch = clamp(上一跳扇出 / 2, 当前值, 100)`，默认 50、
上限 100；`batch(n)` / `barrier(n)` 显式给值时**不再调**（`explicitBatchSize`）；`has/filter` 之后也不调。

实测（每种子一次执行，记录每次读带了多少 id）：

```
189 次读带 2..100 个 id，200 次读只带 1 个 id
  ids <= 10    34 次 (18%)      ← 小扇出，本来就没得批
  ids <= 25     9 次 ( 5%)
  ids <= 50   145 次 (77%)      ← 默认 50 是绝大多数
  ids <= 100    1 次 ( 1%)      ← 扇出最大的种子，规则把它顶到上限
种子 150：hop-1 有 100 个对端 → 下一跳的读带了 100 个 id（唯一的 100）
种子 135：76  → 50
种子 1001：37 → 7（只有 7 个要读）
```

显式 batch/barrier 扫描（20 个 id，每个 2 轮预热）：

```
auto          3.91 ms/query   3.85 reads/query
barrier(1)   11.08 ms/query  70.15 reads/query
batch(1)     10.74 ms/query  70.50 reads/query
batch(10)     4.73 ms/query   9.15 reads/query
batch(25)     3.82 ms/query   5.20 reads/query
batch(50)     4.21 ms/query   3.85 reads/query
batch(100)    3.99 ms/query   3.00 reads/query
batch(200)    4.13 ms/query   3.00 reads/query
```

结论：**自适应落在拐点上，但几乎没动**。

* 默认 50 已经在拐点：25→50 把往返从 5.2 降到 3.85，50→100 再降到 3.0 但延迟没改善（这批数据太小，
  往返已经不是瓶颈，延迟由记录解码与结果构造决定）；batch(1) 往返 70 次、延迟 2.8 倍，说明**批读/并行是最大的杠杆**；
* 上限 100 合理（`batch(200)` 不再省往返，只是把 100 的两次并成一次）；
* **"barrier" 就是 batch 的别名**（`barrier(1)` ≈ `batch(1)`、`barrier(50)` ≈ auto），这点在 §57 已经说过，
  这里再次实测确认；
* 自适应用的是**上一跳的 traverser 数**（平行边算多条），所以种子的"对端数"可能小于它——这正是 150 能把批顶到
  100 而 135（76 个对端）停在 50 的原因。要更激进的话调 `maxBatchSize`，而不是改规则。

### 59.5 问题 3：无关属性有没有被加载？点和边各读了什么属性？

**没有**——而且比"只读三个属性"更省：中间一跳的点**一个属性都不读**，边**一个属性都不读**。

```
被测查询 values('accountUsage','firstName','accountStatus')（种子 135，Account 声明 13 个属性）
  readNeighboursBatch  ids 1  -> 76     none (identity only)       ← hop 1：只读邻居引用
  readNeighboursBatch  ids 50 -> 424    none (identity only)       ← hop 2：同上
  readVertices         ids 1  -> 1      accountUsage, firstName, accountStatus
  readVertices         ids 50 -> 50     accountUsage, firstName, accountStatus
  共读 153 个属性值，返回 2961 个值（39.5 KB）

同一查询去掉 values()（答案是整顶点）
  ... 同上两行邻居读 ...
  readVertices         ids 1  -> 1      all declared properties
  readVertices         ids 50 -> 50     all declared properties
  共读 663 个属性值，返回 987 个顶点（359.0 KB）
```

* **顶点属性投影下推**：最后一跳只读 3 个属性（13 个里），中间一跳因为只用来继续跳，连顶点读都省了
  （`readProperties() == null` → 跳过整读，只记 id/label）；
* **边属性零加载**：`readNeighboursBatch` 只回邻居引用，`edge.decoded = 0`；
  `Payment [status, type, amount, currency]` 四个属性**一次都没被点名**（`edge.read` 只是被计数成"边记录命中"，
  与解码无关）。这解释了 §57 里"按需属性让 payload 423→251→64 KB"的那个结论；
* **省的形态**：往返数不变（4 次），省的是**属性值与响应体积**——属性值 153 vs 663（4.3×），响应 39.5 KB vs
  359.0 KB（9.1×）。所以"属性没被加载"这个检查应该看**属性值数/字节**，而不是 kv 调用次数（§53 的老结论再次成立）；
* 图里声明但本轮**从没被读过**的属性在报告里逐条列出（`Account` 的另 10 个、`FuzzyLink` 的 3 个、
  6 个边类各自的属性），方便对照。

### 59.6 复现

```bash
JAVA_HOME="/c/Program Files/Java/jdk-26.0.2.1" ./mvnw -o -pl graph-integration-test test \
  -Dtest=AerospikeHundredSeedsValuesBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false
# 输出在 target/surefire-reports 与控制台；本次运行的完整输出留在 .logs/bench-final2.log
```

参数：`-Dpayment.vertexSet=`（默认 `http_demo_v3`）、`AEROSPIKE_HOST/PORT/NAMESPACE`。用例里**没有时间断言**
（只有"缓存不改变答案""两次执行读一样多""顶点读只点三个属性"这类不变量），所以在负载下也不会假失败。

### 59.7 三个坑的**根修**（不是绕过）

§22.2 记的三个坑都是"用值/用名字猜语义"引起的。修法是**从声明类型推**，并各加一个守卫用例，
让它们不能悄悄回来（`graph-integration-test/.../AerospikeHundredSeedsValuesBenchmarkTest`）：

| 坑 | 错在哪 | 现在的做法 | 守卫 |
|---|---|---|---|
| ① 无从值上区分 id 与属性名（账户 id 也是字符串） | 手写的"方法名 → 参数下标"表**写错了一格**（`readEdgesBatchWithin` 的 ids 在第 4 位而不是第 3 位，被时间窗参数挤走），错了也没人发现 | 从**泛型参数类型**读：`List<Object>` 是 ids、`List<String>` 是属性名、`String[]` 是 varargs 属性名；手写表删掉 | `everyStoreReadIsClassifiedFromItsDeclaredTypes`：遍历 `GraphQuery` 的每个非静态方法，断言同一种元素类型的 List 参数**至多一个**（再多就无法区分），并钉住 `readVertices` / `readEdgesBatchWithin` 的下标与 `List<List<…>>` 的嵌套形状 |
| ② 用名字前缀判断"这条读是读点的" | `"readVertices".startsWith("readVertex")` **是 false**（第 9 个字母 `i` vs `e`），判定恒假、断言在空集上"通过" | 从**泛型返回类型**的元素判：`VertexQueryResult` → 点、`EdgeQueryResult`/`NeighbourRef`/`ScannedEdge` → 边、`Long` → 计数；"是否算流量"也从返回类型判（布尔 = 能力探测，不记） | 同一个守卫：断言 `readVertices` 是 VERTEX、`readNeighboursBatch` 是 EDGE、`readEdgesBatchWithin` 是 nested |
| ③ 批读的外层 list 是"每个 id 一个列表" | `list.size()` 是**请求数**不是命中数，于是"hop-1 扇出"全是 1 | 按声明形状算：nested → 内层**非 null**元素求和；平坦 → 外层非 null 计数 | `theRecordedHitsAgreeWithTheStorageCounters`：同一查询用两个独立口径量同一件事——记录器数到的顶点记录数必须等于 `storage.vertex.read.items` 的增量（投影读时前者 ≤ 后者，因为"请求的 bin 一个都不在"的记录会被算成 miss）。实测 51 = 51 |

另外把 id 集**排序**（`TreeSet`）后才取 100 个：扫描返回顺序由服务端决定，不排序的话两次运行的 workload 不是同一个，
报告之间没法比。修完后同一环境连跑两次，流量数字**逐字节相同**（`kv.read 4792 / items 69584 / vertex 59216 / edge 10368`）。

### 59.8 顺手硬化：`AerospikeOperations` 的"这是边读还是点读"

同一个坑在**产品代码**里也有实例（指标分类）：

```java
// 原：名字说了算，而且要求"所有 bin 都是边 bin"
boolean edges = set.startsWith("edge") || set.endsWith("_edgeheads") || set.contains("_edge_")
        || (bins.length > 0 && allEdgeBins(bins));
```

两个隐患：**顶点集名字以 `edge` 开头**（如 `edge_store_v1`）时，整条读会被记成边读；而**边 bin + `vt` 元 bin 一起读**时
`allEdgeBins` 为假，会退化成按集合名判，在 `http_demo_v3` 这种顶点集上被记成点读。

现在抽成 `AerospikeReadKind`（包内可见、有 javadoc、有 7 个用例）：**bin 说了算**——读到任何 `vp_*`/`vpt_*` 就是点读，
只有 `ve_*` 就是边读；**集合名只在整条记录读（没有 bin）时兜底**（`edges` / `edge_*` / `<set>_edge*` / `<set>_edgeheads`），
并且明说"名字单独判不了"（顶点集可能就叫 `edge_...`），这一点在用例里写清楚了。

对现有数据的**行为等价**：所有边读都只传一个 `ve_*` bin（`AerospikeEdgeStore` 的 `db.get(..., edgeBin(...))`），
所有点读传 `vp_*`/`vpt_*`(+`vt`) 或不传 bin，两种规则结论一致；集成侧实测流量数字未变（唯一变化来自上面"排序"带来的固定 workload）。

---

## 60. 第十四批：结果面板的单元素、编译缓存、以及在 RocksDB-Edge 上真正跑起来的向量索引

### 60.1 面板：一个元素也要报数量和 distinct

用户报 `g.V(1003,'Account').nearest(20).both('Payment')` **没有显示结果数量和 distinct 数量，显示的是 account id 150**。两层原因：

1. **面板只对"多于一个"报数量**（`summarizeValues`）：`labels.length === 1` 时直接把那个值放大显示 —— 对 `count()` 这种标量是对的，对**一个顶点/边**就成了"只显示一个 id"。现在按"这个值是不是图元素"分流：

| 答案 | 修前 | 修后 |
|---|---|---|
| 1 个顶点/边 | `150`（就一个 id） | `1 项 · 1 个不同值` |
| 1 个标量（`count()`） | `76` | `76`（不变） |
| 2 个元素 | `2 项 · 2 个不同值` | 不变 |
| 同一元素两次 | `2 项 · 1 个不同值` | 不变 |

用**服务上真正下发的那份 app.js**验证（抽出 `isElementValue`/`valueLabel`/`summarizeValues` 在 node 里跑，8 个用例，见 `.logs/panel-fn-check.js`）——不是看源码，是看浏览器会执行到的那份。

2. **它当时只返回 1 个元素，是因为向量索引是坏的**（见 §60.2）。所以这条报障同时暴露了一个"静默给错答案"的问题。

### 60.2 索引坏了却照样回答：截断图以后 `nearest(k)` 只回一个点

复现：`g.V('1003','Account').nearest(20)` 返回 **1** 个元素（2041/150 之类），`nearest(5)` 也是 1 个；`both('Payment')` 正常。

根因链条（实测）：

* 演示播种器（`QueryApiIntegrationTest#demoSharedGraphViaApi`）先 `truncateSet(...)` **清空整个顶点集** —— HNSW 的层边就住在顶点记录的 `ve_*` bin 里，所以层数据一起没了；
* 而**索引元数据**（entry points / size / topLevel）在另一个 set（`hnsw_meta`）里，**活了下来**，仍然声称 `size=152, topLevel=8`；
* 搜索于是从"只剩元数据"的入口点出发：走不到任何邻居，答案就是入口点自己 —— 看起来像"结果很少"，实际是**错的**。

取证：`g.V('1001','Account').bothE('accountVector_0').count()` = **0**、入口点的 `accountVector_3` 也是 0，而 `GET /api/v1/vector-index` 仍显示 `size=152`。重建索引后同一查询立刻恢复（`nearest(5)` → 5 个）。

**修法（三层）**：

1. **拒绝回答**（`HnswIndex.verifyLayers`，读路径每次搜索前一次读）：每个建好的索引里每个顶点在 layer 0 上至少有一条边（入口点也一样），所以"元数据说有 N 个向量、入口点在 layer 0 却没有邻居"就判定为坏索引，抛
   `... has no layer data, although its metadata says it holds 152 vectors (its entry point has no neighbour on layer 0): the index has to be rebuilt`。
   **故意不缓存**这次校验：刚被重载作废的索引不该因为"记得自己是好的"继续回答。新增 2 个用例（`aSearchRefusesAnIndexWhoseLayersAreGone`、`aSearchOfAnIndexThatWasNeverBuiltAnswersNothing`）。
2. **播种器一致**：`truncateSet(client, ns, set, schema)` 现在连该图向量属性的**元数据记录一起删**（截断就是"没数据、没索引"），演示工作流不再留下坏索引。
3. **顺手修的同类 bug**：`HnswIndex.searchById` 用**对象相等**排除自身，而调用方给的是 `'1001'`（字符串）、RocksDB 数据集里存的是 `1001`（long）→ 自身会出现在"最相似的 k 个"里且距离为 0。改成按**文本**比较（与引擎其它地方解析 id 的方式一致），RocksDB 集成用例立刻抓到过这个（`[Long:1001, Long:1004, ...]`）。

### 60.3 编译缓存：同一语句只编译一次，问题是它没有上限

**没有 Groovy**：整个引擎里没有 `GroovyShell`/`GroovyClassLoader`，所谓 Groovy 语法是项目自己的解析器（`GremlinQuery.parse` + `GremlinExpression.parse`）。

* `GremlinGraphQuery.execute(String)` 用 `compiledCache.computeIfAbsent(text, this::compile)` 按**整条语句文本**缓存：解析出的步骤、**属性投影计划**、以及**闭包表达式**（`filter { }` / `map { }` 在建步骤时就解析，绝不按元素解析）全在里面；
* 引擎实例**按图缓存**（`QueryService.engineFor` 的 `engines.computeIfAbsent`），所以一条语句在一个进程里只编译一次；
* 实测（`GremlinCompiledQueryCacheTest`，4 个用例 + 打印数字）：**解析+编译 6.7 µs**，命中缓存的执行 89.6 µs，每次重新编译 165.5 µs（1.85×）——对内存假存储是 40%，对真实存储（毫秒级）是千分之几；
* **发现的问题**：缓存**无上限**，而 key 是整条语句文本 —— 一个把 id 写进语句的客户端（仪表盘就是这么干的）会一直加条目。现在 `MAX_COMPILED_QUERIES = 512`，满了整体清空（重编译一次远比记住它便宜），外加用例证明清空后答案不变、条目数有界；
* 基准里 100 个不同 id 的语句 = **100 次编译**（不是 800 次）✓ 同一 id 的 8 轮复用同一份编译结果。

### 60.4 RocksDB-Edge 上真正可用 的 HNSW（§58.7 的未做项）

原先只支持 Aerospike，理由是"索引住在 Aerospike 的边 bin 里"。**索引其实不需要特定存储**：层就是普通边类、搜索走通用 `GraphQuery`。这次把它接上：

**新增（`graph-storage-rocksdb-edge`）**

| 类 | 作用 |
|---|---|
| `RocksDbHnswStore implements HnswStore` | 写侧：层边按 `accountVector_<l>` 读写；一批 link 按层分组写（`upsertEdges` 要求同批同类）；prune 时先读回该行的 creationTime 再删（RocksDB 行键含创建时间） |
| `RocksDbHnswMetadataStore implements HnswMetadataStore` | 元数据一行 KV 放在**图自己的目录**里（`hnsw:meta` 列族，按需创建），格式是可读的 `key=value` 文本 |
| `RocksDbHnswIndexFactory` | `open`（搜索侧：`GraphQueryHnswStore` + `GraphQueryVectorSource`，只读）、`builder`（构建侧：`RocksDbHnswStore`）、`declareLayers`、config/provisioner |
| `RocksDbVectorIndexBuilder.rebuild` | 一次**扫描**顶点类就同时拿到 ids 和向量（RocksDB-Edge 支持 `scanVertices`，不必像 Aerospike 那样从 layer 0 走图）、清掉旧层、重新插入并 `flush` |

**改动**：`VectorIndexManager` 去掉 Aerospike-only 门槛，读路径对 RocksDB 图用新工厂、写路径走新 builder（`RocksDbGraphStores.edgeStore(name)` 暴露存储）；`RocksDbGraphStores.open` 打开目录前先 `declareLayers`。

**顺带修的真 bug（RocksDB 特有）**：`RocksDbHandle.open` 要求打开目录里**每一个**列族，而层类是按需创建的 ⇒ 索引建过一次以后，服务重启时按 JSON 加载的 schema 不认识那些列族，**目录直接打不开**。现在 `RocksDbHandle.open` 会先问目录它有哪些列族（`RocksDB.listColumnFamilies`）并一起打开 —— 这是"可重开"的关键，不是便利。

**实测（`RocksDbVectorIndexIntegrationTest`，6 个用例；在演示目录的**副本**上跑，不打扰运行中的服务）**：

```
built Account.accountVector: 51 vertices, 7 layers, 753 links, 274 pruned, 0.1 s
nearest(1001): [1004, 1007, 1010, 1013, 1016] at [0.0000, 0.0000, 0.0001, 0.0001, 0.0002]
vector + graph      : nearest(5).outE('Payment') -> 15 条边 / 子图 23 点 36 边
geo + vector + graph: 5km 内 41 个账户 -> 它们的相似账户 -> 78 条出款 / 子图 47 点 100 边
```

服务上（HTTP，`POST /api/v1/vector-index/rebuild {"graph":"payment-graph-rocksdb-edge"}`）同样工作：
`vertices:51, layers:7, links:753, seconds:0.221`，随后 `nearest(5)`、`nearest(5).outE('Payment')`、
`circle(5km).limit(10).nearest(3).outE('Payment')`（**78 项 / 子图 47 点 100 边**）、
`circle(5km).limit(5).nearest(2).out('Payment').dedup().limit(20)` 全部返回数据 ✓。

**注意**：这个数据集（`PaymentGraphRocksDbLoader`）的账户 id 是**数字**（`g.V(1001,'Account')`），而 Aerospike 那套
（`http_demo_v3`）是**字符串**（`g.V('1001','Account')`）。UI 示例里两套图各自用对的写法，README/HANDOVER 也注明。

### 60.5 复现与验证

```bash
# 单元：编译缓存 / 面板的纯函数 / 索引一致性
./mvnw -o -pl graph-query-gremlin test -Dtest=GremlinCompiledQueryCacheTest     # 4 用例 + 打印 µs
./mvnw -o -pl graph-hnsw test -Dtest=HnswIndexTest                             # 15 用例（含 2 个新）
node .logs/panel-fn-check.js                                                   # 服务上那份 app.js 的 8 个用例
# 集成：RocksDB 上的向量索引 + geo + 图
./mvnw -o -pl graph-integration-test test -Dtest=RocksDbVectorIndexIntegrationTest
# 服务：重建两张图的索引，然后跑 .logs/rocksdb-live.js 里的 8 条查询
```

全量 `./mvnw test`：**BUILD SUCCESS，0 失败 / 0 错误**（含新用例）。

---

## 61. 同一个两跳投影 benchmark 跑在 RocksDB-Edge 上（100→51 个 id，816 次执行）

用户要求把 §59 的查询在原 RocksDB-Edge 实例上再做一遍。两条语句/三个检查都一样，所以先把**测量与报告**
抽成一个共享 harness（`TwoHopValuesBenchmark`），存储只提供三样东西：`openStore`（并挂 metrics）、
`candidateIds`、`label`——实测报告因此可以逐行对比。

* `AerospikeHundredSeedsValuesBenchmarkTest`：100 个 id × 8 轮 = 800 次；
* `RocksDbEdgeSeededValuesBenchmarkTest`：**51 个 id × 16 轮 = 816 次**。

**这个实例只有 51 个账户**（`PaymentGraphRocksDbLoader`：hub 135 + 1000..1049），所以"100 个有效 id"在这张图上
不存在：harness 会把候选里"查询答不出东西"的丢掉，然后**用尽所有有效 id 并提高轮数**凑足 500–1000。另外它的
账户 id 是**数字**（`g.V(1001,...)`），而语句里写的是文本 id —— 存储两种形式都能解析到同一条记录，这本身也顺带
验证了 id 形式的容忍度。（服务占着演示目录，所以 benchmark 在**副本**上跑。）

### 61.1 总览（51 id × 16 轮 = 816 次，RocksDB-Edge）

```
延迟     mean 0.51 ms  min 0.18  p50 0.42  p90 0.80  p95 1.01  p99 1.88  max 5.59
吞吐     1958 查询/秒       墙钟 0.42 秒
结果     291 项/查询        响应 3.9 KB/查询
流量     kv.read.call 2.00/查询(max 2)   记录 51   顶点读 2 次调用/51 条   边读 0   解码边 0
```

比 Aerospike 那套（mean 4.96 ms、5.90 次往返）**快约 10 倍、往返少 2/3** —— 本地列族、没有网络。
注意两套的 `vertex.read` 语义不同：Aerospike 的 `readBatch` 每个 key 记一次（所以它 = 记录数），
RocksDB 的 handle 一次 `get`/`multiGet` 记一次调用（所以 2 次调用 / 51 条记录），报告里两个数都印。

### 61.2 问题 1：缓存有效吗？

**引擎的每查询缓存：有效但收益很小**——102 vs 107 次往返（省 4.7% = 0.10 次/查询），0.36 vs 0.44 ms/id。
原因见下面那条：**存储自己还有一层缓存**，引擎缓存能省的东西已经被它省掉了。

**(4) 存储侧（RocksDB 专属测量，本次新加)**：`RocksDbEdgeStore` 会把解码后的边列表缓存在 RocksDB 前面
（`cacheEdgeLists`），于是预热之后**整条遍历的边一次读都不发生**（`edge.read = 0`）——只看计数器会误以为是引擎
缓存的功劳。用同一份数据的第二个副本、把该缓存关掉再跑同样的 workload：

```
store edge cache off: 1.11 ms/query, 19.7 kv reads/query (5401 条边记录), 67.7 记录/query
store edge cache on : 0.75 ms/query（种子 1000）, 2.0 kv reads/query（边全在内存里）
```

即：**存储的边缓存把每次查询的往返从 ~20 降到 2、延迟降约 1/3**。这条对"缓存是否有效"的回答很关键——
在 RocksDB 上，两级缓存（引擎一层、存储一层）叠加，引擎那层的边际贡献本来就有限；这也是为什么本图上的
`edge.decoded = 0`。

### 61.3 问题 2：batch / barrier 自适应到合适值了吗？

```
51 个种子：102 次读带 2..50 个 id，102 次读只带 1 个 id
  ids <= 10    50 次 (49%)      ← 小扇出（本图账户扇出 7 或 50）
  ids <= 50    52 次 (51%)      ← 默认 50 是上限，规则没有把它顶到 100
种子 135：hop-1 有 50 个对端 -> 下一跳读带 50 个 id
种子 1000/1001/1002/1003：hop-1 7 个对端 -> 下一跳读带 7 个 id
```

显式 sweep（20 个 id）：

```
auto        4.00 reads/query  0.25 ms      batch(50)   4.00 reads  0.42 ms
batch(1)   57.40 reads        0.57 ms      batch(100)  3.00 reads  0.23 ms
batch(10)   8.00 reads        0.32 ms      batch(200)  3.00 reads  0.19 ms
batch(25)   5.00 reads        0.49 ms      barrier(1) 57.10 reads  0.64 ms
```

结论与 Aerospike 版一致、但**本图的自适应更"保守"**：扇出只有 7 和 50 两种，规则
`clamp(扇出/2, 当前 50, 100)` 的下限 50 让它**始终停在 50**（从未用到 100）；默认值本身就在拐点
（25→50 把往返从 5 降到 4，50→100 再降到 3，延迟基本不变）；`batch(1)` 57 次往返、延迟 2.3 倍，
说明批读仍是最大杠杆；`barrier(n)` 再次实测就是 `batch(n)` 的别名。

### 61.4 问题 3：无关属性有没有被加载？

**没有**——和 Aerospike 版同样的形态（Account 声明 **9** 个属性，查询只要 3 个）：

```
被测查询 values('accountUsage','firstName','accountStatus')（种子 135）
  readEdges       ids 1  -> 50    none (identity only)   ← hop 1 只读邻居引用
  readEdgesBatch  ids 50 -> 350   none (identity only)   ← hop 2 同上
  readVertices    ids 1  -> 1     accountUsage, firstName, accountStatus
  readVertices    ids 50 -> 50    accountUsage, firstName, accountStatus
  读 153 个属性值，返回 1050 个值（13.9 KB）

去掉 values()（答案是整顶点）
  ... 同上两行边读 ...
  readVertices    ids 1/50 ->  all declared properties
  读 459 个属性值，返回 350 个顶点（141.0 KB）
```

* **边属性零加载**：两跳都是顶点跳，走的都是"邻居引用"读（`readEdges*` 的 attributes 为空、`edge.decoded = 0`），
  `Payment [status, type, amount, currency]` 一次都没被点名；
* **中间一跳的顶点读也省了**：只有最后一跳读顶点，且只读 3 个属性；
* **省的形态**：属性值 153 vs 459（3×），响应 13.9 KB vs 141.0 KB（10×）——往返数不变，省的是体积/解码
  （与 §59.5、§53 的结论一致）。

### 61.5 复现

```bash
./mvnw -o -pl graph-integration-test test -Dtest=RocksDbEdgeSeededValuesBenchmarkTest
./mvnw -o -pl graph-integration-test test -Dtest=AerospikeHundredSeedsValuesBenchmarkTest   # 同一 harness
```
两边各 9 个用例（含 2 个守卫：方法形状分类、命中数与存储计数器一致），**无时间断言**。
本次输出：`.logs/bench-rocksdb2.log`（RocksDB）、`.logs/bench-asp-after.log`（Aerospike，回归对比）。

---

## 62. 组合查询（geo + 向量 + 边）100 次运行的性能测试

被测语句（用户在 RocksDB-Edge 实例上给的形状）：

```groovy
g.V(1001,'Account').circle('{"lat":31.2304,"lon":121.4737,"radius":5000}').limit(10).nearest(10).outE('Payment')
```

这是演示数据里唯一一条把三条访问路径串起来的语句：**geo 索引（S2）**定圆 → **向量索引（HNSW）**找相似 →
**Payment 边表**收出款。测试 `RocksDbGeoVectorPaymentBenchmarkTest`（4 个用例，服务占着目录所以在副本上跑；
索引在副本里重建，0.18 s / 51 点 / 7 层 / 753 链接，免得结果依赖服务那份是否建过索引）。

### 62.1 100 次运行（实测）

```
延迟     mean 5.37 ms  min 2.76  p50 4.64  p90 7.89  p95 8.72  p99 13.42  max 15.38
吞吐     186 查询/秒      墙钟 0.54 秒      结果 203 项      子图 51 点 / 241 边

每 20 次一块（前几次不是稳态，JIT 与缓存都在暖机）
  runs   1- 20    7.43 ms      runs  41- 60    4.81 ms      runs  81-100    4.09 ms
  runs  21- 40    5.11 ms      runs  61- 80    5.41 ms

每次运行的存储流量（均值，max 与均值相同 = 完全确定）
  kv.read.call   127.00    记录 595     顶点读 111 次调用/582 条    边读 0     geo.read 54
  edge.decoded     0.00    （这个计数器由 Aerospike 侧维护，RocksDB 侧不计数，不能读成"没解码"）
```

**稳态约 4.1 ms**（最后 20 次），100 次的均值 5.37 ms 里含 JIT/暖机；流量**每次都一样 127**（存储缓存预热后
确定）——所以看趋势要看延迟，看流量不用重复取平均。

### 62.2 时间花在哪一步（各 50 次，已预热）

```
  1. 只有种子                              0.058 ms   →  1 项
  2. + circle(5km).limit(10)               0.303 ms   → 10 项     （geo 步 +0.245）
  3. + nearest(10)                         2.863 ms   → 52 项     （向量步 +2.56，占 78%）
  4. + outE('Payment')（就是被测语句）      3.282 ms   → 203 项    （边步 +0.42）
```

**向量步是绝对瓶颈**：`nearest(10)` 作用在 10 个 geo 命中上 = **10 次独立的 HNSW 搜索**，每次都要沿图层走
（每跳一次读 + 邻居向量一次读，见 §62.3），所以代价随 traverser 数线性增长。geo 步便宜（0.25 ms，一次 S2
读回 10 个点），边步也便宜（0.42 ms，一次批量边读 + 一次批量点读）。

### 62.3 一次运行读了什么（实测 trace）

```
geoNear            GEO                     ids 1  -> 10     （S2 圆，1 次读回 10 个点）
readVertices       VERTEX                  ids 1  -> 1      × 10   ← 每个 geo 命中单独读回（见下）
readEdges          EDGE  accountVector_0  ids 1  -> 15/16  × 10   ← 每个 traverser 的 HNSW 图层走查
readVertices       VERTEX                  ids 10/7/6 -> 同   × 10   ← 邻居向量，批量
readEdgesBatch     EDGE  Payment          ids 49 -> 194          ← 最后一跳：一次批量边读
readVertices       VERTEX                  ids 51 -> 51           ← 边的对端，一次批量点读
```

三点结论：

1. **`nearest` 的代价在"每个 traverser 一次图走"**：per-traverser 的 `readEdges(accountVector_0)`（邻居引用）
   + 批量 `readVertices`（邻居向量）——这就是 §62.2 里 78% 的来源。想再快只能减少 traverser 数（把
   `limit(10)` 放到 `nearest` 之后再 dedup，或者对 10 个种子做一次合并搜索），不是调 batch/ef。
2. **geo 命中是"一个一个读回来"的**（10 次 `readVertices(ids 1)` 本可合成 1 次 `ids 10`）：`circle` 步为了给每个
   命中挂上距离，对每个 hit 单独读一次。对 10 个命中就是 10 次往返（占 127 次里的 ~8%），是**明确的优化点**
   （改成先收齐 hits 再批量读、然后附着距离）。
3. **收尾是批量且干净的**：最后一跳 `Payment` 是 1 次批量边读（49 个 id，在默认批 50 以内）→ 194 条边，
   再接 1 次批量点读拿对端（51 个 id）。**边属性也没有被点名**（`readEdgesBatch` 的属性列表为空 = 读该类声明的
   全部属性，因为答案是边本身）；HNSW 图层与邻居读天然没有属性。

### 62.4 缓存

```
第一次运行（存储目录刚打开）： 3.45 ms, 127 次读
其后二十次：                   3.00 ms, 127.0 次读   ← 存储把重复读走内存
同一语句两次：                 127 = 127             ← 引擎的每查询缓存作用域是单次执行
```

与前几轮一致：**引擎缓存不跨执行**（写后读永远不回旧值），**存储自己的边列表缓存**把重复读吃掉——
`edge.read` 因此在稳态恒为 0（边都来自内存），`kv.read.call` 的 127 次几乎全是顶点读与 geo 读。
注意 `edge.decoded`/`edge.filtered` 这两个计数器只在 Aerospike 侧维护，RocksDB 侧恒为 0，不能当成"没有解码"。

### 62.5 复现

```bash
./mvnw -o -pl graph-integration-test test -Dtest=RocksDbGeoVectorPaymentBenchmarkTest
```
4 个用例：100 次运行、4 段 step 归因、1 次运行读轨迹、缓存冷热对比（**无时间断言**，只有"每次都有结果""读到了东西"
这类不变量）。本次输出 `.logs/bench-geo-vector6.log`。

---

## 63. 对比：`outE('Payment')` vs `out('Payment')`（各 100 次，交错测量）

只差最后一步：A 的答案是**边**，B 的答案是边到达的**点**。两条语句共享前面所有步骤（种子 → geo 圆 → 向量相似），
所以差异就是最后一步的代价。

### 63.1 先说方法：为什么必须**交错**测

第一版是"先跑 A 100 次、再跑 B 100 次"，结果 B 比 A **快 1.47 ms**（p95 快 2.81 ms）——那是**测量顺序**的假象：
B 跑在后面，JIT 更热。改成 **A、B、A、B… 交错**（各自 100 次，共用同一份 JIT 与缓存状态）后，结论反过来：

```
                             A  outE (edges)   B  out (vertices)      B - A
  mean ms                              5.31                5.59       +0.27
  min / p50 / p90 / p95 / p99     2.71 / 5.16 / 7.28 / 8.09 / 9.02   2.86 / 5.48 / 7.72 / 9.15 / 9.75
  throughput (查询/秒)                  188                 179
  每 20 次一块（稳态）   6.60 → 4.23 ms            6.88 → 4.24 ms      ← 稳态几乎一样
  结果项数                             203                 203        （同一个子图 51 点 / 241 边）
  kv.read.call                         127                 128        +1
  kv.read.items                        595                 645        +50
  vertex.read（调用 / 记录）      111 / 582          112 / 632        +1 / +50
  geo.read                              54                  54        0
```

### 63.2 结论

* **两条语句在这个数据上几乎等价**：稳态（最后 20 次）4.23 vs 4.24 ms；均值差 +0.27 ms（约 5%），
  p95 差 +1.06 ms。**没有"用 `out()` 会快很多"这回事**。
* **B 多做的工作**：多 1 次往返、多读 **50 条顶点记录**（它要把到达的点物化成答案；A 的答案是边，只需要
  边的对端 51 条）。这 50 条记录就是 +0.27 ms 的来源。
* **两者共同的瓶颈**：向量步。A 的分步归因 seed 0.05 → +circle 0.23 → **+nearest(10) 3.91** → +最后一步；
  B 是 0.02 → 0.15 → **+nearest(10) 2.74** → +1.07。**最后一步只占 0~1 ms，前面最多 4 ms。**
  （单条语句的分步归因在同一次会话内可比；A 表里最后一步出现负值就是亚毫秒级噪声，不要当结论——
  权威的 A/B 差异是上面那张交错表。）

### 63.3 顺手发现：RocksDB-Edge 的**顶点跳**没有"邻居读"

两条语句最后一步的读轨迹都是 `readEdgesBatch(Payment, 49 → 194)`，属性列表为空——按接口约定
"空 = 读该类声明的全部属性"。也就是说 **B（只回答点、根本不需要边的属性）也把每条 Payment 的
`status/type/amount/currency` 全解码了**。

原因：Aerospike 侧有 `readNeighboursBatch`（只回 edgeId + 对端 id，不解码属性，§59/§60 的两跳 benchmark
里能看到 `readNeighboursBatch` 且 `edge.decoded = 0`），而 **RocksDB-Edge 没有实现邻居读**
（`supportsNeighbourRead()` 为 false）→ 顶点跳退化成完整边读。接口里也表达不了"要这一行但不要任何属性"
（空 = 全部），所以这是**下一步该给 RocksDB-Edge 补的能力**：一个小接口 + 一个只回端点的批量读。

B 的最后一步比 A 多一次顶点读（`readVertices(50)` 物化答案 + `readVertices(51)` 子图对端），A 只有后者。

---

## 64. 优化：把中间步骤里没人要的点/边属性读掉

§62/§63 的读轨迹指出两处"读了没人要的属性"：

1. **geo 步**：`circle(...)` 对**每个命中单独读一次顶点**（10 个命中 = 10 次往返），而且投影计划是 `null`（后面没有任何步骤要这些点的属性）时，旧代码把 `null` 当成"全部属性"读回来；
2. **RocksDB-Edge 的顶点跳没有"邻居读"**：`out()`/`both()` 这类顶点跳只需要"边通向哪里"，但存储层只能按 A 的口径返回整条边（空属性列表 = 读该边类声明的**全部**属性），于是中间被丢掉的那些 `Payment.status/type/amount/currency` 每次都被解码。

### 64.1 geo 步：一次批量读，不需要就不读

`GremlinSteps.readHits(ctx, vertexName, hits)`（替代原来的 `readVertex(ctx, hit)` 逐个读）：

* 先按 `search.maxResults()` 收齐命中（去重、保序，语义与原来一致）；
* **`readProperties()` 为 `null` 时一次记录都不读**：id + label + 距离就是下一步要的全部（距离来自索引，不从记录来）——这和 hop 对"到达的点"的规则一致；
* 否则**一次 `readVertices(name, ids, plan)` 读完所有命中**，再按命中顺序把距离附上。

日志与用例（`GremlinGraphQueryTest`，85 个用例全绿，其中 2 个更新/新增）：

```
修前：verify(readVertices).times(3)   // 三个命中三次读
修后：verify(readVertices).times(1)   // 一次读三个 id
新增：circleDoesNotReadTheHitsWhenNothingNeedsTheirProperties
      g.V('v1','person').circle(...).count()        -> 0 次顶点读
      g.V('v1','person').circle(...).values('distanceMeters') -> 1 次批量读，距离仍是 100/200
```

### 64.2 RocksDB-Edge：邻居读（只读行的头部）

新增三处，仿 Aerospike 的 `readNeighbours(Batch)`：

| 位置 | 内容 |
|---|---|
| `RocksDbEdgeRowCodec.decodeHeader` | 只读行的前三个字段（对端 id + 两个时间），**跳过属性区**，返回 `NeighbourHeader` |
| `RocksDbEdgeStore.readNeighbours(edgeName, vertexId, direction, side)` | 一次范围扫描 → `List<NeighbourRef>`；**与边列表用同一套 LRU 缓存**（缓存键用哨兵属性集 `{-1}`，避免与"整条边"的缓存串味） |
| `RocksDbEdgeGraphQuery.readNeighbours/readNeighboursBatch/supportsNeighbourRead` | 按 `sides(...)` 展开方向、排序（与边读同样的 lastUpdatedTime 倒序） |

引擎侧本来就有这条路（`hopVertices`：`!ctx.collectsSubgraph() && graphQuery.supportsNeighbourRead()` 时走邻居读），所以只需存储层支持。

**一个踩到的坑**：第一版邻居读**绕过了存储的边缓存** → 两跳 benchmark 的 `kv.read.call` 从 2.00 涨到 **19.69**（每次扫描都真读盘）。把邻居列表也缓存进同一个 LRU（独立缓存键）后回到 **2.00**，而属性解码仍然省掉了。

### 64.3 实测效果

**geo + 向量 + 边（100 次，§62 的语句）**：

```
                kv 读/次   记录/次   顶点读/次   结果      子图          geo 步
修前               127       595        111      203 项   51 点/241 边    0.303 ms
修后               117       585        101      203 项   51 点/241 边    0.184 ms
```

省掉的就是那 10 次逐点读（-10 次往返、-10 条记录、-10 次顶点读调用），**答案一个字节没变**（203 项 / 51 点 / 241 边 ✓）。

**两跳投影（816 次，§61 的语句）**：流量与修前**逐项一致**（2.00 次往返 / 51 条记录 / `edge.read = 0`，因为存储缓存照旧命中），
但两次顶点跳现在读的是**行的头部**：`Payment` 的 `status/type/amount/currency` 一次都不再解码。p50 0.54 ms（修前 0.42）
在这个量级上属于抖动（`max` 17 ms 的离群值把均值抬到 0.70）——**结构性收益是"少了属性解码"，不是往返数**。

### 64.4 复现

```bash
./mvnw -o -pl graph-query-gremlin test          # 85 用例（2 个更新/新增）
./mvnw -o -pl graph-storage-rocksdb-edge test   # 21 用例
./mvnw -o -pl graph-integration-test test -Dtest=RocksDbGeoVectorPaymentBenchmarkTest
./mvnw -o -pl graph-integration-test test -Dtest=RocksDbEdgeSeededValuesBenchmarkTest
```
输出：`.logs/bench-after-opt2.log`（geo+向量+边）、`.logs/bench-twohop-after-opt2.log`（两跳）。

---

## 65. 图管理（建模）页的七项改进

页面是 `builder.html` + `js/builder.js`（draft = 后端接受的模型，JSON 预览就是要保存的内容）。后端侧只动了
`SchemaDraft`（草稿 ↔ schema 的转换）与两条路由；其余是页面自己的事。

### 65.1 点/边/属性都能写 description（#1）

`PropertyDraft` 一直有 `description`，`VertexDraft`/`EdgeDraft` 也有，但**页面从没给它们输入框**：

* 属性表新增一列「说明」（`data-field="description"`）；
* 点卡片新增「描述」输入；边卡片同样。

### 65.2 最大出入度：能填，但只对 `-Edge` 存储生效（#2）

schema 里一直有 `maxOutgoingEdges`/`maxIncomingEdges`/`maxDegree`，`SchemaJsonCodec` 也读写它们，但
**`SchemaDraft.EdgeDraft` 没有这三个字段** —— 于是页面上既填不了，打开已有图时这几个值还会被丢掉。

* `EdgeDraft` 增加 `maxOutDegree`/`maxInDegree`/`maxDegree`，`toSchema` 用 fluent setter 写回
  （`maxOutgoingEdges(...)` 等，这样 `maxDegreeSet` 也会置位），`toDraft` 读回来；
* 页面每个边卡片加两个数字输入；**存储不是 `-Edge` 时置灰并说明原因**（内联方案把每个点的边放在一条记录里，
  上限只能拒绝写入，没有意义）。

实测（UI）：Aerospike 下 `disabled: true`，切到 `Aerospike-Edge` 后 `disabled: false`；API 往返
`schema → draft` 保住 `maxOutDegree: 1000` ✓。

### 65.3 id 是引擎的：名字与索引固定，类型可改（#3）

`toSchema` 给每个点建 `VertexPropertySchema(0, "id", ...)`、每条边建 `id`/`source`/`target`（索引 0/1/2），
`createdTime`/`lastUpdatedTime` 是 -2/-1。以前页面允许把属性取名 `id`，保存时才因为重名/冲突报错。

* 属性表**第一行固定是 `id`**：名字禁用、索引固定（显示 🔒）、不能删、不能当向量/地理属性；
* 它的**类型下拉 = 图实例的 id 类型**（string/long），改它等于改整个图（`draft.idType`），所有点同步；
* 后端新增保留名校验：点里不许有 `id`/`createdTime`/`lastUpdatedTime`，边里还不许有 `source`/`target`，
  报错信息直接点名（页面也会把这一行标红并 toast）。

实测：页面把该行标红；保存返回
`Cannot read the schema: 'id' is part of every element of this kind (its index is fixed), so it cannot be declared as a property` ✓；
`SchemaDraftTest` 新增用例覆盖点/边两种保留名（10 个用例全绿）。

### 65.4 写重复 id 先问一次（#4）

写点/写边之前先查一次：`g.V('<id>','<类型>').limit(1)` 有结果就弹确认，把语义说清楚——**不会新建，是按属性
时间戳合并（这次填的值覆盖旧值），没填的属性保持原样**；取消则报告「已取消：id 已存在」。写入后的结果行也会标注
「（已存在的 id，合并写入）」。

实测：第二次写 `v1` 弹出「点 v1 已经存在（Entity）」+3 条说明，确认后返回
`写入点：SUCCESS（已存在的 id，合并写入）` ✓。

### 65.5 保存后「造数据」的属性列立刻更新（#5）

原因：属性表里改名/加属性只调用 `preview()`（只刷新 JSON 预览），而写入表单的属性列是 `render()` 时渲染的；
保存成功后又只 `loadGraphs()`，没有重绘 —— 于是"加了属性、保存加载完，写点的地方看不到新列"。

* 新增 `refreshDataForm()`：属性增删、属性改名、类型改名、保存成功后都调用它重绘写入表单；
* 保存结果里也写明「下面『造数据』的属性列已经跟着新 schema 更新」。

实测：给 `Entity` 加 `tag` 后保存，写入表单的列从 `["name"]` 变成 `["name","tag"]` ✓。

### 65.6 删除都要先讲后果（#6）

* **schema 侧**：删点类型/边类型/属性都弹确认并列出代价——存量数据不会被删除（只是不再被这张 schema 描述）、
  属性的索引会随声明顺序前移、被引用的点类型删掉后保存会因端点校验失败（并列出哪些边引用它）；
* **数据侧**：新增「5c · 删除已写入的数据」：删点会说明**它的边不会被一起删除（对端留下悬空边）**；删边要给出与
  写入时一致的起点/终点，并说明**边存在两处、两处都会被删除**。后端补了页面用的两条代理路由
  （`DELETE /api/graphs/{graph}/vertices/{id}`、`.../edges/{edgeId}?edgeSchemaName&source&destination`）。

实测：删除弹窗文案逐条命中 ✓，确认后 `删除点：SUCCESS` ✓。

### 65.7 从 CSV 导入点/边（#7）

* 页面自己解析 CSV（RFC4180：引号、双引号转义、字段内的逗号与换行），表头即列名：点要 `id` + 属性名，
  边要 `id`/`source`/`destination` + 属性名；
* 导入前一项项确认：文件名、表头、数据行数、**没有 id 会被跳过的行数**、**表头里缺哪些属性列**（缺就拒绝并列出）、
  以及未被识别的列（忽略）；
* 写入按每 200 行一批，走**新的按图批量写**：`POST /api/v1/graphs/{graph}/batch`（原来只有
  `/api/v1/batch`，它固定写到 primary 图 —— 页面刚建的图不在那里，所以整批失败）。批量写仍然是一次存储往返一批，
  并且会顺带维护向量索引；
* 另有「下载模板」按钮，按当前类型生成只有表头的 CSV。

实测：确认弹窗内容正确；后端的按图批量写 `[{"id":"v2","status":"SUCCESS"},{"id":"v3","status":"SUCCESS"}]` ✓，
回查 `g.V("v2","Entity").values("tag","name")` → `["second","tag,with,commas"]`（带逗号的字段原样保留）✓。

### 65.8 复现与验证

```bash
# 后端：草稿往返（含出入度）与保留名
./mvnw -o -pl graph-http test -Dtest=SchemaDraftTest        # 10 用例
# 页面（headless Edge 驱动真实 DOM）：
node .logs/verify-builder2.js    # 出入度置灰/启用、保留名标红+保存被拒、保存后写入列更新、重复 id 确认、删除确认
node .logs/verify-csv2.js        # CSV 表头/行数/跳过/缺列的确认文案
# 按图批量写（CSV 导入用的接口）：
curl -X POST localhost:8081/api/v1/graphs/<graph>/batch -H 'content-type: application/json' -d '{"vertices":[...]}'
```

---

## 66. RocksDB-Edge：整类顺序遍历、按边 id 查找、按属性过滤（用 payment-graph-rocksdb-edge 验证）

用户要的四条语句，实测（`/api/v1/query`，图 `payment-graph-rocksdb-edge`，数据是 51 个账户 / 200 条 Payment）：

| 能力 | 语句 | 结果 |
|---|---|---|
| 整类遍历（顺序） | `g.V('Account')` / `g.E('Payment')` | 51 / 200 ✓（`limit(k)` 会被下推进扫描，只读前 k 条） |
| 按边 id 查找 | `g.E('pay_hub_0','Payment')` | 该边（含属性）✓；`g.E(id1,id2,'Payment')` ✓；`.otherV()` → `1000/Alan` ✓；未知 id → 空 ✓ |
| 按点属性过滤再跳边 | `g.V('Account').filter { it.get().values('accountStatus') == 'FROZEN' }.outE('Payment')` | 17 个点 → **51** 条边 ✓ |
| 按边属性过滤再 otherV | `g.E('Payment').filter { it.get().values('status') == 'SUCCESS' }.otherV()` | **65** ✓；两个过滤器串联 → 59 ✓ |

### 66.1 已经能用的部分（本次实测确认）

* **整类顺序遍历**：RocksDB-Edge 的 `supportsScan() = true`，`g.V('类型')` / `g.E('类')` 走
  `scanVertices`/`scanEdges`（列族里的有序区间），`limit(k)` 由 `scanLimit` 下推 ✓；
* **属性过滤**：闭包里的 `it.get().values('k')`（复数，Groovy 的 `values(String...)`）与 `value('k')` 两种写法都
  能用，且与 `.has('k','v')` 结果一致（都 17 / 65）✓。之前以为 `values()` 不行是误读——`.filter{...FROZEN}.outE()`
  的 51 就是 17×3，正确。

### 66.2 本次新增：按边 id 查找（`g.E($id, '类')`）

原来 `g.E(...)` 只接受一个类名（整类扫描），两个参数直接报错。现在：

* **语法**：最后一个参数是边类，前面的都是边 id（和 `g.V(id..., '类型')` 同一个约定）。
  `g.E('Payment')` 仍是整类扫描；`g.E('id','Payment')` 是该类里的一条；`g.E(id1,id2,'Payment')` 是多条；
  `g.E('id',)`（尾逗号）报「edge class is missing」。
* **接口**：`GraphQuery.readEdgesByIds(edgeName, ids, propertyNames)`（默认实现），语义写在 javadoc 里：
  一条边由"类 + 两个端点 + 创建时间"决定，只给 id 的存储**只能走类**；默认实现**整个请求只走一遍类**（不是每个 id 一遍）
  并保持请求顺序，**有 id 索引的存储应当覆盖它**。返回 `ScannedEdge`（两个端点都在），所以 `otherV()` 知道该往哪走。
* **引擎**：新增 `Source.Kind.EDGES` + `startEdges(...)`（把结果变成带端点的边 traverser）。非扫描型存储
  （Aerospike 内联）会得到一句明确的话：`g.E(id, 'Payment') needs an edge id index, which AerospikeGraphQuery
  does not keep ...`，而不是空答案。

**代价（写在 javadoc 与这里）**：没有 id 索引时，`g.E($id,'类')` 是 **O(类内边数)** 的顺序扫描；200 条边实测 6–7 ms。
下一步若要做成索引：`edge-id:<类>` 列族，`id → (source, direction, createdTime)`，写入时维护 + 存量重建。

### 66.3 测试与复现

```bash
./mvnw -o -pl graph-query-gremlin test          # 190 用例（新增 2：按 id 起跳 + 非扫描存储的报错；改写 1：E(...) 语法）
node .logs/rocksdb-edge-final.js                # 上表 13 条语句（含耗时）
node .logs/rocksdb-edge-has.js                  # has / filter 两种写法一致
node .logs/aerospike-edge-id.js                 # Aerospike 上的两条明确报错
```

---

## 67. RocksDB-Edge：`g.V()` / `g.E()` 空起点 + 带类型的 `has` + 从边继续跳（用 payment-graph-rocksdb-edge 验证）

用户要的四条语句（这次是"TinkerPop 写法"）：

```groovy
g.V('Account').has('accountStatus', 'FROZEN')                                    // 本来就能用 → 17
g.V().has('Account', 'accountStatus', 'FROZEN').outE('Payment')                  // 空起点 + 带类型的 has → 51
g.E('Payment').has('status', 'SUCCESS').outE('Payment')                          // 从边继续跳 → 195
g.E().has('Payment', 'status', 'SUCCESS')                                        // 空起点 + 带类型的 has → 65
```

### 67.1 带类型的 `has(label, key, value)`

`has` 原来只接受 1–2 个参数（`has(key)` / `has(key,value)`），三个参数直接报错。现在：

* **语义**：`has('Account','accountStatus','FROZEN')` = TinkerPop 的"标签 + 属性"形式，等价于
  `hasLabel('Account').has('accountStatus','FROZEN')`，任何位置都能用（`GremlinSteps.hasLabel(label,key,value)`）；
* **起点**：`g.V()` / `g.E()` 是**空起点**（原来 `g.V()` 是 `NONE`、`g.E()` 直接报错；现在分别是
  `Source.Kind.NO_VERTICES` / `NO_EDGES`，执行时仍然是"不产生任何 traverser"，所以
  `g.V().fuzzyLink(...)` 这条路一点没变——实测两个 fuzzy 例子仍返回链接节点）；
* **编译期解析**：空起点 + 第一个步骤是带类型的 `has` 时，那个类型**就是起点**，步骤只留条件：
  `g.V().has('Account','k','v')` ≡ `g.V('Account').has('k','v')`（实测两条写法结果一致，`SchemaDraft`/引擎都按同一
  规则处理）。类型不存在时明确报出图里有哪些（`... names no vertex type of this graph: Account, IP, Phone`）。
  没有 schema 的存储（内存图/测试）跳过这个校验，与方向校验的处理一致。
* **空起点 + 不带类型的 `has`** → 明确报错，因为"没有任何东西可过滤"，并给出两种写法：
  `g.V() starts nothing: name the type it walks, either g.V().has('Account', 'key', 'value') or g.V('Account').has('key', 'value')`。

### 67.2 从边继续跳：`g.E(...).outE('Payment')`

顶点跳（`out/in/both(E)`）以前遇到边 traverser 直接抛
`Step expects vertices but got: GremlinEdge[...]`。现在 hop 的入口统一走 `standOn(ctx, in, dir)`：

* 边被**穿过**到它**指向的端点**（`out*`）或它**来自的端点**（`in*`）——正好是资金流动的方向；
  `both*` 也取"指向的端点"（边本身已经写明两端，换边用 `otherV()`）；
* 穿过时那个端点的**类型从 schema 取**（该边类在那一侧的对端类型），所以后续 `outE()`（不带类名）也知道自己站在什么上，
  并把端点记进子图；
* 端点未知（只从一端见过这条边）或边类不在 schema 里时，报明确错误并提示 `otherV()`；
* `GremlinEdge` 早就有 `sourceEndpoint()`/`targetEndpoint()`，这次正好用上。

实测：`g.E('Payment').has('status','SUCCESS').outE('Payment').count()` = **195**、`.inE('Payment').count()` = **192**、
`.out('Payment').limit(3).values('id')` = 1005/1049/1043、`otherV()` 照旧 ✓。

### 67.3 测试与复现

```bash
./mvnw -o -pl graph-query-gremlin test     # 194 用例（新增 5：带类型起点 ×2、空起点报错、按 schema 校验类型、从边跳）
node .logs/rocksdb-edge-caps4.js           # 用户这四条语句
node .logs/rocksdb-edge-caps5.js           # 报错信息、typed start + outE/inE、otherV 回归
node .logs/fuzzy-empty-start.js            # g.V() 仍是空起点（fuzzyLink 未受影响）
```

---

## 68. 事件序列模式（fuzzy sequence）：按 EventTime 排的事件列表 → 一个 pattern id

用户给了一个账户的事件列表（Login / BalanceCheck / Payment / Dispute，各带 EventTime），要一个**序列**形状的模糊模式：

```
fl_<pattern_id>_L0(ip2(Login.IP))_[<100s]_B1_[<100s]_P2_[<100s]_D3(Dispute.DisputeReason)
```

### 68.1 语义（与用户四条要求一一对应）

| 要求 | 实现 |
|---|---|
| ① 事件按 EventTime 排序；列表可多可少，但这四个都在且顺序不变 | 取 `context.events()` 按 `EventTime` 升序，然后**按步骤贪心匹配**（从最早能对上的事件开始）；未命名的事件被忽略；任一必需步骤缺失或顺序不成立 → 模式**不适用**（不是空 key） |
| ② 相邻事件相差 100 s 以内 | 两个步骤之间的 `_[<100s]_` 就是窗口：`间隔 > 100s` 立即失败（事件已按时间排序，后面的只会更远）。等号成立算通过（`>100000ms` 才算超） |
| ③ Dispute 的 reason 都是 NotMyCard | 步骤值可以是**条件**：`D3(Dispute.DisputeReason)=='NotMyCard'`。带条件时该值只做判断、不进 key（这组人共享同一个常量，放进 key 只会让 id 变长）；不带条件时值进 key → 不同 reason 各自成组 |
| ④ 行为相同的账号关联在一起 | key 由各步骤的**值**拼成（`ip2(Login.IP)+'_'+Dispute.DisputeReason` → `10.122_notmycard`），link 节点 id = `fl_<patternId>_<key>`（`FuzzyKey` 会把 key 小写），所以同一 /16 + 同一 reason 的账号落在同一个节点上 ✓ |

**表达式语法**（`FuzzySequence`）：可选前缀 `fl_<id>_`（写了就要和 pattern id 一致，否则报错）；步骤 `<事件名首字母><序号>`，可带 `(<值表达式>)` 和 `=='字面量'`；步骤之间 `_[<N s]_`。
值表达式沿用平面模式的表达式语言（`ip2(...)`、`raw(...)`、`regex(...)`…），字段可以是**点号路径**（`Login.IP`、`Dispute.DisputeReason`）——为此把 `FuzzyExpression` 的名字词法放宽到允许 `.`，并让 `FuzzyContext.value()` 支持路径查找（找不到路径时退回最后一段，这样"事件列表形状"和"每个事件一个 key 的形状"都能读）。

### 68.2 上下文从哪来（三种形状都认）

1. 用户给的形状：`{"Login": {...}, "BalanceCheck": {...}, ...}`（键=事件名，值里有 `EventTime`）；
2. `{"events": [{"name":"Login","EventTime":...}, ...]}`（ingest 常见）；
3. 上面任一形状作为**一个字符串属性**（如账号的 `eventLog`）——`FuzzyContexts.fromJson` 现在保留嵌套 map/list（以前会被拍平成文本），`parseJson` 遇到 JSON 文本会当文档解析。

时间字段认 `EventTime`/`eventTime`/`time`/`timestamp`/`ts`，数值按毫秒（`> 1e11`）否则按秒，也接受 ISO 字符串；事件名认 `name`/`eventName`/`eventType`/`type`。

### 68.3 演示 pattern（写进 `fuzzy-patterns.json`，payment-graph）

* **#2** `fl_2_L0(ip2(Login.IP))_[<100s]_B1_[<100s]_P2_[<100s]_D3(Dispute.DisputeReason)`
  —— 四步都在、相邻 ≤100 s，key = `/16 + reason`；
* **#3** `fl_3_L0(ip3(Login.IP))_[<300s]_P1_[<300s]_D2(Dispute.DisputeReason)=='NotMyCard'`
  —— 三步版（Login → Payment → Dispute）、窗口 300 s、key = `/24`、reason 作为条件。

两个 pattern 都带 `example`（就是用户那份事件列表），所以 UI 的"示例"按钮与 `/api/fuzzy/demo` 直接能用。

### 68.4 实测（payment-graph，服务在跑）

```
事件列表按 EventTime 排序（用户给的原始时间）：
  Payment@1790147114881 -> Dispute@1790147128881 -> Login@1790147176281 -> BalanceCheck@1790147177881
  g.V().fuzzyLink(2, <原始>)  -> []            ← 顺序与 pattern 不符（见下）
把四个事件的时间改成 pattern 读的顺序（Login -> BalanceCheck -> Payment -> Dispute）：
  g.V().fuzzyLink(2, <有序>)  -> ["fl_2_10.122_notmycard"]
  g.V().fuzzyLink(3, <有序>)  -> ["fl_3_10.122.13"]
/api/fuzzy/validate（pattern 2 + 有序列表）:
  key=10.122_notmycard  link=fl_2_10.122_notmycard
    0. L0(ip2(Login.IP)) -> Login @1790147176281 (+0s) -> 10.122
    1. B1               -> BalanceCheck @1790147176881 (+0s)
    2. P2               -> Payment @1790147177281 (+0s)
    3. D3(...)          -> Dispute @1790147177881 (+0s) -> NotMyCard
    4. key              -> 10.122_NotMyCard
关联 5 个账号后每个账号的组：
  ev_a1 (10.122.13.14, NotMyCard, 正常间隔) -> [fl_3_10.122.13, fl_2_10.122_notmycard]
  ev_a2 (10.122.13.99, NotMyCard, 正常间隔) -> [fl_3_10.122.13, fl_2_10.122_notmycard]   ← 同组
  ev_a3 (10.130.7.14, NotMyCard)           -> [fl_3_10.130.7,  fl_2_10.130_notmycard]   ← 换网段就换组
  ev_a4 (10.122.13.50, 间隔 101s)          -> [fl_3_10.122.13]                            ← 超过 100s，#2 不适用；#3 窗口 300s 仍然命中
  ev_a5 (10.122.13.51, Fraud)              -> [fl_2_10.122_fraud]                          ← reason 不同：#2 另成一组；#3 因 =='NotMyCard' 不命中
g.V('fl_2_10.122_notmycard','FuzzyLink').out('FuzzyLinkedTo').values('id') -> ["ev_a2","ev_a1"]
```

**一个重要发现（要告诉用户）**：**用户给的那份事件列表，按 EventTime 排序后是 Payment → Dispute → Login → BalanceCheck**，
与他们自己的 pattern（Login → BalanceCheck → Payment → Dispute）**顺序不符**，所以原始列表 evaluate 出来是"不适用"（空结果），
不是 bug。间隔本身都合规（14.0s / 47.4s / 1.6s）。要命中就把时间改成 pattern 的顺序（或把 pattern 的步骤顺序改成 P→D→L→B）。

### 68.5 复现与测试

```bash
./mvnw -o -pl graph-fuzzy test -Dtest=FuzzySequenceTest   # 11 用例（解析/顺序/窗口边界/条件/三形状上下文/两种不同的分组）
node .logs/verify-sequence-final.js                        # 上面那张表（原始 vs 有序、每账号的组、组成员）
node .logs/verify-sequence-pattern.js                      # 关联过程（写 5 个账号 + links 接口）
```

### 68.6 可以直接在 UI 上验证的例子（已加进下拉里的"示例查询"）

`app.js` 的 `payment-graph` 示例列表里加了四条（重建 graph-web 后即可见）：

| 示例标题 | 查询 | 结果 |
|---|---|---|
| 事件序列②：事件列表 → 行为组 | `g.V().fuzzyLink(2, '<事件列表>').values('id')` | `["fl_2_10.122_notmycard"]` |
| 事件序列②：这一组里的账号 | 上面再 `.out('FuzzyLinkedTo').values('id')` | `["ev_a1","ev_a2"]` |
| 事件序列③：/24 + 只有 NotMyCard | `g.V().fuzzyLink(3, '<事件列表>').values('id')` | `["fl_3_10.122.13"]` |
| 事件序列：某账号属于哪些行为组 | `g.V('ev_a1','Account').in('FuzzyLinkedTo').values('id')` | 两个组 |

事件列表用的是**种子里的那条会话**（`ev_a1`：Login → BalanceCheck → Payment → Dispute 各差 1 s，外加一个 pattern 不认识的 `Logout` —— 顺便演示"多余的��件被忽略"）。
headless Edge 实测：下拉里出现这四条，点第一条 → 面板显示 `fl_2_10.122_notmycard` ✓。

**数据可复现**：`FuzzyLinkDemoLoader` 现在也写这 5 条事件会话（`ev_a1..ev_a5`）及它们产生的连接节点/边
（它直接用代码里的 context 调 `FuzzyLinks.plan`，所以事件列表**不落库**——真实引擎里"会话"是刚发生的事，
由查询带进来），`FuzzyDemo.DEMO_PATTERNS` 同步加上 pattern 2/3，`FuzzyLinkIntegrationTest` 断言
`largestNodePerPattern = {0=7, 1=4, 2=2, 3=3}` 并用 engine 跑了一遍"事件列表进、组出" ✓（12 用例全绿，跑测试即重新播种 `http_demo_v3`）。

---

## 69. 事件序列模式（第二轮）：双向时间窗口 + 步骤/跨步骤条件

§68 的序列模式只认"上一步之后 N 秒内"（单向窗口）和写在步骤上的 `=='字面量'`。用户这次给了两个更完整的
pattern（大括号是引用、中括号是推断逻辑、小括号是实例内容，替换后如下）：

```
fl_3_L0(ip2(Login.ip))_[<100s]_B1_[-100s<time_interval<100s]_P2_[<100s]_D3(Dispute.DisputeReason)[Dispute.ip==Login.ip]
fl_4_L0(ip2(Login.ip))[Login.type=='Mobile']_[<100s]_B1_[-100s<time_interval<100s]_P2_[<100s]_D3(Dispute.DisputeReason)[Dispute.ip==Login.ip || Dispute.cookie == Payment.Cookie || Dispute.DisputeReason == 'NotMyCard']
```

这正好补上 §68 收尾清单的第 1 条（"不要求先后、只要相邻在窗口内"），并第一次引入**条件**。

### 69.1 语法的三处增量

| 写法 | 语义 | 实现 |
|---|---|---|
| `_[<100s]_` | 下一步在**之后** ≤100s（等号成立算通过） | 沿用 §68；`withinSeconds=100, minSeconds=0` |
| `_[-100s<time_interval<100s]_` | `-100s < 间隔 < 100s`：**双向**，Payment 可以先于 BalanceCheck | 新 `GAP_RANGE` 正则；`withinSeconds=100, minSeconds=-100`；匹配时按"与上一步事件的时间差"落在 `[min,max]` 判定，`min<0` 时允许往前找事件 |
| `[A==B]`、`[A==B || C==D]`、`[Login.type=='Mobile']` | 写在步骤后或序列末尾的**条件**，读各步骤**已匹配事件**的字段 | 新 `FuzzyCondition`：`== != && || ()`，`&&` 优先级高于 `||`；操作数是 `字段路径`（`Dispute.ip`）或字面量（`'Mobile'`） |

**条件读的是"哪个事件"**：匹配完成后把每个命中事件按**事件自己的名字**（`Login`）和**步骤名**（`L0`/`L`）
放进上下文，于是 `Dispute.ip == Login.ip` 就是"第 4 步命中的 Dispute 的 IP 等于第 1 步命中的 Login 的 IP"，
与它在列表里是第几个无关。**字段缺失时比较为 false**（缺证据不算命中），值按文本比较（BIN 的前导零不丢）。

### 69.2 匹配算法：带回溯的贪心

§68 是"按时间排序 + 单向游标贪心"。双向窗口下 Payment 可能在 BalanceCheck 之前，游标回不去，所以改成
**递归回溯**：每一步按时间顺序试所有未被占用、满足窗口的事件，末尾校验全部条件；取**第一个成功的组合**
（仍是"最早出现的那次"）。事件列表很短（一次会话 4–6 条），回溯代价可忽略；另加 50 万次尝试的上限，
超过就明确报"事件列表太大，收窄窗口或减少事件"，而不是静默给错答案。

### 69.3 演示数据：让每个 pattern 真的关联到账号

`FuzzyLinkDemoLoader` 的事件会话从 5 条扩到 9 条（`ev_a1..ev_a9`），列从 5 个扩到 9 个（登录设备、Dispute 的 IP、
Payment/Dispute 各自的 Cookie）。新增的 `ev_a6..ev_a9` 的 BalanceCheck 与 Payment **互换先后**（`1000/500/3500`
毫秒偏移），所以严格单向的 pattern #2 对它们**不适用**——正是为了演示双向窗口：

| 会话 | 登录 | 设备 | reason | Dispute IP | Cookie | pattern 3 | pattern 4 |
|---|---|---|---|---|---|---|---|
| ev_a6 | 10.141.20.30 | Mobile | NotMyCard | = 登录 IP | 相同 | ✓ | ✓（地址/原因两条） |
| ev_a7 | 10.141.20.31 | Mobile | NotMyCard | 10.99.99.99 | 不同 | ✗（地址不符） | ✓（原因=NotMyCard） |
| ev_a8 | 10.141.20.32 | Mobile | Fraud | 10.99.99.98 | 相同 | ✗ | ✓（Cookie 相同） |
| ev_a9 | 10.141.20.33 | Web | NotMyCard | = 登录 IP | 相同 | ✓ | ✗（不是 Mobile） |

`ev_a1/ev_a2` 仍走单向的 pattern #2（同在 10.122，NotMyCard），顺带也被 #3/#4 命中。

### 69.4 实测（`payment-graph`，8081 服务在跑；`.logs/verify-sequence-v2.js`）

```
pattern 3
  ev_a6: ["fl_3_10.141_notmycard"]  members=["ev_a6","ev_a9"]
  ev_a7: []                          ← Dispute.ip 不是登录 IP
  ev_a8: []                          ← 同上（Cookie 不在 pattern 3 里）
  ev_a9: ["fl_3_10.141_notmycard"]  members=["ev_a6","ev_a9"]
pattern 4
  ev_a6: ["fl_4_10.141_notmycard"]  members=["ev_a6","ev_a7"]
  ev_a7: ["fl_4_10.141_notmycard"]  members=["ev_a6","ev_a7"]   ← 靠 reason 进来
  ev_a8: ["fl_4_10.141_fraud"]      members=["ev_a8"]            ← 靠 Cookie 进来，另成一组
  ev_a9: []                          ← Web 登录
```

`FuzzyLinkDemoLoader.load()` 现在会先清掉上一轮 seed 留下的 `FuzzyLinkedTo` 边（对演示账号与扫到的链接点都 `clearEdgeClasses`）+ 删掉扫得到的链接点，
否则 pattern 形状一变就会同时留下新旧两组（实测：老 pattern 3 的 `fl_3_10.122.13` 的**边**已清掉，
`g.V('ev_a1','Account').in('FuzzyLinkedTo')` 从 4 条回到 3 条；该旧点本身是软删（`lastUpdatedTime` 过期），
在后台回收前直接按 id 查仍读得到，但它已没有任何入边，不影响从账号出发的遍历）。

### 69.5 复现与测试

```bash
./mvnw -o -pl graph-fuzzy test -Dtest=FuzzySequenceTest          # 15 用例（新增双向窗口/跨步骤条件/OR 三类）
./mvnw -o -pl graph-fuzzy test                                   # 54 用例
# 无存储的分组断言（3 用例）：每个 pattern 到底关联了哪些账号
./mvnw -o -pl graph-integration-test -am -Dtest=FuzzySessionGroupingTest -Dsurefire.failIfNoSpecifiedTests=false test
# 真 Aerospike 上跑（会重新播种 http_demo_v3）
./mvnw -o -pl graph-integration-test -am -Dtest=FuzzyLinkIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
node .logs/verify-sequence-v2.js                                 # 上面那张实测表
node .logs/run-ui-examples.js                                    # UI 下拉里 6 条序列示例的实际返回
```

`fuzzy-patterns.json` 现在是 5 个模式（#0/#1 平铺，#2/#3/#4 序列），#3/#4 各带 ev_a6 的事件列表作为 example；
顺带修了 `FuzzyPatterns.readExample` 把嵌套对象摊成空串的 bug（序列 pattern 的 example 就是事件列表，必须原样带回来，
`FuzzyLinksTest.aSequenceExampleSurvivesTheFileWithItsEventList` 钉住）。UI 的 `payment-graph` 示例列表里，
序列例子从 4 条变 6 条（pattern 2 / 3 / 3 的组 / 4 的组 / 某账号属于哪些组）。

---

## 70. 事件序列模式（第三轮）：`anyFields` UDF —— 跨事件的共有字段

第三轮的 pattern 引入一个**读整个匹配结果**的 UDF（前两轮的函数都只读一个值）：

```
fl_5_L0(ip2(Login.ip))[Login.type=='Mobile']_[<100s]_B1_[<50s]_P2_[<100s]_D3(anyFields([Login, Payment, Dispute], 3, false))
```

### 70.1 `anyFields` 的语义

`anyFields([事件, ...], n, 是否带值)`：在**这些事件都出现**的字段里，找出**值相等**的那些；数量不足 `n` 就
**模式不适用**（返回 null，不是空 key）。结果按字段名**字母序**：

* 第三个参数 `false`（默认）：只列字段名 → `cookie_ip_type`；
* `true`：每个字段带上它们一致的值 → `cookie=c1_ip=10.12.13.14_type=Mobile`（`FuzzyKey` 最后会把整条 key 小写）。

**簿记字段永远不参与**：`id`、`eventTime/time/timestamp/ts`、事件名（`name/eventName/eventType/event`）——
它们要么每个事件不同（时间），要么只是流水号，不构成"同类会话"的签名。字段名大小写/下划线不敏感，值按文本比。

### 70.2 为此加的语法与求值能力

| 增量 | 说明 |
|---|---|
| 列表字面量 `[Login, Payment, Dispute]` | `FuzzyExpression` 的 `Node.List`；只作为**参数**出现，不是可求值的值 |
| `true` / `false` 字面量 | 解析成 `Literal`（以前会被当成字段名 `false`） |
| **上下文函数**类别 | `FuzzyFunctions` 新增 `registerContext(...)`/`isContextFunction(...)`/`applyContext(...)`；`anyFields` 注册在这里。普通函数签名是"一个值 + 参数"，装不下"一组事件" |
| 参数**按写法渲染** | 上下文函数不做逐值求值：字段保留名字（`Login` 表示事件）、列表用逗号连接、嵌套调用求值。`anyFields` 再按 `,` 拆开事件名，用 `FuzzyContext.find(名字)` 取到事件 map |
| 步骤值改用**整个匹配结果**求值 | 以前 `D3(...)` 只看 D3 命中的那个事件；现在用"所有命中事件（按事件名/步骤名寻址）+ 当前事件字段"的合并上下文，`anyFields` 才看得到 Login/Payment/Dispute |
| 值/条件校验移到**叶子** | 搜索时只做"事件名 + 窗口"，全部步骤配好、值从合并上下文取到、条件成立才算命中；值缺失就回溯换一种配法 |

### 70.3 演示数据与每个 pattern 关联的账号

`ev_a1/a2/a5`（各自 /16 相同、Mobile、登录/支付/争议三事件都带相同的 `Cookie`/`IP`/`Type`）→ anyFields 找到
`cookie_ip_type`；`ev_b1`（10.155）自己一组；`ev_b2` 只有 `Type` 一致（IP 与 Cookie 都不同）→ 不适用；
`ev_b3` 在 BalanceCheck→Payment 之间放了 **60s**（越过 `[<50s]`）→ 不适用。三个 pattern 的组大小：
`#2=2, #3=2, #4=2, #5=3`（`{0=7,1=4,2=2,3=2,4=2,5=3}`）。账号总数 30（环 18 + 12 条会话），57 条 link 边。

### 70.4 实测（`.logs/verify-anyfields.js`，8081 服务在跑）

```
pattern 5 (anyFields)
  ev_a1: ["fl_5_10.122_cookie_ip_type"]  members=["ev_a1","ev_a2","ev_a5"]   ← cookie/ip/type 三项一致
  ev_b1: ["fl_5_10.155_cookie_ip_type"]  members=["ev_b1"]
  ev_b2: []                              ← 只有 Type 一致（<3）
  ev_b3: []                              ← B→P 60s，越过 [<50s]
```

`g.V('ev_a1','Account').in('FuzzyLinkedTo').values('id')` 现为 4 条（#2/#3/#4/#5 各一）。
`false`/`true` 两种拼法都有单测：`FuzzySequenceTest.anyFieldsListsTheFieldsEveryEventAgreesOn`
（`cookie_ip_type` vs `cookie=c1_ip=10.1.2.3_type=Mobile`），簿记字段不计数见
`anyFieldsNeverCountsTheBookkeepingFields`。

### 70.5 复现与测试

```bash
./mvnw -o -pl graph-fuzzy test -Dtest=FuzzySequenceTest      # 19 用例（新增 anyFields 4 个）
./mvnw -o -pl graph-fuzzy test                               # 58 用例
./mvnw -o -pl graph-integration-test -am -Dtest=FuzzySessionGroupingTest -Dsurefire.failIfNoSpecifiedTests=false test  # 4 用例（#5 的组成员）
./mvnw -o -pl graph-integration-test -am -Dtest=FuzzyLinkIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test # 15 用例（真 Aerospike，播种 http_demo_v3）
node .logs/verify-anyfields.js                               # 上面那张实测表
node .logs/run-ui-examples.js                                # UI 下拉里 8 条序列示例
```

`fuzzy-patterns.json` 现在 6 个模式（#0/#1 平铺，#2–#5 序列）；UI 的 `payment-graph` 示例 8 条。

---

## 71. Gremlin 文本区支持 Groovy 风格脚本（管理员开关）

用户报：`def ids = g.V(1001, 'Account').values('id').toList()` + `print(ids)` 这种多语句写法报
`Expected 'g.V(...)' or 'g.E(edgeName)' prefix`。原因是文本区只接受**一条遍历**。这一批让同一批遍历可以
写成**一段脚本**，并给文本区一个**只给管理员看、暂时默认放开**的开关。

### 71.1 支持的脚本语法（新类 `GremlinScript`）

```groovy
def ids = g.V(1001, 'Account').values('id').toList()
print(ids)
```

| 形状 | 语义 |
|---|---|
| `def name = <expr>` / `name = <expr>` | 存一个值（变量） |
| `print(<expr>)` / `println(<expr>)` | 记一行输出 |
| `return <expr>` | 立即返回 |
| `<expr>` | 最后一条的表达式的值就是答案 |
| `g.V(var, 'Type')` | 变量可当 id：列表展开成逗号分隔的 id，元素取它的 id；空列表明确报"变量为空" |
| `var.size()/count()/toList()/toSet()/isEmpty()/next()/get(i)` | 变量上的小方法链 |
| `[1, 'a', true]`、字符串、数字、布尔、null | 字面量 |

**结果口径**：有 `print` 时，一次 `print` 的值就是答案（`print(ids)` → 那批 id），多次 `print` 则是这些值的列表；
没有 `print` 时是最后一条语句的值。响应里另有 `output`（打印的文本行）和**所有遍历的合并子图**（画布照常画）。

### 71.2 为什么不是真正的 Groovy 运行时

引擎一直**故意没有 Groovy**（`GremlinGraphQuery` 的注释写着没有 `GroovyShell`/`GroovyClassLoader`，表达式语言是自己的）。
把一个真正的 Groovy 塞进服务端等于把任意代码执行放进查询接口；这一版做的是**Groovy 形状的子集**（语句/变量/`def`/`print`/列表/方法链），
在项目自己的解析器上跑，所以既能写用户要的多语句查询，又不引入新依赖与执行面。读不出的表达式会明确点名支持范围
（`Unknown script method '.shuffle()'. Supported: ...` / `Unknown script variable 'x'`），不会静默返回空。

### 71.3 API

`POST /api/v1/query` 的请求多了可选 `script`：`true` 强制按脚本跑，`false` 强制按单条遍历跑，**不传则由文本自己判断**
（有 `def`/`print`/`return`/赋值或多条语句就是脚本；分行的链式遍历（下一行以 `.` 开头）仍算一条）。响应为
`ScriptResponse{result, output, subgraph}`——`result`/`subgraph` 与原来的约定一致，UI 无需特殊分支即可显示。

### 71.4 UI（管理员开关）

`index.html` 的 Gremlin 行里加了 `#scriptToggleWrap`（`#scriptToggle`）：**只给管理员看**由
`IS_ADMIN`/`SHOW_SCRIPT_MODE` 两个常量把关，现在都是 `true`（"暂时放开"；把 `IS_ADMIN` 改 false 即对所有人隐藏，
后端仍能自动识别脚本）。打开后占位符变成脚本示例（`def ids = ...`），请求带 `script: true`，选择记忆在 `localStorage`；
示例下拉里也加了一条"脚本：先取一批 id，再统计"。

### 71.5 实测（8081 服务在跑；`.logs/verify-script-mode.js`）

```
用户的那段：  def ids = g.V(1001,'Account').nearest(5).values('id').toList()  /  print(ids)
  -> result ["1004","1007","1010","1013","1016"]   output ["[1004, 1007, 1010, 1013, 1016]"]   子图 6 点
变量当 id：   g.V(ids, 'Account').outE('Payment').count()  -> 5   （子图 20 点，说明确实从这些 id 走的）
方法链：      print(ids.size()) / print(ids.get(0))  -> 5 / "1004"
多次 print：  -> result [5, ["1004",...]]  output ["5","[1004,...]"]
空变量：      def ids = <空>.toList(); g.V(ids,...)  -> 400 "The script variable 'ids' is empty, so there is no id to walk from"
单条遍历：    g.V(1001,'Account').nearest(3).values('id')  -> 不是脚本，结果不变
```

headless Edge 驱动真实页面：开关可见（标签"脚本"）✓、示例下拉里有脚本条目 ✓、打开开关后占位符变化 ✓、
跑用户脚本后面板显示 `5 项 · 5 个不同值`、画布 6 点 5 边 ✓。

### 71.6 复现与测试

```bash
./mvnw -o -pl graph-query-gremlin test -Dtest=GremlinScriptTest   # 11 用例（解析/变量/print/链/空变量/子图合并/错误文案）
./mvnw -o -pl graph-query-gremlin test                            # 204 用例
./mvnw -o -pl graph-http test                                     # 73 用例（QueryControllerTest 12，含 script 绑定）
node .logs/verify-script-mode.js                                  # 上面的 headless UI 实测
```

顺带修了演示环境的一件事：`payment-graph` 的 HNSW 索引 size 掉到 0（上一批截断顶点集留下的），
`POST /api/v1/vector-index/rebuild` 重建为 152 点 / 9 层 / 2883 条链接，`nearest(5)` 恢复。

---

## 72. 向量索引边默认隐藏、`path()`、sender/receiver 四跳脚本

三件事一起做的：正常查询里不显示 HNSW 的 `accountVector_x` 索引边、补上 `path()` step、给出一段可在 UI 跑的两点四跳路径脚本。

### 72.1 未命名 hop 跳过向量索引层边

`accountVector_0..8` 是 HNSW 自己的层边，不是数据关系。以前 `g.V(1001,'Account').both()` 会把它们当邻居读、
画布也会把它们画出来。实现：`GremlinGraphQuery` 从 schema 算出向量属性对应的层边名（`VectorIndexSchemas.levelOfLayerEdge`），
通过 `GremlinContext.hiddenEdgeClasses(...)` 传给执行上下文；`readAllEdgeClassesBatched`（**只有未命名 hop 走这条**）
在返回前把这些层边滤掉。于是：

* `g.V(1001,'Account').bothE()` 只剩 `Payment/PhoneToAccount/IPToAccount`（实测 37/1/16 条，**无 vector**）；
* 显式命名层边（`bothE('accountVector_0')`）仍然读得到（单元用例钉住）；
* `nearest(k)` 的搜索走的是**命名**层边（`GraphQueryHnswStore` 按 `HnswLevels.edgeName` 读），不受影响，且它记录的
  layer-0 链接照旧进子图 —— 也就是"只有查向量相似时才显示"（实测 `nearest(3)` 子图 3 条 `accountVector_0`）。

### 72.2 `path()`

新类 `GremlinPath`（`extends AbstractList<Object>`，即"可以被当 list 用的 path 对象"），`path()` 由
`GremlinGraphQuery#runPathCompiled` 执行：普通管线是 list 变换（只知道当前元素、不知道从哪来），所以路径需要一次"带记忆的走"。
步骤序列在 `path()` 之前用**路径感知**方式走：hop 追加走过的边（`out` 追加边+到达点，`outE` 只追加边，`otherV` 追加到达点），
`has/filter/limit/dedup` 作用在每条路径的最后一个元素上、不匹配就整条丢弃；`path()` 之后照常走普通管线。
`path()` 出现在 hop 之后以外的位置会明确报错，而不是悄悄丢路径。

```
g.V(1001, 'Account').out('Payment').out('Payment').path()   -> 81 条 path，每条 5 个元素（v,e,v,e,v）
g.V(1001, 'Account').out('Payment').out('Payment').path().count() -> 81
```

path 里的点是**全属性**读的（编译期对含 `path()` 的查询把所有投影计划设为"全读"），所以画布/答案能显示点内容。

### 72.3 脚本层补的集合方法

为写四跳脚本给 `GremlinScript` 的方法链补了：`last/first/start/end/reverse/get/contains/indexOf/add/addAll/retainAll(或 intersect，
去重的公共值)/distinct/join/joinOnEnd`，以及表达式的 `+`（数字相加、其余按文本拼接，用来 `print('...: ' + x)`）。
集合方法**返回新列表**（脚本里赋值），`joinOnEnd(other)` 把两组 path 按"末端是同一个点"拼成更长的 path：
右半段反向、公共点只留一次、**走过的点重复出现（成环）就丢弃**——正是"达不到四跳的路径要排除"。
另外把结果口径讲清楚：**脚本最后是 `print` 时，打印的值就是答案；最后是一条表达式时，那个表达式的值才是答案，print 只是 output。**

### 72.4 sender/receiver 四跳脚本（可放 UI 跑）

`payment-graph` 上 sender=1000、receiver=1006（两点各有两跳路径、且有公共中点）：

```groovy
def sender = 1000
def receiver = 1006

def sPaths = g.V(sender, 'Account').out('Payment').out('Payment').path().toList()
def rPaths = g.V(receiver, 'Account').out('Payment').out('Payment').path().toList()

def sMids = g.V(sender, 'Account').out('Payment').out('Payment').values('id').toList()
def rMids = g.V(receiver, 'Account').out('Payment').out('Payment').values('id').toList()
def common = sMids.retainAll(rMids)
def midObjects = g.V(common, 'Account').toList()

def four = sPaths.joinOnEnd(rPaths)

def answer = []
answer = answer.addAll(sPaths)
answer = answer.addAll(rPaths)
answer = answer.addAll(four)
print('sender two-hop paths: ' + sPaths.size())
print('receiver two-hop paths: ' + rPaths.size())
print('common mids: ' + common)
print('middle objects: ' + midObjects.size())
print('four-hop paths: ' + four.size())
answer
```

实测（`.logs/verify-paths.js`）：sender 两跳 33 条、receiver 两跳 9 条、公共中点 `[1008,1014,1020,1026]`、
中点对象 4 个、四跳路径 **30** 条、`answer` 共 72 个 path 对象；一条四跳：`1000 → 1001 → 1008 → 1007 → 1006`。
（脚本里 `g.V(common,'Account')` 用的是上一批做的"变量当 id"；`midObjects` 与四跳路径中间的点是同一批对象。）

### 72.5 复现与测试

```bash
./mvnw -o -pl graph-query-gremlin test -Dtest=GremlinPathTest   # 4 用例（path 结构/多分支/hop-after-path/隐藏层边）
./mvnw -o -pl graph-query-gremlin test -Dtest=GremlinScriptTest # 17 用例（joinOnEnd/环剔除/retainAll/+/两端）
./mvnw -o -pl graph-query-gremlin test                          # 215 用例
./mvnw -o -pl graph-http test                                    # 73 用例
node .logs/verify-paths.js                                        # 上面 72.1–72.4 的实测
```

UI 的 `payment-graph` 示例里加了两条：`g.V(1001,'Account').out('Payment').out('Payment').path()` 与上面那段四跳脚本。
**注意**：服务重启会重置 `payment-graph` 的 HNSW 索引（size 变 0，`nearest` 空），起来后要跑一次
`POST /api/v1/vector-index/rebuild`（这次重建为 152 点 / 9 层 / 2913 链接）。

---

## 73. 四跳脚本改写：第二跳只跳边（`outE`）+ 性能

用户要求：sender / receiver 的**最后一跳**不用 `out('')`，直接 `outE('')`。改完后路径以**边**结尾，
落点从边本身读（`otherVertexId`），少读一批点；脚本里公共点对象由第 2 步 `g.V(common,'Account')` 取来，
拼接时填进中间。

### 73.1 改写后的脚本（UI 示例同步）

```groovy
def sender = 1000
def receiver = 1006

// 第二跳只跳边：path 以边结尾 [点, 边, 点, 边]
def sPaths = g.V(sender, 'Account').out('Payment').outE('Payment').path().toList()
def rPaths = g.V(receiver, 'Account').out('Payment').outE('Payment').path().toList()

// 落点 = 最后那条边的另一端；求交拿公共 id，再取点对象
def common = sPaths.endIds().retainAll(rPaths.endIds())
def midObjects = g.V(common, 'Account').toList()

// 按公共落点拼四跳，中间点用 midObjects 的对象填充；成环的丢弃
def four = sPaths.joinOnEnd(rPaths, midObjects)

def answer = []
answer = answer.addAll(sPaths)
answer = answer.addAll(rPaths)
answer = answer.addAll(four)
print('sender two-hop paths: ' + sPaths.size())
print('receiver two-hop paths: ' + rPaths.size())
print('common mids: ' + common)
print('middle objects: ' + midObjects.size())
print('four-hop paths: ' + four.size())
answer
```

为此脚本层加了两个方法：`endIds()`（每条 path 的落点：末元素是点就是它，是边就是 `otherVertexId`——**不读点**），
以及 `joinOnEnd(other, middleObjects)`（末元素是边时，用第 2 步取到的点对象填公共点，再反接另一半）。

### 73.2 结果与改前一致

```
sender two-hop paths: 33   receiver two-hop paths: 9
common mids: [1008, 1014, 1020, 1026]   middle objects: 4
four-hop paths: 30   answer = 72 个 path 对象
一条四跳: 1000 -> 1001 -> 1008 -> 1007 -> 1006
```

### 73.3 性能（`.logs/bench-path.js`，100 次交错 A/B，计数器取每次查询前后的差值）

| 变体 | mean | median | p95 | p99 | max | kv.read | vertex.read | edge.read | 答案 | 响应 |
|---|---|---|---|---|---|---|---|---|---|---|
| A 第二跳 `out('Payment')` | 34.72 ms | 34.61 | 42.16 | 44.72 | 44.72 | 33 | 44 | 8 | 72 paths | 154.89 KB |
| **B 第二跳 `outE('Payment')`（改写）** | **29.20 ms** | **29.51** | **35.38** | **40.47** | **40.47** | **23** | **34** | 8 | 72 paths | **139.56 KB** |

**B vs A：延迟 −5.53 ms（−15.9%）、kv 读 −10、点读 −10、边读不变（8）、响应小 15.3 KB。** 省下的正是第二跳
"读到达点"的那 10 次点读（sender 侧 5 + receiver 侧 5 个点）；两跳 path 里因此也只带边、不带到达点对象，响应更小。
数字在 50 次与 100 次两轮完全一致（工作负载可复现）。

### 73.4 复现

```bash
node .logs/verify-paths.js      # 结果等价（33/9/4/30/72 与一条四跳样例）
node .logs/bench-path.js 100    # 上表（改成别的次数即可）
```

---

## 74. Groovy 脚本：控制流与闭包；TinkerPop 3.8.2 step 兼容表

用户两问：脚本模式能不能写 `for`/`if`/闭包；参考文档里的 Gremlin step 都支持吗。前者补齐了，后者逐条核对并记录（下表）。

### 74.1 脚本层新增：控制流、闭包、算术

`GremlinScript` 从"一串平铺语句"变成"语句树"，因此可以嵌套：

| 写法 | 说明 |
|---|---|
| `if (cond) { ... } else { ... }` / `else if` | 条件支持 `&& \|\| !`、`== != < <= > >=`、括号；两边都是普通脚本表达式（`g.V(...).count() > 0` 也可） |
| `for (x in <list>) { ... }` | 遍历列表、数组、Map 的 entry；`g.V(...)` 的结果也是列表 |
| `while (cond) { ... }` | 有 10 万次上限，防呆循环 |
| `break` / `continue` | 在 `for`/`while` 里生效 |
| 闭包 `{ ... }` / `{ x -> ... }` / `{ x, i -> ... }` | 作为方法参数：`each`、`eachWithIndex`、`collect`、`findAll`、`find`、`any`、`every`、`count`、`sum`；无参时绑定 `it`，参数遮蔽外层同名变量并在调用后还原 |
| 算术 `+ - * / %` 与比较 | `+` 数字相加/其余拼接；`*`/`-`/`%` 整数保持整数，`/` 为浮点；`!` 取反 |
| 元素访问 | 闭包里 `it.id()`、`it.label()`、`it.value('k')`、`it.values('a','b')`、`it.properties()`；`'abc'.size()`、`[1,2].size()` 这类字面量上的方法也支持 |
| 真值 | Groovy 真值：null/false/0/空串/空集合/空 Map 为假 |

实测（8081）：

```groovy
def ids = [1000, 1001, 1002, 1003]
def hits = []
for (id in ids) {
  def n = g.V(id, 'Account').outE('Payment').count()
  if (n.get(0) > 0) { hits = hits.add(id + ':' + n.get(0)) }
}
def edges = g.V('1000','Account').outE('Payment').toList()
def statuses = edges.collect { it.value('status') }
def successful = statuses.findAll { it == 'SUCCESS' }
print('hits: ' + hits); print('statuses: ' + statuses); print('successful: ' + successful.size())
```
→ `hits: [1000:3, 1001:27, 1002:3, 1003:3]`、`statuses: [SUCCESS, PENDING, SUCCESS]`、`successful: 2`。

**仍然刻意不做**：类/`import`/线程/IO/`try-catch`/`switch`/区间 `1..5`/Map 字面量 `[k:v]`/`each` 之外的 Map 方法/多方法派发。
这不是"能跑的 Groovy"，是"查询需要的 Groovy 子集"，读不出的写法会明确报错（例如 `.shuffle()` 会列出支持的方法）。

### 74.2 TinkerPop 3.8.2 step 兼容表

对照 <https://tinkerpop.apache.org/docs/3.8.2/reference/> 的步骤目录逐条核对。✅ 支持、🟡 部分（语法或能力有差别）、❌ 未支持（附原因）。

**起点 / Start**

| step | 状态 | 备注 |
|---|---|---|
| `g.V(id..., type)` / `g.V(type)` / `g.V()` | ✅ | 空起点给 `fuzzyLink`/`nearest` 这类自带起点的 step |
| `g.E(class)` / `g.E(id..., class)` / `g.E()` | ✅ | |
| `inject`, `V()`, `E()`（中途） | ❌ | 不产生新起点的语义未做 |

**顶点跳 / 边跳（flatMap）**

| step | 状态 | 备注 |
|---|---|---|
| `out` `in` `both`（0..n 个类名） | ✅ | |
| `outE` `inE` `bothE` | ✅ | 支持 `has`/`filter`/`limit` 下推到存储 |
| `otherV` | ✅ | |
| `outV` `inV` `bothV` | ❌ | 只有 `otherV`（本条边"另一端"） |
| `edge` `V` `E`（中途） | ❌ | |

**过滤 / filter**

| step | 状态 | 备注 |
|---|---|---|
| `has(key)` / `has(key,value)` / `has(label,key,value)` | ✅ | 值支持字符串/数字/`matches` 的 token |
| `hasLabel` `hasId` `hasKey` `hasNot` `hasValue` | ✅ | |
| `filter { ... }` | ✅ | 闭包用自己的表达式语言 |
| `filter(<child>)` / `where` / `and`/`or`/`not`(child) | 🟡 | `not/and/or` 接受子遍历（`not(out('x'))`）；`filter` 只收闭包表达式；`where` 未做 |
| `is(value)` / `is(gt/lt/gte/lte/neq/eq)` | ✅ | `within/between/inside/outside` 未做 |
| `dedup` | 🟡 | 全局去重；`dedup('a')`/`by()` 未做（无 step label） |
| `limit` `range` `skip` `tail` `sample` | ✅ | `sample` 用固定种子（可复现优先）；`limit(local)` 未做 |
| `coin` `timeLimit` `simplePath` `cyclicPath` | ❌ | 需要遍历器历史/随机副作用 |
| `all` `any` `none`（列表谓词）, `has(key,P)` 的 `P` 形式 | ❌ | |

**映射 / map**

| step | 状态 | 备注 |
|---|---|---|
| `values` `valueMap` `elementMap` | ✅ | `valueMap` 无 `by()`/`with(tokens)` |
| `map { ... }` | ✅ | 闭包 |
| `id` `label` `identity` `constant` | ✅ | |
| `path` | 🟡 | 支持 hop 链 + `has/filter/limit/dedup` 后 `path()`；`path().by()/from()/to()` 未做，`path()` 后不能接 hop |
| `count` `sum` `min` `max` `mean` | ✅ | 全局；`count(local)`/`by()` 未做 |
| `fold` `unfold` | ✅ | |
| `order([key[, asc\|desc]])` | 🟡 | 直接给 key；TinkerPop 的 `order().by('k', desc)` 形式未做 |
| `groupCount([key])` | 🟡 | 同上；缺属性归到 `(none)`（JSON 不能有 null 键） |
| `group` `project` `select` `sack` `loops` `math` `index` `element` | ❌ | 需要 step label/副作用/遍历器状态 |
| `toLower` `toUpper` `trim` `length` | ✅ | |
| `asString` `asNumber` `asBool` `asDate` `concat` `split` `substring` `replace` `format` `lTrim` `rTrim` | ❌ | 未做 |
| `dateAdd` `dateDiff` `dateFormat` | ❌ | 未做 |
| `combine` `conjoin` `difference` `disjunct` `intersect`(step) `product` | ❌ | 集合运算未做（脚本里可用 `retainAll/addAll` 等） |

**分支 / 副作用**

| step | 状态 | 备注 |
|---|---|---|
| `union(child...)` `coalesce(child...)` `local(child...)` | ✅ | 子遍历参数（`union(out('a'), out('b'))`） |
| `repeat` `emit` `until` `times` `choose` `optional` `branch` `match` | ❌ | 需要遍历器的循环/标签状态；可用脚本 `for`/`if` + `union`/`coalesce` 近似 |
| `sideEffect` `inject` `aggregate` `store` `cap` `subgraph` `tree` | ❌ | 无 side-effect 存储 |
| `with` `withSideEffect` `withSack` `withStrategies` `withoutStrategies` | ❌ | 不适用 |
| `profile` `explain` | ❌ | 指标走 `/api/v1/metrics/{graph}` |
| `barrier` `batch` | ✅ | 本项目自己的批量/屏障写法 |
| `createdBetween` `nearest` `circle` `fuzzyLink` `pageRank` `louvain` `connectedComponents`/`cc` `sssp` `bfs` | ✅ | 本项目扩展（`nearest`/`circle`/`fuzzyLink`/算法） |

**写 / 终端**

| step | 状态 | 备注 |
|---|---|---|
| `toList` `toSet` `toBulkSet` `toIterator` `next` `iterate` `none` `discard` | ✅ | 引擎本来就返回列表 |
| `count` `size` | ✅ | `size()` 是 Groovy 拼写 |
| `addV` `addE` `mergeV` `mergeE` `property` `drop` `io` `read` `write` `call` | ❌ | 查询引擎只读；写入走 HTTP 写接口（`POST /api/v1/graphs/{graph}/vertices|edges`） |
| `hasNext` `tryNext` `promise` `fill` `asAdmin` | ❌ | |

### 74.3 复现

```bash
./mvnw -o -pl graph-query-gremlin test -Dtest=GremlinScriptTest        # 27 用例（控制流/闭包/算术/元素访问）
./mvnw -o -pl graph-query-gremlin test -Dtest=GremlinTinkerPopStepsTest # 11 用例（新 step）
./mvnw -o -pl graph-query-gremlin test                                  # 236 用例
```

新增实现：`GremlinSteps` 的 `identity/constant/id/label/hasId/hasNot/hasKey/hasValue/is/range/skip/tail/sample/fold/unfold/sum/mean/max/min/order/groupCount/valueMap/toLower/toUpper/trim/length`；`GremlinGraphQuery` 注册的 `union/coalesce/local/not/and/or`（子遍历）；`GremlinQuery.splitArgs` 改为按括号深度切分（支持嵌套子遍历）。`GremlinScript` 新增控制流/闭包/算术/元素方法。

---

## 75. TinkerPop step 支持（第二批）：`by()`、字符串/类型/集合、分支与循环近似

用户要求"除写入外尽量把其余未支持的 step 也支持上"。这一批把 §74.2 表里可做的都做了，剩下的确实需要换遍历器模型（见 75.3）。

### 75.1 新增（✅）

| 组 | step |
|---|---|
| 调制器 | `by(key[, asc\|desc])`——编译器把它折进前一个 step：`order().by('age')`→`order('age')`、`order().by('age', desc)`、`groupCount().by('k')`、`dedup().by('k')`、`path().by('k')`（`path().by` 返回"每个元素在该键上的值"的 walk，缺键处为 null） |
| 元素/值 | `properties`、`elementMap`（已有）、`key`/`value` 以 `valueMap` 覆盖；`dedup(key)` 按属性去重 |
| 字符串 | `asString`、`concat`、`split`、`substring`、`replace`、`lTrim`、`rTrim`（`toLower/toUpper/trim/length` 已有） |
| 类型 | `asNumber`、`asBool`、`asDate`（ISO-8601 → epoch millis，与 schema 存时间的口径一致） |
| 集合 | `combine`、`intersect`、`difference`、`disjunct`、`all(p)`、`any(p)`、`none(p)`（列表谓词；`none()` 仍是终端） |
| 分支 | `optional(t)`（= coalesce(t, identity)）、`where(t)`（t 有结果就保留）、`choose(pred, a, b)`、`branch` 未做 |
| 循环 | `repeat(t).times(n)`（`times` 由编译器折进 `repeat`；t 重复 n 次） |
| 路径 | `simplePath()`、`cyclicPath()`（在 `path()` 的走链里按"点是否重复"过滤） |
| 其它 | `coin(p)`（固定种子、可复现）、`inject(v...)` 与 `g.inject(v...)` 起点、`timeLimit(ms)`（引擎无长步骤循环可打断，做透传） |

### 75.2 实测（8081）

```
g.V(1000,'Account').outE('Payment').order().by('createdTime', desc).limit(1).id()  -> ["pay_r0"]
g.V(1000,'Account').outE('Payment').groupCount().by('status')                       -> [{"SUCCESS":2,"PENDING":1}]
g.V(1000,'Account').outE('Payment').dedup().by('status').count()                    -> [2]
g.V(1000,'Account').outE('Payment').values('status').toUpper()                      -> ["SUCCESS","PENDING","SUCCESS"]
g.inject('2025-01-01T00:00:00Z').asDate()                                            -> [1735689600000]
g.V(1000,'Account').outE('Payment').values('status').fold().combine([100]).unfold() -> ["SUCCESS","PENDING","SUCCESS",100]
g.V(1000,'Account').optional(out('Payment')).count()                                 -> [3]
g.V(1000,'Account').repeat(out('Payment')).times(1).count()                          -> [3]
g.V(1000,'Account').out('Payment').out('Payment').simplePath().path().count()        -> [33]
```

### 75.3 仍未支持（以及为什么）

这些都是**同一个原因**：本引擎的遍历器是"列表里一个值"，没有逐条 traverser 的标签/副作用/循环状态。要把它们做对，需要先把执行模型换成带 `Traverser{value, path, sack, labels}` 的管线（一次大改，不是加 step）：

* **标签/副作用**：`as`、`select`、`match`、`project`、`sack`、`loops`、`cap`、`aggregate`、`store`、`subgraph`、`tree`、`index`、`math`、`element`、`vertex`、`edge`、`propertyMap`、`key`(step)、`value`(step)、`properties`(step)、`outV/inV/bothV`。
* **真正的循环/分支**：`repeat` 的 `until`/`emit`、`branch`（多谓词）、`match`（依赖标签）；可继续用脚本 `for`/`if` + `union/coalesce/optional/choose` 近似。
* **带标签的 `where`**：`where('a', eq('b'))`。
* **谓词扩展**：`is(within/between/inside/outside)`、`has(key, P)`、`all/any/none` 以外的 `P` 组合。
* **调制器扩展**：`order().by(traversal)`、`valueMap().by()`、`path().from()/to()`、`group()`。
* **集合/杂项**：`conjoin`、`product`、`format`、`profile`、`explain`、`hasNext`/`tryNext`/`promise`/`fill`/`asAdmin`（后四个是 Java API）、`V()`/`E()` 中途重启扫描。
* **写入**（用户明确排除）：`addV/addE/mergeV/mergeE/property/drop/io/read/write/call` —— 查询引擎只读，写入走 HTTP 写接口。

### 75.4 复现与测试

```bash
./mvnw -o -pl graph-query-gremlin test -Dtest=GremlinTinkerPopStepsTest  # 15 用例（+by/字符串/集合/分支/inject）
./mvnw -o -pl graph-query-gremlin test -Dtest=GremlinPathTest            # 5 用例（+path().by / simplePath / cyclicPath）
./mvnw -o -pl graph-query-gremlin test                                   # 241 用例
```

---

## 76. Gremlin step 支持现状（合并版，代替向前翻 §74.2/§75 的增量）

§74.2 是逐条核对的第一版，§75 是第二批增量；这里合并成一份当前口径。✅ 支持、🟡 部分、❌ 未支持（附原因）。

### 起点

| step | 状态 |
|---|---|
| `g.V(id..., type)`、`g.V(type)`、`g.V()`、`g.E(class)`、`g.E(id..., class)`、`g.E()`、`g.inject(v...)` | ✅ |
| `V()` / `E()` 中途重启扫描、`inject` 以外的 start step | ❌ |

### 跳（flatMap）

| step | 状态 |
|---|---|
| `out` `in` `both`（0..n 类名） | ✅ |
| `outE` `inE` `bothE` | ✅ |
| `otherV` | ✅ |
| `outV` `inV` `bothV` `edge` `V`/`E`(中途) | ❌ |

### 过滤（filter）

| step | 状态 |
|---|---|
| `has(key)` / `has(key,value)` / `has(label,key,value)` | ✅ |
| `hasLabel` `hasId` `hasNot` `hasKey` `hasValue` | ✅ |
| `filter { ... }`（闭包表达式） | ✅ |
| `is(v)` / `is(gt\|gte\|lt\|lte\|neq\|eq)` | ✅ |
| `not` `and` `or`（子遍历） | ✅ |
| `where(child)` | ✅ |
| `dedup` / `dedup(key)` | 🟡 全局或按 key；`dedup('a','b')` 多标签未做 |
| `limit` `range` `skip` `tail` `sample` | ✅（`sample` 固定种子以保证可复现；`limit(local)` 未做） |
| `coin(p)` | ✅（固定种子） |
| `all(p)` `any(p)` `none(p)`（列表谓词） | ✅ |
| `has(key, P)`、`is(within/between/inside/outside)` | 🟡 谓词形式有限 |
| `simplePath` `cyclicPath` | ✅（需与 `path()` 同用） |
| `timeLimit(ms)` | ✅（透传） |
| `where('a', eq('b'))`、`match` | ❌ 需 step label |

### 映射/聚合（map）

| step | 状态 |
|---|---|
| `values` `valueMap` `elementMap` `map { ... }` | ✅（`valueMap().by()`/`with(tokens)` 未做） |
| `id` `label` `identity` `constant` | ✅ |
| `count` `sum` `min` `max` `mean` | ✅ 全局；`count(local)`/`by()` 未做 |
| `fold` `unfold` | ✅ |
| `order` / `order(key[, asc\|desc])` / `order().by('k'[, desc])` | ✅ |
| `groupCount` / `groupCount(key)` / `groupCount().by('k')` | ✅（缺属性归到 `(none)`，JSON 不能有 null 键） |
| `path` / `path(key)` / `path().by('k')` | 🟡 支持 hop 链 + `has/filter/limit/dedup/simplePath/cyclicPath`；`path().from()/to()` 未做 |
| `toLower` `toUpper` `trim` `length` `asString` `asNumber` `asBool` `asDate` `concat` `split` `substring` `replace` `lTrim` `rTrim` | ✅ |
| `combine` `intersect` `difference` `disjunct` | ✅（参数写 `[a,b]` 或单值） |
| `group` `project` `select` `sack` `loops` `math` `index` `element` `key`(step) `value`(step) `properties`(step) | ❌ 需 label/副作用 |
| `conjoin` `product` `format` | ❌ 未做 |
| `dateAdd` `dateDiff` `dateFormat` | ❌ 未做 |

### 分支/循环/副作用

| step | 状态 |
|---|---|
| `union` `coalesce` `local` `optional` | ✅（子遍历参数） |
| `choose(pred, a, b)` | ✅ |
| `repeat(child).times(n)` | 🟡 只支持固定次数；`until`/`emit` 未做 |
| `branch`（多谓词）、`match`、`repeat.until/emit` | ❌ 需遍历器循环/标签状态 |
| `sideEffect` `inject`(中途) `aggregate` `store` `cap` `subgraph` `tree` `profile` `explain` | ❌ 无 side-effect 存储（`profile/*` 用 `/api/v1/metrics/{graph}`；`inject` 已支持） |
| `with` `withSideEffect` `withSack` `withStrategies` `withoutStrategies` | ❌ 不适用 |
| `barrier` `batch` | ✅（本项目写法） |
| `createdBetween` `nearest` `circle` `fuzzyLink` `pageRank` `louvain` `connectedComponents`/`cc` `sssp` `bfs` | ✅（本项目扩展） |

### 终端/写入

| step | 状态 |
|---|---|
| `toList` `toSet` `toBulkSet` `toIterator` `next` `iterate` `none()` `discard` `count` `size` | ✅ |
| `hasNext` `tryNext` `promise` `fill` `asAdmin` | ❌ Java API |
| `addV` `addE` `mergeV` `mergeE` `property` `drop` `io` `read` `write` `call` | ❌ 查询只读；写入走 HTTP 写接口 |

**一句话**：可读/可算的 step 基本齐了；剩下的是**step label / side-effect / 遍历器循环状态**那一类（`as/select/where('a',eq('b'))/match/project/sack/aggregate/cap/repeat.until` 等），要把执行模型换成带 `Traverser{value,path,sack,labels}` 的管线才能做对；再就是写入（按约定排除）与少数补丁型 step（`conjoin/product/format/date*`、Java 终端 API）。

---

## 77. 四跳脚本改成双向 `both('Payment').bothE('Payment')` + 性能对比

用户要求：四跳 path 查询的跳方向改成 `both('Payment').bothE('Payment')`（无向），重写脚本并做简单性能测试。

### 77.1 改写后的脚本（UI 示例同步）

```groovy
def sender = 1000
def receiver = 1006

def sPaths = g.V(sender, 'Account').both('Payment').bothE('Payment').path().toList()
def rPaths = g.V(receiver, 'Account').both('Payment').bothE('Payment').path().toList()

def common = sPaths.endIds().retainAll(rPaths.endIds())
def midObjects = g.V(common, 'Account').toList()

def four = sPaths.joinOnEnd(rPaths, midObjects)

def answer = []
answer = answer.addAll(sPaths)
answer = answer.addAll(rPaths)
answer = answer.addAll(four)
print('sender two-hop paths: ' + sPaths.size())
print('receiver two-hop paths: ' + rPaths.size())
print('common mids: ' + common)
print('middle objects: ' + midObjects.size())
print('four-hop paths: ' + four.size())
answer
```

与 §73 的唯一区别是两处 hop：`out('Payment').outE('Payment')` → `both('Payment').bothE('Payment')`。
`endIds()`（末元素是边取 `otherVertexId`）和 `joinOnEnd(..., midObjects)`（公共点填对象、成环丢弃）都不变，所以
"对方跳过来"的边也能参与：一条四跳现在是 `1000 → 135 → 1012 → 1005 → 1006`（135 是 hub，边可能逆向走）。

### 77.2 结果对比（sender=1000, receiver=1006）

| 量 | A `out.outE`（§73） | B `both.bothE`（本次） |
|---|---|---|
| sender 两跳 path | 33 | **528** |
| receiver 两跳 path | 9 | **118** |
| 公共中点 | 4 (`1008,1014,1020,1026`) | **51**（几乎整个 10.10/10.12 邻域 + hub 135） |
| 中点对象 | 4 | 51 |
| 四跳 path | 30 | **396** |
| answer 总 path | 72 | **1042** |

### 77.3 性能（`.logs/bench-path-both.js`，100 次交错 A/B，计数器取每次查询前后差值）

| 变体 | mean | median | p95 | p99 | max | kv.read | vertex.read | edge.read | edge.decode | 答案 | 响应 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| A `out(Payment).outE(Payment)` | 34.20 ms | 35.47 | 41.46 | 45.39 | 45.39 | 23 | 34 | 8 | 48 | 72 paths | 139.69 KB |
| **B `both(Payment).bothE(Payment)`** | **94.42 ms** | 92.76 | 110.58 | 204.58 | 204.58 | **59** | **220** | **32** | **285** | **1042 paths** | **1824.03 KB** |

**B vs A：延迟 +60.22 ms（+176%）、kv 读 +36、点读 +186、边读 +24、边解码 +237、响应 ≈13 倍。**

### 77.4 怎么读这个数字

这不是"改慢了"，是**工作负载本身大了**：`both` 的两跳邻域是无向的（sender 侧从 33 条变 528 条），
`bothE` 还要把中间点的**两个方向**的 Payment 边都读一遍，所以点读/边读/答案数同步放大；`edge.decode` 从 48 到 285
正对应多读出来的边。选哪个取决于业务：
* 只关心"钱从 sender 流出去"→ 用 A（有向，快 2.8 倍、答案小）；
* 关心"两个账户之间任意方向的资金往来"→ 用 B（完整，但要接受更大读放大，必要时加 `limit`/`createdBetween` 收窄）。

### 77.5 复现

```bash
node .logs/bench-path-both.js 100     # 上表（次数可改）
node .logs/verify-paths.js            # 仍是 A 变体的结果校验；B 的脚本在 UI 示例里
```

---

## 78. 四跳 path finding 性能分析 + 优化

对 §77 的 `both('Payment').bothE('Payment')` 四跳脚本做了逐段归因，找到并修掉了一处真正的引擎浪费。

### 78.1 归因（先用 §14.7 的规矩量，再动手）

`both` 两跳 path（`path().count()`）与**非 path** 等价查询对比：

| 查询 | kv.read | vertex.read | edge.read | edge.decode |
|---|---|---|---|---|
| `both('Payment').count()`（非 path） | 2 | 0 | 2 | 0 |
| `both('Payment').path().count()` | **10** | 8 | 2 | 12 |
| `both('Payment').bothE('Payment').count()`（非 path） | 4 | 0 | 26 | 0 |
| `both('Payment').bothE('Payment').path().count()` | **24** | 8 | 16 | 160 |

结论：path 模式的每一跳是**逐个 walk 调一次边 step**（`expandPathHop` 里 `for path: edgeStep.apply([last])`），
也就是**每个到达点一次存储读**；而普通管线把多个点合成一次批量读。两跳 `both` 因此 24 次存储往返，而不是 4 次。

### 78.2 优化一：path 跳改成批量读（`GremlinGraphQuery.expandPathHop`）

改成：收集所有 walk 的"落脚点" → 用 `GremlinContext.readAllEdgesBatchCached`（命名边）或
`readAllEdgeClassesBatched`（未命名边）**一次批量读**每个类的边（按 `batchSize=50` 分块、按输入顺序返回、带查询内缓存）
→ 把所有边拍平，用一次 `otherV()` 批量解析远端顶点（内部按 schema 分组批量读）→ 再按 walk 顺序拼回去。
未命名边那一支还补了 `ctx.recordEdges(...)`（批量读不自己记子图，否则画布会丢边）。

**实测（同一查询，改前 → 改后）**

| 指标 | 改前 | 改后 |
|---|---|---|
| 两跳 `both` path：kv.read | 24 | **6** |
| 两跳 `both` path：延迟 | 31.58 ms | **≈17 ms（min 12.6 / median 16.8）** |
| 完整脚本 B：kv.read | 59 | **23** |
| 完整脚本 A：kv.read | 23 | **15** |
| 结果 | s=528 r=118 mids=51 four=396 | **完全相同** |

### 78.3 优化二：`joinOnEnd` 按落点建索引（`GremlinScript`）

原来是 `for left: for right:` 的 **O(|L|×|R|)**（528×118 ≈ 6.2 万对）。改成先把右侧按"末端点 id"收进
`LinkedHashMap`，左侧每个只查自己那一组：**O(|L|+|R|+匹配数)**，且保持原来的 (left, right) 输出顺序。

### 78.4 优化后成本构成（`both` 四跳完整脚本）

```
traversals only (sPath + rPath)      32.09 ms
+ endIds/retainAll/g.V(common)/join  44.53 ms   → 脚本计算 ≈ 12 ms
+ 返回全部 1042 个 path（1.7 MB）     74.95 ms   → 序列化+传输 ≈ 30 ms
```

也就是说：批量读之后**瓶颈已经从存储往返转到"答案体积"**（1.7 MB / 1042 个带全属性的 path）。
这是 §77 那个脚本的设计（要求"所有 path 对象都放进 list 返回"）决定的，不是引擎浪费。要继续压，只能动答案形状：

| 答案形状 | 延迟 | 响应 |
|---|---|---|
| 全部（sPaths+rPaths+four） | 80.23 ms | 1728 KB |
| 只返回 `four`（396 条四跳） | 64.25 ms | 1016 KB |
| 只返回 id 列表 | 70.30 ms* | 33 KB |

\* id 列表那次因机器噪声偏高；**响应从 1.7 MB 降到 33 KB** 是确定的，序列化/传输那 ~30 ms 会基本消失。
另外可选的收窄手段（不需要改引擎）：`both('Payment').limit(k)` / `createdBetween(...)` 把两跳邻域框小。

### 78.5 复现

```bash
node .logs/bench-path-opt.js 20      # 78.1 + 78.4 的归因
node .logs/bench-path-both.js 100    # §77 的 A/B 交错基准（B kv 从 59 降到 23）
./mvnw -o -pl graph-query-gremlin test   # 242 用例（新增"一跳批量读 + 保持 walk 顺序"用例）
```

### 78.6 只返回 path 数目能省多少（`.logs/bench-path-count.js`，100 次交错）

同一段脚本、同一批遍历与 join，只改**答案形状**（全部 1042 个 path 对象 vs `four.size()` 一个数字）：

| 变体 | mean | median | p95 | p99 | max | kv.read | 响应 |
|---|---|---|---|---|---|---|---|
| ALL 返回全部 1042 个 path | 70.55 ms | 69.14 | 88.15 | 120.52 | 120.52 | 23 | 1728 KB |
| **COUNT 只返回 `four.size()`** | **37.77 ms** | **37.85** | **47.53** | **53.75** | 53.75 | 23 | **0.06 KB** |

**延迟 −32.78 ms（mean，−46.5%）／−31.29 ms（median，−45.3%）；p95 −40.6 ms、p99 −66.8 ms；响应从 1.73 MB 降到 60 B。**
存储计数两边完全一致（kv 23 / 点读 220 / 边读 32 / 边解码 285）——遍历与 join 的工作量没变，省的正是那 1.7 MB 的
序列化 + 传输 + 客户端解析，以及它带来的 GC 抖动（p99 从 120 ms 掉到 54 ms）。

## 79. 四跳 path 两个口径：不看方向 vs 看交易方向（一次调用返回两个结果）

需求：在 §77/§78 那条 `both` 四跳脚本的基础上，同时给出
**(1) 不看方向的四跳 path** 与 **(2) 看交易方向的四跳 path**，一个脚本一次调用返回两个结果，并给出性能指标。

### 79.1 两个口径各怎么拼

| 口径 | sender 半边 | receiver 半边 | 拼出来的 walk |
|---|---|---|---|
| 不看方向 | `both('Payment').bothE('Payment')` | `both('Payment').bothE('Payment')` | 不区分谁是付款方 |
| 看交易方向 | `out('Payment').outE('Payment')` | `in('Payment').inE('Payment')` | 每一跳都 payer→payee |

关键在 `joinOnEnd` 右侧会被**反转**拼上：sender 半边是 `[sender, e1, v1, e2]`（e2 的远端是 M），
receiver 半边是 `[receiver, e3, v3, e4]`（e3: v3→receiver，e4: M→v3），反转后接在中间点后面，
得到 `sender -e1-> v1 -e2-> M -e4-> v3 -e3-> receiver` —— **e1..e4 全部沿付款方向**（e4 是 M→v3、e3 是 v3→receiver）。
`in/inE` 走的是 `EdgeSide.IN`，`readPathEdges` 一样批量读，所以有向那半同样吃到 §78 的批量跳读优化。

### 79.2 数据说了什么（结论本身是答案的一部分）

**1000 → 1006 之间不存在有向四跳。** 有向四跳必然在中间点分成 2+2，于是要求 `out²(1000) ∩ in²(1006) ≠ ∅`：

```
out²(1000) = {1002,1008,1014,1020,1026}
in²(1006)  = {135,1004,1048,1042,1036,1030}     交集 = ∅
```

但两人之间有**有向 8 跳**：`out⁴(1000) ∩ in⁴(1006) = {1002,1004,1010,1016,1022,1028,1034,1040,1046}`（非空）。
即 1000/1006 只能靠不看方向的路（经共享对手方 135 之类）或更长的有向链相连。演示时另取 **1000 → 1022**（有向四跳非空）。

**正确性对照**：有向 join 得到的四跳数，与直接 `out⁴` 落在 receiver 的 walk 数逐字相等 ——
`1000→1006: 0 vs 0`，`1000→1022: 74 vs 74`。

### 79.3 脚本（`.logs/scripts/four-hop-two-ways.groovy`，也加进了 UI 示例）

```groovy
def sender = 1000
def receiver = 1006

// (1) 不看方向
def sAny = g.V(sender, 'Account').both('Payment').bothE('Payment').path().toList()
def rAny = g.V(receiver, 'Account').both('Payment').bothE('Payment').path().toList()
def anyMids = sAny.endIds().retainAll(rAny.endIds())
def anyObjs = []
if (anyMids.size() > 0) { anyObjs = g.V(anyMids, 'Account').toList() }
def anyFour = sAny.joinOnEnd(rAny, anyObjs)

// (2) 看交易方向：付款方正着走、收款方倒着走
def sOut = g.V(sender, 'Account').out('Payment').outE('Payment').path().toList()
def rIn = g.V(receiver, 'Account').in('Payment').inE('Payment').path().toList()
def dirMids = sOut.endIds().retainAll(rIn.endIds())
def dirObjs = []
if (dirMids.size() > 0) { dirObjs = g.V(dirMids, 'Account').toList() }
def dirFour = sOut.joinOnEnd(rIn, dirObjs)

print('direction ignored, four-hop: ' + anyFour.size())
print('payer -> payee, four-hop:    ' + dirFour.size())
[anyFour, dirFour]        // 两个结果，一次返回
```

`if (...size() > 0)` 那个守卫是必要的：`g.V(空列表,'Account')` 会抛
"The script variable 'mids' is empty, so there is no id to walk from"（§71 的变量代入语义），
而有向那半在 1000→1006 上正好为空。

### 79.4 测量（40 轮**交错**，每轮跑全部 6 个变体；`.logs/bench-path-two-ways.js`）

**Pair 1：1000 → 1006（不看方向有、有向为空）**

| 变体 | mean | median | p95 | p99 | kv | 点读 | 边读 | 边解码 | 答案 |
|---|---|---|---|---|---|---|---|---|---|
| OLD 两侧两跳半程 + 四跳（1042 条） | 70.9 | 71.5 | 94.3 | 100.2 | 23 | 220 | 32 | 285 | 1728 KB |
| U 只看方向（返回四跳） | 63.7 | 60.6 | 91.2 | 163.3 | 23 | 220 | 32 | 285 | 1016 KB |
| U-COUNT 只看方向（只返回数） | 42.2 | 40.5 | 57.4 | 72.5 | 23 | 220 | 32 | 285 | 0.1 KB |
| D 只看交易方向（返回四跳） | 26.7 | 28.8 | 34.6 | 40.6 | **10** | 28 | 9 | 52 | 0.1 KB |
| **BOTH 两个结果（脚本）** | **73.3** | **73.5** | **92.4** | **93.3** | **33** | 248 | 41 | 337 | 1016 KB |
| BOTH-COUNT 两个数 | 53.5 | 56.0 | 69.2 | 70.3 | 33 | 248 | 41 | 337 | 0.1 KB |

**Pair 2：1000 → 1022（两个口径都非空：不看方向 553 条、有向 74 条）**

| 变体 | mean | median | p95 | p99 | kv | 点读 | 边读 | 边解码 | 答案 |
|---|---|---|---|---|---|---|---|---|---|
| OLD（1199 条） | 61.5 | 61.5 | 81.3 | 87.3 | 23 | 220 | 32 | 285 | 2133 KB |
| U（553 条） | 52.1 | 51.9 | 64.7 | 66.1 | 23 | 220 | 32 | 285 | 1421 KB |
| U-COUNT | 29.5 | 29.2 | 37.3 | 44.0 | 23 | 220 | 32 | 285 | 0.1 KB |
| D（74 条） | 24.6 | 25.0 | 31.3 | 32.8 | 15 | 36 | 9 | 52 | 191 KB |
| **BOTH** | **65.9** | **66.7** | **77.9** | **83.5** | **38** | 256 | 41 | 337 | 1611 KB |
| BOTH-COUNT | 40.2 | 40.4 | 54.9 | 55.9 | 38 | 256 | 41 | 337 | 0.1 KB |

### 79.5 怎么读这组数

* **存储计数正好相加**：U 的 kv 23 + D 的 kv 10 = BOTH 的 kv 33（pair 1）；23 + 15 = 38（pair 2）。
  两个口径是**四条独立的 path 遍历**，没有共享——`bothE` 读出来的边 `side` 是 BOTH，
  丢掉了方向信息，所以不能"算一次不看方向的、再筛出有向的"。
* **有向那半很便宜**：D 的 kv 只有 10/15（`out`/`in` 的扇出远小于 `both`），
  在 BOTH 里只多花约 13–15 ms（`BOTH - max(U,D)`）。
* **答案体积仍是主要成本**：BOTH-COUNT 比 BOTH 少 17.5–26.3 ms（−24%～−39%），省的就是那 1.0–1.6 MB 的序列化与传输。
* **一次调用拿两个结果更划算**：把 U 和 D 分两次 HTTP 调用合计 89.5/76.8 ms，BOTH 一次 73.5/66.7 ms，
  省 ~10–16 ms（省的是每请求的固定开销），且两个结果出自同一份快照。
* **和旧脚本基本同价**：BOTH 比 OLD 只差 +2.0 / +5.2 ms——多算了一个口径，但少返回了 646 条两跳半程（1.7–2.1 MB → 1.0–1.6 MB）。
* 与 §78.6 一致：真正的大头是**答案形状**，不是遍历。

### 79.6 复现

```bash
node .logs/scripts/run-two-ways.sh          # 起服务 + 两个 pair 的 40 轮交错基准（一条命令，见 79.7 的原因）
node .logs/bench-path-two-ways.js 40 1000 1006
node .logs/bench-path-two-ways.js 40 1000 1022
node .logs/run-script.js .logs/scripts/four-hop-two-ways.groovy   # 只看脚本输出
```

### 79.7 环境提示（这次测量踩到的两个坑）

1. **服务会在工具调用之间被外部回收**：JVMs（8081/8080/8083）会在两次工具调用之间整批消失，
   `nohup` 也留不住，日志里没有 shutdown 记录、也没有新的 `hs_err`。
   所以**起服务与跑基准必须在同一次调用里完成**，否则会拿到 ECONNREFUSED。
   基准脚本已改成"崩了就打印已收集的部分"，不会整轮作废。
2. **`.rocksdb/sanguo-kg-text` 会被打断的 Sanguo 测试写坏**：遗留的
   `mvn -pl graph-integration-test test -Dtest=SanguoRocksDbEdgeTest,SanguoTextGraphTest -Dsanguo.queries.write=true`
   被杀在写一半时，`MANIFEST-000018` 缺失，graph-http 启动时抛
   `RocksDBException: Failed to open NewSequentialFile.rocksdb\sanguo-kg-text/MANIFEST-...`，**8081 起不来**。
   等那次测试自己跑完会重建 store；否则删掉 `.rocksdb/sanguo-kg-text` 也能让服务起来（只是那个图变空）。
   **别在 `-Dsanguo.queries.write=true` 的测试跑到一半时杀它。**

## 80. 只用 both 两跳：把有向那一半从同一批 walk 里筛出来，以及 both→out/in 的缓存

§79 用了四条 path 遍历（both×2 + out/in×2）。两条更正：

* `both('Payment').bothE('Payment')` **已经包含**了 out 两跳和 in 两跳——只不过 `bothE` 的每一条边
  都带着**它被读到时的那一侧**（`sides()` 对 `BOTH` 请求返回 `OUT, IN` 两类，`toResult(...)` 逐条打上 side）；
* 所以有向那一半**不需要再查一次**，只要按边上的 side 把 out 半和 in 半**筛**出来。

### 80.1 暴露边的方向：`side` / `direction`

`GremlinElementExpression` 新增伪键 `SIDE_KEY = "@side"`，`resolveKey` 把 `side` / `direction`（含 `$` 前缀）
解析过去，`read` 返回 **小写**的 `out` / `in` / `both`（与 step 名一致）。存了同名属性则属性优先（与 `value(...)` 的既有规则一致）。

两个入口都通了：

| 入口 | 写法 | 说明 |
|---|---|---|
| 遍历 | `values('side')`、`filter(side.equals('out'))` | 表达式里裸写 `side` 也会解析到伪键，**不进 pushdown**（不能被当成存储属性条件推下去，否则静默返回空） |
| 脚本 | `p.get(1).value('side')` | `GremlinScript` 的 `value`/`values` 改为走 `GremlinElementExpression.value(...)`，与遍历同一套解析 |

脚本层还补了布尔运算：`&&`、`||`（短路），在比较之前结合，`||` 优先级低于 `&&`——
`p.get(1).value('side') == 'out' && p.get(3).value('side') == 'out'` 这种写法才成立（之前只支持 `!`）。

### 80.2 改写后的脚本（两条遍历，两个结果）

```groovy
def sAny = g.V(sender, 'Account').both('Payment').bothE('Payment').path().toList()
def rAny = g.V(receiver, 'Account').both('Payment').bothE('Payment').path().toList()
def anyFour = sAny.joinOnEnd(rAny, anyObjs)                    // 全部 walk

def sOut = sAny.findAll { p -> p.get(1).value('side') == 'out' && p.get(3).value('side') == 'out' }
def rIn  = rAny.findAll { p -> p.get(1).value('side') == 'in'  && p.get(3).value('side') == 'in'  }
def dirFour = sOut.joinOnEnd(rIn, dirObjs)                     // 同一批 walk，筛出来的有向半程
[anyFour, dirFour]
```

**筛选与真实遍历逐字一致**（`p.get(1)`/`p.get(3)` 是四元素 walk 的两条边）：

```
sizes: undirected=396 directed-from-filter=0  directed-from-traversal=0   out-half=33/33 in-half=12/12
sizes: undirected=553 directed-from-filter=74 directed-from-traversal=74  out-half=33/33 in-half=12/12
```

### 80.3 实测（30 轮交错；`.logs/bench-both-only.js`，`.logs/both-only-results.txt`）

**Pair 1 · 1000 → 1006（不看方向 396，有向 0）**

| 变体 | mean | median | p95 | p99 | kv | 点读 | 边读 | 边解码 | 答案 |
|---|---|---|---|---|---|---|---|---|---|
| **BOTH-ONLY（2 条遍历）** | **40.4** | **39.0** | 52.3 | 52.9 | **23** | 220 | 32 | 285 | 1016 KB |
| BOTH+OUT/IN（4 条遍历，§79 的写法） | 48.3 | 50.0 | 58.4 | 61.1 | **33** | 248 | 41 | 337 | 1016 KB |
| OUT/IN-ONLY（2 条遍历，只有有向） | 18.5 | 17.1 | 28.2 | 29.0 | 10 | 28 | 9 | 52 | 0.1 KB |
| BOTH-ONLY-COUNT | 26.0 | 25.2 | 33.6 | 42.2 | 23 | — | — | — | 0.1 KB |
| BOTH+OUT/IN-COUNT | 36.7 | 37.1 | 44.8 | 49.9 | 33 | — | — | — | 0.1 KB |

**Pair 2 · 1000 → 1022（不看方向 553，有向 74）**

| 变体 | mean | median | p95 | p99 | kv | 点读 | 边读 | 边解码 | 答案 |
|---|---|---|---|---|---|---|---|---|---|
| **BOTH-ONLY（2 条遍历）** | **46.8** | **45.0** | 60.3 | 64.3 | **28** | 228 | 32 | 285 | 1611 KB |
| BOTH+OUT/IN（4 条遍历） | 53.0 | 51.7 | 67.5 | 72.5 | **38** | 256 | 41 | 337 | 1611 KB |
| OUT/IN-ONLY | 20.1 | 18.8 | 29.3 | 29.8 | 15 | 36 | 9 | 52 | 191 KB |
| BOTH-ONLY-COUNT | 28.8 | 27.1 | 40.8 | 41.6 | 28 | — | — | — | 0.1 KB |
| BOTH+OUT/IN-COUNT | 35.8 | 34.7 | 42.6 | 79.4 | 38 | — | — | — | 0.1 KB |

**读次数**：BOTH-ONLY 的 kv 是 23 / 28，而 BOTH+OUT/IN 是 33 / 38 —— 正好多出 out/in 那两条遍历的
10 / 15（`edge.read` 与 `edge.decode` 也是**逐字相加**：32+9=41、285+52=337）。延迟差 **11.0 / 6.7 ms**。

### 80.4 缓存验证：both 之后走 out/in，会命中吗？

**在同一**条**遍历里**：会（本次修好了）。`EdgeCacheKey` 原来是 `(vertexId, edgeName, direction)`，
`both` 与 `out` 是两个键，所以会**重新读**。现在 `GremlinContext` 增加
`fromTheOtherDirection(...)`：一条 `BOTH` 缓存能回答 `OUT`/`IN`（按 `side` 筛），两条 `OUT`+`IN` 缓存能合成 `BOTH`
（**undirected/bidirectional schema 的每条边 side 都是 `BOTH`，各方向答案相同，不能拼接，要特判**；
有时间窗或读上限时不做复用，那种读由存储按方向决定返回多少）。两个读入口
（`readEdgesCached` 单点、`readAllEdgesBatchCached` 批量）都接上了。

实测（`.logs/cache-proof.js`，同一个顶点 1000 的 Payment 边）：

| 一条遍历 | kv | 边读调用 | 解码边数 |
|---|---|---|---|
| `bothE` | 2 | 2 | 12 |
| `outE` | 1 | 1 | 3 |
| **`union(bothE, outE)`** | **3** | **2** | **12** |
| `union(bothE, bothE)`（必然命中，对照） | 3 | 2 | 12 |
| `union(outE, bothE)`（out 在前，无法复用） | **4** | **3** | **15** |
| `union(outE, inE)`（out+in 是两次读） | 3 | 2 | 12 |

`union(bothE, outE)` 与 `union(bothE, bothE)` 完全相同（3/2/12），而 `union(outE, bothE)` 是
`bothE + outE` 叠加（4/3/15）—— 修复前 `union(bothE, outE)` 就是后者那个数。

**在脚本的不同语句之间：不会，也不该指望。** `GremlinQueryService` 里每一句脚本走的是
`engine::executeWithSubgraph`，而它每次都 `newContext()`：**每条语句一个全新的 GremlinContext**，
边缓存、点缓存、子图都不共享。所以 §79 那种"both 语句 + out/in 语句"的写法，
即使缓存按方向复用了也仍然要重读——**这正是 §80.2 用筛选改写（一条语句解决）的根本原因**。
若要让脚本的语句共享读，需要给 `GremlinGraphQuery` 一个 session（一个 context 串起多条遍历，
并在每条语句前把 `createdBetween` 留下的时间窗/读上限复位），这是下一步可选项。

### 80.5 复现

```bash
node .logs/cache-proof.js                     # 80.4 的缓存对照
bash .logs/scripts/run-two-ways.sh 30         # 起服务 + 两个 pair 的 BOTH-ONLY/BOTH+OUT-IN 交错基准
node .logs/run-script.js .logs/scripts/four-hop-two-ways.groovy
./mvnw -o -pl graph-query-gremlin test        # 245 用例（新增 side/&&/||/缓存复用各一）
```

## 81. 脚本与遍历的"编译一次"：现在有指标可查

### 81.1 改了什么

| 层 | 之前 | 现在 |
|---|---|---|
| 遍历（`g.V(...)...`） | `compiledCache` 按**整段文本**缓存（上限 `MAX_COMPILED_QUERIES=512`，满了整体清空），已经只编译一次 | 不变；新增 `traversalCompiles` / `traversalCompileNanos` 计数，`compiled(text)` 统一入口（超限清空也从 `execute` 挪进来，两条入口都受保护） |
| 脚本（`def ...\nprint(...)`） | HTTP 每来一次请求就 `GremlinScript.parse(...)` 一次（**没有缓存**） | 新增 `GremlinGraphQuery.script(text)`：按整段脚本文本缓存解析结果（同一个 512 上限），返回 `ScriptPlan{script, parsed, parseNanos}` |

调用链：`QueryService` 现在 `engine.script(substituted)` → 记录前后 `engine.compileStats()` 差值 → 响应里带上
`ScriptResponse.compile{parseMillis, parsedNow, traversalsCompiled, compileMillis}`，**每次调用都能看到这次的准备成本**。

### 81.2 实测（`.logs/bench-script-compile.js`，sender 1000 → receiver 1022）

| | parse | 编译的遍历数 | compile |
|---|---|---|---|
| 第 1 次（这个脚本文本对这个引擎是新的） | **3.165 ms**（冷 JVM：首次解析 + 类加载/JIT） | **4**（脚本里有 4 段遍历） | **4.000 ms** |
| 第 2..30 次（同一段文本） | **0.000 ms** | **0**（最大也是 0） | **0.000 ms** |
| 对照：20 次**每次换一段文本**（引擎没见过） | **0.187 ms** 均值（0.117–0.276） | 0（遍历文本相同，仍命中） | 0.000 ms |

结论：**是编译/解析一次，后续不再做**。脚本缓存省下的就是对照那一列：**~0.19 ms/次 ≈ 一次调用的 0.23%**
（`.logs/pair2-results.txt` 的 "preparation as a share of a call: cached 0.000%, uncached text 0.226%"）。
和遍历缓存同一个取舍：**把 id 写进脚本文本，每个文本就是一次新的解析**（所以仪表盘按 id 拼脚本时，缓存等于没有）。

### 81.3 复现

```bash
node .logs/bench-script-compile.js 30 1000 1022    # 需要一个刚启动、还没跑过这段脚本的 JVM
./mvnw -o -pl graph-query-gremlin,graph-http test  # 新增 GremlinCompiledQueryCacheTest#aScriptIsParsedOnceAndItsTraversalsCompiledOnce
```

## 82. 脚本能力边界：不是任意 Groovy，是有意的子集（附探针证据）

`.logs/probe-script-support.js` 把 68 个 Groovy 片段真跑一遍（`.logs/script-coverage.txt`），**44 个接受 / 24 个拒绝**。
"拒绝"不是猜的：下面每条都是引擎自己吐的消息。

### 82.1 接受（查询脚本需要的部分）

* 语句：`def`、赋值/重新赋值、`return`、`print`/`println`、`//` `/* */` 注释、分号或多行分隔、空行
* 控制流：`if` / `else if` / `else`、`for (x in 集合)`、`while`、`break` / `continue`、嵌套块
* 表达式：`+ - * / %`、`!`、`&&` `||`（本次新加）、比较 `== != < <= > >=`、整数与**小数**（本次修好 `1.5`）、字符串拼接
* 集合：列表字面量（含空表、嵌套）、`size() count() toList() toSet() isEmpty() get(i) first() last() start() end() reverse() contains() indexOf() add() addAll() retainAll() intersect() endIds() joinOnEnd()`
* 闭包：`collect {} findAll {} find {} any {} every {} count {} each {} eachWithIndex { x, i -> }`，**闭包体可以多行**；闭包里用 `it.value('k')/id()/label()`（含本次新增的 `it.value('side')`）
* 遍历：`g.V(变量, 'Schema')`、链式 step、把遍历结果当 id 列表再喂给 `g.V(...)`、`toList()` 之后当普通列表用

### 82.2 拒绝（Groovy 语言本身的大半）

| 类别 | 例子 | 引擎的回答 |
|---|---|---|
| 列表聚合方法 | `xs.sum()` `xs.max()` `xs.min()` `xs.sort()` | `Unknown script method '.sum'. Supported: size(), count(), ...`（**可用 `g.inject(xs).sum()` 走 step**，或 `count { }` 这类闭包形式） |
| 字符串方法 | `'abc'.toUpperCase()` `.length()` `.substring()` `.replace()` `.split()` | 同上（`'abc'.size()` 可以；大小写/子串用遍历里的 `toUpper/substring/...` step） |
| 类型转换方法 | `s.asNumber()` | 同上（`asNumber` 是 step，不是值方法） |
| Map 字面量 | `[a: 1, b: 2]` | `Cannot read the script value 'a: 1'. A value is a traversal (g.V(...)), a variable, a list, a string, a number, true/false or null` |
| Range | `1..3` | `Expected '(' after '..3'` |
| 三元 / elvis | `x > 0 ? 'a' : 'b'`、`x ?: 'd'` | `Cannot read the script value ...`（用 `if/else` 代替） |
| 复合赋值 / 自增 | `x += 1`、`x++` | 同上（写 `x = x + 1`） |
| GString | `"value=${x}"` | **`Missing value for query parameter 'x'`** —— `${...}` 在更早的 HTTP 参数替换那一步就被吃掉了，**永远到不了脚本解析器** |
| 异常处理 | `try { } catch (e) { }` | `Cannot read the script value 'try { ... }'` |
| 分支语句 | `switch (1) { case 1: ... }` | 同上（用 `if/else if`） |
| 类型声明 | `class Foo { }`、`import ...`、`def f(x) { }` | 同上（**没有函数定义**；重复逻辑用闭包 + 集合方法表达） |
| 一等闭包 | `def f = { x -> x*2 }` 然后 `f(3)` | 同上（闭包只能当参数传给上面列的集合方法） |
| 断言 / 新对象 / 类型判断 / 正则 | `assert x`、`new Date()`、`x instanceof Number`、`'abc' =~ 'b'` | 同上；**`=~` 尤其危险：它不报错，被当成字符串字面量**（实测返回 `abc' =~ 'b`），属于"静默给错"，不要在脚本里用 |

### 82.3 两个已知的毛刺（写脚本时会踩）

1. **遍历结果是 List，不是标量**：`g.V(...).count() + g.V(...).count()` 得到的是**文本** `[3][9]`（`+` 只在两侧都是数字时相加，
   否则拼字符串）。要算术就先取标量：`g.V(...).count().get(0) + g.V(...).count().get(0)`，或 `first()` / `start()`。
2. **`=~` 静默变成字符串**（见上表）。凡是引擎不认识的写法，**多数**会明确报错并列出可用的方法，但这个是个例外。

### 82.4 结论

**不支持任意 Groovy，也不打算支持**：引擎里没有 Groovy（没有 `GroovyShell`、没有 `GroovyClassLoader`），
脚本模式是**自己解析器上的"Groovy 形状的子集"**。取舍写在这里：脚本要能表达"多段遍历 + 变量 + 条件 + 循环 + 闭包筛选"，
查询就够用了；反过来，类/导入/函数/异常/线程/IO 这类东西既不是查询需要的，也会把执行面放大（任意代码执行 + 新依赖）。
**读不出来的写法会带消息拒绝**（82.2 里 24 条都能看到引擎的原话），除了 82.3 的两个例外。

## 83. 大点（Aerospike-Edge 三层）的演示数据、测试与 UI 查询

需求：按"大点方案"（冷/近线/在线三层 + 大点判定 maxInDegree/maxOutDegree + 读必须带时间窗）造测试用例，
生成**多个大点**写进 UI 那个图实例，并给出能在 UI 直接跑的 Gremlin 语句。

**实现早就在**（`EdgeTiering` + `AerospikeEdgeChunkStore`（冷）+ `AerospikeNearlineStore`/`AerospikeVolumeStore`（近线）
+ `AerospikeEdgeStore` 的在线邻接表 + `AerospikeEdgeOfflineJob`），这次的产出是**数据、用例、查询和两个真 bug 的修复**。

### 83.1 五个大点，一个口径一个点

`AerospikeBigVertexDemoLoader`（`graph-integration-test`）把 5 个大点写进 **`payment-graph-as-edge` 的图实例**
（vertex-set `payment_as_edge_v1`；注：用户说的 `payment-graph-aerospike-edge` 在本仓库里的实际名字是
`payment-graph-as-edge`）。时间锚点都是"装载的那一刻"：

```
cold     (-inf, now-25h]      离线聚合表 Edge_Out / Edge_In（AerospikeEdgeBulkImporter，6000 条/点）
nearline (now-23h, now-1h]    近线 KV（TTL 7 天，按 volume 预划分：声明月量 1e8 → 834 个 KV/天）
online   [now-10m, now]       点记录里的内联邻接表（5 条，schema 上限 in/out 各 1000）
```

| 大点 | 方向 | 有哪几层 | 演示的读 |
|---|---|---|---|
| `9000002` | in | 冷 + 近线 + 在线 | 三种窗口：只在线 / 近线+在线 / 冷+近线+在线 |
| `9000003` | in | 近线 + 在线 | 还没有冷数据的大点 |
| `9000004` | in | 冷 | 安静的大点：全部早于近线窗口 |
| `9000005` | out | 冷 + 近线 + 在线 | 出度方向同样分层（一个大付款方） |
| `9000006` | in | 在线（5 条） | 未超过出/入度上限：**不需要时间窗** |

两个 id 故意写两次，用来演示"同一 id 取最新副本"：`bv_all_c_0`（冷 + 近线，近线更新，状态 REFUNDED）、
`bv_all_n_0`（近线 + 在线，在线更新）。写入口：`AerospikeBigVertexDemoTest`
（`-Daerospike.bigVertex.demo=true`，加 `-Daerospike.bigVertex.reset=true` 先 truncate demo 自己的 set）：

```bash
./mvnw -pl graph-integration-test -am test -Dtest=AerospikeBigVertexDemoTest \
       -Daerospike.bigVertex.demo=true -Daerospike.bigVertex.reset=true -Dsurefire.failIfNoSpecifiedTests=false
```

### 83.2 六个读场景的用例（`.logs/probe`→`AerospikeEdgeBigVertexIntegrationTest`，11 个用例全绿）

测试写进自己的 vertex-set（默认 `payment_as_edge_bv_it`，先 truncate），所以**计数是精确的**：

```
loaded the big-vertex demo into test/payment_as_edge_bv_it in 3100 ms: 15 vertices,
  {9000002=6907, 9000003=905, 9000004=6000, 9000005=6905, 9000006=5}
  all-three window -> 6905 edges (cold=6000, nearline=900+1, online=5)
  near-line + online window -> 906 edges
  online window -> 6 edges
  cold-only window -> 495 edges
  quiet hub -> 6000 edges, 0 in the last day;  near-line-only hub -> 905
  outgoing side -> 6905 over all three tiers
```

对应到方案的六条：不带窗口 → `QueryException`（"…needs a creation-time window…"）；窗口只落在在线 →
只读内联；落在最近一天 → 近线+在线合并；落在冷区间 → 只读聚合表；跨三段 → 三段合并；同一 id 出现在多层 →
**按 id 合并取最新**（`lastUpdatedTime` 大的赢，冷窗口单独看仍是冷的旧副本）。另有两条边界：未超上限的点/window 可省，
近线一天按 volume 切成 834 个 KV（`ceil(1e8/30/4000)`，正是 spec 里"1亿/30/4000…约 1000"的算法）。

### 83.3 UI 上直接跑的 11 条（`payment-graph-as-edge`，已实测）

`app.js` 的 `payment-graph-as-edge` 例子列表里新增 11 条（中英双语）；窗口用**相对时间**（见 §84.1），
所以语句不用改，「最近 10 分钟」永远是最近 10 分钟。实测输出（`.logs/verify-big-vertex-ui.js`，服务 8081）：

| # | 查询 | 结果 |
|---|---|---|
| ① | `g.V('9000002','Account').inE('Payment')` | 被拒：`…has 6000 edges … needs a creation-time window…` |
| ② | `.createdBetween('-10m','*').inE('Payment').values('id','status')` | 5 条（含 `bv_all_n_0`=REFUNDED，即在线更新的副本） |
| ③ | `.createdBetween('-23h','*').inE('Payment').values('id')` | **906**（近线 901 + 在线 6 − 1 合并） |
| ④ | `.createdBetween('-360d','-330d').inE('Payment').limit(20)` | 冷区间 30 天（每页 20 条） |
| ⑤ | `.createdBetween('-400d','*').inE('Payment').limit(10).values('id','status')` | 三段合并 |
| ⑥ | 同上的 `.count()` | **6905** |
| ⑦ | `g.V('9000003',…)` 同上 | **904**（无冷数据） |
| ⑧ | `g.V('9000004',…'-400d','*')….count()` | **6000**（近线窗口内 0 条） |
| ⑨ | `g.V('9000005',…).outE('Payment')` | 出度方向三段合并 |
| ⑩ | `g.V('9000006','Account').inE('Payment').values('id')` | 5 条，**不需要窗口** |
| ⑪ | `.groupCount('status')` | `{PENDING:300, FAILED:300, SUCCESS:299, REFUNDED:2, NEW:5}` = 906 ✓ |

（同时把原有的 `9000001` 大点演示重新装载回同一个 set：`AerospikeEdgeLiveLoadTest -Daerospike.asEdge.set=payment_as_edge_v1`。）

### 83.4 复现

```bash
# 数据（写进 UI 的图实例；reset 会先清掉 demo 自己的 set）
./mvnw -pl graph-integration-test -am test -Dtest=AerospikeBigVertexDemoTest \
       -Daerospike.bigVertex.demo=true -Daerospike.bigVertex.reset=true -Dsurefire.failIfNoSpecifiedTests=false
# 用例（自己的 set，先 truncate，计数精确）
./mvnw -pl graph-integration-test -am test -Dtest=AerospikeEdgeBigVertexIntegrationTest
# UI 逐条验证
node .logs/verify-big-vertex-ui.js
```

## 84. 这次修掉的两个真 bug（都是"大点 + 时间窗"踩出来的）

### 84.1 相对时间窗：`createdBetween('-2h','*')`

`TimeRange.parse` 原来只认 epoch millis / UTC 日期；大点的窗口几乎都是"最近 N 小时"，写死日期在 UI 例子里第二天就失效。
新增 `TimeRange.relativeToNow(value, nowMillis)`：`-30m` `-2h` `-25h` `-7d` `-2w` `-90s` `-500ms`（也接受 **只有一个 `-` 但没有单位**之类就不是相对值，原样去按日期解析）。

关键点：**在 step 执行时解析，不在编译时**。`GremlinStepRegistry` 的 `createdBetween` 原来在编译期就把 range 解析好，
而编译结果是缓存的（§81），`-2h` 会被冻在第一次编译那一刻；现在把原始文本留到 `apply` 时用当时的时钟解析。
`TimeRange.parse(from,to,now)` 也提供了显式时钟的重载，测试可以给固定时钟。

### 84.2 近线读：开区间窗口会**死循环**，且会漏掉最后一个自然日

`AerospikeNearlineStore.readWindow` 是按天循环读当天的 KV：

```java
for (long day = range.fromTime(); day <= range.toTime(); day += dayMillis)   // 旧代码
```

两个问题，都是这次大点用例才暴露出来的：

1. **`toTime = Long.MAX_VALUE`**（`createdBetween(from,'*')`）→ 循环要跑 ~10^14 次，查询**永不返回**（实测挂死 >25 分钟）。
2. 即使有界，循环从 `fromTime` 起跳 24h，**日键是自然日**：窗口 `[now-23h, now]` 只读了 `dayOf(now-23h)` 那一天，
   `dayOf(now)`（今天，装着最新的一批近线边）被跳过了 —— 902 条里只读到 655 条。

修法：把窗口按 TTL 截断（近线最多留 7 天，不可能更多），`toTime` 开区间时取 now，并把起点**对齐到 UTC 零点**再按天步进：

```java
long to = range.toTime() == Long.MAX_VALUE ? now : range.toTime();
long from = range.fromTime() == Long.MIN_VALUE ? to - dayMillis : range.fromTime();
long earliestKept = Math.max(from, to - AerospikeNearline.TTL_SECONDS * 1000L - dayMillis);
long firstDay = earliestKept - Math.floorMod(earliestKept, dayMillis);
for (long day = firstDay; day <= to; day += dayMillis) { ... }
```

修完：近线窗口 901 条（含被更新的 `bv_all_c_0`）、开区间窗口不再挂、跨自然日的窗口能把两天都读到。
`AerospikeEdgeBigVertexIntegrationTest` 里 ②③⑤⑥⑦ 这几个用例正是钉住这两点的。

## 85. RocksDB-Edge 的按属性查找：分布式（MapReduce）执行

需求：把 `g.V('Account').has('accountStatus','FROZEN')` 这类**按属性的查找**从"单进程全表扫描"扩展成可扩展的分布式执行——
① 用基本 MapReduce 划分数据，每个 mapper 内做属性查找与后续 `outE` 等遍历；② mapper 内可多线程；③ reducer 收到对各 mapper 输出的迭代器
（纯分布式计算时结果只留在各 mapper 输出）；④ Gremlin 里加一个 `g.V(DIST)` 参数在分布式/单机之间切换。

### 85.1 为什么这里能切分：hash 前缀

RocksDB-Edge 的 key 一律是 `hash(id) : id`（`RocksDbHash`：**定长 8 字节大端 FNV-1a**），所以
**一个 hash 区间就是一个连续 key 区间**，一个分片就是一次 `RocksDbHandle.iterator(cf, from, to)` 的范围扫描：

* 点表 `vertex:<Type>`、边表 `edge:<Class>` 都是 hash 前缀打头（`RocksDbKeys.vertexKey` / `edgeKey`）→ 同一个切分对点和边都成立；
* `RocksDbHash.partitionBoundary(p, n) = floor(p * 2^64 / n)`（用 `BigInteger` 算 128 位，取低 64 位当无符号值），
  `partitionKeyRange(p, n)` 把最后一个分片的上界设成 **开区间**（否则 `2^64` 折回 0）；
* 分片数取 `RocksDbStoreConfig.regionCount()`——它本来就是"把 key 空间切成几块物理分离的区域"的那个数（`regionOf = hash % regionCount`）。

新增的存储接口（`GraphQuery` 的默认方法，老 store 不受影响）：
`supportsPartitionedScan()`、`scanPartitionCount()`、`scanVerticesPartition(...)`、`scanEdgesPartition(...)`；
`RocksDbVertexTable.forEachRange(...)` 与 `RocksDbEdgeStore.forEachEdgeRange(...)` 是范围扫描的实现
（`scanEdges` 现在就是"全范围 + 收集"，两者共用同一段解码逻辑）。

### 85.2 引擎侧：`g.V(DIST)` / `g.E(DIST)`

语法（`GremlinQuery`）：`DIST`（或 `DISTRIBUTED`，大小写不限）作为**第一个参数**：

```groovy
g.V(DIST,'Account').has('accountStatus','FROZEN')     // 一个类型的分布式扫描
g.V(DIST).has('Account','accountStatus','FROZEN')     // 类型由 has 给（typedStart 认得 DIST）
g.E(DIST,'Payment').has('status','SUCCESS').outE(...) // 边表同理
g.E(DIST).has('Payment','status','FAILED').groupCount('status')
```

`g.V(DIST, id, 'T')` 明确报错（"takes a vertex name and no ids"）：带 id 的读是按 key 的单点读，分布式没有意义。

执行（`GremlinGraphQuery.runDistributed`）：

1. **map**：`scanPartitionCount()` 个分片，每个一个任务、跑在线程池上（线程数 = `min(分片数, parallelism)`，
   线程名 `phoenixgraph-map-*`，daemon）。每个 mapper 有自己的 `GremlinContext`，先 `partitionStart(...)` 把自己那段
   hash 区间的点/边扫出来（`g.V(DIST)` 不带类型时按 schema 里每个类型各扫一段），然后跑**局部步骤前缀**——
   属性条件（`has`/`filter`）、投影（`values`）、跳步（`outE`/`out`/`both`…）都在 mapper 里做完，过滤贴着数据发生。
2. **reduce**：把各 mapper 的输出按分片顺序串起来，再由**一个** context 跑**全集步骤**（`order/limit/skip/range/tail/sample/
   dedup/count/sum/mean/max/min/groupCount/fold/cap/group/aggregate/timeLimit`，见 `GLOBAL_STEPS`）。
   切分点就是"第一个全集步骤"（`mapStepCount`）。**没有全集步骤时 reducer 就是拼接**——正是需求③说的
   "不是计算结果就只留在各 mapper 输出"。答案与单进程**完全一致**（这就是测试比较的东西）。
3. 子图：各 mapper 的 `buildSubgraph()` 通过 `replay(...)` 回放进 reduce 的 context，画布看到的是它们的并集。
4. `path()` 与 `DIST` 明确互斥并报错（一条 walk 在一个分片里按元素构建，跨分片的 walk 这个引擎不保存）。

失败与中断：任一 mapper 抛 QueryException 就原样上抛（`ExecutionException` 解包）；被中断则 `QueryException("…interrupted")`，
线程池 `shutdownNow()`。

### 85.3 验证

**存储层单测** `RocksDbEdgePartitionedScanTest`（`graph-storage-rocksdb-edge`，3 个用例）：300 点/400 边，
分片数 1/2/3/4/8/16 时**每个分片的并集都等于全表、且每行只出现一次**；一个"每分片一个线程、并发跑再 reduce"的用例
（`invokeAll`）得到同样的并集，且**与分片完成顺序无关**；分片边界的无符号单调性与最后一段的开区间。
`graph-storage-rocksdb-edge` 共 **24** 用例绿。

**引擎单测** `GremlinDistributedScanTest`（`graph-query-gremlin`，3 个用例）：用一个可切分的 in-memory store，
把用户给的四种形式逐一对比 `DIST` 与单机（结果集合与条数都相等），再加 `outE` 后续跳、`count`/`groupCount` 归约、
以及两条错误路径（不可切分的 store、`DIST` 带 id）。`graph-query-gremlin` 共 **250** 用例绿。

**线上实测**（`.logs/verify-dist.js`，`payment-graph-rocksdb-edge`，7 组对比全部一致）：

```
same  g.V('Account').has('accountStatus','FROZEN').count()             -> [17]
same  g.V().has('Account','accountStatus','FROZEN').outE('Payment').count()  -> [51]
same  g.E('Payment').has('status','SUCCESS').count()                   -> [65]
same  g.E().has('Payment','status','FAILED').count()                   -> [67]
same  g.V('Account').has(...).outE('Payment').values('status')         -> 同一批
same  g.E('Payment').has('status','SUCCESS').groupCount('status')      -> [{"SUCCESS":65}]
7/7 pairs agree
```

**注意**：这个演示图只有几十个点、上百条边，`DIST` 在这里**不会更快**（延迟同为几十毫秒级，分片线程的调度开销相当）；
它验证的是**切分正确、答案一致、可切换**。收益要在成规模的表上才体现（分片扫描把一次全表扫描换成 N 个并发范围扫描）。

### 85.4 UI 例子与复现

`payment-graph-rocksdb-edge` 的例子列表新增 6 条（单机 vs 分布式成对，中英双语）。

```bash
node .logs/verify-dist.js                          # 线上 7 组 DIST/单机对比
./mvnw -o -pl graph-storage-rocksdb-edge test      # 分片扫描（含并发 mapper）3 个用例
./mvnw -o -pl graph-query-gremlin test             # DIST vs 单机 3 个用例
```

### 85.5 已知边界

* `DIST` + `path()`：不支持（报错说明原因）；
* `limit`/`sample` 这类"无序的全局裁剪"在分布式下的**具体子集**取决于分片拼接顺序（条数一致）——要确定性子集就配 `order`；
* 分片数就是 store 的 `regionCount`（当前演示图是默认值），要更细的并行度得改配置；
* 每个 mapper 一个 `GremlinContext`（自己的边缓存/投影计划），所以 **mapper 内的迭代是有界的**：一个分片里的点/边仍然按批量读走
  （`readAllEdgesBatchCached` 等），"mapper 内多线程"目前体现为 **mapper 之间并行**；
  单 mapper 内部再切子任务（同一分片再分几段给子线程）是下一步可选项。
## 86. 分布式扫描 + 分布式算法：一个 `DIST` 管整条查询

用法（用户给的形状）：

```groovy
g.V(DIST).has('Account','accountStatus','FROZEN').bothE('Payment').cc()
```

前半段（**分区扫描 + 属性条件 + `bothE` 扩展**）在 mapper 里跑（§85）；后半段的 `cc` 是**全局步骤**，交给 **Gemini 集群**
（`graph-gemini`，服务默认 4 worker / 19100 端口起 / local 模式）；输入是 mapper 展开出来的那张子图（`GremlinDistAlgorithms.dist`
的 `scopeOf` 从 edge traverser 取两端点与边类，`GeminiGraph.of(graphQuery, scope, directed)` 构造诱导子图）。
`cc/louvain/pagerank/sssp/bfs` 五个都适用——**算法步骤本来就是全局步骤**。

### 86.1 两处改动

1. **起点 `DIST` 传播到算法步骤**：`GremlinContext.distributedAlgorithms()` 标志，由 `runDistributed` 在 mapper 与 reduce 的
   context 上置位；算法步骤的注册从"`(DIST)` 才用集群、否则内存"改成三态：
   * 显式 `cc(DIST)` → 集群（**没有集群就直接报错**，这条既有语义不变）；
   * 起点 `DIST`（无 `(DIST)` 标记）→ 集群（懒构造，无集群时在执行到那一步才报错）；
   * 否则 → 内存实现。
   `g.V(DIST)` 因此表达的是"**这条查询跑在分布式上**"，不用在每一步重复 `(DIST)`。
2. **算法步骤进了全局步骤表**：`GLOBAL_STEPS` 原来漏了 `cc/louvain/pagerank/sssp/bfs`，于是 `g.V(DIST)…cc()` 会**每个 mapper 各算一份**
   （4 个分片就返回 4 个 map）。补进去之后它们落在 reduce 段：**一张图、一次计算**。

### 86.2 验证（`.logs/verify-dist-cc.js`，`payment-graph-rocksdb-edge`）

组件号只是标签（两个引擎按各自遍历顺序编号），所以对比的是**等价类**（哪些点同组）：

| 查询 | 单机 | 分布式 |
|---|---|---|
| `g.V().has('Account','accountStatus','FROZEN').bothE('Payment').cc()` | 263 ms，**51 点 / 1 个连通分量** | 884 ms，**51 点 / 1 个连通分量**，**同组** ✓ |
| `g.V('Account').bothE('Payment').cc()` | 24 ms，51 点 / 1 分量 | 359 ms，51 点 / 1 分量，**同组** ✓ |

单测：`GremlinDistributedScanTest#aDistributedStartRunsItsAlgorithmOnTheClusterToo`（用 `GeminiCluster.local()` 真起集群，
对比 `g.V('Account').has(...).bothE('Payment').cc()` 与 `g.V(DIST)…cc()` 的等价类，并断言算法步骤只回一个 map）。
`graph-query-gremlin` 共 **251** 用例绿；`GremlinDistStepsTest` 14 个（既有 `(DIST)` 形式）不变。

### 86.3 三点要说清楚的

1. **小图上分布式更慢**：上表 884/359 ms vs 263/24 ms。集群有 4 个 worker 的起停与超步同步开销，50 个点的图吃不到并行收益；
   `DIST` 的价值在成规模的数据上（分片扫描 + 集群算法），这里验证的是**正确性、可切换与端到端组合**。
2. **组件号不可跨引擎比较**，要比就比等价类；要稳定编号得自己在结果上再排一次。
3. **未知属性在分布式下会报错**：用户原始形状里 `filter(status.equals('active'))` 用了 `Account` 上不存在的 `status`
   （应为 `accountStatus`；边类是 `Payment`，不是小写 `payment`）。单机下这个 `filter` 会被下推成条件而**静默返回空**，
   分布式下分区扫描要把该 key 投影出来 → 报 `Unknown property 'status' in vertex schema 'Account'`。
   **行为差异是有意的**（报错比静默空好），但也算是两套路径的一个不一致点，记在这里。

### 86.4 UI 与复现

`payment-graph-rocksdb-edge` 例子列表新增 3 条（单机 cc / `DIST` 版 cc / 同 `DIST` 换 pagerank）。

```bash
node .logs/verify-dist-cc.js                       # 线上等价类对比
./mvnw -o -pl graph-query-gremlin test             # 251 用例（含集群算法那条）
```
## 87. Step 支持现状复核（2026-09-25）：探针实测 + 能否支持 + 支持的副作用

方法：从源码枚举注册的 step（`grep register("`），再拿 71 条探针打**在跑的 8081**（`.logs/probe-steps.js`），
每个 refused 都记引擎原话；对可疑结果再手工复核。产物：`.logs/step-coverage.txt`、`.logs/predicate-coverage.txt`。

### 87.1 已实现（96 个注册名）

```
all and any asBool asDate asNumber asString barrier batch both bothE cc choose circle coalesce coin
combine concat constant count countBothE countInE countOutE createdBetween cyclicPath dedup difference
discard disjunct elementMap filter fold fuzzyLink groupCount has hasId hasKey hasNot hasValue id identity
in inE inject intersect is iterate label length limit local louvain map max mean min nearest next none
not optional or order otherV out outE pagerank path rTrim range repeat replace sample simplePath size
skip split substring sum tail timeLimit times toBulkSet toIterator toList toLower toSet toUpper trim
unfold union valueMap values where
```
（`countBothE/countInE/countOutE/batch/fuzzyLink/nearest/circle/createdBetween/cc/louvain/pagerank` 是本项目的扩展或命名。）

### 87.2 探针结果（71 条）：**18 answered / 4 empty / 49 refused**

**比上一版 §76 表更好的地方**（探针实测，之前表里标的 ❌ 或不确定，其实已经支持）：

| 之前以为 | 实测 | 说明 |
|---|---|---|
| `has(key, P)` 不支持 | **部分支持** | `has('accountStatus', within('ACTIVE','FROZEN'))`=34 ✓、`has('primaryAddress', startingWith('addr-10'))`=50 ✓（TextP 也有） |
| `by(traversal)` 不支持 | **支持** | `g.V(1001).order().by(out('Payment').count())` ✓ |
| `elementMap()` 只无参 | **支持带 keys** | `elementMap('id','accountStatus')` ✓ |
| 子遍历要写 `__` | **不能写 `__`** | 引擎的写法是 `union(out('Payment'))`、`repeat(out('Payment'))`、`where(out('Payment'))`；写 `__.out(...)` 会报 `Unknown Gremlin step: __.out` |

**空的 4 条**：`none()`、`discard()` 空是设计（它们就是丢弃）；`and/or/not` 与 `is(within)` 空是我探针的**数据不匹配**（1001 是 CLOSED，我筛 ACTIVE），手工复核后都正常：
`and(has(...))`/`or(has(...))`/`not(has(...))` ✓、`is(within('CLOSED'))` = 17 ✓。

### 87.3 真正的坑：**P 谓词静默给错**（最重要的一条）

`g.V('Account').values('accountStatus')` 共 51 条（CLOSED/ACTIVE/FROZEN 各 17）实测：

| 写法 | 结果 | 判定 |
|---|---|---|
| `is('CLOSED')` | 17 | ✓ |
| `is(eq('CLOSED'))` | **0** | ✗ 静默错（应为 17） |
| `is(neq('CLOSED'))` | **51** | ✗ 静默错（应为 34；看起来谓词没被求值，而是拿 `neq('CLOSED')` 当字面量比） |
| `is(within(a,b))` / `is(without(a,b))` | 34 / 17 | ✓ |
| `is(gt(1030))` `gte(1049)` `lt(1002)` `lte(1001)` | 19/1/3/3 | ✓ 数值比较正确 |
| `is(between(1001,1003))` `inside(...)` `outside(...)` | **0 / 0 / 0** | ✗ 静默错 |
| `is(not(within('CLOSED')))` `is(and(gt(1000),lt(1003)))` | **0 / 0** | ✗ 静默错 |
| `has(key, eq(..))` `has(key, neq(..))` `has(key, gt(..))` `has(key, between(..))` `has(key, not(..))` | **全 0** | ✗ 静默错（`has(k,v)` 两参形式是好的） |
| `filter(accountStatus.equals('CLOSED'))` | 17 | ✓（文档里推荐的那种裸访问器写法） |
| `filter(is(gt(0)))` | 报错 `unexpected '(' in expression` | ✓ 明确拒绝 |

**结论**：谓词只有 `gt/gte/lt/lte/within/without` 被真正求值；**其余 P 形式既不报错也不生效**——这是当前最该修的一类问题
（错得安静，用户会当成"真的没有数据"）。

**能否支持**：能，而且是**优先级最高、风险最低**的一类修复。做法：在表达式层把 P 谓词编译成统一的 `Predicate`，
`is/has/where/TextP` 共用一条路径；**同时**给"无法识别的谓词"加一个默认分支——**报错**而不是当字面量比较（静默错的根源就是这个兜底）。
**副作用**：① 行为变更——现在返回 0/全量的查询修好后会返回真实结果，依赖"错值"的测试/脚本会变（这正是目的）；
② 修复后 `has(key, P)` 也走谓词，索引/下推的条件类型要与谓词对齐（`EdgePropertyFilter` 已经是这个形状，改动可控）；
③ TextP（`startingWith/endingWith/containing`）已经支持一部分，统一后要确认 `has(k, startingWith())` 与 `filter(k.startsWith())` 语义一致。

### 87.4 不支持的部分：能不能支持、代价与副作用

| 家族 | 现状 | 能否支持 | 代价 / 副作用 |
|---|---|---|---|
| `as/select/match/where('a',eq('b'))/project/sack/withSack/aggregate/store/sideEffect/cap/subgraph/tree/group().by().by()/branch` | ✗（`aggregate/cap/store/sideEffect/subgraph/tree/sack/branch/project/match` 报 `Unknown Gremlin step`） | **能，但要换执行模型**：现在管线是"列表里一个值"，这些需要 `Traverser{value, path, sack, labels}` + 副作用容器 | 影响面最大：`GremlinStep.apply(ctx, in)` 的语义、路径执行（`runPathCompiled`）、批量读优化都要跟着改。建议**分批**：先 `as/select/project`（覆盖大多数真实查询）+ `where('a',eq('b'))`，再议 sack/aggregate/cap。副作用：`as` 之后 `path()`/`dedup` 的语义、以及"同一标签被覆盖"的规则要写清楚 |
| `hasLabel(name)` | ✗ | **能，极小**（按当前 traverser 的 label 过滤，schema 里就有类型名） | 基本无副作用；顺带能支持 `g.V().hasLabel('Account')`（现在要写 `g.V('Account')`） |
| `properties()/key()/value()` | ✗（`Unknown Gremlin step: properties`） | 能，中等 | 需要引入**属性 traverser**（`GremlinProperty`）或退化成 `[k,v]` 对（不与 TinkerPop 一致）。引入新 traverser 类型会影响 `values/valueMap/elementMap/otherV` 的分支 |
| `outV()/inV()/bothV()` | ✗ | **能，小**（边 traverser 已带 `sourceVertexId`/`otherVertexId`，只差"批量读回顶点对象"） | 一次额外批量读；`side=BOTH` 时 `sourceEndpoint()` 目前是近似（§85 之前的记录），实现时要一并厘清方向语义 |
| `emit()/until()/loops()`（`repeat().times()` 已有） | ✗ | 能，但要 per-traverser 计数与沿途收集 | 与第 1 行同源（需要 `Traverser` 状态）；`emit` 的"哪些层算命中"规则要与 TinkerPop 对齐 |
| `math/format/dateAdd/dateDiff/dateTime` | ✗ | 能 | `math` 要新写一个表达式求值（`_ + 1` 的下划线占位），引擎已有 `GremlinExpression` 可复用；`format/sprintf` 直接 `String.format`。副作用：又一套小语法要对齐（`_`、`DT.day` 之类的 token） |
| `subgraph()/tree()` | ✗ | `subgraph` 能且便宜（引擎已经会收集 `Subgraph`）；`tree` 不能（需要路径树） | `subgraph()` 在 TinkerPop 里是副作用 + `cap()` 取值；没有副作用机制时只能做"立即返回当前收集的子图"的近似 → **不完全兼容**，要么接受近似要么等 `Traverser` 模型 |
| 写入家族 `addV/addE/mergeV/mergeE/property/drop/io/read/write/call` | ✗（`g.addV(...)` 直接报"Expected 'g.V(...)'…"；`property/drop/addE/io/read/write` 报未知 step） | **刻意不支持** | 引擎读-only 是设计决定：写走 HTTP 写接口（校验/审计/指标都在那里）。让 `g` 能写会把事务、id 分配、并发与权限全部拉进来。建议**保持**，并在报错里指向写接口（现在正是） |
| `profile()/explain()` | ✗ | **能且值得**（引擎有 metrics 与编译计划） | 副作用是暴露内部计划（对用户是好事）；实现上要把每个 step 的耗时/计数收集起来，和 §81 的编译指标风格一致 |
| `with*/withStrategies/withoutStrategies` | ✗ | 不建议 | 当前架构没有 TraversalStrategy 层；硬做只能"接受但忽略"——那就是**静默错**，宁可不支持（改为在 schema/服务层配置） |
| `tx()/commit()` | ✗ | 不建议（与读-only 冲突） | — |
| `shortestPath()/peerPressure()` | ✗ | 能（Gemini 已有 `sssp`/`bfs`） | 加别名即可，但要注意"集群路径 vs 内存路径"的一致性（§85/§86 同一套） |
| `index()/reverse()/dateAdd` 等零散 step | ✗ | `reverse` 能（列表反转）；`index` 已废弃 → 不做 | 低价值 |

### 87.5 一条不该有的语法偏差

子遍历在 TinkerPop 里写 `__.out('Payment')`，本引擎**不接受 `__` 前缀**（`union(out('Payment'))` 才是对的）。
**能否支持**：能，一行预处理（把裸 `__` 前缀剥掉）。**副作用**：几乎没有——只有"名字真的叫 `__` 的属性"这种荒谬冲突。

### 87.6 建议的优先级

1. **P 谓词统一 + 未知谓词报错**（修静默错，风险低，收益最大）；
2. `hasLabel`（一行过滤器）、`__` 前缀兼容（一行预处理）；
3. `as/select/project` + `where('a',eq('b'))`（第一批 per-traverser 状态，覆盖最多真实需求）；
4. `properties/key/value`、`outV/inV/bothV`、`profile`（中等、独立）；
5. 其余（`emit/until/sack/aggregate/cap/branch/math/tree`）跟着 `Traverser` 模型一起做；
6. 写入与 `with*` 明确保持不支持（错误信息说清原因）。

## 88. 脚本（Groovy 子集）支持现状复核（2026-09-25）：两批探针 + 能否支持 + 副作用

方法：两批共 **127 条**探针打**在跑的 8081**，每条都记引擎原话（`.logs/probe-script-support.js` 68 条 → `.logs/groovy-coverage1.txt`；
`.logs/probe-groovy2.js` 59 条 → `.logs/groovy-coverage2.txt`）。

**结果：48/127 接受**（batch1 44/68、batch2 4/59）。**好消息**：拒绝基本都带明确消息（"Unknown script method '.x'. Supported: …" /
"Cannot read the script value '…'. A value is a traversal…"）；**坏消息**：有 3 处是**静默给错**（见 88.3）。

### 88.1 接受（查询脚本真正用得到的）

* 语句：`def`、赋值/重新赋值、`return`、`print`/`println`、`//` `/* */`、分号/多行分隔、空行；
* 控制流：`if/else if/else`、`for (x in 集合)`、`while`、`break`/`continue`、嵌套块；
* 表达式：`+ - * / %`、`!`、`&&` `||`、比较、**小数**（§84 修好）、字符串拼接（`+` 两侧非数字即文本拼接）；
* 集合：列表字面量（含空表/嵌套）、`size() count() toList() toSet() isEmpty() get(i) first() last() start() end()
  reverse() contains() indexOf() add() addAll() retainAll() intersect() endIds() joinOnEnd()`、`join(sep)`；
* 闭包：`each() eachWithIndex() collect() findAll() find() any() every() count() sum()`（含 `sum { it * 2 }`），**闭包体可多行**；
  闭包里 `it.value('k')/id()/label()/value('side')`；**`it` 之外还可写 `{ x, i -> }`**；
* 遍历：`g.V(变量, 'Schema')`、链式 step、遍历结果当 id 列表再喂 `g.V(...)`、`toList()` 后当普通列表用。

### 88.2 拒绝（都有消息）

| 类别 | 例子 | 引擎回答（节选） |
|---|---|---|
| 语句级 | `switch`、`try/catch`、`do/while`、`assert`、`synchronized`、带标签的 `break outer`、`def (a,b) = [1,2]` | `Cannot read the script value '…'` |
| 运算符 | 三元 `a ? b : c`、elvis `?:`、`+=`、`++`、`**`、`<=>`、`..<`、`<<`、`?.`、`as`、`instanceof` | 同上（`..<` 是 `Expected '(' after '..'`） |
| 索引 | `xs[0]`、`xs[-1]`、`xs[0..1]` | 同上 |
| 一等闭包 | `def f = { x -> x*2 }` 然后 `f(3)`；闭包默认参数 | 同上 |
| 集合方法（未实现） | `collectEntries{}`、`groupBy{}`、`inject`、`sort{}`、`max{}`、`unique`、`flatten`、`take/drop`、`withIndex`、`toSorted`、`sum()`（无闭包） | `Unknown script method '.x'. Supported: …` / `Unknown closure method '.x'. Supported: each(), eachWithIndex(), collect(), findAll(), find(), any(), every(), count(), sum()` |
| 字符串/数字方法 | `'abc'.toUpperCase()` `.length()` `.substring()`、`1.7.intValue()`、`(-3).abs()` | 同上（`'abc'.size()` 可以） |
| 静态调用 | `Math.max(1,2)`、`String.valueOf(12)`、`Integer.parseInt('12')`、`Integer.MAX_VALUE` | `Unknown script variable 'Math'/'String'/'Integer'` |
| Map | `[a: 1]`（字面量）、`m.a`、`m['a']` | `Cannot read the script value 'a: 1'` |
| 正则 | `/a.c/`、`=~`、`==~` | `Cannot read the script value …`（**但有一种 `=~` 写法会被当成字符串，见 88.3**） |
| GString | `"v=${x}"` | `Missing value for query parameter 'x'`（**被 HTTP 参数替换先吃掉了**） |
| 格式化 | `sprintf`、`printf` | `Cannot read the script value …` |
| 语言级 | `class`、`import`、`def f(x) { }`、`new Date()`、`readLines()/eachLine()` | 同上 |

### 88.3 三处"静默给错"（最该先修）

1. **`'''多行字符串'''`**：`'''a\nb'''` 被当成普通字符串，结果是 `''a\nb''` —— 引号被留下、`\n` 没转义。**要么正确支持（`literal()` 里认三引号，几行）要么明确报错**。
2. **`=~`**：某些写法（`'abc' =~ 'b'`）被解析成**字符串**并原样返回（`abc' =~ 'b`），既不报错也不做正则。同样：要么支持要么报错。
3. **遍历结果是 List 不是标量**：`g.V(...).count() + g.V(...).count()` 得到**文本** `[3][9]`（`+` 对非数字走文本拼接）。要算术得先 `.get(0)`/`first()`。
   这是"类型系统不显式"的必然结果——建议在文档里显著提示，或让 `count()`/`sum()` 这类单值 step 在脚本里返回标量（**会改变现有行为**，需权衡）。

### 88.4 能否支持：分类与副作用

| 候选 | 难度 | 能否支持 | 代价 / 副作用 |
|---|---|---|---|
| `+=` `-=` `*=` `/=`、`++x`/`x++`、`do/while`、`switch`（脱糖成 if/else）、`assert` | **低** | 能 | 只是语句/运算符层的小补充；副作用几乎为零。`switch` 要定 `case` 的相等语义（`==` 与 `equals`） |
| 三元 `a ? b : c`、elvis `?:` | **低** | 能 | 纯表达式层；无副作用 |
| 列表索引 `xs[i]`（负索引）、`xs[i..j]` | 低（负索引）/ 中（range 形式，需要 range 值） | 能 | range 值要新增一个值类型（或脱糖成子列表）；无外部副作用 |
| Map 字面量 `[a: 1]`、`m['a']` | **低-中** | 能 | 脚本层已有 Map 值（`properties()` 返回 `LinkedHashMap`），只差解析：要在 `[` 后看是否有顶层 `k:` 来区分 list 与 map。副作用：**与列表字面量的歧义**要小心（`['a':1]` 是 map，`['a',1]` 是 list） |
| `'''…'''`、`=~`/`==~`、`/regex/` | 低（三引号）/ 中（正则值） | 能（三引号建议直接支持；正则至少要**报错**而不是静默） | 正则要引入 pattern 值类型；无外部副作用 |
| 更多闭包方法：`groupBy{} collectEntries{} inject sort{} max{} min{} unique flatten take drop withIndex toSorted` | **低**（逐个复用现有闭包机制） | 能 | 每个方法一个分支；`sort{}`/`max{}` 的**比较器语义**要定清楚（`<=>` 还是布尔），否则又一处静默歧义 |
| 字符串/数字方法：`toUpperCase toLowerCase length substring replace split trim startsWith endsWith contains matches`、`abs intValue toInteger` | **低** | 能 | 委托 Java 即可；要**限定白名单**（只读、无副作用），否则变成"任意方法调用" |
| 静态调用 `Math.*`/`Integer.parseInt`/`String.valueOf` | 中 | 能 | 需要一张**白名单类表**；不做白名单就等于开放任意 Java（沙箱逃逸），所以必须白名单 + 未知类报错 |
| 一等闭包（`def f = {…}; f(3)`） | 中 | 能 | 需要"可调用值"类型 + 作用域捕获；副作用：闭包生命周期/变量捕获语义要定义（Groovy 的 `owner`/`delegate` 不做） |
| `try/catch` | 中 | **建议不做** | 需要把 `QueryException` 暴露成脚本值；副作用是脚本可以**吞掉真错误**（把存储/查询失败变成"看起来没数据"），与"拒绝要明确"的设计原则冲突 |
| `class`/`interface`/`trait`/`import`/注解/`new`（任意类）/多线程/IO | 高 | **不做** | 会把脚本变成通用运行时：依赖、内存、线程、沙箱全部要重做；查询脚本不需要 |
| GString `"${x}"` | — | **不做（冲突）** | `${name}` 已经是**HTTP 参数替换**语法，脚本解析器根本收不到；要支持只能换一套插值标记（例如 `$x` 或 `"${'literal'}"` 的转义规则），会破坏现有参数功能。建议保持不支持并在文档里说明 |
| 格式化 `sprintf/printf` | 低 | 能 | `String.format` 即可；无副作用（除 `%s` 对 List 的渲染规则要定） |

### 88.5 建议的优先级

1. **先修三处静默错**（`'''…'''`、`=~`、`count()+count()` 的类型提示）——它们让"支持边界"变得不可信；
2. **零风险的一批**：`+=` 家族、`++`、`do/while`、`switch`、三元/elvis、`assert`、三引号字符串、`sprintf/printf`、更多闭包方法；
3. **低风险的第二批**：列表索引（含负索引）、Map 字面量 + `m['a']`、字符串/数字方法白名单；
4. 需要权衡的：静态方法白名单（沙箱面）、一等闭包；
5. **明确不做**：`try/catch`（会吞错）、GString（与参数替换冲突）、类/导入/任意 `new`/IO/线程（通用运行时）。
