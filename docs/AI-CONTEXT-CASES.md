# PhoenixGraph 作为 AI 的上下文层：三个真实案例

> 前三个案例演示了「怎么调用」。这三个案例演示**为什么**——一个 LLM agent 在回答问题时，
> PhoenixGraph 具体替它解决了什么、给了它什么别的组件给不了的东西。每个案例都对着运行中的
> 服务执行，响应是真实的；工具名是 MCP 插件暴露的（`node tools/mcp-server.mjs --selftest` 可自测）。
>
> 姊妹篇：[`docs/END-TO-END.md`](END-TO-END.md)（从文档到归因的完整旅程）·
> [`docs/AGENT-INGESTION-CASE.md`](AGENT-INGESTION-CASE.md)（摄取这一步的逐步交互详解）。

三个案例对应三条产品主张：

| 案例 | 主张 | 没有 PhoenixGraph 会怎样 |
|---|---|---|
| 1 多跳溯源 | 上下文的每一步都可指回原文 | 向量库给你一堆 chunk，agent 自己猜它们怎么连 |
| 2 跨文档 + 拒答 | 有证据才说，没有就不编 | LLM 对没见过的内容照样编一个自信的答案 |
| 3 语义层 | 结构化答案是一份可复核的计划 | agent 自己写一段 SQL，没人知道它对不对 |

---

## 案例 1：多跳溯源 —— 「关羽和诸葛亮是什么关系？」

**场景**：一个分析师问 AI 助手一个跨两个实体的问题。答案是「关羽是刘备的义弟，诸葛亮是刘备的
军师」——要答出这句话，agent 必须知道**关羽→刘备**和**诸葛亮→刘备**两条边，并且能把它们连起来。

**agent 实际做的三次调用**（MCP 工具 → HTTP 端点）：

```
find_entities  "关羽和诸葛亮什么关系"          → 定位起点（关羽 name 命中）
context        graph=agent-demo, question=...   → 拉取这两个实体周围带句子的一跳子图
```

**PhoenixGraph 返回的上下文**（`context`，节选）——不是一堆 chunk，是**带方向的边 + 每条的原文**：

```
关羽  -sibling_of->    刘备   「关羽字云长，是刘备的义弟，也是刘备的臣。」
关羽  -minister_of->   刘备   「关羽字云长，是刘备的义弟，也是刘备的臣。」
诸葛亮 -strategist_of-> 刘备   「刘备的军师是诸葛亮，诸葛亮的师傅是水镜先生。」
水镜先生 -teaches->     诸葛亮  「刘备的军师是诸葛亮，诸葛亮的师傅是水镜先生。」
黄月英 -spouse_of->    诸葛亮  「诸葛亮的师傅是水镜先生，诸葛亮的妻子是黄月英。」
关羽  -killed->        颜良   「关羽斩杀颜良，又与文丑交战。」
…（共 16 条，每条带 chunkId + 原文句子）
```

**agent 据此回答**：从子图里抽出两条边组成 2 跳路径 `关羽 → sibling_of → 刘备 ← strategist_of ← 诸葛亮`，
答「关羽是刘备的义弟，诸葛亮是刘备的军师，两人通过刘备相连」，并附上这两句原文。

**PhoenixGraph 实际做了什么**：
1. **把「相似文本」升级成「可走的关系」**——向量检索只告诉你「这些 chunk 和问题相关」，
   它不知道 chunk 里有「关羽」「刘备」两个实体、也不知道谁是谁的义弟。PhoenixGraph 返回的是
   **类型化实体 + 有方向的边 + 该边的原文句子**，所以 agent 不是「引用了一段话」，而是「走了一条可验证的路径」。
2. **每一步都可溯源**——答案里每一条边都能点回 `sentence` 和 `chunkId`，审阅者可以逐跳核对，而不是相信一段综述。

---

## 案例 2：跨文档消解 + 拒答 —— 「Deploy 42 引发了什么？」「谁批准的？」

**场景**：SRE 把三篇事故复盘喂给 AI 助手（三篇里同一个服务写了三种拼写），问两个问题：
一个是图里有的，一个是图里没有的。

**第一步：摄取三篇文档**（`POST /agent/ingest`，`rulePack: incident`）：

```
checkout-outage.md   The payment-service depends on Redis.  Deploy 42 caused the checkout-api outage.  Alice fixed the cache leak.
database-incident.md The payment service depends on the database.  The database failed during Deploy 42.  The outage was caused by Deploy 42.
redis-outage.md      PaymentService depends on Redis.  Redis was down.  Bob reported the Redis outage.
```

**跨文档消解发生在摄取时**，响应里的 `quality` 直接报告：

```
duplicatesRemoved: 2
mergedEntities: [ { canonical: "payment service",
                    aliases: ["payment-service", "PaymentService"], mentions: 3 } ]
```

