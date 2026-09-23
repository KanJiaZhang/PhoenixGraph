# Handover — resume here

> 本文件是**断点续做手记**：今天做了什么（结论）、现场怎么恢复、明天从哪继续。
> 详细设计与实测数据在 `CONTEXT.md`：**2026-09-21 新增 §47–§59，2026-09-23 新增 §60–§68**（§47 仪表盘七问；§48 四问（二）；§49 三问（三）；§50 五问（四）；§51 四问（五）；§52（六）：Aerospike-Edge 大点冷/近线/在线三层；§53（七）：stale 写统一 / 结果面板复核 / 150 两跳扩展 + benchmark；§54（八）：表达式字符串函数 / display `$id` / count 交给服务端；§55（九）：用户报的 12 件事；§56（十）：算法 / legend·页签·示例 / 子图把 filter 掉的边留下；**§57（十一）：闭包 if/else 与集合、`values()` 的元素自身列、空答案不画、面板只报数目 + 135·150 两跳 benchmark**；**§58（十二）：fuzzy 的 xia 账号 / 图实例收敛到四个 / payment-graph 向量 / RocksDB-Edge GEO / 去掉 FuzzyPattern**）；
> 过程记录：**`DAILY-2026-09-21.md`**（§1–§22，09-21 的十三批）与 **`DAILY-2026-09-23.md`**（§1–§14，09-23 的九批；**§14 是这一天跨批的思考主线**：测量要交错、先怀疑观测、静默错答案比报错糟、改依赖模块先 install、从声明类型推断、数据可复现、报告自解释、用户语法即需求、一次一个变量）。

**最后一次会话：2026-09-23（周三）· 一天九批（第十四至二十二批）· 全量 `./mvnw test` 绿（0 失败 / 0 错误；gremlin 194 / fuzzy 49 / http 71 / schema / gemini / hnsw / …）**
**九批一句话：十四 面板单元素 + 索引坏掉要报错 + 编译缓存上限 + RocksDB-Edge 的 HNSW（§60）· 十五 同一个两跳语句在 RocksDB-Edge 上 100 id×8 轮（§61）· 十六 geo+向量+边组合语句 100 次（§62）· 十七 `outE` vs `out` 各 100 次（交错测）（§63）· 十八 优化掉中间多余的点/边属性读（§64）· 十九 建模页七项改进（§65）· 二十 RocksDB-Edge 四条查询能力（§66）· 二十一 TinkerPop 写法四条（§67）· 二十二 事件序列模式（§68）。**后六批里三批是性能测试、三批是查询能力，测量方法本身也踩过坑（见 `DAILY-2026-09-23.md` §14）。**
**用户原话、被否掉的方案、实测数字：`DAILY-2026-09-21.md` §1–§22 与 `DAILY-2026-09-23.md` §1–§14。**
**现场（2026-09-23 收工时，可直接续做）：8081 `graph-http` + 8080 `graph-web` + 8083 `graph-http --profile hnsw-dynamic` 三个 JVM 都在跑，Aerospike 容器在跑；启停在下一节。**
**8081 服务 4 个图：`payment-graph`（Aerospike，`http_demo_v3`）、`payment-graph-as-edge`、`payment-graph-rocksdb-edge`（`.rocksdb/payment-graph-rocksdb-edge`）、`kg-entity-graph`；其余 7 个在 `application.yml` 里注释保留。**
**数据与索引状态（09-23 收工）：`http_demo_v3` 有 fuzzy 环（`fz_a1..fz_a7`，含 xia1999）+ 5 条事件会话（`ev_a1..ev_a5`，由 `FuzzyLinkDemoLoader` 播种，跑 `./mvnw test` 或 `FuzzyLinkIntegrationTest` 即重建）与它们的连接节点；`payment-graph` 与 `payment-graph-rocksdb-edge` 的 HNSW 索引都已建（152 / 51 点）；`fuzzy-patterns.json` 有 4 个模式（#0/#1 平铺，#2/#3 事件序列）；UI 的 `payment-graph` 示例查询里有 4 条序列例子（已实测可跑）。**
**起停一条命令：`tools/start-phoenixgraph.sh [--no-build] [--with-hnsw]`；日志 `.logs/<服务>.out`；停 `tools/stop-phoenixgraph.sh`。改了 UI/资源/引擎一定要**重新建包**（服务读的是 jar 里的 `static/**`）。**
**下一次入口（按优先级）：1) §68 的序列模式收尾 —— 用户给的原始时间戳与他们的 pattern 顺序不符（排序后是 P→D→L→B），要么按用户时间戳调整 pattern 的步骤顺序、要么把一个"不要求先后、只要四个事件都存在且相邻 ≤100s"的变体做出来（目前是严格按时间顺序匹配）；2) §66.2 给 RocksDB-Edge 加 `edge-id:<类>` 列族做边 id 索引（现在 `g.E($id,类)` 是 O(类内边数) 顺序扫描，200 条 6–7 ms）；3) §65 的图下拉只列"被服务的"图（重启后自建的 `*.json` 会消失，应该同时扫工作目录）；4) §62.3 剩下的：`nearest(10)` 对 10 个 traverser 做 10 次独立 HNSW 搜索（这条语句 78% 的时间），要共享/合并搜索；5) §57.9/§56.7/§51.6/§53.4 的旧清单（`new` 只支持三种集合、barrier 仍是批量大小别名、投影上限 40、平行边"×N"、`vt` 进边读、limit 有序表示、RocksDB stale 不一致、UI 镜像边折叠、`map` 聚合）。**

**明天开机（照抄三条命令）**

