# Live verification suite

These are the checks that answer the question *"is the thing that is running actually doing what the
documentation says?"*. They talk to the running services over HTTP (8081) and drive the real UI in a
headless Chromium browser over CDP (8080) - they are not unit tests of a class, they are assertions about
the product as it is served.

```bash
export JAVA_HOME="C:/Program Files/Java/jdk-26.0.2.1"     # or your JDK 21+
tools/start-phoenixgraph.sh --no-build                   # 8080 UI, 8081 API (Docker must be up)

node verification/run.mjs            # everything that can run on this machine
node verification/run.mjs --list     # what each check asserts, and what it needs
node verification/run.mjs --only 3d  # one check (substring of the file name)
node verification/run.mjs --no-browser
node verification/run.mjs --retry 0    # strict: no second try (what CI runs)
```

The runner prints one line per check with **how many assertions ran**, skips a check whose prerequisite is
missing (services down / no browser) instead of reporting a false failure, and exits non-zero if anything
failed. `PG_BROWSER=/path/to/msedge` overrides browser discovery.

A failing check is retried once and reported as **`FLKY` (flaky)** when the retry passes: these checks drive a
real browser against a real server, and on a loaded machine a wait inside one of them can expire before the
page settles. The retry is always visible - `FLKY` means "look at this check", never "this is fine" - and
`--retry 0` makes a run strict, which is what CI and the nightly job use.

## What each check covers

| Check | Needs | Asserts |
|---|---|---|
| `verify-agent-cases.mjs` | service | **every figure in [`docs/AGENT-CASES.md`](../docs/AGENT-CASES.md)** - both worked cases (the 三国 `killed` audit, the TPC-H three self-checks and the defensible analysis) |
| `verify-tpch-semantic-layer.mjs` | service | the semantic layer answering the eight questions of TPC-H-ANALYTICS.md §3 (definition, lineage, columns, dimensions, attribution, second-order attribution, join keys, reverse lineage) and the year figures the README quotes |
| `verify-input-contract.mjs` | service | the input contract: 78 assertions over malformed JSON, missing and wrongly-typed fields, ids and indices that do not exist, values that are not the declared type, values over the size limit, CJK, and the rules that hold across all of them (a client mistake is never a 500, no body carries a stack trace, every error body has the same shape) |
| `verify-failure-modes.mjs` | service + jar | a store that is not there and a store that accepts and then stalls: the instance starts anyway, answers 503 with `Retry-After` for that graph, keeps serving its RocksDB graphs, keeps answering metrics and `/health`, and refuses a 2 MiB body with 413 and a 108k-character query with 400 |
| `verify-alert-drill.mjs` | service + jar | the on-call drill: for each of the six alerts in deploy/prometheus/phoenixgraph-rules.yml, reproduce the condition and read the metric the alert reads (refusals, storage failures, latency, the scrape job) and assert the windowed-p99 fix: a graph with only restored history reports 0 (docs/DATA-QUALITY.md §12) |
| `verify-semantic-api.mjs` | service | the semantic layer over HTTP: the model endpoint, the refusals, and the centre of it - the SQL the API renders is compared **byte-for-byte** with `tools/semantic-sql.mjs` over a matrix of metrics, dimensions, grains and dialects |
| `verify-retrieval-eval.mjs` | service | the answer-level retrieval evaluation: entity recall, relation recall and citation coverage against a hand-checked gold set, with thresholds that fail the run |
| `verify-data-agent.mjs` | service | 智能问数 / 业务分析 / 业务归因 end to end: the answer, its plan, its provenance, the two modes (auto and the signed-token confirmation), and the refusals - asserted against baselines this repository verified by hand (1996 revenue 98,879,432.89, the 1995->1996 change 3,266,638.84) |
| `verify-demo-semantics.mjs` | service | the marketing demo of the semantic layer: a second semantic graph (demo-semantics) over a second fact graph (demo-data), proving the runner/interpreter/renderer are not hardcoded to TPC-H - a base metric (spend) answers from the graph, a ratio metric (cac) is refused with the warehouse SQL, and the attribution decomposition adds up (deterministic baselines) |
| `verify-aerospike-ha.mjs` | service + docker | a two-node Aerospike cluster: kill a node and the answers survive (with the failover time), writes continue while it is down, replication catches up when it returns, and a running engine survives losing the node it was configured with |
| `verify-multi-instance.mjs` | service + docker | two engines over one store: idempotent writes, a stale write refused as `SUCCESS_STALE`, concurrent creates from both instances agreeing, and one engine vs two measured interleaved |
| `verify-schema-version.mjs` | service | the schema format version: stamped and unstamped files load, a future version is refused by name, and a service keeps serving while it refuses one file |
| `verify-slo.mjs` | service | the SLOs of docs/SLO.md measured end to end (four query classes, p50/p95/p99 against their objectives), plus: every metric an alert names exists in the live exposition, every rule threshold is stated in the document, and the failure counters are exported while still zero |
| `verify-tls.mjs` | service | TLS from a real keystore: HTTPS serves the API, the certificate is the generated one, a verifying client still refuses it (verification is on), plain HTTP on that port never serves the API, and a wrong keystore password stops the process |
| `verify-security.mjs` | service | API keys: 401 without one (with the Bearer challenge), 403 for the wrong role or the wrong graph, 200 for what the key may do, a hashed key accepted, a durable audit file with the tenant, the trail naming the keys and never the tokens, and the MCP plugin reaching the secured instance with `PHOENIXGRAPH_API_KEY` |
| `verify-request-limits.mjs` | service | the M2 hardening: `X-Request-Id`, the 404/405 contract, the Prometheus exposition, and - with a second engine instance started on tight limits - `429`+`Retry-After` for the per-second budget and `503` for a query that exceeds `request-timeout` |
| `verify-tree-layout.mjs` | browser | the drawing read as a hierarchy: four orientations, click-to-re-root, back to force layout |
| `verify-dag-layout.mjs` | browser | the tree and the layered DAG keep **every** edge and keep them attached (no NaN paths), right-to-left modes, the layout menu in both languages |
| `verify-3d-panel.mjs` | browser | the 3D view inside the panel: toggle, covers the canvas, draws the same vertices/edges with the same per-type colours, follows filters/search/selection and a new query, mutually exclusive with the map |
| `verify-result-panel.mjs` | browser | clipped values, the expandable value window, the rows tab opening itself for a non-graph answer |
| `verify-tabs-visibility.mjs` | browser | exactly one panel is painted per tab (the `[hidden]` rule) |
| `verify-sanguo-map.mjs` | browser | the map view inside the panel: the icon's gate (coordinates + scannable store), the note, the way back |
| `verify-geo-query-map.mjs` | browser | a query that reads coordinates puts *its* points on the map, and the scope switch works |
| `verify-gcj-coordinates.cjs` | nothing | the offline WGS84 → GCJ-02 conversion the 高德 fallback applies |
| `capture-sanguo-map.mjs` | browser | not a check: rebuilds `docs/screenshots/sanguo-map.png` |
| `capture-readme-screenshots.mjs` | browser | not a check: rebuilds the two README screenshots (`graph-vector-query.png`, `knowledge-graph-context.png`) |

