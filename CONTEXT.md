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