```bash
tools/start-phoenixgraph.sh --no-build --with-hnsw   # Aerospike 容器 + 8081/8080/8083；改过代码去掉 --no-build
curl -s localhost:8081/api/v1/graphs | head -c 200   # 应看到 4 个图
curl -s localhost:8081/api/v1/vector-index           # 应看到 payment-graph 152 点 / rocksdb-edge 51 点
node .logs/run-ui-examples.js                        # UI 里那四条事件序列示例的实际返回（应看到 fl_2_10.122_notmycard 等）
node .logs/verify-sequence-final.js                  # 序列模式全貌：原始时间戳 vs 有序、每个账号的组、组成员
```

**数据/索引丢了的恢复顺序**（缺哪步补哪步；RocksDB 必须先停服务，服务占着目录）：

```bash
# 1. Aerospike 演示集 http_demo_v3（含 accountVector）
./mvnw -pl graph-http test -Dtest=QueryApiIntegrationTest#demoSharedGraphViaApi -Dsurefire.failIfNoSpecifiedTests=false
# 2. fuzzy 环（含 fz_a7 / xia1999）—— 会覆盖 http_demo_v3 里的这些点
./mvnw -pl graph-integration-test test -Dtest=FuzzyLinkIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
# 3. RocksDB-Edge（含 accountVector + location）
tools/stop-phoenixgraph.sh
./mvnw -pl graph-integration-test test -Dtest=RocksDbLiveLoadTest#loadBothInstancesIntoTheConfiguredDirectory \
       -Drocksdb.live.dir=D:/workspace/eclipse/PhoenixGraph/.rocksdb -Dsurefire.failIfNoSpecifiedTests=false
tools/start-phoenixgraph.sh --no-build --with-hnsw
# 4. 向量索引（服务起来后跑一次）
curl -s -X POST localhost:8081/api/v1/vector-index/rebuild -H "content-type: application/json" \
     -d "{\"graph\":\"payment-graph\",\"vertexSchemaName\":\"Account\",\"vectorProperty\":\"accountVector\"}"
```