## Adding a check

A check is a plain Node program (no test framework, no dependency) with two marker comments and an
`ok`/`FAIL` line per assertion:

```js
// needs: browser          (or: service, none)
// what: one line the runner prints in --list
...
check('what this assertion means', condition, 'the values when it failed');
...
console.log(failures ? `${failures} FAILURES` : 'one line saying what was verified');
process.exit(failures ? 1 : 0);
```

Two rules worth keeping:

- **assert values, not activity.** "Something was drawn" is not a check; "the 3D fills are the same set of
  colours the 2D canvas computes" is. Most of the bugs these files found were invisible to a check that
  only looked at whether a query ran.
- **read the value back from the thing the user sees** (the DOM, the computed style, the canvas pixels,
  the API response), not from an intermediate object the test itself constructed.

The historical one-off probes and benchmarks from the working sessions (the `.logs/` directory, gitignored)
are deliberately **not** published here: they were written to answer one question once, and a suite that
passes by remembering yesterday's answers is worse than a small one that asserts today's behaviour.

The MCP plugin has its own check, because it is a tool rather than a live suite entry: `node tools/mcp-server.mjs --selftest` (catalog, bindings, JSON Schemas and three calls), and `node tools/mcp-server.mjs --list` prints what it would advertise.

The answer-level ledger is separate from the smoke checks because it answers a different question - not "does the endpoint work" but "is the answer right": `node verification/eval-data-agent.mjs` runs five categories of question (correct, must refuse, underspecified, unknown term, a year the data does not cover) and prints the two it currently fails as `GAP` lines rather than passing silently.
