package io.github.calamech.rloaded.admin.staging

import io.github.calamech.rloaded.core.runtime.ServerLaunch
import io.github.calamech.rloaded.protocol.Outcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StagingServerTest {
    @TempDir
    lateinit var root: Path

    private val directory get() = root.resolve("rloaded/staging")
    private val eula get() = root.resolve("eula.txt")
    private val candidates get() = listOf(root.resolve("mods/first.jar"), root.resolve("mods/second.jar"))

    @Test
    fun `passes the mods the staging server reports no failure for`() {
        val report = ok(staging("pass").run(candidates))

        assertEquals(StagingReport(listOf("first.jar", "second.jar"), 1, 0, 1, 400, emptyList()), report)
        assertTrue(directory.resolve("console.log").exists(), "the console of the run is kept")
    }

    @Test
    fun `refuses the mods the staging server reports failures for, naming the log`() {
        val failure = err(staging("fail").run(candidates))

        assertIs<StagingFailure.Failed>(failure)
        assertEquals(
            "it failed on the staging server: loading the mods threw java.lang.IllegalStateException: boom; " +
                "see ${directory.resolve("logs").resolve("latest.log")}",
            failure.reason,
        )
    }

    @Test
    fun `refuses when the staging server stops without a report, or crashes after writing one`() {
        val silent = err(staging("silent").run(candidates))
        assertEquals(StagingFailure.NoReport(3, "it wrote none", directory.resolve("logs").resolve("latest.log")), silent)

        val crashed = err(staging("pass-then-crash").run(candidates))
        assertEquals(listOf("the staging server exited with code 1"), (crashed as StagingFailure.Failed).report.failures)
    }

    @Test
    fun `stops a staging server that runs too long, with its children`() {
        val started = System.nanoTime()

        val failure = err(staging("hang", timeout = Duration.ofSeconds(3)).run(candidates))

        assertEquals(StagingFailure.TimedOut(Duration.ofSeconds(3), emptyList(), directory.resolve("logs").resolve("latest.log")), failure)
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(30))
        val child = directory.resolve("child.pid").readText().toLong()
        assertFalse(ProcessHandle.of(child).map { it.isAlive }.orElse(false), "the child of the staging server still runs")
    }

    @Test
    fun `starts each run from an empty directory`() {
        directory.createDirectories()
        directory.resolve("world.dat").writeText("old world")

        ok(staging("pass").run(candidates))

        assertFalse(directory.resolve("world.dat").exists())
    }

    @Test
    fun `refuses to start without the admin's EULA`() {
        val staging = staging("pass")
        Files.delete(eula)

        assertIs<StagingFailure.NotStarted>(err(staging.run(candidates)))
    }

    private fun staging(
        behavior: String,
        timeout: Duration = Duration.ofSeconds(60),
    ): StagingServer {
        eula.writeText("eula=true\n")
        val java =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElseThrow()
        val launch =
            ServerLaunch(
                java = java,
                maxMemoryMiB = 64,
                agent = agentJar().toString(),
                classpath = System.getProperty("java.class.path").split(File.pathSeparator),
                mainClass = FakeStagingServer::class.java.name,
                mods = listOf(root.resolve("mods/fabric-api.jar").toString()),
                properties =
                    mapOf(
                        "rloaded.client-timeout-seconds" to "120",
                        StagingSettings.ENABLED_PROPERTY to "true",
                        ServerLaunch.PROPERTY to root.resolve("rloaded/server-launch.json").toString(),
                        FakeStagingServer.BEHAVIOR_PROPERTY to behavior,
                    ),
            )
        return StagingServer(launch, directory, eula, timeout)
    }

    private fun agentJar(): Path {
        val jar = root.resolve("agent.jar")
        if (jar.exists()) return jar
        val manifest =
            Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes[Attributes.Name("Premain-Class")] = FakeAgent::class.java.name
            }
        JarOutputStream(Files.newOutputStream(jar), manifest).close()
        return jar
    }

    private fun <T> ok(outcome: Outcome<T, StagingFailure>): T =
        when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw AssertionError("expected a pass, got ${outcome.error.reason}")
        }

    private fun <T> err(outcome: Outcome<T, StagingFailure>): StagingFailure =
        when (outcome) {
            is Outcome.Ok -> throw AssertionError("expected a failure, got ${outcome.value}")
            is Outcome.Err -> outcome.error
        }
}
