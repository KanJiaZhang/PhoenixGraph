# PhoenixGraph 1.2 — release notes

**Tag:** `v1.2.0` · **Image:** `phoenixgraph-http:1.2.0` / `phoenixgraph-web:1.2.0` · **Jar:** `dist/phoenixgraph.jar` (+ `.sha256`)
**From:** `v1.1.2` · **Branch:** `develop` (a minor release; see [`RELEASE-PROCESS.md`](RELEASE-PROCESS.md))

A minor release with a new dimension and a new surface.

**The dimension is time.** A vertex or an edge can now say when it holds - `validFrom`/`validTo` - so a fact stops
being true without being deleted, and a question can be asked *as of* an instant. **The surface is the agent**: a pi
extension that puts the graph, the instant and the answer contract in every turn, ranks causes with their evidence,
draws the context it used, and can remember a conversation into the graph.

`/api/v1` is unchanged: fields and endpoints are additive.

## Time

- **Facts carry a validity window** (`validFrom`/`validTo`, epoch millis, either end optional, `from <= t < to`). It
  is the third time next to `createdTime` and `lastUpdatedTime`: those say what the database did, this says what the
  world did. An element without a window is valid at every instant, so nothing built before this behaves
  differently.
- **The ingestion takes the window per document** (ISO-8601 or millis) and stamps what it reads from it. The triage
  corpus says when each document's facts hold: the topology always, the September RCA until `deploy-48` landed, the
  change window from that morning.
- **Queries answer as of an instant**: `POST /api/v1/query` with `asOf` drops the elements whose window excludes it
  from the answer (vertices, edges, and edges whose endpoints went), and every agent tool takes `asOf` too - so
  `context` cannot cite a fact that had not happened yet or has stopped holding.
- **The UI has a timeline** under the side panel: moving it re-runs the query at that instant and the canvas keeps
  what was true then; a switch turns the whole thing off, and a link (`?graph=&q=&asOf=`) reproduces a view.
- **The triage case is now temporal**: `triage.mjs --as-of 2026-10-20` answers `deploy-42` (the September storm),
  `--as-of 2026-10-26` answers `deploy-48` (the change that landed on the 24th).

## The pi extension (`extensions/phoenixgraph`)

Installed with `pi install ./extensions/phoenixgraph`; it is a package (`keywords: ["pi-package"]`), so no symlink
and no copy that rots.

| Surface | What it gives |
|---|---|
| MCP server `phoenixgraph` | the toolkit's fourteen tools, `codemode`-exposed; stdio by default, HTTP (`/pg mcp http <url>`) for a shared deployment with token, JSONL audit and per-client rate limit |
| `pg_context(question)` | cited context: entities, relations with the sentence each was read from, coverage |
| `pg_triage(symptom)` | the ranked causes with their evidence, the precedent incident, and what would overturn it |
| `pg_asof(instant)` | a **sticky** instant: a question that does not name one answers with the session's, and it survives a restart |
| `pg_remember()` / `/pg remember` / `/pg note` | ingest this conversation (or one fact) into the graph, so the next session can cite it |
| `/pg show [entity]` | opens the UI at the context the answer used - the seeds, the relation edges, the instant - and seeds from the graph's busiest entities when nothing has been asked yet |
| a `phoenixgraph` prompt section | graph, instant, tools and the answer contract, added only when the session is about a graph, switchable with `/pg inject off` |
| `/pg status`, `graph`, `endpoint`, `timeline`, `mcp`, `inject` | the switchboard |

State has three lifetimes and no more: a branch-safe `pi.appendEntry` entry (never sent to the model), the
cross-session file `~/.pi/agent/state/phoenixgraph.json`, and a committable `.pi/phoenixgraph.json` that pins the
graph and endpoint for a repository - never the instant, which is the question being asked.

Verified in pi, not only in unit tests: pinning `2026-10-20` and asking `pg_triage` answered `deploy-42` with its
sentence and said the ranking rested on a single causal edge; a fresh process reported the same pin; a note
remembered in one process was answered, with its source sentence, in another.

## The triage case (`docs/INCIDENT-TRIAGE.md`, `tools/triage.mjs`)

Four RCAs, a topology, hosts, metrics and one change window become a graph; a fault symptom is ranked against its
causal edges. The weights live in one file (`tools/triage.mjs`) shared by the example, the MCP server and the
extension, and a check asserts the example and the library rank identically - a shared function with two seed sets
would still be two answers.

## Fixed

- **Re-ingesting a document duplicated its relation edges**: the edge id was `rel-<n>` (a position), so a second run
  wrote a fresh set of edges over ids that had meant different triples. It is a function of `(head, relation, tail)`
  now, so a re-ingest upserts.
- **The Aerospike health check used a flag `asinfo` does not have** (`-c status`), so every container reported
  unhealthy, `docker compose` never started the API, and the Kubernetes readiness probes could not pass.
- **The MCP server answered `2026-07-28` to every `initialize`**, and pi 1.3 rejects a revision it does not know. It
  negotiates now (echoes the client's among `2025-11-25`, `2025-06-18`, `2025-03-26`, `2024-11-05`).
- **`mvnw` and the eleven `tools/*.sh` scripts lacked the executable bit**, so Linux CI never ran the tests.
- **The Gemini cluster asked for privileged ports 1-3** when the configuration said "let the OS choose".
- **A seed-batch test asserted the runner's core count**, and the RDF artifact test compared CRLF against LF.
- **`ToolRequest.asOf` was a `String`**, so a client sending epoch millis got a 400.

## Upgrading from 1.1.2

1. **Nothing to migrate.** Version 1.1.1's Aerospike vector-index rebuild is the only storage step in this line, and
   it is already done if you released 1.1.1 or later.
2. **Optional**: give your documents validity windows when you re-ingest them. Facts without one stay valid at every
   instant, which is the pre-1.2 behaviour.
3. **Optional**: install the pi extension (`pi install ./extensions/phoenixgraph`) and run `/pg status`.

## Verifying this release

```bash
tools/start-phoenixgraph.sh --no-build
node verification/verify-incident-triage.mjs    # 18 checks: the corpus, the ranking, the instant, re-ingest
node --test extensions/phoenixgraph/test/*.test.mjs   # 23 checks: the plugin, without pi or a service
node --test tools/mcp-server.test.mjs           # the MCP surface, including the protocol negotiation
node tools/check-release.mjs                    # the repository agrees with itself about 1.2.0
node verification/run.mjs                       # everything this machine can run
```

## Known limits

The list in the [README](../README.md#status) is the current one and did not change. Two are worth repeating for this
release: extraction is a dictionary plus rule packs (**a fact the pack cannot read is a fact the graph does not
have**, which is why `/pg remember` prints how much it actually read), and the validity filter applies to the
**answer**, not inside the traversal - a walk may pass through an expired edge, while no answer contains one.
