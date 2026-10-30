package io.github.calamech.rloaded.gametests

import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals

class ClientTestCommandTest {
    private val report = Path.of("build", "report.txt")

    @Test
    fun `adds the test mods and turns the tests on`() {
        val command = listOf("java", "-Xmx4096M", "-Dfabric.addMods=base.jar", "Main", "--username", "Tester")

        val result = withTestMods(command, listOf(Path.of("a.jar"), Path.of("b.jar")), clientGameTestProperties(report, "c.jar"))

        assertEquals(
            listOf(
                "java",
                "-Dfabric.client.gametest=true",
                "-Drloaded.client-game-test.report=$report",
                "-Drloaded.hot-add-mods=c.jar",
                "-Xmx4096M",
                "-Dfabric.addMods=base.jar${File.pathSeparator}a.jar${File.pathSeparator}b.jar",
                "Main",
                "--username",
                "Tester",
            ),
            result,
        )
    }

    @Test
    fun `refuses a command that does not load mods`() {
        val refused = runCatching { withTestMods(listOf("java", "Main"), listOf(Path.of("a.jar")), emptyMap()) }.exceptionOrNull()

        assertEquals("the launch command has no -Dfabric.addMods= argument", refused?.message)
    }
}
