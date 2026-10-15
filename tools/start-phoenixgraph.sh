#!/usr/bin/env bash
#
# Start PhoenixGraph: the Aerospike container, graph-http (8081) and graph-web (8080),
# and - with --with-hnsw - the write-driven vector instance (8083).
#
#   tools/start-phoenixgraph.sh                 # rebuild the jars, then start 8081 + 8080
#   tools/start-phoenixgraph.sh --no-build      # start what is already built
#   tools/start-phoenixgraph.sh --with-hnsw     # also start 8083
#   tools/start-phoenixgraph.sh --no-aerospike  # do not touch the docker container
#
# Logs go to .logs/<service>.out.
# Stop again with tools/stop-phoenixgraph.sh.
#
# Notes:
#  * a running service holds its jar open, so the services are stopped *before* the build;
#  * the build must repackage the fat jar - a thin jar (no BOOT-INF/lib) cannot be run
#    with `java -jar`, so the script checks for that and stops with a hint;
#  * 8083 needs its own Gemini ports (--phoenixgraph.gemini.first-port=19110), otherwise
#    its workers collide with the ones of 8081 and the instance fails to start.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

LOGS="$ROOT/.logs"
mkdir -p "$LOGS"

HTTP_PORT=8081
WEB_PORT=8080
HNSW_PORT=8083
HNSW_GEMINI_FIRST_PORT=19110
# The jar name carries the project version, so it is found rather than written down: the release that bumped the
# version to 1.0.0 renamed every artifact, and a hardcoded 0.1.0-SNAPSHOT path then started a *stale* jar while the
# build reported success - the endpoint being tested simply 404ed. (`grep -v original` skips the pre-repackage jar
# that spring-boot-maven-plugin leaves behind.)
find_jar() {
  local pattern="$1"
  local found
  # `ls -t` newest-first: a jar from a previous version is still on disk after a version bump and sorts first
  found=$(ls -t $pattern 2>/dev/null | grep -v '\.original$' | head -1)
  if [ -z "$found" ]; then
    echo "!! no jar matching $pattern; run tools/start-phoenixgraph.sh without --no-build first" >&2
    exit 1
  fi
  echo "$found"
}
HTTP_JAR="$(find_jar 'graph-http/target/graph-http-*.jar')"
WEB_JAR="$(find_jar 'graph-web/target/graph-web-*.jar')"

BUILD=1
WITH_HNSW=0
START_AEROSPIKE=1
WAIT_SECONDS=90

while [ $# -gt 0 ]; do
  case "$1" in
    -b|--build)        BUILD=1 ;;
    --no-build)        BUILD=0 ;;
    --with-hnsw)       WITH_HNSW=1 ;;
    --no-aerospike)    START_AEROSPIKE=0 ;;
    --wait)            shift; WAIT_SECONDS="${1:-90}" ;;
    -h|--help)         sed -n '3,18p' "$0"; exit 0 ;;
    *) echo "unknown option: $1 (try --help)" >&2; exit 2 ;;
  esac
  shift
done

# ---------------------------------------------------------------- java ------
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  newest="$(ls -d /c/Program\ Files/Java/jdk-* 2>/dev/null | sort -V | tail -1)"
  [ -n "$newest" ] && JAVA_HOME="$newest"
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "!! no JDK found - set JAVA_HOME (e.g. \"C:/Program Files/Java/jdk-26.0.2.1\")" >&2
  exit 1
fi
export JAVA_HOME
JAVA_EXE="$JAVA_HOME/bin/java"

# curl is what the wait loop talks HTTP with; the other two are only used by parts this script can go
# without (unzip verifies the fat jar listing, powershell stops a running service by its command line),
# so they are a warning rather than a reason to refuse to start".
for tool in curl unzip powershell; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    case "$tool" in
      curl) echo "!! 'curl' is not on PATH: the script waits for the services with it" >&2; exit 1 ;;
      *) echo "   note: '$tool' is not on PATH - the check it is used for is skipped" >&2 ;;
    esac
  fi
done

echo "PhoenixGraph"
echo "  root : $ROOT"
echo "  java : $JAVA_HOME"

# ------------------------------------------------------------- helpers ------
stop_services() {
  powershell -NoProfile -Command "
    Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" |
      Where-Object { \$_.CommandLine -like '*graph-http*target*graph-*' -or \$_.CommandLine -like '*graph-web*target*graph-*' } |
      ForEach-Object { Write-Host ('  stopped pid ' + \$_.ProcessId); Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }
  " 2>/dev/null | tr -d '\r'
}

fat_jar_ok() {
  local jar="$1" listing
  if [ ! -f "$ROOT/$jar" ]; then
    echo "!! $jar is missing - build it first" >&2
    return 1
  fi
  listing="$(unzip -l "$ROOT/$jar" 2>/dev/null)"
  if [[ "$listing" != *"BOOT-INF/lib/"* ]]; then
    echo "!! $jar is a thin jar (no BOOT-INF/lib): a repackage was skipped last time." >&2
    echo "   Rebuild without -Dspring-boot.repackage.skip, e.g. 'tools/start-phoenixgraph.sh' (builds)." >&2
    return 1
  fi
}

