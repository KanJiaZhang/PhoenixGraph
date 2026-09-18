# Handover — resume here

> 本文件是**断点续做手记**：今天做了什么（结论）、现场怎么恢复、明天从哪继续。
> 详细设计与实测数据在 `CONTEXT.md`（今天新增 **第 21–26 节**）。

**最后一次会话：2026-09-18（周四）· 在 2026-09-17 的 8 个提交之后继续 · 工作树干净 · `./mvnw test` **20 个模块**全绿。**

> 2026-09-18 新增：**smart payment 数据**（第 31 节）—— `payment-graph-smart`（Aerospike，向量/HNSW）+
> `payment-graph-smart-rocksdb`（真 S2 geo 索引），三个流都实测；顺带修掉同名向量属性的 HNSW 层边类冲突；
> 以及**按 benchmark 结论做的优化**（第 32 节：Cypher 聚合 N+1 53.2ms→3.9ms、count 走 store 的 bin size、
> 投影只解码请求的属性；并修掉 both() 无类计数被误改写的回归）、
> **核对与 benchmark**（第 30 节：12 项一致性 + 10 条查询的延迟/KV 往返与瓶颈分析）、
> **图建模 UI**（第 29 节： 建图/写盘/热加载/导出）、**建模接口与指标面板**（第 28/27 节）、
> **UI 修正**（第 33 节：主页查询行不错行、建模页改浅色可读）。
>
> 2026-09-18 新增：**系统 metrics**（第 27 节）——`graph-metrics` 模块、Aerospike/RocksDB 两套持久化、
> `/api/v1/metrics` 接口；`QueryService` 增加 `source` 字段（UI 传 `ui`）。

---

## 0. 今日结论（按提交顺序）

| 提交 | 任务 | 结论 |
|---|---|---|
| `6c515bf` `998c41e` | UI：示例查询收进小按钮；查询结果值浮窗 | 示例框默认隐藏、按钮展开；HTTP 新增 `includeResult`，一次执行同时返回答案与子图（兼容旧形状）；浮窗优先展示"值"而不是数量 |
| `ea80390` | 修复 `limit()` 截断属性投影 | `values()` 紧跟 `limit()` 取不到值 → `neededProperties` 不再把 `limit` 当终结步骤；示例框改为查询框 1/3 宽 |
| `623d174` | **RocksDB 两种存储实现**（第 21 节） | schema 用 `storage.type` 选实现；新增 `graph-storage-rocksdb`（共享底座）+ `-inline` + `-edge` 三模块；两方案都实现 `GraphQuery`，Gremlin/Cypher/批读/并行/按需属性/缓存**全部复用**；payment-graph 数据装进两个 RocksDB 实例，UI/API 可查（`limit`/`degree`/`region`/`time window` 语义见文档） |
| `6dd20c1` | **GEO 查询**（第 22 节） | schema `isGeo` 声明 + Google S2 cell 索引表（一属性一表，键 `cellId:vertexId`，由顶点写入自动维护）；Gremlin `circle(...)`（原 `geoNear`）+ 结果带距离；Aerospike 侧 best-effort geo2dsphere 索引 + 扫描兜底；240 家店 demo，1km → 159 家 |
| `97555a5` | 四项修正（第 23 节） | ①示例框与查询框同列 ②**修复 RocksDB 子图"只有点没有边"**（子图 id 统一为文本，根因是"种子用查询里的字符串 id、顶点用存储里的原生 id"）③`geoNear`→`circle`、半径改**米**、`limit` **下推进**索引搜索（不再先取全部再截断）④edge target 新增 **`isNull`**（只存出边、无指向；含校验/存储/查询/子图） |
| `45cf550` `e9d1a3b` | UI 三处（第 24 节） | 示例框回到同一行靠右（1/3 宽）；浮窗只在**非点非边**结果时出现；「结果」tab 仅 Cypher 且查询过之后才出现 |
| `0cfa4cd` | **Aerospike-Edge**（第 25 节） | 大点用**聚合边表**：每种边两张表（`Edge_Out`/`Edge_In`）+ 键 `点id\|min\|max\|层级` + 三层索引 + **离线批量导入**（生成所有 KV 后批量写，绝不逐条更新）；查询用新增 `createdBetween(from,to)` **逐级下降**并与内联边 **merge**；demo hub **30 万笔**收款，1 天窗口 `limit(500)` ✓；近线更新/删除/重聚合列入 TODO |
| `94c713e` | UI 修正（第 26 节） | 查询行内控件**中线对齐**；**双击大点自动改为有界扩展**（宽窗口 + `limit(200)`，服务端新增**窗口读 cap 下推**：`createdBetween(a,b)` 后紧跟的 `limit(k)` 折进窗口，聚合表读到 k 条即停，30 万条 5.8s → 200 条 114ms）；顺带修掉"扩展合并错响应形状"的既有 bug |

