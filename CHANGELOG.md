# Changelog

All notable changes to this project are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims at
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) from 1.0 on.

## Version policy (from 1.0)

- **`/api/v1` is the contract.** Within `v1`, endpoints may be *added*; an existing endpoint's request or
  response shape changes only in a way that keeps old clients working (a new optional field, a new value in
  an enum). Anything else is `/api/v2`.
- **Storage schemes are independently versioned** by the schema files a graph was written with. A store
  written by an older build is read by a newer one; the reverse is not promised.
- **Errors are part of the contract**: the status codes and the error body documented in
  [`docs/API.md`](docs/API.md) do not change within `v1` (widening a code from `500` to the specific
  `4xx` it always should have been - see 0.2.0 below - is treated as a fix, not a break).
- **`0.x` makes no compatibility promise**, but every release states what changed.
- **A release is a git tag and an image tag with the same name**; `docs/ROADMAP-1.0.md` says what is not in
  yet. Rollback is "deploy the previous tag" and is exercised in the M2 milestone.

## [Unreleased]

### Added

- **Faster CSV edge loads**: `AEROSPIKE_BATCH_SIZE` now controls the merge window, and
  `AerospikeEdgeStore.upsertEdges` writes different vertices in parallel while keeping one vertex's bins
  sequential (no generation self-race); a vertex's bins are read and written once. A later change replaced
  the per-vertex get+operate with one batch get + one batch operate per window (the merge/evict/stale logic
  unchanged, extracted into `computeMergedBins`): 100k edges dropped from 5m20s to **~46 s** (~7×, about
  2,200 edges/s), so 10^7 edges is now an hour's load rather than a day's. A 10^6-vertex/10^6-edge graph was
then built and queried with its edges in place (1M edges in 422 s, 0 failed; a 1,005-edge adjacency costs
~0.6 ms over a point read), and the 10^7 attempt is recorded with the ceiling it actually hits: the stock
dev container's namespace refuses writes at 3.0 GB (`Server memory error`, 70% used / 6% available) because
it has no disk storage engine - a deployment setting, not a loader limit. `docs/BENCHMARK.md` and
`docs/CAPACITY.md` carry both.
- **A Python client** (`clients/python`, `phoenixgraph-client`): zero runtime dependencies, wraps the query,
  ingestion, agent and semantic endpoints, sends an API key as `Authorization: Bearer`, and raises
  `PhoenixGraphError` with the status, body and `X-Request-Id`. Ten unit tests use a local HTTP server, so
  they need no service.
- **A TypeScript client** (`clients/typescript`, `@phoenixgraph/client`): zero runtime
  dependencies, typed methods for query/ingestion/agent/semantic endpoints, a `request` escape hatch, and
  `PhoenixGraphError` with status/body/request id. Its tests use a local mock HTTP server; CI builds it
  with `npm ci && npm run build && npm test`.
- **A generated OpenAPI 3 spec** (`docs/openapi.json`): exact paths, methods, operation ids and
  path parameters from the same controller scan as `docs/API.md`; request/response fields stay generic
  until DTOs carry annotations. `node tools/openapi.mjs --check` runs in CI.
- **Automatic entity dedup**: names that differ only by case, width, whitespace or punctuation are
  merged; a dictionary entry wins as canonical, the other spellings become aliases, and provenance accepts
  an alias in the relation's sentence. Gold triples are canonicalised through the same aliases before the
  precision/recall comparison. `dedupe=false` / `--no-dedupe` keeps variants separate, and the quality
  report carries `duplicatesRemoved`, a `mergedEntities` review list (canonical, aliases, mentions), and
  `mergeCandidates` for near-duplicates that were deliberately left separate (prefix, one edit, shared tokens).
  `POST /api/v1/agent/merge` is the reviewer's answer to one of those candidates: it folds a `drop` entity
  into a `keep` entity, rewrites its edges, merges its chunks and records its name as an alias, in the graph
  and in the artifact.
- **Opt-in PDF OCR** (`tools/ingest-docs.mjs --ocr`): when `pdftotext` has no text, the CLI
  renders pages with `pdftoppm` and reads them with `tesseract -l <lang>`. The command runner is
  injectable, so the fallback is tested without either binary; missing binaries are reported, not guessed.
