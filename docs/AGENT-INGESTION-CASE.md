# 案例详解：从一份事故复盘，到一个可审计的知识图谱

> 这是 `docs/END-TO-END.md` 第一个案例的**逐步详解**——每一步跟 PhoenixGraph 怎么交互、请求长什么样、
> 服务内部做了什么、返回了什么、以及每个字段为什么在。所有响应都来自正在运行的服务（8081），
> 图已经真实写入（`apply=true`），不是 dry-run 的想象。

---

## 场景

一份只有 7 句话的事故复盘，里面故意埋了同一个服务的**三种拼写**（`payment-service` / `PaymentService` /
`payment service`）和一个**近重复**（`payments-service`，多一个 `s`）：

```
The payment-service depends on Redis.
Deploy 42 caused the checkout-api outage.
Alice fixed the cache leak.
PaymentService depends on the database.
The payment service depends on Redis.
payments-service depends on Redis.
The outage was caused by Deploy 42.
```

要回答的问题是：**「这次 outage 是谁引起的？payment service 依赖什么？这些结论能不能逐条指回原文？」**

---

## 第 1 步：摄取 —— 文档变成图

**你要什么**：把这段文本变成一张带证据的知识图谱。

**怎么调用**（`POST /api/v1/agent/ingest`）：

```bash
curl -s localhost:8081/api/v1/agent/ingest -H 'content-type: application/json' --data-binary @- <<'EOF'
{"graph":"case-postmortem","apply":true,"replace":true,"rulePack":"incident",
 "dictionary":{"payment-service":"Service","PaymentService":"Service","payment service":"Service",
               "payments-service":"Service","Redis":"Service","database":"Service","checkout-api":"Service",
               "Deploy 42":"Deploy","Alice":"Person","cache leak":"Incident","outage":"Incident"},
 "documents":[{"id":"postmortem","title":"Checkout latency postmortem",
   "text":"The payment-service depends on Redis.\nDeploy 42 caused the checkout-api outage.\nAlice fixed the cache leak.\nPaymentService depends on the database.\nThe payment service depends on Redis.\npayments-service depends on Redis.\nThe outage was caused by Deploy 42."}]}
EOF
```

**字段说明**：`graph` 是目标图名（不存在就创建）；`apply:true` 表示真的写，`false` 则是 dry-run
（跑完整条流水线但什么都不写）；`rulePack:"incident"` 选择事故词汇（`caused`/`fixed`/`depends_on`…）；
`dictionary` 是词典——告诉抽取器哪些是已知实体、是什么类型，避免把「the」「a」当实体。

**PhoenixGraph 内部按这条流水线走一遍**：

```
文档 ─▶ Chunker 分块 ─▶ KnowledgeExtractor 抽实体+关系 ─▶ 去重(dedupe) ─▶ QualityReport 打分 ─▶ 写图 ─▶ 写 artifact
```

1. **分块**：把文本切成可引用的 chunk（这里就 1 块，`postmortem#0`）。
2. **抽取**：`incident` 规则包里的 `between` 模式找关系——`X depends on Y`、`A caused B`、`C fixed D`。
   实体是关系两端的词，词典给它类型（`Service`/`Person`/`Incident`）。
3. **去重（安全层）**：`payment-service`、`PaymentService`、`payment service` 规范化后是同一个名字
   （大小写/连字符/空格归一），**确定性地合并**成一个实体，其他拼写记成别名；
   `payments-service` 跟它只差一个字符，**不合并**，只进 `mergeCandidates` 交人工。
4. **打分**：算 `provenanceRate`（每条关系是否指向包含两端实体的原文句子）、`typeAgreement`
   （词典类型与抽取类型是否一致）、`entityLinkRate`（多少实体进了关系）等。
5. **写图**：`ensureSchema` 建好 `Entity` / `Chunk` 两类顶点和 `Relates` / `Mentions` 两类边，
   然后走普通写路径把顶点和边 upsert 进去（向量的 HNSW 索引也由同一段代码维护）。
