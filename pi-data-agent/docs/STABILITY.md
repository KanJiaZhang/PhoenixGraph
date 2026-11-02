# 稳定性测试：同一个问题，不同问法，一个答案

本文件由 `node verification/check-stability.mjs --doc docs/STABILITY.md` 生成（对着运行中的 PhoenixGraph 与
`sales-model` 模型执行）。比较的是**完整结果指纹**——每个 tile 的 id / 是否执行 / 金额或行 / 拒答理由，归因的
before / after / delta / 贡献序列 / 和校验 / 限制条数——而不是只看一个数字。四组：

- **A. 等价写法**：17 组，同一个请求的两种（或三种）拼写；
- **B. 自然语言问句**：12 种说法，经 `src/ask.mjs` 解析成结构化请求后与基线比较；
- **C. 恒等式**：5 条，不同问题之间必须成立的关系（年 = 月之和、归因 = 两个看板之差、反向取反、表达式/表限定列引用）；
- **D. 对照**：4 组换了问题，结果必须不同（否则前面什么也没证明）。

## A. 等价写法

| # | 问题 | 写法 A | 写法 B | 结果 | 值 |
|---|---|---|---|---|---|
| 1 | 指标：名字 vs id | 营收 | metric/营收 | 一致 ✅ | 26602544.09 |
| 2 | 指标：单数 metric vs 数组 metrics | metric:"营收" | metrics:["营收"] | 一致 ✅ | 26602544.09 |
| 3 | 指标：名字 vs 绑定列（cost） | 成本 | cost | 一致 ✅ | 7186303.65 |
| 4 | 指标：一个有歧义的绑定（net_revenue，两个指标都绑它）重复执行 | net_revenue #1 | net_revenue #2 | 一致 ✅ | 26602544.09 |
| 5 | 指标：别名（收入）vs id | 收入 | metric/营收 | 一致 ✅ | 26602544.09 |
| 6 | 期间：字符串 vs 数字 | "2025" | 2025 | 一致 ✅ | 26602544.09 |
| 7 | 期间：带空白 | "2025" | " 2025 " | 一致 ✅ | 26602544.09 |
| 8 | 维度：名字 vs id | dimensions:["渠道"] | dimensions:["dim/渠道"] | 一致 ✅ | 26602544.09 |
| 9 | 维度：绑定列（channel）vs id | dimensions:["channel"] | dimensions:["dim/渠道"] | 一致 ✅ | 26602544.09 |
| 10 | topN：数字 vs 字符串 | topN:3 | topN:"3" | 一致 ✅ | 26602544.09 |
| 11 | asOf：ISO 时刻 vs 日期 vs epoch 毫秒 | asOf:"2026-10-01T00:00:00Z" | asOf:"2026-10-01" / asOf:epoch | 一致 ✅ | 31507086.47 |
| 12 | 事实图绑定：显式 vs 取自模型 | 不传 dataGraph/vertexType | 显式传 | 一致 ✅ | 26602544.09 |
| 13 | 看板范围：不选指标 vs 列出全部指标 | 默认（全部指标） | 显式 metrics（名字） | 一致 ✅ | 7186303.65 |
| 14 | 归因：指标名字 vs id（维度同步换写法） | 营收 / 渠道 | metric/营收 / dim/渠道 | 一致 ✅ | 4436491.74（top STORE） |
| 15 | 归因：维度绑定列（channel）vs id | by:"channel" | by:"dim/渠道" | 一致 ✅ | 4436491.74（top STORE） |
| 16 | 归因：期间字符串 vs 数字 | from/to 字符串 | from/to 数字 | 一致 ✅ | 4436491.74（top STORE） |
| 17 | 归因：asOf ISO vs 毫秒 | asOf ISO | asOf 毫秒 | 一致 ✅ | 4330792.08（top STORE） |

## B. 自然语言问句

### 看板问句（同一问题 7 种说法）（基线：26602544.09）

