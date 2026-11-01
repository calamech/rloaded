package io.github.calamech.rloaded.gametests

import io.github.calamech.rloaded.launcher.ClientDirectories
import io.github.calamech.rloaded.launcher.Relaunch
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Adds mods while players are connected, end to end: a server in its own process, two clients that
 * already trust it and stay in the world, and `rloaded add` typed into the server console for
 * content-basic and mixin-field-init, served by a fake Modrinth; then Alice, made an operator,
 * adds keybind-and-tab from her Mods screen, while Bob, who is not one, has no Mods button and is
 * refused when he asks anyway. Before the adds the console summons an armor stand; between them it
 * gives each player content-basic's block. Then late-item comes, the players leave, and join again
 * from the same game, which must restart first; the fresh game joins with both items in the
 * player's inventory. Fails unless both players got each mod without leaving their connection, the
 * server says every player got them each time, every entity of the server has mixin-field-init's
 * field, Alice's add and Bob's refusal are in the server's log, and no log line is an error from
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
            FakeMod("liProject", "liVersion", "Late item", served.single { "lateItem" in it.fileName.toString() }),
        )
    val passed =
        runAddCheck(
            arguments,
            AddPlan(mods, emptyMap(), ::steps, listOf("fieldinit"), askedAtJoin = true) { serverDirectory ->
                val serverLog = serverDirectory.resolve("logs").resolve("latest.log").readText()
                val fieldInit = serverLog.lines().lastOrNull { "field-init:" in it }.orEmpty()
                println("Add check: ${fieldInit.substringAfter("field-init: ")}")
                val fromScreen = "Alice asks to add kbtProject" in serverLog
                val refused = "Bob may not add mods; kbtProject was not added" in serverLog
                println("Add check: Alice added from her screen: $fromScreen; Bob refused: $refused")
                val audited = auditTrail(serverDirectory.resolve("rloaded").resolve("audit.log"))
                println("Add check: audit log: $audited")
                FIELD_INIT_PASSED.containsMatchIn(fieldInit) && fromScreen && refused &&
                    audited.groupBy { it.substringBefore(" ") } == AUDITED.groupBy { it.substringBefore(" ") }
            },
        )
    exitProcess(if (passed) 0 else 1)
}

/**
 * What an add check does: the [mods] the fake Modrinth serves, the [serverProperties] the server
 * starts with, the [steps] both players must reach with the console commands that lead there, the
 * commands typed once all passed ([finally]), whether the server has a code of conduct and a
 * resource pack that players answer when they join ([askedAtJoin]), and what the server's folder,
 * its log first, must show ([serverChecks]).
 */
internal class AddPlan(
    val mods: List<FakeMod>,
    val serverProperties: Map<String, String>,
    val steps: (List<AddPlayer>) -> List<Pair<String, List<String>>>,
    val finally: List<String>,
    val askedAtJoin: Boolean = false,
    val serverChecks: (serverDirectory: Path) -> Boolean,
)

