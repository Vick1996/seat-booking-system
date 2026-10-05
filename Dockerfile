# --- build ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
# `package` skips the Testcontainers ITs (they run on `verify`), so no Docker is needed here.
RUN ./mvnw -q -B -DskipTests package && mv target/seat-reservation-*.jar /app.jar

# --- run ---
FROM eclipse-temurin:21-jre
# Run unprivileged: a bug in the app must not be root inside the container.
RUN groupadd --system app && useradd --system --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /app.jar app.jar
USER app
EXPOSE 8080
# Liveness only (never readiness): a database blip must not mark the container unhealthy and get it restarted.
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -fsS "http://localhost:${PORT:-8080}/healthz" >/dev/null || exit 1
# ExitOnOutOfMemoryError: after an OOM the JVM can be left half-dead (here it could not even log), still
# running but answering nothing, so nothing ever restarts it. Exit instead and let the platform restart us.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-Duser.timezone=UTC", "-jar", "app.jar"]
