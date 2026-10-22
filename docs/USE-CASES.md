# Use cases: knowledge context and data analysis

This document covers the two main use cases: a knowledge graph as context for an LLM, and the semantic,
execution and analysis layers a data agent needs. Each has a working graph and queries you can rerun.

---

## 1. A knowledge graph as agent context

![the knowledge graph around a question](screenshots/knowledge-graph-context.png)

The Three Kingdoms graph is served in three forms (`sanguo-kg` on Aerospike, `sanguo-kg-rocksdb-edge`
and `sanguo-kg-text` on RocksDB-Edge). One traversal hands an agent the **evidence** around the entities
of a question:

```groovy
// the neighbourhood of the entities in the question, as context: relations, offices, battles,
// and the chapters the mentions came from (the edges carry relation + provenance)
g.V('关羽','Entity').bothE().otherV().dedup().limit(18)
```

What makes the graph usable as context:

- **One unified schema** for the domain: a typed `Entity` vertex and one relation edge class whose
  edges carry `relation`, `xrelation`, `relationLabel` and `relationFamily`. "Who is whose lord /
  minister / advisor" is a filter on an edge property, not a different edge class per question — which
  is why a new relation does not need a schema change.
- **From RDF and from free text.** [SANGUO-KG.md](../SANGUO-KG.md) documents the document-to-RDF mapping;
  `sanguo-kg-text` adds entities and relations extracted from the documents' running text by a
  dictionary plus regular expressions (no model, fully reproducible), with the sentence each `mentions`
  edge came from, so an answer can point back at its evidence.
  [SANGUO-KG-TEXT-EXTRACTION.md](../SANGUO-KG-TEXT-EXTRACTION.md) lists every rule.
- **Question-shaped templates.** [SANGUO-KG-QUERIES.md](../SANGUO-KG-QUERIES.md) is a handbook of 22
  question/answer queries ("关羽 和 诸葛亮 是什么关系？", "谁是五虎上将？", two- and three-hop relation
  paths) plus the Cypher equivalent of each, ready to be parameterised by a retrieval or agent layer.
- **Two engines, one answer.** The same graph on Aerospike and on RocksDB answers the shared questions
  identically (`SanguoRocksDbEdgeTest#matchesTheAerospikeGraphOnTheSharedQuestions`).

The unified schema in one line:

```
Entity(id, name, type, ...)  --Entity_Entity{relation, xrelation, relationLabel, relationFamily}-->  Entity
```

`relation` is the machine name (`lord`, `minister`, `master`, `serves`, `holds`, `appearsIn`,
`mentions`, ...), `relationLabel` the human label (`主公`, `义兄`, ...), `relationFamily` a grouping
(`亲属`, `官职`, ...). Consequences:

- a new relation is data, not a schema change;
- one query asks about any relation, and a family filter answers "all kinship of 诸葛亮";
- the same schema serves the RDF graph, the RocksDB graph and the free-text-extracted graph, so the
  three can be compared and merged;
- every extracted edge can carry provenance (the chapter, sometimes the sentence), which is what makes
  the graph usable as evidence for an answer.

```groovy
g.V('诸葛亮','Entity').inE().filter(relation.equals('lord')).otherV().values('name')   // 刘备
g.V('诸葛亮','Entity').bothE().filter(relationFamily.equals('亲属')).otherV().dedup().values('name')
g.V('五虎上将','Entity').inE().filter(relation.equals('holds')).otherV().values('name')
```

The agent toolkit (`POST /api/v1/agent/context`, see [AI-TOOLKIT.md](AI-TOOLKIT.md)) adds the
text-to-seed stage, citations and a `coverage` block on top of these queries. Two complete analyses that
audit the graph's own edges are in [AGENT-CASES.md](AGENT-CASES.md).

---

## 2. Building a data agent: semantic → execution → analysis

One scenario followed through the three layers a data agent needs, on TPC-H. Every number below was run
on this repository's own service.

> **The question** (a user, in natural language):
> 「1996 年的净收入比 1995 年怎么样？变化是什么驱动的？」
> "How did 1996 net revenue compare with 1995, and what drove the change?"

