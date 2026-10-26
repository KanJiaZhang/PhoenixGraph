#!/usr/bin/env bash
# Builds the 1.0 release artifacts: the jar, the images, and a manifest of what was built.
#
# Why a script and not a few commands in the release notes: the version lives in four places (the pom, the
# Dockerfile label, the image tag, the git tag) and "release" is exactly the operation where they drift. This
# reads the version *from the pom*, builds from it, tags with it, and refuses to continue when the working tree
# is dirty or the version has already been tagged - the two ways a release quietly stops describing the code.
#
# Usage:
#   tools/release-1.0.sh                 # build jar + images, print the next steps (no tagging)
#   tools/release-1.0.sh --tag           # also create the git tag and push it
#   tools/release-1.0.sh --no-image      # jar and checksum only (no docker)
set -euo pipefail

cd "$(dirname "$0")/.."

TAG_IT=false
BUILD_IMAGES=true
for argument in "$@"; do
  case "$argument" in
    --tag) TAG_IT=true ;;
    --no-image) BUILD_IMAGES=false ;;
    *) echo "unknown argument: $argument" >&2; exit 2 ;;
  esac
done

# JAVA_HOME may be spelled the Windows way ("C:/Program Files/...") while this script runs under a shell that
# needs the POSIX spelling ("/c/Program Files/..."). Maven's launcher does "cd $JAVA_HOME", so the wrong spelling
# fails with "The JAVA_HOME environment variable is not defined correctly" - which reads like a missing JDK
# rather than a path quirk. Normalise it once, and fail early when it points nowhere.
if [ -n "${JAVA_HOME:-}" ] && [ ! -d "${JAVA_HOME}" ] && command -v cygpath >/dev/null 2>&1; then
  NORMALISED=$(cygpath -u "${JAVA_HOME}" 2>/dev/null || true)
  if [ -n "${NORMALISED}" ] && [ -d "${NORMALISED}" ]; then
    export JAVA_HOME="${NORMALISED}"
  fi
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -d "${JAVA_HOME}" ]; then
  echo "!! JAVA_HOME does not point at a JDK (${JAVA_HOME:-unset}); export it before releasing" >&2
  exit 1
fi

VERSION=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' pom.xml | head -1)
TAG="v${VERSION}"
echo "== PhoenixGraph release ${VERSION} (tag ${TAG})"

# --- the two things that make a release a lie ----------------------------------------------------------
if [ -n "$(git status --porcelain)" ]; then
  echo "!! the working tree is dirty: a release must be a commit" >&2
  git status --short | head -10 >&2
  exit 1
fi
if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then
  if [ "${TAG_IT}" = true ]; then
    echo "!! ${TAG} already exists; bump the version in pom.xml instead of moving a tag" >&2
    exit 1
  fi
  # rebuilding an already-released version locally is legitimate (inspecting the artifact, refreshing the
  # manifest after a documentation commit): warn, build, and never tag
  echo "-- ${TAG} already exists: building the artifact only, tagging stays off"
  echo "-- working tree clean"
else
  echo "-- working tree clean, ${TAG} is free"
fi

# Every fact that does not depend on the tag has to hold before anything is built: the poms agree with each
# other, the changelog has the section, the image tags follow the version, the release note exists.
#
# The generated docs are checked first, because they carry the version: docs/openapi.json's info.version is read
# from the pom, so bumping the version makes the committed spec stale - the one edit a release must follow with a
# regenerate, and the one that does not show up until CI looks (1.1.1 was tagged with a spec that still said
# 1.1.0). Failing here is the point: the message says what to run and the fix is a commit, not a flag.
for generated in tools/openapi.mjs tools/api-endpoints.mjs; do
  if ! node "${generated}" --check >/dev/null 2>&1; then
    echo "!! a generated document is stale: run node ${generated} and commit the result, then release again" >&2
    node "${generated}" --check >&2 || true
    exit 1
  fi
done
node tools/check-release.mjs --expect-untagged

