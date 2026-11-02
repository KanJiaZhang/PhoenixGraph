# 数据智能体端到端案例（sales-case）

本文由 `node examples/sales-case/run-case.mjs` 生成：每一步的输入、调用的 PhoenixGraph 接口/遍历/SQL 和真实返回都在里面，可以直接照着复现。

| 项 | 值 |
|---|---|
| 服务 | http://localhost:8081 |
| 模型图 | sales-model（Aerospike，schema 由包创建） |
| 事实图 | sales-facts / SalesDay（案例自己生成并导入） |
| 口径变更 | 2026-10-15：营收 SUM(revenue) → SUM(net_revenue) |
| 生成时间 | 2026-11-02T03:23:46.308Z |

## 覆盖的能力

1. 生成并导入案例自己的事实数据 — 432 行事实数据在 sales-facts/SalesDay 中，可被语义层绑定与遍历。
2. 从文档 / DDL / SHOW TABLES 识别元数据 — 元数据从文档里读出来，每条都带原句；没有表达式的会成为待评审项，而不是被猜一个公式。
3. 写入模型图：schema 自动创建 — 模型图与它的 schema 都在 PhoenixGraph 里，不依赖任何手工建 schema 的步骤。
4. 关系：依赖 / 贡献 / 包含 / 血缘 — 依赖/包含/血缘/归因关系在模型图里，每条都带证据句，关系类型由 schema 声明而不是自由字符串。
5. 向量相似 + 图关联：把「收入」问成具体定义 — 「收入」被定位到 metric/营收，再由图拿到它的绑定、来源表、可切分维度；这就是 vector + graph 的组合检索。
6. 看板：指标 × 维度 × 期间，全部落在事实图上 — 看板是模型驱动的：换指标/维度/期间只改参数，遍历与 SQL 由模型生成。
7. 归因：2024 → 2025 按渠道拆解 — 归因把变化拆到一个维度上，算术自证（和为 delta），并拒绝因果化。
8. 用户修改 / 删除：受校验、带审计 — 新增、修改、删除实体与关系都可做，且每次写入都留 before/after 与审计行。
9. 口径变更（带时间戳）+ 回溯 — 同一指标两个口径按时间戳共存：默认读当前，asOf 读历史，SQL/遍历随定义一起变化。
10. 定时生成看板 + 归因（三个周期） — 3 个周期全部产出看板与归因，共 45 个执行 tile、3 个拒答。
11. 收尾：模型现状 — 案例结束时模型可读、可回溯、可继续编辑，事实图数据独立于模型图。

## 1. 生成并导入案例自己的事实数据

Case 不依赖任何外部数据集：自己定义事实表、自己生成（固定种子）、自己通过 HTTP 写接口导入 PhoenixGraph。

**做了什么 / 返回了什么**

- 生成 432 行（2024–2026 × 12 月 × 4 渠道 × 3 地区），写入 432 行
- 图 sales-facts：Aerospike，顶点类型 SalesDay
- 一行样例：{"id":"sd-2024-01-app-1","label":"SalesDay","name":"2024-01 APP/华东","year":2024,"month":1,"channel":"APP","region":"华东","campaign":"新客季","orders":262,"customers":204,"revenue":96499.92,"net_revenue":94202.7,"cost":26678.82}

**事实表 schema（由案例创建）**

```
vertex type SalesDay
  year long · month long · channel string · region string · campaign string
  orders long · customers long · revenue double · net_revenue double · cost double
```

> 退款率在 2026 年提高到 3.5%–5%，这是后面「营收口径变更」在数字上能被看见的原因。

**结论**：432 行事实数据在 sales-facts/SalesDay 中，可被语义层绑定与遍历。

## 2. 从文档 / DDL / SHOW TABLES 识别元数据

让包自己读案例的指标字典、DDL 与 SHOW TABLES 输出，抽取表、列、指标、维度、术语，并带上原句证据。

**做了什么 / 返回了什么**