- **An S3 connector** (`tools/ingest-s3.mjs`): SigV4-signed ListObjectsV2/GetObject without an
  AWS SDK, reading Markdown/text/HTML objects; PDF objects are reported as skipped. It reuses the
  same CLI/ingestion contract as the Confluence and Notion connectors.
- **PyPI publishing**: `.github/workflows/publish-python.yml` builds the sdist/wheel and publishes on a
  `v*` tag (or manual dispatch); the CI Python job builds the wheel on every push.
- **Document ingestion in the UI**: the toolbar's document icon opens a form for Markdown/text/HTML files,
  a graph name, a rule pack and optional dictionary/alias JSON; `graph-web` proxies the request to
  `POST /api/v1/agent/ingest` with the API key. PDF remains a CLI path.
- **A three-minute demo** (`examples/three-minute-demo`, `tools/demo-3min.sh`): ingests two documents,
  prints the extraction quality, answers a question with citations and coverage, shows what the graph knows
  about a question it cannot answer, and demonstrates a data-agent refusal.
- **Extraction rule packs are data** (`RulePack`, `agent-rules/incident.json`): the default 三国 vocabulary
  is unchanged, and a business pack reads `caused`, `fixed`, `depends_on`, `owns`, `assigned_to` and the
  other relations of an incident corpus. `POST /api/v1/agent/ingest` takes `rulePack`.
- **A document ingestion CLI** (`tools/ingest-docs.mjs`) for Markdown, text and HTML, with PDF through
  `pdftotext` when installed. It defaults to a dry run, supports a dictionary, aliases and a gold set, and
  sends an API key. `examples/business-demo` is a two-document incident corpus that uses the new pack.
- **An external rule directory** (`phoenixgraph.agent.rules-dir` / `RulePackRegistry`): a `<name>.json`
  pack in that directory wins over the classpath and needs no rebuild. Names are restricted to
  `[a-z0-9_-]+`.
- **Confluence and Notion connectors** (`tools/ingest-confluence.mjs`, `tools/ingest-notion.mjs`) over the
  same ingestion API, with pagination, Basic/Bearer auth for Confluence and block-text extraction for
  Notion. Their tests use local mock servers.
- **A reranker hook** (`Reranker`, default `NoopReranker`): a cross-encoder or hosted rerank API can replace
  the bean without changing the tools.
- **Alias mapping** in the local CLI and both connectors (`--aliases`), so a variant name is canonicalised
  before extraction.
- **Hybrid retrieval** (`POST /api/v1/agent/search`): name, description-term and vector scores in one
  ranking, exposed as the 14th tool in the catalog, the MCP server and the Python client.
- **A retrieval evaluation** (`verification/verify-retrieval-eval.mjs`): a hand-checked gold set for entity
  recall, relation recall and citation coverage; it fails when the answers get worse.
- **An OpenAI-compatible embedding provider** (`HttpEmbedding`, `EmbeddingFactory`): set
  `phoenixgraph.agent.embedding.provider=http` and the ingestion uses `POST <base-url>/embeddings`.
  The artifact records `http:<model>:<dimensions>`, so query time rebuilds the same vector space; a provider
  failure is an error, not a silent hashing fallback. The default remains the offline hashing baseline.
- **A reranker provider** (`HttpReranker`, `RerankerFactory`): set
  `phoenixgraph.agent.reranker.provider=http` for a Cohere/Jina-compatible `POST <base-url>/rerank`; the
  default keeps the hybrid order and a provider failure falls back to it.
- **A fail-closed secure profile** (`application-secure.yml`, `docker-compose.secure.yml`): authentication is
  required, `graph-web` forwards the API key, and the default demo profile stays open (with a loud startup
  warning).
- **A durable audit trail**: `phoenixgraph.audit.file` appends JSONL, reloads it on restart, rotates at
  `max-bytes` and keeps `keep-files` files; `/api/v1/audit/recent` reports `durable`, the file and each
  entry's tenant.
- **Tenant-scoped keys and quotas**: a key may set `tenant`; `phoenixgraph.limits.per-tenant-concurrent` and
  `per-tenant-per-second` bound a tenant's share, and the tenant is in the audit entry.
- **Multi-seed Aerospike discovery**: `phoenixgraph.storage.seeds` accepts a list of `host:port` entries; a
  restart can bootstrap through another node, while a single `host`/`port` remains the fallback.
