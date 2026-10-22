# Quickstart — 10 minutes to the first query

Everything below is a copy-paste path to a running PhoenixGraph and four real answers. Commands were run on
the machine this repository is developed on (Windows + Git Bash, Docker Desktop, JDK 26; the build targets
Java 21) and the outputs shown are the ones it printed.

You need: **Docker** (for the Aerospike store), a **JDK 21+**, and this repository.

---

## 1. Start the services (2 minutes the first time, ~15 s after that)

```bash
git clone git@github.com:KanJiaZhang/PhoenixGraph.git
cd PhoenixGraph
export JAVA_HOME="C:/Program Files/Java/jdk-26.0.2.1"     # your JDK 21+

# Docker Desktop must be running (Aerospike is a container)
tools/start-phoenixgraph.sh
```

The script builds, starts Aerospike on `3000`, the engine/API on **8081** and the UI on **8080**, and waits
until both answer:

```
5/5  waiting for them to answer
  graph-http  up  (http://localhost:8081/api/v1/graphs)
  graph-web   up  (http://localhost:8080/)
```

Open **<http://localhost:8080/>** — that is the workbench. To restart without rebuilding, add
`--no-build`; to stop everything, `tools/stop-phoenixgraph.sh`.

Prefer containers for everything? `docker compose up --build` runs the same three services (see the header
of `docker-compose.yml`; `docker compose config --quiet` validates the file).

---

## 2. The first query (30 seconds)

```bash
curl -s -X POST localhost:8081/api/v1/query -H 'content-type: application/json' -d '{
  "graphName": "payment-graph",
  "query": "g.V(135,\u0027Account\u0027).bothE(\u0027Payment\u0027).otherV().limit(5)",
  "includeResult": true, "returnSubgraph": true }'
```

You get the traversed vertices with their properties, plus a `subgraph` block (`vertices` + `edges`) — the
same picture the UI draws. One request walks graph and vector steps; there is no client-side join:

```bash
# a vector search and a graph walk in one traversal (RocksDB-Edge instance)
curl -s -X POST localhost:8081/api/v1/query -H 'content-type: application/json' -d '{
  "graphName": "payment-graph-rocksdb-edge",
  "query": "g.V(1001,\u0027Account\u0027).nearest(5).outE(\u0027Payment\u0027)",
  "returnSubgraph": true }'
```

`GET /api/v1/graphs` lists what is served and which storage each graph reads.

---

## 3. The first graph algorithm (30 seconds)

```bash
curl -s -X POST localhost:8081/api/v1/query -H 'content-type: application/json' -d '{
  "graphName": "payment-graph",
  "query": "g.V(135,\u0027Account\u0027).bothE(\u0027Payment\u0027).otherV().pagerank()",
  "includeResult": true }'
```

