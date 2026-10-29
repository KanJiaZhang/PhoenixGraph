# 智能问数 + 业务分析归因：设计与交付契约

这份文档是接下来三个交付物（语义层 API/MCP、智能问数、业务分析与归因）的**接口契约与实施计划**。先写它，是因为勘查阶段发现了一件会影响实现方式的事（见 §3），必须先定下来再写代码。

## 1. 现有资产清点（实测，不是设想）

| 资产 | 事实 |
|---|---|
| 语义层**本身就是一张图** | `tpc-h-analytics` 图里是 `Node` 顶点（`entityType` 区分 metric/dim/column/table/query）+ `Relation` 边。**服务里实测**：**18 个指标**、**11 个维度**、**12 条参考查询**（带原始 `sqlText`；数据集里另有若干条已定义的查询未随图发布） |
| 语义边的语义 | `groupedBy`（指标可由哪些维度切片）、`usesColumn`/`computedFrom`（血缘）、`dimensionColumn`（维度→列）、`references`（外键，用于 join 路径）、`definedBy`（指标→参考查询）、`contributesTo`（归因：派生指标由哪个基础指标驱动） |
| 顶点属性 | `name, entityType, description, alias, dataType, columnRole, tableName, aggFunction, expression, metricKind, unit, sqlText, queryNumber` —— 注意 **`alias` 已经存在**，中文同义词就在模型里（例如 `dimensionColumn` → 「维度字段/维度」）|
| 事实图 | `tpc-h-data`（RocksDB-Edge）：`Sale` 把维度**反范式化**到同一个顶点上 —— `Sale: revenue, discount, tax, quantity, gross, discountAmount, taxAmount, lateDelivery, returned, region, nation, segment, priority, shipmode, partType, brand, supplierName, orderYear, orderMonth`；边缘是 `ofPart/ofSupplier/ofOrder/placedBy/customerNation/supplierNation/nationRegion` |
| 现有 agent 工具 | 11 个端点，**全部是确定性检索原语**（`/tools /ingest /index/{graph} /quality/{graph} /find-entities /related /similar /related-similar /similar-related /path /context`）；仓库里 **0 个 LLM 依赖** |
| 现有语义工具 | `tools/semantic-sql.mjs`：由**查询图**得到指标表达式与血缘，BFS 求 join 路径，按方言渲染 SQL（`postgres|duckdb|mysql`），支持 `--grain raw|year|quarter|month`、`--expect` 校验、`--json` |

## 2. 架构：四层，全部可离线、可审计

```
① 语义模型（图内数据，单一来源）
      metric/dim/column/table/query 顶点 + Relation 边 ──► SemanticModel（Java，按图缓存）

② 解释器（确定性 NLU，无 LLM）
      alias 同义词表 + 时间/过滤/粒度/TopN/对比 词法 ──► Interpretation
      （metric, dimensions, filters, grain, timeRange, order, limit, kind=ASK|ANALYZE|ATTRIBUTE,
        confidence, matchedTerms, refusal?）

③ 计划与确认（无状态，不依赖会话）
      mode=auto     → 直接执行
      mode=confirm  → 只返回 Plan（SQL/遍历、范围、成本估计）+ 签名令牌
                      HMAC(secret) 覆盖 {planHash, expiresAt, graph} ──► 客户端回传令牌执行
      （确认流程做成无状态令牌，而不是会话：服务端不保存会话；协议修订与客户端协商，见 `PROTOCOL_VERSIONS`）

④ 执行
      图上执行：g.V(<measureType>).group().by(<dimProp>).by(values(<measure>).sum())
                —— 仅当指标与全部维度都落在同一个事实顶点类型上（本数据集里 Sale 承载全部维度）
      SQL 翻译：translate 端点输出 SQL（导出到数仓/对照）
      分析/归因：在 ④ 的结果上做纯计算（无副作用、可单测）
```