- **A TLS overlay**: `docker-compose.tls.yml` and `deploy/tls/Caddyfile` front the secure stack with Caddy,
  forwarding the API key to `graph-http` and proxying the UI.
- **A benchmark harness and 10⁷ plan**: `tools/bench-queries.mjs` measures point/1-hop/2-hop or TPC-H
  aggregates end to end; `tools/generate-scale-csv.mjs` streams a payment dataset; `tools/bench-load.sh`
  loads it. `docs/BENCHMARK.md` states the method, the measured demo numbers and the unmeasured 10⁷ plan.
- **A Helm chart** (`deploy/helm/phoenixgraph`): secure profile, PVC, audit path, probes and a UI key from a
  Secret; `helm lint` and `helm template` pass.
- **A Terraform module** (`deploy/terraform`): installs the chart into an existing namespace, with pinned
  provider versions and `terraform fmt`/`validate` passing.
- **Scheduled backups with retention** (`tools/backup-all.sh`): backs up every served graph and keeps the
  newest N snapshots per graph. This is snapshot + retention, not point-in-time recovery; PITR remains open.
- **MCP over HTTP** (`tools/mcp-server.mjs --http`): a stateless Streamable HTTP endpoint at `/mcp`
  with JSON responses (or SSE framing when the client sends `Accept: text/event-stream`). It binds loopback
  by default, refuses a non-loopback bind without auth, and supports global `PHOENIXGRAPH_MCP_TOOLS` /
  `PHOENIXGRAPH_MCP_DENY` plus per-token scopes in `PHOENIXGRAPH_MCP_CLIENTS`. `PHOENIXGRAPH_MCP_AUDIT`
  writes one JSONL line per `tools/call`, and `PHOENIXGRAPH_MCP_RATE_LIMIT` is a per-client minute limit.
  A tool whose binding has no `{graph}` path placeholder now receives `graph` in the request body; before,
  `find_entities` and the other non-templated tools lost it. Mock-API HTTP tests are wired into CI.
- **MCP API-key passthrough**: `tools/mcp-server.mjs` reads `PHOENIXGRAPH_API_KEY` and sends it as a Bearer
  token, so the plugin works against a secured instance. `verify-security.mjs` now asserts it.
- **GHCR publishing**: `.github/workflows/publish-images.yml` builds and pushes `phoenixgraph-http` and
  `phoenixgraph-web` to GHCR on a `v*` tag.
- **Positioning and product plan**: `docs/POSITIONING.md` states the auditable-context-layer wedge and the
  production checklist; `docs/PRODUCT-PLAN.md` splits the work into four batches with acceptance criteria.
- **A second semantic graph, the marketing demo** (`demo-semantics` + `demo-data`): spend, impressions, clicks,
  revenue, GMV, new customers and orders answer from the fact graph; the ratio metrics (CAC, ROI, CTR) bind
  nowhere and are refused with the warehouse SQL, never guessed. `verification/verify-demo-semantics.mjs` pins
  the deterministic baselines and proves the runner/interpreter/renderer are not hardcoded to TPC-H.

### Fixed

- **Re-ingesting a document after a merge could not resurrect the folded-away entity.** A merge soft-deletes
  the dropped vertex (a marker, not a removal), and the batch vertex upsert treated that marker as "key
  absent" and tried to create over an existing key, so the create collided and the entity stayed gone. The
  batch upsert now distinguishes a genuinely absent key from a soft-deleted one: a soft-deleted vertex takes
  the update path, whose new lastUpdatedTime clears the marker. `AerospikeVertexStoreTest` pins the two
  predicates (`isMissing` for reads, `isAbsent` for writes).
- **The "volume or rate?" follow-up could not be answered.** The answer-level ledger's sixth category asked
  whether a count metric sliced by the same dimension as an event metric returns numbers, and it came back
  refused: `metric/order_count` is distinct orders (`COUNT(DISTINCT o_orderkey)` from `orders`), so a line-item
  dimension like `ship_mode` may not slice it - a correct refusal, not the parse failure the ledger first
  recorded. `metric/line_count` (订单行数) is that volume: `COUNT(*)` from `lineitem`, bound to `count`, sliceable
  by `ship_mode`, so the fact-graph answer and the warehouse SQL agree and the rate's denominator exists.
  `verification/eval-data-agent.mjs` category 6 now asserts the rows and their sum instead of recording a gap.
