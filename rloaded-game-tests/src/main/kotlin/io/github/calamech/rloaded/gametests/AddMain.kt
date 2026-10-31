package io.github.calamech.rloaded.gametests

import io.github.calamech.rloaded.launcher.ClientDirectories
import java.io.BufferedWriter
import java.io.File
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Adds mods while players are connected, end to end: a server in its own process, two clients that
 * already trust it and stay in the world, and `rloaded add` typed into the server console for
 * content-basic, mixin-field-init, then keybind-and-tab, served by a fake Modrinth. Before the adds the console
 * summons an armor stand; between them it gives each player content-basic's block. Fails unless both
 * players got each mod without leaving their connection, the server says every player got them each
 * time, every entity of the server has mixin-field-init's field, and no log line is an error from
 * the first add on. The project owner accepted the Minecraft EULA for the server directory this
 * check creates.
 *
 * Arguments: client cache, server cache, run directory, agent jar, runtime mod jar, then the client
 * mod jars and the jars to serve as content-basic, mixin-field-init and keybind-and-tab, each list
 * joined with the path separator.
 */
public fun main(arguments: Array<String>) {
    val (served) = arguments.drop(FIXED_PATHS + 1).map { list -> list.split(File.pathSeparator).map { Path.of(it) } }
    val mods =
        listOf(
            FakeMod("cbProject", "cbVersion", "Content basic", served.single { "contentBasic" in it.fileName.toString() }),
            FakeMod("mfiProject", "mfiVersion", "Mixin field initializer", served.single { "mixinFieldInit" in it.fileName.toString() }),
            FakeMod(
                "kbtProject",
                "kbtVersion",
                "Key mapping and creative tab",
                served.single { "keybindAndTab" in it.fileName.toString() },
            ),
        )
    val passed =
        runAddCheck(
            arguments,
            AddPlan(mods, emptyMap(), ::steps, listOf("fieldinit")) { serverLog ->
                val fieldInit = serverLog.lines().lastOrNull { "field-init:" in it }.orEmpty()
                println("Add check: ${fieldInit.substringAfter("field-init: ")}")
                FIELD_INIT_PASSED.containsMatchIn(fieldInit)
            },
        )
    exitProcess(if (passed) 0 else 1)
}

/**
 * What an add check does: the [mods] the fake Modrinth serves, the [serverProperties] the server
 * starts with, the [steps] both players must reach with the console commands that lead there, the
 * commands typed once all passed ([finally]), and what the server's log must show ([serverChecks]).
 */
internal class AddPlan(
    val mods: List<FakeMod>,
    val serverProperties: Map<String, String>,
    val steps: (List<AddPlayer>) -> List<Pair<String, List<String>>>,
    val finally: List<String>,
    val serverChecks: (String) -> Boolean,
)

/**
 * Runs an add check: installs the games, starts the server and the fake Modrinth, two trusted
 * clients, then for each step types its console commands and waits for both players to report it.
 * Returns whether the server completed one rollout per mod, no log has an error line from the first
 * add on, and the plan's checks accept the server's log.
 */
@OptIn(ExperimentalPathApi::class)
internal fun runAddCheck(
    arguments: Array<String>,
    plan: AddPlan,
): Boolean {
    val paths = arguments.take(FIXED_PATHS).map { Path.of(it) }
    val (clientCache, serverCache, run) = paths
    val agent = paths[AGENT_ARGUMENT]
    val runtime = paths[RUNTIME_MOD_ARGUMENT]
    val clientMods = arguments[FIXED_PATHS].split(File.pathSeparator).map { Path.of(it) }
    val directories = ClientDirectories(clientCache)
    val (server, client) = installServerAndClient(serverCache, directories, emptySet())
    if (run.exists()) run.deleteRecursively()
    val processes = mutableListOf<Process>()
    Runtime.getRuntime().addShutdownHook(Thread { processes.forEach { it.destroyForcibly() } })
    val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(CHECK_TIMEOUT_MINUTES)

    // The check ends the process, which stops the fake Modrinth with it.
    val modrinth = FakeModrinth(plan.mods)
    val port = ServerSocket(0).use { it.localPort }
    val serverDirectory = run.resolve("server")
    prepareServer(serverDirectory, port)
    val done = run.resolve("done")
    val players = listOf("Alice", "Bob").map { name -> AddPlayer(name, run.resolve(name.lowercase())) }
    players.forEach { trustServer(serverDirectory, it.launcherData, "server") }
    val serverProcess =
        startServer(server, agent, listOf(runtime), serverDirectory, plan.serverProperties + (MODRINTH_PROPERTY to modrinth.url))
    processes += serverProcess
    waitForPort(port, serverProcess, deadline)
    val console = serverProcess.outputWriter()

    val steps = plan.steps(players)
    val clients =
        players.associate { player ->
            val launch = ClientLaunch(client, directories, player.launcherData, agent, runtime, clientMods)
            val properties = player.properties(port, done, steps.drop(1).map { it.first }) + (MODRINTH_PROPERTY to modrinth.url)
            player.name to startClient(launch, player.directory.resolve("game"), player.name, null, properties)
        }
    processes += clients.values
    for ((step, commands) in steps) {
        type(console, commands)
        waitForStep(players, step, clients, serverProcess, deadline)
    }
    type(console, plan.finally)
    done.writeText("done\n")
    waitForClients(clients, serverProcess, deadline) { true }
    stopServer(serverProcess)

    val serverLog = serverDirectory.resolve("logs").resolve("latest.log").readText()
    val rollouts = serverLog.lines().count { "Every player has the new mods" in it }
    val errors = errorsFromFirstAdd(serverLog, players)
    println("Add check: $rollouts rollouts; ${errors.size} error lines")
    errors.forEach { println("  $it") }
    return rollouts == plan.mods.size && errors.isEmpty() && plan.serverChecks(serverLog)
}

