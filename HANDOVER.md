# Handover — resume here

状态：**vector search 已端到端可用**（HNSW 引擎 + schema 声明 + 写入即索引 +
**属性过滤下沉** + **索引重建**），工作树干净，`./mvnw test` **全绿**。

- `7d6f147` — HNSW 向量索引：分层图 + `nearest(k)` 查询（手工/加载器建索引）
- `e6af92e` — schema `isVector` 声明 + 自动建层边 + 写入即索引 + 入口点 1 分钟缓存
- `ec69940` — `HANDOVER.md`
- 追加（第 17 节）：重复边 id 记 bizlog、`POST /api/v1/vector-index/rebuild` 重建索引、
  一个点类型只能有一个向量属性、demo 数据加 `bucket`(INT)/`category`(STRING) 属性、
  `efSearch` 默认 50、`has`/`filter` 下沉进 HNSW 搜索
- 追加（第 18 节）：有向边布局验证（source 存 out-bin、target 存 in-bin，两跳四组合全通）、
  修复 `@PathVariable` 未命名导致 `GET|DELETE /vertices/{id}` 等接口 400/500 的 bug

> 详细设计与实测数据在 `CONTEXT.md`：**第 15 节**（HNSW 引擎、SIFT Demo、召回实测）、
> **第 16 节**（schema 驱动、自动建边、写入路径、缓存、配置、验证）、
> **第 17 节**（过滤下沉、索引重建、schema 约束、demo 属性、实测对比）。

---

## 1. 明天第一件事：过滤结果的可控性与代价

**现状（已完成）**：`g.V(id,'Vector').nearest(20).has('bucket', 3)` 会把 `has` 下沉进搜索，
返回"最近的 20 个 **bucket=3** 的向量"；实测 10k SIFT（10% 选择率，ef=50）：
**recall@20 = 0.998 / 419ms**，而"先取 20 再过滤"只有 **0.143**（`nearest(20)` 里平均只有 4 个命中）。

**下一步可选**：
1. **选择率来源**：现在靠"声明值 + 实测通过率 + 翻倍重试"。如果给 `HnswFilter` 传准确的选择率
   （属性统计/采样），第一次就能取对候选表大小，省掉重试（现在最多 4 次遍历）。
2. **多条件组合的选择率**：`has(a).has(b)` 现在按独立假设乘不起来（实测通过率会纠正），
   但可以先按单项选择率估个初值。
3. **带过滤的召回/延迟目标**：把 `maxEf` 暴露成查询参数（如 `nearest(20, 200, 'maxEf=1000')`），
   让调用方能在"准"与"快"之间选。
4. **属性与向量相关时的表现**：17.4 记了一个坑 —— 属性若与向量空间强相关（合成数据里一个簇
   共用一个 bucket），任何过滤方案都会退化。真实数据要先看相关性，再定过量取数倍率。
5. **批量重建的并行化**：`rebuild` 现在是顺序插入（601 点 27s / 10k 点约 15 分钟），
   可以按层并行或按批次并行（层内边互不干扰，但同一 bin 有竞争）。

其它未做：删除点后的 un-index、`nearest` 的多向量属性支持（已被 schema 校验禁止）。

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
g.V('4242','Vector').nearest(20)                 // 20 个最近向量
g.V('4242','Vector').nearest(20, 200)            // 候选表 200：recall@20 0.988 → 0.989
g.V('4242','Vector').nearest(20).has('bucket', 3)      // 下沉过滤：20 个全都是 bucket=3（recall 0.998）
g.V('4242','Vector').nearest(20).has('category','music')  // 字符串属性同理
g.V('4242','Vector').nearest(20).has('bucket', 3).values('name')
g.V('4242','Vector').both().both()               // 层次结构可视化（走全部层边）

// 动态向量图（8083 可写，graphName=hnsw-dynamic）
// 写入：POST /api/v1/vertices  {id, vertexSchemaName:"Doc",
//        properties:[{index:1,value:"name"},{index:2,value:[...128 floats]}]}
g.V('900','Doc').nearest(20)                     // 写完立刻可查
```

### 向量索引运维接口

```bash
# 每个向量索引的配置与状态（graph/vertex/property、metric/m/ef、层数、size、topLevel、入口点）
curl -s http://localhost:8081/api/v1/vector-index

# 重建：清空层边数据 + 删元数据 + 重新加载数据建新边（601 点约 27s，10k 点约 15 分钟）
curl -s -X POST http://localhost:8083/api/v1/vector-index/rebuild \
  -H 'content-type: application/json' -d '{"graph":"hnsw-dynamic"}'
# → [{"graph":"hnsw-dynamic","vertex":"Doc","property":"embedding","vertices":601,
#     "layers":11,"links":13821,"pruned":7468,"seconds":26.9}]
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

