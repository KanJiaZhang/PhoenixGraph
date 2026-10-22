# PhoenixGraph

PhoenixGraph is an auditable context layer for AI applications. It turns documents and graph data into a
typed knowledge graph with provenance, searches it by vector, and returns the context an agent needs:
entities, relations, paths, the source sentences and a count of what the answer rests on. Agents call it
over HTTP or MCP. There is no language model inside the data path.

The graph engine underneath is a real database: a graph's schema chooses Aerospike for keyed transactional
reads and writes or RocksDB for ordered scans and analytics, and Gremlin, openCypher and the HTTP API are
the same on both. See [Positioning](docs/POSITIONING.md) for who this is for and what it is not.

## What it does

**Context an agent can cite.** `POST /api/v1/agent/context` takes a question and returns the entities,
relations, paths and the source sentences, plus a count of what the answer rests on. The bundled 三国
graph answers "关羽 and 诸葛亮: how are they related?" from its own edges.
[Use cases →](docs/USE-CASES.md#1-a-knowledge-graph-as-agent-context)

**Graph and vector search in one traversal.** `nearest(k)` is an ordinary step, so a query can go from
similar accounts to their payments without a client-side join. Geo search is available the same way when
a graph carries coordinates. [Query guide →](docs/QUERY.md)

**HTAP without a second system.** Keyed reads touch one record; analytical questions scan ordered tables;
PageRank, Louvain, connected components and four centralities run in-process or on a Gemini cluster. The
backend is a property of the schema, not of the application code. [Design →](docs/DESIGN.md) ·
[Algorithms →](docs/ALGORITHMS.md)

**Latency on the demo graph.** A one-hop query runs at p50 0.54–3.57 ms (median 0.9 ms), two hops at
1.22–5.16 ms (median 2.3 ms), measured by `AerospikeHopQueryLatencyTest`.

**A UI to see the answer.** The bundled `graph-web` has a Gremlin query editor (openCypher is accepted by
the API), a canvas that draws the returned subgraph, algorithm colouring, tree and DAG layouts, and a
schema builder. [Web UI →](docs/WEB-UI.md)

## Quickstart

You need JDK 21+, Maven 3.9+ (the wrapper works) and Docker. The MCP server and the verification
scripts also need Node.js 18+; the Python client needs Python 3.9+, and the TypeScript client needs Node 18+ to build.

```bash
git clone https://github.com/KanJiaZhang/PhoenixGraph.git
cd PhoenixGraph
tools/start-phoenixgraph.sh
```

The script builds the project, starts Aerospike, the API on `8081` and the UI on `8080`, and waits until
both answer. Open <http://localhost:8080>. Use `--no-build` to skip the build and
`tools/stop-phoenixgraph.sh` to stop everything. `docker compose up --build` runs the same services in
containers; `docker compose down` stops them. On Windows, run the scripts from Git Bash and start Docker
Desktop first.

Try a graph walk, an algorithm and an agent call:

```bash
# the shortest proof: two documents -> a cited answer -> a question the graph cannot support
node examples/three-minute-demo/demo.mjs

# 1. walk five payments from account 135
curl -s -X POST localhost:8081/api/v1/query -H 'content-type: application/json' --data-binary @- <<'EOF'
{"graphName":"payment-graph","query":"g.V(135,'Account').bothE('Payment').otherV().limit(5)","includeResult":true,"returnSubgraph":true}
EOF

# 2. rank the reached accounts
curl -s -X POST localhost:8081/api/v1/query -H 'content-type: application/json' --data-binary @- <<'EOF'
{"graphName":"payment-graph","query":"g.V(135,'Account').bothE('Payment').otherV().pagerank()","includeResult":true}
EOF

# 3. what an agent would ask next
curl -s -X POST localhost:8081/api/v1/agent/context -H 'content-type: application/json' --data-binary @- <<'EOF'
{"graph":"agent-demo","question":"诸葛亮和司马懿是什么关系"}
EOF
```

The two `curl` query calls return the vertices and the `subgraph` block the UI draws. The agent call
returns entities, relations, citations and a `coverage` count. For ingestion, verification and more
examples, see [QUICKSTART.md](QUICKSTART.md).

## See it

![a vector search followed by a graph walk](docs/screenshots/graph-vector-query.png)

This is `g.V(1001,'Account').nearest(5).outE('Payment').otherV().dedup().limit(40)` on
`payment-graph-rocksdb-edge`: five vector-nearest accounts, then their payments, in one traversal. The
picture is drawn by `graph-web` from the query's `returnSubgraph`.

## Using it with an AI agent

Pick the path that matches what you already have:

| You have | Start with |
| --- | --- |
| Documents | Use the document icon in the UI, `POST /api/v1/agent/ingest`, or `node tools/ingest-docs.mjs <dir>` to build a graph with entities, relations, vectors and a quality report. Then call `search`, `context`, `related` or `path_between`. Scanned PDFs use `--ocr` (pdftoppm + tesseract). |
| A schema or a curated graph | Query it directly with Gremlin or openCypher, or use the semantic layer (`/api/v1/semantic/*`) to ask data questions with a plan and provenance. |
| An agent runtime | Connect `tools/mcp-server.mjs` over MCP, or register the 14 tools from `GET /api/v1/agent/tools`. |

The MCP server is stateless, reads its tool list from the running API, and forwards an API key when the
service is secured. It runs over stdio or HTTP (`--http`); the HTTP endpoint requires a bearer token
and can be limited to a tool allowlist:

```json
{
  "mcpServers": {
    "phoenixgraph": {
      "command": "node",
      "args": ["/path/to/PhoenixGraph/tools/mcp-server.mjs"],
      "env": {
        "PHOENIXGRAPH_API": "http://localhost:8081",
        "PHOENIXGRAPH_API_KEY": "<your-key>"
      }
    }
  }
}
```

`GET /api/v1/agent/tools` advertises eight retrieval tools (`find_entities`, `search`, `related`, `similar`,
`related_similar`, `similar_related`, `path_between`, `context`) and six data tools (`semantic_model`,
`semantic_translate`, `semantic_validate`, `ask_data`, `analyze_business`, `attribute_change`). The eight
retrieval tools share one answer shape (entities, relations, paths, citations, coverage); the six data
tools return a plan, provenance and either an answer or a refusal. Details and a worked example are in
[docs/AI-TOOLKIT.md](docs/AI-TOOLKIT.md); the data-agent contract is in
[docs/DATA-AGENT.md](docs/DATA-AGENT.md).

From Python, install the zero-dependency client with `pip install -e clients/python`, then:

```python
from phoenixgraph import PhoenixGraphClient

client = PhoenixGraphClient("http://localhost:8081")
answer = client.context("agent-demo", "诸葛亮和司马懿是什么关系")
print(answer["coverage"])
```

## Documentation

| Topic | Document |
| --- | --- |
| Positioning, audience, what it is not | [docs/POSITIONING.md](docs/POSITIONING.md) |
| Product plan and batches | [docs/PRODUCT-PLAN.md](docs/PRODUCT-PLAN.md) |
| Three-minute demo | [examples/three-minute-demo/README.md](examples/three-minute-demo/README.md) |
| Business incident demo | [examples/business-demo/README.md](examples/business-demo/README.md) |
| TypeScript client | [clients/typescript](clients/typescript) — build from source; not yet on npm |
| Confluence / Notion / S3 ingestion | [tools/ingest-confluence.mjs](tools/ingest-confluence.mjs) · [tools/ingest-notion.mjs](tools/ingest-notion.mjs) · [tools/ingest-s3.mjs](tools/ingest-s3.mjs) |
| Python client | [clients/python/README.md](clients/python/README.md) |
| Ten-minute walkthrough | [QUICKSTART.md](QUICKSTART.md) |
| Query language, example graphs, loading data | [docs/QUERY.md](docs/QUERY.md) |
| Storage, modules, distributed engine, monitoring | [docs/DESIGN.md](docs/DESIGN.md) |
| Algorithms and centralities | [docs/ALGORITHMS.md](docs/ALGORITHMS.md) |
| AI agent toolkit and MCP | [docs/AI-TOOLKIT.md](docs/AI-TOOLKIT.md) |
| Knowledge graph context and data-agent walkthrough | [docs/USE-CASES.md](docs/USE-CASES.md) |
| Worked analyses and refusals | [docs/AGENT-CASES.md](docs/AGENT-CASES.md) · [docs/DATA-AGENT-CASES.md](docs/DATA-AGENT-CASES.md) |
| HTTP API, keys, limits, errors | [docs/API.md](docs/API.md) |
| OpenAPI spec | [docs/OPENAPI.md](docs/OPENAPI.md) · [docs/openapi.json](docs/openapi.json) |
| Backups, HA, upgrades, failure modes | [docs/OPERATIONS.md](docs/OPERATIONS.md) |
| Numbers not to trust | [docs/DATA-QUALITY.md](docs/DATA-QUALITY.md) |
| SLOs and capacity | [docs/SLO.md](docs/SLO.md) · [docs/CAPACITY.md](docs/CAPACITY.md) |
| Benchmark harness and 10⁷ plan | [docs/BENCHMARK.md](docs/BENCHMARK.md) |
| TLS reverse proxy | [deploy/tls/README.md](deploy/tls/README.md) |
| Helm chart | [deploy/helm/phoenixgraph/README.md](deploy/helm/phoenixgraph/README.md) |
| Terraform | [deploy/terraform/README.md](deploy/terraform/README.md) |
| Visualisation options | [docs/WEB-UI.md](docs/WEB-UI.md) · [docs/GRAPH-VISUALIZATION.md](docs/GRAPH-VISUALIZATION.md) |
| 三国 graph and extraction | [SANGUO-KG.md](SANGUO-KG.md) · [SANGUO-KG-TEXT-EXTRACTION.md](SANGUO-KG-TEXT-EXTRACTION.md) · [SANGUO-KG-QUERIES.md](SANGUO-KG-QUERIES.md) |
| TPC-H semantic layer | [TPC-H-ANALYTICS.md](TPC-H-ANALYTICS.md) |
| Release notes and roadmap | [docs/RELEASE-1.0.1.md](docs/RELEASE-1.0.1.md) · [docs/ROADMAP-1.0.md](docs/ROADMAP-1.0.md) |

## Build and test

```bash
./mvnw clean package                     # build everything
./mvnw test                              # 1,100+ tests; store-backed ones skip if Aerospike is down
tools/start-phoenixgraph.sh --no-build   # start API and UI
node verification/run.mjs                # live checks against the running service
node --test tools/connectors/*.test.mjs  # Confluence/Notion connectors (mock servers)
pip install -e clients/python             # once, for the Python client
python -m unittest discover -s clients/python/tests -v   # the Python client, no service needed
(cd clients/typescript && npm ci && npm run build && npm test)   # the TypeScript client
```

`verification/run.mjs --list` shows what each check needs: a running service, Docker, or a Chromium-based
browser. The checks drive the real HTTP API and the real UI, including the numbers quoted in these
documents. The MCP server has its own check: `node tools/mcp-server.mjs --selftest`.

Release tags publish `ghcr.io/<owner>/phoenixgraph-http` and `ghcr.io/<owner>/phoenixgraph-web` through
`.github/workflows/publish-images.yml`. Until a tag is published, build locally with
`docker compose up --build`.

For a fail-closed local stack (API key required, audit on disk, UI key forwarded):

```bash
cp deploy/secure/security.example.yml deploy/secure/security.yml
export PHOENIXGRAPH_DASHBOARD_TOKEN="$(openssl rand -hex 24)"
docker compose -f docker-compose.yml -f docker-compose.secure.yml up --build
```

See [docs/OPERATIONS.md §5b](docs/OPERATIONS.md#5b-running-with-authentication-and-a-durable-audit) for
tenants, TLS and the audit file.

## Status

The current release is [v1.0.1](https://github.com/KanJiaZhang/PhoenixGraph/releases/tag/v1.0.1), a
developer release. It is usable and documented, but not finished. The known limits:

- **Authentication is off in the demo profile.** `SPRING_PROFILES_ACTIVE=secure` requires an API key and
  writes the audit to an append-only file; TLS is fronted or configured with `server.ssl.*`. See
  [docs/OPERATIONS.md](docs/OPERATIONS.md#5b-running-with-authentication-and-a-durable-audit).
- **Failover is not automatic.** The store accepts a seed list and a running engine survives losing its seed,
  but there is no leader election or automatic promotion.
- **Demo-scale data.** The examples are 10²–10⁴ vertices. A 10⁶-vertex load was measured (vertices load in ~1 min;
  the CSV edge loader is the ceiling), but there is no published 10⁷-vertex benchmark.
- **A subset of Gremlin and openCypher.** Unsupported syntax is rejected, never silently ignored.
- **RocksDB graphs are loaded, not written over REST.** Bulk loaders fill the ordered tables.

The full list, with the evidence for each item, is in [docs/RELEASE-1.0.0.md](docs/RELEASE-1.0.0.md);
what changed in the current release is in [docs/RELEASE-1.0.1.md](docs/RELEASE-1.0.1.md); what is still
planned is in [docs/ROADMAP-1.0.md](docs/ROADMAP-1.0.md).

## License

Apache License 2.0. See [LICENSE](LICENSE).
