# PhoenixGraph 1.1 — release notes

**Tag:** `v1.1.0` · **Image:** `phoenixgraph-http:1.1.0` / `phoenixgraph-web:1.1.0` · **Jar:** `dist/phoenixgraph.jar` (+ `.sha256`)
**From:** `v1.0.1` · **Branch:** `develop` (a minor release; see [`RELEASE-PROCESS.md`](RELEASE-PROCESS.md))

A minor release with one argument behind it: **the graph is the context an agent cites.** 1.0 shipped the
pipeline; 1.1 makes the case that the pipeline is worth using - three questions, answered against a running
service, with the field each number came from - and turns the agent surface (MCP over HTTP, a Python and a
TypeScript client, a generated OpenAPI spec) into something you can wire into a runtime.

`/api/v1` is unchanged: everything here is additive. One storage step is required - see
[Upgrading](#upgrading-from-101).

## The three questions this release is built around

Each row is a live response, and each number is asserted by `node verification/verify-ai-context.mjs`:

| Question | What comes back | Without it |
|---|---|---|
| 关羽 and 诸葛亮: how are they related? | 14 entities, **16 relations**, 4 citations; each relation carries the sentence it was read from and its `cite` | two chunks with no edges between them |
| What did Deploy 42 cause? Who approved it? | 3 documents become one graph; `payment service` merged from three spellings (`mentions: 3`, `duplicatesRemoved: 2`); two `caused` edges; the approval question returns **0 name matches, 0 relations** | three indexes, one per document; a confident guess for the approval |
| Why did spend change? Is ROI an efficiency problem? | `14,754,787.14 → 14,731,149.38`, `delta −23,637.76`, four slices that sum to it (`decompositionAddsUp: true`); CAC is refused with the warehouse SQL | a hand-written SQL nobody can check; a ratio answered as a sum |

The response excerpts, the field path behind every figure and the assertion that pins it are in
[`AI-CONTEXT-ONE-PAGER.md`](AI-CONTEXT-ONE-PAGER.md); the walkthroughs are
[`AI-CONTEXT-CASES.md`](AI-CONTEXT-CASES.md), [`AGENT-INGESTION-CASE.md`](AGENT-INGESTION-CASE.md) and
[`END-TO-END.md`](END-TO-END.md). The README now leads with this, and with the two properties that make it
more than a demo: no model in the data path, and an answer shape that is a contract (`sentence`/`cite` on
every relation, `coverage` on every answer, no relation when the corpus has none).

## Ingestion got the parts that make an answer survive review

- **One entity, one node, across documents.** Names that differ only by case, width, whitespace or punctuation
  are merged when the document is ingested; 1.1 adds a curated, deterministic 繁→简 mapping, so 關羽 and 关羽
  are the same entity. The dictionary entry wins as canonical, the other spellings become aliases, and
  provenance still accepts a sentence that uses an alias.
- **Near-duplicates stay a review list, not a guess.** `mergeCandidates` names the pairs that differ by a
  prefix, one edit or shared tokens; `POST /api/v1/agent/merge` folds an approved one, rewriting its edges,
  merging its chunks and recording its name as an alias - in the graph and in the artifact.
- **Provenance accumulates.** Re-ingesting a document carries an entity's `chunkIds` over instead of
  overwriting them, so an entity read in two documents keeps both citations.
- **A merge no longer buries an entity.** A merge soft-deletes the dropped vertex; the batch upsert treated that
  marker as "key absent" and tried to create over it, so re-ingesting the document could not resurrect the
  entity. It now tells a genuinely absent key from a soft-deleted one.
- **The edge index bug is gone.** Ingestion wrote relations at schema indices 3..6 and mentions at 3 while the
  schema it creates declares 1..4 and 1, so a newly ingested graph could be extracted and created but not
  written. `AgentIngestServiceTest` now checks every edge property index against the schema draft.

## The agent surface

- **MCP over HTTP**, next to stdio: stateless (no session id), JSON by default, SSE when the client asks.
  Loopback unless a token is configured; `PHOENIXGRAPH_MCP_CLIENTS` gives per-token tool scopes,
  `PHOENIXGRAPH_MCP_AUDIT` writes one JSONL line per `tools/call`, `PHOENIXGRAPH_MCP_RATE_LIMIT` is a
  per-client minute limit. The catalog still comes from `GET /api/v1/agent/tools`, so a tool cannot exist in
  the plugin and not in the service.
- **Clients that are actually clients.** `clients/python` (`pip install -e clients/python`) and
  `clients/typescript` wrap query, ingestion, agent and semantic endpoints with typed methods, send the API
  key as a bearer token, and raise an error carrying the status, body and `X-Request-Id`. Their tests run
  against local mock servers, so they need no service.
- **A generated OpenAPI 3 spec** (`docs/openapi.json`) from the same controller scan as `docs/API.md`;
  `node tools/openapi.mjs --check` runs in CI.
- **Hybrid retrieval** (`POST /api/v1/agent/search`) ranks name, description terms and vectors in one list,
  exposed in the catalog, the MCP server and both clients.
- **Providers behind a seam**: `phoenixgraph.agent.embedding.provider=http` uses an OpenAI-compatible
  `/embeddings` endpoint and records the vector space in the artifact; `phoenixgraph.agent.reranker.provider=http`
  adds a Cohere/Jina-compatible reranker. A provider failure is an error, never a silent fallback to hashing.
- **A retrieval evaluation** with a hand-checked gold set (entity recall, relation recall, citation coverage)
  that fails the run when answers get worse: `node verification/verify-retrieval-eval.mjs`.

## The data side

- **A second semantic graph** (`demo-semantics` over the `demo-data` fact graph) proves the interpreter,
  runner and renderer are not TPC-H shaped: `spend` answers from the graph, `cac` is refused with the
  warehouse SQL, and the attribution decomposition adds up.
- **The volume a rate divides by is a metric.** `order_count` is distinct orders, so a line-item dimension like
  `ship_mode` may not slice it - a correct refusal. `line_count` (`COUNT(*)` from `lineitem`) is that volume,
  sliceable by `ship_mode`, so the fact-graph answer and the warehouse SQL now agree.

## Scale, measured

- **The CSV edge loader is 7x faster**: one batch get plus one batch operate per merge window instead of a
  get+operate per vertex - 100k edges from 5m20s to **~46 s** (~2,200 edges/s).
- **10⁶ vertices and 10⁶ edges, with the edges in place**: 200,000 vertices in 15 s, 1,000,000 edges in 422 s,
  0 conflicts, 0 failed; a 1,005-edge adjacency costs ~0.6 ms of p50 over a point read.
- **10⁷ is recorded with the ceiling it hits**: the stock dev container's namespace refuses writes at 3.0 GB
  (`Server memory error`, 70% used) because it has no disk storage engine. The loader is no longer the ceiling;
  it is a deployment setting, and [`BENCHMARK.md`](BENCHMARK.md) / [`CAPACITY.md`](CAPACITY.md) now say so.

## Deploying it

- **Helm chart** (`deploy/helm/phoenixgraph`) and **Terraform** (`deploy/terraform`) for the engine, the UI and
  the store; the Kubernetes manifests pin `phoenixgraph-http:1.1.0` / `phoenixgraph-web:1.1.0` rather than
  `latest`, and `tools/check-k8s.mjs` fails a manifest that drifts.
- **Images are published to GHCR** on a `v*` tag by `.github/workflows/publish-images.yml`, which is how
  `1.1.0` becomes pullable without a local build.
- **Security is a profile, not a rewrite** (`SPRING_PROFILES_ACTIVE=secure`): API keys with a role ladder and
  optional per-graph scope, a durable JSONL audit trail, request limits and TLS from a keystore.

## Upgrading from 1.0.1

1. **Rebuild Aerospike vector indexes once** - `POST /api/v1/vector-index/rebuild`. The HNSW metadata key now
   includes the graph name; two graphs used to share one metadata record, and the old record is ignored.
2. **Re-ingest nothing else.** The ingestion changes (dedup, chunk carry-over, the edge index fix) apply to new
   ingests; existing graphs keep answering. Re-ingesting a document is idempotent and will now accumulate
   provenance.
3. **Version anywhere you pin it**: the jar, the image tags and the Helm values all read `1.1.0`.

## Verifying this release

```bash
tools/start-phoenixgraph.sh --no-build
node verification/verify-ai-context.mjs      # the three questions above, against the running service
node verification/verify-agent-ingestion.mjs # a postmortem becomes a graph; /merge rewrites the edges
node verification/verify-e2e-case.mjs        # documents -> graph -> analysis -> a real rate
node tools/check-release.mjs                 # the repository agrees with itself about 1.1.0
node verification/run.mjs                    # everything this machine can run
```

`verify-ai-context.mjs` provisions the `agent-demo` graph from `docs/agent-demo` when its artifact is not
there yet, so the first case works on a fresh clone (it needs the Aerospike write path, like the others).

## Known limits

The list in the [README](../README.md#status) is the current one: extraction is a dictionary plus rule packs
rather than a general extractor, the default embedder is an offline hashing baseline, the demo data is
10²-10⁴ vertices, the demo profile has authentication off, and failover is manual. None of them changed in 1.1.
