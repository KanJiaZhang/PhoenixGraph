# pi-data-agent

把「数据字典 + 指标口径」变成一条可执行、可审计的链路的 **pi package**：

```
PDF / Markdown / wiki(HTML) / Word(.docx) / DDL / SHOW 输出
        │  readers + extractors（每条事实都带原句）
        ▼
PhoenixGraph：Table / Column / Metric / Dimension / Query / Term / Source / Model
        │  （Node + Relation，带 sourceId / evidence / confidence / status）
        ▼
看板（totals / trend / Top-N）与归因（贡献、占比、集中度、离群）
```

它不调用大模型，不连接数据库，不执行 SQL。它读你给的文件、写 PhoenixGraph、在事实图上做确定性遍历；
读不懂就拒绝并说明缺什么。设计细节与失败模式见 [docs/DESIGN.md](docs/DESIGN.md)。

---

## 1. 安装与启用

作为 pi package：

```bash
# 本地开发
pi install ./pi-data-agent --local

# 或在会话里临时加载扩展
pi -e ./pi-data-agent
```

包内包含：

| 资源 | 路径 | 作用 |
|---|---|---|
| extension | `extensions/pi-data-agent/index.js` | 10 个工具 + `/data` 命令 + 每轮契约注入 |
| skill | `skills/pi-data-agent/SKILL.md` | 建模/修订/看板/归因/风控的流程与拒答规则 |
| prompt | `prompts/data-agent-review.md` | 模型评审模板（`/data-agent-review [graph]`） |
| CLI | `src/cli.mjs` | 不经过 pi 也能 scan/build/model/edit/dashboard/attribute/risk-dashboard/risk-attribute |

服务端需要已经在 `http://localhost:8081` 运行（`tools/start-phoenixgraph.sh`）。端点、图名、事实图都可以配置：

```json
// <repo>/.pi/data-agent.json
{
  "endpoint": "http://localhost:8081",
  "graph": "data-agent",
  "dataGraph": "demo-data",
  "vertexType": "MarketingDay"
}
```

环境变量：`PHOENIXGRAPH_API`、`PHOENIXGRAPH_API_KEY`、`PI_DATA_AGENT_GRAPH`、`PI_DATA_AGENT_DATA_GRAPH`、
`PI_DATA_AGENT_VERTEX_TYPE`、`PI_DATA_AGENT_STATE_FILE`。

装好之后三样东西都进 pi（已在本机装好后逐项验证过）：

| 装了以后 | 怎么用 | 验证到的行为 |
|---|---|---|
| 扩展 | 8 个 `pg_*` 工具 + `/data` | `pg_data_model({graph:"pi-data-agent-verify"})` 返回 7 个指标 |
| 提示词模板 | `/data-agent-review <graph>` | 模板被展开，模型确实去调 `pg_data_model` 并逐节评审 |
| skill | `/skill:pi-data-agent`（模型也会自动加载） | 模型复述出 skill 里的两条硬规则 |

`pi list` 能看到这个包；`pi remove ./pi-data-agent` 移除它。

---

## 2. 五件事怎么用

### 2.1 从文档/数据库构建元数据层

支持的输入：

| 输入 | 怎么读 | 备注 |
|---|---|---|
| `.md` / `.markdown` / `.txt` / `.rst` | 原文 | 标题、Markdown 表格、标签行 |
| `.html` / `.htm` / wiki 导出 / `http(s)://wiki/...` | DOM-free 转成标题 + `| 表格 |` + 列表 | Confluence/Notion 导出的 HTML 通用 |
| `.wiki` / MediaWiki 源 | 标题/表格/列表 | 不抓取页面树 |
| `.pdf` | `pdftotext -layout`；`ocr=true` 时 `pdftoppm + tesseract` | 没装 poppler 会明确跳过 |
| `.docx` | 内置 ZIP + OOXML 读取（`word/document.xml`） | `.doc` 明确拒绝 |
| `.sql` / `.ddl` / SHOW 输出 / `information_schema` 导出 | DDL、`SHOW CREATE TABLE`、`SHOW TABLES`、`DESCRIBE`、JSON/CSV | 不连接数据库 |

```bash
# 预览，不写任何东西
node src/cli.mjs scan examples/retail --data-graph demo-data --vertex-type MarketingDay

# 写入（新图会创建 schema）
node src/cli.mjs build examples/retail --graph pi-data-agent-demo \
  --data-graph demo-data --vertex-type MarketingDay
```

