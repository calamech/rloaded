package io.github.calamech.rloaded.admin

import io.github.calamech.rloaded.protocol.Outcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LocalCompatTest {
    @TempDir
    lateinit var directory: Path

    private val bundled =
        CompatDatabase(
            deniedCategories = mapOf("optimization" to "built at start"),
            deniedProjects = mapOf("AANobbMI" to "replaces the renderer"),
            versions = mapOf("vfine" to TestedVersion(CompatStatus.VERIFIED, "0.1.0", "works")),
        )

    private fun file() = directory.resolve("rloaded").resolve("compat-local.json")

    private fun open() = (LocalCompat.open(file(), bundled) as Outcome.Ok).value

    @Test
    fun `starts from the shipped database when the server recorded nothing`() {
        assertEquals(bundled, open().current())
        assertFalse(file().exists())
    }

    @Test
    fun `records a broken version, in the file and for the next checks`() {
        val compat = open()

        assertEquals(Outcome.Ok(true), compat.recordBroken("vfine", "0.2.0", "failed on a player's game"))

        val broken = TestedVersion(CompatStatus.BROKEN, "0.2.0", "failed on a player's game")
        assertEquals(bundled.copy(versions = mapOf("vfine" to broken)), compat.current())
        assertEquals(
            Outcome.Ok(CompatDatabase(emptyMap(), emptyMap(), mapOf("vfine" to broken))),
            CompatDatabase.parse(file().readText()),
        )
        assertEquals(mapOf("vfine" to broken), open().current().versions)
    }

    @Test
    fun `records a version once`() {
        val compat = open()
        compat.recordBroken("vbad", "0.2.0", "first")

        assertEquals(Outcome.Ok(false), compat.recordBroken("vbad", "0.2.0", "second"))
        assertEquals("first", compat.current().versions["vbad"]?.note)
    }

    @Test
    fun `keeps a version unrecorded when the file cannot be written`() {
        val compat = (LocalCompat.open(directory.resolve("missing").resolve("compat-local.json"), bundled) as Outcome.Ok).value
        directory.resolve("missing").writeText("a file where the directory should be")

        val recorded = compat.recordBroken("vbad", "0.2.0", "note")

        assertEquals(true, (recorded as? Outcome.Err)?.error?.contains("compat-local.json cannot be written"))
        assertEquals(null, compat.current().versions["vbad"])
    }

    @Test
    fun `refuses a file it cannot read, or one that denies categories or projects`() {
        file().parent.createDirectories()
        file().writeText("{")
        assertEquals(true, (LocalCompat.open(file(), bundled) as? Outcome.Err)?.error?.isNotEmpty())

        file().writeText(
            """{"schema": 1, "deniedCategories": [{"category": "c", "reason": "r"}], "deniedProjects": [], "versions": []}""",
        )
        assertEquals(
            Outcome.Err("${file()} lists categories or projects; it may list versions only"),
            LocalCompat.open(file(), bundled),
        )
    }
}
