# Build stage: compile graph-web (serves the static UI + proxies to graph-http).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -q -pl graph-web -am -DskipTests package

# Runtime stage.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/graph-web/target/graph-web-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