- **Agent ingestion used the wrong edge property indices.** It wrote relations at schema indices 3..6 and
  mentions at 3, while the schema it creates declares 1..4 and 1. A newly ingested graph could be extracted
  and created but not written. The three-minute demo caught it; `AgentIngestServiceTest` now checks every
  edge property index against the schema draft.
- **Two Aerospike graphs shared one HNSW metadata record.** The metadata key was `hnsw:<vertex>` (and
  `hnsw:<vertex>:<property>`), so two graphs with an `Entity`/`embedding` index read each other's entry
  points; a new graph whose vertex set did not contain those ids failed with a `NullPointerException` on
  the first vector write. The key now includes the graph name. **Existing Aerospike vector indexes must be
  rebuilt once after upgrading** (`POST /api/v1/vector-index/rebuild`), because the old metadata record is
  ignored.
- **The latency alert could not clear**: `LatencySnapshot` persisted cumulative percentiles, so the
  Prometheus p99 was a lifetime value. It now stores the windowed percentile; restored history still
  restores the cumulative buckets and reports a windowed p99 of 0 until new samples arrive. The alert drill's
  XFAIL is now an ordinary assertion.
- **`verify-security.mjs` picked the oldest jar** in `graph-http/target` (e.g. 1.0.0 after building 1.0.1),
  so the check could validate a different build from the one just made. It now picks the newest.
- **`check-k8s.mjs` did not find Service selectors on Windows** because it matched LF only; it now normalizes
  CRLF before parsing.

### Changed

- **Entity dedup is one safe step wider**: names that differ only by traditional vs simplified Chinese
  (關羽 / 关羽) now merge through a curated, deterministic 繁→简 mapping, alongside the existing
  case/whitespace/punctuation canonicalization. Near-duplicates (prefix, one edit, shared tokens) stay a
  review list rather than a guess, and re-ingesting a document carries an entity's chunks over instead of
  overwriting them.
- **README** now leads with the auditable context layer, puts the three-minute demo and the Python client on
  the first path, and links the detailed material moved into `docs/`.