private fun type(
    console: BufferedWriter,
    commands: List<String>,
) {
    commands.forEach { console.write("$it\n") }
    console.flush()
}

// What each player reaches, and the console commands that lead there.
private fun steps(players: List<AddPlayer>): List<Pair<String, List<String>>> =
    listOf(
        IN_WORLD to emptyList(),
        // An entity from before the adds, which the server must rebuild for mixin-field-init.
        "content-basic" to listOf("summon minecraft:armor_stand 0 -60 0", "rloaded add cbProject"),
        // The server makes the new item for players: it needs the item's components.
        "content-basic-item" to players.map { "give ${it.name} content-basic:basic_block" },
        "mixin-field-init" to listOf("rloaded add mfiProject"),
        "keybind-and-tab" to listOf("rloaded add kbtProject"),
    )

// Errors before the first add come from starting the games offline, not from adds.
private fun errorsFromFirstAdd(
    serverLog: String,
    players: List<AddPlayer>,
): List<String> {
    val logs = listOf(serverLog to "System chat: Adding ") + players.map { it.log().readText() to "In the world, before any add" }
    return logs.flatMap { (log, mark) ->
        val lines = log.lines()
        val from = lines.indexOfFirst { mark in it }
        if (from < 0) failCheck("a log does not say \"$mark\"")
        lines.drop(from).filter { ERROR in it }
    }
}

// Every player reaches [step], or the check fails: a client that exits, a server that stops, or time.
private fun waitForStep(
    players: List<AddPlayer>,
    step: String,
    clients: Map<String, Process>,
    server: Process,
    deadline: Long,
) {
    while (!players.all { player ->
            player.report.exists() &&
                player.report
                    .readText()
                    .lines()
                    .any { it == step || it.startsWith("$step;") }
        }
    ) {
        clients.filterValues { !it.isAlive }.forEach { (name, process) ->
            failCheck("$name exited with code ${process.exitValue()} before \"$step\"")
        }
        if (!server.isAlive) failCheck("the server exited with code ${server.exitValue()} before \"$step\"")
        if (System.nanoTime() > deadline) failCheck("the players did not reach \"$step\" within $CHECK_TIMEOUT_MINUTES minutes")
        Thread.sleep(POLL_MILLIS)
    }
    println("Add check: every player reached \"$step\"")
}

/** A player of an add check, with its own game and launcher data. */
internal class AddPlayer(
    val name: String,
    val directory: Path,
) {
    val launcherData = ClientDirectories(directory.resolve("launcher-data"))
    val report: Path = directory.resolve("report.txt")

    init {
        directory.createDirectories()
    }

    fun log(): Path = directory.resolve("game").resolve("logs").resolve("latest.log")

    fun properties(
        port: Int,
        done: Path,
        steps: List<String>,
    ): Map<String, String> =
        mapOf(
            "fabric.client.gametest" to "true",
            "rloaded.add-test.server" to "$LOOPBACK:$port",
            "rloaded.add-test.report" to report.toString(),
            "rloaded.add-test.done" to done.toString(),
            "rloaded.add-test.steps" to steps.joinToString(","),
        )
}

// The players and the armor stand at least, every one with the field.
private val FIELD_INIT_PASSED = Regex("""field-init: ([3-9]|\d{2,}) entities, 0 without the field""")
private const val IN_WORLD = "in the world"
private const val MODRINTH_PROPERTY = "rloaded.test.modrinth"
private const val ERROR = "/ERROR]"
private const val POLL_MILLIS = 500L
private const val FIXED_PATHS = 5
private const val AGENT_ARGUMENT = 3
private const val RUNTIME_MOD_ARGUMENT = 4