| 问法 | 解析为 | 结果 | 值 |
|---|---|---|---|
| 2025年营收按渠道拆分，前3 | understood as dashboard: metric=metric/营收 (by id: 营收) · dimension=dim/渠道 (by id) · year=2025 · topN=3 | 一致 ✅ | 26602544.09 |
| 营收 2025 by 渠道 top3 | understood as dashboard: metric=metric/营收 (by id: 营收) · dimension=dim/渠道 (by id) · year=2025 · topN=3 | 一致 ✅ | 26602544.09 |
| 按渠道看下 2025 年的收入前三 | understood as dashboard: metric=metric/营收 (by alias: 收入) · dimension=dim/渠道 (by id) · year=2025 · topN=3 | 一致 ✅ | 26602544.09 |
| 给我 2025 年 metric/营收 按 dim/渠道 前 3 名 | understood as dashboard: metric=metric/营收 (by id: metric/营收) · dimension=dim/渠道 (by id) · year=2025 · topN=3 | 一致 ✅ | 26602544.09 |
| 2025 年销售额按 channel 拆解 top 3 | understood as dashboard: metric=metric/营收 (by alias: 销售额) · dimension=dim/渠道 (by binding) · year=2025 · topN=3 | 一致 ✅ | 26602544.09 |
| 2025年营收按 sales_day.channel 拆分，前3名 | understood as dashboard: metric=metric/营收 (by id: 营收) · dimension=dim/渠道 (by column) · year=2025 · topN=3 | 一致 ✅ | 26602544.09 |
| 营收 2025 按渠道 前三 | understood as dashboard: metric=metric/营收 (by id: 营收) · dimension=dim/渠道 (by id) · year=2025 · topN=3 | 一致 ✅ | 26602544.09 |

### 归因问句（同一问题 5 种说法）（基线：4436491.74（top STORE））

| 问法 | 解析为 | 结果 | 值 |
|---|---|---|---|
| 2024 到 2025 营收按渠道为什么增长 | understood as attribution: metric=metric/营收 (by id: 营收) · by=dim/渠道 (by id) · from=2024 · to=2025 · changeWord=true | 一致 ✅ | 4436491.74（top STORE） |
| 营收 2024 vs 2025 按渠道贡献 | understood as attribution: metric=metric/营收 (by id: 营收) · by=dim/渠道 (by id) · from=2024 · to=2025 · changeWord=true | 一致 ✅ | 4436491.74（top STORE） |
| 按渠道归因：营收从 2024 到 2025 的变化 | understood as attribution: metric=metric/营收 (by id: 营收) · by=dim/渠道 (by id) · from=2024 · to=2025 · changeWord=true | 一致 ✅ | 4436491.74（top STORE） |
| 2025 比 2024，营收按渠道增长了多少 | understood as attribution: metric=metric/营收 (by id: 营收) · by=dim/渠道 (by id) · from=2024 · to=2025 · changeWord=true | 一致 ✅ | 4436491.74（top STORE） |
| metric/营收 2024 -> 2025 by dim/渠道 归因 | understood as attribution: metric=metric/营收 (by id: metric/营收) · by=dim/渠道 (by id) · from=2024 · to=2025 · changeWord=true | 一致 ✅ | 4436491.74（top STORE） |

## C. 恒等式

| 恒等式 | 结果 | A | B | 说明 |
|---|---|---|---|---|
| 按年一次问 = 按月问再相加 | 成立 ✅ | 26602544 | 26602544 | 趋势 12 个月合计 26602544 vs 年度总额 26602544（差 0.0000） |
| 归因 delta = 两个年度看板之差 | 成立 ✅ | 4436492 | 4436492 | 归因 4436492 vs 2025-2024 4436492 |
| 2024→2025 与 2025→2024：delta 取反，切片不变 | 成立 ✅ | 4436492 | -4436492 | 4436492 vs -4436492；切片相同=true；和校验=true |
| 按表达式引用指标（SUM(cost)）= 按 id（metric/成本） | 成立 ✅ | metric/成本|total | metric/成本|total | 解析到 metric/成本|total |
| 按表限定列引用维度（sales_day.channel）= dim/渠道 | 成立 ✅ | metric/营收|breakdown|dim/渠道 | metric/营收|breakdown|dim/渠道 | 解析到 metric/营收|breakdown|dim/渠道 |

## D. 对照

| 对照 | 写法 A | 写法 B | 结果 | A / B |
|---|---|---|---|---|
| 对照：不同年份 | 2024 | 2025 | 不同 ✅ | 22166052.35 / 26602544.09 |
| 对照：不同口径时点 | asOf 2026-10-01 | 现在 | 不同 ✅ | 31507086.47 / 30173396.87 |
| 对照：不同维度 | by 渠道 | by 地区 | 不同 ✅ | 4436491.74（top STORE） / 4436491.74（top 华北） |
| 对照：不同期间 | 2024→2025 | 2025→2026 | 不同 ✅ | 4436491.74（top STORE） / 3570852.78（top MINI） |

## 结论

A 组 17 组等价写法一致，B 组 12 种问法全部解析成同一请求，C 组 5 条恒等式成立，D 组 4 组对照全部不同。

## 复现

```bash
node verification/check-stability.mjs              # 打印四组
node verification/check-stability.mjs --doc docs/STABILITY.md
```
