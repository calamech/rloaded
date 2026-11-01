package io.github.calamech.rloaded.admin

import io.github.calamech.rloaded.core.lockfile.Lockfile
import io.github.calamech.rloaded.core.lockfile.LockfileError
import io.github.calamech.rloaded.modrinth.DependencyType
import io.github.calamech.rloaded.modrinth.Modrinth
import io.github.calamech.rloaded.modrinth.ModrinthError
import io.github.calamech.rloaded.modrinth.ModrinthProject
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.RemovalProblem
import io.github.calamech.rloaded.protocol.flatMap
import io.github.calamech.rloaded.protocol.map
import io.github.calamech.rloaded.protocol.mapError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import kotlinx.coroutines.sync.Mutex
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

/** Why a mod was not marked for removal, or not kept. Nothing was written then. */
public sealed interface RemovalRefusal {
    /** What went wrong, in words for the admin. */
    public val reason: String

    /** What the Mods screen tells the player. */
    public val problem: RemovalProblem

    /** An add or another change is running. */
    public data object Busy : RemovalRefusal {
        override val reason: String = "another add or removal is running"
        override val problem: RemovalProblem = RemovalProblem.BUSY
    }

    /** The lockfile cannot be read, or the change cannot be written to it. */
    public data class Storage(
        val error: LockfileError,
    ) : RemovalRefusal {
        override val reason: String get() = "rloaded/lockfile.json cannot be used: $error"
        override val problem: RemovalProblem = RemovalProblem.STORAGE
    }

    /** The request could not be written to the audit log. */
    public data class NotAudited(
        val error: AuditError,
    ) : RemovalRefusal {
        override val reason: String get() = "the audit log ${error.file.fileName} cannot be written: ${error.reason}"
        override val problem: RemovalProblem = RemovalProblem.STORAGE
    }

    /** The lockfile does not list the mod. */
    public data class NotInstalled(
        val project: String,
    ) : RemovalRefusal {
        override val reason: String get() = "$project is not installed"
        override val problem: RemovalProblem = RemovalProblem.NOT_INSTALLED
    }

    /** The mod is part of the base set. */
    public data class BaseSet(
        val project: String,
    ) : RemovalRefusal {
        override val reason: String get() = "$project is part of the base set every server runs"
        override val problem: RemovalProblem = RemovalProblem.BASE_SET
    }

    /** The mod is already marked. */
    public data class AlreadyMarked(
        val project: String,
    ) : RemovalRefusal {
        override val reason: String get() = "$project is already removed at the next restart"
        override val problem: RemovalProblem = RemovalProblem.ALREADY_MARKED
    }

    /** The mod is not marked. */
    public data class NotMarked(
        val project: String,
    ) : RemovalRefusal {
        override val reason: String get() = "$project is not removed at the next restart"
        override val problem: RemovalProblem = RemovalProblem.NOT_MARKED
    }

    /** Mods that stay need it, named by their Modrinth titles. */
    public data class Needed(
        val project: String,
        val by: List<String>,
    ) : RemovalRefusal {
        override val reason: String get() = "${by.joinToString(", ")} ${if (by.size == 1) "needs" else "need"} it"
        override val problem: RemovalProblem = RemovalProblem.NEEDED
    }

    /** Modrinth could not tell what the installed mods need. */
    public data class Records(
        val error: ModrinthError,
    ) : RemovalRefusal {
        override val reason: String get() = "Modrinth cannot be reached: $error"
        override val problem: RemovalProblem = RemovalProblem.UNAVAILABLE
    }
}

/**
 * Marks mods of the lockfile for removal at the next restart, or keeps them. Shares the lock of the
 * [AdmissionService] that made it, so an add never writes over a mark, and records each request in
 * the same audit log. A mod that other mods staying need is not marked.
 */
