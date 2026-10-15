# HTTP API reference (`/api/v1`)

Everything this product does is reachable over HTTP: the query engine, the writes, the schema authoring, the
fuzzy patterns, the vector indexes, the metrics and the agent toolkit. The UI on 8080 and the CLI tools talk
to the same endpoints - there is no private back door.

> **Authentication is implemented but off by default** (`phoenixgraph.security.enabled=false`), so a local
> `docker compose up`, the UI and the verification suite work without ceremony. **Turn it on before the port
> is reachable by anyone else**, and put TLS in front of it or configure `server.ssl.*` (`M2-3`). On an open
> instance every endpoint is reachable and an error response carries a Java stack trace.

---

## 1. Request id

Every `/api` response carries `X-Request-Id`: the value the caller sent, or an 8-character id the server
generated. The same id is on the server's log line for that request, so "this query failed at 14:02" is one
grep away from the statement, the graph and the latency.

```bash
curl -sD- -o /dev/null -H 'X-Request-Id: my-trace-42' localhost:8081/api/v1/graphs | grep -i x-request-id
# X-Request-Id: my-trace-42
```

## 2. Authentication, roles and the audit trail

With `phoenixgraph.security.enabled=true` every `/api` request needs a key:

```bash
curl -s -H 'Authorization: Bearer $PG_KEY' localhost:8081/api/v1/graphs
# or:  -H "X-API-Key: $PG_KEY"
```

A key is configured with a name (what the audit log calls it), the token **or its SHA-256**, a role and
optionally the graphs it may name:

```yaml
phoenixgraph:
  security:
    enabled: true
    keys:
      - name: dashboard            # token in clear: fine for a dev instance
        token: ${PHOENIXGRAPH_DASHBOARD_TOKEN}
        role: writer
        graphs: [payment-graph, payment-graph-rocksdb-edge]
      - name: ops                  # or never store the token: give its digest
        sha256: ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad   # printf %s abc | sha256sum
        role: admin
```

Roles are a ladder - **reader < writer < admin**:

| Role | May |
|---|---|
| `reader` | read and **query** (a query is a POST that only reads), preview fuzzy patterns, call the agent tools |
| `writer` | everything a reader may, plus vertices/edges/batch writes and fuzzy links |
| `admin` | everything a writer may, plus schema authoring, `/api/v1/agent/ingest`, index rebuilds, metric resets and the **audit view** |

An empty `graphs` list means every graph; a non-empty one is enforced for reads and writes alike.

| Status | When |
|---|---|
| `401` + `WWW-Authenticate: Bearer` | no key, or an unknown one - the caller can fix it |
| `403` | the key is valid but the role or the graph allowlist says no; the message names the rule |

**Audit.** Every call is recorded - key name, role, method, path, graph, query (clipped to 200 characters),
status and latency - in two places: the logger `com.pengcheng.phoenixgraph.audit` (route it to your own file
or collector) and a bounded in-memory ring buffer of the last 2,000 calls, which
`GET /api/v1/audit/recent?limit=50` returns (admin key). Refused calls are recorded too: that is exactly what
an audit is for. **A token is never written to either.**

```bash
curl -s -H "Authorization: Bearer $PG_ADMIN_KEY" localhost:8081/api/v1/audit/recent?limit=5
```

**TLS.** Off by default (plain HTTP, or terminate TLS in front of the service). To serve HTTPS from the
process itself, give it a keystore and the password from the environment:

```bash
keytool -genkeypair -alias phoenixgraph -keyalg RSA -keysize 2048 -storetype PKCS12   -keystore phoenixgraph.p12 -storepass "$PHOENIXGRAPH_TLS_PASSWORD"   -dname "CN=localhost" -validity 365 -ext SAN=dns:localhost,ip:127.0.0.1

PHOENIXGRAPH_TLS_PASSWORD=... java -jar graph-http.jar   --server.ssl.enabled=true --server.ssl.key-store=/etc/phoenixgraph/phoenixgraph.p12   --server.ssl.key-store-type=PKCS12 --server.ssl.key-alias=phoenixgraph   --server.ssl.key-store-password=${PHOENIXGRAPH_TLS_PASSWORD}

curl --cacert phoenixgraph.crt https://localhost:8081/api/v1/graphs
```

A plain-HTTP request to a TLS port is refused, certificate verification stays on (a self-signed certificate
must be trusted by the client, or replaced by a real one), and a wrong keystore password stops the process
instead of serving something else. `verification/verify-tls.mjs` checks those four things against a generated
keystore.

