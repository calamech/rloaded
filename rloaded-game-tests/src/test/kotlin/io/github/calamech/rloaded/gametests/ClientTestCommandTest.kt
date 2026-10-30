package io.github.calamech.rloaded.gametests

import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals

class ClientTestCommandTest {
    private val report = Path.of("build", "report.txt")

    @Test
    fun `adds the test mods, turns the tests on and forces OpenGL`() {
        val command = listOf("java", "-Xmx4096M", "-Dfabric.addMods=base.jar", "Main", "--username", "Tester")

        val result = withTestMods(command, listOf(Path.of("a.jar"), Path.of("b.jar")), report)

        assertEquals(
            listOf(
                "java",
                "-Dfabric.client.gametest",
                "-Drloaded.client-game-test.report=$report",
                "-Xmx4096M",
                "-Dfabric.addMods=base.jar${File.pathSeparator}a.jar${File.pathSeparator}b.jar",
                "Main",
                "--username",
                "Tester",
                "--graphicsBackend",
                "opengl",
            ),
            result,
        )
    }

    @Test
    fun `refuses a command that does not load mods`() {
        val refused = runCatching { withTestMods(listOf("java", "Main"), listOf(Path.of("a.jar")), report) }.exceptionOrNull()

        assertEquals("the launch command has no -Dfabric.addMods= argument", refused?.message)
    }
}
