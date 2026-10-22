#!/usr/bin/env bash
# Back up every graph the service lists, with per-graph retention.
#
#   tools/backup-all.sh [--dir ./backups] [--api http://localhost:8081] [--keep 7] [--dry-run]
#
# This is a snapshot + retention policy, not point-in-time recovery: each run takes a RocksDB checkpoint or an
# `asbackup` set. Continuous PITR needs a write log the engine does not keep (docs/OPERATIONS.md).
set -euo pipefail
cd "$(dirname "$0")/.."

DIR="./backups"
API="http://localhost:8081"
KEEP=7
DRY_RUN=false
while [ $# -gt 0 ]; do
  case "$1" in
    --dir) DIR="$2"; shift 2 ;;
    --api) API="$2"; shift 2 ;;
    --keep) KEEP="$2"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    --help|-h)
      echo "Usage: tools/backup-all.sh [--dir ./backups] [--api URL] [--keep 7] [--dry-run]"
      exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
if ! [ "$KEEP" -ge 1 ] 2>/dev/null; then
  echo "--keep must be a positive integer" >&2
  exit 2
fi
mkdir -p "$DIR"

graphs="$(curl -fsS "$API/api/v1/graphs" | node -e '
  let body = "";
  process.stdin.on("data", (chunk) => { body += chunk; });
  process.stdin.on("end", () => {
    const parsed = JSON.parse(body);
    process.stdout.write(parsed.map((graph) => graph.name).join("\n"));
  });
')"
if [ -z "$graphs" ]; then
  echo "No graphs returned by $API/api/v1/graphs" >&2
  exit 1
fi

failures=0
while IFS= read -r graph; do
  [ -n "$graph" ] || continue
  if [ "$DRY_RUN" = true ]; then
    echo "would back up $graph into $DIR"
    continue
  fi
  echo "backing up $graph"
  if ! path="$(tools/backup-graph.sh "$graph" --dir "$DIR" --api "$API")"; then
    echo "  FAILED $graph" >&2
    failures=$((failures + 1))
    continue
  fi
  echo "  $path"
  # retention: keep the newest $KEEP directories for this graph
  shopt -s nullglob
  backups=("$DIR/$graph"-*)
  shopt -u nullglob
  if [ "${#backups[@]}" -gt "$KEEP" ]; then
    mapfile -t sorted < <(printf '%s\n' "${backups[@]}" | sort)
    remove=$(( ${#sorted[@]} - KEEP ))
    for ((i = 0; i < remove; i += 1)); do
      echo "  remove old ${sorted[$i]}"
      rm -rf "${sorted[$i]}"
    done
  fi
done <<< "$graphs"
exit "$failures"