# Starts one service detached (it survives this script) and returns its Windows pid.
start_service() {
  local name="$1" jar="$2" port="$3"
  shift 3
  local out="$LOGS/$name.out"
  : > "$out"
  # nohup detaches the JVM so it outlives this script. The pid below is the shell's, not the
  # Windows one - tools/stop-phoenixgraph.sh finds the JVM again by its command line.
  nohup "$JAVA_EXE" -jar "$ROOT/$jar" "$@" > "$out" 2>&1 &
  local pid=$!
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "!! $name exited at once - see .logs/$name.out" >&2
    return 1
  fi
  echo "  $name  launched  (port $port, log .logs/$name.out)"
}

wait_for_http() {
  local name="$1" url="$2" seconds="${3:-$WAIT_SECONDS}"
  local i code
  for i in $(seq 1 "$seconds"); do
    code="$(curl -s -m 3 -o /dev/null -w '%{http_code}' "$url" 2>/dev/null)"
    if [ "$code" = "200" ]; then
      echo "  $name  up  ($url)"
      return 0
    fi
    sleep 1
  done
  echo "!! $name did not answer $url within ${seconds}s" >&2
  echo "   last lines of .logs/$name.out:" >&2
  tail -15 "$LOGS/$name.out" 2>/dev/null | sed 's/^/   | /' >&2
  return 1
}

# ----------------------------------------------------------- 1. stop --------
echo
echo "1/5  stopping any running instance"
stop_services
sleep 2

# ---------------------------------------------------------- 2. build --------
if [ "$BUILD" = 1 ]; then
  echo
  echo "2/5  building graph-http, graph-web and their dependencies"
  if ! ./mvnw -q -o -DskipTests -pl graph-http,graph-web -am clean package; then
    echo "  (offline build failed - retrying online)"
    ./mvnw -q -DskipTests -pl graph-http,graph-web -am clean package || {
      echo "!! the build failed" >&2; exit 1; }
  fi
  echo "  built"
else
  echo
  echo "2/5  build skipped (--no-build)"
fi

fat_jar_ok "$HTTP_JAR" || exit 1
fat_jar_ok "$WEB_JAR" || exit 1

# ------------------------------------------------------- 3. aerospike -------
echo
echo "3/5  Aerospike"
if [ "$START_AEROSPIKE" = 1 ] && command -v docker >/dev/null 2>&1; then
  running="$(docker ps --format '{{.Names}}' 2>/dev/null)"
  if [[ "$running" != *aerospike* ]]; then
    docker start aerospike >/dev/null 2>&1 \
      && echo "  started the 'aerospike' container" \
      || echo "  !! could not start the 'aerospike' container" >&2
  fi
  up=0
  for _ in $(seq 1 30); do
    if (exec 3<>/dev/tcp/127.0.0.1/3000) 2>/dev/null; then exec 3>&- 3<&-; up=1; break; fi
    sleep 1
  done
  [ "$up" = 1 ] && echo "  up on 127.0.0.1:3000" || echo "  !! 127.0.0.1:3000 is not answering" >&2
else
  echo "  skipped"
fi

# ---------------------------------------------------------- 4. start -------
echo
echo "4/5  starting services"
start_service graph-http "$HTTP_JAR" "$HTTP_PORT" || exit 1
start_service graph-web  "$WEB_JAR"  "$WEB_PORT"  || exit 1
if [ "$WITH_HNSW" = 1 ]; then
  start_service graph-http-hnsw "$HTTP_JAR" "$HNSW_PORT" \
      --spring.profiles.active=hnsw-dynamic --server.port="$HNSW_PORT" \
      --phoenixgraph.gemini.first-port="$HNSW_GEMINI_FIRST_PORT" || exit 1
fi

# ---------------------------------------------------------- 5. check -------
echo
echo "5/5  waiting for them to answer"
ok=1
wait_for_http graph-http "http://localhost:$HTTP_PORT/api/v1/graphs" || ok=0
wait_for_http graph-web  "http://localhost:$WEB_PORT/"                || ok=0
if [ "$WITH_HNSW" = 1 ]; then
  wait_for_http graph-http-hnsw "http://localhost:$HNSW_PORT/api/v1/graphs" || ok=0
fi

echo
if [ "$ok" = 1 ]; then
  echo "PhoenixGraph is up:"
  echo "  UI          http://localhost:$WEB_PORT/"
  echo "  HTTP API    http://localhost:$HTTP_PORT/api/v1/graphs"
  [ "$WITH_HNSW" = 1 ] && echo "  hnsw-dynamic http://localhost:$HNSW_PORT/api/v1/graphs"
  echo "  logs        .logs/<service>.out"
  echo "  stop        tools/stop-phoenixgraph.sh"
  echo "  restart     tools/start-phoenixgraph.sh --no-build"
else
  echo "!! some service did not come up - check the .logs/*.out files" >&2
  exit 1
fi
