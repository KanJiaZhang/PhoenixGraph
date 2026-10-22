"""A minimal tour of the Python client.

Run against a service started with ``tools/start-phoenixgraph.sh``:

    python clients/python/examples/quickstart.py
"""
from phoenixgraph import PhoenixGraphClient

import sys

if hasattr(sys.stdout, "reconfigure"):
    # Windows consoles default to cp936; the API answers UTF-8.
    sys.stdout.reconfigure(encoding="utf-8")


def main() -> None:
    with PhoenixGraphClient() as client:
        print("graphs:", ", ".join(graph["name"] for graph in client.graphs()))

        answer = client.context("agent-demo", "诸葛亮和司马懿是什么关系")
        coverage = answer.get("coverage", {})
        print(f"\nanswer: {len(answer.get('entities', []))} entities, "
              f"{len(answer.get('relations', []))} relations, "
              f"{len(answer.get('citations', []))} citations "
              f"(coverage: {coverage})")
        for relation in answer.get("relations", []):
            print(f"  {relation['head']} -[{relation['relation']}]-> {relation['tail']}")
            print(f"    {relation.get('cite', '')} {relation.get('sentence', '')}")

        rows = client.query(
            "payment-graph",
            "g.V(135,'Account').bothE('Payment').otherV().limit(3)",
            return_subgraph=True,
        )
        print(f"\nquery returned {len(rows.get('result', []))} rows")


if __name__ == "__main__":
    main()