The answer has three jobs: **know what the metric means** (semantic layer), **produce a query that is
provably the right one** (execution layer), **explain the number** (analysis layer). Guessing any of the
three is how analytics agents go wrong: they invent a metric, then a join, then a story.

### 2.1 Semantic layer: the metric, its lineage and its attribution

`tpc-h-analytics` is a graph of the warehouse's semantics: 8 `Table`, 61 `Column`, 17 `Metric`, 11
`Dimension`, 6 `Domain` and 12 `Query` nodes (115 nodes, 264 relations), built by
`node tools/generate-tpch-schema.js`. A metric is a node with its standard expression; lineage and
attribution are edges (`computedFrom`, `usesColumn`, `groupedBy`, `contributesTo`, `references`).

The agent asks the graph instead of being told:

| Ask | Query | Answer (live) |
| --- | --- | --- |
| What *is* `revenue`? | `g.V('metric/revenue','Node').values('aggFunction','expression','unit')` | `SUM` \| `SUM(l_extendedprice * (1 - l_discount))` \| `元` |
| Which tables? | `...outE().filter(relation.equals('computedFrom')).otherV().values('name')` | `table/lineitem` |
| Which columns? | `...outE().filter(relation.equals('usesColumn')).otherV().values('name')` | `l_extendedprice`, `l_discount` |
| Which dimensions? | `...outE().filter(relation.equals('groupedBy')).otherV().values('name')` | `dim/order_date`, `dim/region`, `dim/nation`, `dim/mktsegment` |
| What drives it? | `...inE().filter(relation.equals('contributesTo')).otherV().values('name')` | `gross_revenue`, `discount_amount`, `tax_amount`, `promotion_revenue`, `order_count`, `avg_order_value` |
| Can I see time? | `g.V('dim/order_date','Node').outE().filter(relation.equals('dimensionColumn')).otherV().values('name')` | `column/orders.o_orderdate` (`DATE`) |
| How do I join it? | `g.V('column/lineitem.l_orderkey','Node').outE().filter(relation.equals('references')).otherV().values('name')` | `column/orders.o_orderkey` |

So the agent knows, before writing anything: *net revenue is a SUM over lineitem, it can be cut by year,
and it is driven by gross revenue and discounts* — the attribution half of the answer is already named.
The `contributesTo` chain is why "it went down" can become "discounts grew faster than gross revenue".

### 2.2 Execution layer: SQL assembled from the graph, then run as a graph query

`tools/semantic-sql.mjs` turns the metric node plus a dimension into the SQL by walking the graph: the
expression from the metric, the identifiers qualified from `usesColumn`, the `GROUP BY` from
`groupedBy` + `dimensionColumn`, and the **join path by breadth-first search over `references`** — no
join is written by hand anywhere in the tool.

```bash
node tools/semantic-sql.mjs --metric metric/revenue
node tools/semantic-sql.mjs --metric metric/revenue --group-by dim/order_date --grain year
node tools/semantic-sql.mjs --metric metric/revenue --group-by dim/region --json
```

```sql
-- metric: metric/revenue (净收入：订单行的金额扣掉折扣后的收入)
-- expression from the graph: SUM(l_extendedprice * (1 - l_discount)), unit: 元
-- join from references: lineitem.l_orderkey -> orders.o_orderkey
SELECT EXTRACT(YEAR FROM orders.o_orderdate) AS order_date_year,
       SUM(lineitem.l_extendedprice * (1 - lineitem.l_discount)) AS revenue
FROM lineitem
JOIN orders ON lineitem.l_orderkey = orders.o_orderkey
GROUP BY EXTRACT(YEAR FROM orders.o_orderdate)
ORDER BY order_date_year;
```

Ask for a dimension two hops away and the generator finds the snowflake chain itself:

```bash
node tools/semantic-sql.mjs --metric metric/revenue --group-by dim/region
```

