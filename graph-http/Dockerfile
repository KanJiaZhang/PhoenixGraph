# PhoenixGraph graph-http (the query/write API and the graph engine).
#
#   build:  docker build -f graph-http/Dockerfile -t phoenixgraph-http .
#   run:    docker run --rm -p 8081:8081 \
#             -e PHOENIXGRAPH_STORAGE_HOST=host.docker.internal phoenixgraph-http
#
# The build stage compiles graph-http and every module it depends on; the runtime stage
# keeps only the fat jar. The container runs as a non-root user and writes its RocksDB
# graphs under /data/rocksdb (mount a volume there to keep them).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -pl graph-http -am -DskipTests package

FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd -r phoenixgraph && useradd -r -g phoenixgraph phoenixgraph
WORKDIR /app
COPY --from=build /workspace/graph-http/target/graph-http-*.jar /app/app.jar
RUN mkdir -p /data/rocksdb && chown -R phoenixgraph:phoenixgraph /app /data
USER phoenixgraph

# MaxRAMPercentage lets the JVM size itself from the container limit.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"
EXPOSE 8081

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8081/api/v1/graphs >/dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