## 3. Limits and the status codes a client must handle

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
| `max-body-bytes` | `1048576` | largest request body; a bigger one is a `413` before anything is parsed |
| `max-query-length` | `65536` | largest query text in characters; a bigger one is a `400` |

| Status | When | What a client should do |
|---|---|---|
| `200` | normal | — |
| `400` | malformed query, unknown graph, bad parameter, body that is not JSON, a query over `max-query-length`, a value that is not the declared type, an index the schema does not declare | fix the request; the message names the field and what it should be |
| `404` | unknown path; an operation naming a vertex, edge or element that is not in the graph | fix the path or the id (see the table below) |
| `405` + `Allow` | known path, wrong method | use the method in `Allow` |
| `409` | the request is fine but clashes with the state - a checkpoint target that already exists | choose another name, or delete the existing one |
| `413` | the body is over `max-body-bytes` (`{"error":"The request body is N bytes, over the limit of M"}`) | send less, or raise the limit |
| `415` | a body in a media type the endpoint does not take | send `application/json` |
| `429` + `Retry-After` | the per-second budget of that graph is spent | back off for `Retry-After`; this is *not* a bug |
| `500` | an unexpected server-side failure (a bug); also a write the store refused after its own retries (`{"status":"FAILED"}`) | report it with the `X-Request-Id` |
| `503` + `Retry-After` | the instance (or that graph) is at its concurrency limit; **or** the query exceeded `request-timeout`; **or** the storage of that graph did not answer (unreachable, too slow, no partition map) | retry after `Retry-After`; for the storage case `GET /api/v1/health` still says `200` because the *instance* is alive (see below) |

**Errors have one shape**, and a stack trace is not part of it:

```json
{ "error": "what is wrong, in a sentence",
  "exception": "com.pengcheng.phoenixgraph...",
  "hint": "what to change or where to look" }
```

`phoenixgraph.errors.include-stack-trace=true` adds a `stackTrace` array for a debugging instance. It is off by
default because a stack trace hands absolute paths, library versions and the shape of the code to anyone
probing the service, and a client cannot act on the frames; the `500` body names the exception class and the
`X-Request-Id` instead, which is what makes it reportable. The one place the container answers instead of the
application is a request Tomcat refuses before it reaches the API (an encoded slash in a path, an illegal
header): the status is still right, the body is the container's HTML page, and there is nothing the
application can do about it.

**A degraded node keeps serving.** If a graph's storage cannot be reached, the instance still starts, still
answers `/api/v1/health` (`200`: the process is alive), still lists its graphs and still serves the graphs
whose storage works - the RocksDB ones. Requests against the unreachable graph come back as `503` with
`Retry-After`, and the client is reconnected automatically when the store returns. This is deliberate: a node
that refuses to boot because one store is down turns a degraded deployment into an outage, and in a cluster it
is a crash loop. A write against the unreachable store is the exception to the tidy story: the write path
retries internally and then answers `500` with `{"status":"FAILED"}`, so a client retry usually will not
help - the store needs to come back first (`docs/OPERATIONS.md`).

The timeout is cooperative and its boundary is deliberate: the engine checks the budget **between steps**, so
a single very expensive step (one hop over a huge fan-out, or a whole `pagerank()`) finishes before the check
fires. The outer bound for a single storage call is the storage client's own timeout (1 s).

```bash
# a refusal is an error body with a hint, never an empty response
curl -s -X POST localhost:8081/api/v1/query -H 'content-type: application/json' \
  -d '{"graphName":"nope","query":"g.V()"}'
# {"error":"Unknown graph 'nope'; available graphs: agent-demo, ...",
#  "exception":"com.pengcheng.phoenixgraph.query.QueryException",
#  "hint":"Check the Gremlin query syntax and the request parameters"}
```

## 4. Querying

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

## 5. Writes

`POST /api/v1/graphs/{graph}/vertices`, `POST /api/v1/graphs/{graph}/edges`, `DELETE .../vertices/{id}`,
`DELETE .../edges/{edgeId}`, plus `POST /api/v1/{vertices,edges,batch}` for the primary graph and
`POST /api/v1/graphs/{graph}/batch` for any graph. A write answers `{"id": ..., "status": "SUCCESS"}`.

**The write shape, as verified:**

