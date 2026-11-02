# 稳定性测试：同一个问题，两种文法，一个答案

本文件由 `node verification/check-stability.mjs --doc docs/STABILITY.md` 生成（对着运行中的 PhoenixGraph 与
`sales-model` 模型执行）。比较的是**完整结果指纹**——每个 tile 的 id、是否执行、金额/行、拒答理由，以及归因的
before/after/delta/贡献序列/和校验/限制条数——而不是只看一个数字。

| # | 问题（两种写法应等价） | 写法 A | 写法 B | 结果 | 值 |
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

## 对照：换一个问题，结果必须变

| 对照 | 写法 A | 写法 B | 结果 | A / B |
|---|---|---|---|---|
| 对照：不同年份 | 2024 | 2025 | 不同 ✅ | 22166052.35 / 26602544.09 |
| 对照：不同口径时点 | asOf 2026-10-01 | 现在 | 不同 ✅ | 31507086.47 / 30173396.87 |
| 对照：不同维度 | by 渠道 | by 地区 | 不同 ✅ | 4436491.74（top STORE） / 4436491.74（top 华北） |
| 对照：不同期间 | 2024→2025 | 2025→2026 | 不同 ✅ | 4436491.74（top STORE） / 3570852.78（top MINI） |

## 结论

17 组等价写法全部得到同一份结果，4 组对照全部不同。

覆盖的等价关系：指标（id / 名字 / 别名 / 绑定列）、维度（id / 名字 / 绑定列）、期间（字符串 / 数字 / 带空白）、
topN（数字 / 字符串）、`asOf`（ISO 时刻 / 日期 / epoch 毫秒）、事实图绑定（显式 / 取自模型）、指标选择（单数 /
数组 / 不选）、归因（指标与维度的四种写法、期间类型、时点）。

## 复现

```bash
node verification/check-stability.mjs              # 打印
node verification/check-stability.mjs --doc docs/STABILITY.md
```
