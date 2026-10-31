package io.github.calamech.rloaded.gametests

import io.github.calamech.rloaded.bootstrap.ExitCode
import io.github.calamech.rloaded.bootstrap.InstalledServer
import io.github.calamech.rloaded.bootstrap.ServerInstaller
import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.UrlPolicy
import io.github.calamech.rloaded.core.lockfile.Lockfile
import io.github.calamech.rloaded.core.mojang.Platforms
import io.github.calamech.rloaded.core.runtime.RuntimePins
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Checks what happens when loading an added mod fails in the running server after the staging
 * server accepted it: `rloaded add` typed into the console of a server in its own process, staging
 * on, for fails-in-running-server, a mod that fails as it starts only in that server's folder. The
 * server must stop with the restart exit code, its lockfile must not list the mod, and the server
 * started again, as the bootstrap does on that code, must run without it. The bootstrap's own loop
 * is checked by its unit tests. The project owner accepted the Minecraft EULA for the server
 * directory this check creates.
 *
 * Arguments: server cache, run directory, agent jar, runtime mod jar, then the jar to serve as
 * fails-in-running-server.
 */
@OptIn(ExperimentalPathApi::class)
public fun main(arguments: Array<String>) {
    val paths = arguments.map { Path.of(it) }
    val (cache, run, agent) = paths
    val runtime = paths[RUNTIME_ARGUMENT]
    val installed = installServer(cache)
    if (run.exists()) run.deleteRecursively()
    val processes = mutableListOf<Process>()
    Runtime.getRuntime().addShutdownHook(Thread { processes.forEach { it.destroyForcibly() } })
    val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(CHECK_TIMEOUT_MINUTES)

    // The check ends the process, which stops the fake Modrinth with it.
    val modrinth = FakeModrinth(listOf(FakeMod(PROJECT, VERSION, "Fails in running server", paths[SERVED_ARGUMENT])))
    val port = ServerSocket(0).use { it.localPort }
    val directory = run.resolve("server")
    prepareServer(directory, port)
    directory.resolve("fail-in-running-server").writeText("fail here\n")
    val properties = mapOf(MODRINTH_PROPERTY to modrinth.url)
    val log = directory.resolve("logs").resolve("latest.log")

    val first = startServer(installed, agent, listOf(runtime), directory, properties)
    processes += first
    waitForPort(port, first, deadline)
    waitUntilReady(log, first, deadline)
    first.outputWriter().run {
        write("rloaded add $PROJECT\n")
        flush()
    }
    if (!first.waitFor(STOP_MINUTES, TimeUnit.MINUTES)) failCheck("the server did not stop within $STOP_MINUTES minutes of the add")
    // The running server loads a mod only once the staging server accepted it.
    val failedLive = log.readText().lines().any { "loading the mods failed; the game restarts without the new mods" in it }
    val listed = listed(directory.resolve("rloaded").resolve("lockfile.json"))
    println(
        "Restart check: the server exited with code ${first.exitValue()}; it failed loading the mod: $failedLive; lockfile lists $listed",
    )
    if (first.exitValue() != ExitCode.RESTART || !failedLive) failCheck("the server did not ask for a restart after loading the mod")
    if (PROJECT in listed) failCheck("the lockfile lists the mod that failed")

    val second = startServer(installed, agent, listOf(runtime), directory, properties)
    processes += second
    waitForPort(port, second, deadline)
    waitUntilReady(log, second, deadline)
    stopServer(second)
    val loadedAgain = "fails-in-running-server" in log.readText()
    println("Restart check: the server started again; it mentions the mod: $loadedAgain")
    exitProcess(if (loadedAgain) 1 else 0)
}

private fun installServer(cache: Path): InstalledServer {
    val platform = (Platforms.current() as? Outcome.Ok)?.value ?: failCheck("unsupported platform")
    return KtorHttp(UrlPolicy.PRODUCTION, HttpSettings()).use { http ->
        when (val result = runBlocking { ServerInstaller(http, cache, platform, RuntimePins.CURRENT).install() }) {
            is Outcome.Ok -> result.value
            is Outcome.Err -> failCheck(result.error)
        }
    }
}

/**
 * Waits until the server says it is ready. The game opens its port before it loads its worlds, and
 * gives a console line read in between a command source without a world: the command then fails
 * with "An unexpected error occurred" (Minecraft 26.3, `DedicatedServer.initServer`).
 */
private fun waitUntilReady(
    log: Path,
    server: Process,
    deadline: Long,
) {
    while (!log.exists() || "Done (" !in log.readText()) {
        if (!server.isAlive || System.nanoTime() > deadline) failCheck("the server did not finish starting")
        Thread.sleep(POLL_MILLIS)
    }
}

private fun listed(lockfile: Path): List<String> =
    if (!lockfile.exists()) {
        emptyList()
    } else {
        when (val read = Lockfile.read(lockfile)) {
            is Outcome.Ok -> read.value.mods.map { it.entry.projectId }
            is Outcome.Err -> failCheck("the lockfile cannot be read: ${read.error}")
        }
    }

private const val PROJECT = "firsProject"
private const val VERSION = "firsVersion"
private const val MODRINTH_PROPERTY = "rloaded.test.modrinth"
private const val STOP_MINUTES = 5L
private const val POLL_MILLIS = 500L
private const val RUNTIME_ARGUMENT = 3
private const val SERVED_ARGUMENT = 4
