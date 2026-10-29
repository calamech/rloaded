package io.github.calamech.rloaded.bootstrap

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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BootstrapTest {
    private val server = TestServer()
    private val http = KtorHttp(server.policy, HttpSettings())
    private val distribution = FakeDistribution(server)
    private val output = ByteArrayOutputStream()

    @TempDir
    lateinit var root: Path

    private val folder get() = ServerFolder(root)

    /** A server process that records each command and exits with the scripted codes. */
    private class ScriptedProcess(
        vararg codes: Int,
    ) : ServerProcess {
        private val remaining = codes.toMutableList()
        val commands = mutableListOf<List<String>>()

        override fun run(
            command: List<String>,
            directory: Path,
        ): Int {
            commands += command
            return if (remaining.isEmpty()) ExitCode.OK else remaining.removeAt(0)
        }
    }

    @AfterEach
    fun close() {
        http.close()
        server.close()
    }

    @Test
    fun `sets up a new folder without accepting the EULA`() =
        runBlocking {
            val process = ScriptedProcess()

            assertEquals(ExitCode.OK, bootstrap(process).run(emptyList()))

            assertEquals(ServerConfig.TEMPLATE, folder.config.readText())
            assertTrue("eula=false" in folder.eula.readText())
            assertTrue("set eula=true in eula.txt if you accept it" in printed())
            assertEquals(emptyList(), process.commands)
            assertEquals(0, server.hits("/manifest.json"))
        }

    @Test
    fun `refuses unknown arguments`() =
        runBlocking {
            assertEquals(ExitCode.USAGE, bootstrap(ScriptedProcess()).run(listOf("--online")))
            assertTrue("Usage: java -jar rloaded-server.jar [--dev]" in printed())
        }

    @Test
    fun `refuses to start before the EULA is accepted`() =
        runBlocking {
            folder.initialize()
            val process = ScriptedProcess()

            assertEquals(ExitCode.FAILED, bootstrap(process).run(emptyList()))

            assertTrue("The Minecraft EULA is not accepted" in printed())
            assertEquals(emptyList(), process.commands)
        }

    @Test
    fun `refuses a settings file with an unknown key`() =
        runBlocking {
            folder.initialize()
            acceptEula()
            folder.config.writeText("[server]\nnmae = \"typo\"\n")

            assertEquals(ExitCode.FAILED, bootstrap(ScriptedProcess()).run(emptyList()))
            assertTrue("rloaded-server.toml is invalid" in printed())
        }

    @Test
    fun `installs the runtime and starts Fabric's server with the agent`() =
        runBlocking {
            folder.initialize()
            acceptEula()
            val process = ScriptedProcess()

            assertEquals(ExitCode.OK, bootstrap(process).run(emptyList()))

            val command = process.commands.single()
            val agent = Path.of(command.single { it.startsWith("-javaagent:") }.removePrefix("-javaagent:"))
            val classpath = command[command.indexOf("-cp") + 1].split(File.pathSeparator).map { Path.of(it).readText() }
            assertTrue(command.first().endsWith("jbr/Contents/Home/bin/java"))
            assertEquals(listOf("-Xmx4096M", "-XX:+AllowEnhancedClassRedefinition"), command.subList(1, 3))
            assertEquals(
                "io.github.calamech.rloaded.agent.RloadedAgent",
                JarFile(agent.toFile()).use {
                    it.manifest.mainAttributes.getValue("Premain-Class")
                },
            )
            assertEquals(listOf("loader", "server"), classpath)
            assertEquals(listOf(RuntimePins.FABRIC_SERVER_MAIN_CLASS, "nogui"), command.takeLast(2))
        }

    @Test
    fun `starts the server again when it asks for a restart`() =
        runBlocking {
            folder.initialize()
            acceptEula()
            val process = ScriptedProcess(ExitCode.RESTART, ExitCode.RESTART, ExitCode.OK)

            assertEquals(ExitCode.OK, bootstrap(process).run(emptyList()))
            assertEquals(3, process.commands.size)
        }

    @Test
    fun `stops after too many restarts in a row`() =
        runBlocking {
            folder.initialize()
            acceptEula()
            val process = ScriptedProcess(*IntArray(10) { ExitCode.RESTART })

            assertEquals(ExitCode.FAILED, Bootstrap(folder, installer(), process, PrintStream(output), maxRestarts = 3).run(emptyList()))
            assertEquals(4, process.commands.size)
            assertTrue("asked for 3 restarts in a row" in printed())
        }

    @Test
    fun `turns off account checks on a local address in dev mode`() =
        runBlocking {
            folder.initialize()
            acceptEula()

            assertEquals(ExitCode.OK, bootstrap(ScriptedProcess()).run(listOf("--dev")))

            val properties = folder.serverProperties.readText()
            assertTrue("online-mode=false" in properties)
            assertTrue("server-ip=127.0.0.1" in properties)
        }

    @Test
    fun `refuses dev mode on a public address`() =
        runBlocking {
            folder.initialize()
            acceptEula()
            folder.serverProperties.writeText("server-ip=8.8.8.8\nonline-mode=true\n")
            val process = ScriptedProcess()

            assertEquals(ExitCode.FAILED, bootstrap(process).run(listOf("--dev")))

            assertTrue("not 8.8.8.8" in printed())
            assertTrue("online-mode=true" in folder.serverProperties.readText())
            assertEquals(emptyList(), process.commands)
        }

    @Test
    fun `keeps a private address in dev mode`() =
        runBlocking {
            folder.initialize()
            acceptEula()
            folder.serverProperties.writeText("server-ip=192.168.1.20\n")

            assertEquals(ExitCode.OK, bootstrap(ScriptedProcess()).run(listOf("--dev")))
            assertTrue("server-ip=192.168.1.20" in folder.serverProperties.readText())
        }

    @Test
    fun `reports a failed install`() =
        runBlocking {
            folder.initialize()
            acceptEula()
            server.serve("/client.jar", ByteArray(0), status = 404)
            server.serve("/server.jar", "tampered".toByteArray())

            assertEquals(ExitCode.FAILED, bootstrap(ScriptedProcess()).run(emptyList()))
            assertTrue("downloads failed" in printed())
            assertFalse(folder.data.resolve("agent/rloaded-agent.jar").exists())
        }

    private fun bootstrap(process: ServerProcess) = Bootstrap(folder, installer(), process, PrintStream(output))

    private fun installer() =
        ServerInstaller(
            http,
            folder.data,
            Platform(OperatingSystem.MACOS, Architecture.ARM64),
            RuntimePins.parse(distribution.pins),
            MojangEndpoints(distribution.manifestUrl, distribution.resourcesUrl),
        )

    private fun acceptEula() = folder.eula.writeText("eula=true\n")

    private fun printed(): String = output.toString(Charsets.UTF_8)
}
