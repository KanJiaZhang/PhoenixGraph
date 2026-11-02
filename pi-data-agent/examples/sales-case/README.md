# sales-case — 数据智能体端到端案例

这个目录就是「一个 data agent 从 0 到看板」所需的全部输入与代码，不依赖任何外部数据集：

| 文件 | 作用 |
|---|---|
| `schema.sql` | 事实表的 DDL（`sales_day`、`channel_dim`、外键）——指标的列、类型、主外键从这里读 |
| `show_tables.txt` | `SHOW TABLES` 的输出（只有表名的那份证据） |
| `docs/metrics.md` | 指标字典：指标表达式、单位、别名、维度（含日期粒度 `year=year,month=month`）、术语 |
| `docs/metrics-2026-10.md` | 2026-10-15 起生效的新口径（案例把它作为「reviewer 变更」写入，并演示 `asOf` 回溯） |
| `generate.mjs` | 生成并导入事实数据（固定种子，432 行 = 3 年 × 12 月 × 4 渠道 × 3 地区） |
| `run-case.mjs` | 端到端驱动：11 步，每步打印操作与真实返回，并把同一份记录写成 [`../../docs/CASE-DATA-AGENT.md`](../../docs/CASE-DATA-AGENT.md) |
| `artifacts/` | `run-case.mjs` 定时产出的看板 HTML/JSON（生成物，不入库） |

```bash
# 需要 PhoenixGraph 在 http://localhost:8081（tools/start-phoenixgraph.sh --no-build）
node examples/sales-case/generate.mjs --dry-run     # 只打印会生成什么
node examples/sales-case/generate.mjs               # 建图 + 导入 432 行
node examples/sales-case/run-case.mjs               # 跑完整案例并重写案例文档
node examples/sales-case/run-case.mjs --dry-run     # 只打印，不写文档
```

案例覆盖：文档/DDL/SHOW → 元数据抽取（schema 自动创建）→ 关系（依赖/包含/血缘）→ 向量+图检索 →
看板 → 归因 → 增删改（审计）→ 口径变更与 `asOf` 回溯 → 定时产出。每一步的说明与数字见
[`docs/CASE-DATA-AGENT.md`](../../docs/CASE-DATA-AGENT.md)。
