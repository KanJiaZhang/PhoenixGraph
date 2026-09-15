# Build stage: compile graph-http and its module dependencies.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -q -pl graph-http -am -DskipTests package

# Runtime stage.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/graph-http/target/graph-http-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app.jar"]
