package io.github.calamech.rloaded.gametests

import io.github.calamech.rloaded.bootstrap.InstalledServer
import io.github.calamech.rloaded.bootstrap.ServerCommand
import io.github.calamech.rloaded.bootstrap.ServerInstaller
import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.UrlPolicy
import io.github.calamech.rloaded.core.mojang.LaunchFeatures
import io.github.calamech.rloaded.core.mojang.Platforms
import io.github.calamech.rloaded.core.runtime.RuntimePins
import io.github.calamech.rloaded.launcher.ClientCommand
import io.github.calamech.rloaded.launcher.ClientDirectories
import io.github.calamech.rloaded.launcher.GameInstaller
import io.github.calamech.rloaded.launcher.InstalledGame
import io.github.calamech.rloaded.launcher.LaunchSettings
import io.github.calamech.rloaded.launcher.OfflineProfile
import io.github.calamech.rloaded.launcher.ServerAddress
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
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
    val (server, client) = install(serverCache, directories)
    val clientLaunch = ClientLaunch(client, directories, agent, paths[RUNTIME_MOD_ARGUMENT], clientMods)
    if (run.exists()) run.deleteRecursively()
    val processes = mutableListOf<Process>()
    Runtime.getRuntime().addShutdownHook(Thread { processes.forEach { it.destroyForcibly() } })
    val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(TIMEOUT_MINUTES)

    val port = ServerSocket(0).use { it.localPort }
    val serverDirectory = run.resolve("server").createDirectories()
    serverDirectory.resolve("eula.txt").writeText("eula=true\n")
    serverDirectory.resolve("server.properties").writeText(serverProperties(port))
    val serverProperties = checkProperties(serverDirectory, SERVER_ORDER) + ("rloaded.registry-sync.players" to "${CLIENT_ORDERS.size}")
    val serverProcess =
        ProcessBuilder(ServerCommand.build(server, agent, serverMods, SERVER_MEMORY_MIB, serverProperties))
            .directory(serverDirectory.toFile())
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
    processes += serverProcess
    waitForPort(port, serverProcess, deadline)

    val clientDirectories =
        CLIENT_ORDERS.mapIndexed { index, order ->
            val name = "client-${index + 1}"
            val directory = run.resolve(name).createDirectories()
            processes += startClient(clientLaunch, ClientRun(name, directory, order, port))
            name to directory
        }
    waitForClients(clientDirectories.map { it.first }.zip(processes.drop(1)).toMap(), clientDirectories.toMap(), serverProcess, deadline)
    stopServer(serverProcess)

    val serverIds = readIds(serverDirectory.resolve("server.txt"))
    val before = clientDirectories.associate { (name, directory) -> name to readIds(directory.resolve("before.txt")) }
    val after = clientDirectories.associate { (name, directory) -> name to readIds(directory.resolve("after.txt")) }
    val problems = registrySyncProblems(serverIds, before, after, ADDED_BLOCKS)
    println("Registry sync check: ${serverIds.ids.size} raw ids on the server, ${problems.size} problems")
    problems.forEach { println("  FAILED $it") }
    exitProcess(if (problems.isEmpty()) 0 else 1)
}

private data class ClientLaunch(
    val installed: InstalledGame,
    val directories: ClientDirectories,
    val agent: Path,
    val runtimeMod: Path,
    val mods: List<Path>,
)

private data class ClientRun(
    val name: String,
    val directory: Path,
    val order: String,
    val port: Int,
)

private const val FIXED_PATHS = 5
private const val AGENT_ARGUMENT = 3
private const val RUNTIME_MOD_ARGUMENT = 4
private const val TIMEOUT_MINUTES = 15L
private const val SERVER_MEMORY_MIB = 2048
private const val CLIENT_MEMORY_MIB = 2048
private const val POLL_MILLIS = 500L
private const val STOP_SECONDS = 60L

