package io.github.calamech.rloaded.bootstrap

import io.github.calamech.rloaded.core.download.ArtifactStore
import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.UrlPolicy
import io.github.calamech.rloaded.core.mojang.Platforms
import io.github.calamech.rloaded.core.runtime.RuntimePins
import io.github.calamech.rloaded.modrinth.ModInstaller
import io.github.calamech.rloaded.modrinth.Modrinth
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Path
import java.time.Clock
import kotlin.system.exitProcess

/** Entry point of `rloaded-server.jar`, started in the server folder. */
public fun main(arguments: Array<String>) {
    val platform =
        when (val detected = Platforms.current()) {
            is Outcome.Ok -> {
                detected.value
            }

            is Outcome.Err -> {
                println("Rloaded does not run on ${detected.error.osName} ${detected.error.osArch}.")
                exitProcess(ExitCode.FAILED)
            }
        }
    val folder = ServerFolder(Path.of("").toAbsolutePath())
    val code =
        KtorHttp(UrlPolicy.PRODUCTION, HttpSettings()).use { http ->
            val installer = ServerInstaller(http, folder.data, platform, RuntimePins.CURRENT)
            val mods =
                LockfileMods(
                    ModInstaller(Modrinth(http), ArtifactStore(folder.data.resolve("cache/mods"), http)),
                    PendingRemovals(folder, Clock.systemUTC(), System.out),
                )
            val bootstrap =
                Bootstrap(folder, installer, mods, InheritedIoProcess, System.out)
            runBlocking { bootstrap.run(arguments.toList()) }
        }
    exitProcess(code)
}

/** Runs the server with the console of the bootstrap, so the admin types commands as usual. */
public object InheritedIoProcess : ServerProcess {
    override fun run(
        command: List<String>,
        directory: Path,
    ): Int =
        try {
            ProcessBuilder(command)
                .directory(directory.toFile())
                .inheritIO()
                .start()
                .waitFor()
        } catch (e: IOException) {
            println("Cannot start the server: ${e.message}")
            ExitCode.FAILED
        }
}