- 读了 16 个来源：sales-model-2026-11-02T03-20-06-849Z.html, sales-model-2026-11-02T03-20-06-849Z.json, sales-model-2026-11-02T03-20-07-976Z.html, sales-model-2026-11-02T03-20-07-976Z.json, sales-model-2026-11-02T03-20-09-110Z.html, sales-model-2026-11-02T03-20-09-110Z.json, sales-model-2026-11-02T03-20-24-054Z.html, sales-model-2026-11-02T03-20-24-054Z.json, sales-model-2026-11-02T03-20-25-372Z.html, sales-model-2026-11-02T03-20-25-372Z.json, sales-model-2026-11-02T03-20-26-646Z.html, sales-model-2026-11-02T03-20-26-646Z.json, metrics-2026-10.md, metrics.md, schema.sql, show_tables.txt
- 抽取：2 表 / 14 列 / 6 指标 / 4 维度 / 0 参考查询
- review：6 项待确认（metric/客单价:metric-without-expression, metric/客单价:metric-unbound, metric/毛利率:metric-without-expression, metric/毛利率:metric-unbound, …）

**指标预览（表达式 | 绑定 | 单位）**

```
metric/成本 | SUM(cost) | cost | 元
metric/订单数 | SUM(orders) | orders | 单
metric/客单价 | (无表达式) | (未绑定) | 元
metric/客户数 | SUM(customers) | customers | 人
metric/毛利率 | (无表达式) | (未绑定) | %
metric/营收 | SUM(net_revenue) | net_revenue | 元
```

**一条指标的原句证据**

```
"[{\"sourceId\":\"metrics_2026_10-4079ef81\",\"quote\":\"指标：营收 = SUM(net_revenue)\",\"locator\":\"metrics-2026-10.md:9\",\"reason\":\"explicit definition\"},{\"sourceId\":\"metrics-93857cfe\",\"quote\":\"指标：营收 = SUM(revenue)\",\"locator\":\"metrics.md:7\",\"reason\":\"explicit definition\"},{\"sourceId\":\"metrics-93857cfe\",\"quote\":\"指标：净营收 = SUM(net_revenue)\",\"locator\":\"metrics.md:12\",\"reason\":\"explicit definition\"}]"
```

**结论**：元数据从文档里读出来，每条都带原句；没有表达式的会成为待评审项，而不是被猜一个公式。

## 3. 写入模型图：schema 自动创建

把同一批元数据写进 sales-model（新图自动建 schema），并绑定到 sales-facts/SalesDay。

**做了什么 / 返回了什么**

- 重置已有的模型图：删除 33 个顶点 / 76 条边（所以本案例可重复运行）
- 写入 33 个顶点、76 条边；schema 已存在
- schema 里声明的类型：Node(31 属性) / Relation
- catalog 写在 Model 顶点上：nodeIds=33、edgeIds=76（keyed store 只能按 id 读）
- 事实绑定：sales-facts/SalesDay，并记录 432 个事实 id（Aerospike 不能按类型扫描，语义查询用 g.V(ids...)）

**schema 的顶点属性（节选）**

```
1 name STRING
2 entityType STRING
3 description STRING
4 alias [object Object]
5 dataType STRING
6 columnRole STRING
7 tableName STRING
8 aggFunction STRING
9 expression STRING
10 metricKind STRING
11 unit STRING
12 sqlText STRING
```

> 每个节点与边都写了 validFrom（构建时刻），这是第 6 步「口径变更后仍可回溯」的基础。

**结论**：模型图与它的 schema 都在 PhoenixGraph 里，不依赖任何手工建 schema 的步骤。

## 4. 关系：依赖 / 贡献 / 包含 / 血缘

关系有两个来源，都在上一步写进了图：SQL DDL 的外键与列归属，文档里的「属于 · 域」「指标来自表」等句子。

**做了什么 / 返回了什么**