今天新增/变更的关键能力（一句话版）：

- **存储可选**：`Aerospike`（默认）/ `Aerospike-Edge` / `RocksDB-Inline` / `RocksDB-Edge`，schema 里 `storage.type` 声明；
  四者都实现同一个 `GraphQuery` 端口 → 查询层零改动。
- **新 Gremlin step**：`circle(点JSON[, 半径米])`（地理）、`createdBetween(from, to[, cap])`（时间窗）、
  `nearest(k[, ef])`（向量，早先）、以及 `limit` 自动下推到 `circle` / 窗口读。
- **schema 新字段**：`isGeo`（+`geoLevel`/`geoMaxCells`）、edge target 的 `isNull`、`storage`。
- **UI**：语言切换、示例查询（按钮 + 按图分组）、结果表格 tab（Cypher 专属）、值浮窗、大点有界扩展。

---

## 1. 现场状态（明天开机即用）

### 1.1 正在跑的服务

| 端口 | 进程 | 内容 |
|---|---|---|
| **8081** | `graph-http`（默认 profile） | **9 个图**：`payment-graph`、`payment-graph-rocksdb-inline`、`payment-graph-rocksdb-edge`、`geo-merchants-rocksdb-inline`、`geo-merchants-rocksdb-edge`、`payment-graph-as-edge`、`kg-entity-graph`、`hnsw-sift`、`hnsw-dynamic` |
| **8080** | `graph-web` | UI（`/api/graphs` 列图、`/api/query` 代理到 8081） |
| **8083** | `graph-http --spring.profiles.active=hnsw-dynamic` | 写入即索引的向量 demo（REST 写点 → 立即可 `nearest`） |

起停（`jps -l | grep graph-` 看 PID；Windows 下 `taskkill /F /PID <pid>`）：

```bash
./mvnw -q -pl graph-http,graph-web -am -DskipTests package
java -jar graph-http/target/graph-http-0.1.0-SNAPSHOT.jar
java -jar graph-web/target/graph-web-0.1.0-SNAPSHOT.jar
java -jar graph-http/target/graph-http-0.1.0-SNAPSHOT.jar --spring.profiles.active=hnsw-dynamic --server.port=8083
# 健康检查
curl -s localhost:8081/api/v1/graphs | head -c 200
```

### 1.2 数据（Aerospike namespace `test` + 本地 RocksDB 目录）

| 位置 | 内容 |
|---|---|
| `http_demo_v1` | payment-graph 星形图（Aerospike，hub `135`） |
| `kg_nell995_v2` | NELL-995 知识图谱（1 种点 `Entity` + 1 种边，按 relation 分 10 片），11.3 万点 / 15 万边 |
| `hnsw_sift_v1` | SIFT small 10,000×128（`Vector` + `vector_0..vector_14`），10,001 点 |
| `hnsw_dynamic_v1` | 600×128（`Doc` + `embedding`，schema 零层边，启动自动建） |
| `payment_as_edge_v1` | **今日新增**：`payment-graph-as-edge` 的 51 个点 + hub `9000001` 的 **30 万笔**收款（聚合边表 `_edge_out_1`/`_edge_in_1`/`_edgeheads`），其中 5 笔内联改写为 `REFUNDED` |
| `.rocksdb/`（仓库根，21MB，已 gitignore） | `payment-graph-rocksdb-inline`、`payment-graph-rocksdb-edge`（各 151 点/400 边）、`geo-merchants-rocksdb-inline`、`geo-merchants-rocksdb-edge`（各 240 商户 + S2 geo 索引） |

