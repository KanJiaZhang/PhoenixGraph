# Verification

`verify-package.mjs` is the live check: it needs a running PhoenixGraph (`http://localhost:8081`) and the
`demo-data` / `MarketingDay` fact graph. The risk section needs neither (it reads the ledger).

```bash
PHOENIXGRAPH_LIVE=1 node verification/verify-package.mjs
```

What it asserts:

| Area | Checks |
|---|---|
| build | vertices/edges written; model read back through the Model catalog |
| model | metrics, dimensions, date binding, evidence, catalog |
| dashboard | executed tiles; revenue total is a number; trend traversal; the ratio tile is refused with SQL; HTML renders |
| attribution | 2024 → 2025; contributions sum to the delta; non-causal limit |
| edit | description and alias persist, audit records exist |
| risk | the ledger period and its KPIs; the deltas against the previous period; three breakdowns; every ring has evidence or states why not; the whole delta decomposed into ring shares; three ranked hypotheses with refutations; the descriptive/FuzzyLink limits; HTML + JSON written |

It does **not** delete the graph on success: the graph name is printed, and `.pida-schemas/<graph>.json` is
the durable schema copy. Remove that file and restart to retire it.

Environment overrides: `PHOENIXGRAPH_API`, `PI_DATA_AGENT_VERIFY_GRAPH`, `PI_DATA_AGENT_VERIFY_DATA_GRAPH`,
`PI_DATA_AGENT_VERIFY_VERTEX_TYPE`.

The unit tests (`npm test`) need no service: 52 of them, covering the readers and extractors, the model and its
bindings, schema mapping, the catalog reads, editing under validation, the dashboard and attribution arithmetic,
the risk ledger (CSV, manifest, aggregations, evidence, hypotheses, both renders), and the package itself - every
resource `package.json#pi` names exists, the skill has the frontmatter pi routes on, the prompt template carries a
description, and the `files` list would ship all of it.

The risk half of the live check reads `examples/risk-control/` and needs no payment graph: the KPIs and the
attribution come from the ledger, and a ring the graph cannot resolve is reported with its reason (and the run
still passes, because "the graph has no such accounts" is an answer).
