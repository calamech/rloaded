package io.github.calamech.rloaded.modrinth

import io.github.calamech.rloaded.core.download.Artifact
import io.github.calamech.rloaded.core.download.ArtifactStore
import io.github.calamech.rloaded.core.download.Download
import io.github.calamech.rloaded.core.download.DownloadFailures
import io.github.calamech.rloaded.core.download.Downloads
import io.github.calamech.rloaded.protocol.ModEntry
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.flatMap
import io.github.calamech.rloaded.protocol.map
import io.github.calamech.rloaded.protocol.mapError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/** A manifest entry and the verified jar it names, in the store. */
public data class InstalledMod(
    val entry: ModEntry,
    val jar: Path,
)

/** Why the mods of a manifest could not be installed. */
public sealed interface InstallError {
    /** Modrinth's records could not be read. */
    public data class Records(
        val error: ModrinthError,
    ) : InstallError

    /** Entries that disagree with Modrinth's records, all of them, by project id. */
    public data class Mismatches(
        val mismatches: Map<String, FileMismatch>,
    ) : InstallError

    /** Files that could not be downloaded or did not match their hash. */
    public data class Downloads(
        val failures: DownloadFailures,
    ) : InstallError
}

/** Told which file is downloaded, and how many are, out of how many. */
public fun interface DownloadProgress {
    /** Called after the file of [entry], from a download thread. */
    public fun onProgress(
        entry: ModEntry,
        done: Int,
        total: Int,
    )
}

/**
 * Installs the mods of a manifest the way a client must: each entry is checked against Modrinth's
 * own record, then its file is downloaded from the URL in that record into the store, where size
 * and SHA-512 are verified. The server's entries never choose where a file comes from.
 */
public class ModInstaller(
    private val modrinth: Modrinth,
    private val store: ArtifactStore,
    private val parallelism: Int = DEFAULT_PARALLELISM,
) {
    /** Installs [entries]; on success the jars are in entry order. */
    public suspend fun install(
        entries: List<ModEntry>,
        progress: DownloadProgress,
    ): Outcome<List<InstalledMod>, InstallError> =
        modrinth
            .versions(entries.map { it.versionId })
            .mapError<Map<String, ModrinthVersion>, ModrinthError, InstallError> { InstallError.Records(it) }
            .flatMap { records -> files(entries, records) }
            .flatMap { files ->
                val downloads = files.map { Download(store, Artifact(it.url, it.sha512, it.size), JAR) }
                Downloads
                    .obtainAll(downloads, parallelism) { index, done, total -> progress.onProgress(entries[index], done, total) }
                    .mapError<List<Path>, DownloadFailures, InstallError> { InstallError.Downloads(it) }
                    .map { jars -> entries.zip(jars) { entry, jar -> InstalledMod(entry, jar) } }
            }

    /** [install] for Java callers: runs on the IO dispatcher and completes with the outcome. */
    public fun installAsync(
        entries: List<ModEntry>,
        progress: DownloadProgress,
    ): CompletableFuture<Outcome<List<InstalledMod>, InstallError>> = CoroutineScope(Dispatchers.IO).future { install(entries, progress) }

    private fun files(
        entries: List<ModEntry>,
        records: Map<String, ModrinthVersion>,
    ): Outcome<List<ModrinthFile>, InstallError> {
        val checked = entries.map { entry -> entry to records.getValue(entry.versionId).fileFor(entry) }
        val mismatches = checked.mapNotNull { (entry, outcome) -> (outcome as? Outcome.Err)?.let { entry.projectId to it.error } }.toMap()
        return if (mismatches.isEmpty()) {
            Outcome.Ok(checked.map { (_, outcome) -> (outcome as Outcome.Ok).value })
        } else {
            Outcome.Err(InstallError.Mismatches(mismatches))
        }
    }

    /** Defaults, for callers that cannot use default arguments. */
    public companion object {
        /** Files downloaded at the same time by default. */
        public const val DEFAULT_PARALLELISM: Int = 4
        private const val JAR = ".jar"
    }
}