`{"1000":0.0200058, "1001":0.0200186, ...}` — one score per reached vertex. The same step runs on the
distributed engine by writing `pagerank(DIST)`; with no cluster configured that is an error rather than a
silent fall back (see [docs/DESIGN.md](docs/DESIGN.md#distributed-execution-gemini)).

Available: `pagerank`, `louvain`, `cc`, `sssp`, `bfs`, and the four centralities (`degree`, `closeness`,
`betweenness`, `eigenvector`), each also in a `DIST` form.

---

## 4. The first agent tool call (1 minute)

The toolkit turns documents into a graph with provenance and vectors, then answers in one shape
(entities, relations, citations, coverage). The example graph `agent-demo` is already served:

```bash
# what can an agent call?
curl -s localhost:8081/api/v1/agent/tools | head -c 400

# one call: which entities is this question about, and what is between them?
printf '{"graph":"agent-demo","question":"%s"}' '诸葛亮和司马懿是什么关系' > /tmp/q.json
curl -s -X POST localhost:8081/api/v1/agent/context \
     -H 'content-type: application/json' --data-binary @/tmp/q.json
```

```
{"tool":"context","graph":"agent-demo","request":{"hops":1,"k":5,"question":"诸葛亮和司马懿是什么关系"},
 "entities":[{"id":"司马懿","type":"Person","name":"司马懿","score":0.6737,"via":"name",
              "cites":["三国志 · 诸葛亮传（示例文本） §2"],"chunkIds":["zhugeliang#1"]},
             {"id":"诸葛亮","type":"Person","name":"诸葛亮","score":0.6648,"via":"name",
              "cites":["三国志 · 关羽传（示例文本） §2", ...]}, ...]}
```

Write the body to a file (`--data-binary @file`) when the text is not ASCII: a shell that re-encodes the
argument will make the service reject the JSON as invalid UTF-8.

Ingest your own documents (this is the dry run — `apply: false` computes everything and writes nothing):

```bash
cat > /tmp/ingest.json << 'EOF'
{
  "graph": "quickstart-demo",
  "documents": [
    {"id": "guanyu", "title": "三国志 · 关羽传", "text": "关羽字云长，是刘备的义弟，也是刘备的臣。\n关羽斩杀颜良，又与文丑交战。"},
    {"id": "zhugeliang", "title": "三国志 · 诸葛亮传", "text": "诸葛亮是刘备的军师。\n诸葛亮与司马懿交战。"}
  ],
  "dictionary": {"关羽": "Person", "刘备": "Person", "诸葛亮": "Person", "司马懿": "Person", "颜良": "Person", "文丑": "Person"},
  "gold": ["关羽|killed|颜良", "诸葛亮|strategist_of|刘备"],
  "apply": false
}
EOF
curl -s -X POST localhost:8081/api/v1/agent/ingest \
     -H 'content-type: application/json' --data-binary @/tmp/ingest.json
```

```json
{"graph":"quickstart-demo","applied":false,"quality":{"documents":2,"chunks":2,"characters":54,
 "entities":6,"relations":6,"provenanceRate":1.0,"typeAgreement":1.0,"entityLinkRate":1.0,
 "chunkCoverage":1.0,"dictionaryCoverage":1.0,"gold":{"precision":0.3333,"recall":1.0,"f1":0.5}},
 "entities":6,"relations":6,"vertices":0,"edges":0}
```

Every metric there is computed from the extraction, not asserted: `recall 1.0` says both gold relations were
found, `precision 0.3333` says it also proposed four the gold set does not list. Set `"apply": true` to
write it (the graph is created on the first ingest, then later ingests upsert into it).

---

## 5. Check that it is really working (2 minutes)

```bash
node verification/run.mjs --list     # what can be checked, and what each check needs
node verification/run.mjs            # run them all (services must be up)
```

The suite asserts what the documentation claims — including every number in
[`docs/AGENT-CASES.md`](docs/AGENT-CASES.md) — by re-running the queries against the live service and reading the
values back from the DOM, the canvas pixels and the API.

---

## Where to go next

| I want to… | Read |
|---|---|
| see the value in three minutes | [examples/three-minute-demo](examples/three-minute-demo/README.md) — ingest, cited answer, refusal |
| call it from Python | [clients/python](clients/python/README.md) — zero-dependency client |
| ingest your own documents | [examples/business-demo](examples/business-demo/README.md) and `tools/ingest-docs.mjs` |
| understand what this is | [README](README.md) — and its **Status** section for what it is *not* |
| use the HTTP API | [docs/API.md](docs/API.md) (59 endpoints, generated from the controllers) |
| see two complete analyses | [docs/AGENT-CASES.md](docs/AGENT-CASES.md) — a knowledge-graph audit and a refused dimension |
| know which numbers to distrust | [docs/DATA-QUALITY.md](docs/DATA-QUALITY.md) |
| run it in production | [docs/ROADMAP-1.0.md](docs/ROADMAP-1.0.md) — M2 is the operator milestone, still ⬜ |
| pick a storage scheme | [docs/DESIGN.md](docs/DESIGN.md#one-engine-four-storage-schemes) (Aerospike for keyed OLTP, RocksDB for scans/analytics) |
| work on the engine | [CONTEXT.md](CONTEXT.md) is the local design notebook (gitignored), `verification/README.md` explains how to add a check |
