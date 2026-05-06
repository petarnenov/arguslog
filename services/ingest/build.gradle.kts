plugins {
    id("arguslog.spring-service")
}

description = "Arguslog ingest — public HTTP event endpoint, Redis Streams writer"

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