三个文档里的 `payment-service` / `payment service` / `PaymentService` 被**确定性地**合并成一个实体
（提及 3 次），而不是变成三个同名不同 id 的节点。这是 PhoenixGraph 和「每篇文档各建各的索引」的本质区别：
同一个服务在不同文档里就是同一个服务。

**问题 A：有证据的** —— agent 调 `context`「what did Deploy 42 cause」：

```
Deploy 42 -caused-> checkout-api   「Deploy 42 caused the checkout-api outage.」
Deploy 42 -caused-> outage          「The outage was caused by Deploy 42.」
```

**问题 B：语料里没有的** —— agent 调 `find_entities`「who approved the deploy」：

```
database(vector,0.18)  Deploy 42(vector,0.17)  payment service(vector,0.17) …
```

关键不是「返回了实体」，而是**没有任何一条边是 `approved`/`authorized`**——`find_entities` 全凭向量
浮出几个「看着像」的实体（分数 0.16–0.18），`context` 里没有任何批准关系。于是 agent 据实回答：
**「这三篇复盘里没有记录是谁批准了这次部署，我不能回答。」**

**PhoenixGraph 实际做了什么**：
1. **跨文档消解**——同一个实体三篇文档只算一次，依赖查询（「payment service 依赖什么」）才不会漏算或重复算。
2. **覆盖率就是拒答的依据**——agent 判断「能不能答」不是靠 prompt 里的「不要编造」，而是靠
   `find_entities` 没有名字命中、`context` 没有相关关系。**证据为空 → 拒答**，这是可审计的拒答，不是模型自觉。

---

## 案例 3：语义层 —— 「投放花费为什么涨了？ROI 变差是效率问题吗？」

**场景**：数据团队问 AI 助手一个经营分析问题。这类问题 agent 最容易搞砸——它「生成一段漂亮 SQL」，
但没人知道口径对不对。

**agent 调语义层工具**（MCP `attribute_change` / `ask_data`）：

```
attribute_change  metric=spend, by=channel, from=2024, to=2025
ask_data          "2025 年各渠道获客成本"     ← 想看 ROI 的分母
```

**PhoenixGraph 返回的是「计划」，不是「一段 SQL」**。归因的响应：

```
totalBefore 14,754,787.14  →  totalAfter 14,731,149.38   delta −23,637.76
contributionsSum −23,637.76   decompositionAddsUp: true    （贡献之和 = 总变化，答案自陈）
搜索 +x · 社交 +y · 邮件 …  （4 个切片，按贡献排序）
limits: 描述性归因，不是因果结论
```

而问「获客成本（CAC = 花费/新客）」时，PhoenixGraph **拒答并给 SQL**：

```
executionRefused: metric/cac 在图里没有可执行的绑定（它是比率）
sql: SELECT marketing.channel, SUM(marketing.spend) / SUM(marketing.new_customers) AS cac
     FROM marketing GROUP BY marketing.channel
```

**PhoenixGraph 实际做了什么**：
1. **结构化问题的答案是计划**——`plan`（指标/维度/年份）+ `traversal`（图上执行的遍历）+
   `sql`（数仓对照）+ `provenance`（行数、来源）一起返回。agent 引用的是「这个数字怎么算出来的」，
   而不是自己拼一段 SQL。审阅者可以拿 `traversal` 重跑、拿 `sql` 到数仓独立核对。
2. **算不了的就拒答**——CAC/ROI 是比率，图里没有预计算属性，于是**拒答 + 给数仓 SQL**，
   绝不把 `SUM(spend)` 硬当成「获客成本」糊弄。这跟案例 2 是同一个原则：能答的带证据，不能答的不编。

---

## 三个案例共同说明的那件事

一个 LLM agent 面对问题时的失败模式是固定的：**召回一堆相关文本、然后自信地生成**。
PhoenixGraph 作为上下文层，把「相关文本」换成「可验证的结构」：

1. **实体是有类型的、关系是有方向的、每跳都有原文**（案例 1）——agent 走的是路径，不是猜关联。
2. **跨文档的同一实体就是同一个**（案例 2）——消解在摄取时确定性完成，覆盖率是拒答的依据。
3. **结构化问题返回计划 + 遍历 + SQL**（案例 3）——答案是可复核的，比率算不了就拒答给 SQL。

一句话：**PhoenixGraph 给 agent 的不是「更多文本」，而是「可以逐条核对的事实、和可以逐跳追溯的路径」——
当这两样都没有的时候，它让 agent 有底气说「我没有」。**

## 复现

```bash
node verification/verify-ai-context.mjs   # 把上面三个案例的关键断言重新跑一遍（案例 2 会真实摄取三篇文档）
```