**工作树状态：2026-09-23 第十四至二十二批已提交（面板单元素、索引坏掉的响亮报错、编译缓存上限、RocksDB-Edge 的 HNSW），`develop` 与 `origin` 同步。**
**第二十二批（§68，序列模式）：`graph-fuzzy` 新增 `FuzzySequence`（步骤/窗口/条件解析 + 按 EventTime 匹配 + 解释）；`FuzzyContext` 支持点号路径与 `events()`（三种上下文形状）；`FuzzyContexts.fromJson` 保留嵌套；`FuzzyExpression` 名字允许 `.`；`FuzzyPattern`/`FuzzyLinks`/`FuzzyController` 按序列分派；`fuzzy-patterns.json` 加 pattern #2/#3（带用户那份 example）。测试 `FuzzySequenceTest`（11）；脚本 `.logs/verify-sequence-final.js`。；**并在 §68.6 补上 UI 可验证的例子**：`payment-graph` 的示例查询里加了 4 条（事件列表 → 组 id / 组内账号 / 模式③ / 某账号属于哪些组），`FuzzyLinkDemoLoader` 现在也播种 5 条事件会话（ev_a1..ev_a5）与它们的连接节点（`FuzzyDemo.DEMO_PATTERNS` 同步加上 2/3），`FuzzyLinkIntegrationTest` 12 用例（新增"事件列表进、组出"，断言组大小 {0=7,1=4,2=2,3=3}）。UI 实测（headless Edge）：下拉出现 4 条，点第一条面板显示 `fl_2_10.122_notmycard`。**
**第二十一批（§67）：`GremlinStepRegistry` 支持 3 参 `has`；`GremlinSteps.hasLabel(label,key,value)` + `standOn(ctx,in,dir)`（边 → 指向/来自的端点）；`GremlinQuery` 新增 `NO_VERTICES`/`NO_EDGES` kind；`GremlinGraphQuery.typedStart` 把"空起点 + 带类型 has"解析成类型扫描 + 条件（类型按 schema 校验、无 schema 跳过、缺类型时给出两种写法）。gremlin 194 用例。脚本：`.logs/rocksdb-edge-caps4.js`、`caps5.js`、`fuzzy-empty-start.js`。**
**第二十批（§66，查询能力）：`GraphQuery.readEdgesByIds`（默认走一遍类、保持顺序、返回两端点；非扫描存储明确报错）+ `GremlinQuery.Source.Kind.EDGES` 与 `GremlinGraphQuery.startEdges`（`g.E($id,类)`，多 id 亦可；`g.E(类)` 仍是扫描）+ 2 个新用例与 1 个改写用例（gremlin 共 190）。验证脚本：`.logs/rocksdb-edge-final.js`、`rocksdb-edge-has.js`、`aerospike-edge-id.js`。**
**第十九批（§65，图管理页）：`SchemaDraft.EdgeDraft` 补出入度（toSchema/toDraft 往返）+ 保留名校验；`GraphWriteController` 新增按图批量写；`ProxyController` 新增批量写与两条删除路由；`builder.html`/`builder.js`/`builder.css` 重写（description 列与输入、id 固定行、出入度置灰说明、重复 id 确认、保存后刷新写入表单、删除后果确认、CSV 导入+模板）。验证脚本：`.logs/verify-builder2.js`、`.logs/verify-csv2.js`。**
**第十八批（§64，优化）：`GremlinSteps.readHits`（geo 命中一次批量读；`readProperties()==null` 时一次都不读）+ 新增 `RocksDbEdgeRowCodec.decodeHeader` / `RocksDbEdgeStore.readNeighbours`（带独立缓存键的 LRU）/ `RocksDbEdgeGraphQuery.readNeighbours(Batch)` + `supportsNeighbourRead`。实测：geo+向量+边 100 次的 kv 读 127→117、geo 步 0.303→0.184 ms、答案不变；两跳流量不变但不再解码 Payment 属性。**
**第十七批（§63，benchmark）：`RocksDbGeoVectorPaymentBenchmarkTest` 扩成两条语句（`outE` / `out`）**交错**跑各 100 次 + 并排对比表 + 两条读轨迹（6 用例）。**关键教训**：先 A 后 B 会得出"B 快 1.47 ms"的假象（JIT 顺序），交错后 A 5.31 / B 5.59 ms、稳态都是 ~4.23 ms。输出 `.logs/bench-out-vs-oute2.log`。**
**第十六批（§62，benchmark）：新增 `graph-integration-test/.../RocksDbGeoVectorPaymentBenchmarkTest`（100 次运行 + 4 段 step 归因 + 读轨迹 + 缓存冷热；4 用例无时间断言）。实测：稳态 ~4.1 ms、向量步占 78%、geo 命中逐个读回是明确的优化点。输出 `.logs/bench-geo-vector6.log`。**
**第十五批（§61，benchmark）：把测量抽成 `graph-integration-test/.../TwoHopValuesBenchmark`（共享 harness：查询/测量/报告/2 个守卫 + `measureStorageSpecific` 钩子），`AerospikeHundredSeedsValuesBenchmarkTest` 与新的 `RocksDbEdgeSeededValuesBenchmarkTest` 各自只提供 store/ids/label；RocksDB 报告多一节存储专属测量（store 边缓存 on/off）。输出：`.logs/bench-rocksdb2.log`、`.logs/bench-asp-after.log`。**
**第十四批（§60）新增/改：`graph-hnsw`（`HnswIndex.verifyLayers` + `searchById` 的文本比较 + 2 用例）；`graph-query-gremlin`（`MAX_COMPILED_QUERIES`/`compiledQueryCount` + `GremlinCompiledQueryCacheTest` 4 用例）；`graph-storage-rocksdb`（`RocksDbHandle.open` 打开目录已有的列族）；`graph-storage-rocksdb-edge`（`RocksDbHnswStore`/`RocksDbHnswMetadataStore`/`RocksDbHnswIndexFactory`/`RocksDbVectorIndexBuilder` + pom 依赖）；`graph-http`（`VectorIndexManager` 支持 RocksDB、`RocksDbGraphStores.edgeStore`、seeder 的 truncate 连元数据一起删）；`graph-integration-test`（`RocksDbVectorIndexIntegrationTest` 6 用例）；UI 面板逻辑与示例查询。验证脚本：`.logs/panel-fn-check.js`、`.logs/rocksdb-live.js`。**
**第十三批收尾（§59.7-59.8）：记录器改为从声明类型推断（泛型参数/返回类型，删掉手写下标表）；加两个守卫用例（`everyStoreReadIsClassifiedFromItsDeclaredTypes`、`theRecordedHitsAgreeWithTheStorageCounters`）；种子 id 排序让 workload 可复现（`.logs/bench-det1.log` 与 `bench-det2.log` 逐字节相同）；产品侧新增 `AerospikeReadKind` + `AerospikeReadKindTest`（7 个用例）把"边读/点读"的指标分类从名字前缀改成 bin 优先。**
**第十三批（§59，性能测试）：新增 `graph-integration-test/.../AerospikeHundredSeedsValuesBenchmarkTest`（6 个用例：100 个有效 id x 8 轮 = 800 次执行；记录每次存储调用的批大小与属性名；缓存开/关 A/B；batch/barrier 扫描；投影 vs 整顶点对照）。完整输出留在 `.logs/bench-final2.log`。**
**第十二批（§58）：`FuzzyLinkDemoLoader` 加 `fz_a7/xia1999` 并停写 pattern 顶点；`FuzzySchema.isSupported` 放宽；`payment-graph.json` 删 `FuzzyPattern` 顶点 + Account 加 `accountVector`；`payment-graph-rocksdb-edge.json` 的 Account 加 `accountVector` + `location`；`application.yml` 的 graphs 收敛到三个（其余注释保留）；`QueryApiIntegrationTest.accountProps` 加向量；`PaymentGraphRocksDbLoader` + 新的 `AccountProfiles`（确定性向量 + 四片区坐标）；UI 示例查询加向量/GEO 样本。详见 `CONTEXT.md` §58 / `DAILY` §20。**
**第十一批（§57）新增/修改：`GremlinQuery.indexOfLogicBrace` 深度判定；`GremlinExpression.program()` 改为按序执行 + `ifStatement`/`block`/`newCollection`/`collectionMethod` + `FRAME`（ThreadLocal per-element）；`GremlinElementExpression` 的伪键（`@createdTime`/`@lastUpdatedTime`/`@source`/`@target`）+ `value(...)`/`isPseudoName(...)`；`GremlinSteps.values` 用 `GremlinElementExpression.value`；`GremlinGraphQuery` 规划器跳过伪键 + 空答案→空子图；UI：面板只报数目 + 元素答案也报、`propertiesOfElement` 从子图补属性。测试：`GremlinClosureStatementTest`（11）+ `GremlinGraphQueryTest`（+2）。benchmark：`.logs/bench-two-hop.js`。详见 `CONTEXT.md` §57 / `DAILY` §19。**
**第十批（§56）新增/修改：`GremlinContext.recordHopEdge` + `buildSubgraph` 的“顶点跳的边只随它到达的点存活”规则（+1 引擎用例）；UI：`largestTypeSubgraph`（异构图投影 + 提示 + `algo-out` 变灰）、初值坐标改稳定哈希、legend 计数与空行、结果页签小圆点（不再自动跳）、示例下拉默认隐藏、重复行提示。验证脚本：`.logs/verify-algorithms.js`（12 项）、`.logs/verify-ui2.js`。详见 `CONTEXT.md` §56 / `DAILY` §18。**
**第九批（§55，用户报的 12 件）新增/修改：`GremlinQuery.stripComments` + `isCountStep`/`size` 注册；`QueryService.Answer`/`queryCapped`/`cap` + `phoenixgraph.query.max-response-items` + `QueryController` 的 `X-PhoenixGraph-Truncated`；UI：结果页签收元素列表（三列）、统计页签按类型明细、legend 边条目、搜索上下文/无命中提示、`queryFailed` 清画布、单文件 CSV、示例双语标题、Cypher 隐藏、表格 2000 行上限、截断响应提示。详见 `CONTEXT.md` §55 / `DAILY` §17。**
**第八批（§51.6 / §53.4 的“明天要做”）新增/修改：`GremlinExpression`（`stringMethod`：字符串/数值函数）+ `GremlinElementExpression.resolveKey`（`value('id')`/`value('label')`）+ `GremlinStringFunctionTest`（7）；`VertexSchema.displayName`/`DISPLAY_ID` + `VertexSchemaDto.displayName` + `SchemaJsonCodec` + `DisplayPropertyTest`（+2）+ UI `displayLabel` 的 `$id`；`AerospikeOperations.mapSize`/`mapSizes`（服务端 `MapOperation.size`，记读）+ `AerospikeEdgeStore.countEdges/countEdgesBatch` 的短路径。详见 `CONTEXT.md` §54 / `DAILY` §16。**
**第七批（#23/#25/#24/#26）新增：`WriteOutcome.SUCCESS_STALE`（stale 写统一）、`AerospikeTwoHopExpansion`（150 两跳扩展 loader，独立 set）+ `AerospikeTwoHopExpansionBenchmarkTest`（200→5000 五档 benchmark）+ `*LiveLoadTest`（种 `payment_twohop_exp`）。详见 `CONTEXT.md` §53 / `DAILY` §15。**
**第六批（Aerospike-Edge 大点三层）新增类：`EdgeTiering`（big 判定 + 分层路由）、`AerospikeNearline`（近线布局/分区数学）、`AerospikeNearlineStore`（TTL 7 天近线 KV + 索引）、`AerospikeVolumeStore`（按月/年 volume）、`AerospikeEdgeOfflineJob`（离线：volume + 预划分 + 冷 delta + big 判定）；`AerospikeEdgeChunks.buildLevels` + `AerospikeEdgeChunkStore.rewriteWindow`（冷 delta）；`AerospikeEdgeGraphQuery` 四参构造器启用分层读（三参旧构造器不变=兼容）。详见 `CONTEXT.md` §52 / `DAILY` §14。**

