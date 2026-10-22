# Benchmark and scale plan

This repository publishes a **method**, a **harness** and the numbers it has actually measured. It does not
publish a 10⁷-vertex number: the demo data stops at 10⁴ and a 10⁷ run needs the hardware described below.

## What is measured

`tools/bench-queries.mjs` sends HTTP requests to a running service, sequentially, after a warm-up, and
reports p50/p95/p99. That is the number a caller sees: the engine, JSON serialisation and the HTTP stack are
all included. It is deliberately not a load test; for concurrency, the gate's own counters and
[CAPACITY.md](CAPACITY.md) are the reference.

```bash
node tools/bench-queries.mjs --suite payment --graph payment-graph --rounds 40
node tools/bench-queries.mjs --suite tpc-h --graph tpc-h-data --rounds 20 --json
```

The payment suite is a point read, one hop and two hops (`limit(50)` / `limit(200)`). The TPC-H suite is
three 8,000-row aggregates. The queries are in the script, so a run is reproducible.

## What has been measured

On the development machine, `payment-graph` on Aerospike, 20 rounds after 3 warm-up rounds (2026-10-20):

| Query | p50 | p95 | p99 |
|---|---:|---:|---:|
| point (`g.V('1000','Account')`) | 15.3 ms | 16.2 ms | 17.0 ms |
| one hop (`bothE('Payment').limit(50)`) | 14.8 ms | 17.3 ms | 18.6 ms |
| two hops (`...dedup().limit(200)`) | 15.5 ms | 19.2 ms | 21.8 ms |

These are HTTP round trips on a laptop, not a capacity claim. The engine-internal one-hop p50 is 0.9 ms
(`AerospikeHopQueryLatencyTest`); the difference is the HTTP and serialisation floor. The demo graphs are
10²–10⁴ vertices ([CAPACITY.md](CAPACITY.md) §1).

## A 10^6 stepping stone (2026-10-21)

The first scale run went to 10^6 vertices, not 10^7, on the development laptop and the existing single
Aerospike container. It is recorded because it separated two costs that the 10^4 demo hides.

| Step | Observed |
|---|---|
| generate 1,000,000 accounts + 5,000,000 payments | **39.2 s**; vertices 86 MB, edges 441 MB |
| load 1,000,000 vertices | **64 s** (69 s with logging quiet), 1,000,000 succeeded, 0 conflicts, 0 failed |
| Aerospike after the vertex load | ~**1.28 GiB** resident; 448 MB of vertex data |
| load 100,000 edges, window 1,000 | **5m20s** (~310 edges/s), 100,000 succeeded |
| load 100,000 edges, window 5,000 | **5m29s** (~300 edges/s), 100,000 succeeded |
| load 1,000,000 edges | **stopped at the 20-minute cap before completion** |
| load 5,000,000 edges | **stopped at the 30-minute cap before completion** |

The edge loader is the finding: it merges a bounded window (default 1,000), reads and writes each touched
vertex once, and writes through the ordinary per-vertex upsert path. The 100k trials ran at roughly
**230–400 edges/s**; the 1M and 5M runs did not finish inside their windows. Earlier 100k runs ranged from
4m10s to 7m10s, and the latest runs were 5m20s–5m29s; this is a characterisation of the load path, not a
clean speedup claim.
10^7 edges is still a multi-hour load before any query is measured.
That is a load-path problem, not an engine-query result, and it has to be fixed (or replaced by the Aerospike
bulk loader) before a 10^7 claim is meaningful.

On the 1,000,000-vertex graph, with the edge load incomplete, the HTTP query benchmark still measured the
point read and the (sparse) one- and two-hop shapes:

| Query | p50 | p95 | p99 |
|---|---:|---:|---:|
| point | 15.6 ms | 17.1 ms | 17.1 ms |
| one hop | 15.6 ms | 16.8 ms | 16.8 ms |
| two hops | 15.3 ms | 15.9 ms | 15.9 ms |

