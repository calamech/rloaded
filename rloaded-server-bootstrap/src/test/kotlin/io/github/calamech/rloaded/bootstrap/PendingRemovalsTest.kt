package io.github.calamech.rloaded.bootstrap

import io.github.calamech.rloaded.core.lockfile.LockedMod
import io.github.calamech.rloaded.core.lockfile.Lockfile
import io.github.calamech.rloaded.protocol.Checksum
import io.github.calamech.rloaded.protocol.HashAlgorithm
import io.github.calamech.rloaded.protocol.ModEntry
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.Side
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingRemovalsTest {
    @TempDir
    lateinit var root: Path

    private val folder get() = ServerFolder(root)
    private val output = ByteArrayOutputStream()
    private val at = Instant.parse("2026-11-01T12:00:00Z")

    @Test
    fun `saves the world, then removes the marked mods from the lockfile`() {
        root.resolve("server.properties").writeText("level-name=survival\n")
        root
            .resolve("survival/region")
            .createDirectories()
            .resolve("r.0.0.mca")
            .writeText("chunks")
        root.resolve("survival/session.lock").writeText("locked")
        lockfile(listOf("menu", "standalone"), pending = listOf("standalone"))

        removals().apply()

        val lockfile = ok(Lockfile.read(folder.lockfile))
        assertEquals(listOf("menu"), lockfile.mods.map { it.entry.projectId })
        assertEquals(emptyList(), lockfile.pendingRemovals)
        val backup = folder.backups.resolve("20261101T120000Z-survival.zip")
        ZipFile(backup.toFile()).use { zip -> assertEquals(listOf("region/r.0.0.mca"), zip.entries().toList().map { it.name }) }
        assertTrue("Removed standalone; the world was saved to rloaded/backups/20261101T120000Z-survival.zip." in output.toString())
        assertTrue("\"event\":\"removed\"" in folder.auditLog.readText())
    }

    @Test
    fun `keeps the newest three backups`() {
        root
            .resolve("world")
            .createDirectories()
            .resolve("level.dat")
            .writeText("level")
        folder.backups.createDirectories()
        listOf("20261001T000000Z", "20261002T000000Z", "20261003T000000Z").forEach { folder.backups.resolve("$it-world.zip").writeText("") }
        lockfile(listOf("standalone"), pending = listOf("standalone"))

        removals().apply()

        assertEquals(
            listOf("20261002T000000Z-world.zip", "20261003T000000Z-world.zip", "20261101T120000Z-world.zip"),
            folder.backups
                .listDirectoryEntries()
                .map { it.name }
                .sorted(),
        )
    }

    @Test
    fun `keeps the marked mods when the world cannot be saved`() {
        root
            .resolve("world")
            .createDirectories()
            .resolve("level.dat")
            .writeText("level")
        folder.data.createDirectories()
        Files.writeString(folder.backups, "not a folder")
        lockfile(listOf("standalone"), pending = listOf("standalone"))

        removals().apply()

        val lockfile = ok(Lockfile.read(folder.lockfile))
        assertEquals(listOf("standalone"), lockfile.mods.map { it.entry.projectId })
        assertEquals(listOf("standalone"), lockfile.pendingRemovals)
        assertTrue(
            output.toString().startsWith(
                "Error: kept standalone, still to be removed at a later start: the world could not be saved first:",
            ),
        )
        assertTrue("\"event\":\"postponed\"" in folder.auditLog.readText())
    }

    @Test
    fun `does nothing when nothing is marked`() {
        root
            .resolve("world")
            .createDirectories()
            .resolve("level.dat")
            .writeText("level")
        lockfile(listOf("standalone"), pending = emptyList())

        removals().apply()

        assertFalse(folder.backups.exists())
        assertFalse(folder.auditLog.exists())
        assertEquals("", output.toString())
    }

    private fun removals() = PendingRemovals(folder, Clock.fixed(at, ZoneOffset.UTC), PrintStream(output, true))

    private fun lockfile(
        mods: List<String>,
        pending: List<String>,
    ) {
        val sha = ok(Checksum.parse(HashAlgorithm.SHA512, "a".repeat(128)))
        val locked = mods.map { LockedMod(ModEntry(it, "v$it", "$it.jar", sha, 10, Side.BOTH), at, "console") }
        ok(Lockfile("26.3", "0.1.0", emptyList(), locked, pending).write(folder.lockfile))
    }

    private fun <T, E> ok(outcome: Outcome<T, E>): T =
        when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw AssertionError("expected a value, got ${outcome.error}")
        }
}
