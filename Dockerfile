# Multi-stage build: the JRE the runtime needs, and nothing else.
#
# Why a Dockerfile at all, when the repository ships a `tools/start-phoenixgraph.sh` and the verification
# suite builds throwaway containers by mounting the jar? Because "a release is a git tag and an image tag with
# the same name" is only true if the image is built from the repository, the same way every time. The suite's
# mounted-jar containers are a drill; this is the artifact.
#
# There is deliberately no build stage here: the jar is staged into dist/phoenixgraph.jar by
# tools/release-1.0.sh, which runs `./mvnw -DskipTests package` first. That keeps the image build to a copy and
# a few layers, and it keeps the version in the image identical to the version in the pom - one place decides
# what is being released. (.dockerignore allows only the Dockerfile and dist/ through, so the build context is
# the artifact and nothing else.)

FROM eclipse-temurin:21-jre AS runtime

# Labels are how an operator finds out what is running without shelling into it.
ARG VERSION=1.0.0
LABEL org.opencontainers.image.title="PhoenixGraph HTTP" \
      org.opencontainers.image.description="HTAP graph database: Gremlin/Cypher, Gemini (Pregel) compute, vector and geo queries" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.source="https://github.com/KanJiaZhang/PhoenixGraph" \
      org.opencontainers.image.licenses="Apache-2.0"

# A non-root user: the service writes only to its RocksDB root and its log directory, so it never needs root,
# and a container that never needs root should not have it.
RUN groupadd --system --gid 1001 phoenixgraph \
 && useradd --system --uid 1001 --gid phoenixgraph --home /opt/phoenixgraph phoenixgraph \
 && mkdir -p /opt/phoenixgraph /var/lib/phoenixgraph/rocksdb /var/log/phoenixgraph \
 && chown -R phoenixgraph:phoenixgraph /opt/phoenixgraph /var/lib/phoenixgraph /var/log/phoenixgraph

WORKDIR /opt/phoenixgraph
COPY --chown=phoenixgraph:phoenixgraph dist/phoenixgraph.jar /opt/phoenixgraph/phoenixgraph.jar

USER phoenixgraph

# The UI is served by the same process on 8080 and the API on 8081 (docs/OPERATIONS.md §1): one process to
# probe, one to scale, and the UI's calls to the API stay same-origin, which is what the map and the builder
# need. Both are declared so `docker run -P` and orchestration see them.
EXPOSE 8080 8081

# Data and logs live outside the image: a container that is replaced must not take the graph with it.
VOLUME ["/var/lib/phoenixgraph", "/var/log/phoenixgraph"]

# Garbage-first, container-aware, and with the heap a limit-bearing container asks for: the JVM reads the
# cgroup limit, so `docker run -m 2g` means a 2g heap rather than a quarter of the host's memory.
ENV JAVA_TOOL_OPTIONS="-XX:+UseG1GC -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    PHOENIXGRAPH_STORAGE_ROCKSDB_ROOT=/var/lib/phoenixgraph/rocksdb \
    LOGGING_FILE_NAME=/var/log/phoenixgraph/phoenixgraph.log

# The health endpoint is liveness, not readiness (docs/API.md §3): a node whose store is unreachable is still
# alive, still serves the graphs whose storage works, and answers 200 here. A readiness probe belongs on
# /api/v1/graphs - deploy/k8s/graph-http.yaml has both.
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
    CMD ["sh", "-c", "exec 3<>/dev/tcp/127.0.0.1/8081 && printf 'GET /api/v1/health HTTP/1.0\\r\\n\\r\\n' >&3 && head -c 200 <&3 | grep -q '200 OK'"]

# Exec form, so the JVM is PID 1 and receives SIGTERM: the graceful shutdown window (30s, during which the
# write path finishes and the RocksDB stores close) only exists if the signal arrives.
ENTRYPOINT ["java", "-jar", "/opt/phoenixgraph/phoenixgraph.jar"]
