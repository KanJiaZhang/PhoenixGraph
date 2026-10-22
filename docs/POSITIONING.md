# Positioning

PhoenixGraph is an **auditable context layer for AI applications**. It turns documents and graph data
into a typed knowledge graph with provenance, searches it by vector, and returns the context an agent
needs: entities, relations, paths, the source sentences, and a count of what the answer rests on.

The graph engine (HTAP storage, Gremlin/Cypher, Gemini algorithms) is the foundation. It is not the
pitch.

## Who it is for

**Primary:** AI and knowledge teams building agents over documents they cannot send to a SaaS: financial
compliance, insurance claims, legal, healthcare, public sector, industrial after-sales. Their requirement
is not "a graph database"; it is "an answer I can defend, with the sentence it came from".

**Secondary:** data teams that want a graph-native semantic layer: metrics, dimensions, lineage and
attribution in one model, with questions that are refused when the model cannot answer them.

## The problem

- A vector index returns chunks. It does not tell an agent which entities those chunks are about, how
  they relate, or whether the retrieved text actually supports the claim.
- A general graph database stores relations, but leaves ingestion, entity typing, provenance, retrieval
  quality and agent tooling to the application.
- An LLM writes a confident answer whether or not the evidence exists. Regulated teams need the opposite:
  citations, coverage, and a refusal when the graph has nothing.

## The wedge

One pipeline, one answer shape:

```
documents ──▶ entities + relations + vectors ──▶ graph with provenance
                                                        │
agent ◀── MCP / HTTP tools ◀── context: entities, relations, paths, citations, coverage
```

The differentiators are deliberately unglamorous:

1. **Evidence.** Every relation carries the chunk and sentence it was read from. Every answer carries
   citations and a coverage count.
2. **Measured extraction.** `provenanceRate`, `typeAgreement`, precision/recall against a gold set, and
   coverage are computed, not asserted.
3. **Refusal.** The data agent refuses a question the semantic model cannot answer, and says why. The
   retrieval tools expose low coverage instead of inventing a relation.
4. **Deterministic.** No LLM in the data path. The same documents and dictionary produce the same graph,
   which is what makes an audit possible.
5. **Agent-native.** The tool catalog is served by the API; the MCP server reads it. Tool definitions
   cannot drift from the service.

## What it is not

- Not a general-purpose graph database first. It can store and query graphs, but it will not win a
  feature-by-feature comparison with Neo4j, TigerGraph or Kùzu.
- Not an agent framework. It does not plan, prompt or orchestrate. It is the context layer an agent calls.
- Not an LLM. The embedder is a deterministic hashing baseline; `Embedding.Function` is the seam for a
  real model.
- Not a vector database. Vector search is one step in a traversal, not the product.
- Not finished. 1.0 is a developer release; the production checklist is below.

## Positioning statement

**English:** PhoenixGraph is the auditable context layer for AI agents. Ingest your documents, get a
knowledge graph with citations, and let agents query it over MCP or HTTP. No LLM in the data path, no
data leaving your environment.

**中文：** PhoenixGraph 是给 AI Agent 的可审计上下文层。文档进图，答案带出处；Agent 通过 MCP 或 HTTP
查询，数据不出域，数据链路上没有大模型。

## How to talk about it

| Say | Do not say |
|---|---|
| "Answers with citations and coverage" | "AI-powered knowledge graph platform" |
| "Refuses when the model cannot answer" | "Answers any question" |
| "A context layer your agent calls" | "An all-in-one agent platform" |
| "Runs on your infrastructure" | "Cloud-native, fully managed" |
| "1.0 developer release" | "Production-ready at any scale" |

## Proof points in this repository

- `agent-demo`: two documents → 17 entities, 19 relations, every relation backed by its sentence.
- `examples/three-minute-demo`: one command that ingests, answers with citations, and shows a question
  the corpus cannot support.
- `clients/python`: a zero-dependency client for the same API, with tests that need no service;
  `.github/workflows/publish-python.yml` builds and publishes the wheel.
- The UI's document icon: upload Markdown/text/HTML, choose `sanguo` or `incident`, see the quality report
  and select the new graph; PDF stays a CLI path.
- `examples/business-demo`: an English incident corpus ingested with the `incident` rule pack, no Java change.
- `POST /api/v1/agent/search`: hybrid name + lexical + vector recall; `provider=http` switches the
  embedder to an OpenAI-compatible endpoint.
- `tools/ingest-confluence.mjs` / `tools/ingest-notion.mjs` / `tools/ingest-s3.mjs`: the same pipeline over a
  Confluence space, a Notion integration or a signed S3 prefix.
- `sanguo-kg-text`: 6,594 entities / 45,081 relations with chapter and sentence provenance.
- `tpc-h-analytics` + `tpc-h-data`: metrics, lineage, join paths and attribution as a graph; refusals for
  dimensions the model does not allow.
- `verification/`: 24 live checks that drive the real API and UI, including the numbers quoted in the docs.

## Production checklist

The current release is honest about what is missing. Before calling a deployment production-ready:

- [x] a fail-closed `secure` profile (API key required) with the UI forwarding the key;
- [x] an append-only JSONL audit trail that survives a restart and rotates;
- [x] tenant-scoped keys and optional per-tenant concurrency/rate limits;
- [x] a TLS reverse-proxy overlay (`docker-compose.tls.yml`, Caddy) or in-process `server.ssl.*`;
- [x] a multi-seed storage list and a load-balanced address;
- [x] a Helm chart with the secure profile, PVC, probes and the UI key;
- [x] a Terraform module and scheduled backups with retention;
- [ ] point-in-time recovery (only snapshots);
- [ ] automatic promotion/failover (no leader election);
- [ ] a measured benchmark above 10⁴ vertices (the harness and plan are in `docs/BENCHMARK.md`);
- [x] Python and TypeScript SDKs and a generated OpenAPI spec (paths exact, fields generic);
- [ ] full field-level OpenAPI schemas;
- [ ] a documented backup/restore drill on the target deployment.

Until then, position it as a **developer release** and run it behind your own gateway and network
controls.

## Related documents

- [AI-TOOLKIT.md](AI-TOOLKIT.md) — ingestion, quality metrics, tools, MCP.
- [USE-CASES.md](USE-CASES.md) — knowledge context and data-agent walkthroughs.
- [PRODUCT-PLAN.md](PRODUCT-PLAN.md) — the batched plan that follows this positioning.
- [DATA-QUALITY.md](DATA-QUALITY.md) — the numbers not to trust.
