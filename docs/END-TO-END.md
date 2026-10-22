# 端到端案例：从一份事故复盘，到一个可审计的归因结论

> 这是一份「能力验证」案例，不是一份功能介绍。下面每一步都对着**正在运行的服务**（8081）执行，
> 每一个数字都来自真实的响应，并且每一条都能用文末的一条命令重新跑出来——这是 PhoenixGraph 与
> 「能生成一段漂亮 SQL 的 demo」的区别：**答案可以复核，而复核靠的是答案自己携带的证据**。

---

## 场景

一个数据团队在一个下午要回答三个看起来互不相干、其实同一类的问题：

1. **「这次事故到底是谁引起的？」** —— 从几份事故复盘里抽实体、抽关系，并且每个结论都要指向原文。
2. **「营收为什么涨了？」** —— 不问「涨了多少」，而问「哪个地区贡献了这波上涨」。
3. **「投放花费涨了，是量涨了、还是每一单的效率变差了？」** —— 这是运营看完报表之后真正会问的下一句。

三个问题的背后是产品的两条主线：

```
文档 ──▶ 实体 + 关系 + 向量 ──▶ 带证据的知识图谱        （可审计上下文层）
                                                              │
指标 / 维度 / 血缘 / 归因 ──▶ 问数 / 分析 / 归因 / 拒答      （图原生语义层）
```

---

## 0. 启动（两分钟）

```bash
tools/start-phoenixgraph.sh --no-build        # 8081 API + 8080 UI
curl -s localhost:8081/api/v1/health          # {"status":"UP",...}
```

本案例用的两个语义图都是 **RocksDB** 承载，不需要 Aerospike：`tpc-h-analytics`（TPC-H 订单模型）与
`demo-semantics`（营销投放模型）。

---

## 1. 文档 → 可审计知识图谱（摄取，dry-run）

> 这一步的**逐步交互详解**（每个端点的请求、内部处理、响应与字段含义，含 `apply=true` 真实写入与
> `POST /agent/merge` 的合并过程）见 [`docs/AGENT-INGESTION-CASE.md`](AGENT-INGESTION-CASE.md)。

输入一份事故复盘（刻意写进了同一实体的**三种拼写**和一个**近重复**）：

```
The payment-service depends on Redis.
Deploy 42 caused the checkout-api outage.
Alice fixed the cache leak.
PaymentService depends on the database.
The payment service depends on Redis.
payments-service depends on Redis.
The outage was caused by Deploy 42.
```

```bash
curl -s localhost:8081/api/v1/agent/ingest -H 'content-type: application/json' \
  --data-binary @- <<'EOF'
{"graph":"case-postmortem","apply":false,"rulePack":"incident",
 "dictionary":{"payment-service":"Service","PaymentService":"Service","payment service":"Service",
               "payments-service":"Service","Redis":"Service","database":"Service","checkout-api":"Service",
               "Deploy 42":"Deploy","Alice":"Person","cache leak":"Incident","outage":"Incident"},
 "documents":[{"id":"postmortem","title":"Checkout latency postmortem","text":"The payment-service depends on Redis.\nDeploy 42 caused the checkout-api outage.\nAlice fixed the cache leak.\nPaymentService depends on the database.\nThe payment service depends on Redis.\npayments-service depends on Redis.\nThe outage was caused by Deploy 42."}]}
EOF
```

响应（`apply=false` 是 dry-run：跑完整条流水线，不写任何东西）：

| 度量 | 值 | 说明了什么 |
|---|---|---|
| 实体 / 关系 | **9 / 5** | 抽取不是空的 |
| `duplicatesRemoved` | **2** | 三种拼写被**确定性地**合并了 |
| `mergedEntities` | `payment service`（别名 `payment-service`、`PaymentService`，提及 3 次） | 大小写/连字符变体是同一个实体 |
| `mergeCandidates` | `payment service` ↔ `payments-service`（一字符之差，0.8 分） | **近重复只列出来给人工，不自动合并** |

这一步演示的是产品的主线之一——**「宁拒不猜」**：字符串上只差一个 `s` 的两个服务，可能是同一个、也可能是两个，
所以引擎只把它们写进 `mergeCandidates` 交给人工，而不是自作主张合并。人工要接受时，有
`POST /api/v1/agent/merge {graph, keep, drop}` 把 `drop` 并入 `keep`（改边、并 chunks、记别名）。

> 写路径需要 Aerospike（`apply=true`），本机 Docker 未启动，所以这里用 dry-run 展示抽取与去重；
> 语义层以下各步用 RocksDB 图，**全部是真实执行**。

---

## 2. 智能问数：一个数字，连同它怎么算出来的

问：**「1996 年各地区营收」**

```bash
curl -s localhost:8081/api/v1/semantic/tpc-h-analytics/ask -H 'content-type: application/json' \
  --data-binary @<(printf '{"question":"1996 年各地区营收"}')
```

回答不只是 `98,879,432.89`，而是**数字 + 计划 + 遍历 + SQL + 置信度**一起回来：

```
plan       metric/revenue by dim/region, year filter 1996
traversal  g.V('Sale').has('orderYear', 1996).group().by('region').by(values('revenue').sum())
sql        SELECT region.r_name AS region,
                  SUM(lineitem.l_extendedprice * (1 - lineitem.l_discount)) AS revenue
           FROM lineitem JOIN supplier ON … GROUP BY region.r_name
total      98,879,432.89   （5 行：AMERICA 20,573,318 · AFRICA 19,850,244 · ASIA 19,753,908 …）
```