- 边：74 条，dimensionColumn=4 usesColumn=5 groupedBy=20 computedFrom=5 describes=27 hasColumn=13
- table/channel_dim -hasColumn-> column/channel_dim.display_name  证据:"[{"sourceId":"schema-3feb480b","quote":"display_name VARCHAR(32) NOT NULL COMMENT '渠道名称'","locator":"schema.sql","reason"
- metric/成本 -computedFrom-> table/sales_day  证据:"指标：成本 = SUM(cost)"
- metric/成本 -groupedBy-> dim/日期  证据:"指标：成本 = SUM(cost)"
- source/metrics-93857cfe -describes-> term/成交  证据:"[{"sourceId":"metrics-93857cfe","quote":"| 成交 | 用户完成支付且未退款的订单 | GMV 口径 |","locator":"metrics.md:52","reason":"structured"

**关系与反向名 / 家族（schema 里声明的）**

```
relation=hasColumn / references / computedFrom / usesColumn / groupedBy / dimensionColumn / definedBy / readsTable / contributesTo / belongsTo / describes / sameAs
xrelation=The reverse relation name
relationLabel=中文关系名
relationFamily=结构 / 血缘 / 关联 / 指标 / 归因 / 来源
```

**结论**：依赖/包含/血缘/归因关系在模型图里，每条都带证据句，关系类型由 schema 声明而不是自由字符串。

## 5. 向量相似 + 图关联：把「收入」问成具体定义

向量只回答「你大概指哪一个」，图回答「那个定义由什么构成」，两者组合才允许报价。

**做了什么 / 返回了什么**

- 查询："收入 按渠道拆分 怎么算" → 4 个候选
- 0.230 metric/毛利率（Metric）毛利率，关系 1 条
- 0.223 dim/渠道（Dimension）渠道，关系 7 条
- 0.219 metric/营收（Metric）营收，关系 7 条
- 0.209 column/date（Column）date，关系 2 条
- 扩展出的子图：27 个节点 / 62 条边；孤立候选 0 个

**候选与它们的图邻域**

```
0.230 metric/毛利率  毛利率 · 毛利润占营收的比例。 · %
    <- describes source/metrics-93857cfe (Source)

0.223 dim/渠道  渠道 · APP / WEB / MINI / STORE · VARCHAR(20)
    -> dimensionColumn column/sales_day.channel (Column)
    <- groupedBy metric/成本 (Metric)
    <- groupedBy metric/订单数 (Metric)
    <- groupedBy metric/净营收 (Metric)
    <- groupedBy metric/客户数 (Metric)
    <- groupedBy metric/营收 (Metric)

0.219 metric/营收  营收 · 收入 · 销售收入 · 营业额 · 成交金额（毛），未扣除退款。按订单支付时间归属。 · SUM(revenue) · 元
    -> usesColumn column/sales_day.revenue (Column)
    -> groupedBy dim/日期 (Dimension)
    -> groupedBy dim/渠道 (Dimension)
    -> groupedBy dim/活动 (Dimension)
    -> computedFrom table/sales_day (Table)
    -> groupedBy dim/地区 (Dimension)

0.209 column/date  date · 自然年 / 自然月 · DATE
    <- dimensionColumn dim/日期 (Dimension)
    <- describes source/metrics-93857cfe (Source)
```

> 向量由包内的确定性 hashing embedder 计算（768 维，词 + 字符 n-gram，L2 归一），换成本地模型只换一个函数。

**结论**：「收入」被定位到 metric/营收，再由图拿到它的绑定、来源表、可切分维度；这就是 vector + graph 的组合检索。

## 6. 看板：指标 × 维度 × 期间，全部落在事实图上

每个 tile 都是语义查询：遍历 + SQL 都打印出来，不能执行的 tile 给拒答理由而不是 0。

**做了什么 / 返回了什么**

- 2025 年：15 个 tile 执行，1 个拒答
- metric/成本：7186303.65
- metric/订单数：77708
- metric/净营收：26602544.09
- metric/客户数：59866
- metric/营收：27176294.39
- 看板文件：D:\workspace\eclipse\PhoenixGraph\.pi\data-agent\dashboards\sales-model-2026-11-02T03-23-42-129Z.html

**营收总额的遍历与 SQL**

