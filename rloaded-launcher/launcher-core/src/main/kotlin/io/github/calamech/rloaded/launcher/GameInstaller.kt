package io.github.calamech.rloaded.launcher

import io.github.calamech.rloaded.core.Outcome
import io.github.calamech.rloaded.core.download.ArtifactStore
import io.github.calamech.rloaded.core.download.Download
import io.github.calamech.rloaded.core.download.DownloadError
import io.github.calamech.rloaded.core.download.DownloadFailures
import io.github.calamech.rloaded.core.download.Downloads
import io.github.calamech.rloaded.core.download.Http
import io.github.calamech.rloaded.core.flatMap
import io.github.calamech.rloaded.core.mapError
import io.github.calamech.rloaded.core.mojang.Asset
import io.github.calamech.rloaded.core.mojang.GameVersion
import io.github.calamech.rloaded.core.mojang.MetadataError
import io.github.calamech.rloaded.core.mojang.MojangMetadata
import io.github.calamech.rloaded.core.mojang.Platform
import io.github.calamech.rloaded.core.runtime.InstallError
import io.github.calamech.rloaded.core.runtime.JetBrainsRuntimeInstaller
import io.github.calamech.rloaded.core.runtime.RuntimePins
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories

/** Everything needed to start the game, on disk and verified. */
public data class InstalledGame(
    /** The Java executable of the pinned JetBrains Runtime. */
    val java: Path,
    /** Fabric Loader and its libraries, then the game libraries, then the client jar. */
    val classpath: List<Path>,
    /** The base set of mods. */
    val mods: List<Path>,
    /** The client logging configuration, when the version has one. */
    val loggingConfig: Path?,
    val game: GameVersion,
)

/** Why the game could not be installed. */
public sealed interface InstallFailure {
    /** The JetBrains Runtime could not be installed. */
    public data class Runtime(
        val error: InstallError,
    ) : InstallFailure

    /** The game metadata could not be read. */
    public data class Metadata(
        val error: MetadataError,
    ) : InstallFailure

    /** Some files could not be downloaded; every failure is listed. */
    public data class Downloads(
        val failures: List<DownloadError>,
        val total: Int,
    ) : InstallFailure

    /** A file could not be written. */
    public data class Storage(
        val path: Path,
        val reason: String,
    ) : InstallFailure
}

/** Where Mojang's metadata and assets are downloaded from. */
public data class MojangEndpoints(
    val manifestUrl: String = MojangMetadata.MANIFEST_URL,
    val resourcesUrl: String = MojangMetadata.RESOURCES_URL,
)

/** Installs the pinned runtime and the game files into a [ClientDirectories] layout. */
public class GameInstaller(
    private val http: Http,
    private val directories: ClientDirectories,
    private val platform: Platform,
    private val pins: RuntimePins,
    private val parallelism: Int = DEFAULT_PARALLELISM,
    endpoints: MojangEndpoints = MojangEndpoints(),
) {
    private val metadataStore = ArtifactStore(directories.metadata, http)
    private val libraries = ArtifactStore(directories.libraries, http)
    private val mods = ArtifactStore(directories.mods, http)
    private val assets = ArtifactStore(directories.assets.resolve("objects"), http)
    private val mojang = MojangMetadata(http, metadataStore, endpoints.manifestUrl, endpoints.resourcesUrl)
    private val runtime = JetBrainsRuntimeInstaller(ArtifactStore(directories.runtimeArchives, http), directories.runtimes, platform.os)

    /**
     * Installs everything the game needs, keeping launch arguments whose rules accept [features].
     * [onProgress] receives the number of files done and the total.
     */
    public suspend fun install(
        features: Set<String>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Outcome<InstalledGame, InstallFailure> =
        runtime
            .install(pins.jetBrainsRuntime(platform), pins.jetBrainsRuntimeVersion)
            .mapError<Path, InstallError, InstallFailure> { InstallFailure.Runtime(it) }
            .flatMap { java ->
                mojang
                    .version(pins.minecraftVersion, platform, features)
                    .mapError<GameVersion, MetadataError, InstallFailure> { InstallFailure.Metadata(it) }
                    .flatMap { game -> installGame(java, game, onProgress) }
            }

    private suspend fun installGame(
        java: Path,
        game: GameVersion,
        onProgress: (Int, Int) -> Unit,
    ): Outcome<InstalledGame, InstallFailure> =
        mojang
            .assets(game)
            .mapError<List<Asset>, MetadataError, InstallFailure> { InstallFailure.Metadata(it) }
            .flatMap { gameAssets ->
                val classpath = pins.fabricLoaderLibraries + game.libraries + game.client
                val downloads =
                    classpath.map { Download(libraries, it, ".jar") } +
                        pins.baseSet.map { Download(mods, it.artifact, ".jar") } +
                        listOfNotNull(game.logging?.let { Download(libraries, it.config, ".xml") }) +
                        gameAssets.map { Download(assets, it.artifact) }
                Downloads
                    .obtainAll(downloads, parallelism, onProgress)
                    .mapError<List<Path>, DownloadFailures, InstallFailure> { InstallFailure.Downloads(it.failures, it.total) }
                    .flatMap { paths ->
                        copyAssetIndex(game).flatMap {
                            Outcome.Ok(
                                InstalledGame(
                                    java = java,
                                    classpath = paths.take(classpath.size),
                                    mods = paths.drop(classpath.size).take(pins.baseSet.size),
                                    loggingConfig = if (game.logging == null) null else paths[classpath.size + pins.baseSet.size],
                                    game = game,
                                ),
                            )
                        }
                    }
            }

    // The game looks for its asset index by id, under assets/indexes.
    private suspend fun copyAssetIndex(game: GameVersion): Outcome<Unit, InstallFailure> =
        withContext(Dispatchers.IO) {
            val target = directories.assets.resolve("indexes").resolve("${game.assetIndexId}.json")
            try {
                target.parent.createDirectories()
                Files.copy(metadataStore.pathOf(game.assetIndex.checksum, ".json"), target, StandardCopyOption.REPLACE_EXISTING)
                Outcome.Ok(Unit)
            } catch (e: IOException) {
                Outcome.Err(InstallFailure.Storage(target, e.message ?: e.javaClass.simpleName))
            }
        }

    private companion object {
        const val DEFAULT_PARALLELISM = 16
    }
}