**上一次会话：2026-09-18（周四，约 17 个提交）· 工作树干净 · 全项目编译通过 · 受影响的模块测试全绿**
**（`graph-gemini` 26 + `graph-query-gremlin` 115 + `graph-http` 68 + `graph-fuzzy` 37；服务在跑，见 §1.1）**
**下一次从 §2「明天第一件事」第 1 条开始：把类起点接到计数改写（`g.V('Account').count()` 不构对象），以及 `g.V().hasLabel(...)` /`bothE()` 全类扫描。**
**（扫描端口已完成：`g.E('Payment').pagerank(DIST)`、`g.V('Account').filter(...).bothE('Payment').cc(DIST)` 在 RocksDB-Edge 上实测可用，见 §0.2。）**

> 2026-09-18 新增（**最新**）：**从「一个类」开始查** —— `GraphQuery.supportsScan()` + `scanVertices/scanEdges`
> （**RocksDB-Edge 实现了它**：一个类 = 一个 column family = 一次迭代器；Aerospike / RocksDB-Inline 保持「只按 id 读」并**明确报错说该用哪个 store**）；
> Gremlin 支持 `g.V('Account')` 与 `g.E('Payment')` 两种起点；顺手修掉 **PageRank 未归一化**（真数据 sum 3.7e6 → 1.0，
> 与内存版 1e-18 一致）与「fat jar 嵌了旧模块 jar」的坑。详见 `CONTEXT.md` **§37** 与 `DAILY-2026-09-18.md` **§8**。

> 2026-09-18 新增（**上一段**）：**分布式图算法（Gemini 复刻）** —— 新模块 `graph-gemini`（chunk 分区 +
> 双份边表示 + 稀疏/稠密自适应 + signal/slot + HTTP worker + LOCAL/PROCESS 两种启动 + 五个算法）、
> Gremlin 的 `DIST` 形式五个步、`graph-http` 默认起 **4 个 worker（19100–19103）**。
> 详见 `CONTEXT.md` **§36** 与 `DAILY-2026-09-18.md` **§6**（含 9 个真 bug 的根因与修法、实测数字、已知限制）。

> 2026-09-18 新增（夜间）：**regex 改为「形状」语义 + 两处 UI 调整**（第 35.9/35.10 节）——
> `regex(field, 'pattern')` 现在**要求形状、并把形状写进 key**（`re_<形状>_<hash>`），
> 所以 `zhang1988` 与 `xia1999` 这类「形状相同、值不同」的账户会关联 ✓；
> 想按抽出的值分组用新的 **`regexValue(...)`**（演示模式 0 用它，key 不变 ✓）；
> 模糊关联图标先**隐藏**（`SHOW_FUZZY_EXAMPLE = false` ✓，接口与 example/demoSteps 保留 ✓）；
> 图实例下拉宽度改为**原来的 3/4（317px → 238px）** ✓。

> 2026-09-18 新增（傍晚）：**模糊关联的 `regex` 修正与示例按钮**（第 35.8 节）——
> `regex` 里 `\d`/`\w`/`\s` 以前被当转义吃掉（看起来像「没实现」）✗ → 现在反斜杠原样保留 ✓，
> 并支持 `regex(field, 'pattern'[, group])`（0 = 整个匹配，越界报错）；两个演示模式改用正则（key 不变）；
> 模式可自带 `example`/`demoTitle`/`demoSteps`，主页新增「模糊关联示例」按钮
> （`GET /api/fuzzy/demo`，只在有示例的模式上出现，一点即跑完整链路 ✓ 实测 8 点 / 18 边；
> **该按钮当晚已按反馈隐藏** ✗，见下一条）。

