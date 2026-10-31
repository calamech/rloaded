package io.github.calamech.rloaded.gametests

import io.github.calamech.rloaded.bootstrap.ServerCommand
import io.github.calamech.rloaded.bootstrap.ServerConfig
import io.github.calamech.rloaded.bootstrap.ServerInstaller
import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.UrlPolicy
import io.github.calamech.rloaded.core.mojang.Platforms
import io.github.calamech.rloaded.core.runtime.RuntimePins
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Installs the pinned server into a cache directory, starts a game test server in a fresh run
 * directory with the agent, the runtime mod and the test mods, and fails unless every game test
 * passed. The launch it describes for staging servers leaves the test mods out, like a real server.
 * The project owner accepted the Minecraft EULA for the run directory, which staging servers copy.
 *
 * Arguments: cache directory, run directory, report file, agent jar, runtime mod jar, the jar that
 * staging must refuse, the jars the tests load while the server runs (joined with the path
 * separator), then the test mod jars the server starts with.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
public fun main(arguments: Array<String>) {
    val paths = arguments.take(AGENT_ARGUMENT + 1).map { Path.of(it) }
    val cache = paths[0]
    val run = paths[1]
    val report = paths[2]
    val agent = paths[AGENT_ARGUMENT]
    val runtime = Path.of(arguments[RUNTIME_ARGUMENT])
    val stagingRefused = arguments[RUNTIME_ARGUMENT + 1]
    val hotAddMods = arguments[FIXED_ARGUMENTS - 1]
    val testMods = arguments.drop(FIXED_ARGUMENTS).map { Path.of(it) }
    val platform = (Platforms.current() as? Outcome.Ok)?.value ?: fail("unsupported platform")
    val installed =
        KtorHttp(UrlPolicy.PRODUCTION, HttpSettings()).use { http ->
            when (val result = runBlocking { ServerInstaller(http, cache, platform, RuntimePins.CURRENT).install() }) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> fail(result.error)
            }
        }
    if (run.exists()) run.deleteRecursively()
    run.createDirectories()
    run.resolve("eula.txt").writeText("eula=true\n")
    report.deleteIfExists()
    val production = ServerCommand.launch(installed, agent, listOf(runtime), MAX_MEMORY_MIB, ServerConfig().gameProperties())
    val described = describe(production, run)
    val testProperties =
        mapOf(
            "fabric-api.gametest" to "true",
            "fabric-api.gametest.report-file" to report.toString(),
            "rloaded.hot-add-mods" to hotAddMods,
            "rloaded.staging-refused-mod" to stagingRefused,
        )
    val gameTestLaunch =
        described.copy(
            mods = described.mods + testMods.map { it.toString() },
            properties =
                described.properties + testProperties,
        )
    val exit =
        ProcessBuilder(gameTestLaunch.command())
            .directory(run.toFile())
            .inheritIO()
            .start()
            .waitFor()
    if (!report.exists()) fail("the game test server exited with code $exit without a report")
    when (val parsed = GameTestReport.parse(report.readText())) {
        is Outcome.Err -> {
            fail(parsed.error)
        }

        is Outcome.Ok -> {
            val result = parsed.value
            println("Game tests: ${result.tests.size} run, ${result.failures.size} failed")
            result.failures.forEach { (name, message) -> println("  FAILED $name: $message") }
            exitProcess(if (exit == 0 && result.passed) 0 else 1)
        }
    }
}

private const val MAX_MEMORY_MIB = 2048
private const val AGENT_ARGUMENT = 3
private const val RUNTIME_ARGUMENT = 4
private const val FIXED_ARGUMENTS = 7

private fun fail(message: String): Nothing {
    println("Game tests could not run: $message")
    exitProcess(1)
}
