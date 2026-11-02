package io.github.calamech.rloaded.gametests

import io.github.calamech.rloaded.admin.CompatDatabase
import io.github.calamech.rloaded.admin.CompatStatus
import io.github.calamech.rloaded.admin.LocalCompat
import io.github.calamech.rloaded.admin.staging.StagingSettings
import io.github.calamech.rloaded.bootstrap.RolloutSection
import io.github.calamech.rloaded.bootstrap.ServerConfig
import io.github.calamech.rloaded.core.mojang.LaunchFeatures
import io.github.calamech.rloaded.launcher.ClientDirectories
import io.github.calamech.rloaded.launcher.Relaunch
import io.github.calamech.rloaded.launcher.ServerAddress
import io.github.calamech.rloaded.protocol.Outcome
import java.net.ServerSocket
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Checks what happens when a player's game fails to load a mod added while it plays: a server in its
 * own process, one player who trusts it, and `rloaded add` typed into the console for
 * fails-on-client-once, a mod that the server runs and that fails once as it starts on a client. The
 * player's game must stop and ask to be started again; the check then starts it again as the launcher
 * does, and the fresh game must join the server with the mod. The server must keep running, record
 * the version as broken in `rloaded/compat-local.json`, and say so in its log. The players are plain
 * games joining by quick play, as players' games are, not client game tests.
 *
 * The console then adds content-basic, whose file comes to the player's game after the server's join
 * timeout: the server disconnects the game, which must stop and ask to be started again too. The
 * fresh game gets the file at once and must join the server.
 *
 * The project owner accepted the Minecraft EULA for the server directory this check creates.
 *
 * Arguments: client cache, server cache, run directory, agent jar, runtime mod jar, then the jars to
 * serve as fails-on-client-once and content-basic.
 */
@OptIn(ExperimentalPathApi::class)
public fun main(arguments: Array<String>) {
    val paths = arguments.map { Path.of(it) }
    val (clientCache, serverCache, run) = paths
    val agent = paths[AGENT_ARGUMENT]
    val runtime = paths[RUNTIME_MOD_ARGUMENT]
    val served = paths.drop(SERVED_ARGUMENT)
    val mod = FakeMod(PROJECT, VERSION, "Fails on client once", served.single { "failsOnClientOnce" in it.fileName.toString() })
    val slow = FakeMod(SLOW_PROJECT, "cbVersion", "Content basic", served.single { "contentBasic" in it.fileName.toString() })
    val directories = ClientDirectories(clientCache)
    val (server, client) = installServerAndClient(serverCache, directories, setOf(LaunchFeatures.QUICK_PLAY_MULTIPLAYER))
    if (run.exists()) run.deleteRecursively()
    val processes = mutableListOf<Process>()
    Runtime.getRuntime().addShutdownHook(Thread { processes.forEach { it.destroyForcibly() } })
    val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(CHECK_TIMEOUT_MINUTES)

    // The check ends the process, which stops the fake Modrinths with it. The player's game gets
    // content-basic late the first time it asks.
    val modrinth = FakeModrinth(listOf(mod, slow))
    val playerModrinth = FakeModrinth(listOf(mod, slow.copy(fileDelay = SLOW_FILE, delayedRequests = 1)))
    val port = ServerSocket(0).use { it.localPort }
    val serverDirectory = run.resolve("server")
    prepareServer(serverDirectory, port)
    val launcherData = ClientDirectories(run.resolve("player").resolve("launcher-data"))
    trustServer(serverDirectory, launcherData, "server")
    val serverProperties =
        mapOf(MODRINTH_PROPERTY to modrinth.url, StagingSettings.ENABLED_PROPERTY to "false") +
            ServerConfig(rollout = RolloutSection(TIMEOUT_SECONDS)).gameProperties()
    val serverProcess = startServer(server, agent, listOf(runtime), serverDirectory, serverProperties)
    processes += serverProcess
    waitForPort(port, serverProcess, deadline)
    val serverLog = serverDirectory.resolve("logs").resolve("latest.log")
    val until = Until(serverProcess, deadline)

    val marker = run.resolve("player").createDirectories().resolve("fail-once")
    marker.writeText("fail once\n")
    val launch = ClientLaunch(client, directories, launcherData, agent, runtime, emptyList())
    val properties = mapOf(MODRINTH_PROPERTY to playerModrinth.url, FAIL_ONCE_PROPERTY to marker.toString())
    val game = run.resolve("player").resolve("game")
    val startGame = { startClient(launch, game, PLAYER, ServerAddress(LOOPBACK, port), properties).also { processes += it } }
    val first = startGame()
    until("$PLAYER joins") { joins(serverLog) == 1 }
    serverProcess.outputWriter().run {
        write("rloaded add $PROJECT\n")
        flush()
    }
    until("$PLAYER's game exits") { !first.isAlive }
    val firstExit = first.exitValue()
    println("Recovery check: the game exited with code $firstExit; fail-once marker left: ${marker.exists()}")
    if (!Relaunch.wanted(firstExit, 0)) failCheck("the game did not ask to be started again")

    val second = startGame()
    until("$PLAYER joins again") { joins(serverLog) == 2 }
    // The game left during the add's configuration phase, for real: the chat says so once.
    val leaves = serverLog.readText().lines().count { "$PLAYER left the game" in it }
    timesOutAndJoinsAgain(serverProcess, serverLog, until, second, startGame)
    stopServer(serverProcess)

    val log = serverLog.readText()
    val reported = log.lines().any { "$PLAYER's game could not load $PROJECT (version $VERSION)" in it && "/ERROR]" in it }
    val recorded = recorded(serverDirectory.resolve("rloaded").resolve("compat-local.json"))
    println("Recovery check: failure in the server log: $reported; recorded as $recorded; left the game $leaves times before joining again")
    val passed = listOf(!marker.exists(), reported, recorded == CompatStatus.BROKEN, leaves == 1).all { it }
    exitProcess(if (passed) 0 else 1)
}

