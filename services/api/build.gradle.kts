plugins {
    id("arguslog.spring-service")
}

description = "Arguslog API — REST API, Stripe webhooks, admin, Flyway owner"

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

// The Keycloak realm JSON is a generated artifact — `render-realm.sh` substitutes the
// __DEV_HOST__ / __*_CLIENT_ID__ placeholders in realm.template.json (which IS tracked in
// git) and strips disabled IdPs. It's gitignored so secrets never accidentally land in a
// commit. Tests that need the file (KeycloakRealmImportTest) get it via `processTestResources`
// → copied onto the test classpath as `arguslog-realm.json`. To make a clean clone able to
// run `./gradlew :services:api:test` without any prior setup, the Gradle task below runs the
// render script first if the output is missing or stale.
val renderKeycloakRealm by tasks.registering(Exec::class) {
    description = "Renders services/keycloak/realm.template.json → realm/arguslog-realm.json " +
        "via render-realm.sh so processTestResources has something to copy onto the classpath."
    workingDir = rootDir
    commandLine("bash", "services/keycloak/render-realm.sh")
    inputs.file("$rootDir/services/keycloak/realm.template.json")
    inputs.file("$rootDir/services/keycloak/render-realm.sh")
    outputs.file("$rootDir/services/keycloak/realm/arguslog-realm.json")
}

// KeycloakRealmImportTest uses KeycloakContainer.withRealmImportFile, which expects the realm
// JSON on the test classpath. Copy it from the canonical location (services/keycloak/realm) at
// processTestResources time so the test never silently runs against a stale duplicate.
tasks.named<Copy>("processTestResources") {
    dependsOn(renderKeycloakRealm)
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
    // Shared AES-256-GCM wire format with worker — bytes-in/bytes-out, no Spring leak. Stops
    // the api/worker copies from drifting on the at-rest secret format.
    implementation(project(":lib:crypto-aes-gcm"))
    implementation(project(":lib:plan-tier"))
    implementation(project(":lib:r2-config"))
    // Dogfood — emits the api's own errors back into Arguslog via the Logback appender. SDK
    // is no-op until ARGUSLOG_DSN is configured (always unset in tests + local dev).
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
