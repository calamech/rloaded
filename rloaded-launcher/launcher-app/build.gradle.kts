import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("rloaded.kotlin-library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val agent = configurations.dependencyScope("agent")
val agentJar = configurations.resolvable("agentJar") { extendsFrom(agent.get()) }

dependencies {
    implementation(project(":rloaded-launcher:launcher-core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.components.resources)
    agent(project(":rloaded-agent"))

    testImplementation(libs.kotlinx.coroutines.test)
}

// The launcher carries the agent jar and writes it to its data directory before each launch.
tasks.processResources {
    from(agentJar) {
        into("io/github/calamech/rloaded/launcher/app")
        rename { "rloaded-agent.jar" }
    }
}

compose.resources {
    packageOfResClass = "io.github.calamech.rloaded.launcher.app.resources"
    publicResClass = false
}

compose.desktop {
    application {
        mainClass = "io.github.calamech.rloaded.launcher.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Rloaded"
            packageVersion = "0.1.0"
        }
    }
}

// The screen and the entry point are checked by hand; LauncherState, behind them, is unit tested.
kover {
    reports {
        filters {
            excludes {
                classes(
                    "io.github.calamech.rloaded.launcher.app.LauncherWindowKt*",
                    "io.github.calamech.rloaded.launcher.app.ComposableSingletons*",
                    "io.github.calamech.rloaded.launcher.app.MainKt*",
                    "io.github.calamech.rloaded.launcher.app.resources.*",
                )
            }
        }
    }
}

// Resolves the desktop runtime of every platform, so the dependency checksums of all of them
// can be recorded from one machine: run it with --write-verification-metadata.
val allDesktopRuntimes = configurations.dependencyScope("allDesktopRuntimes")
val allDesktopRuntimesClasspath = configurations.resolvable("allDesktopRuntimesClasspath") { extendsFrom(allDesktopRuntimes.get()) }

dependencies {
    with(compose.desktop) {
        for (platform in listOf(linux_x64, linux_arm64, macos_x64, macos_arm64, windows_x64, windows_arm64)) {
            allDesktopRuntimes(platform)
        }
    }
}

tasks.register("resolveAllDesktopRuntimes") {
    description = "Resolves the Compose desktop runtime of every platform."
    val files = allDesktopRuntimesClasspath.map { it.incoming.files }
    inputs.files(files)
    doLast { logger.lifecycle("Resolved ${files.get().files.size} files") }
}
