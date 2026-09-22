# PhoenixGraph

A High Scaled and High Performance HTAP Graph Database.

> **Resuming work?** Start with [HANDOVER.md](HANDOVER.md) - current state, how
> to start the services and what is next.

## Profiles, locations and the questions they answer

`payment-graph-smart` is a second payment graph whose accounts and merchants carry a 16-dimensional
profile (`embedding`, a vector property) and a home location (`location`, a geo property). Profiles form
five clusters and the clusters are also five districts of Shanghai, most payments stay inside a cluster
and visits only go to shops of the same district - so similar and nearby agree, which is what makes
these three questions answer something:

```groovy
g.V('a7','Account').nearest(5)                          // the five most similar accounts (all in Xujiahui)
g.V('a7','Account').nearest(5).outE('Payment')          // ... and what those accounts pay
g.V('a7','Account').out('Payment').dedup().nearest(5)   // each receiver, and its own lookalikes
g.V('m1','Merchant').circle('{"lat":31.2304,"lon":121.4737,"radius":3000}').limit(5)
                                                        // the five shops nearest a point ...
...limit(3).nearest(2)                                  // ... and each shop's two closest neighbours
```

The same schema is served twice, because the two indexes live in different places: the vector index on
Aerospike (HNSW; build it with `POST /api/v1/vector-index/rebuild`) and the geo index on RocksDB-Inline
(a real S2 index - a community Aerospike has no geo secondary index and scans). Load the data with
`SmartPaymentGraphLiveLoadTest`; see CONTEXT.md section 31.

## System metrics

Every graph collects its own counters and latency histograms, and persists them in the storage it
already uses: an Aerospike graph writes one record per graph into the `graph_metrics` system set,
a RocksDB graph writes one key into a `metrics` column family inside its own directory. A restart
therefore resumes counting instead of starting over, and today's numbers stay today's.

Collected per graph, in total and per day: vertex/edge reads and writes (with the items they moved),
reads and writes that failed after their retries, stale property updates, edges evicted by a degree
limit, key-value round trips, and — for queries — the count by language and caller, the elements
they returned, the key-value reads they translated into, and the latency as mean/p50/p95/p99/max.

```
GET  /api/v1/metrics                 # every graph that recorded something
GET  /api/v1/metrics/{graph}         # storage / quality / queries, ready for a dashboard
POST /api/v1/metrics/{graph}/flush   # write through now (a timer does it every 30 s)
POST /api/v1/metrics/{graph}/reset   # start the counters over
```

The counters live in `graph-metrics` (`GraphMetrics`, `LatencyStats`, `MetricsStore`,
`MetricsRegistry`); the storage schemes count their traffic where it is issued
(`AerospikeOperations` for Aerospike, `RocksDbHandle` for RocksDB) and the query service
attributes the difference to each query, parallel seed reads included.

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
| `Aerospike-Edge` | Aerospike, plus an aggregated edge table per edge class (`Edge_Out` / `Edge_In`): a vertex's edges are chunked by creation time (2000 per record) and folded into further levels until one root remains | none: written by a bulk import, read by a creation-time window (`createdBetween(from, to)`) |
| `RocksDB-Edge` | one table per vertex type; one edge table per edge class keyed by `hash(vertexId):direction:vertexId:createdTime:edgeId` | none: a hub may have any degree, and a creation-time window is a range scan |

All four schemes implement the same `GraphQuery` port, so Gremlin and Cypher
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

### Geo search

A property marked `isGeo` holds a point and gets an S2 cell index, so a radius query is a range
scan over that index instead of a scan of the graph. Both RocksDB schemes keep it (one index
table per geo property, maintained by the vertex writes themselves), and Aerospike uses its
`geo2dsphere` secondary index where the server has one and scans where it does not.

```groovy
g.V('1','Merchant').circle('{"lat":31.2304,"lon":121.4737,"radius":1000}').limit(10)
g.V('1','Merchant').circle('{"lat":31.2304,"lon":121.4737}', 500).has('category','coffee')
        .values('name','distanceMeters')
```

The radius is in metres and a step argument, the results come back nearest first with the distance
attached (`distanceMeters`), and they combine with the other steps (`has`, `limit`, `elementMap`, `count`).

### Reading a hub by time window

A vertex with hundreds of millions of edges cannot live in one record, so `Aerospike-Edge` keeps its
edges in the aggregated tables and a query has to say which window it wants:

