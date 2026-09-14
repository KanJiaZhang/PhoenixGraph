#!/usr/bin/env bash
#
# Downloads and installs a single-node Aerospike Community Edition server and
# starts it on localhost:3000.
#
# Usage:
#   ./setup-aerospike.sh
#
# Behaviour:
#   * If Docker is available, a community-edition container is started.
#   * Otherwise the Community Edition tarball is downloaded and run natively
#     (Linux x86_64 only).
#
# Configuration via environment variables:
#   AEROSPIKE_VERSION  server version (default: 8.0.0.3)
#   AEROSPIKE_DISTRO   native tarball distro suffix (default: debian12)
#   AEROSPIKE_NAMESPACE namespace name (default: test)
#   AEROSPIKE_INSTALL_DIR native install directory (default: /opt/aerospike)
set -euo pipefail

AEROSPIKE_VERSION="${AEROSPIKE_VERSION:-8.0.0.3}"
AEROSPIKE_DISTRO="${AEROSPIKE_DISTRO:-debian12}"
NAMESPACE="${AEROSPIKE_NAMESPACE:-test}"
INSTALL_DIR="${AEROSPIKE_INSTALL_DIR:-/opt/aerospike}"

start_via_docker() {
  echo "Starting Aerospike via Docker (aerospike/aerospike-server-community)..."
  docker run -d --name aerospike -p 3000:3000 \
    -v "$(dirname "$0")/aerospike.conf:/etc/aerospike/aerospike.conf:ro" \
    aerospike/aerospike-server-community
  echo "Aerospike started via Docker on localhost:3000 (namespace test, replication-factor 1)."
}

start_native() {
  local url="https://download.aerospike.com/artifacts/aerospike-server-community/${AEROSPIKE_VERSION}/aerospike-server-community-${AEROSPIKE_VERSION}-${AEROSPIKE_DISTRO}.tgz"
  local tarball="aerospike-server-community-${AEROSPIKE_VERSION}.tgz"

  echo "Downloading ${url} ..."
  curl -fL "$url" -o "$tarball"

  echo "Extracting to ${INSTALL_DIR} ..."
  sudo mkdir -p "$INSTALL_DIR"
  sudo tar -xzf "$tarball" -C "$INSTALL_DIR" --strip-components=1

  echo "Writing /etc/aerospike/aerospike.conf ..."
  sudo mkdir -p /etc/aerospike
  sudo tee /etc/aerospike/aerospike.conf >/dev/null <<EOF
service {
  user root
  group root
}
logging {
  file /var/log/aerospike.log {
    context any info
  }
}
network {
  service {
    address any
    port 3000
  }
  heartbeat {
    mode multicast
    address 239.1.99.222
    port 9918
  }
}
namespace ${NAMESPACE} {
  replication-factor 1
  memory-size 1G
  default-ttl 0
}
EOF

  echo "Starting Aerospike ..."
  sudo "${INSTALL_DIR}/bin/aerospike" start
  echo "Aerospike started on localhost:3000 (namespace ${NAMESPACE})."
}

if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  echo "Starting Aerospike via docker compose ..."
  docker compose -f "$(dirname "$0")/../docker-compose.yml" up -d
  echo "Aerospike started via docker compose on localhost:3000 (namespace test, replication-factor 1)."
  exit 0
fi

if command -v docker >/dev/null 2>&1; then
  start_via_docker
else
  start_native
fi
