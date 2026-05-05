plugins {
    id("argus.spring-service")
}

description = "Argus worker — consumes Redis Streams, scrubs/fingerprints/persists, dispatches alerts"

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

dependencies {
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.jooq)
    implementation(libs.spring.boot.starter.aop)
    implementation(libs.postgres.driver)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    implementation(libs.aws.s3)
    // TODO(P3): telegram-bot, resend-java, OTel starter.

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.wiremock.standalone)
    testImplementation(libs.mockito.junit)
}
