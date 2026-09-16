# Handover — resume here

状态：**vector search 已端到端可用**（HNSW 引擎 + schema 声明 + 写入即索引），
今天的两个提交已推到 `develop`，工作树干净。

- `7d6f147` — HNSW 向量索引：分层图 + `nearest(k)` 查询（手工/加载器建索引）
- `e6af92e` — schema `isVector` 声明 + 自动建层边 + 写入即索引 + 入口点 1 分钟缓存

> 详细设计与实测数据在 `CONTEXT.md`：**第 15 节**（HNSW 引擎、SIFT Demo、召回实测）、
> **第 16 节**（schema 驱动、自动建边、写入路径、缓存、配置、验证）。

---

## 1. 明天第一件事：向量检索 + 属性过滤

**要回答的问题**：在满足属性条件（`status=ACTIVE`、`category=X`、`price<100` …）的点里，
**如何保证**能查到最近的约 20 个相似向量？

**为什么现在的写法不保证**：`…nearest(20).has('category','x')` 是"先取 20 个再过滤"，
命中数取决于属性在这些邻居里的分布，极端情况一个都不剩。

**四条路**（`CONTEXT.md §16.7` 亦有）：

1. **放大候选 + 后过滤** `nearest(k×f).has(...).limit(k)` —— 今天就能用；`f` 难定，最坏退化。
2. **把过滤下沉进搜索（推荐正解）**：给 `HnswIndex.search` 加 `Predicate<Object>`（满足性判据，
   属性可批量读），不满足的候选不进结果表但**仍参与扩张**；`ef` 按选择率放大到约 `k/selectivity`，
   并在候选表提前收敛时给出策略（继续扩张 / 返回不足 k 个 + 标记）。
   落点：`HnswIndex.searchLayer` / `selectNeighbours`、`GremlinSteps.nearest`
   （后面紧跟 `has`/`filter` 时自动把条件传下去）、`GremlinGraphQuery.neededProperties`。
3. **预过滤 + 候选集内精确检索**：先用属性把候选压小再精确算，最准；需要属性索引支撑。
4. **向量+属性联合索引**（按热点维度分层建索引）：成本高，只在维度固定且少时值得。

**建议顺序**：先做 (2)，把 (1) 作为文档化兜底方案；(3)(4) 视过滤维度稳定性再定。

从零开始的话，可先跑一个对照实验量化问题：在 10k SIFT 图上加一个低基数属性（如 `bucket=0..9`），
比较 `nearest(20).has(bucket)` 与"下沉过滤 + ef 放大"的召回/延迟。

---

## 2. 环境启动

```bash
# 1) Aerospike（本机，namespace=test）
#    已在跑就不用动：localhost:3000

# 2) 打包
./mvnw -q -pl graph-http,graph-web -am -DskipTests package

# 3) 三个实例（8081 = 多图读；8083 = 写入即索引 Demo；8080 = web UI）
java -jar graph-http/target/graph-http-0.1.0-SNAPSHOT.jar                     # 8081
java -jar graph-http/target/graph-http-0.1.0-SNAPSHOT.jar \
     --spring.profiles.active=hnsw-dynamic --server.port=8083                 # 8083
java -jar graph-web/target/graph-web-0.1.0-SNAPSHOT.jar                       # 8080

# 4) 健康检查
curl -s http://localhost:8081/api/v1/graphs
```

profile：默认（8081）同时挂 `payment-graph` / `kg-entity-graph` / `hnsw-sift` / `hnsw-dynamic`；
`--spring.profiles.active=hnsw` 只挂 `hnsw-sift`；`=hnsw-dynamic` 把动态向量图作为**主图**（写接口在这里）。

### Aerospike 数据（namespace `test`）

| set | 内容 |
|---|---|
| `http_demo_v1` | payment-graph 星形图（Account/IP/Phone） |
| `kg_nell995_v2` | NELL-995 知识图谱（1 种点 `Entity` + 1 种边，按 relation 分 10 片），59k 点 / 150k 边 |
| `hnsw_sift_v1` | **SIFT small 10,000×128**，`Vector` 点 + `vector_0..vector_14`，**10,001 个点**（多出的 `20000` = 行 5000 的副本，REST 写入 Demo） |
| `hnsw_dynamic_v1` | 600×128，`Doc` + `embedding`（schema **零层边**，服务启动时自动建 `embedding_0..embedding_10`） |
| `hnsw_meta` | 每个向量属性一条元数据记录：`hnsw:Vector`、`hnsw:Doc`…（入口点 + metric/m/ef/dims/size/topLevel） |

