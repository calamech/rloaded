package io.github.calamech.rloaded.admin

import io.github.calamech.rloaded.core.lockfile.Lockfile
import io.github.calamech.rloaded.protocol.Outcome
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** What applying the marked removals did. */
public sealed interface RemovalsOutcome {
    /** Nothing was marked, or the lockfile could not be read, which the next reader reports. */
    public data object Nothing : RemovalsOutcome

    /** [projects] left the lockfile after the world was saved to [backup], or none when it had no world yet. */
    public data class Removed(
        val projects: List<String>,
        val backup: Path?,
    ) : RemovalsOutcome

    /** [projects] stay, still marked, for [reason]. */
    public data class Postponed(
        val projects: List<String>,
        val reason: String,
    ) : RemovalsOutcome
}

/**
 * Applies the removals marked in the [lockfile] of a server or a world before it starts: saves the
 * [world] folder to a zip in [backups], keeping the newest [keep], then writes the lockfile without
 * the marked mods. When the world cannot be saved, or the lockfile written, the mods stay and stay
 * marked. Each outcome goes to the [audit] log. A [backups] folder inside the world is left out of
 * the zip.
 */
public class RemovalsAtStart(
    private val lockfile: Path,
    private val world: Path,
    private val backups: Path,
    private val audit: AuditLog,
    private val clock: Clock,
    private val keep: Int,
) {
    /** Applies the marked removals, if any. */
    public fun apply(): RemovalsOutcome {
        val current = (if (lockfile.exists()) Lockfile.read(lockfile) as? Outcome.Ok else null)?.value
        val pending = current?.pendingRemovals.orEmpty()
        return if (current == null || pending.isEmpty()) RemovalsOutcome.Nothing else removeAfterBackup(current, pending)
    }

    private fun removeAfterBackup(
        current: Lockfile,
        pending: List<String>,
    ): RemovalsOutcome {
        val backup =
            try {
                backup()
            } catch (e: IOException) {
                return postpone(pending, "the world could not be saved first: ${e.message ?: e.javaClass.simpleName}")
            }
        val updated = current.copy(mods = current.mods.filter { it.entry.projectId !in pending }, pendingRemovals = emptyList())
        return when (val written = updated.write(lockfile)) {
            is Outcome.Err -> {
                postpone(pending, "the lockfile cannot be written: ${written.error}")
            }

            is Outcome.Ok -> {
                audit.removed(pending, backup)
                RemovalsOutcome.Removed(pending, backup)
            }
        }
    }

    private fun postpone(
        pending: List<String>,
        reason: String,
    ): RemovalsOutcome {
        audit.postponed(pending, reason)
        return RemovalsOutcome.Postponed(pending, reason)
    }

    // A zip of the world folder, written aside then moved in place; none when no world exists yet.
    private fun backup(): Path? {
        if (!world.exists()) return null
        Files.createDirectories(backups)
        val temporary = Files.createTempFile(backups, ".tmp-", ".zip")
        try {
            zip(world, temporary, backups)
            val target = backups.resolve("${STAMP.format(clock.instant())}-${world.name}.zip")
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            prune()
            return target
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    // Backup names start with their time, so the newest sort last.
    private fun prune() {
        val zips = Files.list(backups).use { files -> files.filter { it.name.endsWith(".zip") }.sorted().toList() }
        zips.dropLast(keep).forEach { Files.deleteIfExists(it) }
    }

    /** Backups. */
    public companion object {
        /** How many backups are kept. */
        public const val KEEP_BACKUPS: Int = 3

        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    }
}

// The world's files, without the lock the running game holds and without earlier backups.
private fun zip(
    world: Path,
    target: Path,
    backups: Path,
) {
    val files =
        Files.walk(world).use { paths ->
            paths.filter { it.isRegularFile() && it.name != "session.lock" && !it.startsWith(backups) }.toList()
        }
    ZipOutputStream(Files.newOutputStream(target)).use { zip ->
        for (path in files) {
            zip.putNextEntry(ZipEntry(world.relativize(path).joinToString("/")))
            Files.copy(path, zip)
            zip.closeEntry()
        }
    }
}
