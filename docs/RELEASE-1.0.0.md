# PhoenixGraph 1.0.0 — release notes

**Tag:** `v1.0.0` · **Image:** `phoenixgraph-http:1.0.0` · **Jar:** `dist/phoenixgraph.jar` (+ `.sha256`)
**Built from:** `tools/release-1.0.sh`, which refuses a dirty working tree and a version that is already tagged.

## What 1.0 means here

It means: **a stranger can run it from the documentation, get an answer, and see the evidence that the answer
is right.** It does not mean feature-complete, and it does not mean production-hardened at any scale. The two
sentences below are the whole release in one line each:

- **What it is:** an HTAP graph database that stores a vertex/edge graph in Aerospike or RocksDB, answers
  Gremlin and Cypher, runs Pregel-style algorithms (in-process or on a Gemini cluster), and serves a vector,
  geo, full-text and semantic-layer query surface plus a UI and an agent toolkit.
- **What it is not:** a system that has been run at 10⁷ vertices, that survives losing a *datacenter*, or
  whose query language covers everything Gremlin does. Each of those is stated with a number in
  [§ What it is not](#what-it-is-not).

## The numbers this release is measured by

> These counts describe the tag, not `develop`; the current counts are printed by CI
> (`node tools/check-release.mjs` and the unit job).

| | |
|---|---|
| unit tests | **948**, 0 failures (20 modules) |
| live checks | **20 scripts, ~370 assertions** (`node verification/run.mjs`) |
| API | **52 endpoints** across 13 controllers, generated into [`docs/API.md`](API.md) |
| schema files | 17, all stamped `schemaVersion: 1` |
| graphs served out of the box | 5 (Aerospike set, Aggregated-Edge, RocksDB-Edge, RocksDB inline, plus the demo set) |
| largest served graph | 6,594 vertices / 45,081 edges (`sanguo-kg-text`) |
| latency, end to end | p50 14–53 ms and p99 22–83 ms by query class; SLOs and how they are measured in [`docs/SLO.md`](SLO.md) |
| failure behaviour | store down → the node still starts, still serves its other graphs, answers `503`; timings in [`docs/CAPACITY.md`](CAPACITY.md) |

Every number above is produced by a command in this repository, and the assertions that check them are the
same ones CI runs. `verification/README.md` lists them and what each one needs (a service, Docker, or both).

## What is in it

**Query.** Gremlin (`graph-query-gremlin`, 330+ unit tests) and Cypher (`graph-query-cypher`), over four
storage schemes: Aerospike keyed, Aerospike with aggregated edge tables, RocksDB inline, RocksDB with separate
edge tables. Steps for traversal, filtering, aggregation, time windows, geo (`circle`, distance), vectors
(`nearest(k)` over HNSW), full text, and a semantic layer that generates SQL with joins, grains and dialects
(`tools/semantic-sql.mjs`).

**Compute.** `graph-gemini`: a Pregel-style worker cluster with supersteps, combiners, broadcast and
aggregation; PageRank, connected components, degree/closeness/betweenness/eigenvector centrality, shortest
paths, and a `(DIST)` variant of each step so a query can push the work to the cluster. Verified against the
in-process algorithms (equality to ±1e-16 on a 50-vertex fixture).

**Storage.** Aerospike (multi-replica capable, see the HA drill) and RocksDB (four schemes), with a schema
layer (`graph-schema`) that validates values against declared types, enum sets, numeric ranges and constraints,
plus vector, geo and full-text index declarations. Schema files carry a format version; a file from a newer
build is refused *by name* rather than parsed as far as possible.

**API and operations.** API keys with a reader < writer < admin ladder and per-graph allowlists, TLS,
request limits (concurrency, per-graph rate, deadlines, body size, query length) that answer `429`/`413`/`503`
with `Retry-After`, an audit trail, Prometheus exposition with latency percentiles and JVM gauges, structured
logs carrying a request id, structured JSON errors with **no stack traces by default**, RocksDB checkpoints and
`asbackup`-based backups with drilled restores, graceful shutdown, Kubernetes manifests, service level
objectives and the alerts that watch them, and a capacity/tuning guide with measured numbers.

**Agent toolkit.** `graph-agent`: documents → chunks → entities/relations (a knowledge graph) → an exact-cosine
vector index → quality metrics, exposed as eleven `/api/v1/agent/*` endpoints and a tool catalogue an LLM agent
can call. Two worked case studies (auditing a graph's edges against its own source text; refusing a
dimensionally-invalid analytic question and then answering it correctly) are in
[`docs/AGENT-CASES.md`](AGENT-CASES.md).

## What is not in it

| Area | What is missing | Where it is stated |
|---|---|---|
| Scale | nothing is measured above 10⁴ vertices; no published benchmark at 10⁷ | [`docs/CAPACITY.md`](CAPACITY.md) §6 |
| Cross-machine compute | Gemini has not been measured across hosts (one machine, 4 workers) | [`docs/ROADMAP-1.0.md`](ROADMAP-1.0.md) M3-3 |
| Query language | `as/select/match/project/sack` and the traverser model are designed, not built (`TRAVERSER-DESIGN.md`, local). Unsupported syntax is *rejected*, never ignored | README § Status |
| Writes on RocksDB | a RocksDB graph is loaded, not written over REST | [`docs/API.md`](API.md) §5 |
| Storage seed | the engine takes **one** storage seed, so a restart while that seed is down cannot connect (a running node reconnects by itself) | [`docs/CAPACITY.md`](CAPACITY.md) §6 |
| Write failure under a dead store | the write path retries and then answers `500 {"status":"FAILED"}`; a client retry will not help | [`docs/API.md`](API.md) §3 |
| Audit trail | kept in a bounded in-memory ring (2000 entries) and the log; it does not survive a restart | [`docs/API.md`](API.md) §2 |
| Container-level refusals | a request Tomcat rejects before the API (an encoded slash) gets the container's HTML body - the status is right, the body is not ours | [`docs/API.md`](API.md) §3 |
| Sharp edges in the demo data | `tpc-h-data`'s `region`/`segment` loader bug and the Sanguo graph's novel-only edges, quantified | [`docs/DATA-QUALITY.md`](DATA-QUALITY.md) §1–§7 |

## Upgrading

There is no prior 1.0, so "upgrade" means coming from `0.2.0` (2026-10-13). Two behavior changes to expect, both
of them fixes that a client relying on the old behavior will notice:

1. **Client mistakes are no longer `500`.** A missing field, a wrong JSON type, an undeclared property index, a
   value of the wrong type, a missing query parameter and a malformed body are now `400`/`404`/`405`/`413`/`415`
   (the table is in [`docs/API.md`](API.md) §3). A retry policy that treated `500` as "retry" should treat
   `429`/`503` as retry and the rest as "fix the request".
2. **Writes are validated harder.** An edge whose endpoint vertex does not exist is now `404` instead of
   creating an untyped stub vertex; a property index the schema does not declare is now `400`; a vertex delete
   or property update on an id that is not there is now `404` instead of a silent `SUCCESS`. A loader that
   imports edges before vertices sets `phoenixgraph.write.require-existing-endpoints=false`.
3. **Stack traces are gone from error bodies** unless `phoenixgraph.errors.include-stack-trace=true`.

Storage is compatible in one direction: a store written by `0.2.0` is read by `1.0.0`; schema files are
versioned and a file from a newer build is refused by name. Rolling back means deploying the `v0.2.0` image and
restoring from a checkpoint taken before the upgrade (`docs/OPERATIONS.md` §5).

## How to check this release yourself

```bash
export JAVA_HOME=...                       # JDK 21+
tools/start-phoenixgraph.sh                # builds and serves (UI 8080, API 8081)
node verification/run.mjs                  # 20 checks, ~370 assertions (Docker adds the HA drill)
tools/release-1.0.sh --no-image            # rebuild the jar + checksum from this commit
```

The suite skips, loudly and honestly, the checks whose prerequisites are absent (`1 skipped` in the summary);
`--retry 0` makes it strict for CI, which is how the nightly job runs it.
