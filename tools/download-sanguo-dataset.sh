#!/usr/bin/env bash
#
# Downloads the 《三国演义》 knowledge-graph dataset (the 三国演义 part of the
# OpenKG "四大名著人物关系知识图谱和 OWL 本体" dataset, as mirrored by the
# wcswcswcs/sgyy repository) and stores it gzip-compressed next to the tests
# that read it.
#
#   ./tools/download-sanguo-dataset.sh
#
# Contents: 4,487 person documents (renwu_process_v1.csv), the OWL ontology
# (theory.owl) and the curated relation triples (triples.csv); ~1 MB gzipped.
#
# Every download is shape-checked (line count + newline-terminated), because a
# truncated file is still valid gzip — see verify() below.
set -euo pipefail

BASE_URL="https://raw.githubusercontent.com/wcswcswcs/sgyy/master"
TARGET_DIR="$(cd "$(dirname "$0")/.." && pwd)/graph-integration-test/src/test/resources/kg/sanguo"

mkdir -p "$TARGET_DIR"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

download() {
  local url="$1" out="$2" expected="$3"
  echo "downloading $(basename "$url")"
  curl -fsSL --retry 3 --retry-delay 2 -o "$tmp/$(basename "$out")" "$url"
  gzip -9 -c "$tmp/$(basename "$out")" > "$TARGET_DIR/$out"
  echo "  -> $TARGET_DIR/$out ($(wc -c < "$TARGET_DIR/$out") bytes)"
  verify "$out" "$expected"
}

# A truncated download is valid gzip but half a dataset: an interrupted `curl` once left
# renwu_process_v1.csv at 331 of 4,487 persons and every test still passed. Check the
# shape, not just the exit code.
verify() {
  local out="$1" expected="$2" actual
  actual="$(gzip -dc "$TARGET_DIR/$out" | wc -l | tr -d ' ')"
  if [ "$actual" != "$expected" ]; then
    echo "ERROR: $out has $actual lines, expected $expected" >&2
    exit 1
  fi
  if [ "$(gzip -dc "$TARGET_DIR/$out" | tail -c 1 | od -An -c | tr -d ' ')" != '\n' ]; then
    echo "ERROR: $out does not end with a newline (truncated download)" >&2
    exit 1
  fi
  echo "  ok: $actual lines, newline-terminated"
}

# the person documents (the "文档数据集": one record per 三国演义 person).
# 4,488 lines = header + 4,487 persons; the file is ~2.7 MB raw, ~1 MB gzipped.
download "$BASE_URL/data/renwu_process_v1.csv" "renwu_process_v1.csv.gz" 4488
# the curated relation triples extracted upstream
download "$BASE_URL/data/data/%E4%B8%89%E5%9B%BD%E6%BC%94%E4%B9%89/triples.csv" "triples.csv.gz" 154
# the OWL ontology (entity / relation split)
download "$BASE_URL/data/data/%E4%B8%89%E5%9B%BD%E6%BC%94%E4%B9%89/theory.owl" "theory.owl.gz" 823

echo "done"