```
g.V('sd-2024-01-app-1','sd-2024-01-app-2','sd-2024-01-app-3','sd-2024-01-web-1','sd-2024-01-web-2','sd-2024-01-web-3','sd-2024-01-mini-1','sd-2024-01-mini-2','sd-2024-01-mini-3','sd-2024-01-store-1','sd-2024-01-store-2','sd-2024-01-store-3','sd-2024-02-app-1','sd-2024-02-app-2','sd-2024-02-app-3','sd-2024-02-web-1','sd-2024-02-web-2','sd-2024-02-web-3','sd-2024-02-mini-1','sd-2024-02-mini-2','sd-2024-02-mini-3','sd-2024-02-store-1','sd-2024-02-store-2','sd-2024-02-store-3','sd-2024-03-app-1','sd-2024-03-app-2','sd-2024-03-app-3','sd-2024-03-web-1','sd-2024-03-web-2','sd-2024-03-web-3','sd-2024-03-mini-1','sd-2024-03-mini-2','sd-2024-03-mini-3','sd-2024-03-store-1','sd-2024-03-store-2','sd-2024-03-store-3','sd-2024-04-app-1','sd-2024-04-app-2','sd-2024-04-app-3','sd-2024-04-web-1','sd-2024-04-web-2','sd-2024-04-web-3','sd-2024-04-mini-1','sd-2024-04-mini-2','sd-2024-04-mini-3','sd-2024-04-store-1','sd-2024-04-store-2','sd-2024-04-store-3','sd-2024-05-app-1','sd-2024-05-app-2','sd-2024-05-app-3','sd-2024-05-web-1','sd-2024-05-web-2','sd-2024-05-web-3','sd-2024-05-mini-1','sd-2024-05-mini-2','sd-2024-05-mini-3','sd-2024-05-store-1','sd-2024-05-store-2','sd-2024-05-store-3','sd-2024-06-app-1','sd-2024-06-app-2','sd-2024-06-app-3','sd-2024-06-web-1','sd-2024-06-web-2','sd-2024-06-web-3','sd-2024-06-mini-1','sd-2024-06-mini-2','sd-2024-06-mini-3','sd-2024-06-store-1','sd-2024-06-store-2','sd-2024-06-store-3','sd-2024-07-app-1','sd-2024-07-app-2','sd-2024-07-app-3','sd-2024-07-web-1','sd-2024-07-web-2','sd-2024-07-web-3','sd-2024-07-mini-1','sd-2024-07-mini-2','sd-2024-07-mini-3','sd-2024-07-store-1','sd-2024-07-store-2','sd-2024-07-store-3','sd-2024-08-app-1','sd-2024-08-app-2','sd-2024-08-app-3','sd-2024-08-web-1','sd-2024-08-web-2','sd-2024-08-web-3','sd-2024-08-mini-1','sd-2024-08-mini-2','sd-2024-08-mini-3','sd-2024-08-store-1','sd-2024-08-store-2','sd-2024-08-store-3','sd-2024-09-app-1','sd-2024-09-app-2','sd-2024-09-app-3','sd-2024-09-web-1','sd-2024-09-web-2','sd-2024-09-web-3','sd-2024-09-mini-1','sd-2024-09-mini-2','sd-2024-09-mini-3','sd-2024-09-store-1','sd-2024-09-store-2','sd-2024-09-store-3','sd-2024-10-app-1','sd-2024-10-app-2','sd-2024-10-app-3','sd-2024-10-web-1','sd-2024-10-web-2','sd-2024-10-web-3','sd-2024-10-mini-1','sd-2024-10-mini-2','sd-2024-10-mini-3','sd-2024-10-store-1','sd-2024-10-store-2','sd-2024-10-store-3','sd-2024-11-app-1','sd-2024-11-app-2','sd-2024-11-app-3','sd-2024-11-web-1','sd-2024-11-web-2','sd-2024-11-web-3','sd-2024-11-mini-1','sd-2024-11-mini-2','sd-2024-11-mini-3','sd-2024-11-store-1','sd-2024-11-store-2','sd-2024-11-store-3','sd-2024-12-app-1','sd-2024-12-app-2','sd-2024-12-app-3','sd-2024-12-web-1','sd-2024-12-web-2','sd-2024-12-web-3','sd-2024-12-mini-1','sd-2024-12-mini-2','sd-2024-12-mini-3','sd-2024-12-store-1','sd-2024-12-store-2','sd-2024-12-store-3','sd-2025-01-app-1','sd-2025-01-app-2','sd-2025-01-app-3','sd-2025-01-web-1','sd-2025-01-web-2','sd-2025-01-web-3','sd-2025-01-mini-1','sd-2025-01-mini-2','sd-2025-01-mini-3','sd-2025-01-store-1','sd-2025-01-store-2','sd-2025-01-store-3','sd-2025-02-app-1','sd-2025-02-app-2','sd-2025-02-app-3','sd-2025-02-web-1','sd-2025-02-web-2','sd-2025-02-web-3','sd-2025-02-mini-1','sd-2025-02-mini-2','sd-2025-02-mini-3','sd-2025-02-store-1','sd-2025-02-store-2','sd-2025-02-store-3','sd-2025-03-app-1','sd-2025-03-app-2','sd-2025-03-app-3','sd-2025-03-web-1','sd-2025-03-web-2','sd-2025-03-web-3','sd-2025-03-mini-1','sd-2025-03-mini-2','sd-2025-03-mini-3','sd-2025-03-store-1','sd-2025-03-store-2','sd-2025-03-store-3','sd-2025-04-app-1','sd-2025-04-app-2','sd-2025-04-app-3','sd-2025-04-web-1','sd-2025-04-web-2','sd-2025-04-web-3','sd-2025-04-mini-1','sd-2025-04-mini-2','sd-2025-04-mini-3','sd-2025-04-store-1','sd-2025-04-store-2','sd-2025-04-store-3','sd-2025-05-app-1','sd-2025-05-app-2','sd-2025-05-app-3','sd-2025-05-web-1','sd-2025-05-web-2','sd-2025-05-web-3','sd-2025-05-mini-1','sd-2025-05-mini-2','sd-2025-05-mini-3','sd-2025-05-store-1','sd-2025-05-store-2','sd-2025-05-store-3','sd-2025-06-app-1','sd-2025-06-app-2','sd-2025-06-app-3','sd-2025-06-web-1','sd-2025-06-web-2','sd-2025-06-web-3','sd-2025-06-mini-1','sd-2025-06-mini-2','sd-2025-06-mini-3','sd-2025-06-store-1','sd-2025-06-store-2','sd-2025-06-store-3','sd-2025-07-app-1','sd-2025-07-app-2','sd-2025-07-app-3','sd-2025-07-web-1','sd-2025-07-web-2','sd-2025-07-web-3','sd-2025-07-mini-1','sd-2025-07-mini-2','sd-2025-07-mini-3','sd-2025-07-store-1','sd-2025-07-store-2','sd-2025-07-store-3','sd-2025-08-app-1','sd-2025-08-app-2','sd-2025-08-app-3','sd-2025-08-web-1','sd-2025-08-web-2','sd-2025-08-web-3','sd-2025-08-mini-1','sd-2025-08-mini-2','sd-2025-08-mini-3','sd-2025-08-store-1','sd-2025-08-store-2','sd-2025-08-store-3','sd-2025-09-app-1','sd-2025-09-app-2','sd-2025-09-app-3','sd-2025-09-web-1','sd-2025-09-web-2','sd-2025-09-web-3','sd-2025-09-mini-1','sd-2025-09-mini-2','sd-2025-09-mini-3','sd-2025-09-store-1','sd-2025-09-store-2','sd-2025-09-store-3','sd-2025-10-app-1','sd-2025-10-app-2','sd-2025-10-app-3','sd-2025-10-web-1','sd-2025-10-web-2','sd-2025-10-web-3','sd-2025-10-mini-1','sd-2025-10-mini-2','sd-2025-10-mini-3','sd-2025-10-store-1','sd-2025-10-store-2','sd-2025-10-store-3','sd-2025-11-app-1','sd-2025-11-app-2','sd-2025-11-app-3','sd-2025-11-web-1','sd-2025-11-web-2','sd-2025-11-web-3','sd-2025-11-mini-1','sd-2025-11-mini-2','sd-2025-11-mini-3','sd-2025-11-store-1','sd-2025-11-store-2','sd-2025-11-store-3','sd-2025-12-app-1','sd-2025-12-app-2','sd-2025-12-app-3','sd-2025-12-web-1','sd-2025-12-web-2','sd-2025-12-web-3','sd-2025-12-mini-1','sd-2025-12-mini-2','sd-2025-12-mini-3','sd-2025-12-store-1','sd-2025-12-store-2','sd-2025-12-store-3','sd-2026-01-app-1','sd-2026-01-app-2','sd-2026-01-app-3','sd-2026-01-web-1','sd-2026-01-web-2','sd-2026-01-web-3','sd-2026-01-mini-1','sd-2026-01-mini-2','sd-2026-01-mini-3','sd-2026-01-store-1','sd-2026-01-store-2','sd-2026-01-store-3','sd-2026-02-app-1','sd-2026-02-app-2','sd-2026-02-app-3','sd-2026-02-web-1','sd-2026-02-web-2','sd-2026-02-web-3','sd-2026-02-mini-1','sd-2026-02-mini-2','sd-2026-02-mini-3','sd-2026-02-store-1','sd-2026-02-store-2','sd-2026-02-store-3','sd-2026-03-app-1','sd-2026-03-app-2','sd-2026-03-app-3','sd-2026-03-web-1','sd-2026-03-web-2','sd-2026-03-web-3','sd-2026-03-mini-1','sd-2026-03-mini-2','sd-2026-03-mini-3','sd-2026-03-store-1','sd-2026-03-store-2','sd-2026-03-store-3','sd-2026-04-app-1','sd-2026-04-app-2','sd-2026-04-app-3','sd-2026-04-web-1','sd-2026-04-web-2','sd-2026-04-web-3','sd-2026-04-mini-1','sd-2026-04-mini-2','sd-2026-04-mini-3','sd-2026-04-store-1','sd-2026-04-store-2','sd-2026-04-store-3','sd-2026-05-app-1','sd-2026-05-app-2','sd-2026-05-app-3','sd-2026-05-web-1','sd-2026-05-web-2','sd-2026-05-web-3','sd-2026-05-mini-1','sd-2026-05-mini-2','sd-2026-05-mini-3','sd-2026-05-store-1','sd-2026-05-store-2','sd-2026-05-store-3','sd-2026-06-app-1','sd-2026-06-app-2','sd-2026-06-app-3','sd-2026-06-web-1','sd-2026-06-web-2','sd-2026-06-web-3','sd-2026-06-mini-1','sd-2026-06-mini-2','sd-2026-06-mini-3','sd-2026-06-store-1','sd-2026-06-store-2','sd-2026-06-store-3','sd-2026-07-app-1','sd-2026-07-app-2','sd-2026-07-app-3','sd-2026-07-web-1','sd-2026-07-web-2','sd-2026-07-web-3','sd-2026-07-mini-1','sd-2026-07-mini-2','sd-2026-07-mini-3','sd-2026-07-store-1','sd-2026-07-store-2','sd-2026-07-store-3','sd-2026-08-app-1','sd-2026-08-app-2','sd-2026-08-app-3','sd-2026-08-web-1','sd-2026-08-web-2','sd-2026-08-web-3','sd-2026-08-mini-1','sd-2026-08-mini-2','sd-2026-08-mini-3','sd-2026-08-store-1','sd-2026-08-store-2','sd-2026-08-store-3','sd-2026-09-app-1','sd-2026-09-app-2','sd-2026-09-app-3','sd-2026-09-web-1','sd-2026-09-web-2','sd-2026-09-web-3','sd-2026-09-mini-1','sd-2026-09-mini-2','sd-2026-09-mini-3','sd-2026-09-store-1','sd-2026-09-store-2','sd-2026-09-store-3','sd-2026-10-app-1','sd-2026-10-app-2','sd-2026-10-app-3','sd-2026-10-web-1','sd-2026-10-web-2','sd-2026-10-web-3','sd-2026-10-mini-1','sd-2026-10-mini-2','sd-2026-10-mini-3','sd-2026-10-store-1','sd-2026-10-store-2','sd-2026-10-store-3','sd-2026-11-app-1','sd-2026-11-app-2','sd-2026-11-app-3','sd-2026-11-web-1','sd-2026-11-web-2','sd-2026-11-web-3','sd-2026-11-mini-1','sd-2026-11-mini-2','sd-2026-11-mini-3','sd-2026-11-store-1','sd-2026-11-store-2','sd-2026-11-store-3','sd-2026-12-app-1','sd-2026-12-app-2','sd-2026-12-app-3','sd-2026-12-web-1','sd-2026-12-web-2','sd-2026-12-web-3','sd-2026-12-mini-1','sd-2026-12-mini-2','sd-2026-12-mini-3','sd-2026-12-store-1','sd-2026-12-store-2','sd-2026-12-store-3','SalesDay').has('year', 2025).values('revenue').sum()
SELECT SUM(sales_day.revenue) AS 营收 FROM sales_day;
```