在 pi 里：

```text
pg_data_scan({sources:["examples/retail","docs/指标口径.html"], dataGraph:"demo-data", vertexType:"MarketingDay"})
pg_data_build({sources:["examples/retail"], graph:"pi-data-agent-demo", dataGraph:"demo-data", vertexType:"MarketingDay"})
```

构建是 **upsert**：本次来源没看到的旧节点不会自动删除（模型图里可能留下孤儿节点，catalog 不再列它们）。
删除用 `pg_data_edit`；重新 `build` 会按来源重新抽取，可能把已删除的节点带回来——这是“来源是事实”的代价，
需要永久删除时同时改来源或不要再 build。

### 2.2 从文档/SQL 中找指标、维度

抽取是**确定性**的，认这些形状：

- 标签行：`指标：GMV = SUM(order_amount)`、`口径：...`、`单位：元`、`别名：成交额、收入`、`维度：渠道、地区`
- Markdown/wiki 表格：表头包含 `指标/度量/metric` + `定义/口径/表达式`、`维度/dimension`、`术语/term`、
  `单位/unit`、`别名/synonym`、`图属性/graphProperty`
- SQL 聚合：`SUM(x) AS revenue`、`COUNT(*) AS orders`、`100 * SUM(a) / SUM(b) AS ctr`
- SQL 分组：`GROUP BY channel` → 维度
- DDL：`CREATE TABLE` / `SHOW CREATE TABLE` / `DESCRIBE` / `information_schema` → 表、列、类型、注释、PK/FK

每条事实记录来源、原句、行号/位置、置信度和读取方式。**没有表达式的“指标”会被标成 `needs_review`，
不会编一个公式。**

### 2.3 用户添加/修改元数据、数据实体和关系

`pg_data_edit`（CLI 同样 `node src/cli.mjs edit ops.json`）支持：

| op | 说明 |
|---|---|
| `upsert-node` | 新增/覆盖一个节点（Table/Column/Metric/Dimension/Term/Query/Domain/Source/Model） |
| `update-node` | 只合并给定属性，不覆盖其它属性 |
| `set-binding` | 设置 `graphProperty`，如 `revenue` 或 `year=year,month=month` |
| `add-alias` | 追加同义词 |
| `upsert-edge` / `update-edge` | 新增/修改关系 |
| `delete-node` / `delete-edge` | 删除；必须 `confirm:true`，有边时还要 `cascade:true` |

校验：关系名白名单、端点类型匹配（`hasColumn` 只能 Table→Column，`groupedBy` 只能 Metric→Dimension…）、
绑定格式、ID 前缀、节点存在性。每次编辑产生 audit（`.pi/data-agent/audit.jsonl`）和模型快照
（`.pi/data-agent/models/<graph>.json`）。`--dry-run` 只校验不写。

### 2.4 看板与归因

```bash
# 看板：每个指标一个总额 tile、一个日期趋势 tile、一个维度 Top-N tile
node src/cli.mjs dashboard --graph pi-data-agent-demo3 --year 2025 --top 5
# 产物：.pi/data-agent/dashboards/<graph>-<timestamp>.html / .json

# 归因：2024 → 2025，按渠道拆解营收变化
node src/cli.mjs attribute --graph pi-data-agent-demo3 \
  --metric 营收 --by 渠道 --from 2024 --to 2025
```

每个 tile 都带 `traversal`（真正跑的 Gremlin）和 `sql`（数仓可执行的同义查询）。不能执行的 tile 显示
`refused: ...` + SQL，而不是 0 或空图。归因输出：

- `totalBefore` / `totalAfter` / `delta` / `deltaPercent`
- 每个切片的 `before`、`after`、`delta`、`shareOfChange`、`growthPercent`、`shareBefore/After`
- `contributionsSum` 与 `decompositionAddsUp`（贡献之和必须等于变化量）
- `topMover`、`concentrationBefore/After`（HHI、top1）、离群分数
- `limits`：**描述性，不是因果**

### 2.5 风控账本 + 支付图：哪个团伙、哪条规则、为什么

前四件事回答“这个指标是什么、多少”；风控这件事回答“变化是谁造成的、证据是什么”。两个输入：

| 输入 | 例 | 作用 |
|---|---|---|
| 案件账本（CSV/JSON） | `examples/risk-control/risk-cases.csv`（120 行，2025-09 / 2025-10） | 每个 period×channel×rule×ring 的 exposure / loss / blocked / cases / fp_cases / review_hours |
| 风控 manifest（JSON） | `examples/risk-control/risk-model.json` | 指标聚合方式、维度、敏感规则、团伙→账户映射、实体图名 |