**重新播种（数据丢了才需要）**：

```bash
# RocksDB 四个实例（payment ×2 + geo ×2）
./mvnw -q -pl graph-integration-test -am test -Dtest='RocksDbLiveLoadTest' \
       -Drocksdb.live.dir=D:/workspace/eclipse/PhoenixGraph/.rocksdb -Dsurefire.failIfNoSpecifiedTests=false

# Aerospike-Edge 的 30 万笔 demo（约 4 秒；写入前会 truncate 边表与 hub 表）
./mvnw -q -pl graph-integration-test -am test -Dtest='AerospikeEdgeLiveLoadTest' \
       -Daerospike.asEdge.set=payment_as_edge_v1 -Dsurefire.failIfNoSpecifiedTests=false
```

### 1.3 明天的 demo 查询（都能直接用）

```groovy
// 1) 大点按时间窗查询（Aerospike-Edge，文档里的那条）
g.V('9000001','Account').createdBetween('2025-06-01','2025-06-02').inE('Payment').limit(500)   // 500 条
// 2) 大点双击扩展走的就是这句（宽窗口 + cap）
g.V('9000001','Account').createdBetween('1970-01-01T00:00:00','2100-01-01T00:00:00').both().limit(200)
// 3) 地理：1km 内最近的商户（两个 RocksDB geo 实例一致）
g.V('1','Merchant').circle('{"lat":31.2304,"lon":121.4737,"radius":1000}').limit(10)
// 4) 向量
g.V('4242','Vector').nearest(20)
// 5) RocksDB payment 实例（与 Aerospike 结果一致）
g.V('135','Account').out('Payment').count()      // 50
// 6) 有向边 + nullable target demo
g.V('1','Merchant').outE('Opens')                // 3 条 otherVertexId=null 的"未定对手方"边
```

---

## 2. 明天第一件事（按优先级）

1. **Aerospike-Edge 近线更新（今天明确列入 TODO，第 25.7 节）** —— 读路径已正确（内联 + 聚合 merge ✓），
   待补写入侧：
   1. **删除墓碑**：边删除时聚合表里的副本仍在 → 写内联墓碑（`deleted=true` + lastUpdatedTime）并在 merge 时过滤，
      或周期重写受影响 chunk；
   2. **窗口外新增边的增量重聚合**：root 的 `min/max` 只在导入时算过，之后窗口外新增大量边需插入新 chunk 并刷新父层 span；
   3. **重聚合/压缩任务**：把内联里已稳定的边折叠进 level-0、刷新上层、清理墓碑（分钟级延迟可接受）。
2. **HNSW 过滤的可控性与代价**（沿用上一轮遗留，第 17 节）：
   选择率来源（统计/采样代替"实测通过率+翻倍重试"）、多条件选择率初值、`maxEf` 暴露成查询参数、
   属性与向量相关时的退化验证。
3. **前端单一源**：`graph-web/frontend/public` 与 `src/main/resources/static` 仍是**拷贝关系** ✗（今天多次同步），
   建议改成一个源 + 构建时拷贝，避免漂移。
4. **Docker 验证**：本机无法访问 Docker Hub（`auth.docker.io` 超时），待有外网时跑 `docker compose up --build`；
   可把 KG/HNSW/RocksDB/Aerospike-Edge 的播种脚本化进镜像。
