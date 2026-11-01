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
| extension | `extensions/pi-data-agent/index.js` | 6 个工具 + `/data` 命令 + 每轮契约注入 |
| skill | `skills/pi-data-agent/SKILL.md` | 建模/修订/看板/归因的流程与拒答规则 |
| prompt | `prompts/data-agent-review.md` | 模型评审模板 |
| CLI | `src/cli.mjs` | 不经过 pi 也能 scan/build/model/edit/dashboard/attribute |

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

---

## 2. 四件事怎么用

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

```text
/data status
/data graph <name>
/data endpoint <url>
/data scan <file-or-dir> [--data-graph <g> --vertex-type <T>]
/data build <file-or-dir> [--graph <g> --data-graph <g> --vertex-type <T>]
/data model
/data dashboard [--year 2025] [--top 5]
/data attribute <metric> --by <dimension> --from 2024 --to 2025
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

---

## 6. 明确不做

- 不执行用户提供的 SQL；SQL 只作为导出/对照。
- 不连接数据库；`SHOW` 输出/DLL 由用户提供。
- 不猜属性绑定，不把“看起来像”的列当成同一列；没有绑定就拒绝执行。
- 不把描述性归因说成因果。
- 不做 PDF 的手写解析：PDF 依赖 `pdftotext`（可选 OCR）。
- 不做实时 wiki 爬取/页面树同步；给 URL 就抓这一页，导出 HTML 也可以。

这些边界都是为了同一件事：**答案里的每个数字都能追到一句原话或一条遍历；追不到就拒绝。**
