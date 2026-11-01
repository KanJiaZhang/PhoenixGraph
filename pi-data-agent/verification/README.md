# Verification

`verify-package.mjs` is the live check: it needs a running PhoenixGraph (`http://localhost:8081`) and the
`demo-data` / `MarketingDay` fact graph.

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

It does **not** delete the graph on success: the graph name is printed, and `.pida-schemas/<graph>.json` is
the durable schema copy. Remove that file and restart to retire it.

Environment overrides: `PHOENIXGRAPH_API`, `PI_DATA_AGENT_VERIFY_GRAPH`, `PI_DATA_AGENT_VERIFY_DATA_GRAPH`,
`PI_DATA_AGENT_VERIFY_VERTEX_TYPE`.

The unit tests (`npm test`) need no service.
