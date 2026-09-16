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
| `graph-hnsw`          | HNSW vector index: layered graph build + nearest-neighbour search |
| `graph-compute-engine`| Graph analytics and algorithm execution (BFS, PageRank, ...)    |
| `graph-web`           | RESTful web service (Spring Boot)                               |

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
