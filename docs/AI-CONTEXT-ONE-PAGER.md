# PhoenixGraph 作为 AI 的上下文层（一页纸）

> 给评估者：这不是能力清单，是**三个可以当场重跑的问题**。每个数字都标了它出自响应的哪个字段、
> 由哪条断言固定住。`node verification/verify-ai-context.mjs` 全绿，就是这一页说的事情今天仍然成立。
> 详细过程见 [`AI-CONTEXT-CASES.md`](AI-CONTEXT-CASES.md)、[`AGENT-INGESTION-CASE.md`](AGENT-INGESTION-CASE.md)、
> [`END-TO-END.md`](END-TO-END.md)。

## 它解决的那件事

一个 LLM agent 面对问题的失败模式是固定的：**召回一堆相关文本，然后自信地生成**。具体三种：

| 失败 | 后果 | PhoenixGraph 的答案 |
|---|---|---|
| 只知道 chunk 相似，不知道它们怎么连 | 编造实体之间的关系 | 类型化实体 + **有方向的边 + 该边的原文句** |
| 同一实体在多份文档里写成不同样子 | 依赖查询漏算或重复算 | 摄取时**确定性消解**，别名记录在案 |
| 结构化问题自己写 SQL | 没人知道口径对不对；比率也硬答 | 返回 **plan + traversal + sql + provenance**，算不了就拒答 |

## 三个案例

| # | 问题 | agent 调用 | 回答靠什么 | 断言 |
|---|---|---|---|---|
| 1 | 关羽和诸葛亮是什么关系？ | `context(graph=agent-demo)` | 一条 2 跳路径，每跳带原文句 | `verify-ai-context.mjs:31,33` |
| 2 | Deploy 42 引发了什么？谁批准的？ | `ingest` → `context` / `find-entities` | 跨文档合并后的因果边；没有证据就拒答 | `:64,68,74` |
| 3 | 投放花费为什么涨？ROI 变差是效率问题吗？ | `analysis/attribute` / `semantic/ask` | 贡献可加总的分解；比率拒答并给 SQL | `:82,87` |

### 案例 1：多跳溯源 —— 答案是一条可逐跳核对的路径

`POST /api/v1/agent/context`，`graph=agent-demo`，`question=关羽和诸葛亮什么关系`（2026-10-26 实测）：

```json
{
  "coverage": { "entities": 14, "relations": 16, "citations": 4, "seeds": 5, "hops": 1 },
  "entities": [
    {"name": "关羽",   "type": "Person", "via": "name", "score": 0.6595},
    {"name": "诸葛亮", "type": "Person", "via": "name", "score": 0.6545}],
  "relations": [
    {"head": "关羽", "relation": "sibling_of", "tail": "刘备", "rule": "is-of",
     "cite": "三国志 · 关羽传（示例文本） §2",
     "sentence": "关羽字云长，是刘备的义弟，也是刘备的臣。"},
    {"head": "关羽", "relation": "minister_of", "tail": "刘备", "rule": "is-of",
     "cite": "三国志 · 关羽传（示例文本） §2",
     "sentence": "关羽字云长，是刘备的义弟，也是刘备的臣。"},
    {"head": "诸葛亮", "relation": "strategist_of", "tail": "刘备", "rule": "of-is",
     "cite": "三国志 · 关羽传（示例文本） §2",
     "sentence": "刘备的军师是诸葛亮，诸葛亮的师傅是水镜先生。"}],
  "citations": [{"chunkId": "guanyu#0", "cite": "三国志 · 关羽传（示例文本） §1"},
                {"chunkId": "guanyu#1", "cite": "三国志 · 关羽传（示例文本） §2"},
                {"chunkId": "zhugeliang#1", "cite": "三国志 · 诸葛亮传（示例文本） §2"},
                {"chunkId": "zhugeliang#0", "cite": "三国志 · 诸葛亮传（示例文本） §1"}]
}
```

（`cite` 由 chunk 的 `title` 与 `ordinal` 组合而成，`sentence` 是该 chunk 内**真正包含这两个实体**的那句话——
所以「军师」这句标的是《关羽传》§2：它确实出现在关羽传那一块里。）

agent 据此拼出 `关羽 → sibling_of → 刘备 ← strategist_of ← 诸葛亮`，答「关羽是刘备的义弟，诸葛亮是刘备的军师，
两人通过刘备相连」，并附上这两句原文。**关键差别**：向量检索只能给出"这些 chunk 与问题相关"，
给不出谁是谁的义弟；这里是"走了一条可验证的路径"，不是"引用了一段话"。

数字出处：`coverage` 五个字段即上表；实体 `via=name` 表示按名字命中而非向量浮出；
每条关系的 `sentence` 必须包含两端（`quality.provenanceRate=1`，见 `agent-demo.agent.json`，17 实体 / 19 关系 / 4 chunk）。

### 案例 2：跨文档消解 + 拒答 —— 有证据才说，没有就不编

三篇复盘，同一个服务写成 `payment-service` / `payment service` / `PaymentService`（`POST /api/v1/agent/ingest`，
`rulePack=incident`，`apply=true`）：

```json
{ "quality": {
    "entities": 9, "relations": 5, "chunks": 3, "documents": 3,
    "provenanceRate": 1, "typeAgreement": 1, "entityLinkRate": 0.8889,
    "duplicatesRemoved": 2,
    "mergedEntities": [ { "canonical": "payment service", "mentions": 3,
                          "aliases": ["payment-service", "PaymentService"] } ] } }
```

