package io.github.calamech.rloaded.admin.staging

import io.github.calamech.rloaded.core.runtime.ServerLaunch
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.flatMap
import io.github.calamech.rloaded.protocol.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

/** Tries mod jars before an add accepts them. */
public fun interface Staging {
    /**
     * Runs [jars] somewhere nobody plays, with the server's mods: those it started with and the
     * [installed] ones its lockfile lists now. Says why they failed, if they did.
     */
    public suspend fun tryOut(
        installed: List<Path>,
        jars: List<Path>,
    ): Outcome<Unit, StagingFailure>

    /** Staging for a server whose admin turned it off. */
    public companion object {
        /** Tries nothing: every jar passes. */
        public val OFF: Staging = Staging { _, _ -> Outcome.Ok(Unit) }
    }
}

/** Why mods failed on the staging server. The log of the run stays until the next one. */
public sealed interface StagingFailure {
    /** What went wrong, in words for the admin. */
    public val reason: String

    /** The staging server could not be set up or started. */
    public data class NotStarted(
        val problem: String,
    ) : StagingFailure {
        override val reason: String get() = "the staging server could not start: $problem"
    }

    /** The staging server ran longer than allowed, and was killed with its children. */
    public data class TimedOut(
        val timeout: Duration,
        /** Ids of the processes a forced kill did not stop. */
        val stillRunning: List<Long>,
        val log: Path,
    ) : StagingFailure {
        override val reason: String get() =
            "the staging server did not finish within ${timeout.toSeconds()} seconds" +
                (if (stillRunning.isEmpty()) "" else " and processes ${stillRunning.joinToString()} did not stop") +
                "; see $log"
    }

    /** The staging server stopped without a report it could be judged on. */
    public data class NoReport(
        val exitCode: Int,
        val problem: String,
        val log: Path,
    ) : StagingFailure {
        override val reason: String get() = "the staging server stopped with code $exitCode and no report ($problem); see $log"
    }

    /** The mods failed on the staging server. */
    public data class Failed(
        val report: StagingReport,
        val log: Path,
    ) : StagingFailure {
        override val reason: String get() = "it failed on the staging server: ${report.failures.joinToString("; ")}; see $log"
    }
}

/**
 * Starts a server the way [launch] describes, in [directory] with a fresh flat world, loads the
 * candidate jars into it once it runs, and judges them on the report it writes when it stops. The
 * directory is emptied at each run; the run's files stay there until the next one. [eula] is the
 * admin's own acceptance of the Minecraft EULA, copied for the staging server.
 */
public class StagingServer(
    private val launch: ServerLaunch,
    private val directory: Path,
    private val eula: Path,
    private val timeout: Duration,
) : Staging {
    private val report: Path get() = directory.resolve(REPORT_FILE)

    /** The game log of the last run. */
    public val log: Path get() = directory.resolve("logs").resolve("latest.log")

    override suspend fun tryOut(
        installed: List<Path>,
        jars: List<Path>,
    ): Outcome<Unit, StagingFailure> = runInterruptible(Dispatchers.IO) { run(jars, installed) }.map { }

    /**
     * Runs [jars] on a staging server started with the launch's mods and [installed], and returns
     * its report when they passed. Blocks until the run ends.
     */
    public fun run(
        jars: List<Path>,
        installed: List<Path> = emptyList(),
    ): Outcome<StagingReport, StagingFailure> = prepare().flatMap { start(jars, installed) }.flatMap { process -> judge(process) }

    @OptIn(ExperimentalPathApi::class)
    private fun prepare(): Outcome<Unit, StagingFailure> =
        try {
            if (directory.exists()) directory.deleteRecursively()
            Files.createDirectories(directory)
            Files.copy(eula, directory.resolve("eula.txt"))
            Files.writeString(directory.resolve("server.properties"), SERVER_PROPERTIES)
            Outcome.Ok(Unit)
        } catch (e: IOException) {
            Outcome.Err(StagingFailure.NotStarted("cannot prepare $directory: ${e.message}"))
        }

    private fun start(
        jars: List<Path>,
        installed: List<Path>,
    ): Outcome<Process, StagingFailure> {
        val properties =
            launch.properties - ServerLaunch.PROPERTY +
                mapOf(
                    StagingSettings.ENABLED_PROPERTY to "false",
                    CANDIDATES_PROPERTY to jars.joinToString(File.pathSeparator) { it.toAbsolutePath().toString() },
                    REPORT_PROPERTY to report.toAbsolutePath().toString(),
                )
        return try {
            val process =
                ProcessBuilder(launch.copy(mods = withInstalled(installed), properties = properties).command())
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(directory.resolve(CONSOLE_FILE).toFile())
                    .start()
            // The game reads commands from its input; it gets none.
            process.outputStream.close()
            Outcome.Ok(process)
        } catch (e: IOException) {
            Outcome.Err(StagingFailure.NotStarted(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun judge(process: Process): Outcome<StagingReport, StagingFailure> {
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            return Outcome.Err(StagingFailure.TimedOut(timeout, kill(process), log))
        }
        val exit = process.exitValue()
        if (!report.exists()) return Outcome.Err(StagingFailure.NoReport(exit, "it wrote none", log))
        return when (val read = StagingReport.read(report)) {
            is Outcome.Err -> {
                Outcome.Err(StagingFailure.NoReport(exit, read.error, log))
            }

            is Outcome.Ok -> {
                val judged = if (exit == 0) read.value else read.value.failing("the staging server exited with code $exit")
                if (judged.passed) Outcome.Ok(judged) else Outcome.Err(StagingFailure.Failed(judged, log))
            }
        }
    }

    // The mods the server started with stay first; a mod installed since then is added once.
    private fun withInstalled(installed: List<Path>): List<String> =
        (launch.mods.map { absolute(Path.of(it)) } + installed.map { absolute(it) }).distinct()

    private fun absolute(path: Path): String = path.toAbsolutePath().normalize().toString()

    // Returns the ids of the processes still running after a forced kill and a short wait.
    private fun kill(process: Process): List<Long> {
        val all = process.descendants().toList() + process.toHandle()
        all.forEach { it.destroyForcibly() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(KILL_SECONDS)
        all.forEach { handle ->
            handle.onExit().completeOnTimeout(handle, deadline - System.nanoTime(), TimeUnit.NANOSECONDS).join()
        }
        return all.filter { it.isAlive }.map { it.pid() }
    }

    /** The system properties a staging server reads. */
    public companion object {
        /** The candidate jars, joined with the path separator. */
        public const val CANDIDATES_PROPERTY: String = "rloaded.staging.candidates"

        /** Where the staging server writes its report. */
        public const val REPORT_PROPERTY: String = "rloaded.staging.report"

        private const val REPORT_FILE = "report.json"
        private const val CONSOLE_FILE = "console.log"
        private const val KILL_SECONDS = 10L

        // A flat world without structures, on the loopback address, that never pauses for lack of
        // players. The runtime mod does not open the game port of a staging server; the address and
        // port are stated so nothing depends on the game's defaults.
        private val SERVER_PROPERTIES =
            """
            level-type=minecraft\:flat
            generate-structures=false
            online-mode=false
            server-ip=127.0.0.1
            server-port=25565
            enable-query=false
            enable-rcon=false
            management-server-enabled=false
            pause-when-empty-seconds=0
            spawn-protection=0
            white-list=false

            """.trimIndent()
    }
}
