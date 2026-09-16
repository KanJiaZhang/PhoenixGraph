#!/usr/bin/env bash
#
# Downloads the SIFT small dataset (10,000 x 128-d base vectors + 100 queries +
# ground truth) used by the HNSW demo and integration test.
#
# Source: http://corpus-texmex.irisa.fr/ (the classic texmex corpus).
#
# Usage:
#   tools/download-siftsmall-dataset.sh [target-dir]     # default: .datasets/siftsmall
set -euo pipefail

TARGET="${1:-.datasets/siftsmall}"
URL="ftp://ftp.irisa.fr/local/texmex/corpus/siftsmall.tar.gz"
ARCHIVE="$(dirname "$TARGET")/siftsmall.tar.gz"

mkdir -p "$(dirname "$TARGET")"
if [ -f "$TARGET/siftsmall_base.fvecs" ]; then
    echo "SIFT small already present in $TARGET"
    exit 0
fi

echo "Downloading $URL"
if command -v curl >/dev/null 2>&1; then
    curl -fSL -o "$ARCHIVE" "$URL"
elif command -v wget >/dev/null 2>&1; then
    wget -O "$ARCHIVE" "$URL"
else
    echo "Neither curl nor wget is available" >&2
    exit 1
fi

tar xzf "$ARCHIVE" -C "$(dirname "$TARGET")"
echo "Extracted to $(dirname "$TARGET")/siftsmall"
ls -la "$(dirname "$TARGET")/siftsmall"

cat <<'EOF'

File layout (.fvecs / .ivecs, little endian):
  each record = int32 dimension, then dimension * float32
  siftsmall_base.fvecs        10000 x 128 base vectors (the dataset to index)
  siftsmall_query.fvecs         100 x 128 query vectors
  siftsmall_groundtruth.ivecs   100 x 100 true nearest base ids per query

Run the HNSW integration test against it:
  HNSW_DATASET_DIR=.datasets/siftsmall AEROSPIKE_NAMESPACE=test \
    ./mvnw -pl graph-integration-test -am test -Dtest=HnswGraphIntegrationTest
EOF
