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
        // Fabric's game test modules, which the release of Fabric API on Modrinth leaves out.
        maven("https://maven.fabricmc.net/") {
            content {
                includeModule("net.fabricmc.fabric-api", "fabric-client-gametest-api-v1")
                includeModule("net.fabricmc.fabric-api", "fabric-gametest-api-v1")
            }
        }
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
includeBuild("rloaded-runtime")

include("rloaded-admin")
include("rloaded-agent")
include("rloaded-core")
include("rloaded-game-tests")
include("rloaded-launcher:launcher-app")
include("rloaded-launcher:launcher-core")
include("rloaded-modrinth")
include("rloaded-protocol")
include("rloaded-server-bootstrap")
