# syntax=docker/dockerfile:1.7
# See services/api/Dockerfile for the rationale behind layer ordering + jar selection.

FROM gradle:8.11-jdk21 AS builder
WORKDIR /workspace

COPY settings.gradle.kts build.gradle.kts gradlew gradlew.bat ./
COPY gradle ./gradle
COPY buildSrc ./buildSrc
RUN --mount=type=cache,id=gradle-cache,target=/root/.gradle \
    ./gradlew --version --no-daemon

COPY services ./services
COPY java-sdk ./java-sdk

RUN --mount=type=cache,id=gradle-cache,target=/root/.gradle \
    ./gradlew :services:worker:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=builder /workspace/services/worker/build/libs/worker-*.jar /app/app.jar

ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_TOOL_OPTIONS -jar /app/app.jar"]
