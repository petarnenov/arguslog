plugins {
    id("arguslog.spring-service")
}

description = "Arguslog ingest — public HTTP event endpoint, Redis Streams writer"

// The shared `arguslog.spring-service` convention disables the plain `jar` task so a *full*
// gradle build leaves only the bootJar in build/libs (Dockerfiles glob `ingest-*.jar`).
// Re-enable it here so worker's integration tests can consume ingest classes via
// `testImplementation(project(":services:ingest"))` — without a plain library jar Gradle has
// no artifact to expose on the consumer's classpath. The Dockerfile is unaffected: it runs
// `:services:ingest:bootJar` specifically, which doesn't trigger the plain `jar` task.
tasks.named<Jar>("jar") {
    enabled = true
}

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.aop)
    implementation(libs.springdoc.openapi.webmvc)
    implementation(libs.postgres.driver)
    implementation(libs.argon2.jvm)
    implementation(libs.bucket4j.core)
    implementation(libs.caffeine)
    // Dogfood — Logback appender emits ingest's own errors back into Arguslog. No-op until
    // ARGUSLOG_DSN is configured.
    implementation(project(":lib:plan-tier"))
    implementation(project(":java-sdk"))
    // TODO(P5): bucket4j-redis for cross-instance burst limiting; OTel starter for traces.

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgres)
    testImplementation(libs.wiremock.standalone)
    testImplementation(libs.mockito.junit)
    testImplementation(libs.pact.provider)
}