```bash
# a vertex: the property index is the *schema's* property id (they start at 1; 0 is the id property)
curl -s -X POST localhost:8081/api/v1/vertices -H 'content-type: application/json' -d '{
  "id": "1000", "vertexSchemaName": "Account",
  "properties": [{"index": 1, "value": "ACTIVE"}, {"index": 2, "value": "SAVINGS"}] }'
# {"id":"1000","status":"SUCCESS"}

# an edge: edgeId is required, and the endpoints are source/destination (not source/target)
curl -s -X POST localhost:8081/api/v1/edges -H 'content-type: application/json' -d '{
  "edgeId": "e-1", "edgeSchemaName": "Payment", "source": "1000", "destination": "1001",
  "properties": [] }'
# {"id":"e-1","status":"SUCCESS"}

# deleting an edge needs the schema name as a query parameter
curl -s -X DELETE "localhost:8081/api/v1/edges/e-1?edgeSchemaName=Payment"
```

An enum property is validated on write, and the error lists the allowed values
(`accountStatus value 'CHECKING' is not one of the allowed enum values [FROZEN, ACTIVE, CLOSED]`) - worth
knowing before writing a fixture of your own.

**What a write refuses** (each of these used to be accepted, or to arrive as a 500 from somewhere deeper -
they are the M3-wide input contract, checked by `verification/verify-input-contract.mjs`):

| The request | The answer | Why |
|---|---|---|
| no `id` / no `edgeId` / no `source` / no `destination` | `400` naming the field | it was a `NullPointerException` -> `500` |
| a property `index` the schema does not declare | `400` listing the declared ones | it was stored as an extra bin that `elementMap()` then showed |
| a value that is not the declared type (`{}` for a `STRING`) | `400` naming the type | the store writes whatever it is given |
| a value over `phoenixgraph.write.max-property-value-bytes` (512 KiB) | `400` naming the limit, without echoing the value | the store would fail on its own record limit |
| an edge whose `source` or `destination` vertex does not exist | `404` naming the missing endpoint | the store created an **untyped stub vertex**, which then appeared in scans; set `phoenixgraph.write.require-existing-endpoints=false` for a loader that imports edges first |
| a property update or delete, or a vertex delete, on an id that is not there | `404` | `200 {"status":"SUCCESS"}` for a vertex that never existed hides the typo |

A property of an id the schema types differently is still accepted (the id property is the record key spelled
out, and the demo data writes `"135"` for an id schema that says `LONG`); the id itself is `Object` in the API
and is stored as the key.

Three semantics worth knowing before you build on them:

- **A delete is a marker, not a removal**, and reads hide the vertex from then on: point reads, batch reads,
  scans and subgraph expansion all skip it. (They did not until 0.2.0 - a backup drill wrote a vertex, deleted
  it, restarted the service and found it still there; `docs/DATA-QUALITY.md` §8.) The record itself stays,
  which is deliberate: that is how a delete stays cheap and how a late-arriving write cannot resurrect an
  older one. **A restore has to overwrite it** - see `docs/OPERATIONS.md` §4 for the two `asrestore` flags
  that are needed for that.
- **Writes need a keyed store.** `POST /api/v1/graphs/{graph}/vertices` answers with a message when the graph
  is a RocksDB scheme ("writes go through the primary graph or a loader"): a RocksDB graph is loaded, not
  written over REST.
- **`SUCCESS_STALE` means "nothing newer to write"**, not "your write failed": every property you sent was
  older than the stored copy (or you sent none), so the record already holds the newest state. It is a `200`.
  A vertex written twice with the same empty property list is therefore `SUCCESS_STALE` the second time, which
  is what the multi-instance check asserts.
- **Writes go to the graph's own store.** A graph served from a read-only scheme accepts the request and
  rejects the write with a message naming the scheme, rather than silently writing somewhere else.

## 6. The agent toolkit

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

## 7. Backups, and where a graph lives

- `GET /api/v1/graphs/{graph}/storage` - the Aerospike set or the RocksDB directory of a graph, and which tool
  backs it up (an operator, or `tools/backup-graph.sh`, needs this and should not have to read a schema)
- `POST /api/v1/graphs/{graph}/checkpoint?dir=<path>` - a consistent, hard-linked snapshot of a RocksDB graph's
  directory, taken while the service keeps serving. The target must not exist. Restoring is "point a service at
  the copy" (`docs/OPERATIONS.md` §4). An Aerospike graph answers with the `asbackup` command it needs instead
  of pretending to have a directory.

## 8. Metrics

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

## 9. Schema files are versioned

