# PhoenixGraph 开发上下文（断点续做手记）

> 本文件用于保存当前工作上下文，方便下次回到本目录后立即继续开发。
> 分支：`develop`；远程：`github.com:KanJiaZhang/PhoenixGraph.git`。
> 上次基线提交：`97c3519`（develop，已 push）。本文件随当前改动一并提交。

---

## 1. 当前状态（本轮收尾时）

- **构建**：`./mvnw -q -DskipTests compile` 通过；`QueryApiIntegrationTest` 2 个用例全绿（2/0/0）。
- **本地环境**：Aerospike 单机 `localhost:3000`，namespace `test`；集成测试读 `AEROSPIKE_HOST/PORT/NAMESPACE`（默认 localhost:3000/test），无 Aerospike 时自动 `skip`。
- **运行中的服务**（本机曾手动启动，提交代码后如需复现按第 8 节命令重启）：
  - `graph-http`：`java -jar graph-http/target/graph-http-*.jar --phoenixgraph.storage.vertex-set=http_rich_v2`（端口 8081）
  - `graph-web`：`java -jar graph-web/target/graph-web-*.jar`（端口 8080，代理 `/api/query`、`/api/graphs` 到 graph-http）
- **当前数据**：set `http_rich_v2`（由 `QueryApiIntegrationTest#layeredBothHopQueryViaApi` 播种）：888 + 100 个一跳 Account + 300 个二跳 Account + 10 IP + 10 Phone；888 关联 5 IP + 5 Phone，10 个一跳账户各关联 1 IP + 1 Phone。
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
```

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
- **交互已实现**：图实例下拉；ID 查询（顶点类型下拉 + ID 输入）＋ Gremlin toggle 切 textarea（选中时 textarea 位于第二行、宽度 1/3）；单一「查询」按钮固定第一排右侧；d3 力导向（center + forceX/Y + resize 重排）；双击点 `both()` 扩一跳合并；单击点/边看属性（含 createdTime/lastUpdatedTime）；拖拽点只移动固定该点不重排（边跟着走）+ ↻ 手动平衡按钮；⛶ 框选过滤（只显示框内点及两端都在框内的边，⊞ 恢复全部）；Legend 固定左上（在工具栏下方 y=60）。
- **算法下拉（框选旁）**：`louvain` / `连通分量(cc)`（同社区同色，`communityColor` 用 golden-angle HSL）/ `pagerank`（金色虚线环高亮 Top 20% 点）；算法在**当前显示子图**上就地计算、不重排；图变化（查询/扩展/框选/重置/切换图实例）时自动 `clearAlgo()`。
- **前端源码漂移风险**：`frontend/` 与 `static/` 目前是**拷贝关系**，改动后必须同步拷贝（见第 8 节命令）。

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
