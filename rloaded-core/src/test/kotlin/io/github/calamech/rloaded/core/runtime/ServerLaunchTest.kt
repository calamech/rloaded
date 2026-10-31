package io.github.calamech.rloaded.core.runtime

import io.github.calamech.rloaded.protocol.Outcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerLaunchTest {
    @TempDir
    lateinit var directory: Path

    private val launch =
        ServerLaunch(
            java = "/jbr/bin/java",
            maxMemoryMiB = 2048,
            agent = "/data/agent.jar",
            classpath = listOf("/libs/loader.jar", "/libs/server.jar"),
            mainClass = RuntimePins.FABRIC_SERVER_MAIN_CLASS,
            mods = listOf("/mods/fabric-api.jar", "/data/rloaded-runtime.jar"),
            properties = mapOf("rloaded.client-timeout-seconds" to "120"),
        )

    @Test
    fun `starts Fabric's server on the runtime with the agent, the mods and the properties`() {
        assertEquals(
            listOf(
                "/jbr/bin/java",
                "-Xmx2048M",
                "-XX:+AllowEnhancedClassRedefinition",
                "-javaagent:/data/agent.jar",
                "-Dfabric.addMods=/mods/fabric-api.jar${File.pathSeparator}/data/rloaded-runtime.jar",
                "-Drloaded.client-timeout-seconds=120",
                "-cp",
                "/libs/loader.jar${File.pathSeparator}/libs/server.jar",
                RuntimePins.FABRIC_SERVER_MAIN_CLASS,
                "nogui",
            ),
            launch.command(),
        )
    }

    @Test
    fun `tells the server which file describes it`() {
        val file = directory.resolve("server-launch.json")

        val described = launch.describedBy(file)

        assertTrue("-D${ServerLaunch.PROPERTY}=$file" in described.command())
        assertEquals(launch.properties, described.properties - ServerLaunch.PROPERTY, "only the property is added")
    }

    @Test
    fun `reads back what it wrote`() {
        val file = directory.resolve("rloaded/server-launch.json")

        assertEquals(Outcome.Ok(Unit), launch.write(file))

        assertEquals(Outcome.Ok(launch), ServerLaunch.read(file))
    }

    @Test
    fun `refuses a description with an unknown field, a missing one, or none at all`() {
        val file = directory.resolve("server-launch.json")
        file.writeText(
            """{"java": "j", "maxMemoryMiB": 1, "agent": "a", "classpath": [], "mainClass": "m", "mods": [], """ +
                """"properties": {}, "debug": true}""",
        )
        assertTrue((ServerLaunch.read(file) as Outcome.Err).error.contains("debug"))

        file.writeText("""{"java": "j"}""")
        assertIs<Outcome.Err<String>>(ServerLaunch.read(file))

        assertTrue((ServerLaunch.read(directory.resolve("missing.json")) as Outcome.Err).error.startsWith("cannot read"))
    }
}