**结论**：看板是模型驱动的：换指标/维度/期间只改参数，遍历与 SQL 由模型生成。

## 7. 归因：2024 → 2025 按渠道拆解

贡献之和必须等于变化量；结果明说「描述性、不是因果」。

**做了什么 / 返回了什么**

- 执行：true
- 2024 → 2025：22638919 → 27176294（Δ 4537375）
- STORE：Δ 1688795，shareOfChange 37.22%
- MINI：Δ 1156310，shareOfChange 25.48%
- WEB：Δ 859495，shareOfChange 18.94%
- APP：Δ 832775，shareOfChange 18.35%
- contributionsSum=4537375，decompositionAddsUp=true

**归因的限制声明**

```
Descriptive, not causal: this says which slice moved and by how much, not why.
A slice with a large shareOfChange is not necessarily the cause of the change.
No rows for a slice mean the data does not cover it, not that its value was zero.
```

**结论**：归因把变化拆到一个维度上，算术自证（和为 delta），并拒绝因果化。

## 8. 用户修改 / 删除：受校验、带审计

改一个指标的属性、给它加别名、新增一个术语实体、删掉一条关系 —— 全部走 applyEdits 的校验、before/after 与审计。

**做了什么 / 返回了什么**

- 写入：{"vertices":3,"edges":0,"deletedVertices":0,"deletedEdges":0}；审计 3 条
- update-node metric/营收（有 before）
- add-alias metric/营收（有 before）
- upsert-node term/口径草案
- 删除关系：source/metrics-93857cfe -describes-> term/成交（deletedEdges=1）