Every schema file carries `"schemaVersion"` (currently **1**): written by this build, assumed to be 1 when a
file predates the field, and **refused by name** when it comes from a newer build - reading such a file "as far
as possible" would drop whatever the newer build added, and a graph that looks fine and behaves differently is
the worst outcome of a migration.

```bash
node tools/schema-version.mjs --check graph-http/src/main/resources   # exit 1 if a file is unstamped
node tools/schema-version.mjs --write <file-or-directory>             # stamp it with the version this build writes
node tools/schema-version.mjs --dry-run <file-or-directory>           # what --write would change
```

The version it stamps comes from the running service (`POST /api/v1/schema/version` takes a file as text and
answers with `declaredVersion`, `supportedThrough` and a `problem` when it refuses), so a tool and an engine
cannot disagree about the format.

## 10. Endpoints

<!-- endpoints:start -->
<!-- generated by tools/api-endpoints.mjs from 15 controllers - do not edit by hand -->

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
| `GET` | `/api/v1/audit/recent` | `AuditController#recent` | — | `Map<String, Object>` |
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
| `POST` | `/api/v1/graphs/{graph}/checkpoint` | `BackupController#checkpoint` | — | `Map<String, Object>` |
| `POST` | `/api/v1/graphs/{graph}/edges` | `GraphWriteController#upsertEdge` | `EdgeRequest` | `ResponseEntity<WriteResult>` |
| `DELETE` | `/api/v1/graphs/{graph}/edges/{edgeId}` | `GraphWriteController#deleteEdge` | — | `ResponseEntity<WriteResult>` |
| `GET` | `/api/v1/graphs/{graph}/storage` | `BackupController#storage` | — | `Map<String, Object>` |
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
| `POST` | `/api/v1/schema/version` | `SchemaAuthoringController#version` | `String` | `Map<String, Object>` |
| `GET` | `/api/v1/schema/{graph}` | `SchemaAuthoringController#json` | — | `ResponseEntity<String>` |
| `GET` | `/api/v1/schema/{graph}/draft` | `SchemaAuthoringController#draft` | — | `ResponseEntity<SchemaDraft>` |
| `GET` | `/api/v1/semantic/{graph}/model` | `SemanticController#model` | — | `Map<String, Object>` |
| `POST` | `/api/v1/semantic/{graph}/translate` | `SemanticController#translate` | `SemanticRequest` | `Map<String, Object>` |
| `POST` | `/api/v1/semantic/{graph}/validate` | `SemanticController#validate` | `SemanticRequest` | `Map<String, Object>` |
| `GET` | `/api/v1/vector-index` | `VectorIndexController#list` | — | `List<VectorIndexInfo>` |
| `POST` | `/api/v1/vector-index/rebuild` | `VectorIndexController#rebuild` | — | `ResponseEntity<List<VectorIndexRebuildResult>>` |
| `POST` | `/api/v1/vertices` | `VertexController#upsert` | `VertexRequest` | `ResponseEntity<WriteResult>` |
| `DELETE` | `/api/v1/vertices/{id}` | `VertexController#delete` | — | `ResponseEntity<WriteResult>` |
| `GET` | `/api/v1/vertices/{id}` | `VertexController#get` | — | `ResponseEntity<StoredVertex>` |
| `DELETE` | `/api/v1/vertices/{id}/properties/{index}` | `VertexController#deleteProperty` | — | `ResponseEntity<WriteResult>` |
| `PUT` | `/api/v1/vertices/{id}/properties/{index}` | `VertexController#updateProperty` | `PropertyUpdateRequest` | `ResponseEntity<WriteResult>` |

55 endpoints across 15 controllers.
<!-- endpoints:end -->

---

## 11. What is deliberately *not* in this reference

- **A schema/OpenAPI document.** The table above is generated from the controllers by
  `tools/api-endpoints.mjs --check` (CI fails if it drifts), which covers the paths and the request bodies;
  generating a full OpenAPI document is on the M2 list, and until then a machine-readable contract is
  whatever this page and `verification/verify-api-errors.mjs` assert.
- **Pagination.** Answers are bounded by the query (`limit`, `range`, the adaptive batch size), not by a
  cursor: the engine returns the traversers the query asked for, and there is no server-side result set to
  page through. If a client needs streaming over a huge answer, that is an M3-sized feature.
- **Rate limits per API key**, quotas per tenant, and an audit trail that survives a restart (today: a
  logger plus a bounded in-memory buffer) - later M2 work.
