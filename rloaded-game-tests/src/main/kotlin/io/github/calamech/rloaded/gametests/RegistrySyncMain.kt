package io.github.calamech.rloaded.gametests

import io.github.calamech.rloaded.core.mojang.LaunchFeatures
import io.github.calamech.rloaded.launcher.ClientDirectories
import io.github.calamech.rloaded.launcher.ServerAddress
import io.github.calamech.rloaded.protocol.Outcome
import java.io.File
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.system.exitProcess

/**
 * Checks that Fabric's registry sync gives blocks added while players are connected the same raw
 * ids on the server and on every client, each in its own process.
 *
 * Starts the pinned server and two clients that join it. Each client registers two blocks in the
 * world, the second one in the reverse order of the server, so its raw ids differ; the server then
 * registers them and sends both players through the configuration phase again. Fails unless both
 * clients come back with the server's raw ids for every block and block state.
 *
 * The project owner accepted the Minecraft EULA for the server directory this check creates.
 *
 * Arguments: client cache, server cache, run directory, agent jar, runtime mod jar, then the server
 * mod jars and the client mod jars, each list joined with the path separator.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
public fun main(arguments: Array<String>) {
    val paths = arguments.take(FIXED_PATHS).map { Path.of(it) }
    val (clientCache, serverCache, run) = paths
    val agent = paths[AGENT_ARGUMENT]
    val (serverMods, clientMods) = arguments.drop(FIXED_PATHS).map { list -> list.split(File.pathSeparator).map { Path.of(it) } }
    val directories = ClientDirectories(clientCache)
    val (server, client) = installServerAndClient(serverCache, directories, setOf(LaunchFeatures.QUICK_PLAY_MULTIPLAYER))
    if (run.exists()) run.deleteRecursively()
    val processes = mutableListOf<Process>()
    Runtime.getRuntime().addShutdownHook(Thread { processes.forEach { it.destroyForcibly() } })
    val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(CHECK_TIMEOUT_MINUTES)

    val port = ServerSocket(0).use { it.localPort }
    val serverDirectory = run.resolve("server")
    prepareServer(serverDirectory, port)
    val launcherData = ClientDirectories(run.resolve("launcher-data"))
    trustServer(serverDirectory, launcherData, "server")
    val clientLaunch = ClientLaunch(client, directories, launcherData, agent, paths[RUNTIME_MOD_ARGUMENT], clientMods)
    val serverProperties = checkProperties(serverDirectory, SERVER_ORDER) + ("rloaded.registry-sync.players" to "${CLIENT_ORDERS.size}")
    val serverProcess = startServer(server, agent, serverMods, serverDirectory, serverProperties)
    processes += serverProcess
    waitForPort(port, serverProcess, deadline)

    val clientDirectories =
        CLIENT_ORDERS.mapIndexed { index, order ->
            val name = "client-${index + 1}"
            val directory = run.resolve(name)
            val address = ServerAddress(LOOPBACK, port)
            processes += startClient(clientLaunch, directory, "Tester${index + 1}", address, checkProperties(directory, order))
            name to directory
        }
    val byName = clientDirectories.toMap()
    // A client that quits without its ids would leave the server waiting for it.
    waitForClients(clientDirectories.map { it.first }.zip(processes.drop(1)).toMap(), serverProcess, deadline) { name ->
        byName.getValue(name).resolve("after.txt").exists()
    }
    stopServer(serverProcess)

    val serverIds = readIds(serverDirectory.resolve("server.txt"))
    val before = clientDirectories.associate { (name, directory) -> name to readIds(directory.resolve("before.txt")) }
    val after = clientDirectories.associate { (name, directory) -> name to readIds(directory.resolve("after.txt")) }
    val problems = registrySyncProblems(serverIds, before, after, ADDED_BLOCKS)
    println("Registry sync check: ${serverIds.ids.size} raw ids on the server, ${problems.size} problems")
    problems.forEach { println("  FAILED $it") }
    exitProcess(if (problems.isEmpty()) 0 else 1)
}

private const val FIXED_PATHS = 5
private const val AGENT_ARGUMENT = 3
private const val RUNTIME_MOD_ARGUMENT = 4

private fun readIds(file: Path): RegistryIds {
    if (!file.exists()) failCheck("no raw ids in $file")
    return when (val parsed = RegistryIds.parse(file.readText())) {
        is Outcome.Ok -> parsed.value
        is Outcome.Err -> failCheck("$file: ${parsed.error}")
    }
}