**编辑操作（原始 JSON）**

```
[
  {
    "op": "update-node",
    "id": "metric/营收",
    "properties": {
      "description": "毛额口径（2025 版）：成交金额未扣退款"
    }
  },
  {
    "op": "add-alias",
    "id": "metric/营收",
    "alias": "销售额"
  },
  {
    "op": "upsert-node",
    "node": {
      "id": "term/口径草案",
      "entityType": "Term",
      "properties": {
        "name": "口径草案",
        "description": "评审中：营收是否改为净额",
        "proposedBy": "reviewer"
      }
    }
  },
  {
    "op": "delete-edge",
    "edge": {
      "head": "source/metrics-93857cfe",
      "relation": "describes",
      "tail": "term/成交"
    },
    "confirm": true
  }
]
```

> 审计文件：D:\workspace\eclipse\PhoenixGraph\.pi\data-agent\audit.jsonl；快照：D:\workspace\eclipse\PhoenixGraph\.pi\data-agent\models\sales-model.json

**结论**：新增、修改、删除实体与关系都可做，且每次写入都留 before/after 与审计行。

## 9. 口径变更（带时间戳）+ 回溯

2026-10-15 起「营收」改为净额口径：旧定义被冻结成版本节点，新定义从该日生效；asOf 可以取回旧定义。