6. **写 artifact**：把实体（含向量、描述、别名、引文）和关系落成 `case-postmortem.agent.json`，
   AI 工具读它做召回，不需要每问一次就扫一遍图。

**返回了什么**（节选，带注释）：

```json
{
  "graph": "case-postmortem", "applied": true,
  "quality": {
    "entities": 9, "relations": 5,
    "relationsByType": { "depends_on": 2, "fixed": 1, "caused": 2 },
    "provenanceRate": 1,          // 每条关系都能在句子里找到两端实体 —— 证据完整
    "typeAgreement": 1,           // 抽取类型与词典类型全部一致
    "entityLinkRate": 0.8889,
    "duplicatesRemoved": 2,       // 三种拼写合并掉了两个
    "mergedEntities": [ { "canonical": "payment service",
                          "aliases": ["payment-service","PaymentService"], "mentions": 3 } ],
    "mergeCandidates": [ { "left": "payment service", "right": "payments-service",
                           "score": 0.8, "reason": "one-character spelling difference" } ]
  },
  "entities": 9, "relations": 5,
  "vertices": 10, "edges": 14,    // 9 实体 + 1 chunk；5 关系边 + 9 提及边
  "indexFile": "case-postmortem.agent.json",
  "notes": ["rule pack: incident",
            "created graph 'case-postmortem' (D:\\...\\case-postmortem.json)"]
}
```

**这证明了什么**：摄取不是黑盒。回答里带着质量度量、去重明细和待审清单，dry-run 与真写是同一个
流水线（`apply` 只是一个开关）。

---

## 第 2 步：读结果 —— 图谱里有什么

**你要什么**：看看抽取出来的实体长什么样（描述、别名、引文）。

**怎么调用**（`GET /api/v1/agent/index/{graph}`）：

```bash
curl -s localhost:8081/api/v1/agent/index/case-postmortem
```

**返回**（一个实体，节选）：

```json
{ "name": "payment service",
  "type": "Service",
  "id": "payment service",
  "description": "payment service，Service；depends_on Redis。The payment-service depends on Redis.…",
  "aliases": ["payment-service", "PaymentService"],
  "cites": ["Checkout latency postmortem §1"] }
```

**字段说明**：`description` 是这张实体的**上下文卡**——「它是什么 + 它有什么关系 + 它出现在哪些句子」，
向量就是从这张卡算出来的（所以向量检索找的是「语义相近」，不是「字符串相近」）；`aliases` 是被
合并掉的拼写；`cites` 是它能引用的原文位置。

质量单独一个端点 `GET /api/v1/agent/quality/{graph}`，返回和摄取响应里同一份 `quality` 度量。

---

## 第 3 步：问图（召回）—— 「outage 是谁引起的」

**你要什么**：给一句自然语言，找到它提到（或接近）的实体。

**怎么调用**（`POST /api/v1/agent/find-entities`）：

```bash
curl -s localhost:8081/api/v1/agent/find-entities -H 'content-type: application/json' \
  --data-binary @<(printf '{"graph":"case-postmortem","question":"which service caused the outage","k":3}')
```

**返回**：

```json
{ "tool": "find_entities",
  "entities": [
    { "id": "outage",         "type": "Incident", "score": 0.8393, "via": "name",
      "cites": ["Checkout latency postmortem §1"] },
    { "id": "payment service","type": "Service",  "score": 0.238,  "via": "vector" },
    { "id": "checkout-api",   "type": "Service",  "score": 0.237,  "via": "vector" } ],
  "citations": [ ... ] }
```

**内部怎么做**：先在 artifact 里做**名字匹配**（`outage` 直接命中），再用**向量**召回语义相近的实体
（`payment service`、`checkout-api` 被浮出来）。`via` 字段告诉你每个结果是靠名字还是靠向量找到的——
这是「可审计召回」：你能知道为什么它出现在答案里。

混合检索 `POST /api/v1/agent/search` 是同一个入口的更全版本（名字 + 描述词 + 向量一起排序，`via: hybrid`）。

---

## 第 4 步：问图（扩展）—— payment service 依赖什么

