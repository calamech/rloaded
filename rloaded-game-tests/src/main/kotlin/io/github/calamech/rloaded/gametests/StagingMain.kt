package io.github.calamech.rloaded.gametests

import io.github.calamech.rloaded.admin.staging.StagingServer
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
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Tries content-basic and mixin-loaded-class on a staging server started like a real server's,
 * while the game port it would otherwise open is taken, and fails unless they pass with their block
 * placed and their item dropped. The project owner accepted the Minecraft EULA for the server
 * directory this check creates.
 *
 * Arguments: server cache, run directory, agent jar, runtime mod jar, then the candidate jars joined
 * with the path separator.
 */
@OptIn(ExperimentalPathApi::class)
public fun main(arguments: Array<String>) {
    val paths = arguments.take(FIXED_ARGUMENTS).map { Path.of(it) }
    val (cache, run, agent) = paths
    val runtime = paths[RUNTIME_ARGUMENT]
    val candidates = arguments[FIXED_ARGUMENTS].split(File.pathSeparator).map { Path.of(it) }
    val platform = (Platforms.current() as? Outcome.Ok)?.value ?: failCheck("unsupported platform")
    val installed =
        KtorHttp(UrlPolicy.PRODUCTION, HttpSettings()).use { http ->
            when (val result = runBlocking { ServerInstaller(http, cache, platform, RuntimePins.CURRENT).install() }) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> failCheck(result.error)
            }
        }
    if (run.exists()) run.deleteRecursively()
    run.createDirectories()
    val eula = run.resolve("eula.txt")
    eula.writeText("eula=true\n")
    val launch = ServerCommand.launch(installed, agent, listOf(runtime), MEMORY_MIB, ServerConfig().gameProperties())
    val staging = StagingServer(launch, run.resolve("staging"), eula, Duration.ofSeconds(TIMEOUT_SECONDS))

    val report =
        occupyingGamePort {
            when (val result = staging.run(candidates)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> failCheck(result.error.reason)
            }
        }

    println("Staging: $report")
    val expected = listOf("content-basic", "mixin-loaded-class")
    if (report.mods.sorted() != expected) failCheck("the staging server loaded ${report.mods}, not $expected")
    if (report.blocksPlaced != 1 || report.itemsDropped != 1) failCheck("the new block and item were not both used: $report")
    if (report.ticks < MIN_TICKS) failCheck("the staging server ticked ${report.ticks} times only")
    exitProcess(0)
}

// A staging server must not open the game port: the real server it runs next to often holds it.
// Holding it here makes a staging server that tries to open it fail to start.
private fun <T> occupyingGamePort(action: () -> T): T {
    val holder =
        try {
            ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), GAME_PORT)) }
        } catch (_: IOException) {
            // Something else holds it already, which serves the check as well.
            null
        }
    return try {
        action()
    } finally {
        holder?.close()
    }
}

private const val RUNTIME_ARGUMENT = 3
private const val FIXED_ARGUMENTS = 4
private const val MEMORY_MIB = 2048
private const val TIMEOUT_SECONDS = 180L
private const val GAME_PORT = 25565
private const val MIN_TICKS = 400