**做了什么 / 返回了什么**

- supersede：旧定义冻结为 metric/营收#v2024-01-01T00:00:00.000Z，窗口 2024-01-01T00:00:00Z -> 2026-10-15T00:00:00Z
- asOf 2026-10-01：expression=SUM(revenue)，graphProperty=revenue（来自版本 metric/营收#v2024-01-01T00:00:00.000Z）
- asOf 2026-11-01：expression=SUM(net_revenue)，graphProperty=net_revenue
- 2026 年营收，按 2026-10-01 的口径：31507086.47（SELECT SUM(sales_day.revenue) AS 营收 FROM sales_day;）
- 2026 年营收，按 2026-11-01 的口径：30173396.87（SELECT SUM(net_revenue) AS 营收 FROM sales_day;）

**版本节点与窗口**

```
live  node: metric/营收        validFrom=2026-10-15T00:00:00.000Z
version   : metric/营收#v2024-01-01T00:00:00.000Z  2024-01-01T00:00:00Z -> 2026-10-15T00:00:00Z
relation  : metric/营收#v2024-01-01T00:00:00.000Z|supersededBy|metric/营收
```

> 模型里现在有 1 个版本节点；默认视图只显示当前定义，asOf 才会取回旧的。

**结论**：同一指标两个口径按时间戳共存：默认读当前，asOf 读历史，SQL/遍历随定义一起变化。