Those hop numbers are **not** a 10^6-edge result: the edge file did not finish loading. They are recorded
only to show the HTTP floor did not move with the vertex count. The 10^7 plan below remains the target, and
the edge-load bottleneck is now the first item to close.

## Generating a scale dataset

`tools/generate-scale-csv.mjs` streams two CSV files for the payment schema, with the composite ids and
property indices `graph-batch-load` expects. It writes to `.datasets/scale` (gitignored):

```bash
node tools/generate-scale-csv.mjs --accounts 100000 --payments 500000
node tools/generate-scale-csv.mjs --accounts 10000000 --payments 50000000   # the 10^7 plan
```

Start at 10⁵ and check the load path before generating 10⁷: the 10⁷ file is multi-gigabyte and the load is
long. The generator prints the count, file sizes and elapsed time; it does not load anything.

## Loading it

`tools/bench-load.sh` builds `graph-batch-load`, assembles the classpath and passes the schema directory:

```bash
export AEROSPIKE_VERTEX_SET=scale_v1
export GRAPH_SCHEMA=payment-graph
# AEROSPIKE_HOST / AEROSPIKE_PORT / AEROSPIKE_NAMESPACE default to localhost:3000/test

tools/bench-load.sh .datasets/scale/vertices.csv
tools/bench-load.sh .datasets/scale/edges.csv
```

The loader streams rows and merges duplicates in a bounded window; it does not hold the file in memory.
`AEROSPIKE_BATCH_SIZE` (default 1000) controls that window. On the 100k-edge trials, 1,000 and 5,000 were
within noise of each other; edge-store bin writes are parallelised by vertex and each vertex is read and
written once. Measure on the target store before relying on either knob. A
RocksDB graph is loaded by its own loaders, not by this CSV path ([QUERY.md](QUERY.md#loading-data)).

## The 10⁷ plan

A 10⁷ run needs a deployment, not a laptop:

| Component | Requirement |
|---|---|
| Store | an Aerospike cluster (3+ nodes, replication factor 2, NVMe), or RocksDB on local NVMe for an analytical graph. One engine process per RocksDB directory. |
| Engine | one `graph-http` per 8–16 vCPU, `-XX:MaxRAMPercentage=75`, enough heap for the working set; `parallelism` tuned to the store. |
| Network | engine and store on the same network; a load-balanced Aerospike address or the seed list. |
| Load | `graph-batch-load` (CSV, streaming) for Aerospike; the RocksDB loaders for the analytical schemes. |

Record, at each size (10⁵, 10⁶, 10⁷):

1. load time, succeeded/failed/conflict counts, and on-disk size per 10⁶ elements;
2. point / one-hop / two-hop / aggregate p50/p95/p99 with `bench-queries.mjs`;
3. heap and RocksDB block-cache use at steady state (`/api/v1/metrics/prometheus`);
4. the request gate's refusals and the store failure counters while the load runs;
5. for HA, the failover drill in `verification/verify-aerospike-ha.mjs` on the same cluster.

Do not extrapolate the 10⁴ numbers. A hub with 10⁷ edges is a different query shape from a 10⁴ demo, and the
storage scheme (Aerospike-Edge, RocksDB-Edge) is what decides whether a hub read is a range scan or a
fan-out. [CAPACITY.md](CAPACITY.md) §6 states what is not measured; this document is the plan to close it,
not a claim that it is closed.

## Multi-seed and failover

`phoenixgraph.storage.seeds` accepts `host:port` entries instead of a single `host`/`port`:

```yaml
phoenixgraph:
  storage:
    seeds: [aerospike-1:3000, aerospike-2:3000]
```

A seed only bootstraps discovery; a connected client learns every node. A running engine survives losing
the seed (the HA drill covers it); a *restart* while one seed is down can still connect through another
entry. There is no leader election and no automatic promotion; see [OPERATIONS.md](OPERATIONS.md).