---

## 3. 常用命令

```bash
# 全部测试
AEROSPIKE_HOST=localhost AEROSPIKE_PORT=3000 AEROSPIKE_NAMESPACE=test ./mvnw test

# HNSW 集成测试（复用已建索引 ≈2min；HNSW_FORCE=true 重建 ≈15min）
AEROSPIKE_HOST=localhost AEROSPIKE_PORT=3000 AEROSPIKE_NAMESPACE=test \
HNSW_DATASET_DIR=/tmp/siftsmall_dl/siftsmall HNSW_VERTEX_SET=hnsw_sift_v1 \
  ./mvnw -pl graph-integration-test -am test -Dtest=HnswGraphIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
#   其它 env：HNSW_LIMIT(0=全部) HNSW_M HNSW_EF_CONSTRUCTION HNSW_EF_SEARCH
#             HNSW_DYNAMIC_SET(hnsw_dynamic_v1) HNSW_DYNAMIC_LIMIT(600)

# 数据集 / schema 生成
tools/download-siftsmall-dataset.sh                      # → .datasets/siftsmall
node tools/generate-hnsw-schema.js --size 10000 --m 15 --dimensions 128    # hnsw-sift.json
node tools/generate-hnsw-schema.js --size 600 --layers 0 --vertex Doc --vector-property embedding \
  --graph hnsw-dynamic --id 8 --metric COSINE --m 8 --ef-construction 64 --ef-search 24 \
  --out graph-http/src/main/resources/hnsw-dynamic.json

# 前端改动后（frontend/public → graph-web/src/main/resources/static）
cp graph-web/frontend/public/{index.html,css/style.css,js/app.js} graph-web/src/main/resources/static/
```

### 演示查询

```groovy
// 10k SIFT（8081，graphName=hnsw-sift）
g.V('4242','Vector').nearest(20)                 // 20 个最近向量（19/20 与 ground truth 精确一致，多出的是写入 Demo 的副本）
g.V('4242','Vector').nearest(20, 200)            // 候选表 200：recall@20 0.983 → 0.989
g.V('4242','Vector').nearest(20).values('name')
g.V('4242','Vector').both().both()               // 层次结构可视化（走全部层边）

// 动态向量图（8083 可写，graphName=hnsw-dynamic）
// 写入：POST /api/v1/vertices  {id, vertexSchemaName:"Doc",
//        properties:[{index:1,value:"name"},{index:2,value:[...128 floats]}]}
g.V('900','Doc').nearest(20)                     // 写完立刻可查
```

---

## 4. 关键设计要点（改代码前先看这些）

- **层方案**：`topLevel(size) = ceil(log2 size)`，层 `0..topLevel` → `topLevel+1` 个双向边类
  （4096 → `vector_0..vector_12`；10k → `..vector_14`）。点的层 = `floor(-log2 u)`（id 的哈希决定，
  确定性）→ 底层含全部点、顶层约 1 个点。
- **边类命名** = `<向量属性名>_<层>`（`VectorIndexSchemas.layerEdgeName`），**id 稳定**（已存在的保留，
  新的取 max+1）—— 因为 Aerospike bin 名是 `ve_<edgeSchemaId>_<dir>`。
- **每层度数**：HNSW 自己裁到 `m`（第 0 层 `2m`），schema 声明 `2×` 作兜底。
- **写入批量**：一次插入的所有 link/unlink 缓冲后**各一次** `upsertEdges`/`deleteEdges`
  （store 内部按 (vertex,bin) 聚合，每 bin 一次读改写）—— 构建速度关键，别退回逐边写。
- **读路径**：搜索每次展开 ≈ 2 次往返（邻居列表 + 向量批读），10k 图上 ef=30 ≈ 65ms/查询。
- **入口点**：单条 Aerospike 记录（`hnsw_meta/hnsw:<Type>`），`CachedHnswMetadataStore` 缓存
  1 分钟；每次搜索前合并远端入口点 → 多实例共享同一索引。
