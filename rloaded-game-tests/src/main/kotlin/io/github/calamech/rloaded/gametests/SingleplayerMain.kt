package io.github.calamech.rloaded.gametests

import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.UrlPolicy
import io.github.calamech.rloaded.core.link.GameConnection
import io.github.calamech.rloaded.core.link.GameLink
import io.github.calamech.rloaded.core.link.LinkSettings
import io.github.calamech.rloaded.core.mojang.Platforms
import io.github.calamech.rloaded.core.runtime.RuntimePins
import io.github.calamech.rloaded.launcher.ClientDirectories
import io.github.calamech.rloaded.launcher.GameInstaller
import io.github.calamech.rloaded.protocol.AddProblem
import io.github.calamech.rloaded.protocol.LinkEvent
import io.github.calamech.rloaded.protocol.LinkRequest
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Adds mods to an open singleplayer world the way the launcher asks for them: plays the launcher's
 * end of the link with a game it starts, and serves content-basic and mixin-field-init from a fake
 * Modrinth. Asks for content-basic before a world is open, then while the world is open to the local
 * network, and expects both refused; asks for a project Modrinth does not have, refused too; then,
 * with the game paused, asks for content-basic and mixin-field-init and expects both added. The
 * game's test then checks the block, its entities and the world's lockfile. Fails unless every
 * answer is the expected one, the game's test passed, and no line of the game's log is an error
 * from the first add on.
 *
 * Arguments: client cache, run directory, agent jar, runtime mod jar, then the client mod jars and
 * the jars to serve, each list joined with the path separator.
 */
