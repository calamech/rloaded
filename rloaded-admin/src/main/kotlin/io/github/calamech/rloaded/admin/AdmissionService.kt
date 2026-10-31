package io.github.calamech.rloaded.admin

import io.github.calamech.rloaded.admin.staging.Staging
import io.github.calamech.rloaded.admin.staging.StagingFailure
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
import kotlinx.coroutines.future.await
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

/**
 * The rules a mod's jar must pass: the static classifier, the server's own check of what it would
 * load, then a run on a staging server.
 */
public data class JarRules(
    val classifier: JarClassifier,
    val serverCheck: ServerJarCheck,
    val staging: Staging,
)

/** Where the server's lockfile is, and what a server without one starts from. */
public data class LockfileLocation(
    val path: Path,
    val whenMissing: Lockfile,
)

/** Loads admitted jars into the running server. The game implements it. */
public fun interface LiveApply {
    /**
     * Loads [jars] into the running server, which may take several ticks, and completes with the
     * reason when it could not. Empty when only players load the mods of the add.
     */
    public fun apply(jars: List<Path>): CompletableFuture<Outcome<Unit, String>>

    /** Outcomes of an apply, typed for Java implementations. */
    public companion object {
        /** The mods were loaded. */
        public val LOADED: Outcome<Unit, String> = Outcome.Ok(Unit)

        /** The mods were not loaded, for [reason]. */
        public fun notLoaded(reason: String): Outcome<Unit, String> = Outcome.Err(reason)
    }
}

/** The game an add loads mods into, which decides the jars it checks, tries and loads itself. */
public enum class LiveGame {
    /** A dedicated server: it runs every mod but those for the client only, which its players load. */
    DEDICATED_SERVER,

    /** The world a game has open: the game is both the server and its player's client, and loads every mod. */
    SINGLEPLAYER,
    ;

    internal fun loads(side: Side): Boolean =
        when (this) {
            DEDICATED_SERVER -> side != Side.CLIENT
            SINGLEPLAYER -> true
        }
}

/** Where an admitted add lands: the running [game] first, then its lockfile. */
public data class AddTarget(
    val lockfile: LockfileLocation,
    val live: LiveApply,
    val game: LiveGame,
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

    /** The running server could not load the mods. */
    public data class NotApplied(
        val problem: String,
    ) : AddRefusal {
        override val reason: String get() = "the server could not load them: $problem"
    }

    /** The running server loaded the mods, but the lockfile could not record them. */
    public data class NotRecorded(
        val error: LockfileError,
    ) : AddRefusal {
        override val reason: String get() = "the server loaded them but rloaded/lockfile.json cannot record them: $error"
    }

    /** The mods failed on the staging server, or it could not judge them. */
    public data class StagingRefused(
        val failure: StagingFailure,
    ) : AddRefusal {
        override val reason: String get() = failure.reason
    }
}

/**
 * Adds a mod from Modrinth to the lockfile of a server or a singleplayer world, with what it needs:
 * resolves it, refuses versions Modrinth's records rule out (reputation, compatibility database),
 * downloads the files into [store], refuses any jar the rules or the game refuse, tries the jars the
 * game would load on a staging server, loads them into the running game, then writes every entry
 * to the lockfile in one step. The only writer of the lockfile; one add runs at a time.
 */
public class AdmissionService(
    private val resolver: Resolver,
    private val recordChecks: RecordChecks,
    private val store: ArtifactStore,
    private val jarRules: JarRules,
    private val target: AddTarget,
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
                    .flatMap { admitted -> stage(current, admitted) }
                    .flatMap { admitted -> apply(admitted) }
                    .flatMap { added -> write(current, added, addedBy) }
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
        if (Files.exists(target.lockfile.path)) {
            Lockfile.read(target.lockfile.path).mapError { AddRefusal.LockfileProblem(it) }
        } else {
            Outcome.Ok(target.lockfile.whenMissing)
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

    private fun admit(downloaded: List<Downloaded>): Outcome<List<Admitted>, AddRefusal> =
        downloaded
            .traverse { mod ->
                jarRules.classifier
                    .classify(mod.jar)
                    .mapError<JarReport, JarProblem, AddRefusal> { AddRefusal.Unreadable(it) }
                    .flatMap { report ->
                        if (report.refusals.isEmpty()) {
                            entry(mod, report.side).map { Admitted(mod.jar, it) }
                        } else {
                            Outcome.Err(AddRefusal.Refused(report.refusals))
                        }
                    }
            }.flatMap { admitted ->
                val refusal = jarRules.serverCheck.refusal(loadedJars(admitted, target.game))
                if (refusal == null) Outcome.Ok(admitted) else Outcome.Err(AddRefusal.ServerRefused(refusal))
            }

    // The staging server runs the lockfile's mods too: the server may have added some since it started.
    private suspend fun stage(
        current: Lockfile,
        admitted: List<Admitted>,
    ): Outcome<List<Admitted>, AddRefusal> {
        val jars = loadedJars(admitted, target.game)
        if (jars.isEmpty()) return Outcome.Ok(admitted)
        val installed =
            current.mods
                .map { it.entry }
                .filter { target.game.loads(it.side) }
                .map { store.pathOf(it.sha512, JAR) }
        return jarRules.staging
            .tryOut(installed, jars)
            .mapError<Unit, StagingFailure, AddRefusal> { AddRefusal.StagingRefused(it) }
            .map { admitted }
    }

    private suspend fun apply(admitted: List<Admitted>): Outcome<List<AddedMod>, AddRefusal> =
        target.live
            .apply(loadedJars(admitted, target.game))
            .await()
            .mapError<Unit, String, AddRefusal> { AddRefusal.NotApplied(it) }
            .map { admitted.map { it.mod } }

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
        return updated.write(target.lockfile.path).mapError<Unit, LockfileError, AddRefusal> { AddRefusal.NotRecorded(it) }.map { added }
    }

    private companion object {
        const val JAR = ".jar"
        const val PARALLELISM = 4
    }
}

private class Admitted(
    val jar: Path,
    val mod: AddedMod,
)

// The game checks, tries and loads what it runs itself; on a dedicated server, mods for the client only are the players' to load.
private fun loadedJars(
    admitted: List<Admitted>,
    game: LiveGame,
): List<Path> = admitted.filter { game.loads(it.mod.entry.side) }.map { it.jar }
