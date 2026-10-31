package io.github.calamech.rloaded.gametests

import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.UrlPolicy
import io.github.calamech.rloaded.core.mojang.Platforms
import io.github.calamech.rloaded.core.runtime.RuntimePins
import io.github.calamech.rloaded.launcher.ClientDirectories
import io.github.calamech.rloaded.launcher.GameInstaller
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
 * Installs mods in an open singleplayer world from the game's Mods screen: starts a game whose
 * client game test clicks through the screen, against a fake Modrinth that serves content-basic
 * and mixin-field-init, and whose search still lists a project Modrinth removed. Fails unless the
 * game's test passed and no line of the game's log is an error from the first add on.
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
    val report = run.resolve("client-game-test-report.txt")
    val mods =
        listOf(
            FakeMod("cbProject", "cbVersion", "Content basic", served.single { "contentBasic" in it.fileName.toString() }),
            FakeMod("mfiProject", "mfiVersion", "Mixin field initializer", served.single { "mixinFieldInit" in it.fileName.toString() }),
        )
    // The check ends the process, which stops the fake Modrinth with it.
    val modrinth = FakeModrinth(mods, unlisted = listOf("removedProject"))
    val launcherData = ClientDirectories(run.resolve("launcher-data"))
    val launch = ClientLaunch(installed, directories, launcherData, paths[AGENT], paths[RUNTIME], clientMods)
    val properties =
        mapOf(
            "fabric.client.gametest" to "true",
            "rloaded.client-game-test.report" to report.toString(),
            MODRINTH_PROPERTY to modrinth.url,
        )
    val game = startClient(launch, run.resolve("game"), PLAYER, null, properties)
    Runtime.getRuntime().addShutdownHook(Thread { game.destroyForcibly() })
    if (!game.waitFor(CHECK_TIMEOUT_MINUTES, TimeUnit.MINUTES)) failCheck("the game did not finish within $CHECK_TIMEOUT_MINUTES minutes")
    val passed = if (report.exists()) report.readLines().filter { it.isNotBlank() } else emptyList()
    val errors =
        errorsFromFirstAdd(
            run
                .resolve("game")
                .resolve("logs")
                .resolve("latest.log")
                .readText(),
        )
    println("Singleplayer check: game exit code ${game.exitValue()}, passed: $passed, ${errors.size} error lines")
    errors.forEach { println("  $it") }
    exitProcess(if (game.exitValue() == 0 && passed.isNotEmpty() && errors.isEmpty()) 0 else 1)
}

private fun installClient(directories: ClientDirectories) =
    KtorHttp(UrlPolicy.PRODUCTION, HttpSettings()).use { http ->
        val platform = (Platforms.current() as? Outcome.Ok)?.value ?: failCheck("unsupported platform")
        when (val result = runBlocking { GameInstaller(http, directories, platform, RuntimePins.CURRENT).install(emptySet()) }) {
            is Outcome.Ok -> result.value
            is Outcome.Err -> failCheck(result.error.toString())
        }
    }

// Errors before the first add come from starting the game offline, not from adds.
private fun errorsFromFirstAdd(log: String): List<String> {
    val lines = log.lines()
    val from = lines.indexOfFirst { "to the open world" in it }
    if (from < 0) failCheck("the game's log does not show an add")
    return lines.drop(from).filter { ERROR in it }
}

private const val PLAYER = "Tester"
private const val MODRINTH_PROPERTY = "rloaded.test.modrinth"
private const val ERROR = "/ERROR]"
private const val FIXED_PATHS = 4
private const val AGENT = 2
private const val RUNTIME = 3