**为什么不做 LLM 依赖**：产品的定位是"数据不出域的私有化可审计层"。解释器是确定性的、词表来自模型里的 `alias`，因此**答案可复现**；要接 LLM 时，把 LLM 的输出当作 `Interpretation` 的候选，**经 ③ 的确认门**再执行即可——LLM 只做前端，不决定口径。

## 3. 勘查发现的一个阻塞项（必须先解决）

**语义模型里没有"语义列 → 图属性"的绑定。** 证据：语义列 id 是 TPC-H 列名（`column/region.r_name`、`column/customer.c_mktsegment`、`column/orders.o_orderpriority`），而事实图的属性是短名（`Sale.region`、`Sale.segment`、`Sale.priority`）。两者**对不上**，且任何"猜"（子串/去下划线匹配）都只能覆盖 9/11，剩下 2 个（`return_flag` vs `returned`、`line_status`）会**静默返回看似合理的错数**——这正是本项目一贯拒绝的行为。

**决定**：把绑定变成模型里的**数据**，而不是代码里的启发式。

- 在 `Node` 顶点上加可选属性 `graphProperty`（维度/列上），必要时加 `graphType`；
- 由事实图与语义图的**共同写入方**（`TpchDataAgentDataset`）声明，两个图因此不可能各自走偏；
- 事实顶点类型的解析规则（确定性）：**指标属性所属的类型**（`revenue` → `Sale`，唯一）作为基准类型，随后要求每个维度的 `graphProperty` 也存在于该类型上；不满足就**拒绝**并给出原因 + SQL；
- 该属性是**向后兼容**的新增（schema 已带 `schemaVersion`），只需重播小图 `tpc-h-analytics`（约 90 个顶点），事实图不动。

## 4. HTTP 契约（新增端点，全部 `/api/v1`）

| 方法 | 路径 | 作用 | 关键参数 |
|---|---|---|---|
| GET | `/semantic/{graph}/model` | 语义模型：指标、维度、血缘、参考查询、**绑定**与模型版本 | `?dataGraph=` |
| POST | `/semantic/{graph}/translate` | 语义查询 → SQL（不执行） | `{metric, dimensions[], grain, dialect, filters[], order, limit, dataGraph}` |
| POST | `/semantic/{graph}/ask` | **智能问数**：自然语言 → 答案 | `{question, mode: "auto"\|"confirm", confirmToken?, dataGraph}` |
| POST | `/semantic/{graph}/validate` | 校验语义请求/生成的 SQL（dry-run） | 同上 translate |
| POST | `/analysis/{graph}/analyze` | **业务分析**：趋势、Top 变化、集中度、异常 | `{question?, metric, dimensions[], periods[], mode, confirmToken?}` |
| POST | `/analysis/{graph}/attribute` | **业务归因**：把变化分解到维度贡献 | `{question?, metric, by[], from, to, mode, confirmToken?}` |

**回答形状（统一）**

```json
{ "mode": "auto",
  "answer": { "rows": [ {"region": "ASIA", "revenue": 95612794.05} ], "total": 95612794.05 },
  "plan": { "kind": "ASK", "metric": "metric/revenue", "dimensions": ["dim/region"],
            "grain": "year", "sql": "SELECT ...", "graph": "g.V('Sale')...", "binding": {...} },
  "provenance": { "semanticModel": {"graph": "tpc-h-analytics", "modelVersion": 1},
                  "metricExpression": "revenue", "metricDefinition": ["column/lineitem.l_extendedprice", "..."],
                  "joinPath": [...], "executedOn": "graph", "rowCount": 5 },
  "confidence": { "score": 0.92, "matchedTerms": ["营收", "按地区"], "alternatives": [...],
                  "warnings": ["region 与 segment 在演示数据里共用索引，见 docs/DATA-QUALITY.md §4"] },
  "limits": ["描述性统计，不是因果结论"] }
```

`mode=confirm` 时返回 `{ "mode": "confirm", "plan": {...}, "confirmToken": "...", "expiresAt": "..." }`，**不执行**；客户端带令牌再次调用即执行（令牌与计划哈希绑定，改一个字段即失效）。

## 5. Agent tool / 插件契约（MCP）

