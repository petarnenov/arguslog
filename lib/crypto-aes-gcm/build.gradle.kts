plugins {
    `java-library`
}

description =
    "AES-256-GCM cipher with versioned wire format — shared between api + worker so they cannot drift on the at-rest secret format."

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
    // slf4j-api only — concrete logger is bound by the consuming service (api/worker → logback).
    api("org.slf4j:slf4j-api:2.0.18")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.27.0")
}

tasks.test {
    useJUnitPlatform()
}