```sql
-- join from references: lineitem.l_suppkey -> supplier.s_suppkey
-- join from references: supplier.s_nationkey -> nation.n_nationkey
-- join from references: nation.n_regionkey -> region.r_regionkey
SELECT region.r_name AS region,
       SUM(lineitem.l_extendedprice * (1 - lineitem.l_discount)) AS revenue
FROM lineitem
JOIN supplier ON lineitem.l_suppkey = supplier.s_suppkey
JOIN nation ON supplier.s_nationkey = nation.n_nationkey
JOIN region ON nation.n_regionkey = region.r_regionkey
GROUP BY region.r_name
ORDER BY region;
```

A metric whose expression touches two tables brings the second one along:
`--metric metric/promotion_revenue --group-by dim/region` adds
`JOIN part ON lineitem.l_partkey = part.p_partkey` (its `p_type LIKE 'PROMO%'` came from `usesColumn`),
on top of the same chain. The tool prints what it could not ground as a `-- WARNING:` comment instead of
inventing it, and `--json` returns the parts (expression, tables, columns, joins, warnings, sql) for an
agent to check before it runs.

This repository has no SQL database — the SQL is the deliverable for a real warehouse (PostgreSQL /
DuckDB / MySQL, `--dialect`), and the *same analysis intent* runs here as a graph query on `tpc-h-data`
(8,000 order lines with the metrics materialised, so it is one scan):

```gremlin
g.V('Sale').group().by('orderYear').by(values('revenue').sum())
g.V('Sale').group().by('orderYear').by(values('gross').sum())
g.V('Sale').group().by('orderYear').by(values('discountAmount').sum())
```

The generator checks itself, so the SQL in this document cannot drift away from the graph:

```bash
node tools/semantic-sql.mjs --metric metric/revenue --group-by dim/order_date --grain year \
  --expect "SELECT EXTRACT(YEAR FROM orders.o_orderdate) AS order_date_year, \
            SUM(lineitem.l_extendedprice * (1 - lineitem.l_discount)) AS revenue \
            FROM lineitem JOIN orders ON lineitem.l_orderkey = orders.o_orderkey \
            GROUP BY EXTRACT(YEAR FROM orders.o_orderdate) ORDER BY order_date_year"   # == matches
```

### 2.3 Analysis layer: from the numbers to the explanation

| Year | revenue (net) | gross | discountAmount |
| --- | --- | --- | --- |
| 1995 | 95,612,794 | 100,509,809 | 4,897,015 |
| 1996 | 98,879,433 | 104,059,747 | 5,180,314 |
| **Δ** | **+3,266,639 (+3.42%)** | **+3,549,938 (+3.53%)** | **+283,299 (+5.79%)** |

The analysis writes itself from the attribution edges the semantic layer named:

- net revenue **grew**, by 3.27M (3.42%);
- it grew *less* than gross revenue (3.53%) because **discounts grew faster** (5.79%) — 8.0% of the gross
  growth was given away in discounts;
- the discount share of gross moved 4.87% → 4.98%, so the gap is a pricing/promotion effect, not a
  demand collapse;
- the next questions are therefore about the other `contributesTo` upstreams: `promotion_revenue`
  (75,667,262 over the whole instance, computed with the definition the graph carries:
  `p_type LIKE 'PROMO%'`) and `order_count` / `avg_order_value` (is the growth volume or basket size?).

That is the difference between a number and an answer: the graph supplied the *decomposition*, the
warehouse supplied the *numbers*, and the analyst (a person or a model) supplies the judgement.

An AI reaches these three layers over the same HTTP API, in either of two ways:

- **A curated graph** (like `tpc-h-analytics`, built by a schema generator) is queried as it stands — the
  tables above *are* the tool surface, and they are as machine-callable as a tool call is.
- **An ingested corpus** gets the toolkit (see [AI-TOOLKIT.md](AI-TOOLKIT.md)) on top:
  `POST /api/v1/agent/context` adds the text-to-seed stage, the four vector/graph combinations, and
  citations with a `coverage` block — what a curated graph's own fields do not carry. On `agent-demo`,
  `context('诸葛亮和司马懿是什么关系')` answers with the entities, the relations and the source sentences
  in one document.