private fun install(
    serverCache: Path,
    directories: ClientDirectories,
): Pair<InstalledServer, InstalledGame> {
    val platform = (Platforms.current() as? Outcome.Ok)?.value ?: fail("unsupported platform")
    return KtorHttp(UrlPolicy.PRODUCTION, HttpSettings()).use { http ->
        runBlocking {
            val server =
                when (val result = ServerInstaller(http, serverCache, platform, RuntimePins.CURRENT).install()) {
                    is Outcome.Ok -> result.value
                    is Outcome.Err -> fail(result.error)
                }
            val installer = GameInstaller(http, directories, platform, RuntimePins.CURRENT)
            val client =
                when (val result = installer.install(setOf(LaunchFeatures.QUICK_PLAY_MULTIPLAYER))) {
                    is Outcome.Ok -> result.value
                    is Outcome.Err -> fail(result.error.toString())
                }
            server to client
        }
    }
}

private fun startClient(
    launch: ClientLaunch,
    run: ClientRun,
): Process {
    run.directory.resolve("options.txt").writeText(CLIENT_OPTIONS)
    val player = "Tester${run.name.substringAfter('-')}"
    val profile = (OfflineProfile.of(player) as? Outcome.Ok)?.value ?: fail("invalid player name $player")
    val address = ServerAddress(LOOPBACK, run.port)
    val settings =
        LaunchSettings(profile, run.directory, address, launch.agent, launch.runtimeMod, "registry-sync-check", CLIENT_MEMORY_MIB)
    val command =
        when (val built = ClientCommand.build(launch.installed, settings, launch.directories.assets, run.directory.resolve("natives"))) {
            is Outcome.Ok -> withTestMods(built.value, launch.mods, checkProperties(run.directory, run.order))
            is Outcome.Err -> fail("unknown launch placeholder ${built.error.name}")
        }
    return ProcessBuilder(command).directory(run.directory.toFile()).inheritIO().start()
}

private fun waitForPort(
    port: Int,
    server: Process,
    deadline: Long,
) {
    while (!accepts(port)) {
        if (!server.isAlive) fail("the server exited with code ${server.exitValue()} before it accepted players")
        if (System.nanoTime() > deadline) fail("the server did not accept players within $TIMEOUT_MINUTES minutes")
        Thread.sleep(POLL_MILLIS)
    }
}

private fun accepts(port: Int): Boolean =
    try {
        Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), POLL_MILLIS.toInt()) }
        true
    } catch (_: IOException) {
        false
    }

// A client that quits without its ids would leave the server waiting for it, so fail at once.
private fun waitForClients(
    clients: Map<String, Process>,
    directories: Map<String, Path>,
    server: Process,
    deadline: Long,
) {
    while (clients.values.any { it.isAlive }) {
        clients.filterValues { !it.isAlive }.forEach { (name, process) ->
            val exit = process.exitValue()
            val ids = directories.getValue(name).resolve("after.txt")
            if (exit != 0 || !ids.exists()) fail("$name exited with code $exit, raw ids after the sync written: ${ids.exists()}")
        }
        if (!server.isAlive) fail("the server exited with code ${server.exitValue()} while clients were connected")
        if (System.nanoTime() > deadline) fail("the clients did not finish within $TIMEOUT_MINUTES minutes")
        Thread.sleep(POLL_MILLIS)
    }
    clients.forEach { (name, process) ->
        if (process.exitValue() != 0) fail("$name exited with code ${process.exitValue()}")
    }
}

private fun stopServer(server: Process) {
    server.outputWriter().use { it.write("stop\n") }
    if (!server.waitFor(STOP_SECONDS, TimeUnit.SECONDS)) fail("the server did not stop within $STOP_SECONDS seconds")
    if (server.exitValue() != 0) fail("the server exited with code ${server.exitValue()}")
}

private fun readIds(file: Path): RegistryIds {
    if (!file.exists()) fail("no raw ids in $file")
    return when (val parsed = RegistryIds.parse(file.readText())) {
        is Outcome.Ok -> parsed.value
        is Outcome.Err -> fail("$file: ${parsed.error}")
    }
}

private fun fail(message: String): Nothing {
    println("Registry sync check failed: $message")
    exitProcess(1)
}
