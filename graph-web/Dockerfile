# PhoenixGraph graph-web (the single-page visualization UI).
#
#   build:  docker build -f graph-web/Dockerfile -t phoenixgraph-web .
#   run:    docker run --rm -p 8080:8080 -e PHOENIXGRAPH_HTTP_URL=http://graph-http:8081 phoenixgraph-web
#
# The UI talks to graph-http through this service, so PHOENIXGRAPH_HTTP_URL points at the
# graph-http container (the compose service name).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -pl graph-web -am -DskipTests package

FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd -r phoenixgraph && useradd -r -g phoenixgraph phoenixgraph
WORKDIR /app
COPY --from=build /workspace/graph-web/target/graph-web-*.jar /app/app.jar
RUN chown -R phoenixgraph:phoenixgraph /app
USER phoenixgraph

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
    CMD curl -fsS http://localhost:8080/ >/dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
