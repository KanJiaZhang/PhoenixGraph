# Restoring the demo graphs

The RocksDB graphs live in files under `.rocksdb/` and survive anything. The **Aerospike** graphs live in the
container's namespace, so replacing the container (or `docker compose down -v`) empties them: the graphs still
*exist* — their schemas are in `graph-http/src/main/resources` — but they answer with nothing.

This page is the runbook for putting them back, with the numbers a restore produced on this machine
(2026-11-03, `aerospike/aerospike-server:8.1.2.5`, namespace `test`).

## 1. What each graph needs

| Graph | Storage | Where the data comes from | Restored size |
|---|---|---|---|
| `payment-graph` | Aerospike (`http_demo_v3`) | `tools/generate-payment-graph.mjs` (this repository's generator) | 6 000 accounts, 3 000 IPs, 2 400 phones, ~24 000 payments — 11 400 objects |
| `payment-graph-as-edge` | Aerospike-Edge (`payment_as_edge_v1`) | the same generator, pointed at the graph | 2 000 accounts — 3 801 objects |
| `sanguo-kg` | Aerospike (`kg_sanguo_v1`) | `SanguoGraphIntegrationTest` (seeds `SanguoDataset`) | 11 418 vertices, 21 162 edges; 6 202 entities / 21 050 relations / 46 632 RDF triples |
| `kg-entity-graph` | Aerospike (`kg_nell995_v2`) | `KnowledgeGraphIntegrationTest` (NELL995, bundled under `graph-integration-test/src/test/resources/kg/nell995`) | 113 553 entity-shards, 149 678 edges (75 492 entities, 200 relations); hub degree 2 237 |
| `agent-demo` | Aerospike (`agent_demo_v2`) | `POST /api/v1/agent/ingest` with `docs/agent-demo/*.md` and a dictionary | 14 entities, 15 relations, provenance 1.0 |
| `context-graph` | Aerospike (`incident_triage_v1`) | `verification/verify-incident-triage.mjs` | 41 entities, 64 relations, 122 objects |
| `demo-data`, `demo-semantics`, `tpc-h-data`, `tpc-h-analytics`, `sanguo-kg-rocksdb-edge`, `sanguo-kg-text`, `payment-graph-rocksdb-edge`, `payment-graph-smart-rocksdb` | RocksDB | files under `.rocksdb/` — nothing to do | (on disk) |

## 2. Restore commands

```bash
tools/start-phoenixgraph.sh --no-build          # 8081 (and the Aerospike container) first

# payment graph: the generated distribution (20k accounts by default, see --accounts)
node tools/generate-payment-graph.mjs --accounts 6000 --graph payment-graph --skip-vectors
node tools/generate-payment-graph.mjs --accounts 2000 --graph payment-graph-as-edge --skip-vectors

# knowledge graphs, one loader each (they write straight into Aerospike)
mvn -o -pl graph-integration-test -am test -Dtest=SanguoGraphIntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false
KG_RESEED=true KG_PARALLELISM=1 mvn -o -pl graph-integration-test -am test \
    -Dtest=KnowledgeGraphIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false

# the agent demo corpus
node -e '...'   # POST /api/v1/agent/ingest with docs/agent-demo/*.md (see §4)

# the incident/context graph
node verification/verify-incident-triage.mjs
```

## 3. The payment distribution (why it is generated, not copied)

The Java loaders seed the *curated* demo — a handful of rings, because the fuzzy tests assert exact keys and
lengths. A graph that only holds those is useless for the query docs, the UI and the benchmarks, so
`tools/generate-payment-graph.mjs` builds a payment network with a shape worth querying:

- **accounts** carry the schema's eight-dimensional behaviour vector in three spending clusters, so `nearest(k)`
  returns a cluster rather than a random set;
- **payments** follow a log-normal amount and a zipf-ish target choice: measured on 2 000 accounts,
  out-degree p50 3 / p90 7 / p99 31 / max 444 and in-degree p50 3 / p90 8 / p99 28 / max 403 — a few hubs, a long
  tail of payers, which is what a 2-hop or fan-out query is for;
- an IP is shared by 1–4 accounts and a phone by 1–4, so `in(IPToAccount).out(IPToAccount)` finds neighbours;
- `100`, `135` and `1000` keep the degrees the docs quote (`V(135).out(Payment)`, `V(1000).inE(Payment)`).

It is deterministic (one seed), additive (ids carry a family prefix, so it does not overwrite the curated `fz_*`
demo), and it reports the degree percentiles it produced.

**Vectors are a second pass.** Writing a vertex through the batch API also inserts its vector into the HNSW index,
which for 20 000 accounts is slower than everything else combined. The generator writes the rows first, then the
vectors as *property updates* (which do not re-index), then rebuilds the index once — `--skip-vectors` skips both.

## 4. The one defect this restore found (and how to avoid it)

The knowledge-graph seeders accept `KG_PARALLELISM` (default 8) and document that "parallel writers race on the
per-vertex edge bins, which is resolved by re-writing". **It is not resolved**: seeding NELL995 with parallelism 8
wrote 149 678 edges and left the top hub with a degree of 377 instead of 2 237 (about 84 % of the incident edges
lost to lost updates on the same vertex bin). Re-seeding with `KG_PARALLELISM=1` produced the exact degree:

```
[kg] hub vertex id      : concept_book_new_9   (degree 2237, relation 'concept:proxyfor')
[kg] multi-seed query   : … -> 2411 edges over 4 seeds
```

Sanguo shows the same class of loss (186 vs 252 incident edges for 曹操). **Restore with `KG_PARALLELISM=1`**:
21 s for NELL995's 113 553 vertices + 149 678 edges, 618 s for Sanguo's 11 418 vertices + 21 162 edges - and then
`g.V('曹操_2','曹操_3','曹操_5','曹操_6','曹操_7','曹操_8','曹操_9','Entity').bothE()` answers **252**, exactly what
the test expects. Treat a hub-degree mismatch as a seeding defect rather than a query bug. A proper fix belongs in `AerospikeEdgeStore`: the per-vertex edge bin needs an
optimistic/locked read-modify-write instead of last-writer-wins.

## 5. Verifying a restore

| Check | Expected |
|---|---|
| `g.V('135','Account').outE('Payment').count()` on `payment-graph` | a few hundred (the generator adds payments to that hub on every run) |
| `g.V('acct-000001','Account').nearest(3)` on `payment-graph` | three accounts from the same spending cluster (the vector index was rebuilt) |
| `g.V('concept_book_new_9','Entity').bothE('Entity_Entity').count()` on `kg-entity-graph` | 2 237 (parallelism 1) |
| the union over 曹操's shards on `sanguo-kg` | 252 |
| `g.V('MarketingDay').count()` on `demo-data` | 1 152 (RocksDB, untouched) |
| `POST /api/v1/agent/context` on `agent-demo` | relations with `provenanceRate` 1.0 |

`docker exec aerospike asinfo -v sets` lists the object count per set, which is the quickest way to see whether a
keyed graph has anything in it at all.
