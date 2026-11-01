# Design: pi-data-agent

## 1. Goal

Turn the two documents a data team already has - **business definitions** (PDF/Markdown/wiki/Word) and
**database metadata** (DDL / `SHOW` output) - into a graph that can answer three questions without a model
in the loop:

1. *What does this metric mean, where was that written, and which columns does it read?*
2. *Which dimensions may slice it, and on which fact graph property does that execute?*
3. *What moved between two periods, and how much of the change does each slice account for?*

The package is deterministic. It reads text and SQL, writes `Node`/`Relation` vertices into PhoenixGraph, and
evaluates against a fact graph. It does not call an LLM, run user SQL, or connect to a database.

## 2. Pipeline

```
sources ──► readers ──► observations ──► model ──► PhoenixGraph
             │             │              │
             │             │              └─ Model node: dataGraph, vertexType, catalog
             │             └─ every fact: sourceId, quote, locator, confidence, proposedBy
             └─ .md/.html/wiki/.pdf/.docx/.sql/SHOW/JSON/CSV

PhoenixGraph model ──► dashboard / attribution
                         │
                         └─ executes on the fact graph, shows traversal + SQL + rows
```

### Readers

| Format | Implementation | Failure behaviour |
|---|---|---|
| Markdown/text | raw text, headings/tables preserved | empty file is a named skip |
| HTML/wiki export | dependency-free HTML → headings, `| tables |`, lists | scripts/styles dropped |
| MediaWiki markup | headings/tables/lists | |
| PDF | `pdftotext -layout`; optional `pdftoppm`+`tesseract` | missing binary is a named skip |
| DOCX | built-in ZIP central-directory reader + OOXML text | legacy `.doc` refused |
| SQL / SHOW | DDL, `SHOW CREATE TABLE`, `SHOW TABLES`, `DESCRIBE`, `information_schema` | unparseable statement is a warning |

A skipped source never becomes an empty document.

### Observations

Every observation is one fact read from one place:

```json
{
  "id": "metric/营收",
  "sourceId": "metrics-9442a698",
  "locator": "metrics.md:10",
  "evidence": "| 营收 | SUM(revenue) | 元 | 收入、营业额 | revenue | ... |",
  "confidence": 0.95,
  "reason": "explicit definition",
  "proposedBy": "doc-table"
}
```

The extractor recognises:

- labelled prose: `指标：GMV = SUM(amount)`, `口径：…`, `单位：元`, `别名：…`, `维度：渠道=channel`;
- tables with metric/dimension/term headers (Chinese and English aliases);
- SQL aggregates with an alias (`SUM(x) AS revenue`) and ratios (`100 * SUM(a) / SUM(b) AS ctr`);
- `GROUP BY` columns as dimensions;
- DDL columns, types, comments, PK/FK; `SHOW`/`information_schema` rows.

A metric without an expression stays in the model as `status=needs_review`; no formula is invented.

### Model

| Entity | id | Key properties |
|---|---|---|
| Table | `table/<name>` | `name`, `description`, `domain` |
| Column | `column/<table>.<col>` | `dataType`, `columnRole`, `tableName` |
| Metric | `metric/<name>` | `expression`, `aggFunction`, `unit`, `metricKind`, `graphProperty`, `alias` |
| Dimension | `dim/<name>` | `graphProperty` (or `year=…,month=…`), `alias`, `dataType` |
| Query | `query/<name>` | `sqlText`, `tables` |
| Term | `term/<name>` | `description`, `alias` |
| Source | `source/<id>` | title/path/URL |
| Model | `model/<graph>` | `dataGraph`, `vertexType`, `nodeIds`, `edgeIds` |
| Domain | `domain/<name>` | description |

Relations:

```
table  -hasColumn->        column
column -references->       column            (FK -> PK)
metric -computedFrom->     table
metric -usesColumn->       column
metric -groupedBy->        dimension
dimension -dimensionColumn-> column
metric -definedBy->        query
query  -readsTable->       table
base metric -contributesTo-> derived metric
source -describes->        node
node   -belongsTo->        domain
```

All edges carry `relation / xrelation / relationLabel / relationFamily / confidence / evidence / status /
sourceId`.

### Binding rule

`graphProperty` is the only bridge between the semantic column (`column/lineitem.l_extendedprice`) and the
fact property (`Sale.revenue`). It is data, never a substring heuristic:

- explicit value from a table/prose/edit wins;
- otherwise a metric binds when its expression reads exactly one property the fact vertex type declares;
- a dimension binds when the fact vertex type declares the column name (or `year`/`month` for a date);
- no match -> `unboundMetrics` / `unboundDimensions`, SQL still exported, execution refused.

### Merge rule

Two observations merge when their id matches. Cross-language duplicates (e.g. `metric/营收` and
`metric/revenue`) merge when **expression and binding agree**; the second name becomes an alias and a warning
records the merge. This is visible, not silent.

## 3. Storage and restart

Model graph:

- `Node` + `Relation` schema created on first build;
- default storage **Aerospike** (the HTTP batch endpoint writes there);
- the Model node carries `nodeIds`/`edgeIds`, because Aerospike cannot scan a whole type - reads go catalog →
  ids → vertices/edges;
- `build` writes a schema copy to `.pida-schemas/<graph>.json`; `phoenixgraph.storage.schema-dir` discovers
  it at start-up, so the graph survives a restart (the dynamic-creation and discovery paths now derive the same
  Aerospike vertex set);
