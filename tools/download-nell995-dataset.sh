#!/usr/bin/env bash
#
# Downloads the NELL-995 triple dataset (as distributed by the OpenKE benchmark)
# and stores it gzip-compressed next to the tests that read it.
#
#   ./tools/download-nell995-dataset.sh
#
# Contents: 75,492 entities, 200 relations, 149,678 triples (~1.5 MB gzipped).
set -euo pipefail

BASE_URL="https://raw.githubusercontent.com/thunlp/OpenKE/OpenKE-PyTorch/benchmarks/NELL-995"
TARGET_DIR="$(cd "$(dirname "$0")/.." && pwd)/graph-integration-test/src/test/resources/kg/nell995"

mkdir -p "$TARGET_DIR"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

for file in entity2id relation2id train2id; do
  echo "downloading ${file}.txt"
  curl -fsSL --retry 3 --retry-delay 2 -o "$tmp/${file}.txt" "$BASE_URL/${file}.txt"
  gzip -9 -c "$tmp/${file}.txt" > "$TARGET_DIR/${file}.txt.gz"
  echo "  -> $TARGET_DIR/${file}.txt.gz ($(wc -c < "$TARGET_DIR/${file}.txt.gz") bytes)"
done

echo "done"
