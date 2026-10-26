#!/usr/bin/env bash
# Load a CSV file with graph-batch-load, with the classpath assembled from Maven.
#
#   AEROSPIKE_VERTEX_SET=scale_v1 GRAPH_SCHEMA=payment-graph \
#     tools/bench-load.sh .datasets/scale/vertices.csv
#
# The Aerospike connection is read from AEROSPIKE_HOST / AEROSPIKE_PORT / AEROSPIKE_NAMESPACE;
# AEROSPIKE_BATCH_SIZE (default 1000) controls the merge/flush window.
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "$#" -lt 1 ]; then
  echo "Usage: tools/bench-load.sh <csv-file>" >&2
  exit 1
fi

./mvnw -q -o -pl graph-batch-load -am -DskipTests package
classpath_file="$(mktemp)"
trap 'rm -f "$classpath_file"' EXIT
./mvnw -q -o -pl graph-batch-load dependency:build-classpath -Dmdep.outputFile="$classpath_file"
classpath="$(cat "$classpath_file")"
separator=":"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) separator=";" ;;
esac
schema_dir="$(pwd)/graph-http/src/main/resources"
exec java -cp "graph-batch-load/target/classes${separator}${classpath}" \
  com.pengcheng.phoenixgraph.batchload.BatchLoadMain "$@" "$schema_dir"