@OptIn(ExperimentalPathApi::class)
public fun main(arguments: Array<String>) {
    val paths = arguments.take(FIXED_PATHS).map { Path.of(it) }
    val (cache, run) = paths
    val agent = paths[AGENT_ARGUMENT]
    val runtime = paths[RUNTIME_MOD_ARGUMENT]
    val (clientMods, served) = arguments.drop(FIXED_PATHS).map { list -> list.split(File.pathSeparator).map { Path.of(it) } }
    val directories = ClientDirectories(cache)
    val installed = installClient(directories)
    if (run.exists()) run.deleteRecursively()
    run.createDirectories()
    val report = run.resolve("report.txt")
    val control = run.resolve("control.txt")
    val testReport = run.resolve("client-game-test-report.txt")
    val mods =
        listOf(
            FakeMod("cbProject", "cbVersion", "Content basic", served.single { "contentBasic" in it.fileName.toString() }),
            FakeMod("mfiProject", "mfiVersion", "Mixin field initializer", served.single { "mixinFieldInit" in it.fileName.toString() }),
        )
    // The check ends the process, which stops the fake Modrinth and the link with it.
    val modrinth = FakeModrinth(mods)
    val link = (GameLink.open(LINK_SETTINGS) as? Outcome.Ok)?.value ?: failCheck("the launcher's end of the link cannot open")
    val launcherData = ClientDirectories(run.resolve("launcher-data"))
    val launch = ClientLaunch(installed, directories, launcherData, agent, runtime, clientMods, link.environment)
    val properties =
        mapOf(
            "fabric.client.gametest" to "true",
            "rloaded.client-game-test.report" to testReport.toString(),
            "rloaded.singleplayer-test.report" to report.toString(),
            "rloaded.singleplayer-test.control" to control.toString(),
            MODRINTH_PROPERTY to modrinth.url,
        )
    val game = startClient(launch, run.resolve("game"), PLAYER, null, properties)
    Runtime.getRuntime().addShutdownHook(Thread { game.destroyForcibly() })
    val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(CHECK_TIMEOUT_MINUTES)
    val launcher = (link.accept() as? Outcome.Ok)?.value ?: failCheck("the game did not connect to the launcher")

    val expect = { request: LinkRequest.Add, expected: (LinkEvent) -> Boolean -> expect(launcher, request, expected, deadline) }
    expect(LinkRequest.Add("before-world", "cbProject", null), refused(AddProblem.NO_WORLD))
    allow(control, "open")
    waitForStep(report, "shared", game, deadline)
    expect(LinkRequest.Add("shared", "cbProject", null), refused(AddProblem.SHARED_WORLD))
    allow(control, "unshare")
    waitForStep(report, "paused", game, deadline)
    expect(LinkRequest.Add("unknown", "noSuchProject", null), refused(AddProblem.NOT_FOUND))
    expect(LinkRequest.Add("content-basic", "cbProject", null), added(mods[0]))
    expect(LinkRequest.Add("mixin-field-init", "mfiProject", null), added(mods[1]))
    allow(control, "added")

    if (!game.waitFor(maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) failCheck("the game did not finish in time")
    val passed = report.exists() && "passed" in report.readLines()
    val errors =
        errorsFromFirstAdd(
            run
                .resolve("game")
                .resolve("logs")
                .resolve("latest.log")
                .readText(),
        )
    println("Singleplayer check: game exit code ${game.exitValue()}, test passed: $passed, ${errors.size} error lines")
    errors.forEach { println("  $it") }
    exitProcess(if (game.exitValue() == 0 && passed && errors.isEmpty()) 0 else 1)
}

private fun installClient(directories: ClientDirectories) =
    KtorHttp(UrlPolicy.PRODUCTION, HttpSettings()).use { http ->
        val platform = (Platforms.current() as? Outcome.Ok)?.value ?: failCheck("unsupported platform")
        when (val result = runBlocking { GameInstaller(http, directories, platform, RuntimePins.CURRENT).install(emptySet()) }) {
            is Outcome.Ok -> result.value
            is Outcome.Err -> failCheck(result.error.toString())
        }
    }

// Sends [request] and fails the check unless the game's answer is accepted by [expected].
private fun expect(
    launcher: GameConnection,
    request: LinkRequest.Add,
    expected: (LinkEvent) -> Boolean,
    deadline: Long,
) {
    if (launcher.send(request) is Outcome.Err) failCheck("the game did not get ${request.requestId}")
    val answer = CompletableFuture.supplyAsync { launcher.receive() }
    val received =
        try {
            answer.get(maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            failCheck("no answer to ${request.requestId} within $CHECK_TIMEOUT_MINUTES minutes")
        }
    val event =
        when (received) {
            is Outcome.Ok -> received.value
            is Outcome.Err -> failCheck("no answer to ${request.requestId}: ${received.error}")
        }
    println("Singleplayer check: ${request.requestId} -> $event")
    if (event.requestId != request.requestId || !expected(event)) failCheck("unexpected answer to ${request.requestId}: $event")
}

// Lets the game's test go on to [step].
private fun allow(
    control: Path,
    step: String,
) {
    control.writeText("$step\n", options = arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND))
}

private fun refused(problem: AddProblem): (LinkEvent) -> Boolean = { it is LinkEvent.Refused && it.problem == problem }

private fun added(mod: FakeMod): (LinkEvent) -> Boolean = { it is LinkEvent.Added && it.files == listOf(mod.jar.fileName.toString()) }

private fun waitForStep(
    report: Path,
    step: String,
    game: Process,
    deadline: Long,
) {
    while (!report.exists() || step !in report.readLines()) {
        if (!game.isAlive) failCheck("the game exited with code ${game.exitValue()} before \"$step\"")
        if (System.nanoTime() > deadline) failCheck("the game did not reach \"$step\" within $CHECK_TIMEOUT_MINUTES minutes")
        Thread.sleep(POLL_MILLIS)
    }
}

// Errors before the first add come from starting the game offline, not from adds.
private fun errorsFromFirstAdd(log: String): List<String> {
    val lines = log.lines()
    val from = lines.indexOfFirst { "The launcher asks to add" in it }
    if (from < 0) failCheck("the game's log does not show an add")
    return lines.drop(from).filter { ERROR in it }
}

private const val HELLO_SECONDS = 10L
private val LINK_SETTINGS = LinkSettings(Duration.ofMinutes(CHECK_TIMEOUT_MINUTES), Duration.ofSeconds(HELLO_SECONDS))
private const val PLAYER = "Tester"
private const val MODRINTH_PROPERTY = "rloaded.test.modrinth"
private const val ERROR = "/ERROR]"
private const val POLL_MILLIS = 500L
private const val FIXED_PATHS = 4
private const val AGENT_ARGUMENT = 2
private const val RUNTIME_MOD_ARGUMENT = 3