Ingest when the knowledge has to be read out of documents (with provenance and a quality report); curate
a graph when the knowledge is a standard you must not paraphrase (TPC-H's metric definitions). A data
agent can use both, because both answer over the same API.

```bash
# reproduce the whole story: start the service, then
node tools/semantic-sql.mjs --metric metric/revenue --group-by dim/order_date --grain year
curl -s -X POST localhost:8081/api/v1/query -H 'content-type: application/json' \
  -d '{"graphName":"tpc-h-data","query":"g.V(\u0027Sale\u0027).group().by(\u0027orderYear\u0027).by(values(\u0027revenue\u0027).sum())","includeResult":true}'
# the semantic layer's 13 assertions (metrics, lineage, attribution, join paths, real numbers):
./mvnw -o -pl graph-integration-test test -Dtest='TpchDataAgentGraphTest' -Dsurefire.failIfNoSpecifiedTests=false
```

> **Data note:** the year figures above are the ones the served `tpc-h-data` instance answers today.
> Three things in that build are *not* what a TPC-H reader would expect:
> (1) `lateDelivery` and `returned` are **integers** (`0`/`1`), so `has('lateDelivery', true)` matches
> nothing while `has('lateDelivery', 1)` matches 771 of the 8,000 sales;
> (2) the `nation -> region` assignment is cyclic instead of the TPC-H reference — only 7 of 25 nations
> sit where TPC-H puts them, so any per-region figure is meaningless, and `region` and `segment` come out
> to the same five sums because the loader derives both from the same cyclic index;
> (3) `promotionRevenue` is absent. The full ledger, including the older numbers that are no longer
> valid, is [DATA-QUALITY.md](DATA-QUALITY.md); [AGENT-CASES.md](AGENT-CASES.md) case B walks through
> all three findings as an agent auditing its own analysis before reporting it.

### 2.4 Two worked cases

[AGENT-CASES.md](AGENT-CASES.md) walks two full cases end to end — every step shows the query and its
measured output, and `.logs/verify-agent-cases.mjs` re-checks all 63 figures against the running service:

- **case A (三国) — the agent audits the knowledge graph's own edges.** 关羽's ten `killed` relations are
  matched one by one against the `history` texts stored on the same vertices: only **2** hold (颜良, 庞德);
  **4** are refuted — by the history (华雄 → 孙坚, 蔡阳 → 刘备, 文丑 → 曹操) or by a timeline (庞会 is in
  景元四年/263, 44 years after 关羽's death); **3** are novel-only or unprovable because the evidence field
  is truncated at 120 characters (566 of 2,743 entities hit the cap); and the whole relation family turns
  out to be **300/300 `provenance=EXTRA`** — a *quantified* source bias rather than noise, which is what
  makes the fix obvious (split the relation by source, not "clean the data").
- **case B (TPC-H) — the agent refuses a per-region answer.** The obvious region ranking looks usable
  (ASIA leads EUROPE by 327k) until three self-checks fire: **18 of 25 nations** sit in the wrong TPC-H
  region, `region` and `segment` return the **same five sums to the cent**, and `has('lateDelivery', true)`
  silently returns **0** where the integer `1` returns **771** rows. It then delivers what *is* defensible:
  revenue +3.42% against discounts +5.79% and late-delivery revenue +10.15%; RAIL/SHIP late at 10.4%/10.1%
  against MAIL at 8.5%; and no return-rate signal by priority (4.06%-4.69%).

Both cases do the same thing: **case A asks whether a relation can be believed, case B whether a
dimension can be used**, and each looks for the check that could overturn the answer before reporting it.
[DATA-AGENT-CASES.md](DATA-AGENT-CASES.md) adds the 问数 / 分析 / 归因 cases with refusals and
`mode=confirm`.

---

For more on the semantic layer graph itself, see [TPC-H-ANALYTICS.md](../TPC-H-ANALYTICS.md) (Chinese)
and the design contract in [DATA-AGENT.md](DATA-AGENT.md) (Chinese).
