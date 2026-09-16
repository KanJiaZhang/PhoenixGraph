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
- `GremlinSteps`：内置步骤（`out/in/both`、`outE/inE/bothE`、`has`、`values`、`elementMap`、`count/limit/dedup`、`batch/barrier`）；**`out/in/both/outE/inE/bothE` 支持 0 参数 = 所有边类**（`readAllEdgeClasses` + `edge.edgeName()` 逐边解析邻居 schema）。
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
