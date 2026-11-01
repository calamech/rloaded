package io.github.calamech.rloaded.admin

import io.github.calamech.rloaded.core.lockfile.LockedMod
import io.github.calamech.rloaded.core.lockfile.Lockfile
import io.github.calamech.rloaded.protocol.Checksum
import io.github.calamech.rloaded.protocol.HashAlgorithm
import io.github.calamech.rloaded.protocol.ModEntry
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.Side
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class RemovalsAtStartTest {
    @TempDir
    lateinit var world: Path

    private val at = Instant.parse("2026-11-01T12:00:00Z")

    @Test
    fun `leaves the backups of a world out of its next backup`() {
        world
            .resolve("region")
            .createDirectories()
            .resolve("r.0.0.mca")
            .writeText("chunks")
        val backups = world.resolve("rloaded/backups").createDirectories()
        backups.resolve("20261001T000000Z-world.zip").writeText("an older backup")
        val lockfile = world.resolve("rloaded/lockfile.json")
        val sha = (Checksum.parse(HashAlgorithm.SHA512, "a".repeat(128)) as Outcome.Ok).value
        val mod = LockedMod(ModEntry("standalone", "vstandalone", "standalone.jar", sha, 10, Side.BOTH), at, "owner")
        (Lockfile("26.3", "0.1.0", emptyList(), listOf(mod), listOf("standalone")).write(lockfile) as Outcome.Ok)

        val clock = Clock.fixed(at, ZoneOffset.UTC)
        val outcome =
            RemovalsAtStart(lockfile, world, backups, AuditLog(world.resolve("rloaded/audit.log"), clock) {}, clock, 1).apply()

        val backup = backups.resolve("20261101T120000Z-${world.fileName}.zip")
        assertEquals(RemovalsOutcome.Removed(listOf("standalone"), backup), outcome)
        ZipFile(backup.toFile()).use { zip ->
            assertEquals(
                listOf("region/r.0.0.mca", "rloaded/lockfile.json"),
                zip
                    .entries()
                    .toList()
                    .map { it.name }
                    .sorted(),
            )
        }
        assertEquals(
            listOf(backup),
            java.nio.file.Files
                .list(backups)
                .use { it.toList() },
            "only the newest backup is kept",
        )
    }
}
