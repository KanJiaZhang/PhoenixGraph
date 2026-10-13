# Operations

How to run PhoenixGraph, how to back it up, how to get it back, and what to check when it misbehaves. Written
for the person on call, not for the person who wrote the code.

> Everything here is exercised by a check or a script in this repository, and the ones marked *drill* are run
> by `verification/run.mjs` against a live instance. Numbers and messages are the ones this machine produced.

## 1. What runs where

| Piece | Port | State | Notes |
|---|---|---|---|
| `graph-http` | 8081 | graphs are defined in its configuration; RocksDB directories live under `phoenixgraph.storage.rocksdb-root` | the engine, the query API, the write API, `/api/v1/health` |
| `graph-web` | 8080 | none (it proxies `/api` to 8081) | the UI; a restart costs nothing |
| Aerospike | 3000 | the keyed graphs | one container in the compose file; a real deployment uses the Aerospike operator or a managed service |
| RocksDB directories | a volume | the analytical graphs | **one process per directory** - a second service pointed at the same path fails to open it |

Start everything with `tools/start-phoenixgraph.sh` (builds) or `--no-build`; stop with
`tools/stop-phoenixgraph.sh`; containers with `docker compose up -d`.

## 2. Configuration an operator actually changes

Everything under `phoenixgraph.*` can be set from the environment (`PHOENIXGRAPH_STORAGE_HOST`,
`PHOENIXGRAPH_SECURITY_ENABLED`, `PHOENIXGRAPH_LIMITS_REQUEST_TIMEOUT`, ...) or from an extra file:

```bash
java -jar graph-http.jar \
  --spring.config.additional-location=file:/etc/phoenixgraph/application.yml
```

| Setting | Why you would touch it |
|---|---|
| `phoenixgraph.storage.rocksdb-root` | where the analytical graphs live (a mounted volume). **Created if missing.** |
| `phoenixgraph.storage.schema-dir` | a directory of schema JSON files; every one becomes a graph, no image rebuild. Vertex set = the file name with what a set name cannot hold folded to `_`. **Each file needs its own `"id"`** - two graphs sharing a schema id are refused at startup (`Graph schema id N is already registered as ...`), which is what an operator meets the first time they copy a file to make a second graph |
| `phoenixgraph.storage.graphs[]` | the explicit list (primary graph first); an entry **wins** over a file of the same name, so mounting a directory can never silently re-point a graph |
| `phoenixgraph.limits.*` | `max-concurrent`, `queue-wait`, `per-graph-concurrent`, `per-graph-per-second`, `request-timeout` |
| `phoenixgraph.security.*` | `enabled`, and the keys with their role and graph scope (token or its SHA-256) |
| `server.ssl.*` + `PHOENIXGRAPH_TLS_PASSWORD` | serve HTTPS from the process instead of terminating TLS in front of it |

Adding a graph without touching the image (*drill*: `verification/verify-backup-restore.mjs`):

```bash
cp /etc/phoenixgraph/schemas/new-graph.json /mnt/schemas/     # the schema says its own storage
#   ... and give the copy its own "id" and "name" before restarting
# restart, then:
curl -s localhost:8081/api/v1/graphs | grep new-graph
```

## 2b. Running against a replicated store