public class Removals internal constructor(
    private val running: Mutex,
    private val target: AddTarget,
    private val modrinth: Modrinth,
    private val gameVersion: String,
) {
    /** Marks [project], an id or a slug, for removal at the next restart, for [actor]; returns its id. */
    public suspend fun remove(
        project: String,
        actor: AddActor,
    ): Outcome<String, RemovalRefusal> = change(project, actor, AuditAction.REMOVE)

    /** Keeps [project], marked for removal, for [actor]; returns its id. */
    public suspend fun keep(
        project: String,
        actor: AddActor,
    ): Outcome<String, RemovalRefusal> = change(project, actor, AuditAction.KEEP)

    /** [remove] for Java callers. */
    public fun removeAsync(
        project: String,
        actor: AddActor,
    ): CompletableFuture<Outcome<String, RemovalRefusal>> = CoroutineScope(Dispatchers.IO).future { remove(project, actor) }

    /** [keep] for Java callers. */
    public fun keepAsync(
        project: String,
        actor: AddActor,
    ): CompletableFuture<Outcome<String, RemovalRefusal>> = CoroutineScope(Dispatchers.IO).future { keep(project, actor) }

    private suspend fun change(
        project: String,
        actor: AddActor,
        action: AuditAction,
    ): Outcome<String, RemovalRefusal> =
        when (val requested = target.audit.requested(actor, action, project, null)) {
            is Outcome.Err -> {
                Outcome.Err(RemovalRefusal.NotAudited(requested.error))
            }

            is Outcome.Ok -> {
                val outcome = locked { lockfile -> apply(lockfile, project, action) }
                when (outcome) {
                    is Outcome.Ok -> if (action == AuditAction.KEEP) requested.value.kept() else requested.value.marked()
                    is Outcome.Err -> requested.value.refused(outcome.error.problem, outcome.error.reason)
                }
                outcome
            }
        }

    private suspend fun locked(work: suspend (Lockfile) -> Outcome<String, RemovalRefusal>): Outcome<String, RemovalRefusal> {
        if (!running.tryLock()) return Outcome.Err(RemovalRefusal.Busy)
        return try {
            lockfile(target).flatMap { work(it) }
        } finally {
            running.unlock()
        }
    }

    private suspend fun apply(
        lockfile: Lockfile,
        project: String,
        action: AuditAction,
    ): Outcome<String, RemovalRefusal> =
        projectId(lockfile, project).flatMap { id ->
            when {
                action == AuditAction.KEEP && id !in lockfile.pendingRemovals -> {
                    Outcome.Err(RemovalRefusal.NotMarked(project))
                }

                action == AuditAction.KEEP -> {
                    target.write(lockfile.copy(pendingRemovals = lockfile.pendingRemovals - id), id)
                }

                id in lockfile.pendingRemovals -> {
                    Outcome.Err(RemovalRefusal.AlreadyMarked(project))
                }

                else -> {
                    notNeeded(
                        lockfile,
                        id,
                        project,
                    ).flatMap { target.write(lockfile.copy(pendingRemovals = lockfile.pendingRemovals + id), id) }
                }
            }
        }

    // The project id the lockfile knows, from an id or a slug.
    private suspend fun projectId(
        lockfile: Lockfile,
        project: String,
    ): Outcome<String, RemovalRefusal> {
        if (lockfile.baseSet.any { it.projectId == project }) return Outcome.Err(RemovalRefusal.BaseSet(project))
        if (lockfile.mods.any { it.entry.projectId == project }) return Outcome.Ok(project)
        // Modrinth lists a project's versions by id or slug; each names the project's id.
        return when (val versions = modrinth.projectVersions(project, gameVersion)) {
            is Outcome.Err -> {
                if (versions.error is ModrinthError.UnknownProjects) {
                    Outcome.Err(RemovalRefusal.NotInstalled(project))
                } else {
                    Outcome.Err(RemovalRefusal.Records(versions.error))
                }
            }

            is Outcome.Ok -> {
                val id = versions.value.firstOrNull()?.projectId
                when {
                    id != null && lockfile.baseSet.any { it.projectId == id } -> Outcome.Err(RemovalRefusal.BaseSet(project))
                    id != null && lockfile.mods.any { it.entry.projectId == id } -> Outcome.Ok(id)
                    else -> Outcome.Err(RemovalRefusal.NotInstalled(project))
                }
            }
        }
    }

    // Mods that stay and need the project, from Modrinth's records of their versions.
    private suspend fun notNeeded(
        lockfile: Lockfile,
        id: String,
        project: String,
    ): Outcome<Unit, RemovalRefusal> {
        val staying = lockfile.mods.filter { it.entry.projectId != id && it.entry.projectId !in lockfile.pendingRemovals }
        return modrinth
            .versions(staying.map { it.entry.versionId })
            .flatMap { versions ->
                val needing =
                    versions.values
                        .filter { version -> version.dependencies.any { it.type == DependencyType.REQUIRED && it.projectId == id } }
                        .map { it.projectId }
                if (needing.isEmpty()) Outcome.Ok(emptyMap()) else modrinth.projects(needing)
            }.mapError<Map<String, ModrinthProject>, ModrinthError, RemovalRefusal> { RemovalRefusal.Records(it) }
            .flatMap { needing ->
                if (needing.isEmpty()) {
                    Outcome.Ok(Unit)
                } else {
                    Outcome.Err(RemovalRefusal.Needed(project, needing.values.map { it.title }.sorted()))
                }
            }
    }
}

private fun lockfile(target: AddTarget): Outcome<Lockfile, RemovalRefusal> =
    if (Files.exists(target.lockfile.path)) {
        Lockfile.read(target.lockfile.path).mapError { RemovalRefusal.Storage(it) }
    } else {
        Outcome.Ok(target.lockfile.whenMissing)
    }

private fun AddTarget.write(
    updated: Lockfile,
    id: String,
): Outcome<String, RemovalRefusal> =
    updated
        .write(lockfile.path)
        .mapError<Unit, LockfileError, RemovalRefusal> {
            RemovalRefusal.Storage(it)
        }.map { id }