5. **Logo**：凤凰 Logo 仍用 `#1`，选定后替换 `index.html` brand SVG + `favicon.svg`（+ `logos/` 内 8 个文件）。

---

## 3. 常用命令

```bash
# 全部测试（约 6.5 分钟；Aerospike 不在会自动 skip 集成测试）
AEROSPIKE_HOST=localhost AEROSPIKE_PORT=3000 AEROSPIKE_NAMESPACE=test \
AEROSPIKE_VERTEX_SET=vertices_probe_clean HNSW_DATASET_DIR=/tmp/siftsmall_dl/siftsmall \
  ./mvnw test -Dsurefire.failIfNoSpecifiedTests=false

# 定向集成测试
./mvnw -pl graph-integration-test -am test -Dtest=AerospikeEdgeIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl graph-integration-test -am test -Dtest=RocksDbStorageIntegrationTest  -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl graph-integration-test -am test -Dtest=GeoQueryIntegrationTest        -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl graph-integration-test -am test -Dtest=HnswGraphIntegrationTest \
       -DHNSW_VERTEX_SET=hnsw_sift_v1 -DHNSW_DATASET_DIR=/tmp/siftsmall_dl/siftsmall \
       -Dsurefire.failIfNoSpecifiedTests=false          # HNSW_FORCE=true 才会重建索引（≈15min）

# schema / 数据集生成工具
node tools/generate-hnsw-schema.js --size 10000 --ef-search 50
node tools/generate-kg-schema.js
tools/download-siftsmall-dataset.sh        # → .datasets/siftsmall（gitignore）

# 前端改动后必须同步静态副本（现仍是拷贝关系）
cp graph-web/frontend/public/index.html graph-web/src/main/resources/static/index.html
cp graph-web/frontend/public/css/style.css graph-web/src/main/resources/static/css/style.css
cp graph-web/frontend/public/js/app.js  graph-web/src/main/resources/static/js/app.js
```

**向量索引运维接口**（详见第 16/17 节）：

```bash
curl -s localhost:8081/api/v1/vector-index                     # 配置与状态
curl -s -X POST localhost:8081/api/v1/vector-index/rebuild \
     -H 'content-type: application/json' -d '{"graph":"hnsw-dynamic"}'
```

---

## 4. 关键设计要点（改代码前先看）

### 4.1 存储与查询分层（今天的基础）

- `GraphQuery`（`graph-query`）= 唯一读端口：`readVertex/readVertices/readEdges/readEdgesBatch/readAllEdgesBatch`
  + `readEdgesWithin(...[, maxResults])`（时间窗 + cap）+ `geoNear(...)`；
  **四种存储都实现它** → Gremlin / Cypher / 批读 / 并行 seed / 按需属性 / 缓存全部复用。
- `Subgraph` / `NeighborVertexResolver` / `SchemaProjections` / `TimeRange` 都在公共模块（`graph-query` / `graph-storage`）；
  `Subgraph` 的 **id 统一为文本**（否则同一张子图里点与边的 id 类型不一致 → UI 画不出边）。
- 顶点写的**版本语义**：每个 property 的 `lastUpdatedTime` 即"版本"，只允许新版本覆盖；
  RocksDB 两方案与 Aerospike 完全一致（含"搬家重建 geo 索引"）。
- **`limit` 下推**（今天的性能关键）：`circle(...)` 与 `createdBetween(...)` 之后紧跟的 `limit(k)`
  在**编译期**折进 step 参数 → 存储侧带 cap 读取：S2 索引用 k 大小的堆、聚合边表读到 k 条即停。

### 4.2 各存储方案的边界

