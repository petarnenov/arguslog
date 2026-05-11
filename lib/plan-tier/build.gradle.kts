plugins {
    `java-library`
}

description =
    "PlanTier enum — single source of truth for per-plan limits, pricing, and retention. Shared between api / ingest / worker so caps cannot drift between services."

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Werror"))
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.27.0")
}

tasks.test {
    useJUnitPlatform()
}
