# The three-minute demo

This is the shortest path from a running service to the thing PhoenixGraph is for: **an answer with
citations, and a question the graph refuses to fake.**

It uses the two short documents in [`docs/agent-demo`](../../docs/agent-demo). They are a domain pack:
the bundled extractor knows the relation words of the 三国 corpus. Batch 2 of
[PRODUCT-PLAN.md](../../docs/PRODUCT-PLAN.md) makes rule packs data, so a business corpus (incidents,
contracts, tickets) can use the same pipeline.

## Run it

```bash
# terminal 1
tools/start-phoenixgraph.sh

# terminal 2
node examples/three-minute-demo/demo.mjs
```

or `tools/demo-3min.sh`.

The script:

1. checks the service is up;
2. runs ingestion as a dry run and prints the quality report;
3. writes the graph;
4. asks “诸葛亮和司马懿是什么关系” and prints entities, relations, citations and coverage;
5. asks “曹操的坐骑是什么颜色” and shows the relations the graph has about 曹操 - none of which answers the question;
6. asks the TPC-H semantic layer a question it must refuse.

The graph is named `demo-three-minute` by default. Override it with `PHOENIXGRAPH_DEMO_GRAPH`; point at a
different instance with `PHOENIXGRAPH_API`.

## What it writes

- a `demo-three-minute` graph in the running store (Aerospike by default);
- `demo-three-minute.json` in the working directory (the schema, gitignored);
- `demo-three-minute.agent.json` (the artifact, gitignored).

The demo is idempotent: a second run upserts the same documents and rewrites the artifact.

## Expected output (abridged)

```
1. Extract (dry run, nothing written)
  17 entities, 19 relations from 2 documents
  provenance 100% · type agreement 100% · linked 100%
2. Write the graph
  21 vertices, 42 edges
3. Ask a question
  question: 诸葛亮和司马懿是什么关系
  relations:
    - 诸葛亮 -[fought]-> 司马懿
      三国志 · 诸葛亮传（示例文本） §2: 诸葛亮与司马懿交战
  coverage: 7 entities, 6 relations, 3 citations
4. Ask something the corpus cannot support
  question: 曹操的坐骑是什么颜色
  what the graph knows about 曹操:
    - 张辽 -[subordinate_of]-> 曹操
    - 张辽 -[serves]-> 曹操
  2 relation(s) mention 曹操, and none of them is about a horse.
  The graph returned context; it did not answer the question. An agent must check the relation, not just the count.
```

This demo does not need the Python client. If you want the same calls from Python, see
[`clients/python`](../../clients/python).