```bash
# 看板：KPI + 上期对比 + by ring/rule/channel 拆解 + 每个团伙的图上证据
node src/cli.mjs risk-dashboard --period 2025-10
# 归因：敞口 2025-09 → 2025-10，按 ring 拆解，再回支付图找证据
node src/cli.mjs risk-attribute --by ring --from 2025-09 --to 2025-10
```

在 pi 里是 `pg_risk_dashboard({period:"2025-10"})` 和 `pg_risk_attribution({by:"ring"})`，或 `/data risk`、
`/data risk-attr`。**账本回答“什么变了”，图回答“背后是谁”**：

```
团伙 ring_a（7 个账户）→ FuzzyLink key mail.ru|10.20|621700 → 2 笔付款 / 2000 元 → 收款账户 collector-9
receiver degree 40，payers 3，dominance 13.33 → verdict: confirmed
```

- **每条结论都带证据和查询**：`evidence[].kind` 是 `members / fuzzy link / payments / receiver / centrality`，
  每一项都带 `citation`（图/顶点 id）和真正跑的 `query`。
- **验收用 checks，不用形容词**：团伙人数、共享 FuzzyLink、唯一收款账户、收款账户是否主导网络、是否有付款；
  全过是 `confirmed`，否则是 `candidate` 并列出 `failedChecks`。
- **图答不上来不是 0**：一条查询失败会进 `warnings`，该团伙降级为 `candidate`（或在无 client 时
  `executed:false` + 原因），绝不静默当零。
- **假设必须可推翻**：`risk_attribution` 给出排好序的候选解释（`rule-sensitivity` / `loss-improvement` /
  `ring-reentry`），每个都带 `support`、`checks` 和 `refutations`（推翻条件）。
- **口径**：`contributionsSum == delta`（`decompositionAddsUp`）；`limits` 明说“描述性、不是因果”，
  并且 **FuzzyLink 是模式相似候选，不是欺诈事实**。

示例账本的真实数字（2025-09 → 2025-10）：敞口 1,000,000 → 1,420,000；确认损失 80,000 → 54,999.99；
拦截 250,000.02 → 619,999.98；FP 率 15% → 25%；ring_a 贡献 57.14%、ring_b 28.57%。

### 2.6 向量 + 图：把「收入」问成具体定义

```bash
node src/cli.mjs similar "收入 按渠道 拆分" --graph sales-model --k 4 --hops 1
```

向量只回答「你大概指哪一个」（包内确定性 hashing embedder，768 维，词 + 字符 n-gram，L2 归一），
**图**回答「那个定义由什么构成」：匹配到的节点会带上它经 `Relation` 边连到的列、表、维度、来源；
没有关系的候选会被标成 `isolated`（一个没有血缘的定义不能报价）。在 pi 里是 `pg_data_similar(text)`。

### 2.5.1 交互式模式：先看计划，确认后才执行

看板和归因都不是"读一个数"那么简单——它替你决定了用哪个指标、按哪个维度分组、保留哪些行、用哪套算法、读哪个
事实图、以及用哪个时点的口径。所以每次执行前先产出一份**计划**（`src/plan.mjs`），交互式模式下由人确认：

```
问题：2025年营收按渠道拆分，前3
将执行：看板（指标 × 维度 × 期间） · 模型 sales-model · 时点 2026-11-05T06:31:57.836Z
  · 指标：metric/营收（营收） — 表达式 SUM(net_revenue) · 绑定 net_revenue · 单位 元
  · 维度：dim/渠道（渠道） — 按 channel 分组
  · 过滤条件：year = 2025
  · 排名条数：前 3 条
  ~ 算法：按维度分组后 values('net_revenue').sum() — 表达式 SUM(net_revenue) · 事实属性 net_revenue
  ~ 事实图：sales-facts/SalesDay（432 个事实 id）
~ 解析得到 / ? 默认值：算法、事实图
（· 明确指定 · ~ 由模型解析 · ? 默认值 · ! 会拒答）
执行这个查询？[y/N]
```

- **确认什么**：指标（含表达式与绑定）、维度、过滤条件（year / from→to）、排名条数、算法（聚合 / 归因算术）、
  事实图绑定、口径时点（`asOf`）。每一项都标出来源：`·` 明确指定、`~` 由模型解析、`?` 默认值、`!` 会拒答。