```groovy
g.V('9000001','Account').createdBetween('2025-06-01','2025-06-02').inE('Payment').limit(500)
```

The window walks the levels (root -> children whose span overlaps -> level 0 chunks), the answers are
merged with the edges still inline in the vertex record (an update always leaves the newest copy
there), and a query without a window is refused with a message that says to add `createdBetween`.

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

### Fuzzy linking: finding the accounts that hide together

A fraudster who knows the system links accounts will keep the obvious details apart -
different names, different phones, different cards - and still reuse the small ones:
the same email provider, the same first octets of an address, a user name that is a word
plus digits, the same card BIN. Fuzzy linking turns those fragments into a **node**:
every account whose fragments agree produces the same key, so they end up on one
`FuzzyLink` vertex and a query can walk from the pattern to the ring.

A pattern is data, written by an analyst:

```
emailDomain(email)+'_'+countryCode(phone)+'_'+ip2(ip)+'_'+regexValue(username, '^([a-z]+)')
```

It is evaluated against a **context** (a JSON object, or the properties of a vertex) and each
part produces a fragment; the fragments joined are the key. A part with no value makes the
whole pattern not apply - an account without an email is not linked by a pattern that needs
the email domain - and `opt(...)` marks a part as optional.

A regular expression is how a pattern asks for a *shape*, and there are two ways to use one:

```
regex(username, '^[a-z]+\d+$')             // requires the shape, and puts the shape in the key
regexValue(username, '^([a-z]+)\d+$')      // requires it, and puts what it matched in the key: zhang
regexValue(email, '@(\w+)\.(\w+)$', 2)    // a group of it: a@mail.ru -> ru (0 is the whole match)
```

The difference decides who ends up on one node. With `regex`, `zhang1988` and `xia1999` - two accounts
that share the way their user name is built and nothing else - are linked; with `regexValue`, only
accounts whose extracted word is the same are. The shape itself is the fragment
(`re_<shape>_<hash>`, so a reader sees which sub-pattern it stands for and two shapes that normalise
alike stay apart), and a value that does not have the shape makes the pattern not apply at all.

Backslashes are kept as written (`\d`, `\w`, `\s`), so a class means what it says. A group a
`regexValue` asks for but the expression does not have is reported when the pattern is used.

A pattern can carry how to try it: an `example` context, a `demoTitle` and the `demoSteps` a demo
query continues with, so the demo walks from the fragments to the ring, its payments and the account
they reach. (The dashboard button that ran it is switched off in `app.js` for now - the endpoint and
the pattern examples stay.)

```groovy
g.V().fuzzyLink(0, '{"email":"19880101@mail.ru","phone":"+8613800138001",
                   "ip":"10.20.31.7","username":"zhang1988"}')
    .out('FuzzyLinkedTo')                 // the accounts that share those fragments
    .outE('Payment').otherV()             // where their money went

g.V('fz_a1','Account').fuzzyLink(0)       // which ring does this account belong to
```

What it is made of:

- **`graph-fuzzy`** (a module of its own): the pattern language
  (`emailDomain`, `emailName`, `ip2`/`ip3`, `countryCode`, `phonePrefix`, `cardBin`,
  `alphaPrefix`, `digitSuffix`, `regex`, `digits`, `first`/`last`, `hash`, `opt`, …),
  the key normalisation, the deterministic ids (`pattern_<id>`, `fl_<pattern>_<key>`) and
  the link plan - a pure function of a pattern and a context, which is why the reader and
  the writer agree by construction;
- **patterns as a file** (`fuzzy-patterns.json`, per graph), edited in the builder page and
  re-read when it changes, so a new pattern is used by the next query without a restart;
- **the `fuzzyLink` step**: it computes the key from the context, reads the link vertex with
  that id and continues the traversal from it. It works from a context alone
  (`g.V().fuzzyLink(0, '{...}')`) or from an account (`g.V('a1','Account').fuzzyLink(0)`,
  whose own properties are the context);
- **the schema**: a `FuzzyLink` vertex, a `FuzzyPattern` vertex and one `FuzzyLinkedTo`
  edge from a link to an account, with out- and in-degree limits of 1000 - a pattern like
  "same /16" can group a lot of accounts, and the edge has to allow it;
- **the writes**: `POST /api/v1/graphs/{graph}/fuzzy-links` links one vertex (and the demo
  loader seeds two rings), through the ordinary write path, so links are validated and
  indexed like any other vertex - and linking twice leaves one node and one edge, because
  the ids are the key.

