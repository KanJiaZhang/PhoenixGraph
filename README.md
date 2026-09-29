# PhoenixGraph

A high-scale, high-performance **HTAP graph database** that puts **graph traversal, vector search and
geo search** behind one query, and serves **knowledge graphs as context for AI agents**.

## What PhoenixGraph is

- **AI context graphs.** A knowledge graph is what an LLM needs to answer accurately: typed entities,
  typed relations with provenance, and question-shaped query templates. The worked example in this
  repository is the 《三国演义》 (Romance of the Three Kingdoms) graph - one unified schema serves a
  whole domain, and a query answers "关羽 and 诸葛亮: how are they related?" from the graph instead of
  from the model's memory alone.
- **One query over graph, vector and geo.** A vector hit and a geo hit are ordinary traversers, so
  "the five entities nearest me, their lookalikes, and what they are connected to" is one traversal,
  not three round trips from an application. See [below](#one-query-over-vector-geo-and-the-graph).
- **HTAP: OLTP on Aerospike, OLAP on RocksDB.** The transactional side answers a request by key in one
  record (or one batch); the analytical side scans a class in ordered tables, runs graph algorithms
  in-process or on a Gemini cluster. The backend is a property of the **schema**, so the same graph
  definition, the same Gremlin, the same openCypher and the same HTTP API are served by any scheme.
- **High performance and high QPS.** Keyed reads never scan; a hop reads its edges in one batched,
  cached, projected round trip; a compiled query is cached; a set of seeds is read in parallel; an
  attribute condition and a degree cap are pushed into the storage decode. Measured on the demo graph:
  a one-hop query p50 **0.54-3.57 ms** (median 0.9 ms), two hops **1.22-5.16 ms** (median 2.3 ms)
  (`AerospikeHopQueryLatencyTest`).
- **Visualization.** `graph-web` renders the induced subgraph a query returned - the engine returns it
  with full properties for exactly this - colours it with graph algorithms, and edits schemas and fuzzy
  patterns in a builder page.
- **Fraud rings from fragments.** `fuzzyLink(patternId, context)` turns the small details several
  accounts share (an email provider, an IP /16, a card BIN, a user-name *shape*) into a link node, so a
  query walks from the pattern to the ring (`graph-fuzzy` holds the pattern language and the
  deterministic ids). A pattern is data (`fuzzy-patterns.json`), editable while the service runs.

### One query over vector, geo and the graph

![vector, geo and graph in one query](docs/screenshots/vector-geo-graph.png)

Every account in the payment demo carries a profile (`accountVector`, a vector property) and a home
location (`location`, a geo property); the smart graph (`payment-graph-smart` /
`payment-graph-smart-rocksdb`) has the same two properties on `Account` and `Merchant`. Because a
vector hit and a geo hit are ordinary traversers, the three kinds of search compose in one query:

```groovy
// the five accounts nearest me (geo), each one's two most similar accounts (vector), and
// what those accounts paid (graph)
g.V(1001,'Account')
    .circle('{"latitude":31.2304,"longitude":121.4737}', 8000).limit(5)   // geo: the five nearest
    .nearest(2)                                                        // vector: their lookalikes
    .outE('Payment').otherV().dedup().limit(60)                        // graph: where the money went
```

- `circle(point, radiusMetres)` is an **S2 range scan**, nearest first, with `distanceMeters` attached
  to each hit; `has(...)`/`limit(...)` compose with it.
- `nearest(k[, ef])` is an **HNSW search**; a `has`/`filter` right after it is pushed into the search,
  so it answers the k nearest that *match*, not the k nearest of which some happen to match. On the
  payment demo the HNSW layer edges (`accountVector_0`, one more per doubling of the data) are part of
  the graph, so the search runs on any storage; the smart graph instead uses the index the write path
  maintains (Aerospike's HNSW, built with `POST /api/v1/vector-index/rebuild`).
- Every other step (`out/in/both`, `outE/inE/bothE`, filters, aggregations, algorithms) walks the
  result, so the three kinds of search are steps of one pipeline rather than three systems.

### A knowledge graph as the context an LLM answers from

![the knowledge graph around a question](docs/screenshots/knowledge-graph-context.png)

The Three Kingdoms graph is served in three forms (`sanguo-kg` on Aerospike, `sanguo-kg-rocksdb-edge`
and `sanguo-kg-text` on RocksDB-Edge) and one traversal hands an agent the **evidence** around the
entities of a question:

```groovy
// the neighbourhood of the entities in the question, as context: relations, offices, battles,
// and the chapters the mentions came from (the edges carry relation + provenance)
g.V('关羽','Entity').bothE().otherV().dedup().limit(24)
    .bothE().otherV().dedup().limit(60)
```

What makes the graph usable as context:

- **One unified schema** for the domain: a typed `Entity` vertex and one relation edge class whose
  edges carry `relation`, `xrelation`, `relationLabel` and `relationFamily`. "Who is whose lord /
  minister / advisor" is a filter on an edge property, not a different edge class per question -
  which is why a new relation does not need a schema change.
- **From RDF and from free text.** `SANGUO-KG.md` documents the document-to-RDF mapping; `sanguo-kg-text`
  adds entities and relations extracted from the documents' running text by a dictionary plus regular
  expressions (no model, fully reproducible), with the sentence each `mentions` edge came from, so an
  answer can point back at its evidence. `SANGUO-KG-TEXT-EXTRACTION.md` lists every rule.
- **Question-shaped templates.** `SANGUO-KG-QUERIES.md` is a handbook of 22 question/answer queries
  ("关羽 和 诸葛亮 是什么关系？", "谁是五虎上将？", two- and three-hop relation paths) plus the Cypher
  equivalent of each, ready to be parameterised by a retrieval or agent layer.
- **Two engines, one answer.** The same graph on Aerospike and on RocksDB answers the shared questions
  identically (`SanguoRocksDbEdgeTest#matchesTheAerospikeGraphOnTheSharedQuestions`).

## Install and run

### Requirements

- JDK 21+ (the project compiles with `--release 21`)
- Maven 3.9+ (or the included wrapper: `./mvnw`)
- Docker (for Aerospike, and for the container images)

### Build

```bash
./mvnw clean package
```

### Run the services

```bash
tools/start-phoenixgraph.sh                    # build, then start graph-http (8081) + graph-web (8080)
tools/start-phoenixgraph.sh --no-build         # start what is already built
tools/start-phoenixgraph.sh --with-hnsw        # also the write-driven vector instance (8083)
tools/stop-phoenixgraph.sh
```

Then open <http://localhost:8080> for the UI and <http://localhost:8081/api/v1/graphs> for the API. The
start script stops any running instance first (a live service holds its jar open), builds `graph-http` +
`graph-web` and their dependencies, starts the Aerospike container if needed, launches the services
detached and waits until each answers. Logs are written to `.logs/<service>.out`.

### Run it all in Docker

```bash
docker compose up --build                 # Aerospike + graph-http (8081) + graph-web (8080)
docker compose --profile hnsw up --build  # also the write-driven vector instance (8083)
docker compose down                       # add -v to drop the RocksDB volume too
```

Both images are multi-stage, run as a non-root user, have healthchecks (`/api/v1/graphs` for
`graph-http`, `/` for `graph-web`) and size the JVM from the container limit
(`JAVA_OPTS=-XX:MaxRAMPercentage=75`). `graph-web` waits for `graph-http` to be healthy. The image runs
with the `docker` Spring profile (`graph-http/src/main/resources/application-docker.yml`), which points
the storage host at the compose service and puts RocksDB under `/data/rocksdb` (a named volume).
Configuration is the ordinary environment: `PHOENIXGRAPH_STORAGE_HOST`, `PHOENIXGRAPH_STORAGE_PORT`,
`PHOENIXGRAPH_STORAGE_NAMESPACE`, `PHOENIXGRAPH_STORAGE_VERTEX_SET`,
`PHOENIXGRAPH_STORAGE_ROCKSDB_ROOT`.

The two images also run on their own:

```bash
docker build -f graph-http/Dockerfile -t phoenixgraph-http .
docker build -f graph-web/Dockerfile  -t phoenixgraph-web  .
docker run --rm -p 8081:8081 -e PHOENIXGRAPH_STORAGE_HOST=host.docker.internal phoenixgraph-http
docker run --rm -p 8080:8080 -e PHOENIXGRAPH_HTTP_URL=http://host.docker.internal:8081 phoenixgraph-web
```

Aerospike starts empty: seed the demo set (`http_demo_v3`) from the host with the integration-test
loader, or mount a loaded `.rocksdb` directory at `/data/rocksdb` for the analytical graphs (see
[Loading data](#loading-data)).

### Distributed, high-concurrency deployment

The algorithm steps and `g.V(DIST)` partitioned scans run on a **Gemini** cluster (after Gemini,
OSDI '16): the graph is cut into contiguous chunks, one per worker, with a dual edge representation,
and each superstep picks the cheaper direction (`sparse when activeEdges < |E| / 20`).

- **`DIST` means the distributed engine and nothing else.** With no cluster configured the step is an
  error, never a quiet fall back to the in-process implementation. `pagerank()` without the marker still
  runs in memory.
- **`g.V(DIST)` is a partitioned scan**: the key space is split into hash ranges, one range per mapper,
  each mapper runs its own `GremlinContext` and pushes the attribute condition, projection and hops
  next to the data; the reduce phase runs the whole-set steps (`order`, `limit`, `count`, ...) once.
  The split point is the key layout (`hash(id):id`), so a range is a contiguous scan.
- **Workers are processes with a port.** `graph-http` starts four on 19100-19103 by default
  (`phoenixgraph.gemini.workers`, `first-port`, `mode=local|process`); each worker answers on its own
  HTTP endpoint, and `GeminiWorkerMain` is the program a helper script or a YARN container starts.
  Messages between workers are batched per peer and buffered on arrival, so no two workers wait on
  each other.
- **High-concurrency serving.** One `graph-http` instance holds one engine per graph (with a compiled
  query cache), reads a batch of seeds in parallel (`parallelism`, default `min(8, CPU)`), keeps an
  adaptive batch size, and serves stateless HTTP requests, so it scales horizontally behind a load
  balancer; the storage (Aerospike, or RocksDB on a shared volume) is the only shared state.

```groovy
g.V('135','Account').bothE('Payment').otherV().pagerank(DIST, 10)   // 50 accounts, ~2.8 s incl. load
g.V('a1,a2,a3','Account').cc(DIST)                                  // connected components
g.V('a1,a2,a3','Account').louvain(DIST, 20)                         // communities
g.V(DIST,'Account').has('accountStatus','ACTIVE').count()           // a partitioned class scan
```

## Storage guide: OLTP on Aerospike, OLAP on RocksDB

### Where the schema says a graph lives

A graph's schema declares its storage, so the same definition can be served by any backend:

```json
"storage": { "type": "RocksDB-Edge", "path": "payment-graph-rocksdb-edge" }

// or, the default:
"storage": { "type": "Aerospike" }
```

| `storage.type` | Role | Layout | Degree limits |
| --- | --- | --- | --- |
| `Aerospike` (**default**) | OLTP: keyed reads and writes | one record per vertex: properties + denormalised edges (one bin per class and direction), per-property CAS on `lastUpdatedTime` | enforced with CAS |
| `Aerospike-Edge` | OLTP + aggregated hub reads | Aerospike, plus `Edge_Out`/`Edge_In` tables: a vertex's edges are chunked by creation time and indexed by further levels | none; read by a creation-time window |
| `RocksDB-Inline` | analytical, with degree limits | one table per vertex type, one per edge type, edges folded into the record | enforced: merge by `lastUpdatedTime`, evict the oldest beyond `maxOutDegree`/`maxInDegree`/`maxDegree` |
| `RocksDB-Edge` | analytical, no degree limit | one table per vertex type, one edge table per class keyed by `hash(vertexId):direction:vertexId:createdTime:edgeId` | none; a creation-time window is a range scan |

The engine asks the storage what it can do (`supportsScan`, `supportsEdgeReadPushdown`,
`supportsPartitionedScan`): either the storage pushes the work down or the traversal does it. A new
backend only implements the `GraphQuery` port.

### OLTP: keyed reads and writes on Aerospike

A request path names ids, so one record (or one batch) answers it and nothing scans. Writes go through
the REST write API (they are validated, indexed by the vector index, and counted by the metrics);
concurrent updates to the same property are safe under per-property CAS.

```groovy
g.V('135','Account').outE('Payment').limit(10).values('status')   // one record + its out bin
g.V('1001','Account').nearest(5).outE('Payment')                  // vector index + one hop
```

### OLAP: class scans and time windows on RocksDB

The analytical side answers a question that names a **class**: one iterator per table, capped,
projected and pushed into the decode.

```groovy
g.V('Account').has('accountStatus','ACTIVE').bothE('Payment').cc(DIST)
g.E('Payment').count()
g.V('9000001','Account').createdBetween('2025-06-01','2025-06-02').inE('Payment').limit(500)
```

A hub with hundreds of millions of edges lives in the aggregated tables and is read by walking the
levels of a time window; a query without a window is refused with a message that says to add
`createdBetween`. `g.E('Payment')` reads each edge once, with both endpoints (`otherV()` is the far
one). `scanLimit` bounds every class scan of an engine in one place.

### Building a schema

A schema is JSON (`graph-http/src/main/resources/*.json`) with vertex types, edge endpoints, property
types and the storage block. The UI's **builder page** (`builder.html`, the "图管理" button) authors
one: it edits vertices/edges/properties, picks the storage type and path, and previews the draft. The
same JSON can be generated with the tools under `tools/`.

### Loading data

Three ways, by scale:

```bash
# 1. the payment graphs (RocksDB-Inline + RocksDB-Edge) into a local .rocksdb directory
./mvnw -pl graph-integration-test -am test -Dtest=RocksDbLiveLoadTest \
       -Drocksdb.live.dir=$PWD/.rocksdb -Dsurefire.failIfNoSpecifiedTests=false

# 2. the other demo graphs, one loader each (all run from graph-integration-test)
#    SmartPaymentGraphLiveLoadTest  - the smart graph (vector + geo on Account/Merchant)
#    GeoMerchantDataLoader          - the geo-merchants graphs (240 merchants, S2 index)
#    SanguoGraphIntegrationTest     - the Three Kingdoms graph on Aerospike (11k points / 21k edges)
#    SanguoRocksDbLoader            - the Three Kingdoms graph on RocksDB-Edge
#    QueryApiIntegrationTest        - the Aerospike demo set http_demo_v3 (and the fuzzy rings)
./mvnw -pl graph-integration-test -am test -Dtest=SmartPaymentGraphLiveLoadTest -Dsurefire.failIfNoSpecifiedTests=false

# 3. bulk CSV through the batch loader (graph-batch-load: BatchLoadMain <csv-file>)
```

For a large analytical import, a loader writes straight into the ordered tables (the same layout the
engine scans); the write API is for the transactional, request-driven path.

### Querying

One HTTP endpoint serves both languages and both storage roles:

```bash
curl -s -X POST http://localhost:8081/api/v1/query -H 'content-type: application/json' -d '{
  "graphName": "payment-graph-rocksdb-edge",
  "query": "g.V(1001,\u0027Account\u0027).out(\u0027Payment\u0027).values(\u0027id\u0027)",
  "params": {}, "includeResult": true, "returnSubgraph": true }'
```

- `language` picks `gremlin` (default) or `cypher`; `${name}` placeholders come from `params` (Cypher
  also takes native `$name`).
- `returnSubgraph` adds the induced subgraph the traversal touched - what the UI draws.
- `script: true` runs a Groovy-subset script (variables, loops, collection helpers; the advanced
  constructs - whitelisted statics, first-class closures, regular expressions - need
  `phoenixgraph.script.advanced`, on by default).

The UI has a language picker, example pickers and a rows table; the same graph answers Gremlin and
openCypher, so a team can pick the one it knows.

### Monitoring

Every graph collects its own counters and latency histograms and persists them in the storage it
already uses (an Aerospike graph in a system set, a RocksDB graph in a column family of its own
directory), so a restart resumes counting. Per graph, in total and per day: vertex/edge reads and
writes, failed reads and writes, stale property updates, edges evicted by a degree limit, key-value
round trips, and - for queries - the count by language and caller, the elements returned, the key-value
reads they caused, and the latency as mean/p50/p95/p99/max.

```
GET  /api/v1/metrics                 # every graph that recorded something
GET  /api/v1/metrics/{graph}         # storage / quality / queries, ready for a dashboard
POST /api/v1/metrics/{graph}/flush   # write through now (a timer does it every 30 s)
POST /api/v1/metrics/{graph}/reset
```

### The knowledge graph's unified schema

The knowledge graph deliberately does **not** create an edge class per relation. One `Entity` vertex
and one relation edge class carry the relation in a property:

```
Entity(id, name, type, ...)  --Entity_Entity{relation, xrelation, relationLabel, relationFamily}-->  Entity
```

`relation` is the machine name (`lord`, `minister`, `master`, `serves`, `holds`, `appearsIn`,
`mentions`, ...), `relationLabel` the human label (`主公`, `义兄`, ...), `relationFamily` a grouping
(`亲属`, `官职`, ...). Consequences:

- a new relation is data, not a schema change;
- one query asks about any relation, and a family filter answers "all kinship of 诸葛亮";
- the same schema serves the RDF graph, the RocksDB graph and the free-text-extracted graph, so the
  three can be compared and merged;
- every extracted edge can carry provenance (the chapter, sometimes the sentence), which is what makes
  the graph usable as evidence for an answer.

```groovy
g.V('诸葛亮','Entity').inE().filter(relation.equals('lord')).otherV().values('name')   // 刘备
g.V('诸葛亮','Entity').bothE().filter(relationFamily.equals('亲属')).otherV().dedup().values('name')
g.V('五虎上将','Entity').inE().filter(relation.equals('holds')).otherV().values('name')
```

## Graph algorithms (OLAP)

The algorithm steps run over the induced subgraph a traversal reached, in-process by default and on a
Gemini cluster with `DIST`. The UI colours the canvas with the result, which is the quickest way to see
what an algorithm computed:

![PageRank colouring the canvas](docs/screenshots/olap-pagerank.png)

![Louvain communities on the canvas](docs/screenshots/olap-communities.png)

| Step | Runs over | Distributed form |
| --- | --- | --- |
| `pagerank([iterations])` | the reached subgraph | `pagerank(DIST, 10)` |
| `louvain([iterations])` | communities | `louvain(DIST, 20)` |
| `cc()` | connected components | `cc(DIST)` |
| `sssp(DIST[, root])` | shortest paths (distributed only) | `sssp(DIST, 'a1')` |
| `bfs(DIST[, root])` | breadth-first reachability (distributed only) | `bfs(DIST, 'a1')` |

```groovy
g.V('135','Account').bothE('Payment').otherV().pagerank(10)   // in this process
g.V('135','Account').bothE('Payment').otherV().cc(DIST)       // on the Gemini cluster
```

The UI's toolbar runs the same steps (Louvain, PageRank, connected components, shortest path) on the
subgraph currently drawn, and the metrics endpoints report the counters and latency of every graph.

## Visualization

`graph-web` (8080) is a single-page canvas over the HTTP API:

- **query editor** for Gremlin and openCypher, with example pickers and a script mode;
- **graph canvas** that draws the returned induced subgraph with full properties, with re-balance,
  brush/selection, zoom-to-fit and a text search;
- **value / rows panels** for an element's properties and for tabular answers, and a legend/count;
- **algorithm colouring** (PageRank, Louvain, connected components, shortest path);
- **graph builder** (`builder.html`) to author a schema and its storage and to edit the fuzzy-link
  patterns; and **metrics** through the `/api/v1/metrics` endpoints.

## Modules

| Module | Description |
| --- | --- |
| `graph-core` | core graph abstractions |
| `graph-schema` | schema model: labels, property keys, data types, cardinalities |
| `graph-storage` | storage abstraction + in-memory implementation |
| `graph-storage-aerospike` | Aerospike: vertices and denormalised edges in one record, per-property CAS |
| `graph-storage-rocksdb` | shared RocksDB plumbing: key layout, regions, column families, codec |
| `graph-storage-rocksdb-inline` | RocksDB-Inline: one table per type, degree limits |
| `graph-storage-rocksdb-edge` | RocksDB-Edge: one edge table per class, time-window scans |
| `graph-query` | query parsing, planning and execution |
| `graph-query-gremlin` | Gremlin traversal engine + the Groovy-subset script layer |
| `graph-query-cypher` | openCypher subset over the same query ports |
| `graph-fuzzy` | fuzzy-link pattern language (fraud rings) |
| `graph-hnsw` | HNSW vector index: layered build + nearest-neighbour search |
| `graph-gemini` | the Gemini cluster (partitioning, dual representation, workers) |
| `graph-compute-engine` | graph analytics |
| `graph-metrics` | per-graph counters and latency histograms |
| `graph-http` | the query/write REST API and the engines |
| `graph-web` | the visualization UI |
| `graph-batch-load` | CSV/RDF bulk loader |

## What is next: a data-agent component

PhoenixGraph is being turned into an **open, independent component that a data agent can build on** -
the graph side of an agent stack, not another agent. The direction:

1. **Graph as agent memory and grounding.** Expose the knowledge graph as a retrievable context
   substrate: an agent asks for the neighbourhood, the shortest relation path or the community around
   entities, and gets back a bounded subgraph with provenance to cite - exactly the traversal shown in
   [A knowledge graph as the context an LLM answers from](#a-knowledge-graph-as-the-context-an-llm-answers-from),
   wrapped behind a small, stable API.
2. **A tool/function interface for agents.** A minimal "graph tools" surface (`find_entities`,
   `related`, `path_between`, `similar`, `nearby`, `aggregate`) that any agent framework (MCP, OpenAI
   function calling, LangChain-style tool use) can register, so the same graph serves many agent
   products without each writing traversal code.
3. **Schema-on-read for context graphs.** The unified entity/relation schema and the RDF/free-text
   ingestion let a customer bring their own corpus and get a context graph without a modelling project;
   the builder page is the human entry point, the ingestion API is the machine one.
4. **The three searches as retrieval plugins.** Vector, geo and graph retrieval are already steps of one
   query, so a retrieval layer can mix them (geo filter + vector ranking + graph expansion) without
   stitching three systems - which is the part most agent stacks currently hand-roll.
5. **Open and independent.** The engine stays a self-contained database (four storage backends, one
   wire API, a visualization UI), with the agent-facing layer kept thin and framework-agnostic, so it
   can sit under any agent business rather than locking one in.