| 方案 | 点 | 边 | 限制/特性 |
|---|---|---|---|
| `Aerospike` | 顶点记录 | 记录内 `ve_<schemaId>_<dir>` bin（内联） | 受出入度上限与记录大小限制；per-property CAS |
| `Aerospike-Edge` | 同 Aerospike | **聚合边表**：`Edge_Out`/`Edge_In`，键 `点id\|min\|max\|层级`，level0 ≤2000 条/CDT，逐层折叠到 root | 大点读**必须**给窗口（`createdBetween`）；与内联 merge；批量导入写 |
| `RocksDB-Inline` | 每点类型一张表，键 `hash(id):id` | 每边类型一张表，按 Aerospike bin 名存该点全部边 | 度数上限（淘汰最旧，按端独立）；旧写不回退 |
| `RocksDB-Edge` | 同上 | 每边类型一张边表，键 `hash(vertexId):dir:vertexId:createdTime:edgeId` | 无度数上限；时间窗是 range scan；`both` = 两迭代器 |

### 4.3 其他（沿用）

- HNSW：层方案/层边命名 `<属性名>_<层>`（id 稳定）/度数/schema `2×` 兜底/入口点 1 分钟缓存/
  `metric·m·efConstruction` 以元数据为准、`efSearch` 取 schema。
- 知识图谱：一种点 + 一种有向边，id 按 `floorMod(relation.hashCode(), 10)` 分 10 片。
- HTTP：`language`（gremlin/cypher）、`${name}` 文本替换 + `$name` 原生参数、错误回传
  `{error, exception, stackTrace[], hint}`、多图 `GraphResolver/GraphRegistry`。
- UI：查询**替换**（不累加）画布；双击扩展 380ms 手动判定；`includeResult` 让"值浮窗"与"子图"一次拿到。

---

## 5. 已知问题 / 限制

1. **Aerospike-Edge 写入侧 gap**（见 §2.1）：删除、窗口外新增、重聚合未做（读路径已 merge，正确性 OK）。
2. **前端两处源**（`frontend/public` vs `static/`）靠手工 `cp` 同步。
3. **Docker 未实测**（本机无外网到 Docker Hub）。
4. 内存图算法（pagerank/louvain/cc）只支持**同构点**。
5. Gremlin / Cypher 均为**子集**：Cypher 不支持 `WITH/UNWIND/OPTIONAL MATCH/UNION/变长关系命名/shortestPath`；
   Gremlin 无 `group/order/project` 等（`order by` 在 Cypher 有）。
6. RocksDB 为**单进程**占用目录（同一目录不能多实例同时打开）。
7. Aerospike 的 geo 二级索引在社区版不可用 → geo 查询退化为扫描（RocksDB 侧是真索引）。
8. UI 双击扩展对超大点显示的是"窗口内最早的 200 条"（不是随机/最新）——按时间序可预期，文档已说明。

---

## 6. 代码地图（今天新增/改动）

**新增模块**：`graph-storage-rocksdb`（共享底座：键/区域/CF/属性编解码/顶点表/条带锁/LRU/`S2Cells`/`RocksDbGeoIndex`）、
`graph-storage-rocksdb-inline`、`graph-storage-rocksdb-edge`。

**新增类（要点）**
- RocksDB：`RocksDbHandle/RocksDbKeys/RocksDbHash/RocksDbVertexTable/RocksDbGeoIndex/S2Cells/RocksDbInlineStore/RocksDbInlineGraphQuery/RocksDbEdgeStore/RocksDbEdgeGraphQuery/RocksDbTimeRange→TimeRange`
- Aerospike-Edge：`AerospikeEdgeChunks`（键/分层/折叠/窗口选择）、`AerospikeEdgeChunkStore`（读写/逐级下降/cap/hub 缓存）、
  `AerospikeEdgeBulkImporter`（批量导入）、`AerospikeEdgeGraphQuery`（内联 + 聚合 merge）
- 公共：`TimeRange`（`graph-storage`）、`GeoPoint/GeoSearch/GeoHit`（`graph-schema.geo`）、`GeoIndexConfig/GeoIndexSchemas`、
  `GraphStorageType/GraphStorageSchema`、`SchemaProjections`（含"计算属性"`distanceMeters`）、edge `isNull`