- **会拒答的计划在确认框里就能看到**：没有表达式/没有绑定的指标、模型里没有的维度，都在计划里标 `!`，
  不会跑完才告诉你。
- **谁来问**：pi 交互式（`ctx.hasUI`）用 `ctx.ui.confirm`；CLI 在终端（TTY）上用 `y/N` 提示，`--confirm`
  可强制询问、`--yes` 跳过；print/JSON/RPC 无 UI 时**不假装确认**，结果里带 `confirmed: false`。
- **拒绝即不执行**：返回计划文本 + `cancelled: true`，不会碰事实图（测试里断言过：拒绝后事实图查询数为 0）。

工具参数 `autoApprove: true` 供"已经在别处确认过"的调用者跳过对话框；除此之外一律先问。

### 2.6.1 稳定性：同一个问题，写法不同，答案必须一样

指标/维度可以用 **id**（`metric/营收`）、**名字**（`营收`）、**别名**（`收入`）、**绑定列**（`revenue`、`channel`）
来指代；期间可以写字符串或数字；`asOf` 可以是 ISO 时刻、日期或毫秒；`topN` 可以是数字或字符串 —— 这些都解析到同一个
节点/同一个值（`src/resolve.mjs`，解析顺序 id → 名字 → 别名 → 绑定 → 列，同级按 id 排序，歧义时结果确定）。
**指代不到**的指标/维度会变成一张**拒答 tile**并列出可选值，而不是被悄悄丢掉（以前 `dimensions:["channel"]` 会
悄悄少 5 张 tile，现在会解析或拒答）。

更复杂的是**问句**：`src/ask.mjs` 把自然语言解析成结构化请求（指标按名字/别名/id/绑定/表达式，维度按
按/by 之后的名字或任意位置的名字，期间按出现的年份，`前3`/`top 3`/`前三名` 都能读），所以

```
2025年营收按渠道拆分，前3
营收 2025 by 渠道 top3
按渠道看下 2025 年的收入前三
2025 年销售额按 channel 拆解 top 3
2024 到 2025 营收按渠道为什么增长
```

都是同一个请求（前四句是看板，最后一句是归因）。pi 里 `pg_data_ask(question)` 或 `/data ask <question>`，
CLI `node src/cli.mjs ask "…"`，返回里带 `understood as …`，一眼能看出它理解成了什么；问句里没有模型认识的
指标时**拒答并列出可选指标**。

稳定性实测（四组，见 [`docs/STABILITY.md`](docs/STABILITY.md)）：

- **A. 17 组等价写法**：写法不同、结果指纹完全相同；
- **B. 12 种自然语言问法**：解析成同一请求、同一结果；
- **C. 5 条恒等式**：按年一次问 = 按月问再相加；归因 delta = 两个年度看板之差；2024→2025 与 2025→2024
  delta 取反、切片不变；按表达式引用指标（`SUM(cost)`）= 按 id；按表限定列引用维度
  （`sales_day.channel`）= `dim/渠道`；
- **D. 4 组对照**：换年份/时点/维度/期间，结果必须不同（否则测试无意义）。

```bash
node verification/check-stability.mjs --doc docs/STABILITY.md
```

### 2.7 口径的时间戳：过期失效，但可以回溯

```bash
# 2026-10-15 起营收改为净额口径（旧定义被冻结成版本节点）
echo '[{"op":"update-node","id":"metric/营收","effectiveFrom":"2026-10-15",
        "properties":{"expression":"SUM(net_revenue)","graphProperty":"net_revenue"}}]' | \
  node src/cli.mjs edit --graph sales-model

node src/cli.mjs dashboard --graph sales-model --year 2026 --as-of 2026-10-01   # 旧口径
node src/cli.mjs dashboard --graph sales-model --year 2026                     # 当前口径
```

- 规则：`validFrom <= instant < validTo`（epoch millis，两端可空）；`asOf` 不传 = 现在。
- 修改带 `effectiveFrom` 时，旧定义被**冻结成版本节点** `metric/营收#v<instant>`（窗口关闭），
  live 节点从该时刻生效，两者用 `supersededBy` 相连；读者按 base id 折叠（一个 id 一个定义）。
- `retire-node`（需 `confirm:true` + `effectiveTo`）是**软删除**：定义停用但历史仍可读；`delete-node` 是真删。
- 所有读接口都接受 `asOf`：`model` / `dashboard` / `attribute` / `similar`，pi 里是工具参数 `asOf`。

