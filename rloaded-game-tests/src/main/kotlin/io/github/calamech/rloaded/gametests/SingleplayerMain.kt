package io.github.calamech.rloaded.gametests

import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.UrlPolicy
import io.github.calamech.rloaded.core.mojang.Platforms
import io.github.calamech.rloaded.core.runtime.RuntimePins
import io.github.calamech.rloaded.launcher.ClientDirectories
import io.github.calamech.rloaded.launcher.GameInstaller
import io.github.calamech.rloaded.launcher.Relaunch
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.system.exitProcess

/**
 * Installs mods in an open singleplayer world from the game's Mods screen, and opens the world
 * again, against a fake Modrinth that serves content-basic and mixin-field-init and whose search
 * still lists a project Modrinth removed. Three games, in the same game folder: the first clicks
 * through the screen and opens the world again; a fresh game, with an empty mod store, opens the
 * world, then a world without mods, and must ask to be started again; started again, as the
 * launcher does, it opens that world by itself. Fails unless each game's test passed, each exited
 * as expected, and no line of a game's log is an error from Rloaded's first step on.
 *
 * Arguments: client cache, run directory, agent jar, runtime mod jar, then the client mod jars and
 * the jars to serve, each list joined with the path separator.
 */
@OptIn(ExperimentalPathApi::class)
public fun main(arguments: Array<String>) {
    val paths = arguments.take(FIXED_PATHS).map { Path.of(it) }
    val (cache, run) = paths
    val (clientMods, served) = arguments.drop(FIXED_PATHS).map { list -> list.split(File.pathSeparator).map { Path.of(it) } }
    val directories = ClientDirectories(cache)
    val installed = installClient(directories)
    if (run.exists()) run.deleteRecursively()
    run.createDirectories()
    val mods =
        listOf(
            FakeMod("cbProject", "cbVersion", "Content basic", served.single { "contentBasic" in it.fileName.toString() }),
            FakeMod("mfiProject", "mfiVersion", "Mixin field initializer", served.single { "mixinFieldInit" in it.fileName.toString() }),
        )
    // The check ends the process, which stops the fake Modrinth with it.
    val modrinth = FakeModrinth(mods, unlisted = listOf("removedProject"))
    val game = run.resolve("game")
    val state = run.resolve("state.txt")
    // The first game adds the mods; a fresh game, with an empty mod store, opens the world later.
    val first =
        ClientLaunch(installed, directories, ClientDirectories(run.resolve("launcher-data")), paths[AGENT], paths[RUNTIME], clientMods)
    val fresh = first.copy(launcherData = ClientDirectories(run.resolve("fresh-launcher-data")))
    val play = { launch: ClientLaunch, step: String ->
        val report = run.resolve("$step.txt")
        val properties =
            mapOf(
                "fabric.client.gametest" to "true",
                "rloaded.client-game-test.report" to report.toString(),
                "rloaded.singleplayer-test.step" to step,
                "rloaded.singleplayer-test.state" to state.toString(),
                MODRINTH_PROPERTY to modrinth.url,
            )
        val process = startClient(launch, game, PLAYER, null, properties)
        Runtime.getRuntime().addShutdownHook(Thread { process.destroyForcibly() })
        if (!process.waitFor(CHECK_TIMEOUT_MINUTES, TimeUnit.MINUTES)) failCheck("the $step game did not finish in time")
        val passed = if (report.exists()) report.readLines().filter { it.isNotBlank() } else emptyList()
        val errors = errors(game.resolve("logs").resolve("latest.log").readText(), MARKS.getValue(step))
        println("Singleplayer check, $step game: exit code ${process.exitValue()}, passed: $passed, ${errors.size} error lines")
        errors.forEach { println("  $it") }
        Triple(process.exitValue(), passed, errors)
    }
    val (firstExit, firstPassed, firstErrors) = play(first, "first")
    if (firstExit != 0 || firstPassed.isEmpty() || firstErrors.isNotEmpty()) exitProcess(1)
    // The fresh game must ask to be started again, as it does when it opens a world without its mods.
    val (reopenExit, reopenPassed, reopenErrors) = play(fresh, "reopen")
    if (!Relaunch.wanted(reopenExit, 0) || reopenPassed.isEmpty() || reopenErrors.isNotEmpty()) exitProcess(1)
    val (restartedExit, restartedPassed, restartedErrors) = play(fresh, "restarted")
    // It opens Plain without the mods, then Island after the removal of one of them.
    exitProcess(if (restartedExit == 0 && restartedPassed.size == 2 && restartedErrors.isEmpty()) 0 else 1)
}

private fun installClient(directories: ClientDirectories) =
    KtorHttp(UrlPolicy.PRODUCTION, HttpSettings()).use { http ->
        val platform = (Platforms.current() as? Outcome.Ok)?.value ?: failCheck("unsupported platform")
        when (val result = runBlocking { GameInstaller(http, directories, platform, RuntimePins.CURRENT).install(emptySet()) }) {
            is Outcome.Ok -> result.value
            is Outcome.Err -> failCheck(result.error.toString())
        }
    }

// Errors before [mark] come from starting the game offline, not from Rloaded.
private fun errors(
    log: String,
    mark: String,
): List<String> {
    val lines = log.lines()
    val from = lines.indexOfFirst { mark in it }
    if (from < 0) failCheck("the game's log does not say \"$mark\"")
    return lines.drop(from).filter { ERROR in it && OFFLINE_REALMS !in it }
}

// The games play offline: the title screen's check of Realms fails, and may come after a world
// the game opens at once.
private const val OFFLINE_REALMS = "Couldn't connect to realms"

// Where each game's own work starts in its log.
private val MARKS =
    mapOf("first" to "asks to add", "reopen" to "Installing the mods of Island", "restarted" to "Opening Plain again")

private const val PLAYER = "Tester"
private const val MODRINTH_PROPERTY = "rloaded.test.modrinth"
private const val ERROR = "/ERROR]"
private const val FIXED_PATHS = 4
private const val AGENT = 2
private const val RUNTIME = 3
