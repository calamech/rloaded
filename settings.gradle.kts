pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // Compose for Desktop depends on a few AndroidX libraries, published on Google's repository only.
        google {
            content {
                includeGroupByRegex("androidx\\..*")
            }
        }
    }
}

rootProject.name = "rloaded"

// Also included as a regular build so the detekt rules it builds can be used as a dependency.
includeBuild("build-logic")

include("rloaded-agent")
include("rloaded-core")
include("rloaded-launcher:launcher-app")
include("rloaded-launcher:launcher-core")
include("rloaded-server-bootstrap")
