package io.github.calamech.rloaded.bootstrap

import io.github.calamech.rloaded.admin.AuditLog
import io.github.calamech.rloaded.admin.RemovalsAtStart
import io.github.calamech.rloaded.admin.RemovalsOutcome
import java.io.PrintStream
import java.time.Clock

/**
 * Applies the removals marked in the server's lockfile when the server starts, with a backup of the
 * world in `rloaded/backups` first ([RemovalsAtStart]), and says what it did on the console.
 */
public class PendingRemovals(
    folder: ServerFolder,
    clock: Clock,
    private val out: PrintStream,
) {
    private val removals =
        RemovalsAtStart(
            folder.lockfile,
            folder.world,
            folder.backups,
            AuditLog(folder.auditLog, clock) { out.println("The audit log cannot be written: ${it.reason}") },
            clock,
            RemovalsAtStart.KEEP_BACKUPS,
        )

    /** Applies the marked removals, if any. */
    public fun apply() {
        when (val outcome = removals.apply()) {
            RemovalsOutcome.Nothing -> {}

            is RemovalsOutcome.Removed -> {
                out.println(
                    "Removed ${outcome.projects.joinToString(", ")}; the world was saved to " +
                        "rloaded/backups/${outcome.backup?.fileName ?: "(no world yet)"}.",
                )
            }

            is RemovalsOutcome.Postponed -> {
                out.println("Error: kept ${outcome.projects.joinToString(", ")}, still to be removed at a later start: ${outcome.reason}")
            }
        }
    }
}