- **配置优先级**：`metric`/`m`/`efConstruction` 建过索引后以**元数据**为准；`efSearch` 始终取
  **schema**（默认 30），`nearest(k, ef)` 可临时覆盖。
- **构建是单写者**：`HnswIndex.insert` 加了 `synchronized`；查询用各自会话，可并发。

---

## 5. 已知问题 / 限制

1. **既有测试冲突（非本次引入）**：`./mvnw test` 全量跑时 `AerospikeIntegrationTest` 报
   `Duplicate key e2` —— `graph-http` 的 `QueryApiIntegrationTest` 通过 REST 往共享默认 set 写了
   Payment `e2`（干净 worktree 上同样复现）。修法二选一：给写接口测试单独 set；或各集成测试类
   各用独立 set（给 `AerospikeIntegrationTest` 直接 truncate 会误伤同模块其它测试）。
2. **没有 un-index**：删除点后邻居列表里仍可能残留该 id（`nearest` 读不到会跳过，不报错），
   元数据 `size` 也只增不减 → 重建请用 `HNSW_FORCE=true`（会 truncate + 重新播种 + 重建）。因此
   10k 图现在是 10,001 个点，集成测试按 `existing.size() >= size` 判定可复用。
3. **一个点类型多个向量属性**：查询步骤只用第一个（`VectorIndexManager.indexesOf(graph)` 的 key 是点类型）；
   其余用 `AerospikeHnswIndexFactory.open(..., property, ttl)` 单独取（元数据 key 为 `hnsw:<Type>:<prop>`）。
4. **暂不支持**：`latency` 高时的多 Aerospike 节点并行写、删除后回收层边、`efConstruction` 在线调大。
5. 前端 `graph-web/frontend/public` 与 `graph-web/src/main/resources/static` 仍是**拷贝关系**（需手动同步）。

---

## 6. 代码地图（今天的改动）

```
graph-schema/…/VectorIndexConfig.java      向量属性配置（metric/m/efConstruction/efSearch + 默认值校验）
graph-schema/…/VectorIndexSchemas.java     isVector 检测、层边命名、按需建边（id 稳定）
graph-schema/…/PropertySchema.java         isVector()/vector(…)/vectorConfig()
graph-schema/…/json/{PropertyDto,SchemaJsonCodec}.java   isVector/metric/m/efConstruction/efSearch 读写
graph-schema/…/SchemaValidator.java        向量属性校验（数字 list、list<object> warning、多向量 warning）

graph-hnsw/…/HnswIndex.java                建/查核心；dynamicSize + provisioner + 入口点合并刷新
graph-hnsw/…/HnswLayerProvisioner.java     按需建层的回调端口
graph-hnsw/…/CachedHnswMetadataStore.java  入口点缓存（TTL=1min，write 直写穿透）
graph-hnsw/…/HnswLevels.java               层方案（属性名感知的边名）
graph-hnsw/…/HnswStore|GraphQueryHnswStore 邻居读写端口（linkBatch/unlinkBatch 批量）

graph-query-aerospike/…/AerospikeHnswStore.java          层边读写（读容错：层不存在=空）
graph-query-aerospike/…/AerospikeHnswMetadataStore.java  元数据记录（forVertex/自定义 key）
graph-query-aerospike/…/AerospikeHnswIndexFactory.java   schema 驱动打开索引（provisioner + TTL + 配置合并）

graph-query-gremlin/…/GremlinSteps.java    nearest(k[,ef]) 步骤（顺带记录底层连边供 UI 画图）
graph-http/…/service/VectorIndexManager.java  每图每属性的索引（读写共用）、启动建层、写入后索引
graph-http/…/service/GraphService.java        写路径触发索引；QueryService 注册索引给引擎
graph-http/src/main/resources/hnsw-sift.json / hnsw-dynamic.json / application*.yml

graph-integration-test/…/HnswGraphIntegrationTest.java   10k 加载建图 + 写入驱动两场景
tools/generate-hnsw-schema.js, tools/download-siftsmall-dataset.sh
```

重启前若改了 schema JSON 或 `application*.yml`，记得重新 `package` 再启动（jar 里带资源）。
