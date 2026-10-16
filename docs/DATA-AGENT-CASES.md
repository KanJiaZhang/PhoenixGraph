# Data-agent cases: what can be asked, and how the answers read

Worked examples of the two surfaces built on the semantic layer - **智能问数** (`POST /api/v1/semantic/{graph}/ask`)
and **业务分析与归因** (`POST /api/v1/analysis/{graph}/analyze|attribute`) - with the numbers the served instance
returns today. Every figure below was measured against the running service; each case states its plan, its
execution, its answer and its limits, because an answer without those is a claim.

The API this exercises, and the reason each mode exists:

| Mode | What it does | Use it when |
|---|---|---|
| `auto` | runs the plan and answers | the question is routine and the caller is a program |
| `confirm` | returns the plan plus an HMAC-signed token (plan hash + expiry) and runs **nothing** | a human (or an agent) should approve what will run; sending the same call back with the token executes it |

`mode=confirm` keeps no server state, so a confirmation survives a restart and works across replicas. All of these
questions are reachable through the MCP plugin as well (`ask_data`, `analyze_business`, `attribute_change`).

---

## 1. 智能问数: five shapes

| # | Question | Plan the interpreter derives | Answer (measured) |
|---|---|---|---|
| A1 | `1996 年各地区营收` | `metric/revenue` by `dim/region`, year filter 1996 | total **98,879,432.89**; AMERICA 20,573,318 · AFRICA 19,850,244 · ASIA 19,753,908 (5 rows) |
| A2 | `1996 年各品牌销量` | `metric/quantity_sold` by `dim/brand` | total **104,099**; Brand#21 21,533 · Brand#41 20,894 · Brand#31 20,614 (5 rows) |
| A3 | `1996 年各运输方式延迟交付` | `metric/late_delivery_count` by `dim/ship_mode` | total **396**; SHIP 76 · RAIL 70 · REG AIR 67 (6 rows) |
| A4 | `1996 年各国家营收` | `metric/revenue` by `dim/nation` | total **98,879,432.89**, 25 rows |
| A5 | `1996 年各细分行业营收` | `metric/revenue` by `dim/mktsegment` | 5 rows — **not usable, see §4** |

Every answer carries `plan` (metric, dimension, grain, years, the traversal the executor built), `provenance`
(the traversal, the fact graph, the binding property, the row count), `sql` (the warehouse form of the same
question) and `confidence` (score, matched terms, warnings).

### What gets refused, and why that is the feature

| Question | Refusal | Why it is correct |
|---|---|---|
| `1996 年订单优先级营收` | `dim/order_priority may not slice metric/revenue; it allows mktsegment, region, nation, order_date` | the model declares which dimensions may slice which metric; the engine does not invent one |
| `1996 年各退货标志营收` | `dim/return_flag may not slice metric/revenue` | same, plus the binding maps `return_flag` nowhere: the fact graph's `returned` is a 0/1 flag, which is not the model's R/A/N |
| `1996 年各细分利润率` | `no metric in this graph matches the question; it holds …` | the synonym file has no word for `metric/profit` yet - one line of data, not a code change |
| `今年各地区营收` | refused when the dimension is a date | no clock relative to the data; guessing a year would be worse than refusing |
| `1996 年各渠道营收` | `asks for a breakdown but names no dimension this graph holds` | a breakdown phrase with no dimension would otherwise be answered as a grand total: a right number to a question nobody asked |

---

## 2. 归因 example one: an amount, sliced five ways

`1995 到 1996 年各地区营收为什么变化`

```
plan       metric/revenue by dim/region, 1995 -> 1996, grain raw
traversal  g.V('Sale').has('orderYear', 1995).group().by('region').by(values('revenue').sum())
alsoRan    g.V('Sale').has('orderYear', 1996).group().by('region').by(values('revenue').sum())
sql        SELECT region.r_name AS region, SUM(lineitem.l_extendedprice * (1 - lineitem.l_discount)) AS revenue
           FROM lineitem JOIN supplier ON … JOIN nation ON … JOIN region ON … GROUP BY region.r_name
```

| value | 1995 | 1996 | delta | shareOfChange |
|---|---:|---:|---:|---:|
| AMERICA | 18,311,520.37 | 20,573,317.54 | +2,261,797.17 | 69.24% |
| AFRICA | 18,579,757.06 | 19,850,244.31 | +1,270,487.25 | 38.89% |
| MIDDLE EAST | 19,442,533.31 | 19,031,725.42 | −410,807.89 | −12.58% |
| EUROPE | 19,517,585.95 | 19,670,237.80 | +152,651.85 | 4.67% |
| ASIA | 19,761,397.36 | 19,753,907.82 | −7,489.54 | −0.23% |

```
totalBefore=95,612,794.05  totalAfter=98,879,432.89  delta=3,266,638.84
contributionsSum=3,266,638.84  decompositionAddsUp=true
analysis: deltaPercent 3.42%, direction up, topMover AMERICA, concentrationAfter 20.81%, outlierScore 2.52
```

Readings: two regions carry 108% of the change while two fall; EUROPE grows 0.78% yet **loses share** (20.41% →
19.89%), which is the difference between growth and share. Note the SQL needs three joins (its model is
normalised) while the graph needs none (the fact vertices carry `region`), and the binding is what keeps the two
paths on one definition.

