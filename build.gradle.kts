plugins {
    alias(libs.plugins.spotless) apply false
}

allprojects {
    // Reverse-DNS of project domain arguslog.org. Java packages live under org.arguslog.*.
    group = "org.arguslog"
    version = "0.0.1-SNAPSHOT"
}

subprojects {
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat("1.25.2")
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
        kotlinGradle {
            target("**/*.gradle.kts")
            ktlint("1.5.0")
        }
    }
}