### 2.8 定时产出：看板 + 归因

```bash
node src/cli.mjs schedule --graph sales-model --periods 2024,2025,2026 --metric 营收 --by 渠道 --every 60000
```

每个期间一轮：看板 + 归因各一份产物流落盘（`--out`，默认 `.pi/data-agent/dashboards/`），
运行日志（每轮的 executed/refused、delta、top mover）作为结果返回；基期没有数据时归因**拒答**而不是
把整年当成增长。pi 里是 `/data schedule --periods …`，或 `runSchedule()` 自己嵌入别的调度器。

---

## 3. 存储模型

模型图使用 PhoenixGraph 已有的语义层形状：

**顶点 Type = `Node`**，`entityType` ∈ `Table / Column / Metric / Dimension / Term / Query / Source / Model / Domain`。

关键属性：`name`, `description`, `alias`, `dataType`, `columnRole`, `tableName`, `aggFunction`,
`expression`, `metricKind`, `unit`, `sqlText`, `graphProperty`, `dataGraph`, `vertexType`, `owner`,
`domain`, `sourceId`, `evidence`, `confidence`, `status`, `proposedBy`, `modelVersion`, `nodeIds`,
`edgeIds`。

**边 Type = `Relation`**，`relation` ∈ `hasColumn / references / computedFrom / usesColumn / groupedBy /
dimensionColumn / definedBy / readsTable / contributesTo / belongsTo / describes / sameAs`，边上同样带
`sourceId / evidence / confidence / status`。

`Model` 节点的 `dataGraph` + `vertexType` 是“部署事实”：指标在哪个图的哪个顶点类型上执行。节点上的
`graphProperty` 是“语义列 → 事实属性”的绑定；绑定只会在事实类型**确实声明了该属性**时自动建立，否则留空
并在 review 里报告。

### 为什么模型图是 Aerospike + catalog

PhoenixGraph 的 HTTP 写接口目前只对 Aerospike 生效；RocksDB-Edge 图由 loader 在启动时写入，不能并发写。
而 Aerospike 的 keyed store 又不能 `g.V('Node')` 扫描整个类型。因此：

- 模型图用 **Aerospike** 存储（HTTP 可写）；
- `Model` 节点带 `nodeIds` / `edgeIds` 两个 catalog 列表；
- 包读取模型时先读 catalog，再按 id 批量读取节点、按 head 读关系边。

这个限制是真实的：`GET /api/v1/semantic/{graph}/model` 对 RocksDB 图使用扫描，对 Aerospike 模型图不适用。
所以看板/归因由本包直接在**事实图**（如 `demo-data`、`tpc-h-data`）上执行遍历；SQL 由包内的渲染器导出。
如果要把模型图接到 Java 语义层，把它建成 RocksDB-Edge 并用 loader 写入即可（同一套 Node/Relation 形状）。

### 重启后仍在

`build` 在写图之后会把 schema 复制到 `<project>/.pida-schemas/<graph>.json`（`--schema-dir` 可改）；
PhoenixGraph 的 `phoenixgraph.storage.schema-dir` 在启动时发现这个目录，因此模型图不需要重新构建。
本次修改同时让“HTTP 动态建图”和“从 schema-dir 发现”使用**同一个 Aerospike vertex set**，否则重启后读的是一张空图。
删除图只需删掉对应的 `.pida-schemas/<graph>.json` 并重启；数据仍在该 vertex set 里。

如果服务部署在另一台机器，`--schema-dir` 应指向服务能看到的目录；或者设 `persistSchema:false` 并自行管理 schema。

### 删除用的 body 端点

节点 id 带 `/`（`metric/revenue`）时，`DELETE /graphs/{g}/vertices/{id}` 的路径形式会被 servlet 容器在进入 handler
前以 `%2F` 拒绝。包改用两个 body 形式（已加在 `GraphWriteController`）：
`POST /api/v1/graphs/{g}/vertices/delete {"id":...}`、`POST /api/v1/graphs/{g}/edges/delete {edgeId,edgeSchemaName,source,destination}`。

---

## 4. 工具与命令

