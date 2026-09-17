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
| `graph-query`  | Query parsing, planning and execution                           |
| `graph-query-gremlin` | Gremlin-style traversal engine (`out/in/both`, `bothE`, `filter`, `nearest`, ...) |
| `graph-query-cypher`  | openCypher subset over the same query ports (`MATCH` / `WHERE` / `RETURN` + aggregation) |
| `graph-hnsw`          | HNSW vector index: layered graph build + nearest-neighbour search |
| `graph-compute-engine`| Graph analytics and algorithm execution (BFS, PageRank, ...)    |
| `graph-web`           | RESTful web service (Spring Boot)                               |

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
