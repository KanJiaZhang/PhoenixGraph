# PhoenixGraph

A High Scaled and High Performance HTAP Graph Database.

> **Resuming work?** Start with [HANDOVER.md](HANDOVER.md) - current state, how
> to start the services and what is next.

## Modules

| Module                | Description                                                     |
| --------------------- | --------------------------------------------------------------- |
| `graph-core`          | Core graph abstractions: graph, vertex, edge and exceptions     |
| `graph-schema`        | Schema model: labels, property keys, data types, cardinalities  |
| `graph-storage`       | Storage engine abstraction + in-memory storage implementation   |
| `graph-storage-aerospike` | Aerospike storage: vertices and denormalised edges in one record per vertex, per-property CAS |
| `graph-storage-rocksdb` | Shared RocksDB plumbing: key layout (fixed-width hash + id), 512-region key space, column families, property codec, vertex tables |
| `graph-storage-rocksdb-inline` | RocksDB-Inline storage: one table per vertex type, edges folded into one table per edge type, degree limits enforced |
| `graph-storage-rocksdb-edge` | RocksDB-Edge storage: one table per vertex type plus an edge table per edge class, creation-time window scans, no degree limit |
| `graph-query`  | Query parsing, planning and execution                           |
| `graph-query-gremlin` | Gremlin-style traversal engine (`out/in/both`, `bothE`, `filter`, `nearest`, ...) |
| `graph-query-cypher`  | openCypher subset over the same query ports (`MATCH` / `WHERE` / `RETURN` + aggregation) |
| `graph-hnsw`          | HNSW vector index: layered graph build + nearest-neighbour search |
| `graph-compute-engine`| Graph analytics and algorithm execution (BFS, PageRank, ...)    |
| `graph-web`           | RESTful web service (Spring Boot)                               |

### Storage implementations

The schema decides where a graph lives, so the same graph definition can be served by any
backend:

```json
"storage": { "type": "RocksDB-Inline", "path": "payment-graph-rocksdb-inline" }
```

| `storage.type` | Layout | Degree limits |
| --- | --- | --- |
| `Aerospike` (**default**) | one record per vertex holding its properties and its denormalised edges (one bin per edge class and direction) | enforced with per-property CAS on `lastUpdatedTime` |
| `RocksDB-Inline` | one table per vertex type; one table per edge type holding, per vertex, every edge of that class and direction (Aerospike bin names kept) | enforced: the update reads the edge list, merges by `lastUpdatedTime` and evicts the oldest beyond `maxOutDegree`/`maxInDegree`/`maxDegree` |
| `RocksDB-Edge` | one table per vertex type; one edge table per edge class keyed by `hash(vertexId):direction:vertexId:createdTime:edgeId` | none: a hub may have any degree, and a creation-time window is a range scan |

Both RocksDB schemes implement the same `GraphQuery` port as Aerospike, so Gremlin and Cypher
run unchanged on all three, with the same batched/parallel reads, on-demand property projection
and caching.

```bash
# load the demo payment graph into both RocksDB instances
./mvnw -pl graph-integration-test -am test -Dtest=RocksDbLiveLoadTest \
       -Drocksdb.live.dir=$PWD/.rocksdb -Dsurefire.failIfNoSpecifiedTests=false
# serve Aerospike + both RocksDB instances (default application.yml), or only RocksDB
java -jar graph-http/target/graph-http-*.jar
java -jar graph-http/target/graph-http-*.jar --spring.profiles.active=rocksdb
```

### Two query languages

The same graph answers Gremlin and openCypher. The HTTP API picks one with `language`,
and both take a query template plus parameters (`${name}` text placeholders, and native
`$name` references for Cypher):

```bash
curl -s -X POST http://localhost:8081/api/v1/query -H 'content-type: application/json' -d '{
  "graphName": "payment-graph", "language": "cypher",
  "query": "MATCH (a:Account {id: $id})-[p:Payment]->(b:Account) RETURN b.id, p.status LIMIT 5",
  "params": { "id": "135" }, "returnSubgraph": true }'
```

Supported Cypher: one or more `MATCH` patterns (directed, undirected or variable length,
with labels and inline properties), `WHERE` with comparisons, `AND/OR/NOT`, `IN`,
`IS [NOT] NULL` and string predicates, `RETURN [DISTINCT]` with
`count/sum/avg/min/max/collect`, `ORDER BY`, `SKIP` and `LIMIT`. Writes stay in the REST
write API, and a `MATCH` always needs a starting vertex with an id (the storage has no
label index, so nothing is scanned). The web UI has a language picker, an example picker
and a rows table. See `CONTEXT.md` section 19.

### Vector search in one query

Flag a property `"isVector": true` and the graph does the rest: writes index the
embedding automatically, the HNSW layer edge classes (`<property>_0`, one more per
doubling of the data) are created on demand, and the query engine exposes the
layered search as a Gremlin step:

```json
{ "id": 2, "name": "embedding", "type": { "list": "DOUBLE" },
  "isVector": true, "metric": "COSINE", "m": 15,
  "efConstruction": 100, "efSearch": 30 }
```

```groovy
g.V('4242', 'Vector').nearest(20)          // 20 nearest vectors of that id
g.V('4242', 'Vector').nearest(20, 200)     // wider candidate list (more accurate)
g.V('4242', 'Vector').nearest(20).values('name')
g.V('4242', 'Vector').nearest(20).has('bucket', 3)   // the filter is pushed into the search
```

See `CONTEXT.md` ("HNSW vector index" and the schema-driven index section) for the
design, the dataset and the measured recall, and
`tools/download-siftsmall-dataset.sh` + `HnswGraphIntegrationTest` to build the
demo index.

## Requirements

- JDK 21+ (the project compiles with `--release 21`)
- Maven 3.9+ (or use the included Maven wrapper: `./mvnw`)

## Build

```bash
./mvnw clean package
```

On Windows (cmd/PowerShell):

```bat
mvnw.cmd clean package
```

## Run the web service

```bash
./mvnw -pl graph-web spring-boot:run
```

Then open <http://localhost:8080/api/v1/graph/health>.
