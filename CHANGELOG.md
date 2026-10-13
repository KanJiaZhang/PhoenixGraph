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

Nothing yet: the next work is milestone **M2** of [`docs/ROADMAP-1.0.md`](docs/ROADMAP-1.0.md)
(authentication, TLS, backup/restore, externalised configuration, k8s manifests, SLO).

## [0.2.0] - 2026-10-13

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

## [0.1.0] - 2026-09-25

The first tagged state: the engine, the four storage schemes, Gremlin and openCypher, the graph algorithms
(in-process and on the Gemini cluster), fuzzy linking, HNSW vectors, geo search, the agent toolkit, the
semantic layer, and the visualization workbench. See `README.md` for what each of those does and
`HANDOVER.md` (local) for how it was built.

[Unreleased]: https://github.com/KanJiaZhang/PhoenixGraph/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/KanJiaZhang/PhoenixGraph/releases/tag/v0.2.0
[0.1.0]: https://github.com/KanJiaZhang/PhoenixGraph/releases/tag/v0.1.0
