# PhoenixGraph for pi

The pi extension of [`docs/PI-PLUGIN.md`](../../docs/PI-PLUGIN.md), P0 and P1 of that plan:

- **P0 - the tool surface.** Registers `tools/mcp-server.mjs` as an MCP server named `phoenixgraph`, exposed to
  `codemode` so fourteen graph tools are available without crowding the model's context.
- **P1 - the switchboard.** Two tools and one command, plus a *sticky* validity instant: `pg_asof` pins the answers
  to an instant, every later `pg_context` inherits it, and it survives the conversation.

Not here yet (P2 of the plan): `pg_triage`, and the `before_agent_start` block that states the answer contract.

## Install

Simlink or copy this directory into the agent directory, or load it for one run:

```bash
# per user: ~/.pi/agent/extensions/phoenixgraph -> this directory
ln -s "$(pwd)/extensions/phoenixgraph" ~/.pi/agent/extensions/phoenixgraph   # POSIX
# mklink /D "%USERPROFILE%\.pi\agent\extensions\phoenixgraph" "%CD%\extensions\phoenixgraph"   (Windows)

# or for a single session
pi --extension ./extensions/phoenixgraph/index.js
```

The MCP server path is resolved from this file (`../../tools/mcp-server.mjs`), so a symlink still finds the
repository; set `PHOENIXGRAPH_REPO` when the extension is copied somewhere else.

## What it adds

| Surface | Name | What it does |
|---|---|---|
| tool | `pg_context(question, k?, hops?, asOf?, graph?)` | entities, relations with the sentence each was read from, citations and a coverage count - the same document `POST /api/v1/agent/context` returns, rendered for a model |
| tool | `pg_asof(instant?, on?)` | pins the answers to an instant (`2026-10-21`), clears it (`on: false`), or reports it |
| command | `/pg status` | graph, endpoint, the pinned instant, the last question asked |
| command | `/pg graph <name>` · `/pg endpoint <url>` | which graph and which service this session talks to |
| command | `/pg timeline on\|off\|<instant>` | the same switch as the tool, from the keyboard |
| MCP | `phoenixgraph` | the fourteen tools of the toolkit (`find_entities`, `search`, `context`, `related`, `similar`, `path_between`, the semantic layer), `codemode`-exposed |

The instant is **sticky**, and that is the point: a question that does not name one answers with the session's,
so "what was true then" is a property of the conversation rather than a parameter the model has to remember.
`pg_context` reports the instant it answered with, and the coverage it rests on.

## State: three lifetimes

| Where | What | Lifetime |
|---|---|---|
| `pi.appendEntry("phoenixgraph.state", …)` | graph, endpoint, instant, timeline switch | the conversation, branch-safe, never sent to the model |
| `~/.pi/agent/state/phoenixgraph.json` | the same fields | across conversations (`PHOENIXGRAPH_STATE_FILE` overrides) |
| `<repo>/.pi/phoenixgraph.json` | `graph`, `endpoint` | the repository - a team pinning a service; it deliberately cannot set the instant |

On `session_start` the file is read, then the last state entry **on the current branch** wins, so `/tree` and
resume give the instant that branch was asking from. Nothing is written outside those two places.

## Verify it

```bash
node --test extensions/phoenixgraph/test/*.test.mjs   # 11 assertions, no pi, no service (a fake pi/fetch)
```

What the tests pin: the MCP server is registered and `codemode`-exposed, both tools and `/pg` exist, an instant is
normalised (`2026-10-21` -> `2026-10-21T00:00:00.000Z`) and a bad one is refused with the shape it wants,
`pg_context` inherits the pin and quotes the sentence and coverage, a per-call `asOf` overrides without moving it,
`on: false` clears it and stops sending it, the file carries the pin into a new conversation and a branch overrides
the file, a project file pins the graph but not the instant, `/pg` reports status, an unreachable service is
explained instead of thrown, and a question that names nothing is reported as a refusal.

Verified once in pi itself (`pi --extension ./extensions/phoenixgraph/index.js -p "…"`): the model pinned
2026-10-21, asked `pg_context`, and answered with the relation, its sentence and `coverage: 16 entities, 19
relations, 52 citations`; a second, fresh process reported the same pin from the state file.

## Limits

- `pg_context` is a thin, honest wrapper: it does not rank, summarise or fill gaps. When the graph has no relation
  it says so and the model is told not to guess (the P2 contract makes that explicit).
- The instant is a **declaration of fact time**, not a snapshot of rows: an element without a validity window is
  valid at every instant, and expires only if the ingestion gave it one.
- No write path: ingestion is `POST /api/v1/agent/ingest` (or `tools/ingest-docs.mjs`) today, and
  `/pg ingest` is P2.
