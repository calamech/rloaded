plugins {
    id("rloaded.kotlin-library")
}

val agent = configurations.dependencyScope("agent")
val agentJar = configurations.resolvable("agentJar") { extendsFrom(agent.get()) }
val testMods = configurations.dependencyScope("testMods")
val testModJars = configurations.resolvable("testModJars") { extendsFrom(testMods.get()) }
val runtimeMod = configurations.dependencyScope("runtimeMod")
val runtimeModJar = configurations.resolvable("runtimeModJar") { extendsFrom(runtimeMod.get()) }
val clientTestMods = configurations.dependencyScope("clientTestMods")
val clientTestModJars = configurations.resolvable("clientTestModJars") { extendsFrom(clientTestMods.get()) }

dependencies {
    implementation(project(":rloaded-core"))
    implementation(project(":rloaded-server-bootstrap"))
    implementation(project(":rloaded-launcher:launcher-core"))
    agent(project(":rloaded-agent"))
    testMods("io.github.calamech.rloaded:rloaded-runtime") { isTransitive = false }
    testMods("io.github.calamech.rloaded:rloaded-runtime") {
        isTransitive = false
        capabilities { requireCapability("io.github.calamech.rloaded:rloaded-runtime-gametest") }
    }
    testMods(libs.fabric.gametest.api) { isTransitive = false }
    runtimeMod("io.github.calamech.rloaded:rloaded-runtime") { isTransitive = false }
    clientTestMods("io.github.calamech.rloaded:rloaded-runtime") {
        isTransitive = false
        capabilities { requireCapability("io.github.calamech.rloaded:rloaded-runtime-gametest") }
    }
    clientTestMods(libs.fabric.gametest.api) { isTransitive = false }
    clientTestMods(libs.fabric.client.gametest.api) { isTransitive = false }
}

// The entry point downloads and starts a real game test server; its logic is checked by running it.
kover {
    reports {
        filters {
            excludes {
                classes("io.github.calamech.rloaded.gametests.MainKt*")
                // Like Main, but starts a client; the command it builds is unit tested in ClientTestCommand.
                classes("io.github.calamech.rloaded.gametests.ClientMainKt*")
                // Starts a server and two clients; the comparison it ends with is unit tested in RegistryIds.
                classes(
                    "io.github.calamech.rloaded.gametests.RegistrySyncMainKt*",
                    "io.github.calamech.rloaded.gametests.ClientLaunch",
                    "io.github.calamech.rloaded.gametests.ClientRun",
                )
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

// Runs the client game tests: installs the pinned client like the launcher does, starts it with
// the agent, the runtime mod and the test mods, and fails unless the tests passed. Needs a display;
// CI runs it under a virtual one in a job of its own.
tasks.register<JavaExec>("clientGameTest") {
    description = "Runs the client game tests on a real client."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.github.calamech.rloaded.gametests.ClientMainKt"
    val cache = layout.buildDirectory.dir("client-game-test/cache")
    val run = layout.buildDirectory.dir("client-game-test/run")
    val report = layout.buildDirectory.file("client-game-test/report.txt")
    val agentFile = files(agentJar)
    val runtimeFile = files(runtimeModJar)
    val modFiles = files(clientTestModJars)
    inputs.files(agentFile).withPropertyName("agent")
    inputs.files(runtimeFile).withPropertyName("runtimeMod")
    inputs.files(modFiles).withPropertyName("clientTestMods")
    outputs.file(report)
    doFirst { report.get().asFile.delete() }
    argumentProviders.add {
        listOf(
            cache.get().asFile.absolutePath,
            run.get().asFile.absolutePath,
            report.get().asFile.absolutePath,
            agentFile.singleFile.absolutePath,
            runtimeFile.singleFile.absolutePath,
        ) + modFiles.files.map { it.absolutePath }
    }
}

// Runs the pinned server in its own process and two clients that join it, adds blocks while they
// are connected, and fails unless the three agree on the raw ids. Needs a display, like
// clientGameTest, and runs in the same CI job.
tasks.register<JavaExec>("registrySyncTest") {
    description = "Checks that the server and two clients agree on raw ids after an add."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.github.calamech.rloaded.gametests.RegistrySyncMainKt"
    val clientCache = layout.buildDirectory.dir("client-game-test/cache")
    val serverCache = layout.buildDirectory.dir("registry-sync-test/server-cache")
    val run = layout.buildDirectory.dir("registry-sync-test/run")
    val agentFile = files(agentJar)
    val runtimeFile = files(runtimeModJar)
    val serverModFiles = files(testModJars)
    val clientModFiles = files(clientTestModJars)
    inputs.files(agentFile).withPropertyName("agent")
    inputs.files(runtimeFile).withPropertyName("runtimeMod")
    inputs.files(serverModFiles).withPropertyName("serverMods")
    inputs.files(clientModFiles).withPropertyName("clientMods")
    argumentProviders.add {
        listOf(
            clientCache.get().asFile.absolutePath,
            serverCache.get().asFile.absolutePath,
            run.get().asFile.absolutePath,
            agentFile.singleFile.absolutePath,
            runtimeFile.singleFile.absolutePath,
            serverModFiles.files.joinToString(File.pathSeparator) { it.absolutePath },
            clientModFiles.files.joinToString(File.pathSeparator) { it.absolutePath },
        )
    }
}
