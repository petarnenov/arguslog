@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "arguslog"

include(
    ":services:ingest",
    ":services:worker",
    ":services:api",
    ":java-sdk",
    ":lib:crypto-aes-gcm",
)
