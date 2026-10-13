# HTTP API reference (`/api/v1`)

Everything this product does is reachable over HTTP: the query engine, the writes, the schema authoring, the
fuzzy patterns, the vector indexes, the metrics and the agent toolkit. The UI on 8080 and the CLI tools talk
to the same endpoints - there is no private back door.

> **Auth: none yet.** Every endpoint is open to whoever can reach the port, and an error response carries a
> Java stack trace. Run it behind a trusted boundary; `docs/ROADMAP-1.0.md` M2-2/M2-3 add authentication and
> TLS. Do not expose it to the internet.

- **Base URL** `http://localhost:8081` (`graph-web` proxies `/api` on 8080, so the UI is same-origin)
- **Content type** `application/json` (the UI HTML/JS is served by `graph-web`; the metrics endpoints also
  speak `text/plain` in Prometheus format)
- **Versioning** the path carries `v1`; within `v1` endpoints are added, and shapes change only compatibly
  (see the policy in [`CHANGELOG.md`](../CHANGELOG.md))
- **Compression** gzip above 1 KiB (`server.compression` in `application.yml`)
- **No caching of the UI** (`no-store`), so a running instance always serves the build it was started with

---

## 1. Request id

Every `/api` response carries `X-Request-Id`: the value the caller sent, or an 8-character id the server
generated. The same id is on the server's log line for that request, so "this query failed at 14:02" is one
grep away from the statement, the graph and the latency.

```bash
curl -sD- -o /dev/null -H 'X-Request-Id: my-trace-42' localhost:8081/api/v1/graphs | grep -i x-request-id
# X-Request-Id: my-trace-42
```

## 2. Limits and the status codes a client must handle

One instance has a fixed number of threads and connections. `phoenixgraph.limits` (see
`graph-http/src/main/resources/application.yml`) decides what it will spend:

| Setting | Default | Meaning |
|---|---|---|
| `enabled` | `true` | run the gate at all (a benchmark turns it off) |
| `max-concurrent` | `64` | requests in flight on this instance |
| `queue-wait` | `2s` | how long a request waits for a slot before it is refused |
| `per-graph-concurrent` | `8` | one hot graph cannot take the whole instance |
| `per-graph-per-second` | `200` | per-second budget per graph (`0` disables it) |
| `request-timeout` | `30s` | wall clock a query may run; the engine checks it between steps |
| `retry-after-seconds` | `1` | what a `429`/`503` answer tells the client to wait |

| Status | When | What a client should do |
|---|---|---|
| `200` | normal | — |
| `400` | malformed query, unknown graph, bad parameter | fix the request; the message says what is wrong |
| `404` | unknown path, unknown graph on a metrics route | fix the path (see the table below) |
| `405` + `Allow` | known path, wrong method | use the method in `Allow` |
| `429` + `Retry-After` | the per-second budget of that graph is spent | back off for `Retry-After`; this is *not* a bug |
| `500` | an unexpected server-side failure | report it with the `X-Request-Id` |
| `503` + `Retry-After` | the instance (or that graph) is at its concurrency limit, **or** the query exceeded `request-timeout` | retry with a narrower query, or raise the limit |

The timeout is cooperative and its boundary is deliberate: the engine checks the budget **between steps**, so
a single very expensive step (one hop over a huge fan-out, or a whole `pagerank()`) finishes before the check
fires. The outer bound for a single storage call is the storage client's own timeout.

```bash
# a refusal is an error body with a hint, never an empty response
curl -s -X POST localhost:8081/api/v1/query -H 'content-type: application/json' \
  -d '{"graphName":"nope","query":"g.V()"}'
# {"error":"Unknown graph 'nope'; available graphs: agent-demo, ...","exception":"...QueryException",
#  "stackTrace":[...],"hint":"Check the Gremlin query syntax and the request parameters"}
```

## 3. Querying

