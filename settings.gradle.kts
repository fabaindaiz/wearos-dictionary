pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Dictionary"
include(":app")
include(":dict-core")
include(":dict-data")
include(":tools")

// Herramienta de desarrollo, no parte del producto: nadie la declara como dependencia y por eso
// no toca el APK que se mide. Ver watch-keepalive/build.gradle.kts.
include(":watch-keepalive")
