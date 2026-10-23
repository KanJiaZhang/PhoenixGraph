# Capacity and tuning

What this software needs and what it does, measured rather than asserted - with the *method* written down,
because the numbers below come from one laptop and the method is what transfers to your hardware.

> **Scope of the numbers.** Everything measured here was measured on the development machine (Windows, Docker
> Desktop, one Aerospike container, one engine in the same JVM as the process under test). They are honest
> numbers for *this* machine and *this* data, and they are not a promise about yours. What a reader should take
> away is the shape: which costs grow with what, where the bottleneck is, and which knob moves it.
>
> Re-measured by: `node verification/verify-slo.mjs` (query classes), `node verification/verify-multi-instance.mjs`
> (one engine vs two), `node verification/verify-aerospike-ha.mjs` (failover timing). The heap is on
> `/api/v1/metrics/prometheus` as `phoenixgraph_jvm_heap_used_bytes`.

## 1. What is being served (2026-10-13)

| Graph | Storage | Vertices | Edges | On disk |
|---|---|---|---|---|
| `payment-graph` | Aerospike (keyed) | 240 accounts + IPs/phones | ~20k | n/a (store) |
| `payment-graph-rocksdb-edge` | RocksDB-Edge | 152 | ~20k | 103 MB |
| `sanguo-kg-text` | RocksDB-Edge | 6,594 | 45,081 | 94 MB |
| `tpc-h-data` | RocksDB-Edge | 8,000 sales (+ parts/suppliers/orders) | 16,000 | 87 MB |
| `tpc-h-analytics` | RocksDB-Edge | ~90 | ~300 | 80 MB |

The demo data spans four orders of magnitude in vertices and three in edges, which is enough to see how each
query class scales - and not enough to see what happens at 10⁷, which is why §6 says so.

Two notes on the disk column, because they matter for sizing: a RocksDB graph is much larger than its logical
data (sst files, versions, the edge tables - 8,000 sales are 87 MB), and the directory keeps growing with
versions and compaction until RocksDB compacts it. It is a *store*, not a file you can compare against a
row size.

## 2. Query latency by class (end to end, including HTTP and the server's JSON)

Measured by `verify-slo.mjs`, interleaved, 40 rounds per class, first round excluded (warm-up):

| Class | p50 | p95 | p99 | Objective |
|---|---|---|---|---|
| Keyed point read (`g.V(id)`) | 14.4 ms | 21.5 ms | 21.6 ms | < 40 ms |
| One hop (`bothE('Payment').limit(50)`) | 19.3 ms | 22.0 ms | 22.1 ms | < 60 ms |
| Two hops (`both().both().dedup().limit(200)`) | 16.8 ms | 24.5 ms | 30.6 ms | < 150 ms |
| Class aggregate over 8,000 rows | 52.6 ms | 74.9 ms | 83.1 ms | < 500 ms |

Two things to read out of that table:

- **The floor is the HTTP round trip, not the graph.** The engine's own measurement of a one-hop query is a
  p50 of **0.9 ms** (README); end to end it is 19 ms on this machine, almost all of it request handling,
  serialisation and the client. Before optimising a query, check whether the query is the cost at all.
- **Hops cost by fan-out, not by depth.** One hop over a hub with 50 neighbours and two hops over 200 are the
  same order of magnitude here; the class scan over 8,000 rows is the only one 3× slower, because it reads a
  whole table.

## 3. One engine vs two (the multi-instance question)

`verify-multi-instance.mjs`: 25 interleaved rounds, one "operation" = three reads (point, 1 hop, 2 hops) plus a
write every fifth round, against **one** engine and then against **two** engines sharing one store (the client
round-robins, which is what a load balancer does):

| Configuration | p50 | p95 | p99 | operations/s |
|---|---|---|---|---|
| one engine | 46.8 ms | 63.4 ms | 91.7 ms | 20.9 |
| two engines, round-robin | 47.9 ms | 65.7 ms | 71.0 ms | 20.1 |

**Two engines gave 96% of one engine's throughput**: on this data the workload is store-bound, so a second
engine buys *availability* (no single process to restart) and headroom for CPU-bound work (parsing, planning,
serialising), not speed. A CPU-bound workload - many small queries, or script mode with real computation - is
where the second engine pays for itself; measure yours rather than assuming either way.

The same drill checks the two guarantees that make more than one instance safe:

- **the same write twice is one write** (both accepted, one value stored) - the store applies per-property CAS,
  so a retry from a load balancer is harmless;
- **a stale write is refused, not applied**: a property carrying an older `lastModifiedTime` comes back
  `SUCCESS_STALE` and the newer value survives.

## 4. Availability: what losing a node costs

`verify-aerospike-ha.mjs` against a two-node cluster with `replication-factor 2`:

| Event | Observed |
|---|---|
| one node killed | the answer is unchanged; **failover completed in 1 s** |
| writes while a node is down | accepted (the surviving node owns the partitions and has the replicas) |
| node restarted | cluster re-forms in **2 s**, replication catches up in **1 s** |
| the *seed* node killed while the engine runs | the engine keeps answering (it discovered the other node) |
| the *seed* node gone at start-up | **the engine does not start** - see the knob below |

Re-run on 2026-10-21: failover **1 s**, cluster re-form **2 s**, replication catch-up **1 s**;
a running engine also survived the seed node being killed. The full drill is in
`verification/verify-aerospike-ha.mjs`.

So a single node loss is a non-event for a running instance. Two caveats worth stating plainly: the same node
must not be the only one in its failure domain (a rack, a host), and a restart that happens *while* the seed is
down needs a second seed (see §5).

## 5. The knobs, and what each one is for

| Knob | Default | What it changes |
|---|---|---|
| `phoenixgraph.storage.host` / `.port` | `localhost:3000` | the fallback single seed when no seed list is configured |
| `phoenixgraph.storage.seeds` | unset | comma list of `host:port` seeds; a restart can bootstrap through another node |
| `phoenixgraph.storage.rocksdb-root` | `.rocksdb` | where the analytical graphs live; created if missing. **One process per directory** - the reason `graph-http` runs one replica |
| `phoenixgraph.storage.schema-dir` | unset | a mounted directory of schemas: a graph per file, no image rebuild (each file needs its own `"id"`) |
| `phoenixgraph.limits.max-concurrent` | 64 | requests in flight; the brake that keeps one slow query from spending the instance |
| `phoenixgraph.limits.queue-wait` | 2 s | how long a request waits for a slot before it is answered `503` |
| `phoenixgraph.limits.per-graph-concurrent` | 8 | in-flight requests per graph |
| `phoenixgraph.limits.per-graph-per-second` | 200 | the fairness budget per graph (`429` beyond it) |
| `phoenixgraph.limits.per-tenant-concurrent` | 0 (off) | in-flight requests per authenticated tenant |
| `phoenixgraph.limits.per-tenant-per-second` | 0 (off) | the fairness budget per tenant (`429` beyond it) |
| `phoenixgraph.limits.request-timeout` | 30 s | wall clock a query may run; checked between steps |
| `regionCount` (per schema, RocksDB) | 512 | how many hash ranges the key space is cut into - also the shard count a `DIST` scan uses |
| `cacheEdges` (per schema) | true | whether a graph caches decoded edges; the memory/CPU trade-off, and the first knob to turn off under memory pressure |
| `parallelism` (engine) | `min(8, CPU)` | how many seeds are read at once - the knob for a store that answers slowly but in parallel |

**Memory.** The container sets `-XX:MaxRAMPercentage=75`, so the JVM sizes its heap from the container limit.
The heap in use is exported (`phoenixgraph_jvm_heap_used_bytes`); a graph's RocksDB *directory size* is the
other half of the picture (§1), and its block cache is bounded by the store configuration rather than by the
data. Watch the heap after a workload, not at start-up: with `cacheEdges` on, the steady-state heap is what
says whether the limit is right.

## 6. What is not measured here (and why)

- **Sizes above ~10⁴ vertices.** The demo data stops there. The *method* in §2 is what to reuse: pick the
  classes your callers use, measure p99 end to end, and keep the numbers next to the objectives.
  [BENCHMARK.md](BENCHMARK.md) now carries a 10⁶-vertex / 10⁶-edge run **with its edges loaded and queried**,
  and the 10⁷ attempt with the ceiling it hit: at 3.0 GB of edge data the stock dev container's namespace
  (no `storage-engine file`) refused writes with `Server memory error` at 70% used / 6% available. A 10⁷
  corpus needs a disk-backed namespace; that is a deployment setting, not a loader limit - the loader itself
  sustains ~2,370 edges/s, i.e. an hour for 10⁷.
- **Cross-machine distributed runs.** `DIST` steps synchronise between workers, and on a demo-sized graph that
  is *slower* than the in-process path - which is expected, not a regression. A cross-machine benchmark is
  roadmap item M3-3 and is deliberately out of scope here.
- **Write throughput.** The drills write a handful of vertices at a time. A bulk-load number belongs with the
  loaders (`graph-batch-load`, `graph-mapreduce`), not with the serving path; the current one is in
  [BENCHMARK.md](BENCHMARK.md) (~2,370 edges/s after the batch uplift).
- **Automatic promotion.** Covered by `verification/verify-aerospike-ha.mjs` from the availability side: a
  two-node cluster with replication factor 2, the engine keeps answering when a node (including the seed)
  is killed, and replication catches up when it returns. Aerospike does the promotion; the engine does not
  elect anything.