**你要什么**：拿到实体在**图里**的邻居和关系（这一步才真正碰图，召回只碰 artifact）。

**怎么调用**（`POST /api/v1/agent/related`）：

```bash
curl -s localhost:8081/api/v1/agent/related -H 'content-type: application/json' \
  --data-binary @<(printf '{"graph":"case-postmortem","entity":"payment service"}')
```

**返回**：

```json
{ "entities":  [ { "id": "payment service", "via": "seed" }, { "id": "Redis", "via": "related" } ],
  "relations": [ { "head": "payment service", "relation": "depends_on", "tail": "Redis",
                   "sentence": "The payment-service depends on Redis.…" } ] }
```

**内部怎么做**：从 `payment service` 出发走一跳 `outE('Relates')`，把命中的边连同它的**原文句子**带回来。

问整句话的上下文用 `POST /api/v1/agent/context`——它把召回 + 扩展串起来，回答「what caused the outage」时返回：

```json
{ "relations": [
    { "head": "Deploy 42", "relation": "caused", "tail": "outage",
      "sentence": "…Deploy 42 caused the checkout-api outage.…" },
    { "head": "Alice",     "relation": "fixed",   "tail": "cache leak",
      "sentence": "…Alice fixed the cache leak.…" } ] }
```

这就是答案的形状：**不是一段生成的话，而是一组带原文句子的关系**——agent 用它们来组织回答，用户用
`cites`/`sentence` 来核对回答。

---

## 第 5 步：复核 + 合并 —— 接受「payments-service 就是 payment service」

**你要什么**：`mergeCandidates` 里那条「一字符之差」的建议，人工确认它确实是同一个服务，把它并掉。

**怎么调用**（`POST /api/v1/agent/merge`）：

```bash
curl -s localhost:8081/api/v1/agent/merge -H 'content-type: application/json' \
  --data-binary @<(printf '{"graph":"case-postmortem","keep":"payment service","drop":"payments-service"}')
```

**返回**：

```json
{ "graph": "case-postmortem", "keep": "payment service", "drop": "payments-service",
  "edgesRewritten": 2, "chunkCount": 1, "indexFile": "case-postmortem.agent.json" }
```

**内部怎么做**：把 `drop` 的所有 `Relates` / `Mentions` 边里碰到它的端点改写为 `keep`（另一端不动），
`keep` 的 `chunkIds` 吸收 `drop` 的，删除 `drop` 顶点，并把 `drop` 的名字记成 `keep` 的别名——
图和 artifact 一起更新，两者不会漂移。

**合并后再读**（`GET /api/v1/agent/index/case-postmortem`），`payment service` 变成了：

```json
{ "name": "payment service", "type": "Service",
  "aliases": ["payment-service", "PaymentService", "payments-service"],  // 多了一个
  "cites": ["Checkout latency postmortem §1"] }
```

**这证明了什么**：机器从不替人拍板「差一个 `s` 的两个服务是不是同一个」——它只列出候选；人一旦
拍板，`/merge` 就把它贯彻到图和索引里，并且答案自陈改了多少条边。

---

## 小结：每一步的交互一览

| 步骤 | 端点 | 做什么 | 碰图还是碰 artifact |
|---|---|---|---|
| 摄取 | `POST /agent/ingest` | 分块→抽取→去重→打分→写图→写 artifact | 写（Aerospike） |
| 读结果 | `GET /agent/index/{g}`、`GET /agent/quality/{g}` | 实体/关系/质量 | artifact |
| 召回 | `POST /agent/find-entities`、`POST /agent/search` | 名字 + 向量找实体 | artifact（向量） |
| 扩展 | `POST /agent/related`、`POST /agent/context` | 图里走邻居、带原文句子 | 图 |
| 复核 | `POST /agent/merge` | 接受候选，合并两实体 | 写（图 + artifact） |

贯穿始终的是一条原则：**能答的每一步都带着出处（`cites`/`sentence`/`chunkId`），不能确定的
（近重复、缺绑定）宁可列出来交人，也不猜。**
