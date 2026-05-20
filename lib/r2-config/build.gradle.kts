plugins {
    `java-library`
}

description =
    "R2 / S3 connection config record — shared between api (presigns uploads) and worker (fetches sourcemaps) so the env namespace cannot drift between services."

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
    // Carries Spring Boot's @ConfigurationProperties annotation. compileOnly is enough — consuming
    // services already have spring-boot on their classpath.
    compileOnly("org.springframework.boot:spring-boot:4.0.6")
}
