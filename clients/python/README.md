# PhoenixGraph Python client

A zero-dependency client for the PhoenixGraph HTTP API. It wraps the query, ingestion, agent and semantic
endpoints and returns the service's JSON as-is, so citations and coverage stay visible to the caller.

Requires Python 3.9+ and a running PhoenixGraph service (`tools/start-phoenixgraph.sh`).

## Install

```bash
pip install phoenixgraph-client       # once a release is published
pip install -e clients/python         # from this repository
```

## First call

```python
from phoenixgraph import PhoenixGraphClient

client = PhoenixGraphClient("http://localhost:8081")

answer = client.context("agent-demo", "诸葛亮和司马懿是什么关系")
print(answer["coverage"])
for relation in answer["relations"]:
    print(relation["head"], "->", relation["relation"], "->", relation["tail"])
    print("  ", relation["cite"], relation.get("sentence", ""))
```

`context` returns `entities`, `relations`, `paths`, `citations`, `coverage` and `next` - the same document
an agent gets over MCP.

## Ingest your own documents

```python
from phoenixgraph import Document, PhoenixGraphClient

client = PhoenixGraphClient()
result = client.ingest(
    "my-graph",
    [
        Document(id="doc-1", title="Incident report", text="..."),
        Document(id="doc-2", title="Postmortem", text="..."),
    ],
    dictionary={"Aurora-7": "Service", "Redis": "Component"},
    apply=False,  # dry run: see the quality report before writing
)
print(result["quality"])
```

Set `apply=True` to write the graph. The graph is created on the first ingest and later ingests upsert into
it.

## Query

```python
rows = client.query(
    "payment-graph",
    "g.V(135,'Account').bothE('Payment').otherV().limit(5)",
    return_subgraph=True,
)
print(rows["result"])
print(rows["subgraph"]["vertices"])
```

## Data questions

```python
answer = client.ask_data("tpc-h-analytics", "1996 年各地区营收")
print(answer.get("answer"), answer.get("refusal"))
```

`ask_data` and the attribution tools return a plan, provenance and either an answer or a refusal. Nothing
is executed from a generated SQL string.

## Authentication

```python
client = PhoenixGraphClient("https://graph.example.com", api_key="...")
```

The key is sent as `Authorization: Bearer ...`. A 401/403 raises `PhoenixGraphError` with `status`, `body`
and `request_id`.

## What is wrapped

| Group | Methods |
|---|---|
| Service | `health`, `graphs`, `metrics`, `prometheus` |
| Query | `query` |
| Ingestion | `ingest`, `quality`, `index` |
| Agent tools | `tools`, `find_entities`, `search`, `related`, `similar`, `related_similar`, `similar_related`, `path_between`, `context` |
| Semantic layer | `semantic_model`, `semantic_translate`, `semantic_validate`, `ask_data` |
| Analysis | `analyze_business`, `attribute_change` |
| Escape hatch | `request(method, path, body, params)` |

## Tests

The tests use a local HTTP server and need no running service:

```bash
python -m unittest discover -s clients/python/tests -v
```

The full API contract is in [docs/API.md](../../docs/API.md).