`tools/mcp-server.mjs`：无状态 MCP（stdio 为主，可选 HTTP），工具从 `/api/v1/agent/tools` 读取，**不在插件里重复定义**。

| 工具 | 参数 | 模式 |
|---|---|---|
| `ask_data` | `question, graph, mode, confirmToken?` | auto / confirm |
| `translate_semantic` | `metric, dimensions[], grain, dialect, graph` | — |
| `analyze_business` | `question?\|metric, dimensions[], periods[]` | auto / confirm |
| `attribute_change` | `metric, by[], from, to` | auto / confirm |
| `graph_context` / `related` / `similar` / `path` / `quality_report` | 现有 11 个端点 | — |

## 6. 能力边界与拒绝规则（宁可拒绝，不猜）

拒绝时返回 `200 + plan.refusal`（或 `400`，视参数而定），并给出**为什么**与**可以怎么做**：

1. 指标无法唯一匹配（多个候选）→ 列出候选，要求澄清；
2. 维度不在该指标的 `groupedBy` 集合里 → 400 并列出可用维度；
3. 维度缺少 `graphProperty` 绑定 → 明确说明并在 `plan` 里给出 SQL 版本（数仓可执行）；
4. 指标与维度不在同一事实顶点类型 → 同上（图上执行不可表达时必须说清，不能改口径）；
5. 问题超出模型能力（例如问"为什么"却没有可归因维度）→ 建议改用 `attribute_change`；
6. 时间范围/粒度在模型里没有对应列 → 拒绝。

## 7. 评估台账（交付物 3：「答案级」回归）

`verification/eval-data-agent.mjs`：golden set **五类**，每题断言"答案 + 必须引用到的来源 + 是否必须拒答"：

| 类别 | 例 | 断言 |
|---|---|---|
| 正确 | 「1996 年各地区营收」 | 数值等于已核对过的基准（如 1996 年总营收 98,879,432.89）；`provenance.metricDefinition` 非空 |
| 必须拒答 | 「上周的转化率」（模型无此指标/时间列） | `refusal != null` 且原因指向缺失项 |
| 歧义 | 「营收」在多个候选口径下 | 返回候选并要求澄清，**不执行** |
| schema 漂移 | 引用已弃用列 | 明确报错并指出列名 |
| 陈旧数据 | 数据截止与问题时间不匹配 | 给出 `warnings` 且不静默回答 |

并写 `verification/data-agent-trend.json`（每次运行的 pass/fail 与时间戳）形成趋势。

## 8. 分片实施计划（每片都有验收命令）

| 片 | 内容 | 验收 |
|---|---|---|
| **S1** | `graphProperty` 绑定进模型（dataset + schema JSON）+ 重播 `tpc-h-analytics` | `verify-tpch-semantic-layer.mjs` 仍全绿；`/semantic/{g}/model` 列出绑定 |
| **S2** | 语义层 Java 化：`SemanticModel`/`SqlRenderer`（与 CLI 逐字节等价）+ `translate`/`validate`/`model` 端点 | `verify-semantic-api.mjs`：API SQL == `semantic-sql.mjs` SQL（多指标×多维度×3 方言×4 粒度） |
| **S3** | 解释器 + 确认门 + 图上执行：`ask`（auto/confirm）、`analyze`、`attribute` | `verify-semantic-api.mjs` 增补；单测覆盖拒绝规则 1–6 |
| **S4** | MCP server + 工具目录 + 评估台账 + 文档 | `node tools/mcp-server.mjs --selftest` 通过；`eval-data-agent.mjs` 五类全绿 |

## 9. 明确不做

- 不引入 LLM 依赖（可作为**可选**解释器插件，经确认门执行，默认关闭）；
- 不做自由生成 SQL（一切经语义模型；生成物只作导出/对照）；
- 不追 Spider/BIRD 榜单（基准自身标注错误率高，见调研）；
- 不在图上"猜"属性绑定或跨类型 join（宁可拒绝 + 给 SQL）。

## 10. 交付状态（每片完成即更新）