```bash
curl -s -X POST localhost:8081/api/v1/query -H 'content-type: application/json' -d '{
  "graphName": "payment-graph",
  "query": "g.V(135,\u0027Account\u0027).bothE(\u0027Payment\u0027).otherV().limit(5)",
  "includeResult": true, "returnSubgraph": true }'
```

`QueryRequest`: `graphName` (required), `query` (required), `params` (placeholders `${name}` in the query),
`language` (`gremlin` default, or `cypher`), `returnSubgraph` (include the drawn subgraph), `includeResult`
(include the traversers), `source` (who asked: `ui`, `http`, `internal`, `algorithm` - it only labels the
metrics), `script` (force the Groovy-style script mode on or off).

The response is `result` (one row per traverser, with `label`, `id`, `properties`, timestamps) and
`subgraph` (`vertices` + `edges`, the same picture the UI draws). A query that only reads values answers with
those values; the rows panel of the UI is exactly this array.

## 4. Writes

`POST /api/v1/graphs/{graph}/vertices`, `POST /api/v1/graphs/{graph}/edges`, `DELETE .../vertices/{id}`,
`DELETE .../edges/{edgeId}`, plus `POST /api/v1/{vertices,edges,batch}` for the primary graph and
`POST /api/v1/graphs/{graph}/batch` for any graph. A write answers `{"id": ..., "status": "SUCCESS"}`.

Two semantics worth knowing before you build on them:

- **Deletes are idempotent**: `DELETE /api/v1/vertices/{id}` on an id that does not exist answers
  `200 {"status":"SUCCESS"}` - the post-condition ("this id is gone") holds either way. A typo therefore
  looks like a success; check with a read when that matters. (A `found` field is a candidate for a later
  release; changing the response shape within `v1` would break clients.)
- **Writes go to the graph's own store.** A graph served from a read-only scheme accepts the request and
  rejects the write with a message naming the scheme, rather than silently writing somewhere else.

## 5. The agent toolkit

`GET /api/v1/agent/tools` returns the catalogue (the seven tools, their parameters and the answer shape).
The tools take one `ToolRequest`: `graph` (required), `question`, `entity`, `relation`, `from`, `to`, `k`,
`hops`, `maxHops`.

| Tool | Call it when |
|---|---|
| `find_entities` | you have a question but no entity id |
| `related`, `similar`, `related_similar`, `similar_related` | you have an entity (or a mention) and want what is around it |
| `path_between` | you have two entities and want the connections |
| `context` | you want one answer an LLM can use: entities, relations, paths and **citations** |

```bash
printf '{"graph":"agent-demo","question":"诸葛亮和司马懿是什么关系"}' > /tmp/q.json
curl -s -X POST localhost:8081/api/v1/agent/context -H 'content-type: application/json' --data-binary @/tmp/q.json
```

(The `--data-binary @file` form matters for non-ASCII text: a shell that re-encodes the argument makes the
service reject the JSON as invalid UTF-8.)

## 6. Metrics

- `GET /api/v1/metrics` - every graph in the grouped view, plus a flat counter dump
- `GET /api/v1/metrics/{graph}` - one graph: `storage`, `quality`, `queries`, `counters`
- `GET /api/v1/metrics/prometheus` - **all graphs in Prometheus text exposition format**, plus the request
  gate's counters (`phoenixgraph_requests_admitted_total`, `..._refused_total`)
- `GET /api/v1/metrics/{graph}/prometheus` - one graph in the same format
- `POST /api/v1/metrics/{graph}/flush` / `.../reset` - write through now / start the counters over

Metric names are the internal ones lower-cased with punctuation folded to `_`, and every graph-scoped sample
carries `graph="..."`:

```
phoenixgraph_storage_edge_read_total{graph="payment-graph"} 19161225
phoenixgraph_query_latency_language_gremlin_p99_millis{graph="payment-graph"} 4.812
phoenixgraph_requests_refused_total 0
```

