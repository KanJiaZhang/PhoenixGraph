# 数据质量台账（Data quality ledger）

> 这份文档把**在实例里实测到的、会让分析结论出错的数据问题**集中记录，每条给出：现象 → 证据查询 → 影响 → 处理。
> 台账只写**当次实测**的结论；数字可用 `node verification/run.mjs --only agent-cases` 复跑（该检查逐条断言本文引用的数字）。
>
> 记录时间：2026-10-13，服务 `localhost:8081`（`graph-web` 8080 / `graph-http` 8081）。

---

## 1. `tpc-h-data`：`nation → region` 的赋值是轮转的，不是 TPC-H 语义

| 项 | 内容 |
|---|---|
| **现象** | 25 个国家里只有 **7 个**落在 TPC-H 标准大区。美国 → MIDDLE EAST、中国 → EUROPE、印度 → EUROPE、巴西 → ASIA、法国 → AMERICA、沙特 → AFRICA、肯尼亚 → MIDDLE EAST |
| **证据** | `g.V('Nation').limit(30)` → 逐条对回标准映射（脚本内断言 7/25 与上述 7 个错例） |
| **影响** | **任何 per-region 的排名、对比、下钻都无意义**（例如"ASIA 收入第一"来自错误的分组）。以 region 为维度的指标、看板、semantic layer 查询都不可交付 |
| **处理** | 重播时按 TPC-H 标准赋值 `nation → region`；在修好前，口径里禁用 `region` 维度（`docs/AGENT-CASES.md` 案例 B 给了可交付的替代分析） |

## 2. `tpc-h-data`：`region` 与 `segment` 是同一份数据的两个标签

| 项 | 内容 |
|---|---|
| **现象** | 按 `region` 与按 `segment` 聚合出的**五组收入到分位完全相等**：AFRICA=BUILDING 38,430,001.37 · AMERICA=AUTOMOBILE 38,884,837.91 · ASIA=MACHINERY 39,515,305.18 · EUROPE=HOUSEHOLD 39,187,823.75 · MIDDLE EAST=FURNITURE 38,474,258.73 |
| **证据** | `g.V('Sale').group().by('region').by(values('revenue').sum())` 与 `.by('segment')` 对照（脚本断言五对相等） |
| **影响** | 两个维度**不能互相验证**，交叉分析、下钻、按细分的差异化结论都会自我循环 |
| **处理** | 让 `region` 与 `segment` 取独立来源（现在同一个轮转下标同时生成两者） |

## 3. `tpc-h-data`：`lateDelivery` / `returned` 存的是整数，布尔谓词静默返回空集

| 项 | 内容 |
|---|---|
| **现象** | `has('lateDelivery', true)` → **0 行**（不报错）；`has('lateDelivery', 1)` → **771 行**。`returned` 同理：`true` → 0，`1` → **351** |
| **证据** | 三条对照查询；`values('lateDelivery').dedup()` 返回 `[0, 1]`；`hasNot('lateDelivery')` 为 0（字段无缺失） |
| **影响** | **"本数据没有延迟交付"这种结论会被静默地产生**——错在问句类型，不在数据。真实占比：晚交付 **9.64%**、退货 **4.39%** |
| **处理** | 统一成布尔存储，或在使用侧统一按 `0/1` 查；建议引擎在 `has(key, true)` 匹配到整数 `1` 时给出提示而不是空集 |
| **备注** | 本仓库 README 的一条旧注记曾据此断言"字段全为空"——**那是错的**，已按实测更正 |

## 4. `tpc-h-data`：`promotionRevenue` 属性不存在

| 项 | 内容 |
|---|---|
| **现象** | `Sale` 有 21 个属性，没有 `promotionRevenue`；语义层里却仍有促销相关指标（`metric/promotion_revenue`、`metric/promotion_effect`） |
| **影响** | **悬空血缘**：指标有定义、数据没有列，任何按语义层生成的 SQL 落到这个实例都会失败或静默为空 |
| **处理** | 补数据，或把这两个指标标为"本实例不可用" |

## 5. `sanguo-kg-text`：`killed` 关系整族来自演义侧（`provenance=EXTRA`）

| 项 | 内容 |
|---|---|
| **现象** | **300 条 `killed` 边全部 `provenance=EXTRA`、0 条 `DOCUMENT`**；对照组 `serves`(4,912) / `father`(1,351) / `brother`(2,628) 全部 `DOCUMENT` |
| **影响** | 用 `killed` 做史实判断会系统性出错。实测关羽的 10 条 `killed` 边里只有 **2 条**与正史一致（颜良、庞德）；华雄（正史：孙坚）、蔡阳（正史：刘备）、文丑（正史：曹操）另有其人；庞会的时间线自证错误（263 vs 关羽 219） |
| **处理** | 按来源拆分关系语义（`killed` vs `novelKilled`），或在查询层默认按 `provenance` 过滤；补正史侧缺失边（`孙坚→killed→华雄` 当前不存在） |