> 2026-09-18 新增（下半天）：**模糊关联 Fuzzy Linking**（第 35 节）—— 新模块 `graph-fuzzy`
> （模式语言 `emailDomain/ip2/ip3/countryCode/cardBin/alphaPrefix/regex/hash/opt` …、
> key 归一化、确定性 id、`FuzzyLinks.plan` 读写共用一份计划）、Gremlin 新步 `fuzzyLink(patternId[, context])`、
> schema 新增 `FuzzyPattern`/`FuzzyLink`/`FuzzyLinkedTo`（出/入度 1000）、两圈演示数据与完整链路查询、
> `/api/v1/fuzzy/*` 接口 + 建模页「模糊关联模式」卡片。
> 2026-09-18 新增：**条件与上限下沉进 store**（第 34 节）—— `has`/`filter` 在解码时判定、`limit` 只取最新 k 条、
> `count` 一条不构造；benchmark 新增 `decoded` 列（50 → 0/10/20）；
> 并修掉 `limit` 不清理子图、pushdown 列表错位、批读无限递归三个真 bug。
>
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

### 0.0 2026-09-20：Aerospike 查询路径的准确性与时延

| 事项 | 结论 |
|---|---|
| 准确性测试 | 新增 `AerospikeHopSuite`（**参考读数器**：只读 bin + schema，手工组合跳/条件/上限/投影/去重，期望值不从引擎来）+ `AerospikeHopQueryAccuracyTest`：**10 个一跳 + 10 个两跳（5 到点 / 5 到边）全部与存储记录一致**（4 项，约 0.7 s） |
| 时延测量 | 新增 `AerospikeHopQueryLatencyTest`：23 次测量（20 条 + `batch(1)` + `parallelism(8)` + 一条「条件不匹配」对照），各 5 预热 + 20 计时，打印**时延表 + 存储流量表**。安静机器：一跳 p50 0.54–3.57 ms（中位 0.9）、两跳 1.22–5.16 ms（中位 2.3）；流量列确定性 |
| 关键读数 | 每跳的读是**批量**的（1.1 四次调用拿 51 条；2.4 九次拿 151 条）；计数**零构造**（1.6/1.7 decoded=0）；条件下沉（1.5c decoded=0 / filtered=50）；上限压构造量（1.8 decoded=5 / filtered=45）；**顶点跳为拿对端 id 把边全构造**（1.4/2.4/2.8 decoded=50/150/150）⇒ 最值得优化的点 |
| 本轮实现 | **时间窗口下沉**：`EdgeRead.window` + 解码循环先按窗口过滤（读 raw entry 的 createdTime，不反序列化）+ 计数路径同样先过滤；`AerospikeGraphQuery.readEdgesWithin/readEdgesBatchWithin` 窗口进 store；新增 `AerospikeEdgeWindowPushdownTest`（3 条相隔一天的边，窗口解 2 走 1） |
| 语义提醒 | `createdBetween` 放在**跳之前**才能收窄读；demo 数据所有边同一时间戳 ⇒ 时延上看不出窗口收益（用自造数据验证） |
| 未做（§2 第 0 条） | 顶点跳不构造边；`limit(k)`/条件的有序表示与摘要 bin；跨查询 bin 缓存；一跳邻居读按 display 属性投影 |


### 0.1 2026-09-18（17 个提交，分三段）

| 段 | 提交 | 结果 |
|---|---|---|
| 系统 metrics | `fe02c14` `c9455be` | 新模块 `graph-metrics`：三漏斗采集（KV 层 / 业务日志 / 查询前后差值）+ Aerospike `graph_metrics` 与 RocksDB `metrics` CF 两套持久化 + `/api/v1/metrics` + UI 指标面板（§27–28） |
| 建模 / benchmark / 两轮优化 | `c4348ae` `f3d3ef3` `c41a686` `23f0508` `a6fcc7e` `df86f26` `6d0a4a3` `3bab63d` `190478b` `67b63c7` | 建模 UI（热加载）、payment-graph 12 项一致性 + 10 条查询 benchmark、smart 数据（向量+地理）、**Cypher 聚合 53.2→3.9 ms**、count 走 bin size、条件/上限下沉进 store（`decoded` 50→0/10/20）、UI 两处修正（§29–34） |
| 模糊关联 + 分布式算法 | `356ac79` `bc38edb` `feecf8c` `a091cd6` `865a143` | **Fuzzy Linking**（`graph-fuzzy` + `fuzzyLink` 步 + 两圈演示数据 + 接口 + 建模页卡片）、`regex` 反斜杠修复 → **形状语义** + `regexValue`、示例按钮（先隐藏）、图实例下拉 3/4、**Gemini 分布式算法**（§35–36） |


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

### 1.0 两个记录在案的坑（今天踩过）

* `-Dspring-boot.repackage.skip=true` 会把 **thin jar 覆盖到同一个路径** ✗ —— 之后再 `java -jar` 就报
  `ClassNotFoundException: ch.qos.logback...sprit.ThrowableProxy`。**跑之前重新 `./mvnw -pl graph-http -DskipTests package`（不带该 flag）** ✓。
* **非 clean 的 `package` 会在 fat jar 里留下旧的嵌套模块 jar**（本次 `graph-gemini` 就是这样，症状：改了算法但服务行为不变）✗ →
  判定：`unzip -p graph-http/target/graph-http-0.1.0-SNAPSHOT.jar BOOT-INF/lib/graph-gemini-0.1.0-SNAPSHOT.jar | md5sum` 与
  `graph-gemini/target/graph-gemini-0.1.0-SNAPSHOT.jar` 对比；修法：`clean package`（并先 taskkill 占 jar 的进程）✓。
* 被占用的 jar 无法删除（`clean` 失败）→ 先 `taskkill /F /PID` 再 `clean package` ✓；
  测试若要起 worker，用 `GeminiCluster.Config.local(n)`（端口 0 = 系统分配 ✓），别用 19100 硬编码 ✗。

### 1.1 正在跑的服务

