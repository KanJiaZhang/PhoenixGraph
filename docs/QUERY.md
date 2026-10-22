# Query guide

One HTTP endpoint serves Gremlin and openCypher over every graph. Vector search and geo search are
traversal steps, so they compose with graph walks in the same query instead of being separate systems.

For the full request/response contract, see [API.md §4](API.md#4-querying). For the
question-shaped 三国 queries, see [SANGUO-KG-QUERIES.md](../SANGUO-KG-QUERIES.md). For algorithms, see
[ALGORITHMS.md](ALGORITHMS.md).

## The query endpoint

```bash
curl -s -X POST http://localhost:8081/api/v1/query -H 'content-type: application/json' -d '{
  "graphName": "payment-graph-rocksdb-edge",
  "query": "g.V(1001,\u0027Account\u0027).out(\u0027Payment\u0027).values(\u0027id\u0027)",
  "params": {}, "includeResult": true, "returnSubgraph": true }'
```

- `language` picks `gremlin` (default) or `cypher`; `${name}` placeholders come from `params` (Cypher
  also takes native `$name`).
- `returnSubgraph` adds the induced subgraph the traversal touched — what the UI draws.
- `script: true` runs a Groovy-subset script (variables, loops, collection helpers; the advanced
  constructs — whitelisted statics, first-class closures, regular expressions — need
  `phoenixgraph.script.advanced`, on by default).

The UI has a Gremlin editor with example pickers and a rows table (the Cypher option is hidden in the
current build); the same graph answers Gremlin and openCypher over HTTP, so a team can pick the one it
knows. `GET /api/v1/graphs` lists the graphs being served, their storage and their vertex/edge types.

## Example graphs

The demo graphs are small (10²–10⁴ vertices) so every number in the docs can be checked.

| Graph | Storage | What it is |
| --- | --- | --- |
| `payment-graph` | Aerospike | accounts, IPs, phones, payments, fuzzy-link rings |
| `payment-graph-rocksdb-edge` | RocksDB-Edge | the same payments, plus HNSW layer edges for vector search |
| `payment-graph-smart-rocksdb` | RocksDB-Inline | accounts and merchants with vector profiles |
| `sanguo-kg` | Aerospike | 《三国演义》 entities and relations |
| `sanguo-kg-rocksdb-edge` | RocksDB-Edge | the same graph, scannable |
| `agent-demo` | Aerospike | a 2-document corpus ingested through `/api/v1/agent/ingest` |
| `tpc-h-analytics` | RocksDB-Edge | TPC-H tables, columns, metrics, dimensions and queries as a graph |
| `tpc-h-data` | RocksDB-Edge | 8,000 order lines with the metrics materialised |

A simple graph walk on the payment graph:

```groovy
g.V(135,'Account').bothE('Payment').otherV().limit(5)
```

## Vector search inside a traversal

`nearest(k[, ef])` is an HNSW search and an ordinary traversal step. A `has`/`filter` right after it is
pushed into the search, so it answers the k nearest that *match*, not the k nearest of which some happen
to match.

```groovy
// the five accounts most similar to 1001, and where those accounts paid
g.V(1001,'Account').nearest(5).outE('Payment').otherV().dedup().limit(40)
```

On the payment demo the HNSW layer edges (`accountVector_0`, one more per doubling of the data) are part
of the graph, so the search runs on any storage. A graph can also use the index the write path maintains
(built with `POST /api/v1/vector-index/rebuild`). The vector property and its model are declared in the
schema; `graph-hnsw` holds the index itself.

## Geo search (optional)

`circle(point, radiusMetres)` is an S2 range scan, nearest first, with `distanceMeters` attached to each
hit. It composes with `has(...)` and `limit(...)` like any other step. Use it when your vertices carry a
`GeoPoint` property.

```groovy
// accounts within 8 km, nearest first
g.V(1001,'Account').circle('{"latitude":31.2304,"longitude":121.4737}', 8000).limit(5)
```

The UI can also draw a query's coordinates on a map when the selected graph declares a coordinate
property; see [WEB-UI.md](WEB-UI.md#map-optional).

## Fuzzy links

`fuzzyLink(patternId, context)` turns small details that several records share (an email provider, an IP
/16, a card BIN, a user-name shape) into a link vertex. A query can then walk from the pattern to the
ring. The pattern language and the deterministic ids are in `graph-fuzzy`; patterns are data in
`fuzzy-patterns.json` and can be edited while the service runs, from the builder page or the
`/api/v1/fuzzy/*` endpoints in [API.md](API.md).

## Loading data

Three ways, by scale:

```bash
# 1. the payment graphs (RocksDB-Inline + RocksDB-Edge) into a local .rocksdb directory
./mvnw -pl graph-integration-test -am test -Dtest=RocksDbLiveLoadTest \
       -Drocksdb.live.dir=$PWD/.rocksdb -Dsurefire.failIfNoSpecifiedTests=false

# 2. the other demo graphs, one loader each (all run from graph-integration-test)
#    SmartPaymentGraphLiveLoadTest  - the smart graph (vector on Account/Merchant)
#    SanguoGraphIntegrationTest     - the Three Kingdoms graph on Aerospike (11k points / 21k edges)
#    SanguoRocksDbLoader            - the Three Kingdoms graph on RocksDB-Edge
#    QueryApiIntegrationTest        - the Aerospike demo set http_demo_v3 (and the fuzzy rings)
./mvnw -pl graph-integration-test -am test -Dtest=SanguoRocksDbLoader -Dsurefire.failIfNoSpecifiedTests=false

# 3. bulk CSV through the batch loader (graph-batch-load: BatchLoadMain <csv-file> [schema-dir])
#    or tools/bench-load.sh, which assembles the classpath (see docs/BENCHMARK.md)
```

For a large analytical import, a loader writes straight into the ordered tables (the same layout the
engine scans); the write API is for the transactional, request-driven path. A RocksDB graph is loaded,
not written over REST; the write API applies to Aerospike graphs. See [API.md §5](API.md#5-writes).

## Knowledge graphs

The 三国 graph uses one `Entity` vertex type and one relation edge class, with the relation in an edge
property. This is what makes a new relation a data change rather than a schema change, and it is why the
same schema can serve RDF data and text-extracted relations. The query patterns and the consequences of
this schema are in [USE-CASES.md §1](USE-CASES.md#1-a-knowledge-graph-as-agent-context); the full schema,
the document-to-RDF mapping and the extraction rules are in [SANGUO-KG.md](../SANGUO-KG.md),
[SANGUO-KG-TEXT-EXTRACTION.md](../SANGUO-KG-TEXT-EXTRACTION.md) and
[SANGUO-KG-QUERIES.md](../SANGUO-KG-QUERIES.md).