## 6. `sanguo-kg-text`：证据文本在 120 字处被截断

| 项 | 内容 |
|---|---|
| **现象** | `history` 最长 **120 字**，2,743 条里 **566 条触顶**；`novel` 803 条里 **218 条触顶** |
| **影响** | **"正史没写"与"摘录被切掉"不可区分**。沮授、郭图这类条目只能判"证据不足"，不能判"演义虚构" |
| **处理** | 全文入库，或在字段上标注 `truncated: true`，让分析能区分两种缺失 |

## 7. `TPC-H-ANALYTICS.md` §4/§5 的数字来自更早的构建

| 项 | 内容 |
|---|---|
| **现象** | 文档 §4 的大区表（EUROPE 4,118.6 万 …）与当前实例不符（当前 EUROPE 39,187,823.75 = 3,918.8 万）；§5 的 per-region 同比表（AMERICA +2,261,797 …）同理 |
| **文档自相矛盾** | 把文档自己的两张表相加：大区表合计 **193,850,000**，年份表合计 **194,492,227**，差 **642,227（0.33%）** —— 同一份文档的两张表对不上 |
| **年份表本身是对的** | 1995 = 95,612,794 / 1996 = 98,879,433 与当前实例一致 |
| **处理** | 重播后重算 §4/§5，或把这两张表标注为"历史构建"。**在修正前不要引用 §4/§5 的大区数字** |

## 8. `payment-graph` 等 Aerospike 图：删除后仍可读到（已修复，2026-10-13）

| 项 | 内容 |
|---|---|
| **现象** | `DELETE /api/v1/vertices/{id}` 返回 `SUCCESS`，但 `g.V('{id}','Account').count()` 仍是 **1**；重启服务后依旧 ✗ |
| **根因** | 删除是**软删除**：写入 `lastUpdatedTime = 0` 作为过期标记（`AerospikeVertexStore.deleteVertex`）。存储层有 `isExpired` 判断，但**所有读路径共用的 `isMissing` 只看 record 是否为 null**，没有看这个标记 ✗ |
| **影响** | 删除后的点依然出现在点读、批量读、扫描与子图展开里——「删了还在」；也解释了为什么恢复演练里 `asrestore` 会 `skipped` / `fresher` 而不生效 ✓ |
| **修复** | `isMissing` 现在把过期标记当成缺失（一处修复覆盖所有读路径）+ 单测 `AerospikeVertexStoreTest#aSoftDeletedVertexIsMissing` ✓ |
| **发现方式** | M2-4 的备份/恢复演练（写探针 → 备份 → 删除 → 断言已消失）——**计划里的验收标准把它逼出来了** ✓ |
| **验证** | 修复前写入并删除的探针重启后仍是 `[1]` ✗；修复后同一探针 `[0]` ✓，新的一轮「写→删」也是 `[1]→[0]` ✓；`verification/verify-backup-restore.mjs` 断言「删除后不可读、恢复后回来」✓ |

---

## 附：当前实例的权威数字（实测）

| 图 | 事实 | 值 |
|---|---|---|
| `tpc-h-data` | 点 / 边 | Sale 8,000 · Customer 500 · Nation 25 · Region 5 |
| `tpc-h-data` | 净收入 1995 → 1996 | 95,612,794.05 → 98,879,432.89（+3.42%） |
| `tpc-h-data` | 折扣额 1995 → 1996 | 4,897,014.95 → 5,180,314.11（+5.79%） |
| `tpc-h-data` | 晚交付收入 1995 → 1996 | 8,945,352.26 → 9,853,656.02（+10.15%，占比 9.36% → 9.97%） |
| `tpc-h-data` | 晚交付 / 退货 | 771 / 8,000（9.64%）· 351 / 8,000（4.39%） |
| `sanguo-kg-text` | 点 / 边 | 6,594 / 45,081 |
| `sanguo-kg-text` | 人物性质 | 史实 4,273 · 虚构 214 |
| `sanguo-kg-text` | 边来源 | EXTRA 24,147 · DOCUMENT 20,788 |

> 复跑：`node verification/run.mjs --only agent-cases`（断言上表与本文 1–6 条的全部数字）。

## 9. 写路径接受「无法读回」的值（已修复，2026-10-14）