## 3. 归因 example two: the same amount, sliced twenty-five ways

`1995 到 1996 年各国家营收为什么变化` - same totals and the same delta, `dim/nation`, 25 rows per period.

| value | 1995 | 1996 | delta | shareOfChange |
|---|---:|---:|---:|---:|
| SAUDI ARABIA | 2,664,500.42 | 4,641,863.11 | +1,977,362.69 | 60.53% |
| FRANCE | 3,021,771.63 | 4,972,557.52 | +1,950,785.89 | 59.72% |
| ALGERIA | 4,791,366.56 | 2,907,997.41 | −1,883,369.15 | −57.65% |
| MOROCCO | 3,124,836.34 | 4,590,086.37 | +1,465,250.03 | 44.85% |
| INDONESIA | 4,351,400.74 | 3,118,087.10 | −1,233,313.64 | −37.75% |
| JAPAN | 4,302,702.60 | 3,226,270.93 | −1,076,431.67 | −32.95% |

Readings: the total is up 3.42% while six of the twelve largest movers fall - the aggregate hides a reallocation.
Three nations (SAUDI, FRANCE, ALGERIA) net +2.04M, about 62% of the whole change, so the story is structural, not
general growth.

## 4. 归因 example three: an event metric, six ways

`1995 到 1996 年各运输方式延迟交付为什么变化` - a different *kind* of measure: `late_delivery_count` sums a 0/1
flag, so the question is operational ("are we delivering late more often?") rather than financial.

```
traversal  g.V('Sale').has('orderYear', 1995).group().by('shipmode').by(values('lateDelivery').sum())
sql        SELECT lineitem.l_shipmode, COUNT(CASE WHEN l_receiptdate > l_commitdate THEN 1 END) FROM lineitem GROUP BY 1
```

| ship mode | 1995 | 1996 | delta | shareOfChange |
|---|---:|---:|---:|---:|
| MAIL | 52 | 67 | +15 | 71.43% |
| TRUCK | 66 | 54 | −12 | −57.14% |
| SHIP | 65 | 76 | +11 | 52.38% |
| RAIL | 63 | 70 | +7 | 33.33% |
| REG AIR | 66 | 67 | +1 | 4.76% |
| AIR | 63 | 62 | −1 | −4.76% |

```
totalBefore=375  totalAfter=396  delta=21  contributionsSum=21  decompositionAddsUp=true
analysis: deltaPercent 5.6%, topMover MAIL, concentrationAfter 19.19%, outlierScore −1.60
```

Readings: +21 net hides MAIL +15 against TRUCK −12; MAIL alone explains 71.43% of the change and grows 28.85% of
its own base, so it is a structural deterioration rather than a general one; the negative outlier score is the
MAD-based reading saying TRUCK is the unusual *improvement*. Cross-check: 375 + 396 = **771**, the late-delivery
count this repository verified independently (`docs/DATA-QUALITY.md`), so two paths through the same data agree.

The operational follow-up - **"did volume grow, or did the rate get worse?"** - needs the same dimension on
`metric/order_count`, and that call currently returns rows without a measure: the `group().by(count())` shape is
not parsed (recorded as a gap in `verification/eval-data-agent.mjs`, which is where the fix will be verified).

---

## 5. Limits, stated with the answers

1. **Descriptive, not causal.** Every attribution carries `limits` saying so. It reports which slice moved and by
   how much; a slice explaining most of a change is not necessarily its cause.
2. **`region` and `segment` are numerically identical in the demo data** (`docs/DATA-QUALITY.md` §4: the loader
   gives them one cyclic index). Anything sliced by `dim/mktsegment` - case A5, and any segment attribution -
   therefore looks exactly like the region answer and must not be read as a business finding. Grouping by
   `nation`, `brand`, `ship_mode` or `priority` does not inherit that defect.
3. **Noisy warning.** Any question that names years and groups by a non-date dimension gets
   `the question names a year but groups by … `. For an explicit two-year comparison that warning is a false
   alarm; it is recorded rather than silenced because the condition it guards is real in other phrasings.
4. **Count metrics count rows, not distinct orders.** `metric/order_count` counts fact rows (line items), so any
   rate derived from it is a row-level rate. The wording in the answer must say so.
5. **Demo scale.** About 8,000 rows over two years: a 12-row swing in one ship mode is real and thin
   (`docs/CAPACITY.md`), which is another reason the answer stops at description.

## 6. Reproducing

```bash
curl -s localhost:8081/api/v1/semantic/tpc-h-analytics/ask -H 'content-type: application/json' \
  --data-binary @<(printf '{"question":"1996 年各地区营收"}')
curl -s localhost:8081/api/v1/analysis/tpc-h-analytics/attribute -H 'content-type: application/json' \
  --data-binary @<(printf '{"question":"1995 到 1996 年各运输方式延迟交付为什么变化"}')

node verification/verify-data-agent.mjs      # 22 assertions: the numbers above and the refusal rules
node verification/eval-data-agent.mjs        # the answer-level ledger: five categories, gaps printed not hidden
node tools/mcp-server.mjs --selftest         # the same tools through the MCP plugin (13 tools)
```
