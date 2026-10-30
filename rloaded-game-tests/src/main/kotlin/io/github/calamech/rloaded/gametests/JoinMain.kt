package io.github.calamech.rloaded.gametests

import io.github.calamech.rloaded.launcher.ClientDirectories
import java.io.File
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Checks a first join end to end: a server in its own process runs content-basic and
 * mixin-loaded-class from its lockfile; a client with only the base set joins it, accepts the trust
 * screen, downloads both mods from a fake Modrinth, loads them before the world exists, and uses them
 * in the world without a restart.
 *
 * The project owner accepted the Minecraft EULA for the server directory this check creates.
 *
 * Arguments: client cache, server cache, run directory, agent jar, runtime mod jar, then the server
 * mod jars, the client mod jars and the jars to serve as content-basic and mixin-loaded-class, each
 * list joined with the path separator.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
public fun main(arguments: Array<String>) {
    val paths = arguments.take(FIXED_PATHS).map { Path.of(it) }
    val (clientCache, serverCache, run) = paths
    val agent = paths[AGENT_ARGUMENT]
    val (serverMods, clientMods, served) = arguments.drop(FIXED_PATHS).map { list -> list.split(File.pathSeparator).map { Path.of(it) } }
    val directories = ClientDirectories(clientCache)
    val (server, client) = installServerAndClient(serverCache, directories, emptySet())
    if (run.exists()) run.deleteRecursively()
    val processes = mutableListOf<Process>()
    Runtime.getRuntime().addShutdownHook(Thread { processes.forEach { it.destroyForcibly() } })
    val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(CHECK_TIMEOUT_MINUTES)

    val mods =
        listOf(
            FakeMod("cbProject", "cbVersion", "Content basic", served.single { "contentBasic" in it.fileName.toString() }),
            FakeMod("mlcProject", "mlcVersion", "Mixin on a loaded class", served.single { "mixinLoadedClass" in it.fileName.toString() }),
        )
    FakeModrinth(mods).use { modrinth ->
        val port = ServerSocket(0).use { it.localPort }
        val serverDirectory = run.resolve("server")
        prepareServer(serverDirectory, port)
        serverDirectory
            .resolve("rloaded")
            .createDirectories()
            .resolve("lockfile.json")
            .writeText(lockfile(mods))
        val serverProcess = startServer(server, agent, serverMods, serverDirectory, emptyMap())
        processes += serverProcess
        waitForPort(port, serverProcess, deadline)

        val clientDirectory = run.resolve("client")
        val launcherData = ClientDirectories(run.resolve("launcher-data"))
        val report = run.resolve("join-report.txt")
        val properties =
            mapOf(
                "fabric.client.gametest" to "true",
                "rloaded.join-test.server" to "$LOOPBACK:$port",
                "rloaded.join-test.report" to report.toString(),
                "rloaded.test.modrinth" to modrinth.url,
            )
        val launch = ClientLaunch(client, directories, launcherData, agent, paths[RUNTIME_MOD_ARGUMENT], clientMods)
        processes += startClient(launch, clientDirectory, "Tester", null, properties)
        waitForClients(mapOf("client" to processes.last()), serverProcess, deadline) { report.exists() }
        stopServer(serverProcess)

        val trust = launcherData.trustStore.readText()
        val cached =
            launcherData.mods
                .walk()
                .filter { it.toString().endsWith(".jar") }
                .count()
        println("Join check: ${report.readText().trim()}; $cached jars in the cache; trust store written: ${"\"key\"" in trust}")
        exitProcess(if (cached == mods.size && "\"key\"" in trust) 0 else 1)
    }
}

private const val FIXED_PATHS = 5
private const val AGENT_ARGUMENT = 3
private const val RUNTIME_MOD_ARGUMENT = 4

private fun lockfile(mods: List<FakeMod>): String {
    val entries =
        mods.joinToString(",\n") { mod ->
            """    {"projectId": "${mod.projectId}", "versionId": "${mod.versionId}", "fileName": "${mod.jar.fileName}", """ +
                """"sha512": "${mod.sha512}", "size": ${mod.size}, "side": "BOTH", """ +
                """"addedAt": "2026-10-30T12:00:00Z", "addedBy": "console"}"""
        }
    return """{"schema": 1, "minecraft": "26.3", "rloaded": "0.1.0", "baseSet": [], "mods": [
$entries
], "pendingRemovals": []}
"""
}