`FuzzyLinkIntegrationTest` seeds two rings (six accounts sharing an email provider, a
country, a `/16` and a name word; four hiding behind machine-made addresses on one `/24`
with one card BIN) plus honest accounts that share nothing, and then asserts that the
pattern finds exactly the ring, that the link node and its members agree both ways, and
that the query above walks to twelve payments at the collection account.

### A query starts from a key or from a class

The two storage implementations are not two spellings of one thing: they answer different questions.
**Aerospike is the transactional side** - a request arrives with a key, one record (or a batch of them)
answers it, and that is the whole read path of a request. **RocksDB-Edge is the analytical side** - one
ordered table per vertex type and per edge class, so a question that names a *class* is one iterator
per table, and the walk can be capped, projected and pushed into the decode.

```groovy
// by key: both stores answer this, and it is what a request path does
g.V('135','Account').outE('Payment').limit(10).values('status')

// by class: the analytical store walks the table, the transactional one says so
g.V('Account').filter(accountStatus.equals('ACTIVE')).bothE('Payment').cc(DIST)
g.E('Payment').pagerank(DIST, 10)
g.E('Payment').count()
```

- A traversal may start from **one type on its own**: `g.V('Account')` is every account, `g.E('Payment')`
  is every payment of that class. Both need a store that can scan; on one that answers by id the query
  is refused with a message that says which store does it rather than returning nothing:
  *`g.V('Account') starts from a whole type, which AerospikeGraphQuery cannot read: a keyed store
  answers by id. Scan a type on a store whose tables are ordered ranges (the RocksDB-Edge scheme is the
  analytical one), or name the ids instead.`*
- `g.E('Payment')` reads each payment **once**, with **both** of its endpoints (`otherV()` is the far
  one, and an algorithm step gets the whole edge).
- The capability is one method on the query port (`supportsScan()`), so a new backend opts in without
  the traversal changing, exactly like the pushed-down conditions.
- A scan reads as much as the store holds, which is what the analytical side is for: `scanLimit`
  (`GremlinGraphQuery.scanLimit(n)`, `0` = no cap) bounds every scan of an embedding in one place.

### Distributed graph algorithms (Gemini)

The algorithm steps also exist in a distributed form, after Gemini (OSDI '16): the graph is cut
into contiguous chunks, one per worker, every edge is stored twice (where its destination lives for
a push, where its source lives for a pull), and each superstep picks the cheaper direction from the
number of active edges - the rule the paper takes from Ligra, `sparse when activeEdges < |E| / 20` -
so a traversal pays for the work it causes rather than for a fixed strategy.

```groovy
g.V('135','Account').bothE('Payment').otherV().pagerank(DIST, 10)   // on the payment edges reached
g.V('a1,a2,a3','Account').cc(DIST)                                  // connected components
g.V('a1,a2,a3','Account').louvain(DIST, 20)                         // communities
g.V('a1,a2,a3','Account').sssp(DIST, 'a1')                          // shortest paths from a1
```

- **`DIST` means the distributed engine and nothing else**: with no cluster configured the step is an
  error, never a quiet fall back to the in-process implementation (`pagerank()` without the marker still
  runs in memory, which is what the three algorithms that have both forms are for).
- Workers are **processes with a port**: `graph-http` starts four on 19100-19103 by default
  (`phoenixgraph.gemini.workers`, `first-port`, `mode=local|process`), each worker answers on its own
  HTTP endpoint, and `GeminiWorkerMain` is the program a helper script (or a YARN container) starts. The
  messages between workers are batched per peer and buffered on arrival, so no two workers wait on each
  other; the launcher only decides the mode of each superstep and adds up the totals an algorithm needs.
- The graph computed on is the **induced subgraph the traversal reached** - the same one the in-memory
  steps use - so the two engines can be compared on one query. The edge classes come from the traversal
  (`bothE('Payment')` means the payment edges only).
- A whole-class seed (`g.E('payment').pagerank(DIST)` with no vertices) is not supported yet: it needs a
  scan port, which is the same gap as counting a whole vertex type. Seed the vertices the graph is about.

See `CONTEXT.md` section 36 for the mapping onto the paper (chunk partitioning, the dual edge copy,
the signal/slot API, mirror scheduling, the bitmap-assisted CSR and doubly-compressed CSC), what was
adapted for HTTP and single-process workers, the bugs the first runs turned up, and the measured runs:
four workers on one machine, connected components converging in seven supersteps, PageRank matching a
hand-written single-process reference to 1e-6, and a live `pagerank(DIST)` over the payment demo
answering for fifty accounts in 2.8 seconds including loading the graph.

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
, 2)   // the second group: a@mail.ru -> ru (0 is the whole match)
```

Backslashes are kept as written (`\d`, `\w`, `\s`), so a class means what it says; a group the
expression does not have is reported when the pattern is used, and a value the expression does not
match contributes nothing - which makes the pattern not apply.

A pattern can carry how to try it: an `example` context, a `demoTitle` and the `demoSteps` a demo
query continues with. The dashboard's fuzzy button reads exactly that - it only appears for a graph
whose patterns have an example, and it runs `g.V().fuzzyLink(id, context)` plus those steps, so the
demo walks from the fragments to the ring, its payments and the account they reach.

```groovy
g.V().fuzzyLink(0, '{"email":"19880101@mail.ru","phone":"+8613800138001",
                   "ip":"10.20.31.7","username":"zhang1988"}')
    .out('FuzzyLinkedTo')                 // the accounts that share those fragments
    .outE('Payment').otherV()             // where their money went

