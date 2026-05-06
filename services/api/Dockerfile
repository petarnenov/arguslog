# syntax=docker/dockerfile:1.7

# Build stage — runs the Gradle build for the api module only. The repo is the build context so
# Gradle has access to settings/build files + every other module via the version catalog. We
# explicitly skip tests because the CI build already gates them; rebuilding test containers in the
# container build would slow deploys without catching new bugs.
FROM gradle:8.11-jdk21 AS builder
WORKDIR /workspace

# Cache Gradle metadata + plugins on a separate layer so source-only changes don't re-download.
# (Single COPY of the whole tree would be simpler, but Railway's per-deploy build caching benefits
# from the smaller invalidation surface.)
COPY settings.gradle.kts build.gradle.kts gradlew gradlew.bat ./
COPY gradle ./gradle
COPY buildSrc ./buildSrc
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --version --no-daemon

# Now bring in the actual sources.
COPY services ./services
COPY java-sdk ./java-sdk

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :services:api:bootJar -x test --no-daemon

# Runtime stage — JRE only, executable boot jar copied in. eclipse-temurin matches the JDK used
# above so byte-for-byte compatibility is guaranteed.
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=builder /workspace/services/api/build/libs/api-*.jar /app/app.jar

# Railway injects PORT — Spring Boot reads ${PORT}; default to 8080 for local docker run.
ENV PORT=8080
EXPOSE 8080

# JVM tuning lives in JAVA_TOOL_OPTIONS so it can be overridden per environment without touching
# the image. The runbook recommends -XX:MaxRAMPercentage=75 on Railway.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_TOOL_OPTIONS -jar /app/app.jar"]
