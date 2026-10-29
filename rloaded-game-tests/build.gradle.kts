plugins {
    id("rloaded.kotlin-library")
}

val agent = configurations.dependencyScope("agent")
val agentJar = configurations.resolvable("agentJar") { extendsFrom(agent.get()) }
val testMods = configurations.dependencyScope("testMods")
val testModJars = configurations.resolvable("testModJars") { extendsFrom(testMods.get()) }

dependencies {
    implementation(project(":rloaded-core"))
    implementation(project(":rloaded-server-bootstrap"))
    agent(project(":rloaded-agent"))
    testMods("io.github.calamech.rloaded:rloaded-runtime") { isTransitive = false }
    testMods("io.github.calamech.rloaded:rloaded-runtime") {
        isTransitive = false
        capabilities { requireCapability("io.github.calamech.rloaded:rloaded-runtime-gametest") }
    }
    testMods(libs.fabric.gametest.api) { isTransitive = false }
}

// The entry point downloads and starts a real game test server; its logic is checked by running it.
kover {
    reports {
        filters {
            excludes {
                classes("io.github.calamech.rloaded.gametests.MainKt*")
            }
        }
    }
}

// Runs the game tests on the pinned server, with the agent and the runtime mod, like players get it.
// Not part of check: it downloads the server and runs it, so CI runs it in a job of its own.
tasks.register<JavaExec>("gameTest") {
    description = "Runs the game tests on a real game test server."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.github.calamech.rloaded.gametests.MainKt"
    val cache = layout.buildDirectory.dir("game-test/cache")
    val run = layout.buildDirectory.dir("game-test/run")
    val report = layout.buildDirectory.file("game-test/report.xml")
    val agentFile = files(agentJar)
    val modFiles = files(testModJars)
    inputs.files(agentFile).withPropertyName("agent")
    inputs.files(modFiles).withPropertyName("testMods")
    outputs.file(report)
    argumentProviders.add {
        listOf(
            cache.get().asFile.absolutePath,
            run.get().asFile.absolutePath,
            report.get().asFile.absolutePath,
            agentFile.singleFile.absolutePath,
        ) + modFiles.files.map { it.absolutePath }
    }
}
