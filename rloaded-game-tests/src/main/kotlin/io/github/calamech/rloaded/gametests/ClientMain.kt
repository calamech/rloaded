package io.github.calamech.rloaded.gametests

import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.UrlPolicy
import io.github.calamech.rloaded.core.mojang.Platforms
import io.github.calamech.rloaded.core.runtime.RuntimePins
import io.github.calamech.rloaded.launcher.ClientCommand
import io.github.calamech.rloaded.launcher.ClientDirectories
import io.github.calamech.rloaded.launcher.GameInstaller
import io.github.calamech.rloaded.launcher.LaunchSettings
import io.github.calamech.rloaded.launcher.OfflineProfile
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Installs the pinned client into a cache directory, starts it in a fresh run directory with the
 * agent, the runtime mod and the test mods, lets Fabric run the client game tests, and fails unless
 * the client exited cleanly after recording at least one passed test.
 *
 * The tests start a dedicated server in the client's process, in the run directory, which needs an
 * accepted Minecraft EULA: the project owner accepted it for these test directories.
 *
 * Arguments: cache directory, run directory, report file, agent jar, runtime mod jar, the jars the
 * tests load while the client runs (joined with the path separator), then the test mod jars.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
public fun main(arguments: Array<String>) {
    val paths = arguments.take(RUNTIME_MOD_ARGUMENT + 1).map { Path.of(it) }
    val (cache, run, report) = paths
    val agent = paths[AGENT_ARGUMENT]
    val runtimeMod = paths[RUNTIME_MOD_ARGUMENT]
    val hotAddMods = arguments[HOT_ADD_ARGUMENT]
    val testMods = arguments.drop(HOT_ADD_ARGUMENT + 1).map { Path.of(it) }
    val platform = (Platforms.current() as? Outcome.Ok)?.value ?: fail("unsupported platform")
    val directories = ClientDirectories(cache)
    val installed =
        KtorHttp(UrlPolicy.PRODUCTION, HttpSettings()).use { http ->
            when (val result = runBlocking { GameInstaller(http, directories, platform, RuntimePins.CURRENT).install(emptySet()) }) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> fail(result.error.toString())
            }
        }
    if (run.exists()) run.deleteRecursively()
    run.createDirectories()
    run.resolve("eula.txt").writeText("eula=true\n")
    val profile = (OfflineProfile.of(PLAYER) as? Outcome.Ok)?.value ?: fail("invalid player name $PLAYER")
    val settings = LaunchSettings(profile, run, null, agent, runtimeMod, "client-game-test")
    val command =
        when (val built = ClientCommand.build(installed, settings, directories.assets, run.resolve("natives"))) {
            is Outcome.Ok -> withTestMods(built.value, testMods, clientGameTestProperties(report, hotAddMods))
            is Outcome.Err -> fail("unknown launch placeholder ${built.error.name}")
        }
    val client =
        ProcessBuilder(command)
            .directory(run.toFile())
            .inheritIO()
            .start()
    if (!client.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
        client.destroyForcibly().waitFor()
        fail("the client did not finish within $TIMEOUT_MINUTES minutes")
    }
    val exit = client.exitValue()
    val passed = if (report.exists()) report.readLines().filter { it.isNotBlank() } else emptyList()
    println("Client game tests: exit code $exit, ${passed.size} passed")
    passed.forEach { println("  passed: $it") }
    exitProcess(if (exit == 0 && passed.isNotEmpty()) 0 else 1)
}

private const val PLAYER = "Tester"
private const val TIMEOUT_MINUTES = 15L
private const val AGENT_ARGUMENT = 3
private const val RUNTIME_MOD_ARGUMENT = 4
private const val HOT_ADD_ARGUMENT = 5

private fun fail(message: String): Nothing {
    println("Client game tests could not run: $message")
    exitProcess(1)
}