# --- the artifact -------------------------------------------------------------------------------------
echo "-- packaging (skipping tests: CI runs them, and a release build must not be the first time they run)"
./mvnw -o -q -DskipTests package
mkdir -p dist
cp "graph-http/target/graph-http-${VERSION}.jar" dist/phoenixgraph.jar
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum dist/phoenixgraph.jar > dist/phoenixgraph.jar.sha256
elif command -v shasum >/dev/null 2>&1; then
  shasum -a 256 dist/phoenixgraph.jar > dist/phoenixgraph.jar.sha256
fi
echo "-- dist/phoenixgraph.jar ($(du -h dist/phoenixgraph.jar | cut -f1))"
cat dist/phoenixgraph.jar.sha256 2>/dev/null || true

# --- the image ----------------------------------------------------------------------------------------
if [ "${BUILD_IMAGES}" = true ]; then
  if ! command -v docker >/dev/null 2>&1; then
    echo "!! docker is not on PATH; rerun with --no-image if that is intended" >&2
    exit 1
  fi
  echo "-- building phoenixgraph-http:${VERSION} (from the staged jar: the image carries the bytes that were checksummed)"
  docker build --build-arg "VERSION=${VERSION}" -t "phoenixgraph-http:${VERSION}" -t phoenixgraph-http:latest .
  echo "-- building phoenixgraph-web:${VERSION} (the UI; its Dockerfile builds the module inside the image)"
  docker build -f graph-web/Dockerfile -t "phoenixgraph-web:${VERSION}" .
  echo "-- image labels:"
  docker image inspect "phoenixgraph-http:${VERSION}" \
    --format '{{range $k, $v := .Config.Labels}}   {{$k}}={{$v}}{{"\n"}}{{end}}'
  echo "-- the image starts and answers (the same two probes a deployment uses):"
  docker rm -f phoenixgraph-release-probe >/dev/null 2>&1 || true
  docker run -d --name phoenixgraph-release-probe -p 18081:8081 \
    -e PHOENIXGRAPH_TLS_PASSWORD=unused "phoenixgraph-http:${VERSION}" >/dev/null
  for _ in $(seq 1 60); do
    if curl -sf http://localhost:18081/api/v1/health >/dev/null 2>&1; then break; fi
    sleep 1
  done
  HEALTH=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:18081/api/v1/health || true)
  GRAPHS=$(curl -s http://localhost:18081/api/v1/graphs | head -c 80 || true)
  echo "   /api/v1/health -> ${HEALTH}"
  echo "   /api/v1/graphs -> ${GRAPHS}..."
  docker rm -f phoenixgraph-release-probe >/dev/null 2>&1 || true
  if [ "${HEALTH}" != "200" ]; then
    echo "!! the release image does not answer its health endpoint" >&2
    exit 1
  fi
fi

# --- the manifest -------------------------------------------------------------------------------------
{
  echo "version: ${VERSION}"
  echo "tag: ${TAG}"
  echo "commit: $(git rev-parse HEAD)"
  echo "built: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "jar: dist/phoenixgraph.jar"
  echo "image: phoenixgraph-http:${VERSION}"
  echo "image: phoenixgraph-web:${VERSION}"
  echo "java: $(java -version 2>&1 | head -1)"
} > dist/RELEASE.txt
echo "-- dist/RELEASE.txt"
cat dist/RELEASE.txt

# --- the tag ------------------------------------------------------------------------------------------
if [ "${TAG_IT}" = true ]; then
  git tag -a "${TAG}" -m "PhoenixGraph ${VERSION}"
  git push origin "${TAG}"
  echo "-- ${TAG} created and pushed"
else
  echo
  echo "next: review docs/RELEASE-${VERSION}.md, then"
  echo "  tools/release-1.0.sh --tag"
  echo "  docker push phoenixgraph-http:${VERSION}"
  echo "  docker push phoenixgraph-web:${VERSION}"
fi
