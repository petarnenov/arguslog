plugins {
    `java-library`
    `maven-publish`
    id("io.spring.dependency-management")
}

group = "dev.argus"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
    withJavadocJar()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.6")
    }
}

dependencies {
    // Optional Spring Boot integration — consumers can use the SDK without Spring.
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-starter-aop")
    compileOnly("org.springframework:spring-web")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-aop")
    testImplementation("org.springframework:spring-web")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.mockito:mockito-junit-jupiter")
    testImplementation("org.wiremock:wiremock-standalone:3.10.0")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("Argus Java SDK")
                description.set("Argus error tracking SDK for Java and Spring Boot.")
                url.set("https://github.com/argus/argus")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
            }
        }
    }
}