## 10. 定时生成看板 + 归因（三个周期）

把上面两步做成周期性任务：每个期间一个循环，产物流落盘，运行日志保留。

**做了什么 / 返回了什么**

- 周期 1/3 2024：看板 15 执行 / 1 拒答；归因 Δ 22166052
- 周期 2/3 2025：看板 15 执行 / 1 拒答；归因 Δ 4436492
- 周期 3/3 2026：看板 15 执行 / 1 拒答；归因 Δ 3570853

**调度日志**

```
schedule: graph sales-model · 3 cycle(s) · every 800 ms
period | dashboard (executed/refused) | attribution | files
---|---|---|---
2024 | 15/1 tiles | 2023 -> 2024: delta 22166052 | D:\workspace\eclipse\PhoenixGraph\pi-data-agent\examples\sales-case\artifacts\sales-model-2026-11-02T03-23-43-671Z.html
2025 | 15/1 tiles | 2024 -> 2025: delta 4436492 | D:\workspace\eclipse\PhoenixGraph\pi-data-agent\examples\sales-case\artifacts\sales-model-2026-11-02T03-23-44-907Z.html
2026 | 15/1 tiles | 2025 -> 2026: delta 3570853 | D:\workspace\eclipse\PhoenixGraph\pi-data-agent\examples\sales-case\artifacts\sales-model-2026-11-02T03-23-46-151Z.html

total: 45 executed, 3 refused (a refusal is an answer: it carries the SQL)
```

> 产物目录：D:\workspace\eclipse\PhoenixGraph\pi-data-agent\examples\sales-case\artifacts（HTML + JSON，每周期一份）

**结论**：3 个周期全部产出看板与归因，共 45 个执行 tile、3 个拒答。

## 11. 收尾：模型现状

把当前模型的状态记下来，便于与文档对照。

**做了什么 / 返回了什么**

- 节点 32（另 1 个历史版本节点不在默认视图中）、边 73
- 指标 6、维度 4、表 2、列 14、术语 2
- asOf 2026-11-02T03:23:46.306Z

**结论**：案例结束时模型可读、可回溯、可继续编辑，事实图数据独立于模型图。

---

## 复现

```bash
tools/start-phoenixgraph.sh --no-build        # 8081 + 8080 + Aerospike
cd pi-data-agent
node examples/sales-case/run-case.mjs         # 跑完并重写本文
```

产物：`.pi/data-agent/dashboards/*.html`、`examples/sales-case/artifacts/*.html`、`.pi/data-agent/audit.jsonl`、`.pi/data-agent/models/sales-model.json`。