/**
 * Runs an add check: installs the games, starts the server and the fake Modrinth, two trusted
 * clients, then for each step types its console commands and waits for both players to report it.
 * Returns whether the server completed one rollout per mod, no log has an error line from the first
 * add on, the server announced each player's join and leave once, and the plan's checks accept the
 * server's log.
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
    val pack = resourcePack()
    val modrinth = FakeModrinth(plan.mods, mapOf(PACK to pack))
    val port = ServerSocket(0).use { it.localPort }
    val serverDirectory = run.resolve("server")
    prepareServer(serverDirectory, port)
    if (plan.askedAtJoin) askAtJoin(serverDirectory, "${modrinth.url}/files/$PACK", pack)
    val done = run.resolve("done")
    val players = listOf("Alice", "Bob").map { name -> AddPlayer(name, run.resolve(name.lowercase())) }
    players.forEach { trustServer(serverDirectory, it.launcherData, "server") }
    val serverProcess =
        startServer(server, agent, listOf(runtime), serverDirectory, plan.serverProperties + (MODRINTH_PROPERTY to modrinth.url))
    processes += serverProcess
    waitForPort(port, serverProcess, deadline)
    val console = serverProcess.outputWriter()

    val steps = plan.steps(players)
    // The clients of the steps from [first] on, the first one already reached.
    val start = { first: Int ->
        players.associate { player ->
            val launch = ClientLaunch(client, directories, player.launcherData, agent, runtime, clientMods)
            val remaining = steps.drop(first + 1).map { it.first }
            val properties = player.properties(port, done, remaining) + (MODRINTH_PROPERTY to modrinth.url)
            player.name to startClient(launch, player.directory.resolve("game"), player.name, null, properties)
        }
    }
    var clients = start(0)
    processes += clients.values
    for ((index, entry) in steps.withIndex()) {
        type(console, entry.second)
        if (entry.first == REJOIN) {
            clients = startedAgain(clients, serverProcess, deadline) { start(index) }
            processes += clients.values
        } else {
            waitForStep(players, entry.first, clients, serverProcess, deadline)
        }
    }
    type(console, plan.finally)
    done.writeText("done\n")
    waitForClients(clients, serverProcess, deadline) { true }
    stopServer(serverProcess)
    val joins = 1 + steps.count { (step, _) -> step == REJOIN }
    return passed(plan, players, joins, serverDirectory)
}

private fun passed(
    plan: AddPlan,
    players: List<AddPlayer>,
    joins: Int,
    serverDirectory: Path,
): Boolean {
    val serverLog = serverDirectory.resolve("logs").resolve("latest.log").readText()
    val rollouts = serverLog.lines().count { "Every player has the new mods" in it }
    val errors = errorsFromFirstAdd(serverLog, players)
    // An add is no reason to tell the chat that players left and joined: each did so only for real.
    val announced =
        players.associate { player ->
            player.name to listOf("joined", "left").map { what -> serverLog.lines().count { "${player.name} $what the game" in it } }
        }
    println("Add check: $rollouts rollouts; ${errors.size} error lines; joins and leaves announced: $announced")
    errors.forEach { println("  $it") }
    // Answered once each, at join; a step fails as soon as a player is asked again.
    val answered =
        players.all { player ->
            player.report
                .readText()
                .lines()
                .first() == IN_WORLD + (if (plan.askedAtJoin) ANSWERED else "")
        }
    println("Add check: players answered what the server asks at join, and only then: $answered")
    return rollouts == plan.mods.size && errors.isEmpty() && announced.values.all { it == listOf(joins, joins) } && answered &&
        plan.serverChecks(serverDirectory)
}

// A code of conduct and an optional resource pack, which a player answers when it joins.
private fun askAtJoin(
    serverDirectory: Path,
    packUrl: String,
    pack: ByteArray,
) {
    serverDirectory
        .resolve("codeofconduct")
        .createDirectories()
        .resolve("en_us.txt")
        .writeText("Be kind to the other players.\n")
    val sha1 = MessageDigest.getInstance("SHA-1").digest(pack).toHexString()
    serverDirectory
        .resolve("server.properties")
        .appendText("enable-code-of-conduct=true\nresource-pack=$packUrl\nresource-pack-sha1=$sha1\nrequire-resource-pack=false\n")
}

// A resource pack for the game's format, with nothing in it.
private fun resourcePack(): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        zip.putNextEntry(ZipEntry("pack.mcmeta"))
        zip.write("""{"pack": {"description": "Add check", "min_format": 97, "max_format": 97}}""".toByteArray())
        zip.closeEntry()
    }
    return bytes.toByteArray()
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
        // Alice may add from her Mods screen from her next join on, which the add brings.
        "content-basic" to listOf("op Alice", "summon minecraft:armor_stand 0 -60 0", "rloaded add cbProject"),
        // The server makes the new item for players: it needs the item's components.
        "content-basic-item" to players.map { "give ${it.name} content-basic:basic_block" },
        "mixin-field-init" to listOf("rloaded add mfiProject"),
        "keybind-and-tab" to emptyList(),
        "no-access" to emptyList(),
        // An item added once the players' games have synchronized their items with the server.
        "late-item" to listOf("rloaded add liProject"),
        "late-item-given" to players.map { "give ${it.name} late-item:late_item" },
        // The players leave and join again in the same game, which loaded the mods while it played.
        REJOIN to emptyList(),
        "rejoined" to emptyList(),
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

// A game that loaded mods while it played must restart rather than join again: each must exit
// asking for it, and the check then starts them again, as the launcher does.
private fun startedAgain(
    clients: Map<String, Process>,
    server: Process,
    deadline: Long,
    start: () -> Map<String, Process>,
): Map<String, Process> {
    clients.forEach { (name, process) ->
        val exited = process.waitFor(maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
        if (!exited || !Relaunch.wanted(process.exitValue(), 0)) failCheck("$name did not ask to be started again before joining again")
    }
    if (!server.isAlive) failCheck("the server exited with code ${server.exitValue()} while the players restarted")
    println("Add check: every player restarted before joining again")
    return start()
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
// The console adds three mods, Alice one from her Mods screen, which she removes and keeps, and Bob,
// who is no operator, is refused. Each one's requests are in order; the players' requests may
// interleave.
private val AUDITED =
    listOf(
        "console cbProject: added",
        "console mfiProject: added",
        "Alice kbtProject: added",
        "Alice kbtProject: marked",
        "Alice kbtProject: kept",
        "Bob kbtProject: refused NOT_ALLOWED",
        "console liProject: added",
    )

private val FIELD_INIT_PASSED = Regex("""field-init: ([3-9]|\d{2,}) entities, 0 without the field""")
private const val IN_WORLD = "in the world"
private const val REJOIN = "rejoin"
private const val ANSWERED = "; answered the code of conduct and the resource pack"
private const val PACK = "add-check-pack.zip"
private const val MODRINTH_PROPERTY = "rloaded.test.modrinth"
private const val ERROR = "/ERROR]"
private const val POLL_MILLIS = 500L
private const val FIXED_PATHS = 5
private const val AGENT_ARGUMENT = 3
private const val RUNTIME_MOD_ARGUMENT = 4