**问题 A（有证据）** `context(question="what did Deploy 42 cause")`：

```json
[{"head":"Deploy 42","relation":"caused","tail":"checkout-api","rule":"caused",
  "cite":"Checkout outage postmortem §1",
  "sentence":"The payment-service depends on Redis.Deploy 42 caused the checkout-api outage.Alice fixed the cache leak."},
 {"head":"Deploy 42","relation":"caused","tail":"outage","rule":"was-caused-by",
  "cite":"Database incident §1",
  "sentence":"The payment service depends on the database.The database failed during Deploy 42.The outage was caused by Deploy 42."}]
```

**问题 B（语料里没有）** `find-entities(question="who approved the deploy", k=5)`：

```json
{ "entities": [{"name":"database","via":"vector","score":0.181},
               {"name":"Deploy 42","via":"vector","score":0.1702},
               {"name":"payment service","via":"vector","score":0.1675}],
  "coverage": {"entities":5,"relations":0,"citations":3,"candidates":9} }
```

全部 `via=vector`、分数 0.16–0.18、**name 命中 0 条，`relations` 为 0**，没有任何 `approved/authorized` 边。
于是 agent 据实回答：「这三篇复盘里没有记录是谁批准了这次部署，我不能回答。」
**拒答的依据是覆盖率，不是 prompt 里写的"不要编造"。**

数字出处：`quality.mergedEntities[0].mentions=3`；`duplicatesRemoved=2`；`find-entities` 的 `via` 与 `relations` 计数。
artifact 落在 `case-outage-<timestamp>.agent.json`。

### 案例 3：语义层 —— 结构化答案是计划，不是一段没人能核对的 SQL

`POST /api/v1/analysis/demo-semantics/attribute`，`question=2024 到 2025 年各渠道花费为什么变化`：

```json
{ "attribution": {
    "metric": "spend", "dimension": "dim/channel", "from": "2024", "to": "2025",
    "totalBefore": 14754787.14, "totalAfter": 14731149.38, "delta": -23637.76,
    "contributions": [
      {"value":"搜索", "2024":3888950.91, "2025":3832604.79, "delta":-56346.13},
      {"value":"社交", "2024":3640712.73, "2025":3693447.31, "delta": 52734.58},
      {"value":"邮件", "2024":3647450.06, "2025":3634985.04, "delta":-12465.02},
      {"value":"联盟", "2024":3577673.43, "2025":3570112.24, "delta": -7561.19}],
    "contributionsSum": -23637.76, "decompositionAddsUp": true,
    "limits": ["Descriptive attribution: … It is not a causal claim, …"] } }
```

同一个响应还带着复核它自身所需的东西：`plan`（`metric/spend`、`dim/channel`、`fromYear/toYear`、`grain=raw`）、
`provenance.traversal`（图上真正跑的那次遍历，`rows=4`）、`executedOn: demo-data` 与数仓对照 `sql`。
审阅者可以拿 `traversal` 重跑，也可以拿 `sql` 到数仓独立核对——**答案自带复核手段**。

`POST /api/v1/semantic/demo-semantics/ask`，`question=2025 年各渠道获客成本`（CAC 是比率）：

```json
{ "executionRefused": "Metric 'metric/cac' cannot be executed on graph 'demo-data': neither the model nor the binding says which property carries it. The SQL is still available from /translate.",
  "sql": "SELECT marketing.channel AS channel,\n       SUM(marketing.spend) / SUM(marketing.new_customers) AS cac\nFROM marketing\nGROUP BY marketing.channel\nORDER BY channel;" }
```

**算不了就拒答并交付 SQL，绝不把 `SUM(spend)` 当成"获客成本"。** 与案例 2 是同一个原则。

数字出处：`attribution.contributionsSum == attribution.delta == -23637.76`（`decompositionAddsUp: true`）；
`executionRefused` 必须含 `SUM(marketing.spend) / SUM(marketing.new_customers)`。

## 它不做什么（同样重要）

- **不做因果**：归因是描述性分解，响应自带 `limits`，说清"哪个切片解释了变化"不等于"哪个是原因"。
- **不猜近重复**：`payments-service` 与 `payment-service` 只差一个字符，只进 `mergeCandidates`，由
  `POST /api/v1/agent/merge` 交人决定（改边、并 chunk、记别名）。
- **不做无依据的抽取**：抽取是规则 + 词典确定的，没有 LLM 摘要，所以答案可复现；覆盖面不足时宁可拒答。
- **不承诺无限规模**：10⁶ 顶点/10⁶ 边实测可用（1M 边 422 s，0 失败）；10⁷ 撞到开发容器 3.0 GB 的 namespace
  上限，属部署配置而非 loader 上限，见 [`BENCHMARK.md`](BENCHMARK.md) / [`CAPACITY.md`](CAPACITY.md)。

## 复现

```bash
tools/start-phoenixgraph.sh --no-build       # 8081 API + 8080 UI
node verification/verify-ai-context.mjs      # 六个断言：路径、原句、合并、拒答、可加总、比率拒答
```

案例 2 会真实摄取三篇文档（需要 Aerospike 写入路径），因此每次跑都会留下一个
`case-outage-<timestamp>.agent.json` —— 这一页里的数字就是从这种 artifact 与那次响应里取的。
