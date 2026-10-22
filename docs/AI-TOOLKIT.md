# AI agent toolkit

`graph-agent` turns documents into a graph an AI can query with sources, and exposes the graph as tools
any agent runtime can call. It has no LLM dependency: retrieval, extraction and analysis are
deterministic, so an answer can be reproduced and checked.

```
documents ──extract──▶ entities + relations ──measure──▶ quality metrics (accuracy / coverage)
                             │
                             └──write──▶ graph (vertices, edges, vectors) + index artifact
                                                      │
                            tool calls ◀──recall───────┴───────expansion──▶ graph engine
```

For the HTTP shapes, see [API.md §6](API.md#6-the-agent-toolkit). For worked examples, see
[AGENT-CASES.md](AGENT-CASES.md) and [DATA-AGENT-CASES.md](DATA-AGENT-CASES.md).

## 1. Ingest documents

Any text works (a chapter, a spec, a ticket, a CSV of triples). Known names come in as a *dictionary*
(the gazetteer); everything a rule finds outside it becomes a new entity, typed by the rule's slot. The
relation rules read the text *between* two mentions and the words that follow them, clause by clause, so
Chinese word order (which is not verb-final) is handled without a parser; `知识图谱` extractors usually
start here and swap the rule table for a model.

```bash
curl -s -X POST localhost:8081/api/v1/agent/ingest -H 'content-type: application/json' --data-binary @- <<'EOF'
{
  "graph": "agent-demo",
  "documents": [
    {"id": "guanyu", "title": "三国志 · 关羽传", "text": "关羽字云长，是刘备的义弟，也是刘备的臣。\n关羽斩杀颜良，又与文丑交战。"},
    {"id": "zhugeliang", "title": "三国志 · 诸葛亮传", "text": "诸葛亮是刘备的军师。\n诸葛亮与司马懿交战。"}
  ],
  "dictionary": {"关羽": "Person", "刘备": "Person", "诸葛亮": "Person", "司马懿": "Person", "颜良": "Person", "文丑": "Person"},
  "gold": ["关羽|killed|颜良", "诸葛亮|strategist_of|刘备"],
  "apply": true
}
EOF
```

`apply: false` runs the whole pipeline and writes nothing — the way to look at what an ingestion *would*
produce before trusting it with a graph. The graph itself is created on the first ingest (schema, vertex
and edge types, the vector property), then every later ingest upserts into it.

### Rule packs and the document CLI

The vocabulary is data. `rulePack` selects a JSON pack; the default `sanguo` is built in, and
`incident` reads business prose (`caused`, `fixed`, `depends_on`, `owns`, `assigned_to`, `deployed_on`, ...).
The pack is a list of rules that match the text between two known mentions; `swap` reverses the direction
for passive sentences. A pack in `phoenixgraph.agent.rules-dir` (or `PHOENIXGRAPH_AGENT_RULES_DIR`) wins over
the classpath, so an operator can add a domain without rebuilding; the name is restricted to
`[a-z0-9_-]+`.

```bash
node tools/ingest-docs.mjs examples/business-demo/documents \
  --graph incident-demo --rule-pack incident \
  --dictionary examples/business-demo/dictionary.json --apply
```

The CLI reads Markdown, text and HTML directly and PDF through `pdftotext`. A scanned PDF can go through
`--ocr`, which renders pages with `pdftoppm` and reads them with `tesseract -l <lang>` (`--ocr-lang`,
default `eng`); both commands must be installed. It defaults to a dry run; `--apply` writes.
`--dictionary` types known entities, `--aliases` maps an alias to a canonical name before extraction
(manual entity resolution), and `--gold` supplies triples for the quality report. See [the business demo](../examples/business-demo/README.md) and
[the CLI](../tools/ingest-docs.mjs). The running UI has the same form behind the document icon for
Markdown, text and HTML; PDF still goes through the CLI.

Entity names that differ only by case, width, whitespace or punctuation are merged by default. The
dictionary entry wins as the canonical name, the other spellings become aliases on the entity, and a
relation's sentence is still accepted as evidence when it contains an alias. `--no-dedupe` keeps the
variants separate.

Confluence and Notion use the same ingestion API:

```bash
CONFLUENCE_BASE_URL=https://acme.atlassian.net/wiki CONFLUENCE_SPACE=ENG \
  CONFLUENCE_EMAIL=me@acme.com CONFLUENCE_TOKEN=... \
  node tools/ingest-confluence.mjs --graph confluence-demo --rule-pack incident --apply

NOTION_TOKEN=secret_... node tools/ingest-notion.mjs --graph notion-demo --rule-pack incident --apply

S3 uses SigV4 and needs no AWS SDK:

```bash
S3_BUCKET=my-docs node tools/ingest-s3.mjs --prefix handbooks/ --graph handbook-demo --rule-pack incident --apply
```

## 2. Quality metrics

Two families, both computed from the extraction rather than asserted, and both per relation type:

| Metric | What it means |
| --- | --- |
| `provenanceRate` | share of relations whose own sentence really contains both ends (or an alias of an end) |
| `typeAgreement` | share of entities whose type came from the dictionary or a typed rule slot rather than from a guessed mention |
| `precision` / `recall` / `f1` | against a `gold` triple set (endpoints resolve through dedup aliases), when one was given (plus `pairPrecision`/`pairRecall`, which forgive a differently named relation) |
| `chunkCoverage` | share of chunks that produced at least one relation |
| `entityLinkRate`, `isolatedEntities` | how much of the entity set ended up connected |
| `dictionaryCoverage` | share of the supplied vocabulary the corpus actually uses |
| `duplicatesRemoved` | entity names merged by case/whitespace/punctuation normalisation |
| `mergedEntities` | the canonical name and aliases of each merged group, for review |
| `mergeCandidates` | near-duplicate names (prefix / one edit / shared tokens) that were **not** merged; capped at 20 and scanned over the 2,000 most-mentioned entities |
| `relationsPerThousandChars`, `relationsByType`, `relationsByRule` | density and where the facts came from |

A high accuracy with a low coverage means a cautious extractor; the opposite means one that guesses.
`GET /api/v1/agent/quality/{graph}` returns these numbers later from the artifact.

## 3. Tools for an agent

The catalog is fetched, not hard-coded: `GET /api/v1/agent/tools` returns 14 tools with their parameters
and the endpoint each one binds to. Adding a tool to `AgentToolCatalog` makes it visible to every client.

Retrieval tools:

| Tool | Question |
| --- | --- |
| `find_entities(question, k)` | which entities is this about? (name matches first, then vector closeness) |
| `search(question, k)` | hybrid recall: name, description terms and vector; use it when the question is a phrase |
| `related(entity[, relation][, hops])` | what is connected to it, with relation names and provenance |
| `similar(entity, k)` | what is *like* it (vectors) |
| `related_similar(entity, k)` | the lookalikes of everything around it (**graph → vector**) |
| `similar_related(entity, k)` | what its lookalikes are connected to (**vector → graph**) |
| `path_between(from, to, maxHops)` | are they connected, and by which walk |
| `context(question, k, hops)` | the whole context of a question, with sources — the shape to hand to a model |

Data tools, over the semantic layer (see [DATA-AGENT.md](DATA-AGENT.md)):

| Tool | Question |
| --- | --- |
| `semantic_model(graph)` | what can be asked: metrics, dimensions, fact-graph bindings |
| `semantic_translate(...)` | what SQL a metric + dimension becomes, without running it |
| `semantic_validate(...)` | is this request answerable, and if not, why |
| `ask_data(question)` | answer a data question, with the plan and provenance, or refuse it |
| `analyze_business(...)` | what moved between two periods, and by how much |
| `attribute_change(...)` | how much of a change each slice accounts for |

For the retrieval tools, every answer is the same document:

```json
{
  "tool": "context", "graph": "agent-demo",
  "entities":  [{"id": "司马懿", "type": "Person", "name": "司马懿", "score": 0.64, "via": "name", "cites": ["三国志 · 诸葛亮传 §1"]}],
  "relations": [{"head": "诸葛亮", "relation": "fought", "tail": "司马懿", "rule": "fought",
                 "cite": "三国志 · 诸葛亮传 §1", "sentence": "诸葛亮与司马懿交战。"}],
  "paths":     [{"vertices": ["水镜先生", "诸葛亮", "司马懿"], "hops": 2}],
  "citations": [{"chunkId": "zhugeliang#0", "cite": "三国志 · 诸葛亮传 §1", "text": "..."}],
  "coverage":  {"entities": 7, "relations": 6, "citations": 3, "seeds": 2, "hops": 1},
  "next":      ["related(entity, relation, hops)", "context(question, k, hops)"]
}
```

`coverage` is the part a model should quote: it says how many entities and relations the answer rests
on, so "from 6 related entities and 11 relations" replaces a fact from nowhere. `next` names the tools
worth trying after this one.

## 4. Use it from an agent product

Any runtime that speaks MCP or can call HTTP can use the same tools.

**MCP (stdio).** Point the client at `tools/mcp-server.mjs`; the server reads the catalog from the API
and advertises the tools, so it stays in sync with the service.

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

```bash
node tools/mcp-server.mjs            # speak MCP on stdin/stdout
node tools/mcp-server.mjs --list     # what it would advertise
node tools/mcp-server.mjs --selftest # initialize, list, one call, exit
```

The server is stateless and forwards `PHOENIXGRAPH_API_KEY` as `Authorization: Bearer ...` when the
service has security enabled. User confirmation for data questions is a signed token returned by the API
(plan hash + expiry), so it survives a restart and works across replicas.

**MCP (HTTP).** The same server can run as a Streamable HTTP endpoint (JSON response mode). It is
stateless, so there is no session id to keep; bind it to loopback by default and give it a bearer token
when something else must reach it.

```bash
PHOENIXGRAPH_MCP_PORT=8090 PHOENIXGRAPH_MCP_TOKEN=... node tools/mcp-server.mjs --http
curl -s localhost:8090/healthz
curl -s localhost:8090/mcp -H "Authorization: Bearer ..." -H 'content-type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

| Variable | Meaning |
| --- | --- |
| `PHOENIXGRAPH_MCP_HOST` | bind address, default `127.0.0.1`; a non-loopback bind without a token or clients map is refused |
| `PHOENIXGRAPH_MCP_PORT` | port, default `8090` (`0` picks a free port) |
| `PHOENIXGRAPH_MCP_TOKEN` | require `Authorization: Bearer <token>` on `/mcp` |
| `PHOENIXGRAPH_MCP_CLIENTS` | JSON map `token -> {name, tools}`; per-client tool scopes, takes precedence over the single token |
| `PHOENIXGRAPH_MCP_TOOLS` | comma-separated allowlist of tool names (default: all) |
| `PHOENIXGRAPH_MCP_DENY` | comma-separated denylist, applied after the allowlist |
| `PHOENIXGRAPH_MCP_AUDIT` | JSONL file: one line per `tools/call` with client, tool, graph, status, duration |
| `PHOENIXGRAPH_MCP_RATE_LIMIT` | calls per client per minute; `0` (default) is unlimited |

The HTTP transport answers JSON by default. If the request carries `Accept: text/event-stream`, a POST
returns one `event: message` per JSON-RPC answer and closes; `GET /mcp` with that header opens a heartbeat
stream. There is no server-initiated message queue: the server is stateless, so SSE is a framing choice,
not a session. `DELETE /mcp` is a no-op 204.

Every `tools/call` can be appended to a JSONL audit file (the API key still reaches the service, so the
service's own audit also sees the request). The rate limit is an in-process sliding minute per client; it is
not distributed across replicas.

Per-client scopes are an environment map, not an OAuth server:

```bash
export PHOENIXGRAPH_MCP_CLIENTS='{"agent-token":{"name":"agent","tools":["find_entities","context"]},"admin-token":{"name":"admin","tools":["*"]}}'
```

**Python.** The zero-dependency client in [`clients/python`](../clients/python) wraps the same calls:

```python
from phoenixgraph import PhoenixGraphClient

client = PhoenixGraphClient("http://localhost:8081")  # api_key="..." when secured
answer = client.context("agent-demo", "诸葛亮和司马懿是什么关系")
print(answer["coverage"])
```

Install it with `pip install -e clients/python`.

**TypeScript.** The zero-runtime-dependency client in [`clients/typescript`](../clients/typescript)
wraps the same calls and ships its own types. It is not published to npm yet; build it from the repository
(`npm install && npm run build`) and import `dist/index.js` or install the local path.

```ts
import { PhoenixGraphClient } from '@phoenixgraph/client';

const client = new PhoenixGraphClient({ baseUrl: 'http://localhost:8081' });
const answer = await client.context('agent-demo', '诸葛亮和司马懿是什么关系');
console.log(answer.coverage);
```

Build and test it with `npm install && npm run build && npm test`; the tests use a local mock HTTP
server, not a running service.

**HTTP.** `GET /api/v1/agent/tools` carries each tool's parameters and the endpoint it binds to; an
OpenAI-style function-calling or LangChain tool adapter can register them from that response. The
retrieval endpoints are `POST /api/v1/agent/*`; the data endpoints are `POST /api/v1/semantic/*` and
`POST /api/v1/analysis/*`.

**Curated vs ingested.** A curated graph (like `tpc-h-analytics`) is queried as it stands: its vertices
and edges are the tool surface. An ingested corpus gets `context`, the four vector/graph combinations and
citations on top. A data agent can use both, because both answer over the same API.

## 5. Worked example

The two short documents in [`docs/agent-demo`](agent-demo) (a 关羽 and a 诸葛亮 biography) are ingested
into the `agent-demo` graph with the command above. Measured on this machine:

| | |
| --- | --- |
| input | 2 documents, 4 chunks, 234 characters |
| extracted | **17 entities, 19 relations, 10 relation types** (killed, fought, minister_of, strategist_of, sibling_of, subordinate_of, teaches, spouse_of, stationed_at, serves) |
| accuracy | `provenanceRate` **1.0** (every relation is backed by its own sentence), `typeAgreement` **1.0** |
| coverage | `entityLinkRate` **1.0** (no isolated entity), `dictionaryCoverage` **1.0**, `chunkCoverage` 0.5 |
| against gold (4 triples) | `recall` **1.0**, `pairRecall` **1.0**, `precision` 0.21 (the corpus states 19 relations, 4 of them in the gold set) |
| written | 21 vertices, 42 edges, artifact `agent-demo.agent.json` |

One question, end to end — "how are 诸葛亮 and 司马懿 related?" (`POST /api/v1/agent/context` with
`k=2`, because both entities are named; a larger `k` adds low-score vector matches):

| Step | Answer |
| --- | --- |
| seeds | 司马懿 (name), 诸葛亮 (name) — `via: name`, scores 0.64/0.63 |
| expansion (1 hop) | + 张郃, 黄月英, 汉中, 刘备, 水镜先生 → **7 entities** |
| relations | `诸葛亮-[fought]->司马懿`, `水镜先生-[teaches]->诸葛亮`, `张郃-[subordinate_of]->司马懿`, `诸葛亮-[strategist_of]->刘备`, `诸葛亮-[stationed_at]->汉中`, `黄月英-[spouse_of]->诸葛亮` → **6 relations** |
| sources | **3 citations** (`三国志 · 诸葛亮传 §1` …) |

The same example through the other tools: `related('关羽')` answers with **5 neighbours and 8 relations**
and 3 citations; `similar('关羽')` puts 刘备 (0.95), 张飞 (0.89) and 水镜先生 (0.89) first;
`similar_related('关羽')` reaches 诸葛亮 and 阆中 through the lookalikes;
`path_between('水镜先生','司马懿')` finds `水镜先生 → 诸葛亮 → 司马懿` with the two relations behind the
hops.

## 6. Design choices and their limits

- **The default embedder is a hashing bag of character n-grams**, not a language model: deterministic,
  offline, no dependency. It keeps ingestion reproducible and needs no segmenter for Chinese. For a real
  model, set `phoenixgraph.agent.embedding.provider=http` (an OpenAI-compatible `/embeddings` endpoint);
  the artifact records `http:<model>:<dimensions>` and query time rebuilds the same vector space. A
  provider failure is an error, never a silent fallback to hashing (mixing vector spaces produces
  plausible nonsense).
- **Vector recall and rerank happen over the artifact** (exact cosine, linear in the entity count); the
  graph's own HNSW index answers the same question in sublinear time through `nearest(k)` in a query, and
  a graph with millions of entities should use that instead.
- **Expansion happens in the graph engine** (adjacency, paths, relation filters) — the half the engine is
  best at.
- **Ingestion writes through the REST write path**, so a graph whose store does not take REST writes (the
  RocksDB schemes, which their loaders fill) is refused with that message rather than half written. The
  *query* tools have no such limit: the artifact plus the engine answer on any scheme.
- **The rules are data.** A new domain means a new `RulePack` (and a dictionary), not a new engine;
  `agent-rules/incident.json` is the worked example, and `phoenixgraph.agent.rules-dir` loads one without a
  rebuild.
- **The reranker has a provider.** The default `none` keeps the hybrid order. `provider=http` sends the
  query and each hit's name/type to a Cohere/Jina-compatible `/rerank` endpoint; a provider failure falls
  back to the hybrid order rather than failing the answer. The hit does not carry its description, so a
  domain cross-encoder still belongs in the `AgentQuery` layer.

To switch the embedder to an OpenAI-compatible endpoint:

```yaml
phoenixgraph:
  agent:
    embedding:
      provider: http
      base-url: https://api.openai.com/v1
      api-key: ${OPENAI_API_KEY}
      model: text-embedding-3-small
      dimensions: 1536
```

Re-ingest after changing the model: an existing artifact keeps the embedder it was built with, and a
query rebuilds that same space.

The optional reranker is configured separately:

```yaml
phoenixgraph:
  agent:
    reranker:
      provider: http
      base-url: https://api.cohere.com/v1
      api-key: ${COHERE_API_KEY}
      model: rerank-v3.5
```

## 7. Evaluation

`verification/verify-retrieval-eval.mjs` is the answer-level check: a hand-checked gold set of questions,
the entities and relations a good answer must contain, and a source fragment a citation must carry. It
reports entity recall, relation recall and citation coverage, and exits non-zero when a threshold is
missed. Run it against a running service:

```bash
node verification/run.mjs --only retrieval-eval
```

The data-agent evaluation is separate (`verification/verify-data-agent.mjs`), because a correct number and
a correct refusal are different claims from a well-cited context.