- Gremlin：`circle` / `createdBetween`（含 cap 下推）、`GremlinQuery` 解析器改为**引号感知**（JSON 参数）
- HTTP：`GraphBinding.graphQuery`、`RocksDbGraphStores`、`Aerospike-Edge` 分支、`GraphInfo.storage/geoProperties`
- 集成测试/工具：`PaymentGraphRocksDbLoader`、`GeoMerchantDataLoader`、`AerospikeEdgeDemoLoader`、
  `RocksDbStorageIntegrationTest`、`GeoQueryIntegrationTest`、`AerospikeEdgeIntegrationTest`、`*LiveLoadTest`

**schema 资源**（`graph-http/src/main/resources/`）：`payment-graph.json`、`payment-graph-rocksdb-{inline,edge}.json`、
`geo-merchants-rocksdb-{inline,edge}.json`、`payment-graph-as-edge.json`、`kg-entity-graph.json`、
`hnsw-sift.json`、`hnsw-dynamic.json`；profile：`application-rocksdb.yml`、`application-hnsw.yml`。

---

## 7. 文档索引

| 文档 | 内容 |
|---|---|
| `README.md` | 模块表、四种存储对比、Geo search、时间窗大点读法、两种查询语言 |
| `CONTEXT.md` | **第 21 节** RocksDB 两实现；**第 22 节** GEO；**第 23 节** 四项修正；**第 24 节** UI 三处；**第 25 节** Aerospike-Edge（含 25.7 TODO）；**第 26 节** UI 居中 + 大点有界扩展；更早：第 15–20 节（HNSW/向量索引/UI） |
| `HANDOVER.md` | 本文件（结论 / 现场 / 明天第一件事 / 命令 / 设计要点 / 限制 / 代码地图） |

---

## 8. 怎么查历史（含完整思考与输出）

**结论类**：本文件 §0（今天的结论）+ `CONTEXT.md` §21–26（设计与实测）+ `git log --oneline --since=…`（改动本身）。
这三处只有**结论**，没有推理过程。

**完整过程类**：pi 把每一轮会话存成 JSONL（含用户输入、模型的 thinking、每次工具调用与输出、以及历次压缩摘要）：

```
C:\Users\Pengshan Zhang\.pi\agent\sessions\--D--workspace-eclipse-PhoenixGraph--\
    2026-09-13T14-07-54-067Z_01a09b18-92d3-70dc-ad7c-211a2b191f78.jsonl    ← 本次会话（跨 09-13 … 09-17）
```

- 记录类型：`message`（`user` / `assistant`（`text` + `thinking` + `toolCall`）/ `toolResult`）、`compaction`（6 条，每次上下文压缩的完整摘要）、
  `model_change`、`custom`；本会话共 **5256 条**。
- 在 pi 里看：`/session`（当前会话信息）、`/tree`（对话树、可跳回任意节点）、`/resume` 或 `pi -r`（历史会话选择器）、
  **`/export out.html`（导出成可读 HTML，含全部思考与工具输出）**、`/share`（私有 gist 链接）。
- 转成 Markdown（本仓库自带工具，无需联网）：

```bash
# 今天一整天（本地时区），含思考、工具调用与输出、压缩摘要
node tools/session-export.mjs --from 2026-09-17 --to 2026-09-17 --out .sessions/2026-09-17-full.md

# 只看问答（思考压到 1.2k 字符、不含工具输出）
node tools/session-export.mjs --from 2026-09-17 --thinking brief --tools none \
     --out .sessions/2026-09-17-answers-only.md

# 常用开关：--session <路径|id|latest> --thinking full|brief|none --tools full|brief|none --max-chars N
```

已导出到 `'.sessions/`（已 gitignore，不入库）：`2026-09-17-full.md`（1.4 MB，14 个用户轮次 / 965 个助手轮次 / 369 段思考 /
368 段工具输出 / 2 次压缩摘要）、`2026-09-17-answers-only.md`（0.6 MB，速读用）。