| 端口 | 进程 | 内容 |
|---|---|---|
| **8081** | `graph-http`（默认 profile） | **11 个图**：payment ×3、geo ×2、`payment-graph-as-edge`、`kg-entity-graph`、`hnsw-sift`、`hnsw-dynamic`、**`payment-graph-smart`**、**`payment-graph-smart-rocksdb`** |
| **19100–19103** | 同 8081 进程内的 **4 个 Gemini worker** | `GET /gemini/status`；`graph-http` 启动即监听（`phoenixgraph.gemini.workers/first-port/mode`）；`mode=process` 时每个 worker 是独立 JVM |
| **8080** | `graph-web` | UI（`/api/graphs` 列图、`/api/query` 代理到 8081） |
| **8083** | `graph-http --spring.profiles.active=hnsw-dynamic`（本轮未起） | 写入即索引的向量 demo（REST 写点 → 立即可 `nearest`）；**必须加 `--phoenixgraph.gemini.first-port=19110`**，否则 Gemini worker 的 19100–19103 与 8081 冲突而启动失败 |

起停（**首选脚本**：建包 → 杀旧进程 → 起 Aerospike → 起服务 → 健康检查，日志在 `.logs/<服务>.out`）：

```bash
tools/start-phoenixgraph.sh                 # 建包后起 8081 + 8080
tools/start-phoenixgraph.sh --no-build      # 跳过建包，直接起
tools/start-phoenixgraph.sh --with-hnsw     # 同时起 8083（脚本已带 --phoenixgraph.gemini.first-port=19110）
tools/stop-phoenixgraph.sh                  # 停掉
```

手工等价命令（脚本就是这套，供排查参照）：

```bash
jps -l | grep graph-          # 找 PID；Windows 下 taskkill /F /PID <pid>
./mvnw -q -pl graph-http,graph-web -am -DskipTests clean package
java -jar graph-http/target/graph-http-0.1.0-SNAPSHOT.jar
java -jar graph-web/target/graph-web-0.1.0-SNAPSHOT.jar
java -jar graph-http/target/graph-http-0.1.0-SNAPSHOT.jar --spring.profiles.active=hnsw-dynamic --server.port=8083 \
     --phoenixgraph.gemini.first-port=19110
# 健康检查
curl -s localhost:8081/api/v1/graphs | head -c 200
```

### 1.2 数据（Aerospike namespace `test` + 本地 RocksDB 目录）

