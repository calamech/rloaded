package io.github.calamech.rloaded.gametests

import io.github.calamech.rloaded.bootstrap.InstalledServer
import io.github.calamech.rloaded.bootstrap.ServerCommand
import io.github.calamech.rloaded.bootstrap.ServerConfig
import io.github.calamech.rloaded.bootstrap.ServerInstaller
import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.UrlPolicy
import io.github.calamech.rloaded.core.mojang.Platforms
import io.github.calamech.rloaded.core.runtime.RuntimePins
import io.github.calamech.rloaded.core.trust.ServerIdentity
import io.github.calamech.rloaded.core.trust.TrustStore
import io.github.calamech.rloaded.core.trust.TrustedServer
import io.github.calamech.rloaded.launcher.ClientCommand
import io.github.calamech.rloaded.launcher.ClientDirectories
import io.github.calamech.rloaded.launcher.GameInstaller
import io.github.calamech.rloaded.launcher.InstalledGame
import io.github.calamech.rloaded.launcher.LaunchSettings
import io.github.calamech.rloaded.launcher.OfflineProfile
import io.github.calamech.rloaded.launcher.ServerAddress
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.flatMap
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.system.exitProcess

// Checks that run a dedicated server and clients in processes of their own share these steps.

/** Time a whole check may take, from the first process started. */
internal const val CHECK_TIMEOUT_MINUTES = 15L
private const val SERVER_MEMORY_MIB = 2048
private const val CLIENT_MEMORY_MIB = 2048
private const val POLL_MILLIS = 500L
private const val STOP_SECONDS = 60L

/** Installs the pinned server and client; [features] decide which launch arguments the client gets. */
internal fun installServerAndClient(
    serverCache: Path,
    directories: ClientDirectories,
    features: Set<String>,
): Pair<InstalledServer, InstalledGame> {
    val platform = (Platforms.current() as? Outcome.Ok)?.value ?: failCheck("unsupported platform")
    return KtorHttp(UrlPolicy.PRODUCTION, HttpSettings()).use { http ->
        runBlocking {
            val server =
                when (val result = ServerInstaller(http, serverCache, platform, RuntimePins.CURRENT).install()) {
                    is Outcome.Ok -> result.value
                    is Outcome.Err -> failCheck(result.error)
                }
            val installer = GameInstaller(http, directories, platform, RuntimePins.CURRENT)
            val client =
                when (val result = installer.install(features)) {
                    is Outcome.Ok -> result.value
                    is Outcome.Err -> failCheck(result.error.toString())
                }
            server to client
        }
    }
}

/** Writes the EULA, which the project owner accepted for these test directories, and the server properties. */
internal fun prepareServer(
    directory: Path,
    port: Int,
) {
    directory.createDirectories()
    directory.resolve("eula.txt").writeText("eula=true\n")
    directory.resolve("server.properties").writeText(serverProperties(port))
}

/** Creates the server's identity and records, in the launcher's trust store, that the player trusts it. */
internal fun trustServer(
    serverDirectory: Path,
    launcherData: ClientDirectories,
    name: String,
) {
    val trusted =
        ServerIdentity.loadOrCreate(serverDirectory.resolve("rloaded").resolve("identity.key")).flatMap { identity ->
            TrustStore.open(launcherData.trustStore).flatMap { it.trust(TrustedServer(identity.publicKey, name, emptySet())) }
        }
    if (trusted is Outcome.Err) failCheck("cannot trust the server: ${trusted.error}")
}

/** Starts the server with the settings of a new server; [properties] may set others. */
internal fun startServer(
    installed: InstalledServer,
    agent: Path,
    mods: List<Path>,
    directory: Path,
    properties: Map<String, String>,
): Process =
    ProcessBuilder(
        ServerCommand.build(
            installed,
            agent,
            mods,
            SERVER_MEMORY_MIB,
            ServerConfig().gameProperties() + properties,
        ),
    ).directory(directory.toFile())
        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()

internal fun waitForPort(
    port: Int,
    server: Process,
    deadline: Long,
) {
    while (!accepts(port)) {
        if (!server.isAlive) failCheck("the server exited with code ${server.exitValue()} before it accepted players")
        if (System.nanoTime() > deadline) failCheck("the server did not accept players within $CHECK_TIMEOUT_MINUTES minutes")
        Thread.sleep(POLL_MILLIS)
    }
}

/** Starts a client in [directory]; with [server], it joins it at once. */
internal fun startClient(
    launch: ClientLaunch,
    directory: Path,
    player: String,
    server: ServerAddress?,
    properties: Map<String, String>,
): Process {
    directory.createDirectories()
    directory.resolve("options.txt").writeText(CLIENT_OPTIONS)
    val profile = (OfflineProfile.of(player) as? Outcome.Ok)?.value ?: failCheck("invalid player name $player")
    val settings =
        LaunchSettings(
            profile,
            directory,
            server,
            launch.agent,
            launch.runtimeMod,
            "game-check",
            launch.launcherData.mods,
            launch.launcherData.trustStore,
            CLIENT_MEMORY_MIB,
        )
    val command =
        when (val built = ClientCommand.build(launch.installed, settings, launch.directories.assets, directory.resolve("natives"))) {
            is Outcome.Ok -> withTestMods(built.value, launch.mods, properties)
            is Outcome.Err -> failCheck("unknown launch placeholder ${built.error.name}")
        }
    return ProcessBuilder(command).directory(directory.toFile()).inheritIO().start()
}

/** Waits for the clients to end; one that fails, or a server that stops first, fails the check at once. */
internal fun waitForClients(
    clients: Map<String, Process>,
    server: Process,
    deadline: Long,
    done: (String) -> Boolean,
) {
    while (clients.values.any { it.isAlive }) {
        clients.filterValues { !it.isAlive }.forEach { (name, process) ->
            val exit = process.exitValue()
            if (exit != 0 || !done(name)) failCheck("$name exited with code $exit before finishing")
        }
        if (!server.isAlive) failCheck("the server exited with code ${server.exitValue()} while clients were connected")
        if (System.nanoTime() > deadline) failCheck("the clients did not finish within $CHECK_TIMEOUT_MINUTES minutes")
        Thread.sleep(POLL_MILLIS)
    }
    clients.forEach { (name, process) ->
        if (process.exitValue() != 0 || !done(name)) failCheck("$name exited with code ${process.exitValue()} before finishing")
    }
}

internal fun stopServer(server: Process) {
    server.outputWriter().use { it.write("stop\n") }
    if (!server.waitFor(STOP_SECONDS, TimeUnit.SECONDS)) failCheck("the server did not stop within $STOP_SECONDS seconds")
    if (server.exitValue() != 0) failCheck("the server exited with code ${server.exitValue()}")
}

internal fun failCheck(message: String): Nothing {
    println("Game check failed: $message")
    exitProcess(1)
}

private fun accepts(port: Int): Boolean =
    try {
        Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), POLL_MILLIS.toInt()) }
        true
    } catch (_: IOException) {
        false
    }
