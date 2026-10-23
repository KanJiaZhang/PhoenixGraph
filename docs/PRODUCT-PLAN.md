# Product plan: from graph engine to auditable context layer

This plan follows [POSITIONING.md](POSITIONING.md). The goal is not more engine features. The goal is a
product a stranger can try in three minutes, integrate in an afternoon, and trust with an answer.

The work is split into four batches. Each batch has one question it answers and a definition of done. A
later batch does not start until the earlier one is usable.

---

## Batch 1 — Can someone see the value in three minutes?

**Question:** does the product have a demo and an integration path that do not require reading 60 KB of
engineering notes?

**Deliverables (this change):**

| Item | Where | Done when |
|---|---|---|
| One-sentence positioning | `docs/POSITIONING.md`, README first screen | a reader can say what it is and who it is for |
| Python client, zero runtime dependencies | `clients/python/` | `pip install -e clients/python` then a query works |
| Three-minute demo | `examples/three-minute-demo/` | one command prints extraction quality, a cited answer, and a low-coverage case |
| MCP API-key passthrough | `tools/mcp-server.mjs` | a secured instance works with `PHOENIXGRAPH_API_KEY` |
| TypeScript client | `clients/typescript` | typed, zero runtime dependencies, tested against a mock API |
| MCP HTTP, SSE, scopes and audit | `tools/mcp-server.mjs --http` | loopback by default; JSON/SSE framing; global and per-token tool scopes; JSONL call audit; per-client minute limit |
| Publishable container images | `.github/workflows/publish-images.yml` | a `v*` tag builds and pushes both images to GHCR |
| SDK tests in CI | `.github/workflows/ci.yml` | the Python tests run on every push |

**Definition of done:** a new user starts the service, runs one command, and sees an answer with
citations. A Python developer can install the client and call `context()` in under ten lines.

**Not in this batch:** new retrieval algorithms, new connectors, enterprise auth, scale work.

---

## Batch 2 — Can it ingest a real corpus, not just the demo?

**Question:** does "bring your own documents" work for formats and domains outside the bundled corpus?

**Deliverables:**

1. **Domain rule packs as data.** Today the extraction rules are Java constants while the docs say "the
   rules are data". Move the relation words, window verbs and rule patterns into a JSON rule pack loaded
   by the agent service. Ship two packs: `sanguo` (the current rules) and `incident` (a business
   postmortem vocabulary: caused, fixed, depends_on, owns, deployed_on, reported_by).
2. **Document connectors.** At minimum: plain text, Markdown, HTML, PDF; a CLI to ingest a directory;
   Confluence/Notion/S3 connectors behind a connector interface.
3. **Retrieval quality.** Real embedding providers (OpenAI-compatible and BGE), hybrid BM25 + vector
   retrieval, a reranker hook, and entity resolution for aliases/duplicates.
4. **Evaluation harness.** A small gold set for the incident corpus and a script that reports citation
   faithfulness, answer coverage, refusal correctness and retrieval recall. It must fail loudly, not print
   a green summary.

**Definition of done:** a user can point the CLI at a folder of PDFs and Markdown, get a graph, and see a
retrieval-quality report. The business demo works without editing Java.

**Progress:** the rule pack (`sanguo`, `incident`), the document CLI (Markdown/text/HTML, PDF through
`pdftotext` or opt-in `pdftoppm` + `tesseract` OCR), an external rule directory, Confluence and Notion
connectors, alias mapping, conservative entity dedup, the hybrid `search` tool, an OpenAI-compatible
embedding provider (`phoenixgraph.agent.embedding.provider=http`), a
`Reranker` provider (Cohere/Jina-compatible `/rerank`), the retrieval evaluation and a document-ingestion
form in the UI are in place. Still open: an OCR quality evaluation and a cross-document entity-resolution
policy beyond the conservative case/whitespace/punctuation dedup; the quality report now lists
`mergeCandidates` for manual review. The default embedder remains the deterministic hashing baseline.

**Explicit non-goals:** OCR quality tuning, OCR in the browser, streaming ingestion, every SaaS connector.

---

## Batch 3 — Can an enterprise run it on Monday?

**Question:** are the defaults and the operational surface safe enough to put behind a real workload?

**Deliverables:**

1. **Secure by default.** Docker/compose ships with authentication and TLS on; the open dev profile is
   explicit (`SPRING_PROFILES_ACTIVE=dev`). MCP forwards the key and can be limited to a tool subset.
2. **Durable audit.** Move the audit ring into the graph's store, with retention and an export endpoint.
3. **Multi-tenancy.** Tenant-scoped keys, per-tenant quotas and graph allowlists. No shared admin token.
4. **Availability.** Multiple storage seeds, automatic failover, a documented load-balanced deployment.
5. **SLO credibility.** Fix the cumulative-p99 defect so the latency alert can clear. Add a benchmark
   harness and publish a 10⁷-vertex plan, even if the measured number is still smaller.
6. **Packaging.** Helm chart/operator, Terraform, PITR, upgrade and rollback drill.

**Definition of done:** an operator can deploy with auth/TLS on, rotate keys, restore a backup, and read a
durable audit trail without editing application code.

**Progress:** the `secure` profile, UI key forwarding, append-only audit file with retention, tenant-scoped
keys and optional per-tenant limits, the windowed-p99 fix, a multi-seed storage list, automatic failover
(verified live: `verify-aerospike-ha.mjs` kills each node - including the seed - and the running engine
keeps answering, then replication catches up when the node returns), a Caddy TLS overlay, the benchmark
harness/generator (the edge-load bottleneck is closed: 100k edges in ~46 s, about 7×, see BENCHMARK.md),
a Helm chart, a Terraform module, scheduled backups with retention and a generated OpenAPI spec are in
place. Still open: point-in-time recovery (only snapshots exist). The 10⁷ question is answered as far as
this hardware can answer it - 2·10⁶ vertices load in 105 s, 10⁶ edges load in 422 s (~2,370 edges/s) and
that graph's queries are measured; the 10⁷ attempt stops at the dev container's namespace memory (3.0 GB,
no disk storage engine) rather than at the loader, so a 10⁷ corpus is a deployment setting, and
[BENCHMARK.md](BENCHMARK.md) says exactly that.

---

## Batch 4 — Should the semantic layer become a product?

**Question:** is there a second act after the context layer?

**Only start if Batch 2 and 3 have design partners.** The semantic layer already demonstrates a real
differentiator: metrics, lineage, join paths, attribution and refusals. To productize it:

- connectors to Snowflake, BigQuery, Databricks and PostgreSQL;
- SQL execution as well as generation;
- dbt/Cube import and export;
- multi-fact joins, not only the single denormalised fact vertex;
- BI integration and a metric-governance workflow;
- more attribution methods and anomaly detection.

This is a different buyer (data teams) and a different go-to-market. Do not run it in parallel with the
context-layer wedge.

---

## What we stop doing

- Adding storage backends, graph algorithms or demo domains.
- Leading with HTAP, Gemini or four storage schemes in the first screen.
- Writing more evidence documents before the three-minute path works.
- Calling a release "production-ready" while the production checklist is open.

## Acceptance rule

A batch is done when the acceptance command in its table passes on a clean machine, not when the code
merges. If a batch cannot be demonstrated in one command, it is not done.
