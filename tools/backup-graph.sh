#!/usr/bin/env bash
#
# Backs up one graph. What "a backup" means depends on where the graph lives, and the script does the right
# thing for both schemes rather than pretending they are the same:
#
#   RocksDB graphs   the graph *is* its directory, so the backup is RocksDB's own checkpoint: a consistent
#                    snapshot made of hard links, taken through the API while the service keeps serving.
#                    Restoring is "point a service at the copy" (tools/restore-graph.sh).
#   Aerospike graphs the backup belongs to the database: `asbackup` runs inside the Aerospike container and
#                    writes a set of files that `asrestore` can load, into the set of your choosing.
#
#   tools/backup-graph.sh <graph> [--dir <directory>] [--api http://localhost:8081]
#
# Examples
#   tools/backup-graph.sh tpc-h-data                       # -> ./backups/tpc-h-data-<stamp>/
#   tools/backup-graph.sh payment-graph --dir /mnt/backups
#
# The script prints the path it wrote and nothing else on success, so a cron job can log it as is.
set -euo pipefail

GRAPH="${1:-}"
shift || true
DIR="./backups"
API="http://localhost:8081"
AEROSPIKE_CONTAINER="${AEROSPIKE_CONTAINER:-$(docker ps -qf name=^aerospike$ | head -1)}"
NAMESPACE="${PHOENIXGRAPH_STORAGE_NAMESPACE:-test}"

while [ $# -gt 0 ]; do
  case "$1" in
    --dir) DIR="$2"; shift 2 ;;
    --api) API="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$GRAPH" ]; then
  echo "usage: tools/backup-graph.sh <graph> [--dir <directory>] [--api <url>]" >&2
  exit 2
fi

STAMP="$(date -u +%Y%m%d-%H%M%S)"
TARGET="$DIR/$GRAPH-$STAMP"
mkdir -p "$DIR"

# Which storage does this graph use? The API answers that, so the script does not have to parse a schema.
STORAGE="$(curl -fsS "$API/api/v1/graphs" | tr ',' '\n' | awk -v g="\"$GRAPH\"" '
  index($0, "\"name\":" g) > 0 { found=1 }
  found && index($0, "\"storage\":") > 0 { value=$0; sub(/.*"storage":"/, "", value); sub(/".*/, "", value); print value; exit }')"

if [ -z "$STORAGE" ]; then
  echo "!! '$GRAPH' is not served by $API (see $API/api/v1/graphs)" >&2
  exit 1
fi

case "$STORAGE" in
  *RocksDB*)
    # the checkpoint endpoint returns JSON with the path it wrote; move it under the backup directory
    RESPONSE="$(curl -fsS -X POST "$API/api/v1/graphs/$GRAPH/checkpoint?dir=$TARGET")"
    FILES="$(printf '%s' "$RESPONSE" | tr ',' '\n' | awk -F: '/"files"/ { gsub(/[^0-9]/, "", $2); print $2 }')"
    BYTES="$(printf '%s' "$RESPONSE" | tr ',' '\n' | awk -F: '/"bytes"/ { gsub(/[^0-9]/, "", $2); print $2 }')"
    if [ ! -d "$TARGET" ]; then
      echo "!! the checkpoint endpoint did not write $TARGET: $RESPONSE" >&2
      exit 1
    fi
    echo "$TARGET ($FILES files, $BYTES bytes)"
    ;;
  *Aerospike*)
    if [ -z "$AEROSPIKE_CONTAINER" ]; then
      echo "!! no Aerospike container is running; start it (tools/start-phoenixgraph.sh) or set AEROSPIKE_CONTAINER" >&2
      exit 1
    fi
    SET="$(curl -fsS "$API/api/v1/graphs/$GRAPH/storage" | tr ',' '\n' | awk -F'"' '/"vertexSet"/ { print $4; exit }')"
    if [ -z "$SET" ] || [ "$SET" = "null" ]; then
      echo "!! cannot read the vertex set of '$GRAPH' from $API/api/v1/graphs/$GRAPH/storage" >&2
      exit 1
    fi
    mkdir -p "$TARGET"
    docker exec "$AEROSPIKE_CONTAINER" asbackup -h 127.0.0.1 -n "$NAMESPACE" -s "$SET" -d "/tmp/pg-backup-$STAMP" >/dev/null
    docker cp "$AEROSPIKE_CONTAINER:/tmp/pg-backup-$STAMP/." "$TARGET/" >/dev/null
    docker exec "$AEROSPIKE_CONTAINER" rm -rf "/tmp/pg-backup-$STAMP"
    # a manifest, because an asbackup directory does not say which set it came from
    printf '{"graph":"%s","storage":"%s","namespace":"%s","set":"%s","at":"%s"}\n' \
      "$GRAPH" "$STORAGE" "$NAMESPACE" "$SET" "$STAMP" > "$TARGET/manifest.json"
    echo "$TARGET ($(find "$TARGET" -type f | wc -l | tr -d ' ') files, set $SET)"
    ;;
  *)
    echo "!! '$GRAPH' uses storage '$STORAGE', which this script does not know how to back up" >&2
    exit 1
    ;;
esac
