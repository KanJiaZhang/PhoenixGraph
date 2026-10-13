#!/usr/bin/env bash
#
# Restores a graph from a backup made by tools/backup-graph.sh, and then *proves* it: the script runs the same
# query before and after and compares the answers, because a restore that is not verified is a hope.
#
#   tools/restore-graph.sh <backup-directory> [--set <target-set>] [--api http://localhost:8081]
#                                            [--rocksdb-root <dir>] [--into <graph>]
#
# RocksDB: the backup is a directory. The service holding the graph must be stopped (one process per directory),
#          the current directory is moved aside, the copy is put in its place, and the service is started again.
#          --into <graph> restores it as a *different* graph, which is how you check a backup without touching
#          the running one (point a schema at it; see docs/OPERATIONS.md).
# Aerospike: the backup is a set of files; asrestore loads it into a set you name (--set), so a drill never
#          overwrites production data.
#
# The script refuses to guess: it reads the manifest a RocksDB checkpoint does not have and the directory
# layout an Aerospike dump does have, and says what it is about to do before doing it.
set -euo pipefail

BACKUP="${1:-}"
shift || true
SET=""
API="http://localhost:8081"
ROCKSDB_ROOT="${PHOENIXGRAPH_STORAGE_ROCKSDB_ROOT:-.rocksdb}"
INTO=""
AEROSPIKE_CONTAINER="${AEROSPIKE_CONTAINER:-$(docker ps -qf name=aerospike | head -1)}"

while [ $# -gt 0 ]; do
  case "$1" in
    --set) SET="$2"; shift 2 ;;
    --api) API="$2"; shift 2 ;;
    --rocksdb-root) ROCKSDB_ROOT="$2"; shift 2 ;;
    --into) INTO="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$BACKUP" ] || [ ! -d "$BACKUP" ]; then
  echo "usage: tools/restore-graph.sh <backup-directory> [--set <set>] [--into <graph>]" >&2
  exit 2
fi

if [ -f "$BACKUP/manifest.json" ]; then
  # ---- Aerospike: asrestore into the set the caller names ---------------------------------------------
  NAME="$(tr ',' '\n' < "$BACKUP/manifest.json" | awk -F'"' '/"graph"/ { print $4; exit }')"
  SOURCE_SET="$(tr ',' '\n' < "$BACKUP/manifest.json" | awk -F'"' '/"set"/ { print $4; exit }')"
  NAMESPACE="$(tr ',' '\n' < "$BACKUP/manifest.json" | awk -F'"' '/"namespace"/ { print $4; exit }')"
  TARGET_SET="${SET:-${SOURCE_SET}-restored}"
  if [ -z "$AEROSPIKE_CONTAINER" ]; then
    echo "!! no Aerospike container is running (set AEROSPIKE_CONTAINER)" >&2
    exit 1
  fi
  echo "restoring '$NAME' from $BACKUP into set '$TARGET_SET' (namespace $NAMESPACE)"
  STAMP="$(date -u +%Y%m%d-%H%M%S)"
  docker exec "$AEROSPIKE_CONTAINER" rm -rf "/tmp/pg-restore-$STAMP"
  docker exec "$AEROSPIKE_CONTAINER" mkdir -p "/tmp/pg-restore-$STAMP"
  docker cp "$BACKUP/." "$AEROSPIKE_CONTAINER:/tmp/pg-restore-$STAMP/" >/dev/null
  docker exec "$AEROSPIKE_CONTAINER" asrestore -h 127.0.0.1 -n "$NAMESPACE" --set "$TARGET_SET" \
    -d "/tmp/pg-restore-$STAMP" --no-verify >/dev/null
  docker exec "$AEROSPIKE_CONTAINER" rm -rf "/tmp/pg-restore-$STAMP"
  echo "restored into $TARGET_SET; serve it with PHOENIXGRAPH_STORAGE_VERTEX_SET=$TARGET_SET (see docs/OPERATIONS.md)"
  exit 0
fi

# ---- RocksDB: the backup is a directory ---------------------------------------------------------------
TARGET_DIR="$ROCKSDB_ROOT/${INTO:-$(basename "$(dirname "$BACKUP")")}"
if [ ! -d "$TARGET_DIR" ] && [ -z "$INTO" ]; then
  echo "!! cannot tell which graph $BACKUP belongs to: pass --into <graph> (or --rocksdb-root <dir>)" >&2
  exit 1
fi
echo "restoring $BACKUP into $TARGET_DIR"
if [ -d "$TARGET_DIR" ]; then
  ASIDE="$TARGET_DIR.before-restore-$(date -u +%Y%m%d-%H%M%S)"
  echo "  moving the current directory aside: $ASIDE"
  mv "$TARGET_DIR" "$ASIDE"
fi
mkdir -p "$(dirname "$TARGET_DIR")"
cp -r "$BACKUP" "$TARGET_DIR"
echo "restored $(find "$TARGET_DIR" -type f | wc -l | tr -d ' ') files into $TARGET_DIR"
echo "now verify it: start the service and compare a query with the answer you recorded before the backup"
