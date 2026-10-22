# Design, storage and deployment

This document covers the engine's internals and deployment shape: how a graph chooses its storage, how
keyed reads and scans are served, how the distributed engine works, and what each module does. For the
query language, see [QUERY.md](QUERY.md); for the API contract, see [API.md](API.md); for operations,
see [OPERATIONS.md](OPERATIONS.md).

## One engine, four storage schemes

A graph's schema declares its storage. The same schema, Gremlin, openCypher and HTTP API are available on
every scheme, so moving a graph from a transactional store to an analytical one is a schema edit and a
reload, not a rewrite. Writes need a keyed store; a RocksDB graph is filled by a loader.

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

## OLTP: keyed reads and writes on Aerospike

A request path names ids, so one record (or one batch) answers it and nothing scans. Writes go through
the REST write API (they are validated, indexed by the vector index, and counted by the metrics);
concurrent updates to the same property are safe under per-property CAS.

```groovy
g.V('135','Account').outE('Payment').limit(10).values('status')   // one record + its out bin
g.V('1001','Account').nearest(5).outE('Payment')                  // vector index + one hop
```

## The read path

A request names ids, so a keyed read answers it from one record or one batch. Beyond that:

- a hop reads its edges in one batched, cached, projected round trip;
- a compiled query is cached;
- a set of seeds is read in parallel (`parallelism`, default `min(8, CPU)`);
- an attribute condition and a degree cap are pushed into the storage decode;
- the batch size adapts to the observed latency.

This is what the latency figures in the README measure. End-to-end objectives, including HTTP handling,
are in [SLO.md](SLO.md).

## OLAP: class scans and time windows on RocksDB

The analytical side answers a question that names a **class**: one iterator per table, capped, projected
and pushed into the decode.

```groovy
g.V('Account').has('accountStatus','ACTIVE').bothE('Payment').cc(DIST)
g.E('Payment').count()
g.V('9000001','Account').createdBetween('2025-06-01','2025-06-02').inE('Payment').limit(500)
```

A hub with hundreds of millions of edges lives in the aggregated tables and is read by walking the
levels of a time window; a query without a window is refused with a message that says to add
`createdBetween`. `g.E('Payment')` reads each edge once, with both endpoints (`otherV()` is the far
one). `scanLimit` bounds every class scan of an engine in one place.

## Building a schema

A schema is JSON (`graph-http/src/main/resources/*.json`) with vertex types, edge endpoints, property
types and the storage block. The UI's builder page (`builder.html`, the "图管理" button) authors one: it
edits vertices/edges/properties, picks the storage type and path, and previews the draft. The same JSON
can be generated with the tools under `tools/`. Schema files carry a version and are checked with
`node tools/schema-version.mjs` (see [API.md §9](API.md#9-schema-files-are-versioned)).

## Distributed execution (Gemini)

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
- **What one instance will spend is configured, not hoped for.** `phoenixgraph.limits` bounds requests in
  flight, requests per second per graph, and the wall clock a query may run (`request-timeout`), answering
  `429`/`503` with `Retry-After`; every request carries an `X-Request-Id` that is also on its log line. See
  [API.md §3](API.md#3-limits-and-the-status-codes-a-client-must-handle). Horizontal scaling is a
  deployment story (M3 of the roadmap, unverified at multiple instances): the limits make one instance
  *safe*, not *fast enough*.

```groovy
g.V('135','Account').bothE('Payment').otherV().pagerank(DIST, 10)   // 50 accounts, ~2.8 s incl. load
g.V('a1,a2,a3','Account').cc(DIST)                                  // connected components
g.V('a1,a2,a3','Account').louvain(DIST, 20)                         // communities
g.V('135','Account').bothE('Payment').otherV().betweenness(DIST, 8) // 8 sampled sources, exact with no k
g.V(DIST,'Account').has('accountStatus','ACTIVE').count()           // a partitioned class scan
```

## Monitoring

Every graph collects its own counters and latency histograms and persists them in the storage it
already uses (an Aerospike graph in a system set, a RocksDB graph in a column family of its own
directory), so a restart resumes counting. Per graph, in total and per day: vertex/edge reads and
writes, failed reads and writes, stale property updates, edges evicted by a degree limit, key-value
round trips, and — for queries — the count by language and caller, the elements returned, the key-value
reads they caused, and the latency as mean/p50/p95/p99/max.

```
GET  /api/v1/metrics                 # every graph that recorded something
GET  /api/v1/metrics/{graph}         # storage / quality / queries, ready for a dashboard
GET  /api/v1/metrics/prometheus      # the same numbers in Prometheus text format (+ the request gate)
POST /api/v1/metrics/{graph}/flush   # write through now (a timer does it every 30 s)
POST /api/v1/metrics/{graph}/reset
```

The endpoint reference is in [API.md §8](API.md#8-metrics). Thresholds and alerts are in
[OPERATIONS.md](OPERATIONS.md); the measured latency objectives are in [SLO.md](SLO.md) and
[CAPACITY.md](CAPACITY.md).

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

## A note on scope

The engine is 1.0 in the sense that someone else can run it and re-derive the numbers in these documents.
It is not feature-complete or proven at 10⁷ vertices. Known gaps, with evidence, are in
[RELEASE-1.0.0.md](RELEASE-1.0.0.md) and [ROADMAP-1.0.md](ROADMAP-1.0.md); measured data sizes are in
[CAPACITY.md](CAPACITY.md).
