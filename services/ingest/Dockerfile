# syntax=docker/dockerfile:1.7
# See services/api/Dockerfile for the rationale behind layer ordering + jar selection.

FROM gradle:8.11-jdk21 AS builder
WORKDIR /workspace

COPY settings.gradle.kts build.gradle.kts gradlew gradlew.bat ./
COPY gradle ./gradle
COPY buildSrc ./buildSrc
RUN ./gradlew --version --no-daemon

COPY services ./services
COPY java-sdk ./java-sdk
COPY lib ./lib

RUN ./gradlew :services:ingest:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=builder /workspace/services/ingest/build/libs/ingest-*.jar /app/app.jar

ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_TOOL_OPTIONS -jar /app/app.jar"]