`deploy/aerospike-cluster/docker-compose.yml` starts a **two-node cluster with replication-factor 2** (with the
comments that came out of getting it to work: the mesh seeds belong inside `network.heartbeat`, Aerospike 8 has
no `memory-size`, and the image's entrypoint would otherwise overwrite the configuration). With it up:

```bash
docker compose -f deploy/aerospike-cluster/docker-compose.yml up -d
docker exec aerospike-1 asinfo -v statistics | tr ';' '\n' | grep cluster_size   # -> 2
node verification/verify-aerospike-ha.mjs                                        # the whole drill
```

What the drill measures: killing one node costs **1 s** of failover and no correctness, writes continue while it
is down, the node rejoins in 2 s and replication catches up in 1 s, and a *running* engine keeps answering even
if the node it was configured with is the one that dies. **The engine takes one storage seed**, so a restart
*while that seed is down* cannot connect - point `phoenixgraph.storage.host` at a load-balanced address
(`docs/CAPACITY.md` §6 lists this as the known gap).

## 3. Backups

Two kinds of graph, two kinds of backup. `GET /api/v1/graphs/{graph}/storage` says which one applies:

```bash
curl -s localhost:8081/api/v1/graphs/payment-graph/storage
# {"graph":"payment-graph","storage":"Aerospike","vertexSet":"http_demo_v3", ...,
#  "backup":"asbackup -n test -s http_demo_v3 (see docs/OPERATIONS.md)"}
curl -s localhost:8081/api/v1/graphs/tpc-h-data/storage
# {"graph":"tpc-h-data","storage":"RocksDB-Edge","directory":".rocksdb\\tpc-h-data", ...,
#  "backup":"POST /api/v1/graphs/tpc-h-data/checkpoint (a hard-linked RocksDB snapshot)"}
```

**RocksDB - a checkpoint of the directory.** Consistent, made of hard links (a few kilobytes and a moment),
and the service keeps serving:

```bash
tools/backup-graph.sh tpc-h-data                 # -> ./backups/tpc-h-data-<stamp>/
# or directly:
curl -s -X POST "localhost:8081/api/v1/graphs/tpc-h-data/checkpoint?dir=/backups/tpc-h-data-$(date -u +%Y%m%d)"
```

The target directory must not exist (RocksDB creates it; a checkpoint must not be written into something that
already holds data). Put the copy on another disk: a checkpoint on the same disk is not a backup.

**Aerospike - the database's own tooling.** `asbackup` runs inside the container and writes a set of files:

```bash
tools/backup-graph.sh payment-graph              # docker exec asbackup + docker cp + a manifest
# or directly:
docker exec $(docker ps -qf name=aerospike) asbackup -h 127.0.0.1 -n test -s http_demo_v3 -d /tmp/backup
docker cp $(docker ps -qf name=aerospike):/tmp/backup ./backups/payment-graph-$(date -u +%Y%m%d)
```

## 4. Restoring (and proving it)

**RocksDB.** Stop the service that holds the graph (one process per directory), keep the old directory aside,
put the copy where the schema points:

```bash
tools/stop-phoenixgraph.sh
tools/restore-graph.sh ./backups/tpc-h-data-20261013-080000 --rocksdb-root .rocksdb --into tpc-h-data
tools/start-phoenixgraph.sh --no-build
# then compare a query with the answer you recorded before the backup
```

**Aerospike.** Two flags are needed, and getting them wrong is silent - this was found by the drill:

```bash
docker exec $C asrestore -h 127.0.0.1 -n test -s http_demo_v3 --replace --no-generation -d /tmp/backup
```

- **`--replace`**: without it `asrestore` *skips* a record that already exists. A deleted vertex still exists
  (see §7), so a plain restore skips exactly the records you are trying to bring back.
- **`--no-generation`**: even with `--replace`, it refuses records whose stored copy looks "fresher"
  (`failed N (fresher N)`) and reports success while changing nothing.
- With both: `inserted 246: failed 0 (existed 0, fresher 0)`, and the deleted vertex answers again.

**The drill that proves the whole path** (`verification/verify-backup-restore.mjs`): write a probe vertex →
`asbackup` → delete it → assert it is gone → `asrestore --replace --no-generation` → assert it is back → clean
up. For RocksDB: checkpoint a loaded graph, serve the copy from a second instance, assert the answers are
identical (not just non-empty: 8,000 vertices and the same revenue per year), stop it with `SIGTERM`, start it
again, assert the same answer again.

## 5. Upgrades and rollback

1. Take a backup (§3) - a checkpoint for every RocksDB graph, an `asbackup` for the Aerospike sets.
2. Stop the service gracefully (`tools/stop-phoenixgraph.sh`, which sends `SIGTERM`: the container stops
   accepting requests, lets in-flight ones finish for up to `spring.lifecycle.timeout-per-shutdown-phase`, then
   the metrics are flushed and the RocksDB directories are closed).
3. Deploy the new build (a new jar or image tag - never `latest`, see `CHANGELOG.md`).
4. Watch `/api/v1/health` and the first queries; the log line per request carries the status and the latency.
5. Rollback is "deploy the previous tag". Schema changes within `v1` are additive; a *store* written by a newer
   build is not promised to be readable by an older one (that is what the backup in step 1 is for).

Graceful shutdown matters more than it looks: the metrics timer fires every 30 s, so a `kill -9` loses up to
half a minute of counters, and RocksDB must be closed cleanly.

## 6. What to watch

| Signal | Where | Threshold to start looking |
|---|---|---|
| request latency | `/api/v1/metrics/{graph}` (`queries.*`), and the per-request log line | p99 above the SLO (see `docs/SLO.md`) |
| refused requests | `phoenixgraph_requests_refused_total` in `/api/v1/metrics/prometheus` | any sustained rise: the gate is telling you the instance is saturated |
| storage failures | `storage.*.failedTotal` per graph | above zero: the store is unhappy (disk, network, or a schema mismatch) |
| deleted-but-present data | `GET /api/v1/vertices/{id}` after a delete | should be 404 (§7) |
| who called what | `GET /api/v1/audit/recent?limit=100` (admin key), or the `com.pengcheng.phoenixgraph.audit` logger | an unexpected key, or 401/403 from a source you do not recognise |

## 7. Known traps (each one cost somebody an afternoon)

- **A delete is a marker, not a removal.** `DELETE /api/v1/vertices/{id}` writes an expiry sentinel into the
  record's `lastUpdatedTime` bin; reads hide it from then on (fixed in `0.2.0`: point reads, batch reads, scans
  and subgraph expansion all honour it now - a drill found that they did not, by deleting a vertex and reading
  it back after a restart). A restore therefore has to overwrite, see §4.
