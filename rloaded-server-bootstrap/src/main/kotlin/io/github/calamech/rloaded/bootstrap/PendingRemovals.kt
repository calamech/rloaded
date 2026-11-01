package io.github.calamech.rloaded.bootstrap

import io.github.calamech.rloaded.admin.AuditLog
import io.github.calamech.rloaded.core.lockfile.Lockfile
import io.github.calamech.rloaded.protocol.Outcome
import java.io.IOException
import java.io.PrintStream
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

/**
 * Applies the removals marked in the lockfile when the server starts: saves the world to a zip in
 * `rloaded/backups`, keeping the newest [keep], then writes the lockfile without the marked mods.
 * When the world cannot be saved, or the lockfile written, the mods stay and stay marked: the
 * removal waits for a later start. Each outcome goes to the console and the audit log.
 */
public class PendingRemovals(
    private val folder: ServerFolder,
    private val clock: Clock,
    private val out: PrintStream,
    private val keep: Int = KEEP_BACKUPS,
) {
    private val audit = AuditLog(folder.auditLog, clock) { out.println("The audit log cannot be written: ${it.reason}") }

    /** Applies the marked removals, if any. A lockfile that cannot be read is left to the start that reads it next. */
    public fun apply() {
        if (!folder.lockfile.exists()) return
        val lockfile = (Lockfile.read(folder.lockfile) as? Outcome.Ok)?.value ?: return
        val pending = lockfile.pendingRemovals
        if (pending.isEmpty()) return
        val backup =
            try {
                backup()
            } catch (e: IOException) {
                return postpone(pending, "the world could not be saved first: ${e.message ?: e.javaClass.simpleName}")
            }
        val updated = lockfile.copy(mods = lockfile.mods.filter { it.entry.projectId !in pending }, pendingRemovals = emptyList())
        when (val written = updated.write(folder.lockfile)) {
            is Outcome.Err -> {
                postpone(pending, "rloaded/lockfile.json cannot be written: ${written.error}")
            }

            is Outcome.Ok -> {
                out.println(
                    "Removed ${pending.joinToString(", ")}; the world was saved to rloaded/backups/${backup?.name ?: "(no world yet)"}.",
                )
                audit.removed(pending, backup)
            }
        }
    }

    private fun postpone(
        pending: List<String>,
        reason: String,
    ) {
        out.println("Error: kept ${pending.joinToString(", ")}, still to be removed at a later start: $reason")
        audit.postponed(pending, reason)
    }

    // A zip of the world folder, written aside then moved in place; none when no world exists yet.
    private fun backup(): Path? {
        val world = folder.world
        if (!world.exists()) return null
        Files.createDirectories(folder.backups)
        val name = "${STAMP.format(clock.instant())}-${world.name}.zip"
        val temporary = Files.createTempFile(folder.backups, ".tmp-", ".zip")
        try {
            zip(world, temporary)
            val target = folder.backups.resolve(name)
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            prune()
            return target
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    // Backup names start with their time, so the newest sort last.
    private fun prune() {
        val backups = Files.list(folder.backups).use { files -> files.filter { it.name.endsWith(".zip") }.sorted().toList() }
        backups.dropLast(keep).forEach { Files.deleteIfExists(it) }
    }

    private companion object {
        const val KEEP_BACKUPS = 3
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    }
}

// The world's files, without the lock the running game holds.
private fun zip(
    world: Path,
    target: Path,
) {
    val files = Files.walk(world).use { paths -> paths.filter { it.isRegularFile() && it.name != "session.lock" }.toList() }
    ZipOutputStream(Files.newOutputStream(target)).use { zip ->
        for (path in files) {
            zip.putNextEntry(ZipEntry(world.relativize(path).joinToString("/")))
            Files.copy(path, zip)
            zip.closeEntry()
        }
    }
}
