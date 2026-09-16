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