g.V('fz_a1','Account').fuzzyLink(0)       // which ring does this account belong to
```

What it is made of:

- **`graph-fuzzy`** (a module of its own): the pattern language
  (`emailDomain`, `emailName`, `ip2`/`ip3`, `countryCode`, `phonePrefix`, `cardBin`,
  `alphaPrefix`, `digitSuffix`, `regex`, `digits`, `first`/`last`, `hash`, `opt`, …),
  the key normalisation, the deterministic ids (`pattern_<id>`, `fl_<pattern>_<key>`) and
  the link plan - a pure function of a pattern and a context, which is why the reader and
  the writer agree by construction;
- **patterns as a file** (`fuzzy-patterns.json`, per graph), edited in the builder page and
  re-read when it changes, so a new pattern is used by the next query without a restart;
- **the `fuzzyLink` step**: it computes the key from the context, reads the link vertex with
  that id and continues the traversal from it. It works from a context alone
  (`g.V().fuzzyLink(0, '{...}')`) or from an account (`g.V('a1','Account').fuzzyLink(0)`,
  whose own properties are the context);
- **the schema**: a `FuzzyLink` vertex, a `FuzzyPattern` vertex and one `FuzzyLinkedTo`
  edge from a link to an account, with out- and in-degree limits of 1000 - a pattern like
  "same /16" can group a lot of accounts, and the edge has to allow it;
- **the writes**: `POST /api/v1/graphs/{graph}/fuzzy-links` links one vertex (and the demo
  loader seeds two rings), through the ordinary write path, so links are validated and
  indexed like any other vertex - and linking twice leaves one node and one edge, because
  the ids are the key.

`FuzzyLinkIntegrationTest` seeds two rings (six accounts sharing an email provider, a
country, a `/16` and a name word; four hiding behind machine-made addresses on one `/24`
with one card BIN) plus honest accounts that share nothing, and then asserts that the
pattern finds exactly the ring, that the link node and its members agree both ways, and
that the query above walks to twelve payments at the collection account.

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

## Start and stop everything with one script

```bash
tools/start-phoenixgraph.sh                    # build, then start graph-http (8081) + graph-web (8080)
tools/start-phoenixgraph.sh --no-build         # skip the build, start what is already built
tools/start-phoenixgraph.sh --with-hnsw        # also start the hnsw-dynamic instance (8083)
tools/stop-phoenixgraph.sh                     # stop the running instances
```

What the start script does, in order: stop any running instance (a live service holds its jar
open, so the build would fail otherwise), build `graph-http` + `graph-web` and their dependencies
(`clean package`, tests skipped), start the Aerospike container if it is not running, launch the
services detached, and wait until each answers before reporting the URLs.

- it checks that the jar really is a **fat jar** (has `BOOT-INF/lib`) and stops with a hint if a
  build ever ran with `-Dspring-boot.repackage.skip=true`, which leaves a thin jar that cannot be
  started with `java -jar`;
- 8083 always gets its own Gemini ports (`--phoenixgraph.gemini.first-port=19110`), otherwise its
  workers collide with the ones of 8081 and that instance fails to start;
- it needs a JDK on `JAVA_HOME` (falling back to the newest `/c/Program Files/Java/jdk-*`), and
  `curl`, `unzip`, `docker` and `powershell` on PATH;
- logs are written to `.logs/<service>.out`, and the services outlive the script (`nohup`).