- RocksDB-edge graphs are readable by scan but not writable over HTTP; `writeModel` refuses them with a
  reason instead of a 400 mid-batch.

The fact graph (`dataGraph`/`vertexType`) is untouched; the package only reads it with Gremlin.

## 4. Dashboard and attribution

A dashboard tile is a semantic query: metric × optional dimension × period, executed as

```
g.V(<vertexType>).has(<yearProperty>, <year>).group().by(<dimensionProperty>).by(values(<measure>).sum())
```

Tiles: total, trend (year or month), Top-N breakdown. Each tile carries its traversal, SQL, rows, warnings
and an `executed` flag. A refusal carries the reason + SQL; it is never rendered as zero.

Attribution:

```
before = rows(year=from), after = rows(year=to)
delta  = after - before (per slice and total)
shareOfChange = sliceDelta / totalDelta
growth = sliceDelta / sliceBefore
HHI / top1 = concentration of shares
outlierScore = (delta - median) / (1.4826 * MAD)
decompositionAddsUp = |sum(sliceDelta) - totalDelta| <= tolerance
```

Slices present in only one period are kept with a warning, so the contributions still sum to the change.
`limits` always say the answer is descriptive, not causal.

## 5. Risk: the ledger and the graph

A risk question has two sources, and the answer is only as good as the join between them:

```
case ledger (CSV/JSON)                     entity graph (PhoenixGraph)
  period × channel × rule × ring             Account —FuzzyLinkedTo→ FuzzyLink
  exposure / loss / blocked / cases          Account —Payment→ Account (collection receiver)
  fp_cases / review_hours                    payment count, amount, status
        │                                            │
        └──────────── manifest.rings: ring → accounts ┘
```

The ledger answers *what moved*; the graph answers *who is behind the slice*. Both keep their citations:

```
summary(period)  = Σ column per metric definition        (metricValue: SUM / COUNT / AVG / RATIO)
breakdown(dim)   = groupMetric(rows, exposure, dim) sorted by measure
delta            = summary(to) - summary(from)            (exposure / loss / blocked / cases / rates)
ring evidence    = members · fuzzy link · payments · receiver · centrality   (one query each)
verdict          = confirmed iff every check passed, else candidate + failedChecks
hypotheses       = rule-sensitivity · loss-improvement · ring-reentry, scored and sorted
```

Each ring's checks are stated, not implied: at least `minMembers` accounts resolved, one shared FuzzyLink key,
a single collection receiver, that receiver's degree dominating the payer count, and payments present. The
centrality numbers are what make "one account collects" checkable - `receiverDegree`, `payerCount`,
`receiverDominance` - instead of an assertion.

Every hypothesis carries `support`, `checks` and `refutations`: a candidate explanation without the observation
that would overturn it is an opinion. `limits` say the attribution is descriptive and that a FuzzyLink is a
pattern-similarity candidate, not a fraud fact.

A query the graph cannot answer becomes a `warning` and a failed check (or `executed: false` with its reason
when there is no client at all). It is never a zero: zero accounts and "nobody could read the accounts" are
different findings, and only one of them is evidence.

File names built from a period or a graph name go through `pathSegment`, because both come from user input: a
period of `../../x` must not write outside `.pi/data-agent/`.

## 6. Editing

Operations are validated before any write: id prefix vs entityType, relation whitelist, endpoint types,
binding format, node existence, delete confirmation and cascade. Writes go through the same schema mapping as
a build; a snapshot (`.pi/data-agent/models/<graph>.json`) and an audit line
(`.pi/data-agent/audit.jsonl`) are written after a successful edit. Dry-run validates only.

The HTTP delete endpoints take the id in the body (`POST /graphs/{graph}/vertices/delete`,
`POST /graphs/{graph}/edges/delete`) because an id such as `metric/revenue` contains a slash that the path
form cannot carry through the servlet container.

## 7. Failure modes this design keeps visible

| Situation | Answer |
|---|---|
| metric with no expression | model + `needs_review`, no execution |
| metric/dimension with no binding | SQL exported, execution refused |
| year not covered by the data | zero **with** "the data does not cover that year" |
| breakdown word with no matching dimension | refuse instead of returning a grand total |
| source cannot be read | named skip with the reason |
| graph storage has no HTTP write | refuse before writing |
| dashboard tile that cannot execute | refusal + SQL, not an empty chart |
| attribution slice missing on one side | kept, warned, sum still equals delta |
| a ring whose evidence query failed | `warning` + failed check, verdict `candidate` - not zero accounts |
| no graph client at all | `executed: false` with the reason, never fabricated evidence |
| risk period/slice not in the ledger | absent from the answer; no interpolated zero |

## 8. Tests

Unit tests (`npm test`) cover readers, extraction, model/binding/merge, schema mapping, catalog reads,
editing validation, attribution arithmetic, dashboard rendering, the risk ledger/manifest/evidence/
hypotheses, the pi package manifest (extension, skill frontmatter, prompt template, `files`, peer deps) and
extension registration. The live verification (`PHOENIXGRAPH_LIVE=1 npm run verify`) builds the retail
example, reads it back through the catalog, runs a dashboard and an attribution, applies an edit, and runs
the risk ledger end to end; it asserts the numbers and the refusals.

The tests read the package's example files relative to `package.json`, not to the shell, so they pass from
the package directory and from the repository root.