| 位置 | 内容 |
|---|---|
| `http_demo_v3`（**service 现在读这个**） | payment-graph 的单向 demo（Aerospike）：135/150 + 1000–1049 + 2000–2099，IP/Phone → Account 各一条，hub `135` **76** 笔出款（50 + 26 笔同端点多笔），由 `QueryApiIntegrationTest.demoSharedGraphViaApi` 播种 |
| `http_demo_v2` | 旧的星形图（hub `135` 50 笔，IP/Phone 边**双向镜像**）；只有 `demoStarGraphViaApi` 还在写它 |
| `kg_nell995_v2` | NELL-995 知识图谱（1 种点 `Entity` + 1 种边，按 relation 分 10 片），11.3 万点 / 15 万边 |
| `hnsw_sift_v1` | SIFT small 10,000×128（`Vector` + `vector_0..vector_14`），10,001 点 |
| `hnsw_dynamic_v1` | 600×128（`Doc` + `embedding`，schema 零层边，启动自动建） |
| `payment_as_edge_v1` | **今日新增**：`payment-graph-as-edge` 的 51 个点 + hub `9000001` 的 **30 万笔**收款（聚合边表 `_edge_out_1`/`_edge_in_1`/`_edgeheads`），其中 5 笔内联改写为 `REFUNDED` |
| `.rocksdb/`（仓库根，已 gitignore） | `payment-graph-rocksdb-inline`、`payment-graph-rocksdb-edge`（各 151 点/**300 边**，单向）、`geo-merchants-rocksdb-inline`、`geo-merchants-rocksdb-edge`（各 240 商户 + S2 geo 索引） |

**重新播种（数据丢了才需要）**：

```bash
# RocksDB 四个实例（payment ×2 + geo ×2）
./mvnw -q -pl graph-integration-test -am test -Dtest='RocksDbLiveLoadTest' \
       -Drocksdb.live.dir=D:/workspace/eclipse/PhoenixGraph/.rocksdb -Dsurefire.failIfNoSpecifiedTests=false

# 分布式算法（4 个 worker 默认就在 8081 进程里；PROCESS 模式=各自独立 JVM）
curl -s 127.0.0.1:19100/gemini/status            # 单个 worker 的状态（supersteps/mode/messages）
#   分布式步：点种子 + 边类（无 id 种子尚未支持，见 §2 第 0 条）
#   g.V('135','Account').bothE('Payment').otherV().pagerank(DIST, 10)     # 50 点 / 2.8 s
#   g.V('135','Account').both('Payment').cc(DIST)                          # 50 点 / 1 个分量 / 1.9 s
#   g.V('a1,a2,a3','Account').louvain(DIST, 20) / sssp(DIST, 'a1') / bfs(DIST, 'a1')
# 测试：graph-gemini（分区/双表示/集群/子进程）、GremlinDistStepsTest（DIST 五种步）
./mvnw -q -pl graph-gemini,graph-query-gremlin test

# 模糊关联的两圈演示数据（幂等，可反复跑；payment-graph / set http_demo_v3）
./mvnw -q -pl graph-integration-test -am test -Dtest='FuzzyLinkIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false

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
// 7) 模糊关联：context -> 链接节点（payment-graph，模式在 fuzzy-patterns.json）
g.V().fuzzyLink(0, '{"email":"19880101@mail.ru","phone":"+8613800138001","ip":"10.20.31.7","username":"zhang1988"}')
// 8) 模糊关联完整链路：模式 -> 团伙账户 -> 出款 -> 收款方（8 点 / 6+12 边）
g.V().fuzzyLink(0, '{"email":"19880101@mail.ru","phone":"+8613800138001","ip":"10.20.31.7","username":"zhang1988"}')
     .out('FuzzyLinkedTo').outE('Payment').otherV()
// 9) 从账户自省它属于哪个团伙（context 取自点自身属性）
g.V('fz_a1','Account').fuzzyLink(0)
// 10) 第二个模式（同 /24 + 同卡 BIN + 邮箱前四位）
g.V().fuzzyLink(1, '{"email":"44100101@qq.com","ip":"10.30.40.11","cardNumber":"6217000090009001"}').out('FuzzyLinkedTo')
```

---

## 2. 明天第一件事（按优先级）

0. **Aerospike 读路径的三个优化（今天用 20 条查询的时延/流量测量排出来的顺序）**：
   1. **顶点跳不构造边**：给 `GraphQuery` 加「只读对端」的读（或让 `EdgeRead` 支持），顶点跳只需 (边类, 方向, 对端 id)
      ⇒ 1.4 的 decoded 50 → 0、2.4/2.8 的 150 → 50/100（与计数改写同源）；
   2. **`limit(k)` / 条件的有序或摘要表示**：现在都是有界选择/线性扫描（1.8 走完 50 条只留 5 条）；
      写时维护时间有序副表示或「(边类, 属性) → 取值计数」摘要 bin，可把 cap 变 O(k)、把 `has(p,v).count()` 变 O(1)；
   3. **跨查询 bin 缓存**（按 顶点/边类/方向 + `lastUpdatedTime` 失效）：仪表盘反复查询可从 N 次往返降到接近 0。
   （第 6 条：一跳最终答案是点、且调用方不需要全部属性时按 display 属性投影 —— 会改 API 返回的属性集合，需与前端先约定。）

0b. **类起点的下一步**（今天做完了「能查」，下面是「查得省」）：
   `g.V('Account').count()` / `g.E('Payment').count()` 走 store 的 `countScanVertices/countScanEdges`（**不构对象**，
   接口已实现 ✓）；`g.V().hasLabel('Account')`（无参 V + 类型过滤）与 `bothE()` 全类扫描；
   扫描的时间窗口下推（createdTime 不在键前缀，只能过滤）。
1. ~~分布式算法的「无 id 种子」~~（今天留给明天的第一条）：给 `graph-query` 加可选扫描端口
   （`scanVertices(vertexName)` / `scanEdges(edgeName)`，Aerospike 走 `scanAll`、RocksDB 走迭代器），
   于是 `g.E('payment').pagerank(DIST)`、`g.V().filter(...).bothE('payment').cc(DIST)` 这类写法可用
   （与 §5 的「按类型计数需要扫描计划」是同一个缺口）；随后把 `DIST` 加进 UI 示例下拉、
   再补 BC 算法（`GeminiAlgorithms` 里加一个 signal/slot 实现即可）。

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

* **demo 数据的时间戳是单一的**（`http_demo_v3` 里所有边同一 createdTime）⇒ 时间窗口的收益在 demo 上看不出来，
  窗口下沉用自造数据（`AerospikeEdgeWindowPushdownTest` 的 3 条相隔一天的边）验证；
* **Aerospike 时延测量对机器负载敏感**（繁忙时同一批查询到 1.5 倍），流量列是确定性的，做回归时看流量列；
* **一跳「最终答案是点」时会读全部属性**（1.1/1.4 v-items=50）—— 优化方向见 §2 第 0 条第 6 项。

* **类起点只在 RocksDB-Edge 上可用** ✓（`supportsScan()`）：Aerospike（OLTP，按 id 读）与 RocksDB-Inline 会**明确报错**
  并指出该用哪种 store —— 这是定位而不是缺陷；扫描上限用 `GremlinGraphQuery.scanLimit(n)`（0 = 不限）。
* 扫描把 id 还原成存储类型（数字仍是数字），查询文本是字符串：引擎在建图/取根/邻接处按文本兜底匹配 ✓，
  但**答案里 key 的写法**沿用种子来源（扫描来的 key 是数字、查询里写的 key 是文本）。

* **分布式算法**：`g.E('class').algo(DIST)`（空种子只给边类 = 全图某类边）**不支持** ✗（需要扫描端口，见 §2 第 0 条）；
  BC 未实现；不做 NUMA/socket 子分区与计算/通信重叠（论文单机多核那半）；一个集群同时只跑一次计算（launcher 串行驱动）。
* **算法步要吃「点」**：`outE(...)` 之后 traverser 是边、起点会丢 ✗，所以写法是
  `bothE('class').otherV().algo(DIST)` 或多起点 `g.V('a,b,c','Account').algo(DIST)`。
* **模糊关联**：写点不自动建链（按需求先不做）；无「按 key 前缀列节点」的反向索引；`countryCode` 依赖内置码表；
  `regex` 是形状语义、`regexValue` 是抽值语义（别混）。

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

**Aerospike 准确性与时延（2026-09-20）**：`graph-integration-test` 的
`AerospikeHopSuite`（参考读数器 + 20 条查询目录 + 答案比较）、`AerospikeHopQueryAccuracyTest`（4 项）、
`AerospikeHopQueryLatencyTest`（23 次测量 + 两张表 + 分析）、`AerospikeEdgeWindowPushdownTest`（2 项，scratch set `payment_window_probe`）；
产品代码：`AerospikeEdgeStore.EdgeRead`（+window / hasWindow / inWindow / withWindow）、`decodeEdges` 与 `countEdges` 的窗口过滤、
`AerospikeGraphQuery.readEdgesWithin/readEdgesBatchWithin` 的窗口下沉。

**类起点（扫描）**：`graph-query` 的 `ScannedEdge` + `GraphQuery.supportsScan/scanVertices/scanEdges/countScan*`；
`graph-storage-rocksdb` 的 `RocksDbKeys.vertexIdOf/directionOfKey/edgeKeyParts`（owner 从键里被验证地切出来）、
`RocksDbVertexTable.scan/count`；`graph-storage-rocksdb-edge` 的 `RocksDbEdgeStore.scanEdges/countScannedEdges/ScannedEdgeRow`、
`RocksDbEdgeGraphQuery` 的扫描实现；`graph-query-gremlin` 的 `GremlinQuery.Source`（VERTICES/VERTEX_TYPE/EDGE_TYPE/NONE）、
`GremlinGraphQuery.start/startVertexScan/startEdgeScan/requireScan/scanLimit`、`GremlinEdge.sourceVertexId`、
`readEdgesByVertex`（保留「边是谁读出来的」）、`GeminiGraph.of` 的文本兜底匹配、`GremlinAlgorithms.canonicalIds`。
PageRank 修正：`GeminiAlgorithms.pagerank`（全局出度除法 + `(1-d)/|V|`）、`GeminiComputation.accumulative/result(...,state)`、
`WorkerState.outDegree/totalVertexCount/clearOwnedValues`、`GeminiCluster.run` 注入 `vertices/edges`。
测试：`RocksDbEdgeScanTest`(8)、`GremlinScanStepsTest`(11)、`RocksDbEdgeScanQueryIntegrationTest`(8)。

**`graph-gemini`（新模块，12 类）**：`GeminiGraph`（输入图+scope）、`GeminiPartitioner`（chunk + 双份边）、
`GeminiLauncherLayout`（每 worker payload：拥有/出度/镜像 owner/push 目标）、`WorkerLayout`（CSR bitmap + 压缩 CSC）、
`WorkerState`（值 + pending/commit + 活跃集）、`GeminiWorker`（exchange/compute/聚合/统计）、
`GeminiWorkerServer`（10 个端点）、`GeminiWorkerMain`（独立程序）、`GeminiWire`/`GeminiClient`（wire + 共享 HttpClient）、
`GeminiCluster`（LOCAL/PROCESS + 逐轮驱动 + 结果与统计）、`GeminiAlgorithms`（pagerank/louvain/cc/sssp/bfs）。
配套：`GremlinDistAlgorithms` + `GremlinGraphQuery.geminiCluster(...)`、
`StorageConfig.geminiCluster` bean、`QueryService` 注入、`graph-http`/`graph-query-gremlin` 的 pom。
测试：`GeminiPartitionerTest`(7)、`GeminiWorkerDirectTest`(7)、`GeminiClusterTest`(11)、`GeminiProcessWorkerTest`(1)、
`GremlinDistStepsTest`(12)。

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
| `README.md` | 模块表、四种存储对比、Geo search、时间窗大点读法、两种查询语言、向量检索、模糊关联 |
| `CONTEXT.md` | **第 36 节**（2026-09-18：分布式图算法 Gemini —— 论文映射/适配/bug/实测/限制）；**第 32–35 节**（2026-09-18：两轮 store 下沉优化 / 模糊关联 / regex 形状语义 / UI 三处）；**第 27–31 节**（同日：系统 metrics、指标面板、建模 UI、benchmark、smart 数据）；**第 21–26 节**（2026-09-17：RocksDB 两实现、GEO、四项修正、UI 三处、Aerospike-Edge、居中与有界扩展）；更早：第 15–20 节（HNSW/向量索引/UI） |
| `DAILY-2026-09-20.md` | **按天笔记（今天）**：要求与做法、20 条查询清单与 20/20 结论、时延表与流量表、**8 条优化点**（逐条对着测量列）、本轮实现的窗口下沉、运行命令与测量注意 |
| `CONTEXT.md` | **第 38 节**（参考读数器的设计、指标语义 decoded/filtered、结论与优化清单、窗口下沉实现）；37 节是类起点扫描 |
| `DAILY-2026-09-18.md` | **按天笔记**：今天 17 个提交的思考与结果（§6 = 分布式算法那一段：9 个 bug 的根因、实测、限制）、复现命令、遗留事项（与昨天的 `CONTEXT.md` §21–31 互不覆盖） |
| `HANDOVER.md` | 本文件（结论 / 现场 / 明天第一件事 / 命令 / 设计要点 / 限制 / 代码地图） |

---

## 8. 怎么查历史（含完整思考与输出）

**结论类**：本文件 §0（今天的结论）+ `CONTEXT.md`（设计与实测）+ **`DAILY-2026-09-18.md`（按天：思考与结果）** + `git log --oneline --since=…`（改动本身）。
这几处只有**结论**，没有逐步的推理过程。

> 按天的**思考**（为什么这么做、掉过哪些坑、哪些方案被放弃）在 `DAILY-*.md` 里；
> **逐步的推理过程**只在会话导出里（下一节）；`CONTEXT.md` 是滚动的设计文档（昨天 §21–31、今天 §32–35，互不覆盖）。

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
node tools/session-export.mjs --from 2026-09-18 --to 2026-09-18 --thinking full --tools full \
     --max-chars 4000 --out .sessions/2026-09-18-full.md

# 只看问答（不含思考与工具输出）
node tools/session-export.mjs --from 2026-09-18 --to 2026-09-18 --thinking none --tools none \
     --out .sessions/2026-09-18-answers-only.md

# 常用开关：--session <路径|id|latest> --thinking full|brief|none --tools full|brief|none --max-chars N
```

已导出到 `'.sessions/`（已 gitignore，不入库）：

| 文件 | 内容 |
|---|---|
| `2026-09-17-full.md`（1.4 MB） | 09-17：14 个用户轮次 / 965 个助手轮次 / 369 段思考 / 368 段工具输出 / 2 次压缩 |
| `2026-09-17-answers-only.md`（0.6 MB） | 09-17 速读版 |
| `2026-09-18-full.md`（3.0 MB） | **09-18：932 轮，含全部 thinking 与工具输出** |
| `2026-09-18-answers-only.md`（0.17 MB） | **09-18 速读版** |