- **One process per RocksDB directory.** A second service pointed at the same path fails to open it. A backup is
  a *copy*; serve the copy from another instance (that is what the drill does) rather than from two processes
  on one directory.
- **A fresh root must exist for RocksDB** - now created at startup, but a *container* with a read-only mount
  will still fail; check the volume.
- **An engine in a container must share a network with its store.** An Aerospike node tells its clients which
  addresses to use next, and those are container addresses: a process on the host cannot reach them
  (`Peers not reachable: 172.18.0.3 3000`), and on Docker Desktop's default bridge a container *name* does not
  resolve either. Put both on a user-defined network and use the service name
  (`deploy/aerospike-cluster/docker-compose.yml`), or keep the engine on the host and publish the store's port
  as the served deployment does. `verification/verify-aerospike-ha.mjs` runs its engine inside the network;
  `verification/verify-multi-instance.mjs` runs two engines that reach the store through
  `host.docker.internal`.
- **`docker ps -f name=aerospike` also matches `aerospike-1`/`aerospike-2`.** The scripts here pin
  `name=^aerospike$`: once a drill cluster exists, a loose filter picks up the wrong store.
- **`docker exec` paths from Git Bash**: MSYS rewrites `/tmp/...` into `C:/cygwin64/tmp/...` before `docker`
  sees it, so `asbackup -d /tmp/x` writes nowhere. Set `MSYS2_ARG_CONV_EXCL='*'` (the scripts here do).
- **The jar cannot be rebuilt while the service runs** (Windows holds it): stop the service first.
- **`g.V('Entity')` on a keyed store** is an error, not an empty answer: a whole-type scan needs the RocksDB
  scheme (`docs/DATA-QUALITY.md` §1 and the README's storage guide).
