# Handover — resume here

> 本文件是**断点续做手记**：今天做了什么（结论）、现场怎么恢复、明天从哪继续。
> 详细设计与实测数据在 `CONTEXT.md`：**2026-09-21 新增 §47–§59，2026-09-23 新增 §60–§68**（§47 仪表盘七问；§48 四问（二）；§49 三问（三）；§50 五问（四）；§51 四问（五）；§52（六）：Aerospike-Edge 大点冷/近线/在线三层；§53（七）：stale 写统一 / 结果面板复核 / 150 两跳扩展 + benchmark；§54（八）：表达式字符串函数 / display `$id` / count 交给服务端；§55（九）：用户报的 12 件事；§56（十）：算法 / legend·页签·示例 / 子图把 filter 掉的边留下；**§57（十一）：闭包 if/else 与集合、`values()` 的元素自身列、空答案不画、面板只报数目 + 135·150 两跳 benchmark**；**§58（十二）：fuzzy 的 xia 账号 / 图实例收敛到四个 / payment-graph 向量 / RocksDB-Edge GEO / 去掉 FuzzyPattern**）；
> 过程记录：**`DAILY-2026-09-21.md`**（§1–§22，09-21 的十三批）与 **`DAILY-2026-09-23.md`**（§1–§14，09-23 的九批；**§14 是这一天跨批的思考主线**：测量要交错、先怀疑观测、静默错答案比报错糟、改依赖模块先 install、从声明类型推断、数据可复现、报告自解释、用户语法即需求、一次一个变量）。