这个数字是这个仓库**逐字节对过账**的基准（`verification/eval-data-agent.mjs`）。关键不是数字本身，
而是「遍历」让任何人都能重跑一遍，「SQL」让数仓能独立核对同一句话。

**拒答也是功能。** 问「1996 年各渠道营收」——模型里根本没有「渠道」这个维度：

```
refusal: the question asks for a breakdown but names no dimension this graph holds;
         the dimensions that may slice metric/revenue are mktsegment, region, nation, order_date
```

给一个「看似合理的总额」是错的，因为那是在回答一个**没人问过的问题**。拒答 + 给可用维度，才是对的。

---

## 3. 业务归因：不是「涨了 3.27M」，而是「谁贡献了这 3.27M」

问：**「1995 到 1996 年各地区营收为什么变化」**

```bash
curl -s localhost:8081/api/v1/analysis/tpc-h-analytics/attribute -H 'content-type: application/json' \
  --data-binary @<(printf '{"question":"1995 到 1996 年各地区营收为什么变化"}')
```

| value | 1995 | 1996 | delta | shareOfChange |
|---|---:|---:|---:|---:|
| AMERICA | 18,311,520.37 | 20,573,317.54 | **+2,261,797.17** | **69.24%** |
| AFRICA | 18,581,767.40 | 19,850,244.30 | +1,270,487.25 | 38.89% |
| MIDDLE EAST | 20,407,672.63 | 19,996,864.73 | −410,807.89 | −12.58% |
| … | | | | |

```
totalBefore=95,612,794.05  totalAfter=98,879,432.89  delta=+3,266,638.84
contributionsSum=3,266,638.84  decompositionAddsUp=true
```

两点值得注意：**贡献之和 = 总变化**（答案自己声明 `addsUp=true`，可复核）；答案同时写明
`limits` ——「描述性归因，不是因果结论」，一个切片解释了大部分变化 ≠ 它就是原因。

---

## 4. 第二张语义图：营销 demo，证明不是 TPC-H 特调

同样的 runner / 解释器 / 渲染器，换一张图（`demo-semantics`，10 指标 / 4 维度 / 1152 行确定性事实），
就能答营销问题：

问：**「2025 年各渠道花费」**

```
total 14,731,149.38
搜索 3,832,605 · 社交 3,693,447 · 邮件 3,634,985 · 联盟 3,570,112
traversal  g.V('MarketingDay').has('year', 2025).group().by('channel').by(values('spend').sum())
```

问：**「2025 年各渠道获客成本」（CAC = 花费 / 新客数，是个比率）**

```
executionRefused: Metric 'metric/cac' cannot be executed on graph 'demo-data':
                  neither the model nor the binding says which property carries it.
sql              SELECT marketing.channel, SUM(marketing.spend) / SUM(marketing.new_customers) AS cac
                 FROM marketing GROUP BY marketing.channel
```

基础指标（spend/impressions/revenue…）在图里**真实求和**；比率指标（CAC/ROI/CTR）图里算不了，
于是**拒答 + 给数仓 SQL**——绝不把「SUM(spend)」硬当成「CAC」来糊弄。

归因照旧可用：**「2024 到 2025 年各渠道花费为什么变化」** → `delta −23,637.76`，`addsUp=true`，4 个贡献切片。

---

## 5. 收尾一问：**「量涨了，还是率变差了？」**

运营看到「延迟交付 375 → 396（+21）」之后，真正想问的是：是单量变多了（所以延迟按比例变多），
还是配送本身变差了？这需要把**事件数**和**它的分母**放在同一维度下对看。

| ship mode | 1995 行数 | 1996 行数 | 1995 延迟 | 1996 延迟 | 延迟率变化 |
|---|---:|---:|---:|---:|---:|
| MAIL | 709 | **698** | 52 | **67** | **7.33% → 9.60%** |
| 全部 | 3,952 | 4,048 | 375 | 396 | 9.49% → 9.78% |

MAIL 这一行就是答案：**量在降（709→698），延迟在升（52→67），所以是率变差了，不是量涨了**。
这正是上一轮补上的 `metric/line_count`（订单行数）——「量涨还是率降」这种追问，在有了正确的分母指标后
才可回答；而「按运输方式切订单数」这种问法仍会被拒（`order_count` 是去重订单数，不能按行级维度切）。

---

## 6. 这条案例到底验证了什么

1. **可审计**：每个答案带 `plan`（计划）、`traversal`（遍历）、`sql`（数仓对照）、`provenance`（来源与行数）。
2. **确定性**：解释器不依赖 LLM，同一句话永远得到同一个计划；文档抽取同理，可复现。
3. **宁拒不猜**：未定义的维度、无处绑定的比率、数据不覆盖的年份——一律拒答并说明原因，而不是给个漂亮数字。
4. **不硬编码单一模型**：同一套语义层在 TPC-H（订单）和营销（投放）两张图上跑通，日期判定按 `dataType` 而非某个固定 id。
5. **实体消解分层**：大小写/繁简/全半角 → 安全自动合并；近重复 → 只列 `mergeCandidates` 交人工 `/merge`。
6. **每个数字都有出处**：文中的基准数字由 `verification/` 下的脚本逐条断言，不是写在文档里的承诺。

## 7. 一键复现

```bash
node verification/verify-e2e-case.mjs     # 把上面 2–5 各步的数字重新跑一遍并断言
node verification/eval-data-agent.mjs     # 答案级台账：6 类，0 失败 0 缺口
node verification/verify-demo-semantics.mjs
node verification/verify-semantic-api.mjs # 32 条 SQL 与 CLI 逐字节一致
```
