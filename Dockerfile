# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Dependencies change far less often than sources, so they get a layer of their own; the cache
# mount keeps the local repository between builds without baking it into any layer.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -DskipTests package \
    && java -Djarmode=tools -jar target/core-banking-ledger-*.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre

# The base image already has a uid 1000 user; a high system uid cannot collide with a host user
# on a bind mount and cannot be mistaken for one.
RUN groupadd --system --gid 10001 ledger \
    && useradd --system --uid 10001 --gid ledger --no-create-home --shell /usr/sbin/nologin ledger

WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER ledger

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8082

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD curl -fsS "http://localhost:${SERVER_PORT:-8082}/actuator/health/readiness" || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