1. ~~既有测试冲突 `Duplicate key e2`~~ **已修**：`GraphVerifier` 遇到重复 edge id 时
   **后写胜出 + 记一条带完整两边数据的 bizlog**，`./mvnw test` 现在**全绿**（CONTEXT 第 17.1 节）。
   共享 set 里的脏数据还在，想彻底干净可以给写接口测试单独 set（未做，不影响测试）。
2. **没有 un-index，但有 rebuild**：删除点后邻居列表里仍可能残留该 id（`nearest` 读不到会跳过，
   不报错）；元数据 `size` 是写入路径每次插入都会发布的快照。要清理结构就用
   `POST /api/v1/vector-index/rebuild` —— 它**走层 0 + 扫描**重新加载全部被索引的点
   （`sendKey` 已打开，新写入的记录扫描可寻址），删层边、删元数据、重建、换掉缓存索引；
   10k 图目前 10,001 个点（多出的一个是写入 Demo），集成测试按 `existing.size() >= size` 判定可复用。
3. **一个点类型只能有一个向量属性**：schema 校验直接报错（CONTEXT 17.3）；`hnsw:<Type>:<prop>`
   的多属性 key 逻辑保留为防御性代码。
4. **过滤查询的代价**：候选表按 `k/selectivity` 放大（10% 选择率、k=20、ef=50 → 约 3× 普通查询延迟）。
   选择率若由调用方给准（属性统计/采样），可省掉"不够再放大"的重复遍历。
5. **暂不支持**：多 Aerospike 节点并行写、`efConstruction` 在线调大、重建的并行化。
6. 前端 `graph-web/frontend/public` 与 `graph-web/src/main/resources/static` 仍是**拷贝关系**（需手动同步）。

---

## 6. 代码地图（今天的改动）

```
graph-schema/…/VectorIndexConfig.java      向量属性配置（metric/m/efConstruction/efSearch + 默认值校验）
graph-schema/…/VectorIndexSchemas.java     isVector 检测、层边命名、按需建边（id 稳定）
graph-schema/…/PropertySchema.java         isVector()/vector(…)/vectorConfig()
graph-schema/…/json/{PropertyDto,SchemaJsonCodec}.java   isVector/metric/m/efConstruction/efSearch 读写
graph-schema/…/SchemaValidator.java        向量属性校验（数字 list、list<object> warning、多向量 warning）

graph-hnsw/…/HnswIndex.java                建/查核心；dynamicSize + provisioner + 入口点合并刷新
                                           +  filteredPass/searchLayerFiltered（has/filter 下沉）
graph-hnsw/…/HnswFilter.java               下沉谓词端口（propertyNames / selectivity / test / maxEf）
graph-hnsw/…/HnswElements.java             elements(ids, extraProperties)：向量+过滤属性一次批读
graph-hnsw/…/HnswLayerProvisioner.java     按需建层的回调端口
graph-hnsw/…/CachedHnswMetadataStore.java  入口点缓存（TTL=1min，write 直写穿透）
graph-hnsw/…/HnswLevels.java               层方案（属性名感知的边名）
graph-hnsw/…/HnswStore|GraphQueryHnswStore 邻居读写端口（linkBatch/unlinkBatch 批量）

graph-query-aerospike/…/AerospikeHnswStore.java          层边读写（读容错：层不存在=空）
graph-query-aerospike/…/AerospikeHnswMetadataStore.java  元数据记录（forVertex/自定义 key）
graph-query-aerospike/…/AerospikeHnswIndexFactory.java   schema 驱动打开索引（provisioner + TTL + 配置合并）

graph-query-gremlin/…/GremlinSteps.java    nearest(k[,ef]) 步骤（顺带记录底层连边供 UI 画图）
graph-http/…/controller/VectorIndexController.java  GET /api/v1/vector-index + POST …/rebuild
graph-http/…/service/VectorIndexManager.java  每图每属性的索引（读写共用）、启动建层、写入后索引、rebuild
graph-http/…/service/GraphService.java        写路径触发索引；QueryService 注册索引给引擎
graph-http/src/main/resources/hnsw-sift.json / hnsw-dynamic.json / application*.yml

graph-integration-test/…/HnswDatasetLoader.java          向量 + bucket/category 属性导入（seedAttributes）
graph-integration-test/…/HnswGraphIntegrationTest.java   10k 加载建图 + 写入驱动 + 过滤对比 三场景
graph-integration-test/…/GraphVerifier.java              重复 edge id → bizlog（不再抛异常）
tools/generate-hnsw-schema.js, tools/download-siftsmall-dataset.sh
```

重启前若改了 schema JSON 或 `application*.yml`，记得重新 `package` 再启动（jar 里带资源）。