- **The fact-graph binding now lives in the semantic graph** (S1's final form): each metric/dimension's
  `graphProperty` and its synonyms (`alias`) are written onto the `tpc-h-analytics` nodes by the same dataset
  that writes the fact graph, so the two can no longer drift. `semantic-bindings/*.json` keeps only
  `dataGraph`/`vertexType`; `SemanticRunner` reads the model first and falls back to the file, and the year
  filter/date-dimension check find the date dimension by its year grain instead of hardcoding `dim/order_date`.
- **Release notes** state that their counts describe the tag, not `develop`.

## [1.0.0] - 2026-10-14

The release the plan in [docs/ROADMAP-1.0.md](docs/ROADMAP-1.0.md) was written for: M1 (seven items) and M2
(nine) complete, M3 four of five (cross-machine Gemini is deliberately skipped). The headline is not a feature -
it is that a stranger can now run this, get an answer, and re-derive every number in the documentation with a
command in the repository. [docs/RELEASE-1.0.0.md](docs/RELEASE-1.0.0.md) is the release note; what follows is
what changed, not what exists.

### Added

- **Verification of the input contract and the failure modes.** Two published checks that drive the API with the
  requests nobody intends to send. `verification/verify-input-contract.mjs` (78 assertions) covers malformed
  JSON, missing and wrongly-typed fields, unknown graphs, undeclared property indices, values of the wrong
  declared type, values over the size limit, CJK ids, and asserts three rules across all of it: a client mistake
  is never a `500`, no body carries a stack trace, and every error body has the same shape.
  `verification/verify-failure-modes.mjs` (20 assertions) starts an instance whose store is unreachable **and**
  one whose store accepts the connection and then stalls: both start, answer `503` + `Retry-After` for that
  graph, keep serving their RocksDB graphs, keep answering `/health` and the metrics endpoint, and refuse a
  2 MiB body (`413`) and a 108k-character query (`400`).
- **A degraded node keeps serving** (`StorageConfig`): the Aerospike client is built with
  `failIfNotConnected=false`, so an instance that cannot reach the store at startup boots instead of
  crash-looping, serves the graphs it can read, and reconnects by itself. `VectorIndexManager` logs and falls
  back to the schema's declared vector layers rather than refusing the whole application context.
- **Explicit status codes for failures that used to be `500`**: a malformed, missing or wrongly-typed body
  (`HttpMessageNotReadableException`) is `400`; a missing query parameter is `400` naming it; a parameter of the
  wrong type is `400` naming the parameter and the type it needs; an unsupported media type is `415`; an
  operation naming an element that is not in the graph is `404`; a checkpoint target that already exists is
  `409` instead of a `500` whose message happened to say "already exists".
- **Request-size limits** (`phoenixgraph.limits.max-body-bytes` 1 MiB, `max-query-length` 65536): an oversized
  body is `413` before anything is parsed or buffered, an oversized query is `400` naming the setting.
  `CachedBodyRequest` now enforces the bound its comment always claimed.
- **Declared-type validation on writes** (`DataQualityValidator`): a value that is not the type the schema
  declares is refused with a message naming the value and the type; an index the schema does not declare is
  refused with the list of declared indices (`docs/DATA-QUALITY.md` §9).
- **Referential integrity for edges** (`phoenixgraph.write.require-existing-endpoints`, default `true`): an edge
  whose endpoint vertex does not exist is a `404` naming the endpoint, instead of the store silently creating an
  untyped stub vertex that then appeared in scans and `elementMap()` (`docs/DATA-QUALITY.md` §10). A batch counts
  the vertices it writes itself, so one file's vertices and edges still go in one request.
- **A `Dockerfile` and `tools/release-1.0.sh`**: a non-root runtime image built from a staged jar, and a release
  script that reads the version from the pom, refuses a dirty tree or an existing tag, builds, checksums, probes
  the image, and (with `--tag`) tags and pushes.
- **`docs/RELEASE-1.0.0.md`** and this entry: what is in 1.0, what is not, and what an upgrade from 0.2.0
  changes.
- **A JSON error body for container-level refusals** (`JsonErrorController`): the errors Tomcat answers itself no
  longer come back as an HTML page. (An encoded slash in a path is still refused by the connector before the
  application sees it: the status is right, the body is the container's.)

### Changed

- **Error bodies no longer carry a stack trace** (`phoenixgraph.errors.include-stack-trace`, default `false`).
  They name the exception class and the `X-Request-Id`, which is what makes a `500` reportable; a stack trace
  hands absolute paths and library versions to anyone probing the service, and a client cannot act on the frames.
  The flag turns the old behavior back on for a debugging instance.
- **The 500 body no longer echoes the exception message.** The server log has it, with the request id.
- **JSON scalar coercion is off** (`CoercionConfigs`): `"graphName": 123` is a `400` naming the type rather than a
  request against a graph named `123`, and `"returnSubgraph": "yes"` is a `400`. Dynamic fields (`id`, `source`,
  `destination`) stay dynamic, because an id is a string or a number depending on the client.
- **`POST /api/v1/schema/version` judges a body it cannot parse** instead of failing: a 200 with
  `compatible: false` and a `problem` naming the parse error, which is the answer an endpoint whose job is to
  judge a file should give.
- **The request-limits check is no longer flaky**: it aligns its two-requests-in-one-window pair to a wall-clock
  second boundary and retries the deadline case, so a slow machine cannot turn "the second request happened in
  the next second, so it was allowed" into a failure.
- **Every module is at `1.0.0`** (from `0.1.0-SNAPSHOT`): the changelog's `0.2.0` had been the milestone name
  rather than the pom version, and the two now agree.

### Fixed

- **Writing an object into a `STRING` property was accepted** and stored as-is, readable through `elementMap()`
  as a nested structure (`docs/DATA-QUALITY.md` §9).
- **A property index the schema does not declare was stored as an extra bin** and shown to every reader
  (`docs/DATA-QUALITY.md` §9).
- **An edge to a non-existent vertex created an untyped stub vertex** with no type and no properties, which then
  appeared in type scans (`docs/DATA-QUALITY.md` §10).
- **A missing `query`, a missing `graphName`, a null body, a JSON array body, a null property entry, a missing
  `source`/`destination`/`edgeId` and a non-numeric property index were all `500`** with a Java stack trace in
  the body (`docs/DATA-QUALITY.md` §11).
- **A rejected 1 MB property value was echoed back in full** in the error body: messages now quote at most 80
  characters of the offending value.
- **`DELETE /api/v1/vertices/{id}` answered `SUCCESS` for an id that never existed**, hiding the typo; it now
  answers `404` like the read does.

## [1.0.1] - 2026-10-14

A patch release with what the on-call drill found. No API change.

### Added

- **`deploy/prometheus/prometheus.yml`** and **`verification/verify-alert-drill.mjs`**: the drill reproduces the
  condition behind each of the six alerts and reads the metric Prometheus would read (refusals `0 -> 3` against a
  tight rate limit, storage failure counters `0 -> 2` against an unreachable store, rejected requests for the
  error rate, concurrent load for the latency alerts, and for `up == 0` the scrape job, path and health endpoint
  that have to agree for the alert to mean anything).
- **A five-minute window for latency percentiles** (`LatencyStats.percentileMillis`): five one-minute slots,
  rotated on record *and* on read - a quiet instance would otherwise keep reporting its last minute forever, and
  a scrape is not a query. `cumulativePercentileMillis` keeps the since-start view. Ten unit tests, including
  "the window forgets and the cumulative view does not" and "a quiet graph stops reporting the old percentile".

### Fixed

- **The alert rules referenced a scrape job that did not exist.** `PhoenixGraphDown` selects
  `up{job="phoenixgraph"}`, and nothing in the repository defined that job - six alerts that could never fire.
  The drill asserts the rule and the scrape configuration agree.

### Known issue

- **The `*_p99_millis` series is still cumulative**, so `PhoenixGraphQueryLatencyHigh` cannot clear after one
  slow query. The exposition renders a persisted snapshot, and a snapshot is rebuilt from its bucket grid by the
  stores, so it cannot carry an in-memory window: a graph restored from storage reported 113 ms while nobody had
  queried it, identical across restarts, which is how the drill caught it. `docs/DATA-QUALITY.md` §12 has the
  evidence, `docs/SLO.md` says to treat the alert as noisy until then, and the drill states it as an explicit
  `XFAIL` - when that line starts failing, the fix has landed and it becomes an ordinary assertion.

## [Unreleased]

### Added

- **The semantic layer over HTTP** (`graph-http/.../http/semantic`): `GET /api/v1/semantic/{graph}/model` is the vocabulary an agent plans against (metrics, dimensions, the fact-graph binding behind each dimension, the reference queries with their original SQL, and the lineage), `POST .../translate` renders warehouse SQL without running it, and `POST .../validate` answers whether a request is answerable, returning the vocabulary when it is not. The renderer is a port of `tools/semantic-sql.mjs` and `verification/verify-semantic-api.mjs` compares the two byte-for-byte (32 statements over a matrix of metrics, dimensions, grains and dialects), with seven unit tests pinning the strings offline: the base-table rule, the missing-join warning, the per-dialect grain functions, and a grain-to-fact-property binding that is read from the model rather than guessed.
- **An MCP server** (`tools/mcp-server.mjs`): the graph tools and the semantic layer as an agent plugin, stateless in the 2026-07-28 revision of the protocol. Its tool list comes from `GET /api/v1/agent/tools`, which now also carries `bindings` - how to call each tool - so the plugin holds no tool knowledge and a tool cannot exist in one and not the other. User confirmation is a signed token rather than a session. `--selftest` checks the catalog, the schemas and three calls; a stdio round trip works end to end.

### Fixed

- **`tools/start-phoenixgraph.sh` started a stale jar.** It hardcoded `graph-http-0.1.0-SNAPSHOT.jar`, and the 1.0.0 release renamed every artifact: the script kept starting an old build while the build reported success, so new endpoints simply 404ed. It finds the jar now (newest first, `.original` skipped) and refuses to start when there is none.
- **`tools/api-endpoints.mjs` missed whole packages.** It only scanned `controller/`, so a controller in its own package never reached `docs/API.md` - an endpoint the reference does not list is an endpoint nobody finds. It walks the `http` package tree now: 55 endpoints across 15 controllers.

Milestone **M2** of [`docs/ROADMAP-1.0.md`](docs/ROADMAP-1.0.md) continues here.

### Added

- **API keys, roles and an audit trail** (M2-2): `phoenixgraph.security` with named keys (token or its
  SHA-256), the role ladder reader < writer < admin, optional per-graph scoping, `401`/`403` answered before
  any work happens, and every call recorded to the `com.pengcheng.phoenixgraph.audit` logger plus a bounded
  buffer read back through `GET /api/v1/audit/recent` (admin). Off by default; tokens are never logged.

- **TLS** (M2-3): `server.ssl.*` with a PKCS12 keystore and the password from the environment
  (`PHOENIXGRAPH_TLS_PASSWORD`), documented end to end and checked by `verification/verify-tls.mjs` against a
  keystore it generates with keytool - including that verification stays on (a self-signed certificate is
  refused by a verifying client) and that a wrong password stops the process instead of serving something else.

### Fixed

- **A key configured as its SHA-256 was never accepted**: the comparison hashed the configured digest a
  second time, so the failure looked exactly like a wrong token. A query is also a read
  (`POST /api/v1/query`, the pattern preview, the agent tools) - a reader role that could not query would
  have been useless - and the audit view needs `admin` even though it is a `GET`.

## 0.2.0 (2026-10-13) — milestone M1, never tagged

> Not a tagged release: `0.2.0` was the *milestone* name in `docs/ROADMAP-1.0.md` while the pom still said
> `0.1.0-SNAPSHOT`, so the two disagreed. It is kept here for the record; from 1.0 on the changelog version, the
> pom version, the git tag and the image tag are the same string (`docs/RELEASE-PROCESS.md` §2).

Milestone **M1** of the 1.0 plan: *a stranger can install it, see evidence, and file an issue*.

### Added

- **Published live verification suite** (`verification/`): ten checks that assert what the documentation
  claims, by driving the real API and the real UI in a headless browser, plus `verification/run.mjs`, which
  skips a check whose prerequisite is missing instead of reporting a false failure. Previously these lived
  in a gitignored working directory, so "reproducible" was a promise nobody outside could keep.
- **`QUICKSTART.md`**: ten minutes from clone to the first query, algorithm and agent tool call.
- **`docs/API.md`**: the endpoint reference (46 endpoints, 11 controllers), generated from the controllers
  by `tools/api-endpoints.mjs` so the table cannot drift; the error model, the write semantics and the
  request/response shapes are hand-written around it.
- **`docs/DATA-QUALITY.md`**: the ledger of data defects that make an analysis wrong, each with its
  evidence query, its impact and its handling (TPC-H's cyclic `nation → region`, `region` ≡ `segment`, the
  integer/boolean predicate trap, the 三国 `killed` relations being novel-sourced, truncated evidence text,
  and the stale `TPC-H-ANALYTICS.md` §4/§5 figures).
- **`docs/ROADMAP-1.0.md`**: the three milestones with acceptance criteria per item.
- **CI** (`.github/workflows/ci.yml`): unit tests on every push, the container path built and smoke-tested
  over HTTP, and a nightly job that seeds the demo graphs and runs the full live suite. `tools/check-ci.mjs`
  validates the workflow against the pom and the compose file (JDK version, pinned store).
- **Server-side request limits** (M2): a per-request wall-clock timeout, a concurrency gate and per-graph
  quotas, configured under `phoenixgraph.limits`, answering `503`/`429` with `Retry-After` instead of
  occupying the instance.
- **Prometheus metrics** (M2): `GET /api/v1/metrics/prometheus` (and per graph) in text exposition format,
  from the counters and latency histograms the engine already keeps.
- **Structured request logging** (M2): every request logs one line with a request id (also returned as the
  `X-Request-Id` header), the graph, the language, the outcome and the latency.

### Changed

- **Unknown paths answer `404` and a known path with the wrong method answers `405`** (with `Allow`), each
  with the standard error body. Both used to fall through the catch-all and answer `500`, which told an
  integrator the server was broken when the request was wrong.
- **The Aerospike image is pinned** (`aerospike/aerospike-server:8.1.2.5`) in the deployment compose and in
  CI, instead of `latest`.
- **README carries a Limitations section** stating what this is not yet (no auth/TLS, single node, no
  backup, no per-request limits, demo-scale numbers, unsupported traversal constructs).

### Fixed

- **`lateDelivery`/`returned` were never empty.** An earlier note here concluded "the flags are all false"
  from `has('lateDelivery', true)`, which matches nothing because the loader writes integers: the integer
  form matches **771** of 8,000 sales (9.64%) and **351** returned (4.39%). The question was wrong, not the
  data - see `docs/DATA-QUALITY.md` §3.

## 0.1.0 (2026-09-25) — first milestone, never tagged

> Not a tagged release: the pom said `0.1.0-SNAPSHOT` and the number was a milestone in the plan (see the note
> above `0.2.0`). Kept for the record.

The first tagged state: the engine, the four storage schemes, Gremlin and openCypher, the graph algorithms
(in-process and on the Gemini cluster), fuzzy linking, HNSW vectors, geo search, the agent toolkit, the
semantic layer, and the visualization workbench. See `README.md` for what each of those does and
`HANDOVER.md` (local) for how it was built.

[Unreleased]: https://github.com/KanJiaZhang/PhoenixGraph/compare/v1.0.1...HEAD
[1.0.1]: https://github.com/KanJiaZhang/PhoenixGraph/releases/tag/v1.0.1
[1.0.0]: https://github.com/KanJiaZhang/PhoenixGraph/releases/tag/v1.0.0

### Added

- **Backups, and a recovery drill** (M2-4): `POST /api/v1/graphs/{graph}/checkpoint` takes a consistent,
  hard-linked RocksDB snapshot while the service keeps serving; `GET /api/v1/graphs/{graph}/storage` says which
  Aerospike set or RocksDB directory holds a graph and which tool backs it up; `tools/backup-graph.sh` and
  `tools/restore-graph.sh` do both schemes; graceful shutdown (`server.shutdown=graceful`, plus the metrics
  flush and the RocksDB close that already ran on context close) so a restart does not drop counters or cut a
  query in half. `verification/verify-backup-restore.mjs` proves it: a checkpoint is served by a second
  instance and answers identically (8,000 vertices, same revenue per year), a restart loses nothing, and
  asbackup → delete → asrestore brings a deleted vertex back.
- **A schema directory** (M2-5): `phoenixgraph.storage.schema-dir` serves every `*.json` in a mounted
  directory as a graph, without rebuilding the image; an explicit entry in `phoenixgraph.storage.graphs` wins
  over a file of the same name.
- **`docs/OPERATIONS.md`**, the runbook: what runs where, the settings an operator changes, backups for both
  schemes, restore and the two `asrestore` flags a repair needs, upgrades and rollback, what to watch, and the
  traps that cost somebody an afternoon.

### Fixed

- **A deleted vertex is now really gone from reads.** Deleting writes an expiry marker into the record's
  `lastUpdatedTime` bin, but the predicate every read path goes through (`isMissing`) only checked for a null
  record - so a deleted vertex stayed visible in point reads, batch reads, scans and subgraph expansion, and
  stayed visible across a restart. Found by the backup drill (write a vertex, delete it, read it back), fixed
  in one place, and covered by a unit test. It also explains the `asrestore` "skipped"/"fresher" behaviour the
  restore drill hit. See `docs/DATA-QUALITY.md` §8.

### Added

- **Kubernetes manifests** (M2-6) in `deploy/k8s`: a kustomization with the engine, the UI, the store, the PVC,
  the ConfigMap and a Secret *example* (placeholders only). Probes are split the way they should be
  (`/api/v1/health` for liveness and startup, `/api/v1/graphs` for readiness), resources are declared, the
  termination grace period is longer than the shutdown phase, secrets are referenced by key, and
  `tools/check-k8s.mjs` fails a manifest that loses any of that, pins `latest`, or selects nothing.
- **Service level objectives** (M2-9) in `docs/SLO.md`, with the alert rules in
  `deploy/prometheus/phoenixgraph-rules.yml` and the measurement in `verification/verify-slo.mjs`: four query
  classes measured end to end (p50/p95/p99 against their objectives, interleaved so one busy period cannot make
  the last class look slow), plus checks that every metric an alert names exists in the live exposition and
  every threshold in the rules is stated in the document.

### Changed

- **Failure counters are exported while they are still zero.** A series that appears only after something has
  already failed cannot be alerted on: "no data" and "no failures" look the same to a rule. The six storage
  failure counters are now always exposed, so `0` really means zero.