**最后一次会话：2026-09-24（周四）· 第二十三至三十一批（§69 双向窗口 … §77 四跳双向 both、§78 path 批量读优化+归因）· graph-query-gremlin 242 用例绿**
**前一次会话：2026-09-23（周三）· 一天九批（第十四至二十二批）· 当时全量 `./mvnw test` 绿（0 失败 / 0 错误；gremlin 194 / fuzzy 49 / http 71 / schema / gemini / hnsw / …）**
**九批一句话：十四 面板单元素 + 索引坏掉要报错 + 编译缓存上限 + RocksDB-Edge 的 HNSW（§60）· 十五 同一个两跳语句在 RocksDB-Edge 上 100 id×8 轮（§61）· 十六 geo+向量+边组合语句 100 次（§62）· 十七 `outE` vs `out` 各 100 次（交错测）（§63）· 十八 优化掉中间多余的点/边属性读（§64）· 十九 建模页七项改进（§65）· 二十 RocksDB-Edge 四条查询能力（§66）· 二十一 TinkerPop 写法四条（§67）· 二十二 事件序列模式（§68）。**后六批里三批是性能测试、三批是查询能力，测量方法本身也踩过坑（见 `DAILY-2026-09-23.md` §14）。**
**第二十三批（2026-09-24，§69）：事件序列模式第二轮 —— 双向时间窗口 `[-100s<time_interval<100s]` + 步骤/跨步骤条件 `[A==B || C==D]`；pattern #3/#4 落地并各关联到账号（`ev_a6..ev_a9`）；`fuzzy 54` / `http fuzzy 15` / 集成 `FuzzyLinkIntegrationTest 14` 全绿，8081 服务已重启用新包实测。**
**第二十四批（2026-09-24，§70）：事件序列模式第三轮 —— `anyFields([Event,...], n, bool)` 跨事件共有字段 UDF（列表字面量、`true/false`、上下文函数类别、步骤值改整个匹配结果求值）；pattern #5 落地（`ev_a1/a2/a5` 一组三人）；`fuzzy 58` / `http fuzzy 15` / 集成 `FuzzyLinkIntegrationTest 15` + `FuzzySessionGroupingTest 4` 全绿；8081/8080/8083 已重启用新包，`.logs/verify-anyfields.js` 实测。**
**第二十五批（2026-09-24，§71）：Gremlin 文本区支持 Groovy 风格脚本（`def` 变量 / `print` / 赋值 / `return` / 列表 / 变量当 id / 小方法链）——新类 `GremlinScript`，不引入 Groovy 运行时；请求多可选 `script` 布尔（不传则自动判断），响应 `ScriptResponse{result,output,subgraph}`；UI 加**只给管理员看、默认放开**的开关（`IS_ADMIN`/`SHOW_SCRIPT_MODE`）。`graph-query-gremlin 204` / `graph-http 73` 全绿；headless 实测 `.logs/verify-script-mode.js`；顺带重建 payment-graph HNSW 索引（152 点）。**
**第二十六批（2026-09-24，§72）：未命名 hop 不再读/画 HNSW 层边（`hiddenEdgeClasses`，命名仍可、`nearest` 仍画 layer-0）；新增 `path()`（`GremlinPath` + 路径感知执行，`v,e,v,e,v`）；脚本补集合方法（`retainAll/joinOnEnd/...`）与 `+`；给出 sender/receiver 四跳脚本。`graph-query-gremlin 215` / `graph-http 73` 全绿；`.logs/verify-paths.js` 实测（bothE 无 vector、nearest 3 条、81 条 path、四跳 30 条）。**
**第二十七批（2026-09-24，§73）：四跳脚本第二跳改 `outE('Payment')`（路径以边结尾）；脚本补 `endIds()`（落点从边 `otherVertexId` 取，不读点）与 `joinOnEnd(other, midObjects)`（公共点用第 2 步的点对象填充）；UI 示例同步。100 次交错基准（`.logs/bench-path.js`）：**29.20 ms vs 34.72 ms（−15.9%），kv 读 23 vs 33、点读 34 vs 44、边读不变 8，响应 139.56 vs 154.89 KB**；结果与改前一致（33/9/4/30/72）。`graph-query-gremlin 216` 全绿。**
**第二十八批（2026-09-24，§74）：脚本补 Groovy 控制流（`if/else`、`for in`、`while`、`break/continue`）与闭包（`each/collect/findAll/find/any/every/count/sum`，`it`/具名参数）+ 算术 `- * / %` + 元素方法 `it.value('k')/id()/label()`；Gremlin step 新增 `identity/constant/id/label/hasId/hasNot/hasKey/hasValue/is/range/skip/tail/sample/fold/unfold/sum/mean/max/min/order/groupCount/valueMap/toLower/toUpper/trim/length` 与子遍历分支 `union/coalesce/local/not/and/or`；`GremlinQuery.splitArgs` 按括号深度切分。并写出 TinkerPop 3.8.2 step 兼容表（§74.2，逐条 ✅/🟡/❌ 附原因）。`GremlinScriptTest 27` + `GremlinTinkerPopStepsTest 11`，gremlin 共 236 用例绿。**
**第二十九批（2026-09-24，§75）：step 第二批 —— `by()` 调制器（编译器折进 `order/groupCount/dedup/path`）、字符串（`asString/concat/split/substring/replace/lTrim/rTrim`）、类型（`asNumber/asBool/asDate`）、集合（`combine/intersect/difference/disjunct/all/any/none`）、分支（`optional/where/choose`）、循环（`repeat(t).times(n)`）、`simplePath/cyclicPath`（path 走链）、`coin/inject`（含 `g.inject(...)` 起点）/`timeLimit`；`dedup(key)`、`path(key)`；`GremlinPath` 允许 null（`path().by` 缺键）。§75.3 列出仍未支持的（都需带 `Traverser{value,path,sack,labels}` 的模型：`as/select/match/project/sack/aggregate/cap/...` 与 `repeat.until/emit`、`branch`、`outV/inV/bothV` 等）。gremlin 共 241 用例绿；8081 实测 by/inject/asDate/collection/optional/repeat/simplePath 均通。**
**第三十批（2026-09-24，§76/§77）：§76 把 step 支持现状合并成一份当前表（✅/🟡/❌，代替翻 §74.2+§75）；§77 把四跳脚本的 hop 改成双向 `both('Payment').bothE('Payment')`（UI 示例同步）并做 100 次交错基准：结果从 33/9/4/30/72 变成 528/118/51/396/1042，**94.42 ms vs 34.20 ms（+176%）、kv 59 vs 23、点读 220 vs 34、边读 32 vs 8、响应 1.8 MB vs 140 KB**；结论是工作负载变大而非回归（无向两跳邻域 + bothE 双向读），脚本 `.logs/bench-path-both.js`。**
**第三十一批（2026-09-24，§78）：四跳 path finding 性能优化。归因发现 path 模式每跳是"逐个 walk 调一次边 step"（每个到达点一次存储读），与普通管线的批量读不一致；`expandPathHop` 改成"收落脚点 → `readAllEdgesBatchCached`/`readAllEdgeClassesBatched` 批量读边（未命名边补 `recordEdges`）→ 一次 `otherV()` 批量解远端点 → 按 walk 顺序拼回"；`GremlinScript.joinOnEnd` 从 O(|L|×|R|) 改成按末端 id 建索引的 O(|L|+|R|)。实测：两跳 `both` path 的 kv 24→6、31.58→≈17 ms；完整脚本 B 的 kv 59→23、A 23→15；**结果完全不变**（s=528 r=118 mids=51 four=396）。优化后成本构成：遍历 32 ms + 脚本计算 12 ms + 1.7 MB 答案的序列化/传输 30 ms；剩余瓶颈是答案体积（只返回 `four` → 1.0 MB；只返回 id → 33 KB，见 `.logs/bench-path-opt.js`）。步骤归因/复现：`.logs/bench-path-opt.js`、`.logs/bench-path-both.js`；测试新增"一跳批量读 + 保持 walk 顺序"，gremlin 共 242 用例绿。另：只返回 path 数目（`four.size()`）而不是 1042 个 path 对象，延迟 **70.55 → 37.77 ms（−46.5%）**、p99 120.5 → 53.8 ms、响应 1728 KB → 60 B，而存储计数完全一致（`.logs/bench-path-count.js`）。**
**第三十二批（2026-09-25，§79）：四跳 path 两个口径，一个脚本一次调用返回两个结果。**不看方向**＝两侧 `both('Payment').bothE('Payment')`（§77 那条）；**看交易方向**＝付款方 `out('Payment').outE('Payment')`、收款方 `in('Payment').inE('Payment')`，`joinOnEnd` 反转右半后拼出的 walk 每跳都是 payer→payee（`in/inE` 走 `EdgeSide.IN`，同样吃 §78 的批量跳读）。**数据结论：1000→1006 之间不存在有向四跳**（`out²(1000)={1002,1008,1014,1020,1026}` 与 `in²(1006)={135,1004,1048,1042,1036,1030}` 交集为空），但 `out⁴∩in⁴` 非空（有向 8 跳）；演示另取 1000→1022（有向 74 条，与直接 `out⁴` 逐字相等）。40 轮交错基准（`.logs/bench-path-two-ways.js`，`.logs/two-ways-results.txt`）：pair1 OLD 71.5 / U 60.6 / U-COUNT 40.5 / D 28.8 / **BOTH 73.5** / BOTH-COUNT 56.0 ms，kv 23/23/23/10/**33**/33；pair2（553+74）OLD 61.5 / U 51.9 / U-COUNT 29.2 / D 25.0 / **BOTH 66.7** / BOTH-COUNT 40.4 ms，kv 23/23/23/15/**38**/38，响应 1611 KB。要点：**两个口径是 4 条独立 path 遍历**（`bothE` 的 `side=BOTH` 丢了方向，无法复用），存储计数正好相加（23+10=33、23+15=38）；有向那半只多 ~13–15 ms；答案体积仍是主要成本（BOTH-COUNT 比 BOTH 少 17–26 ms）；一次调用比两次省 ~10–16 ms；与旧脚本基本同价但少返回 646 条两跳半程。脚本进 UI 示例（`app.js` 两份），`.logs/scripts/four-hop-two-ways.groovy` + `run-two-ways.sh`。**
**第三十二批的环境坑（必读，§79.7）：(1) 三个 JVM 会在**工具调用之间**被外部整批回收，`nohup` 留不住、无 shutdown 日志、无 `hs_err` —— **起服务与跑基准必须同一次调用完成**，否则 ECONNREFUSED；基准脚本已改成崩了也打印已收集部分。(2) 遗留的 `mvn -pl graph-integration-test test -Dtest=SanguoRocksDbEdgeTest,SanguoTextGraphTest -Dsanguo.queries.write=true` 被杀在写一半时会把 `.rocksdb/sanguo-kg-text` 写坏（`MANIFEST-000018` 缺失），导致 graph-http **8081 起动失败**（`RocksDBException: Failed to open NewSequentialFile...`）；等它自己跑完会重建 store，或删掉该目录也能起来（图变空）。别在其写到一半时杀它。**
**第三十三批（2026-09-25，§80）：只用 `both` 两跳改写四跳 path，并验证 both→out/in 的缓存。** 用户更正：`both('Payment').bothE('Payment')` 本来就包含 out 半和 in 半（存储对 `BOTH` 请求返回 OUT/IN 两组、逐条带上 `side`），所以有向那半只要**筛**不要**查**。为此：(1) `GremlinElementExpression` 新增伪键 `side`/`direction` → 小写 `out`/`in`/`both`（遍历 `values('side')` 与裸写 `side` 都通，**裸写不进 pushdown**，否则被当存储属性条件而静默返回空）；(2) 脚本 `value/values` 改走同一套解析，于是 `p.get(1).value('side')` 可用；(3) 脚本补 `&&`/`||`（短路，`||` 低于 `&&`）；(4) `GremlinContext.fromTheOtherDirection(...)`：`BOTH` 缓存按 `side` 筛出 `OUT`/`IN`，`OUT`+`IN` 合成 `BOTH`（undirected 每条边 `side=BOTH`、各方向相同，不拼接；有时间窗/读上限不复用），单点 `readEdgesCached` 与批量 `readAllEdgesBatchCached` 都接上。**筛出来的与真走 out/in 逐字一致**（1000→1006: 396/0=0；1000→1022: 553/74=74；out-half 33/33、in-half 12/12）。**实测（30 轮交错）**：BOTH-ONLY vs BOTH+OUT/IN —— median **39.0 vs 50.0 ms（pair1）/ 45.0 vs 51.7 ms（pair2）**，kv **23 vs 33 / 28 vs 38**（edge.read 32 vs 41、decode 285 vs 337，正好等于省掉的 out/in 两条遍历），答案相同。**缓存验证**：修前 `both` 后走 `out` 必回存储（`EdgeCacheKey` 带 direction）；修后 `union(bothE, outE)` 与 `union(bothE, bothE)` 完全相同（3 kv / 2 边读 / 12 解码），而 `union(outE, bothE)` 是 4/3/15。**但脚本语句之间不共享上下文**（每句 `executeWithSubgraph` 都 `newContext()`），故 §79 的“both 语句+out/in 语句”必然重读——这才是必须筛不要查的根本原因；语句共享需加 session（一个 context 串多条遍历 + 每句前复位时间窗/读上限），列为可选项。`./mvnw -o -pl graph-query-gremlin test` **245 用例绿**；UI 示例与 `.logs/scripts/four-hop-two-ways.groovy` 已改成 both-only 版。**
**第三十四批（2026-09-25，§81/§82）：复测 1000→1022；查清"编译一次"并给出指标；摸清脚本能力边界。**(1) 复测（40 轮交错）：BOTH-ONLY median **67.3 ms / kv 28** vs BOTH+OUT/IN **78.6 ms / kv 38**，筛出的有向半与真走 out/in 逐字一致（undirected 553、directed 74=74、out-half 33/33、in-half 12/12）。(2) **脚本原先每个请求都重新 parse**（遍历本来就是按整段文本编译一次并缓存），新增 `GremlinGraphQuery.script(text)`按脚本文本缓存解析（同一个 512 上限）并返回 `ScriptPlan{script, parsed, parseNanos}`，遍历编译也加了计数；响应新增 `ScriptResponse.compile{parseMillis, parsedNow, traversalsCompiled, compileMillis}`。实测：第 1 次 parse **3.165 ms**（冷 JVM）+ 4 段遍历编译 **4.000 ms**，第 2..30 次全 **0**；对照"每次换一段文本"的解析 **0.187 ms** 均值 —— 缓存省下约一次调用的 **0.23%**；把 id 拼进脚本文本仍是一次新解析。(3) 68 个 Groovy 探针：**44 接受 / 24 拒绝** —— 不是任意 Groovy，是自研解析器上的子集；拒绝都带回消息（列表 `sum/max/min/sort`、字符串方法、Map 字面量、Range、三元、elvis、`+=`/`++`、`try/catch`、`switch`、`class`/`import`/函数定义、`assert`、`new`、`instanceof`）；两个坑：`=~` 静默变字符串、`"${x}"` 被 HTTP 参数替换吃掉；毛刺：遍历结果是 List，`count()+count()` 得到文本 `[3][9]`。顺手修了**小数**（`1.5` 曾被当成方法链）。测试：gremlin 247、http 73 绿。****环境事故：另一个会话生成 `sanguo-kg*.json` 的写入窗口撞上 rebuild，读到半个 JSON 导致 `Duplicate property name 'source'` 起不来（单独 `SchemaJsonCodec.fromJson` 解析这些文件都 OK）；别在其 `-Dsanguo.queries.write=true` 测试运行时 rebuild graph-http。**
**环境事故：另一个会话生成  的写入窗口撞上 rebuild，读到半个 JSON 导致  起不来（单独解析这些文件都 OK）；别在其 write 测试运行时 rebuild。**
**第三十五批（2026-09-25，§83/§84）：Aerospike-Edge 大点三层方案的演示数据、用例与 UI 查询（任务 1）。** 实现早就在（`EdgeTiering`/`AerospikeEdgeChunkStore`/`AerospikeNearlineStore`+`AerospikeVolumeStore`/`AerospikeEdgeOfflineJob`/`AerospikeEdgeGraphQuery.readEdgesWithin`；`StorageConfig` 对 `AEROSPIKE_EDGE` 已用 tiered 构造 + `Clock.systemUTC()`），本轮产出**数据+用例+查询+两个 bug 修复**。(1) `AerospikeBigVertexDemoLoader` 把 5 个大点写进 **`payment-graph-as-edge`** 的 `payment_as_edge_v1`：`9000002`(in,冷+近线+在线)、`9000003`(in,近线+在线)、`9000004`(in,只有冷)、`9000005`(out,三层)、`9000006`(未超上限)；两个 id 跨层写两次演示取最新副本；锚定 now：冷`[-365d,-25h]`、近线`[-23h,-1h]`、在线`[-10m,now]`；声明月量 1e8 → 近线一天 **834** KV。写入命令：`-Dtest=AerospikeBigVertexDemoTest -Daerospike.bigVertex.demo=true [-Daerospike.bigVertex.reset=true]`；同一 set 里也把 `9000001` 的老演示重新装回（`AerospikeEdgeLiveLoadTest -Daerospike.asEdge.set=payment_as_edge_v1`）。(2) `AerospikeEdgeBigVertexIntegrationTest` **11 用例全绿**（自己的 set 先 truncate，计数精确）：三段合并 6905、近线+在线 906、只在线 6、只冷 495、安静大点 6000（近线窗口 0）、出度三段 6905、无窗口被拒、未超上限免窗口、近线按 volume 切 834。(3) UI 例子新增 11 条（中英双语，相对窗口，已逐条实测：`.logs/verify-big-vertex-ui.js`）。**(4) 两个真 bug**：<br>① 近线 `readWindow` 按天循环遇上开区间窗口（`createdBetween(from,'*')`，`toTime=Long.MAX_VALUE`）**死循环**（实测挂 >25 min）→ 按 TTL 截断 + 开区间取 now；② 同一循环从 `fromTime` 起跳 24h，**漏掉最后一个自然日**（跨天窗口 902 条只读到 655）→ 起点对齐 UTC 零点按天步进。③ 新增相对时间窗 `-30m/-2h/-25h/-7d/-2w/-90s`（`TimeRange.relativeToNow` + `parse(from,to,now)`），**在 step 执行时解析**（编译期解析会被 §81 的编译缓存冻住）。测试：`graph-storage 20`（新增 `TimeRangeRelativeBoundsTest`）、`graph-query-gremlin 247`、`graph-http 73`、`graph-fuzzy 58` 全绿。**任务 2（RocksDB-Edge 按属性的分布式查找）本轮未开工**，设计见 `DAILY-2026-09-25.md` §26 之后与下面的"下一步"。****下一步（任务 2，RocksDB-Edge 的分布式属性查找）：** 用户要求 ① 用基本 MapReduce 划分数据、每个 mapper 内做属性查找 + 后续 Gremlin 遍历；② 每个 mapper 内多线程；③ reducer 收到对各 mapper 输出的迭代器（纯分布式计算时结果可只留在各 mapper 输出）；④ Gremlin 加一个 `g.V(DIST)` 之类的有效参数在"分布式/单机"之间切换。可行的落点：`RocksDbEdgeStore` 的 key 是 `hash(id):id`（`RocksDbHash`，**定长 8 字节大端**），所以一个连续 hash 区间就是一个连续 key 区间，`RocksDbHandle.iterator(cf, from, to)` 已支持范围扫描；按 `regionCount`（`RocksDbStoreConfig`）切 N 个 split，每个 mapper 在自己 split 的 range 上扫描 + 过滤属性 + 跑后续 step 链（引擎已有 `runSubTraversal`/`CompiledQuery.steps` 可复用），reducer 把各 mapper 输出串起来（聚合尾巴要在 reducer 上重算一次）。落点文件：`GraphQuery`（新增 `supportsPartitionedScan`/`scanVerticesPartition`/`scanEdgesPartition` 默认方法）、`RocksDbEdgeGraphQuery`+`RocksDbVertexTable`/`RocksDbEdgeStore`（范围扫描）、`GremlinQuery`（解析 `g.V(DIST)`）、`GremlinGraphQuery`（分发 + 归并）、UI 例子与集成测试。
**第三十六批（2026-09-25，§85）：RocksDB-Edge 按属性查找的分布式（MapReduce）执行（任务 2 完成）。** 切分点：key 是 hash(id):id（RocksDbHash 定长 8 字节大端），**一个 hash 区间 = 一个连续 key 区间 = 一个分片的一次范围扫描**；新增 GraphQuery.supportsPartitionedScan/scanPartitionCount/scanVerticesPartition/scanEdgesPartition（默认方法，老 store 不受影响）、RocksDbVertexTable.forEachRange、RocksDbEdgeStore.forEachEdgeRange（scanEdges 改为"全范围+收集"，共用解码）、RocksDbHash.partitionBoundary/partitionKeyRange（floor(p·2^64/n)，BigInteger 算 128 位取低 64；最后一段开区间）。语法：DIST/DISTRIBUTED 作为 g.V(...)/g.E(...) 第一个参数（类型也可由后面的 has 给出）；g.V(DIST,id,'T') 明确报错。执行：分片数 = scanPartitionCount()；**map** 每分片一任务跑线程池（min(分片数, parallelism)），每任务自己的 GremlinContext，扫出区间内的点/边后跑**局部步骤前缀**（属性条件/投影/跳步都在 mapper 里）；**reduce** 把各 mapper 输出串起来，一个 context 跑**全集步骤**（首个全集步骤为切分点：order/limit/dedup/count/sum/mean/min/max/groupCount/fold/…）；没有全集步骤就是纯拼接（需求③）；子图由各 mapper 的子图回放合并。DIST+path() 明确互斥。测试：RocksDbEdgePartitionedScanTest（3：分片 1/2/3/4/8/16 并集=全表且每行一次、并发 mapper 与完成顺序无关、边界单调+末段开区间）、GremlinDistributedScanTest（3：四种形式 DIST vs 单机、outE 后续跳与 count/groupCount、两条错误路径）；线上 .logs/verify-dist.js **7/7 组一致**（17/51/65/67/groupCount{SUCCESS:65}）；graph-storage-rocksdb-edge 24、graph-query-gremlin 250 全绿。**边界（§85.5）**：演示图小，DIST 不会更快（验证的是切分正确/答案一致/可切换，收益在大表）；limit 这类无序裁剪的子集随分片顺序变（条数一致）；分片数 = store 的 regionCount；目前"mapper 内多线程"体现为 mapper 之间并行，单分片再切子任务为可选项。UI：payment-graph-rocksdb-edge 新增 6 条单机/分布式对照例子。**
**第三十七批（2026-09-25，§86）：分布式扫描与分布式算法组合 —— 一个 DIST 管整条查询。** 用户形状 `g.V(DIST).has('Account','accountStatus','FROZEN').bothE('Payment').cc()`：前半段（分区扫描+属性条件+bothE）在 mapper，后半段 cc 交给 Gemini 集群（GremlinDistAlgorithms 早就有 cc(DIST)/pagerank(DIST)/louvain(DIST)/sssp(DIST)/bfs(DIST)，服务默认 4 worker 集群）。本轮改两处：① GremlinContext.distributedAlgorithms() 标志（runDistributed 置位；算法步骤注册三态：显式(DIST)→集群且无集群即报错、起点 DIST→集群（懒构造）、否则内存），于是 g.V(DIST) 表达"整条查询分布式"，不必每步重复 (DIST)；② GLOBAL_STEPS 补上 cc/louvain/pagerank/sssp/bfs —— 之前漏了会导致 g.V(DIST)…cc() 每个 mapper 各算一份（新单测第一次就抓到 1 vs 4）。验证：单测 GremlinDistributedScanTest#aDistributedStartRunsItsAlgorithmOnTheClusterToo（真起 GeminiCluster.local()，比等价类、断言只回一个 map）；线上 .logs/verify-dist-cc.js 两组均"同组"（FROZEN→payments→cc：单机 263ms / 分布式 884ms，都是 51 点 1 分量；全量：24 / 359ms 同行）。**注意**：小图上分布式更慢（集群起停+超步同步），价值在规模；组件号是标签，只能比等价类；未知属性在分布式下会报错（单机 filter 下推会静默返回空）——这是有意的行为差异，记在 §86.3。graph-query-gremlin 251 用例绿；UI：payment-graph-rocksdb-edge 新增 3 条（单机 cc / DIST 版 cc / 同 DIST 换 pagerank）。**
**第三十八批（2026-09-25，§87/§88）：复核 step 与脚本语法的支持边界（探针实测）。** 先 `grep register("` 枚举**96 个已注册 step**，再拿 71 条 step 探针 + 21 条谓词探针 + 68+59 条 Groovy 探针打真在跑的 8081，把引擎原话记下来。**最重要的发现：P 谓词静默给错** —— 51 个账户（CLOSED/ACTIVE/FROZEN 各 17）下 `is('CLOSED')`=17 ✓、`is(eq('CLOSED'))`=**0** ✗、`is(neq('CLOSED'))`=**51** ✗、`is(within(...))`=34 ✓、`is(gt/gte/lt/lte)` ✓、`is(between/inside/outside/not/and)`=**0** ✗、`has(k, eq/neq/gt/between/not)` **全 0** ✗；`filter(k.equals(v))`=17 ✓。即只有 gt/gte/lt/lte/within/without 被真正求值，其余既不报错也不生效 → 建议**优先级第 1**：谓词编译成统一 Predicate + 未知谓词报错（兜底当字面量比较正是根源）；副作用是行为变更（现在返回 0/全量的查询会返回真结果）。**比上一版表更好的**：`has(key, within(...))`、`has(key, startingWith(...))`、`by(traversal)`、`elementMap(keys)` 实测已支持；**一条偏差**：子遍历不能写 `__`（引擎要 `union(out('Payment'))`），支持它只是一行预处理。脚本：**48/127 接受**（batch1 44/68、batch2 4/59），拒绝基本都有明确消息；但有三处**静默错**：`'''多行'''` 被当普通字符串、`=~` 某些写法被当字符串、`count()+count()` 得到文本 `[3][9]`（遍历结果是 List）→ 建议先修这三处，否则"支持边界"不可信。其余按难度/副作用分档（§88.4）：零风险一批（`+=`/`++`/do-while/switch/三元/elvis/assert/三引号/sprintf/更多闭包方法）、低-中风险二批（列表索引含负索引、Map 字面量、字符串与数字方法白名单）、要权衡（静态方法白名单=沙箱面、一等闭包）、**明确不做**（try/catch 会吞真错误、GString 与 HTTP 参数替换冲突、类/导入/任意 new/IO/线程=通用运行时）。产物：`.logs/probe-steps.js`、`.logs/probe-predicates.js`、`.logs/probe-groovy2.js` 与对应 coverage 文本。**
**第三十九批（2026-09-25，§89/§90）：按复核结论落地支持。** ① **P 谓词全量求值 + 未知谓词报错**（`GremlinPredicate` 重写，`is(...)`/`has(key,...)` 共用一条路径；根因是 `is` 那套正则把**带引号的参数**直接比较、且 `has` 完全没有谓词分支）；线上前后对比：`is(eq)=0→17`、`is(neq)=51→34`、`is(between/inside/outside)=0/0/0→3/1/20`、`is(not(within))=0→34`、`is(and(...))=0→2`、`has(k,eq/neq/gt/between/not)=全0→17/34/1/1/34`。② `hasLabel(name...)`。③ `outV()/inV()/bothV()`（端点走 `otherV()` 的批量读，`hopEdges` 早把读到边的点写进 `sourceVertexId`）。④ 接受 TinkerPop 匿名标记 `__`（`union(__.out('Payment'))`）。⑤ 脚本三处静默错全修：`'''多行'''` 成一个值、`'abc' =~ 'b'` 改为**报错**、`+` 改 Groovy 语义（String 任一侧→文本、List→追加，故 `count()+count()`=`[3, 9]`）。⑥ 脚本新增：`+= -= *= /= %=`、`x++/++x/x--/--x`、三元、elvis、括号分组、三引号；顺带修了"行尾 `++` 被当续行"与"表达式无括号"两个探针照出来的问题。测试：`graph-query-gremlin` **268 绿**（新增 `GremlinPredicateTest` 8）；线上 `.logs/verify-support-batch.js` **21/21**，谓词对照 `predicate-coverage.txt`/`-after.txt`。**副作用**：谓词与 `+` 的修复都是**行为变更**（以前静默 0/全量的查询现在返回真值；`'text'+list` 仍是文本，原用例钉住）。**仍未做**：`as/select/project/...`（需 `Traverser` 模型）、`do/while`、`switch`、更多闭包与字符串/数字方法、列表索引、Map 字面量；`try/catch`/GString/类/IO/线程继续刻意不支持。**
**第四十批（2026-09-25，§91）：脚本语法第二梯队。** 新增 `assert`（含 `: message`）、`do/while`、`switch`（`==` 匹配、首个命中、**不 fall-through**）、下标 `xs[i]`（负索引、越界报错）与 `s[i]`/`m['k']`、Map 字面量 `[a: 1]` + `m.a`、集合方法（`unique/flatten/take/drop/withIndex/toSorted/sort/max/min/sum/inject(seed){}`）、闭包方法（`sort{}/max{}/min{}/groupBy{}/collectEntries{}`，比较器接受 `<=>` 或布尔）、文本方法（`toUpperCase/toLowerCase/trim/length/startsWith/endsWith/substring/replace/split`）、数值方法（`abs/intValue/toInteger/toDouble/round`）、`sprintf/printf`、太空船 `<=>`、括号分组（含 `(-3).abs()`）。过程中探针照出并修掉三个解析问题：`inject(seed) { ... }` 的闭包要并入同一次调用、`m.b` 之前报 `Expected '('`、分组只认"配对括号在末尾"。验证：`graph-query-gremlin` **273 用例绿**；线上 `.logs/verify-script-tier2.js` **19/19**。**仍未做**：一等闭包与静态方法白名单（沙箱面）、GString（与 HTTP 参数替换冲突）、`try/catch`（会吞真错误）、类/导入/new/IO/线程；step 侧 `as/select/project/match/sack/...` 仍等 `Traverser` 模型。**
**第四十一批（2026-09-25，§92/§93）：复核清单第 2 项实现 + 第 1 项设计。** 实现：`properties()`（新记录 `GremlinProperty`，加入 `GremlinElement` 的 permits）、`key()`/`value()`、`math('_ * 2 + 1')`（自写算术求值器，整型进整型出，非数字报错并提示先 values()）、`format('%{id}: %{status}')`（`%{key}`/`%{_}`，键不存在报错）、`dateTime()/dateAdd(unit,n)/dateDiff(unit,other)`（数字进数字出、文本进文本出，支持 `DT.day` 与复数）、`profile()`（终结步：逐步 in/out/millis + 总耗时/遍历器数，记录器只在需要时创建→普通查询零成本）、`explain()`（终结步：编译计划、每步属性投影 none/all/列表、pathAware、verifySeeds）；投影规则把这几步算作"需要全部属性"。设计：`TRAVERSER-DESIGN.md` —— 给遍历器加状态（`GremlinTraverser{value,path,labels,sack,bulk}` + `TraverserContext`），但**两条路径由编译器选**（无状态 step → 今天的快路径，零影响；有 → 新路径，通过适配器**复用现有 step 实现**），跳步在新路径上仍按顶点 id 批读，`DIST` 与状态 step 明确互斥；四批落地与每批验收（现有 275 用例全绿 + "同一查询两条路径答案一致"对照）都在文档里。验证：`graph-query-gremlin` **275 用例绿**；线上 `.logs/verify-medium-steps.js` **12/12**。**
**第四十二批（2026-09-25，§94）：`Traverser` 第一批（`as/select/project` + `where('a',P)`）启动实现。** 按 `TRAVERSER-DESIGN.md` 第 1 批落地，**验收条件"既有 275 用例一个不改、全绿"已达成**（现共 **282**）。机制：`GremlinTraverser{value,labels,path}`；`CompiledQuery.traverserMode` **编译期**判定（计划里有 `as/select/project` 或两参 `where(标签,P)` → 状态路径，否则原快路径一字不改）；状态路径上只有这 4 个 step 有新实现（`GremlinTraverserSteps`），其余 step 走**适配器**调用**同一个** `GremlinStep` 再按对象同一性/下标/首个把 labels 带回去；跳步暂时逐个 traverser 跑；投影规则把这几步算作"需要全部属性"。过程中修掉三处：两参 `where(label,P)` 被编译期当子遍历路由、`project/select` 不在"需要全部属性"名单导致 `by('name')` 读 null、语句切分器把行尾 `++` 当续行。限制（§94.2）：跳步未批读（下一片做 native 批量跳步）；`path()` 与标签互斥报错（第 3 批）；`where('a',eq('b'))` 的标签作参数形式已实现但暂无测试（删掉了测错期望的断言，不留不确定行为）。新增 `GremlinTraverserStepsTest` 7 个用例，含"同一查询两条路径答案一致"的 4 组对照。**
**第四十三批（2026-09-25，§94.4）：Traverser 第一批收尾。** ① **批量跳步已实现**：状态路径上的跳步不再逐个 traverser 跑——新增 `GremlinSteps.hopPerInput(ctx, in, step)`，用**同一个批量读**（`readEdgesByVertex` + 一次 `readNeighbourVertices`）读完整跳，再按"每个输入的边数"切片回各自结果（边跳带 `sourceVertexId`，点跳切一次批量点读），`GremlinGraphQuery.traverserHop` 改用它，**存储调用回到"一跳一次（每类一次）"，与列表路径一致**。② `where('a', eq('b'))`（标签当谓词参数）**确认是缺口**：`g.V('a','b','c','Account').as('x').values('status').as('s').where('s', eq('s')).select('x')` 返回 `[]`（应为 `[a, c]`），根因未定位；测试**故意断言今天的空结果**并写明 known gap，修好后改成 `[a, c]` 即可。③ 计划里第 2、3 批（`sack/withSack` + `aggregate/store/sideEffect/cap`；`path()` 并入 + `match/branch/emit/until/loops` + `subgraph/tree`）**本轮未开工**，设计仍在 `TRAVERSER-DESIGN.md` §5。`graph-query-gremlin` **282 用例绿**（既有用例一个未改）。**
**第四十四批（2026-09-25，§94.5）：修掉 `where('a', eq('b'))` 的标签参数缺口。** 根因是把绑定标签写进谓词文本时值被在原有引号里又加了一层引号（`eq('s')` → `eq(''ACTIVE'')`），于是谓词把 `''ACTIVE''` 当字面量比较、什么都不匹配（正是 §94.4 钉住的那条空结果）。修法：识别"词本来就在引号里"，连引号一起替换（`eq('s')` → `eq('ACTIVE')`）。验证三条自证用例：`eq('s')` 自比保留全部（若不替换会拿状态比文本 `'s'`，一个不留）、`within('s')` 按每个 traverser 自己的标签值解析、跨标签 `where('n', eq('v'))` 一个不留。`graph-query-gremlin` **283 用例绿**。计划里第 2 批（`sack/withSack`+`aggregate/store/sideEffect/cap`）与第 3 批（`path()` 并入 + `match/branch/emit/until/loops` + `subgraph/tree`）仍未开工。**
**第四十五批（2026-09-25，§95）：Traverser 第二批 —— 副作用。** 新增 `withSack(seed)`（`GremlinTraverser` 加第 4 个分量 `sack` 并保留三参构造）、`sack()`（值换成 sack）、`sack(assign|sum|min|max).by(键)`、`aggregate(name)`/`store(name)`（本次运行的命名容器，traverser 透传）、`cap(name)`（用容器内容结束遍历，名字未被填充则明确报错并提示先 aggregate/store）、`sideEffect(child)`（按 traverser 跑子遍历、值透传）；六个名字进入 `STATEFUL_STEPS` 与"需要全部属性"投影规则，`by()` 折叠扩展到 `sack`。**两处有意的语义差异**：aggregate 与 store 的 barrier/惰性差异在列表形状 step 里表现不出（内容相同）；`sideEffect` 子遍历内部的副作用暂不进入外层容器（需把容器挂到 context，留到第三批与 `path()` 合并时做）。测试：`graph-query-gremlin` **287 用例绿**（既有用例一个未改；`GremlinTraverserStepsTest` 12 个，新批次 4 个含两组"两条路径答案一致"对照）。第三批（`path()` 并入 + `match/branch/emit/until/loops` + `subgraph/tree`）仍未开工。**
**用户原话、被否掉的方案、实测数字：`DAILY-2026-09-21.md` §1–§22 与 `DAILY-2026-09-23.md` §1–§14。**
**现场（2026-09-23 收工时，可直接续做）：8081 `graph-http` + 8080 `graph-web` + 8083 `graph-http --profile hnsw-dynamic` 三个 JVM 都在跑，Aerospike 容器在跑；启停在下一节。**
**8081 服务 4 个图：`payment-graph`（Aerospike，`http_demo_v3`）、`payment-graph-as-edge`、`payment-graph-rocksdb-edge`（`.rocksdb/payment-graph-rocksdb-edge`）、`kg-entity-graph`；其余 7 个在 `application.yml` 里注释保留。**
**数据与索引状态（09-24）：`http_demo_v3` 有 fuzzy 环（`fz_a1..fz_a7`，含 xia1999）+ 12 条事件会话（`ev_a1..ev_a9` + `ev_b1..ev_b3`，由 `FuzzyLinkDemoLoader` 播种，跑 `./mvnw test` 或 `FuzzyLinkIntegrationTest` 即重建；load 会先清掉上一轮留下的旧链接节点/边）与它们的连接节点；`payment-graph` 与 `payment-graph-rocksdb-edge` 的 HNSW 索引都已建（152 / 51 点）；`fuzzy-patterns.json` 有 6 个模式（#0/#1 平铺，#2–#5 事件序列）；UI 的 `payment-graph` 示例查询里有 8 条序列例子（已实测可跑）。**
**起停一条命令：`tools/start-phoenixgraph.sh [--no-build] [--with-hnsw]`；日志 `.logs/<服务>.out`；停 `tools/stop-phoenixgraph.sh`。改了 UI/资源/引擎一定要**重新建包**（服务读的是 jar 里的 `static/**`）。**
**下一次入口（按优先级）：1) §66.2 给 RocksDB-Edge 加 `edge-id:<类>` 列族做边 id 索引（现在 `g.E($id,类)` 是 O(类内边数) 顺序扫描，200 条 6–7 ms）；2) §65 的图下拉只列"被服务的"图（重启后自建的 `*.json` 会消失，应该同时扫工作目录）；3) §62.3 剩下的：`nearest(10)` 对 10 个 traverser 做 10 次独立 HNSW 搜索（这条语句 78% 的时间），要共享/合并搜索；4) §72 收尾：`path()` 目前只支持 hop 链 + `has/filter/limit/dedup`（`path()` 后不能接 hop）；`nearest`/`circle` 在 `path()` 前会明确报错；`joinOnEnd` 是脚本级的路径拼接（要做成通用 step 的话需要先有 `as`/`where`）；5) §57.9/§56.7/§51.6/§53.4 的旧清单（`new` 只支持三种集合、barrier 仍是批量大小别名、投影上限 40、平行边"×N"、`vt` 进边读、limit 有序表示、RocksDB stale 不一致、UI 镜像边折叠、`map` 聚合）。**

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
**第二十三批（§69，序列模式第二轮）：`FuzzySequence` 支持双向窗口 `[A<x<B]`（`GAP_RANGE`、`minSeconds`、带回溯的匹配）与条件括号（新类 `FuzzyCondition`：`== != && || ()`，字段路径读各步骤已匹配事件，缺失即 false）；`FuzzyPatterns.readExample` 修嵌套对象被摊成空串；`FuzzyDemo` 的 #3 换成 `ip2 + 双向窗口 + [Dispute.ip==Login.ip]`、新增 #4（`[Login.type=='Mobile']` + 三选一 OR）；`fuzzy-patterns.json` 同步；`FuzzyLinkDemoLoader` 会话 5→9 条、列 5→9 个，并在 `load()` 前清掉上一轮旧链接（`clearEdgeClasses` + 扫 `key` 删 `fl_` 点）；`app.js` 序列示例 4→6 条。测试：`FuzzySequenceTest` 15、`FuzzyLinksTest` 21、fuzzy 共 54；集成 `FuzzyLinkIntegrationTest` 14 + 无存储的 `FuzzySessionGroupingTest` 3。8081/8080/8083 已用新包重启，`node .logs/verify-sequence-v2.js` 实测：#3 → {ev_a6,ev_a9}、#4 → {ev_a6,ev_a7} 与 {ev_a8}。**
**第二十四批（§70，序列模式第三轮）：`FuzzyFunctions` 新增**上下文函数**类别与 `anyFields([Event,...], n[, bool])`（跨事件共有字段，忽略 id/时间/事件名，字母序；bool=true 带值）；`FuzzyExpression` 支持列表字面量与 `true/false`；`FuzzySequence` 的步骤值改用整个匹配结果求值、值/条件校验移到叶子（为 UDF 能看到多事件）。`FuzzyDemo`/`fuzzy-patterns.json` 加 #5；`FuzzyLinkDemoLoader` 会话 9→12 条（`ev_b1..ev_b3`）、登录加 Cookie、支付/争议加 IP+Type。测试：`FuzzySequenceTest` 19、fuzzy 共 58；集成 `FuzzyLinkIntegrationTest` 15 + `FuzzySessionGroupingTest` 4。8081 实测：`fl_5_10.122_cookie_ip_type` members {ev_a1,ev_a2,ev_a5}，`ev_b2`/`ev_b3` 不适用（脚本 `.logs/verify-anyfields.js`）。**
**第二十五批（§71，Groovy 风格脚本）：`graph-query-gremlin` 新增 `GremlinScript`（语句拆分、`def`/赋值/`print`/`return`、变量、`g.V(var,...)` 替换、`size()/get()` 等链、字面量列表；只收集子图与值，不执行任意代码）；`QueryRequest` 加 `Boolean script`、`QueryService.queryCapped` 加 8 参重载与 `ScriptResponse{result,output,subgraph}`（不传 `script` 就按文本自动判定），`QueryController` 透传；`index.html`/`app.js` 加管理员开关（`IS_ADMIN`/`SHOW_SCRIPT_MODE`，默认开）与脚本示例、`localStorage` 记忆。测试：`GremlinScriptTest` 11、gremlin 共 204、http 共 73（`QueryControllerTest` 12）。顺带 `POST /api/v1/vector-index/rebuild` 重建 payment-graph HNSW（152 点 / 9 层 / 2883 链接，`nearest(5)` 恢复）。**
**第二十六批（§72，隐藏索引边 / path / 四跳脚本）：`GremlinContext.hiddenEdgeClasses` 让未命名 hop 不读向量层边（命名 hop 与 `nearest` 不受影响）；新类 `GremlinPath` + `path()`（`runPathCompiled` 路径感知走链，hop 追加边/到达点，filter 丢整条；`path()` 后不能接 hop）；`GremlinStepRegistry` 注册 `path`；脚本补 `last/first/start/end/reverse/contains/indexOf/add/addAll/retainAll/intersect/distinct/join/joinOnEnd` 与表达式 `+`，并改口径（最后是 print 时打印值为答案，最后是表达式时表达式为答案）；`fuzzy-patterns.json` 不变，UI 示例加 `path()` 与四跳脚本。测试：`GremlinPathTest` 4、`GremlinScriptTest` 17、gremlin 共 215、http 共 73。`.logs/verify-paths.js` 实测。**
**第二十七批（§73，四跳第二跳 outE）：四跳脚本的 sender/receiver 第二跳改成 `outE('Payment')`（`path()` 以边结尾）；`GremlinScript` 加 `endIds()`（末元素是边就用它的 `otherVertexId` 当落点，不读点）与 `joinOnEnd(other, middleObjects)`（末元素是边时用第 2 步的点对象填公共点）；UI 示例改写成 `outE` 版。结果与改前完全一致（33/9/[1008,1014,1020,1026]/4/30/72，一条四跳 1000→1001→1008→1007→1006）。基准 `.logs/bench-path.js`（100 次交错，计数器取差值）：**B(outE) 29.20 ms vs A(out) 34.72 ms（−15.9%），kv 23 vs 33、点读 34 vs 44、边读 8/8、响应 139.56 vs 154.89 KB**。测试：`GremlinScriptTest` 18、gremlin 共 216。**
**第二十八批（§74，Groovy 控制流 / step 兼容表）：`GremlinScript` 的语句模型改为语句树，新增 `if/else`（含 `else if`）、`for (x in ...)`、`while`（10 万次上限）、`break/continue`；闭包 `{ ... }`/`{ x -> ... }` 作为 `each/eachWithIndex/collect/findAll/find/any/every/count/sum` 的参数（`it` 或具名参数，作用域保存/还原）；算术 `- * / %`（整数保持整数，`/` 浮点）与 `!` 真值；元素方法 `it.id()/label()/value(k)/values(k...)/properties()`；字面量上的方法 `'abc'.size()`。步骤侧新增 26 个 map/filter/reduce step（`identity/constant/id/label/hasId/hasNot/hasKey/hasValue/is/range/skip/tail/sample/fold/unfold/sum/mean/max/min/order/groupCount/valueMap/toLower/toUpper/trim/length`）与 6 个子遍历分支 step（`union/coalesce/local/not/and/or`，在 `GremlinGraphQuery` 里注册并复用 registry）；`GremlinQuery.splitArgs` 改为按括号深度切分，支持 `union(out('a').has('x','y'), out('b'))`；`neededProperties` 对读属性的新 step 一律投影全属性。§74.2 写出 TinkerPop 3.8.2 逐条 ✅/🟡/❌ 兼容表（最大的缺口是 step label/副作用：`as/select/where/match/project/sack` 与 `repeat/choose/optional`，以及写入 step 本来就走 HTTP 写接口）。测试：`GremlinScriptTest` 27、`GremlinTinkerPopStepsTest` 11，gremlin 共 236。8081 实测：`hasId/hasNot/hasKey/hasValue/is/order/groupCount/union/coalesce/not` 与一段 `for`+`if`+闭包脚本全部可跑。**
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
