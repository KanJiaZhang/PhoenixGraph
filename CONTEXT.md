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
