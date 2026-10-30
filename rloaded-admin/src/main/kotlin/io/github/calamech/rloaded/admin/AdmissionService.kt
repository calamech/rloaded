package io.github.calamech.rloaded.admin

import io.github.calamech.rloaded.core.admission.JarClassifier
import io.github.calamech.rloaded.core.admission.JarProblem
import io.github.calamech.rloaded.core.admission.JarReport
import io.github.calamech.rloaded.core.admission.Refusal
import io.github.calamech.rloaded.core.download.Artifact
import io.github.calamech.rloaded.core.download.ArtifactStore
import io.github.calamech.rloaded.core.download.Download
import io.github.calamech.rloaded.core.download.DownloadFailures
import io.github.calamech.rloaded.core.download.Downloads
import io.github.calamech.rloaded.core.lockfile.LockedMod
import io.github.calamech.rloaded.core.lockfile.Lockfile
import io.github.calamech.rloaded.core.lockfile.LockfileError
import io.github.calamech.rloaded.modrinth.ModrinthError
import io.github.calamech.rloaded.modrinth.ModrinthFile
import io.github.calamech.rloaded.modrinth.ModrinthVersion
import io.github.calamech.rloaded.protocol.ModEntry
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.Side
import io.github.calamech.rloaded.protocol.flatMap
import io.github.calamech.rloaded.protocol.map
import io.github.calamech.rloaded.protocol.mapError
import io.github.calamech.rloaded.protocol.traverse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import kotlinx.coroutines.sync.Mutex
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.CompletableFuture

/** Checks mod jars against what the running server loads. The game implements it. */
public fun interface ServerJarCheck {
    /** Why the server cannot load [jars] together with its mods, or null when it can. */
    public fun refusal(jars: List<Path>): String?
}

/** The rules a mod's jar must pass: the static classifier, then the server's own check of what it would load. */
public data class JarRules(
    val classifier: JarClassifier,
    val serverCheck: ServerJarCheck,
)

/** Where the server's lockfile is, and what a server without one starts from. */
public data class LockfileLocation(
    val path: Path,
    val whenMissing: Lockfile,
)

/** A mod an add wrote to the lockfile. */
public data class AddedMod(
    val entry: ModEntry,
    /** The version as its authors number it. */
    val versionNumber: String,
)

/** Why an add was refused. Nothing was written then. */
public sealed interface AddRefusal {
    /** What went wrong, in words for the admin. */
    public val reason: String

    /** Another add is running. */
    public data object Busy : AddRefusal {
        override val reason: String get() = "another add is running; try again when it ends"
    }

    /** The lockfile cannot be read or written. */
    public data class LockfileProblem(
        val error: LockfileError,
    ) : AddRefusal {
        override val reason: String get() = "rloaded/lockfile.json cannot be used: $error"
    }

    /** The mod or one of its dependencies cannot be resolved. */
    public data class Unresolved(
        val error: ResolveError,
    ) : AddRefusal {
        override val reason: String get() = error.reason
    }

    /** A version has no primary file to install. */
    public data class NoFile(
        val versionId: String,
    ) : AddRefusal {
        override val reason: String get() = "version $versionId has no primary file"
    }

    /** Modrinth's records of versions or projects rule them out; every one is listed. */
    public data class Unsuitable(
        val failures: List<RecordFailure>,
    ) : AddRefusal {
        override val reason: String get() = failures.joinToString("; ") { it.reason }
    }

    /** Modrinth's project records could not be read to check downloads. */
    public data class ProjectRecords(
        val error: ModrinthError,
    ) : AddRefusal {
        override val reason: String get() = "Modrinth's project records cannot be read: $error"
    }

    /** Files could not be downloaded or did not match their hash. */
    public data class NotDownloaded(
        val failures: DownloadFailures,
    ) : AddRefusal {
        override val reason: String get() =
            "${failures.failures.size} of ${failures.total} files cannot be downloaded: ${failures.failures}"
    }

    /** A jar cannot be read. */
    public data class Unreadable(
        val problem: JarProblem,
    ) : AddRefusal {
        override val reason: String get() = problem.description
    }

    /** The static rules refuse a jar. */
    public data class Refused(
        val refusals: List<Refusal>,
    ) : AddRefusal {
        override val reason: String get() = refusals.joinToString("; ") { it.reason }
    }

    /** A version's file cannot be listed in a manifest, such as a file name with a path in it. */
    public data class Unlistable(
        val versionId: String,
        val problem: String,
    ) : AddRefusal {
        override val reason: String get() = "version $versionId cannot be sent to players: $problem"
    }

    /** The server cannot load the mods it runs itself. */
    public data class ServerRefused(
        override val reason: String,
    ) : AddRefusal
}

/**
 * Adds a mod from Modrinth to the server's lockfile, with what it needs: resolves it, refuses
 * versions Modrinth's records rule out (reputation, compatibility database), downloads the files into [store], refuses any jar the rules or
 * the server refuse, then writes every entry to the lockfile in one step. The only writer of the
 * lockfile; one add runs at a time.
 */
