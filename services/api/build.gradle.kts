plugins {
    id("argus.spring-service")
}

description = "Argus API — REST API, Stripe webhooks, admin, Flyway owner"

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.aop)
    implementation(libs.spring.boot.starter.jooq)
    implementation(libs.springdoc.openapi.webmvc)
    implementation(libs.postgres.driver)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    implementation(libs.aws.s3)
    implementation(libs.stripe.java)
    implementation(libs.argon2.jvm)
    // TODO(P4): bucket4j-redis for quotas; OTel starter; Pact contracts.

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.testcontainers.minio)
    testImplementation(libs.wiremock.standalone)
    testImplementation(libs.mockito.junit)
}
