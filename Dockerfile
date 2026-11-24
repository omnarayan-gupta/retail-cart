# syntax=docker/dockerfile:1
# Multi-stage build: compile with the JDK, run on a small JRE image as a non-root user.

# --- 1. Build the executable jar ------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Gradle wrapper and build scripts first, so dependency downloads are cached between code changes.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew

COPY src src
# Tests run in CI / `./gradlew build`, not on every image build.
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootJar --no-daemon -q

# Split the jar into layers (dependencies change rarely, our code often) for faster image rebuilds.
RUN java -Djarmode=tools -jar build/libs/retail-cart-*.jar extract --layers --destination extracted

# --- 2. Runtime image ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S cart && adduser -S cart -G cart
USER cart

COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

EXPOSE 8081
# Size the heap from the container's memory limit rather than the host's.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8081/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "retail-cart-0.0.1-SNAPSHOT.jar"]
