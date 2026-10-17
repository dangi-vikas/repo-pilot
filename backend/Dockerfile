# =============================================================================
# RepoPilot backend image.
#
# Two stages: the Maven build produces a Spring Boot executable jar, and the
# runtime stage carries only a JRE, the jar and a non-root user. The build stage
# is discarded, so the image never contains the Maven cache or the test sources.
# =============================================================================

# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /build

# Only the build descriptors are copied first so that a source-only change does
# not invalidate the dependency layer. `.mvn` carries the wrapper settings.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline -DskipTests

COPY src/ src/
RUN ./mvnw -B -q package -DskipTests \
    && cp target/repopilot-backend-*.jar /build/application.jar

# -----------------------------------------------------------------------------

FROM eclipse-temurin:21-jre-jammy AS runtime

# curl is used by the container healthcheck below.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# RepoPilot resolves every repository path against the workspace roots and
# refuses paths that escape them, so the OS user is a second line of defence:
# a bug in the sandbox still cannot write outside /data.
RUN groupadd --system --gid 10001 repopilot \
    && useradd --system --uid 10001 --gid repopilot --home-dir /data repopilot \
    && mkdir -p /data \
    && chown -R repopilot:repopilot /data

WORKDIR /app
COPY --from=build --chown=repopilot:repopilot /build/application.jar /app/application.jar

USER repopilot

# RepoPilot binds loopback by default. Inside a container that would make the
# port unreachable, so the compose file sets SERVER_ADDRESS=0.0.0.0 explicitly
# and keeps the bearer token mandatory. Nothing is exposed without it.
ENV SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8080 \
    SPRING_PROFILES_ACTIVE=postgres \
    REPOPILOT_DATA_DIR=/data

EXPOSE 8080

# The readiness group includes the database, the job workers and the AI
# provider, which is exactly what "can serve a request" means here. Liveness
# deliberately does not, so a missing Ollama never restarts the container.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl --fail --silent "http://127.0.0.1:8080/actuator/health/readiness" || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/application.jar"]
