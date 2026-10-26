#!/usr/bin/env bash
# Run the three-minute proof against a running service.
set -euo pipefail
cd "$(dirname "$0")/.."

API="${PHOENIXGRAPH_API:-http://localhost:8081}"
if ! curl -fsS "$API/api/v1/health" >/dev/null 2>&1; then
  echo "PhoenixGraph is not answering at $API" >&2
  echo "Start it with: tools/start-phoenixgraph.sh" >&2
  exit 1
fi
exec node examples/three-minute-demo/demo.mjs "$@"