| 工具 | 类型 | 说明 |
|---|---|---|
| `pg_data_scan` | 只读 | 读取来源、抽取、预览模型和 review |
| `pg_data_build` | 写 | 写模型到 PhoenixGraph（新图建 schema） |
| `pg_data_model` | 只读 | 读取并展示已存模型 |
| `pg_data_edit` | 写 | 校验并应用修订，生成 audit/快照 |
| `pg_data_dashboard` | 只读 | 生成 HTML/JSON 看板 |
| `pg_data_attribute` | 只读 | 两期变化按一个维度归因 |
| `pg_risk_dashboard` | 写（生成 HTML） | 风控 KPI 看板 + ring/rule/channel 拆解 + 团伙图上证据 |
| `pg_risk_attribution` | 只读 | 风险指标两期归因 + 团伙证据 + 可推翻的候选解释 |
| `pg_data_ask` | 只读 | 用自然语言问：解析成看板或归因请求，并返回 `understood as …` |
| `pg_data_similar` | 只读 | 向量相似 + 关系展开：一个词最可能指哪个定义、它由什么构成 |

```text
/data status
/data graph <name>
/data endpoint <url>
/data scan <file-or-dir> [--data-graph <g> --vertex-type <T>]
/data build <file-or-dir> [--graph <g> --data-graph <g> --vertex-type <T>]
/data model [--as-of <instant>]
/data ask <question>      (e.g. 2025年营收按渠道拆分，前3)
/data similar <text> [--k 5] [--hops 1]
/data schedule --periods 2024,2025 [--metric 营收 --by 渠道] [--every 60000]
/data dashboard [--year 2025] [--top 5] [--as-of <instant>]
/data attribute <metric> --by <dimension> --from 2024 --to 2025
/data risk [cases] [--period 2025-10]
/data risk-attr [cases] [--by ring --from 2025-09 --to 2025-10]
/data edit <operations.json>
```

---

## 5. 示例与验证

```bash
# 单元测试（无服务依赖）
npm test

# 现场验证（需要一个运行中的 8081；会创建一个模型图）
PHOENIXGRAPH_LIVE=1 npm run verify
```

示例输入在 `examples/retail/`：一份业务指标口径 Markdown、一份 Confluence 导出 HTML、一份 DDL、一份
`SHOW TABLES` 输出。它对齐 `demo-data` / `MarketingDay`，可以直接跑通 scan → build → dashboard →
attribute。

端到端案例在 `examples/sales-case/`：自己生成并导入 432 行事实数据（三年 × 12 月 × 4 渠道 × 3 地区）、
自己的指标字典/DDL/`SHOW TABLES`、11 步跑完并从真实返回生成
[`docs/CASE-DATA-AGENT.md`](docs/CASE-DATA-AGENT.md)（文档/DDL → 元数据 → schema → 关系 → 向量+图 → 看板 →
归因 → 增删改 → 口径变更与回溯 → 定时产出）：

```bash
node examples/sales-case/generate.mjs      # 建图 + 导入事实
node examples/sales-case/run-case.mjs       # 跑完并重写案例文档
```

风控示例在 `examples/risk-control/`：120 行案件账本（2025-09 / 2025-10，含团伙 ring_a/ring_b、敏感规则
`DEVICE_SHARE`、MINI 渠道）和它的 manifest。它不需要事实图就能算 KPI 和归因；团伙证据要 `payment-graph`
里有对应 Account/FuzzyLink/Payment 顶点，没有就降级为 `candidate` 并说明原因。

---

## 6. 明确不做

- 不执行用户提供的 SQL；SQL 只作为导出/对照。
- 不连接数据库；`SHOW` 输出/DLL 由用户提供。
- 不猜属性绑定，不把“看起来像”的列当成同一列；没有绑定就拒绝执行。
- 不把描述性归因说成因果。
- 不把 FuzzyLink 当欺诈事实：它是模式相似候选，`confirmed` 也要人工复核账户与资金路径。
- 不推算没进账本的案件：账本里没有的 period×切片不在答案里，归因也不会替它补一个 0。
- 向量是**确定性 hashing 基线**（无外部模型）：换成本地 embedder 只换 `src/embed.mjs` 一个函数，
  但不要混用两个向量空间。
- 写接口是 **merge 语义**：重写一个顶点**不能删除**它已有的属性（例如 `validTo`），所以重建模型
  不会「复活」一个已停用的定义 —— 这点写在 DESIGN §9，需要的语义用新的版本节点表达而不是擦除。
- 不做 PDF 的手写解析：PDF 依赖 `pdftotext`（可选 OCR）。
- 不做实时 wiki 爬取/页面树同步；给 URL 就抓这一页，导出 HTML 也可以。

这些边界都是为了同一件事：**答案里的每个数字都能追到一句原话或一条遍历；追不到就拒绝。**
