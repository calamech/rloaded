package io.github.calamech.rloaded.launcher

import io.github.calamech.rloaded.protocol.Outcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameProcessTest {
    @Test
    fun `starts a process and writes its output to the log`(
        @TempDir dir: Path,
    ) {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val log = dir.resolve("logs/game.log")

        val process = (GameProcess.start(listOf(java, "-version"), dir.resolve("game"), log) as Outcome.Ok).value

        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        assertTrue("version" in log.readText())
    }

    @Test
    fun `reports a command that cannot start`(
        @TempDir dir: Path,
    ) {
        val result = GameProcess.start(listOf(dir.resolve("missing-java").toString()), dir, dir.resolve("game.log"))

        assertTrue(result is Outcome.Err)
    }
}