Latency is exported as the summary the engine computes (`_count`, `_mean_millis`, `_p50_millis`,
`_p95_millis`, `_p99_millis`, `_max_millis`) rather than as Prometheus buckets: the internal bucket edges are
not a stable public contract, and a p99 you can compare across versions beats one you cannot.

## 7. Endpoints

<!-- endpoints:start -->
<!-- generated by tools/api-endpoints.mjs from 11 controllers - do not edit by hand -->

| Method | Path | Java method | Request body | Returns |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/agent/context` | `AgentController#context` | `ToolRequest` | `Map<String, Object>` |
| `POST` | `/api/v1/agent/find-entities` | `AgentController#findEntities` | `ToolRequest` | `Map<String, Object>` |
| `GET` | `/api/v1/agent/index/{graph}` | `AgentController#index` | — | `Object` |
| `POST` | `/api/v1/agent/ingest` | `AgentController#ingest` | `IngestRequest` | `IngestResult` |
| `POST` | `/api/v1/agent/path` | `AgentController#path` | `ToolRequest` | `Map<String, Object>` |
| `GET` | `/api/v1/agent/quality/{graph}` | `AgentController#quality` | — | `Object` |
| `POST` | `/api/v1/agent/related` | `AgentController#related` | `ToolRequest` | `Map<String, Object>` |
| `POST` | `/api/v1/agent/related-similar` | `AgentController#relatedSimilar` | `ToolRequest` | `Map<String, Object>` |
| `POST` | `/api/v1/agent/similar` | `AgentController#similar` | `ToolRequest` | `Map<String, Object>` |
| `POST` | `/api/v1/agent/similar-related` | `AgentController#similarRelated` | `ToolRequest` | `Map<String, Object>` |
| `GET` | `/api/v1/agent/tools` | `AgentController#tools` | — | `Map<String, Object>` |
| `POST` | `/api/v1/batch` | `BatchController#batch` | `BatchRequest` | `List<WriteResult>` |
| `POST` | `/api/v1/edges` | `EdgeController#upsert` | `EdgeRequest` | `ResponseEntity<WriteResult>` |
| `DELETE` | `/api/v1/edges/{edgeId}` | `EdgeController#delete` | — | `ResponseEntity<WriteResult>` |
| `GET` | `/api/v1/fuzzy/demo` | `FuzzyController#demo` | — | `Map<String, Object>` |
| `GET` | `/api/v1/fuzzy/functions` | `FuzzyController#functions` | — | `List<Map<String, Object>>` |
| `POST` | `/api/v1/fuzzy/graphs/{graph}/links` | `FuzzyController#link` | `FuzzyPreviewRequest` | `Map<String, Object>` |
| `POST` | `/api/v1/fuzzy/graphs/{graph}/plan` | `FuzzyController#plan` | `FuzzyPreviewRequest` | `Map<String, Object>` |
| `GET` | `/api/v1/fuzzy/patterns` | `FuzzyController#patterns` | — | `Map<String, Object>` |
| `POST` | `/api/v1/fuzzy/patterns` | `FuzzyController#save` | `FuzzyPatternRequest` | `Map<String, Object>` |
| `DELETE` | `/api/v1/fuzzy/patterns/{graph}/{id}` | `FuzzyController#remove` | — | `ResponseEntity<Map<String, Object>>` |
| `POST` | `/api/v1/fuzzy/validate` | `FuzzyController#validate` | `FuzzyPreviewRequest` | `Map<String, Object>` |
| `GET` | `/api/v1/graphs` | `SchemaController#graphs` | — | `List<GraphInfo>` |
| `POST` | `/api/v1/graphs/{graph}/batch` | `GraphWriteController#batch` | `BatchRequest` | `List<WriteResult>` |
| `POST` | `/api/v1/graphs/{graph}/edges` | `GraphWriteController#upsertEdge` | `EdgeRequest` | `ResponseEntity<WriteResult>` |
| `DELETE` | `/api/v1/graphs/{graph}/edges/{edgeId}` | `GraphWriteController#deleteEdge` | — | `ResponseEntity<WriteResult>` |
| `POST` | `/api/v1/graphs/{graph}/vertices` | `GraphWriteController#upsertVertex` | `VertexRequest` | `ResponseEntity<WriteResult>` |
| `DELETE` | `/api/v1/graphs/{graph}/vertices/{id}` | `GraphWriteController#deleteVertex` | — | `ResponseEntity<WriteResult>` |
| `GET` | `/api/v1/health` | `BatchController#health` | — | `String` |
| `GET` | `/api/v1/metrics` | `MetricsController#all` | — | `Map<String, Object>` |
| `GET` | `/api/v1/metrics/prometheus` | `MetricsController#prometheus` | — | `String` |
| `GET` | `/api/v1/metrics/{graph}` | `MetricsController#one` | — | `ResponseEntity<Map<String, Object>>` |
| `POST` | `/api/v1/metrics/{graph}/flush` | `MetricsController#flush` | — | `Map<String, Object>` |
| `GET` | `/api/v1/metrics/{graph}/prometheus` | `MetricsController#prometheusOne` | — | `ResponseEntity<String>` |
| `POST` | `/api/v1/metrics/{graph}/reset` | `MetricsController#reset` | — | `Map<String, Object>` |
| `POST` | `/api/v1/query` | `QueryController#query` | `QueryRequest` | `Object` |
| `POST` | `/api/v1/schema` | `SchemaAuthoringController#save` | `SchemaDraft` | `ResponseEntity<Map<String, Object>>` |
| `GET` | `/api/v1/schema/storage-types` | `SchemaAuthoringController#storageTypes` | — | `List<Map<String, Object>>` |
| `POST` | `/api/v1/schema/validate` | `SchemaAuthoringController#validate` | `SchemaDraft` | `Map<String, Object>` |
| `GET` | `/api/v1/schema/{graph}` | `SchemaAuthoringController#json` | — | `ResponseEntity<String>` |
| `GET` | `/api/v1/schema/{graph}/draft` | `SchemaAuthoringController#draft` | — | `ResponseEntity<SchemaDraft>` |
| `GET` | `/api/v1/vector-index` | `VectorIndexController#list` | — | `List<VectorIndexInfo>` |
| `POST` | `/api/v1/vector-index/rebuild` | `VectorIndexController#rebuild` | — | `ResponseEntity<List<VectorIndexRebuildResult>>` |
| `POST` | `/api/v1/vertices` | `VertexController#upsert` | `VertexRequest` | `ResponseEntity<WriteResult>` |
| `DELETE` | `/api/v1/vertices/{id}` | `VertexController#delete` | — | `ResponseEntity<WriteResult>` |
| `GET` | `/api/v1/vertices/{id}` | `VertexController#get` | — | `ResponseEntity<StoredVertex>` |
| `DELETE` | `/api/v1/vertices/{id}/properties/{index}` | `VertexController#deleteProperty` | — | `ResponseEntity<WriteResult>` |
| `PUT` | `/api/v1/vertices/{id}/properties/{index}` | `VertexController#updateProperty` | `PropertyUpdateRequest` | `ResponseEntity<WriteResult>` |

48 endpoints across 11 controllers.
<!-- endpoints:end -->

---

## 8. What is deliberately *not* in this reference

- **A schema/OpenAPI document.** The table above is generated from the controllers by
  `tools/api-endpoints.mjs --check` (CI fails if it drifts), which covers the paths and the request bodies;
  generating a full OpenAPI document is on the M2 list, and until then a machine-readable contract is
  whatever this page and `verification/verify-api-errors.mjs` assert.
- **Pagination.** Answers are bounded by the query (`limit`, `range`, the adaptive batch size), not by a
  cursor: the engine returns the traversers the query asked for, and there is no server-side result set to
  page through. If a client needs streaming over a huge answer, that is an M3-sized feature.
- **Rate limits per API key**, quotas per tenant, and audit trails - M2-2.
