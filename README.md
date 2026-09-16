# PhoenixGraph

A High Scaled and High Performance HTAP Graph Database.

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

A vector dataset is stored as `Vector` vertices (a `vector` property) plus one
bidirectional edge class per HNSW layer (`vector_0` .. `vector_<ceil(log2 n)>`).
The query engine exposes the layered search as a Gremlin step:

```groovy
g.V('4242', 'Vector').nearest(20)          // 20 nearest vectors of that id
g.V('4242', 'Vector').nearest(20, 200)     // wider candidate list (more accurate)
g.V('4242', 'Vector').nearest(20).values('name')
```

See `CONTEXT.md` (section "HNSW vector index") for the design, the dataset and
the measured recall, and `tools/download-siftsmall-dataset.sh` +
`HnswGraphIntegrationTest` to build the demo index.

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
