plugins {
    id("arguslog.spring-service")
}

description = "Arguslog worker — consumes Redis Streams, scrubs/fingerprints/persists, dispatches alerts"

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

dependencies {
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.json)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.aop)
    implementation(libs.postgres.driver)
    implementation(libs.aws.s3)
    implementation(libs.caffeine)
    // Dogfood — Logback appender emits the worker's own errors back into Arguslog. No-op
    // until ARGUS_DSN is configured.
    implementation(project(":java-sdk"))
    // TODO(P3): telegram-bot, resend-java, OTel starter.

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.testcontainers.redis)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgres)
    testImplementation(libs.wiremock.standalone)
    testImplementation(libs.mockito.junit)
    testImplementation(libs.awaitility)
    // ingest classes for the in-process end-to-end test (P1 milestone #4).
    // Test-scoped so the production worker JAR doesn't pull in ingest.
    testImplementation(project(":services:ingest"))
}
