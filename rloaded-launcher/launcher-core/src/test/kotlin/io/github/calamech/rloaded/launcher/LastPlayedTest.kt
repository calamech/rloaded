package io.github.calamech.rloaded.launcher

import io.github.calamech.rloaded.protocol.Outcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LastPlayedTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `reads back what it wrote`() {
        val file = dir.resolve("data/last-played.json")

        assertEquals(Outcome.Ok(Unit), LastPlayed("Alice", "friends.example.org:25566").write(file))

        assertEquals(Outcome.Ok(LastPlayed("Alice", "friends.example.org:25566")), LastPlayed.read(file))
    }

    @Test
    fun `is empty before the first game`() {
        assertEquals(Outcome.Ok(LastPlayed.NONE), LastPlayed.read(dir.resolve("last-played.json")))
    }

    @Test
    fun `refuses a file it did not write`() {
        val file = dir.resolve("last-played.json").apply { writeText("""{"playerName": "Alice", "server": "", "extra": 1}""") }

        val error = (LastPlayed.read(file) as Outcome.Err).error
        assertTrue(error.startsWith("$file is not what the launcher writes"), error)
    }
}