| 项 | 内容 |
|---|---|
| **现象** | （1）`properties: [{"index": 99, "value": "x"}]` 返回 `SUCCESS`，之后 `GET /api/v1/vertices/{id}` 里出现 `"properties": {"99": {...}}`——schema 里没有 99 号属性 ✗；（2）`{"index": 2, "value": {}}`（把一个对象写进 `STRING` 属性）也返回 `SUCCESS`，读回 `"value": {}` ✗ |
| **根因** | 写路径只用 `DataQualityValidator` 检查**枚举取值与约束**，从不检查**声明类型**；`GraphService.checkPropertyValue(schema.property(index), ...)` 在索引不存在时拿到 `null` 就 `return`（静默通过）✗ |
| **影响** | 存储里出现 schema 未声明的 bin 与类型不符的值：`elementMap()` / `values()` 会把它读给任何查询者，schema 的可信度被写路径自己破坏 |
| **修复** | （1）`DataQualityValidator` 增加**声明类型检查**（BOOLEAN/INT/LONG/FLOAT/DOUBLE/STRING/OBJECT/enum/list/map，错误信息点明「值是什么、应该是什么」）；（2）`GraphService` 在写入前校验索引是否为 schema 声明（不在则 400 并列出已声明索引）；（3）id 属性（索引 0）豁免类型检查（id 在 API 里是 `Object`，演示数据就是给 `LONG` 的 idSchema 写字符串 id） |
| **发现方式** | 1.0 前的「边缘与失败场景」测试（`verification/verify-input-contract.mjs` 的前身探针）——**把「没人会这么写」的请求都发一遍** |
| **验证** | `verify-input-contract.mjs`：索引 99 → `400 Unknown property index 99 ... declared: [1 (accountStatus), ...]`；对象写入 STRING → `400 ... is not a STRING`；合法写入与 id 属性写入仍 `200` ✓ |

## 10. 悬空边静默创建「无类型顶点」（已修复，2026-10-14）

| 项 | 内容 |
|---|---|
| **现象** | 给两个不存在的 id 写一条边 → `200 SUCCESS`；随后 `GET /api/v1/vertices/ghost` 返回 `200 {"vertexId":"ghost","properties":{},"vertexType":null}` ✗ |
| **根因** | 边的存储方式：边写在**两端顶点的记录里**，记录不存在时 `AerospikeEdgeStore.upsertInto` 会按设计建一个最小顶点 stub 再把边挂上去 ✓（这是有意的，但对 API 调用者是不可见且静默的 ✗） |
| **影响** | 一个 id 打错字就会**凭空造出一个顶点**：它没有类型、没有属性，出现在扫描与 `elementMap()` 里，把「图里有什么」变得不可信 |
| **修复** | 写边前检查两端顶点是否存在（`phoenixgraph.write.require-existing-endpoints`，默认 `true`）→ 缺失时 `404` 并点名缺失的那一端与开关；批量写把**同一批次里写入的顶点**视为存在（一个文件里的点+边仍然一次写完）；需要「先边后点」的导入显式关掉该开关 |
| **发现方式** | 同上一轮边缘测试（探针写了一条指向 `ghost` 的边，再点读 `ghost`） |
| **验证** | `verify-input-contract.mjs`：悬空边 → `404` 且之后 `GET /api/v1/vertices/zz-ghost-*` 仍是 `404`（没有 stub）✓；同批次点+边 → `200` ✓；`verify-multi-instance.mjs` 25/25 ✓ |

## 11. 客户端错误被当成服务器错误（已修复，2026-10-14）

| 项 | 内容 |
|---|---|
| **现象** | 一批「请求本身不合法」的输入返回 **500**：缺少 `query` 字段、缺少 `graphName`、缺 `id`、`properties: [null]`、`DELETE /api/v1/edges/{id}` 缺参数、`PUT .../properties/abc`、请求体不是 JSON、数组代替对象、请求体为空 ✗ |
| **根因** | 这些异常（Spring 的 `HttpMessageNotReadableException` / `MissingServletRequestParameterException` / `MethodArgumentTypeMismatchException`，以及 `toVertexUpsert` 里的 `Objects.requireNonNull`）都不在 `ApiExceptionHandler` 的映射里，落进 `Exception` 兜底 → 500（且响应体里还带着 Java 堆栈 ✗） |
| **影响** | 调用方按 500 处理（重试、报警「服务坏了」），而真正的问题是请求；错误信息指向 `java.lang.NullPointerException` 这种对使用者无意义的细节 |
| **修复** | 见 `docs/API.md` §3 的状态码表：400/404/405/409/413/415 各有明确映射；`Objects.requireNonNull` 换成带字段名的校验；`ApiExceptionHandler` 的 500 改为**不回显内部信息**，堆栈默认**不返回**（`phoenixgraph.errors.include-stack-trace` 打开才返回） |
| **发现方式** | 同上（探针把 40+ 种畸形输入发了一遍，逐条记录状态码与响应体） |
| **验证** | `verify-input-contract.mjs` 78 条断言，其中三条是**跨全部用例的规则**：客户端错误从不为 500、响应体不含堆栈、错误体形状一致 ✓ |
