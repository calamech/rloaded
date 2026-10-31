package io.github.calamech.rloaded.admin.staging

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Stands in for Fabric's server in [StagingServerTest]: started by [StagingServer] with the
 * staging properties, it behaves as `rloaded.test.fake-staging` says and reports what it saw.
 */
object FakeStagingServer {
    const val BEHAVIOR_PROPERTY = "rloaded.test.fake-staging"
    private const val SILENT_EXIT = 3
    private const val HANG_MILLIS = 120_000L

    @JvmStatic
    fun main(arguments: Array<String>) {
        if (System.getProperty(BEHAVIOR_PROPERTY) == "sleep") Thread.sleep(HANG_MILLIS)
        val report = Path.of(System.getProperty(StagingServer.REPORT_PROPERTY))
        val candidates = System.getProperty(StagingServer.CANDIDATES_PROPERTY).split(File.pathSeparator)
        val started = StagingReport.of(candidates.map { Path.of(it).fileName.toString() })
        when (System.getProperty(BEHAVIOR_PROPERTY)) {
            "pass" -> {
                write(report, unexpected(arguments).fold(started.exercised(1, 0, 1, 400)) { report, failure -> report.failing(failure) })
            }

            "fail" -> {
                write(report, started.failing("loading the mods threw java.lang.IllegalStateException: boom"))
            }

            "pass-then-crash" -> {
                write(report, started)
                exitProcess(1)
            }

            "silent" -> {
                exitProcess(SILENT_EXIT)
            }

            "hang" -> {
                // A child of its own, which a timeout must stop too.
                val java =
                    ProcessHandle
                        .current()
                        .info()
                        .command()
                        .orElseThrow()
                val classpath = System.getProperty("java.class.path")
                val child = ProcessBuilder(java, "-cp", classpath, "-D$BEHAVIOR_PROPERTY=sleep", javaClass.name).start()
                Files.writeString(report.resolveSibling("child.pid"), child.pid().toString())
                Thread.sleep(HANG_MILLIS)
            }
        }
    }

    // What a staging server must find: the admin's EULA, a flat world that never pauses, staging off,
    // no launch description to stage from, and the game's usual arguments.
    private fun unexpected(arguments: Array<String>): List<String> {
        val directory = Path.of("").toAbsolutePath()
        val properties = Files.readString(directory.resolve("server.properties"))
        return listOfNotNull(
            "no EULA".takeUnless { Files.readString(directory.resolve("eula.txt")) == "eula=true\n" },
            "not flat".takeUnless { "level-type=minecraft\\:flat" in properties },
            "pauses".takeUnless { "pause-when-empty-seconds=0" in properties },
            "staging on".takeUnless { System.getProperty(StagingSettings.ENABLED_PROPERTY) == "false" },
            "launch description passed on".takeUnless { System.getProperty("rloaded.server-launch") == null },
            "other setting lost".takeUnless { System.getProperty("rloaded.client-timeout-seconds") == "120" },
            "arguments ${arguments.toList()}".takeUnless { arguments.toList() == listOf("nogui") },
        )
    }

    private fun write(
        path: Path,
        report: StagingReport,
    ) {
        report.write(path)
    }
}

/** The agent the fake server starts with: the real one needs the game. */
object FakeAgent {
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun premain(arguments: String?) {
        // Nothing to set up.
    }
}
