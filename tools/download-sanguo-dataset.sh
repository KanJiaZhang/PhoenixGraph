#!/usr/bin/env bash
#
# Downloads the 《三国演义》 knowledge-graph dataset (the 三国演义 part of the
# OpenKG "四大名著人物关系知识图谱和 OWL 本体" dataset, as mirrored by the
# wcswcswcs/sgyy repository) and stores it gzip-compressed next to the tests
# that read it.
#
#   ./tools/download-sanguo-dataset.sh
#
# Contents: 331 person documents (renwu_process_v1.csv), the OWL ontology
# (theory.owl) and the curated relation triples (triples.csv); ~80 KB gzipped.
set -euo pipefail

BASE_URL="https://raw.githubusercontent.com/wcswcswcs/sgyy/master"
TARGET_DIR="$(cd "$(dirname "$0")/.." && pwd)/graph-integration-test/src/test/resources/kg/sanguo"

mkdir -p "$TARGET_DIR"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

download() {
  local url="$1" out="$2"
  echo "downloading $(basename "$url")"
  curl -fsSL --retry 3 --retry-delay 2 -o "$tmp/$(basename "$out")" "$url"
  gzip -9 -c "$tmp/$(basename "$out")" > "$TARGET_DIR/$out"
  echo "  -> $TARGET_DIR/$out ($(wc -c < "$TARGET_DIR/$out") bytes)"
}

# the person documents (the "文档数据集": one record per 三国演义 person)
download "$BASE_URL/data/renwu_process_v1.csv" "renwu_process_v1.csv.gz"
# the curated relation triples extracted upstream
download "$BASE_URL/data/data/%E4%B8%89%E5%9B%BD%E6%BC%94%E4%B9%89/triples.csv" "triples.csv.gz"
# the OWL ontology (entity / relation split)
download "$BASE_URL/data/data/%E4%B8%89%E5%9B%BD%E6%BC%94%E4%B9%89/theory.owl" "theory.owl.gz"

echo "done"