| 片 | 状态 | 证据 |
|---|---|---|
| S2 语义层 API | ✅ 完成 | `verify-semantic-api.mjs`：32 条语句与 CLI 逐字节相同；`SqlRendererTest` 7/7 |
| S1 事实图绑定 | ✅ 完成（最终形态） | 每个指标/维度的 `graphProperty` 与同义词 `alias` 直接写在 `tpc-h-analytics` 图节点上，由同一个 `TpchDataAgentDataset` 与事实图一起写（两者不再漂移）；`SemanticRunner` 先读模型、再回退文件；`semantic-bindings/*.json` 只剩 `dataGraph`/`vertexType`。第二个语义图 `demo-semantics`（营销 demo，10 指标/4 维度/1152 行事实）用同一套 runner/interpreter/renderer 跑通，证明不硬编码 TPC-H（`verify-demo-semantics.mjs`） |
| S3 智能问数 | ✅ 完成 | `verify-data-agent.mjs` 22/22：1996 营收 = **98,879,432.89**，答案带 plan + 遍历 + 行 + SQL；`confirm` 只回计划与签名令牌、不执行，令牌不可跨计划复用 |
| S3 业务分析 / 归因 | ✅ 完成 | 95,612,794.05 → 98,879,432.89，变化 **+3,266,638.84**，**贡献之和 = 变化量**（答案自陈），按大小排序，并写明「描述性，非因果」 |
| S4 MCP 插件 | ✅ 完成 | `node tools/mcp-server.mjs --selftest` 13 工具全通；stdio 往返实测；`ask_data` / `analyze_business` / `attribute_change` 均从插件跑通 |
| S4 评估台账 | ✅ 完成 | `verification/eval-data-agent.mjs`：五类（正确 / 必须拒答 / 欠定 / 模型没有的词 / 数据不覆盖的年份）**0 失败 0 缺口**；两条曾被记录的缺口已改为断言 |

### 从台账里闭环的两个缺口（曾被记录，现为断言）

1. **未定义的分解词曾被忽略**：`1996 年各渠道营收` 想要模型没有词汇的维度分解，却回了**总额** —— 数字对、答案错（默默换了个问题）。现在遇到分解语义（`各/按/每个/分别/by/breakdown/前 N/排名`）而没匹配到维度时**拒绝**并给出可用维度；单独的「分」不算分解词（它在「分析」里，否则会把细化变成墙）。
2. **数据不覆盖的年份曾只回 0**：`2026 年各地区营收` 返回 `total 0`，读者分不清「那年没有收入」与「那年没有数据」。现在答案里说明按哪个属性过滤、数据不覆盖该年 —— **仍是 200 与 0，但说明白了是哪一个 0**。

### 已交付的两处实现细节

- **工具目录是单一来源**：`GET /api/v1/agent/tools` 现在同时返回 `bindings`（每个工具怎么用 HTTP 调）。
  插件不含任何工具知识——在 `AgentToolCatalog` 里加一个工具，所有客户端立刻能调；反之不可能出现"插件里有、服务里没有"。
- **无状态**：服务端不保存会话，也不做服务端推送。协议修订**与客户端协商**——客户端请求 `2025-11-25`、`2025-06-18`、
  `2025-03-26` 或 `2024-11-05` 中的任何一个，服务端就回同一个（这些修订在本服务用到的地方没有差别），
  未知的则回 `2025-11-25` 并由客户端决定。用户确认因此是**令牌**（计划哈希 + 过期时间 + 签名），
  而不是服务端会话——插件重启、多副本都不影响确认流程。

### 已知尖锐边缘（记下来，不藏）

- 粒度会被施加到**任何**维度上：`dim/region` + `grain=year` 会渲染 `EXTRACT(YEAR FROM region.r_name)`。
  这与 CLI 逐字节一致（可移植性优先），但语义上是错的；模型里的 `dataType` 足以判断维度是不是日期，
  应当补一条 warning（S3 一并做）。
- 一次语义查询只支持一个维度（与 CLI 一致）；第二个维度会被**按名字拒绝**，不静默丢弃。
