plugins {
    id("arguslog.spring-service")
}

description = "Arguslog API — REST API, Stripe webhooks, admin, Flyway owner"

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

// KeycloakRealmImportTest uses KeycloakContainer.withRealmImportFile, which expects the realm
// JSON on the test classpath. Copy it from the canonical location (services/keycloak/realm) at
// processTestResources time so the test never silently runs against a stale duplicate.
tasks.named<Copy>("processTestResources") {
    from("../keycloak/realm/arguslog-realm.json")
}

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
    // Stripe SDK is annotated with com.google.gson.annotations.SerializedName; declare gson
    // explicitly so the api compile classpath sees the annotation type (Stripe pulls it at
    // runtime but doesn't expose it through its POM as a compile-scope dep).
    implementation(libs.gson)
    implementation(libs.argon2.jvm)
    // Per-IP / per-JWT rate limit on the api surface (in-memory Bucket4j + Caffeine LRU
    // of buckets). bucket4j-redis is the followup for cross-instance limits.
    implementation(libs.bucket4j.core)
    implementation(libs.caffeine)
    // Dogfood — emits the api's own errors back into Arguslog via the Logback appender. SDK
    // is no-op until ARGUS_DSN is configured (always unset in tests + local dev).
    implementation(project(":java-sdk"))
    // TODO(P4): bucket4j-redis for quotas; OTel starter; Pact contracts.

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.testcontainers.minio)
    testImplementation(libs.testcontainers.keycloak)
    testImplementation(libs.wiremock.standalone)
    testImplementation(libs.mockito.junit)
}
