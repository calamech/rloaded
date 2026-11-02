package io.github.calamech.rloaded.gametests

import io.github.calamech.rloaded.core.download.ArtifactStore
import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.UrlPolicy
import io.github.calamech.rloaded.core.mojang.Platforms
import io.github.calamech.rloaded.core.runtime.JetBrainsRuntimeInstaller
import io.github.calamech.rloaded.core.runtime.RuntimeOptions
import io.github.calamech.rloaded.core.runtime.RuntimePins
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.system.exitProcess

/**
 * Runs the redefinition probe on the pinned JetBrains Runtime with the agent, twice. With the
 * options every game and server gets, it must finish. Without the option that turns off type
 * profiles, it must still abort the JVM: the day the runtime no longer does, the option costs time
 * for nothing and this check says so (#114).
 *
 * Arguments: runtime cache directory, run directory, agent jar.
 */
@OptIn(ExperimentalPathApi::class)
public fun main(arguments: Array<String>) {
    val (cache, run, agent) = arguments.map { Path.of(it) }
    val platform = (Platforms.current() as? Outcome.Ok)?.value ?: failCheck("unsupported platform")
    val pins = RuntimePins.CURRENT
    val java =
        KtorHttp(UrlPolicy.PRODUCTION, HttpSettings()).use { http ->
            val installer =
                JetBrainsRuntimeInstaller(
                    ArtifactStore(cache.resolve("cache/runtime-archives"), http),
                    cache.resolve("runtimes"),
                    platform.os,
                )
            when (val installed = runBlocking { installer.install(pins.jetBrainsRuntime(platform), pins.jetBrainsRuntimeVersion) }) {
                is Outcome.Ok -> installed.value
                is Outcome.Err -> failCheck("cannot install the Java runtime: ${installed.error}")
            }
        }
    if (run.exists()) run.deleteRecursively()
    run.createDirectories()

    val protected = probe(java, agent, run, RuntimeOptions.ALL)
    println("Redefinition check: with ${RuntimeOptions.ALL}, exit code ${protected.exit}")
    if (protected.exit != 0 || "Redefinition probe:" !in protected.output) {
        failCheck("the probe did not finish with the options games and servers get:\n${protected.output}")
    }
    val unprotected = probe(java, agent, run, RuntimeOptions.ALL - RuntimeOptions.NO_TYPE_PROFILE)
    println("Redefinition check: without ${RuntimeOptions.NO_TYPE_PROFILE}, exit code ${unprotected.exit}")
    if (CRASH !in unprotected.output) {
        failCheck(
            "the pinned runtime no longer aborts without ${RuntimeOptions.NO_TYPE_PROFILE}: " +
                "remove the option and record it in #114\n${unprotected.output}",
        )
    }
    exitProcess(0)
}

private class ProbeRun(
    val exit: Int,
    val output: String,
)

private fun probe(
    java: Path,
    agent: Path,
    run: Path,
    options: List<String>,
): ProbeRun {
    val command =
        listOf(java.toString()) + options +
            listOf(
                "-XX:ErrorFile=${run.resolve("hs_err_pid%p.log")}",
                "-XX:ReplayDataFile=${run.resolve("replay_pid%p.log")}",
                "-javaagent:$agent",
                "-cp",
                System.getProperty("java.class.path"),
                "io.github.calamech.rloaded.gametests.RedefinitionProbeKt",
            )
    val process =
        ProcessBuilder(command)
            .directory(run.toFile())
            .redirectErrorStream(true)
            .start()
    val output = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(PROBE_MINUTES, TimeUnit.MINUTES)) failCheck("the probe did not end within $PROBE_MINUTES minutes")
    return ProbeRun(process.exitValue(), output)
}

private const val CRASH = "old method not detected"
private const val PROBE_MINUTES = 2L