public class AdmissionService(
    private val resolver: Resolver,
    private val recordChecks: RecordChecks,
    private val store: ArtifactStore,
    private val jarRules: JarRules,
    private val lockfile: LockfileLocation,
    private val clock: Clock,
) {
    private val running = Mutex()

    /** Adds [project] at [version], or its newest release when null, recording [addedBy]. */
    public suspend fun add(
        project: String,
        version: String?,
        addedBy: String,
    ): Outcome<List<AddedMod>, AddRefusal> {
        if (!running.tryLock()) return Outcome.Err(AddRefusal.Busy)
        return try {
            lockfile().flatMap { current ->
                val installed =
                    current.baseSet.associate { it.projectId to it.versionId } +
                        current.mods.associate { it.entry.projectId to it.entry.versionId }
                resolver
                    .resolve(project, version, installed)
                    .mapError<List<ModrinthVersion>, ResolveError, AddRefusal> { AddRefusal.Unresolved(it) }
                    .flatMap { versions -> proven(versions) }
                    .flatMap { versions -> download(versions) }
                    .flatMap { downloaded -> admit(downloaded) }
                    .flatMap { admitted -> write(current, admitted, addedBy) }
            }
        } finally {
            running.unlock()
        }
    }

    /** [add] for Java callers: runs on the IO dispatcher and completes with the outcome. */
    public fun addAsync(
        project: String,
        version: String?,
        addedBy: String,
    ): CompletableFuture<Outcome<List<AddedMod>, AddRefusal>> = CoroutineScope(Dispatchers.IO).future { add(project, version, addedBy) }

    private fun lockfile(): Outcome<Lockfile, AddRefusal> =
        if (Files.exists(lockfile.path)) {
            Lockfile.read(lockfile.path).mapError { AddRefusal.LockfileProblem(it) }
        } else {
            Outcome.Ok(lockfile.whenMissing)
        }

    private suspend fun proven(versions: List<ModrinthVersion>): Outcome<List<ModrinthVersion>, AddRefusal> =
        recordChecks
            .failures(versions)
            .mapError<List<RecordFailure>, ModrinthError, AddRefusal> { AddRefusal.ProjectRecords(it) }
            .flatMap { failures -> if (failures.isEmpty()) Outcome.Ok(versions) else Outcome.Err(AddRefusal.Unsuitable(failures)) }

    private class Downloaded(
        val version: ModrinthVersion,
        val file: ModrinthFile,
        val jar: Path,
    )

    private suspend fun download(versions: List<ModrinthVersion>): Outcome<List<Downloaded>, AddRefusal> =
        versions
            .traverse { version ->
                version.primaryFile?.let { Outcome.Ok(version to it) } ?: Outcome.Err(AddRefusal.NoFile(version.id))
            }.flatMap { files ->
                val artifacts = files.map { (_, file) -> Artifact(file.url, file.sha512, file.size) }
                Downloads
                    .obtainAll(artifacts.map { Download(store, it, JAR) }, PARALLELISM)
                    .mapError<List<Path>, DownloadFailures, AddRefusal> { AddRefusal.NotDownloaded(it) }
                    .map { jars -> files.indices.map { Downloaded(files[it].first, files[it].second, jars[it]) } }
            }

    private fun admit(downloaded: List<Downloaded>): Outcome<List<AddedMod>, AddRefusal> =
        downloaded
            .traverse { mod ->
                jarRules.classifier
                    .classify(mod.jar)
                    .mapError<JarReport, JarProblem, AddRefusal> { AddRefusal.Unreadable(it) }
                    .flatMap { report ->
                        if (report.refusals.isEmpty()) {
                            entry(mod, report.side).map { mod.jar to it }
                        } else {
                            Outcome.Err(AddRefusal.Refused(report.refusals))
                        }
                    }
            }.flatMap { admitted ->
                // The server checks what it loads itself; mods for the client only are the players' to load.
                val refusal = jarRules.serverCheck.refusal(admitted.filter { it.second.entry.side != Side.CLIENT }.map { it.first })
                if (refusal == null) Outcome.Ok(admitted.map { it.second }) else Outcome.Err(AddRefusal.ServerRefused(refusal))
            }

    private fun entry(
        mod: Downloaded,
        side: Side,
    ): Outcome<AddedMod, AddRefusal> =
        try {
            val file = mod.file
            val entry = ModEntry(mod.version.projectId, mod.version.id, file.fileName, file.sha512, file.size, side)
            Outcome.Ok(AddedMod(entry, mod.version.versionNumber))
        } catch (e: IllegalArgumentException) {
            Outcome.Err(AddRefusal.Unlistable(mod.version.id, e.message ?: "invalid entry"))
        }

    private fun write(
        current: Lockfile,
        added: List<AddedMod>,
        addedBy: String,
    ): Outcome<List<AddedMod>, AddRefusal> {
        val now = clock.instant()
        val updated = current.copy(mods = current.mods + added.map { LockedMod(it.entry, now, addedBy) })
        return updated.write(lockfile.path).mapError<Unit, LockfileError, AddRefusal> { AddRefusal.LockfileProblem(it) }.map { added }
    }

    private companion object {
        const val JAR = ".jar"
        const val PARALLELISM = 4
    }
}
