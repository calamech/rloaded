package io.github.calamech.rloaded.launcher

import io.github.calamech.rloaded.core.Outcome
import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.TestServer
import io.github.calamech.rloaded.core.mojang.Architecture
import io.github.calamech.rloaded.core.mojang.MojangEndpoints
import io.github.calamech.rloaded.core.mojang.OperatingSystem
import io.github.calamech.rloaded.core.mojang.Platform
import io.github.calamech.rloaded.core.runtime.FakeDistribution
import io.github.calamech.rloaded.core.runtime.RuntimePins
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LauncherTest {
    private val server = TestServer()
    private val http = KtorHttp(server.policy, HttpSettings())
    private val distribution = FakeDistribution(server)
    private val profile = (OfflineProfile.of("Dev") as Outcome.Ok).value

    @TempDir
    lateinit var root: Path

    /** Records what it was asked to start, and starts a short real process instead. */
    private class RecordingStarter : ProcessStarter {
        val started = mutableListOf<Triple<List<String>, Path, Path>>()

        override fun start(
            command: List<String>,
            directory: Path,
            log: Path,
        ): Outcome<Process, StartFailure> {
            started += Triple(command, directory, log)
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            return Outcome.Ok(ProcessBuilder(java, "-version").start())
        }
    }

    @AfterEach
    fun close() {
        http.close()
        server.close()
    }

    @Test
    fun `installs and starts the game for a server`() =
        runBlocking {
            val starter = RecordingStarter()

            val process = (launcher(starter).play(profile, ServerAddress("localhost")) as Outcome.Ok).value

            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            val (command, directory, log) = starter.started.single()
            val gameDirectory = ClientDirectories(root).profile("server-localhost-25565")
            assertEquals(gameDirectory, directory)
            assertEquals(gameDirectory.resolve("logs/launcher.log"), log)
            assertEquals(listOf("--quickPlayMultiplayer", "localhost"), command.takeLast(2))
            assertTrue("-javaagent:${root.resolve("agent.jar")}" in command)
        }

    @Test
    fun `opens the title screen without a server`() =
        runBlocking {
            val starter = RecordingStarter()

            launcher(starter).play(profile, server = null)

            val (command, directory, _) = starter.started.single()
            assertEquals(ClientDirectories(root).profile("default"), directory)
            assertTrue("--quickPlayMultiplayer" !in command)
        }

    @Test
    fun `reports an install that failed`() =
        runBlocking {
            server.serve("/manifest.json", ByteArray(0), status = 500)
            val starter = RecordingStarter()

            val result = launcher(starter).play(profile, server = null)

            assertTrue(result is Outcome.Err && result.error is LaunchFailure.Install)
            assertEquals(emptyList(), starter.started)
        }

    @Test
    fun `reports a process that could not start`() =
        runBlocking {
            val result = launcher { _, _, _ -> Outcome.Err(StartFailure("no such file")) }.play(profile, server = null)

            assertEquals(Outcome.Err(LaunchFailure.Start(StartFailure("no such file"))), result)
        }

    private fun launcher(starter: ProcessStarter): Launcher {
        val directories = ClientDirectories(root)
        val installer =
            GameInstaller(
                http,
                directories,
                Platform(OperatingSystem.LINUX, Architecture.X86_64),
                RuntimePins.parse(distribution.pins),
                endpoints = MojangEndpoints(distribution.manifestUrl, distribution.resourcesUrl),
            )
        return Launcher(installer, directories, root.resolve("agent.jar"), "0.1.0", starter)
    }
}