/**
 * Adds content-basic, which reaches [game] after the join timeout: the server must disconnect the
 * game, which must ask to be started again, and the fresh game must join.
 */
private fun timesOutAndJoinsAgain(
    server: Process,
    serverLog: Path,
    until: Until,
    game: Process,
    startGame: () -> Process,
) {
    val joinsBefore = joins(serverLog)
    server.outputWriter().run {
        write("rloaded add $SLOW_PROJECT\n")
        flush()
    }
    until("the server disconnects $PLAYER's game, which took too long") { TIMED_OUT in serverLog.readText() }
    until("$PLAYER's game exits again") { !game.isAlive }
    println("Recovery check: after the join timeout the game exited with code ${game.exitValue()}")
    if (!Relaunch.wanted(game.exitValue(), 1)) failCheck("the game did not ask to be started again after the join timeout")
    val fresh = startGame()
    until("$PLAYER joins with content-basic") { joins(serverLog) == joinsBefore + 1 }
    fresh.destroy()
}

private fun joins(serverLog: Path): Int =
    if (serverLog.exists()) serverLog.readText().lines().count { "$PLAYER joined the game" in it } else 0

private fun recorded(file: Path): CompatStatus? {
    val empty = CompatDatabase(emptyMap(), emptyMap(), emptyMap())
    return when (val compat = LocalCompat.open(file, empty)) {
        is Outcome.Ok -> {
            compat.value
                .current()
                .versions[VERSION]
                ?.status
        }

        is Outcome.Err -> {
            failCheck(compat.error)
        }
    }
}

/**
 * Waits for a condition; a server that stops, or the step's or the check's time running out, fails
 * the check.
 */
private class Until(
    private val server: Process,
    private val deadline: Long,
) {
    operator fun invoke(
        what: String,
        condition: () -> Boolean,
    ) {
        val stepDeadline = minOf(deadline, System.nanoTime() + TimeUnit.MINUTES.toNanos(STEP_MINUTES))
        while (!condition()) {
            if (!server.isAlive) failCheck("the server exited with code ${server.exitValue()} before \"$what\"")
            if (System.nanoTime() > stepDeadline) failCheck("\"$what\" did not happen within $STEP_MINUTES minutes")
            Thread.sleep(POLL_MILLIS)
        }
        println("Recovery check: $what")
    }
}

private const val PLAYER = "Alice"
private const val PROJECT = "focProject"
private const val VERSION = "focVersion"
private const val MODRINTH_PROPERTY = "rloaded.test.modrinth"
private const val FAIL_ONCE_PROPERTY = "rloaded.test.fail-once"
private const val SLOW_PROJECT = "cbProject"
private const val POLL_MILLIS = 500L
private const val TIMEOUT_SECONDS = 15

// Past the join timeout, and below the client's 30 seconds of silence allowed on a socket, so the
// download itself does not fail.
private const val SLOW_FILE_SECONDS = 22L
private val SLOW_FILE: Duration = Duration.ofSeconds(SLOW_FILE_SECONDS)
private const val TIMED_OUT = "$PLAYER stopped answering while installing the server's mods and was disconnected"

// A step takes well under a minute in local; the first launch installs nothing.
private const val STEP_MINUTES = 4L
private const val AGENT_ARGUMENT = 3
private const val RUNTIME_MOD_ARGUMENT = 4
private const val SERVED_ARGUMENT = 5
