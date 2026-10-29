package io.github.calamech.rloaded.core.mojang

import io.github.calamech.rloaded.core.Outcome
import io.github.calamech.rloaded.core.download.Artifact
import io.github.calamech.rloaded.core.download.ArtifactStore
import io.github.calamech.rloaded.core.download.DownloadError
import io.github.calamech.rloaded.core.download.Http
import io.github.calamech.rloaded.core.flatMap
import io.github.calamech.rloaded.core.mapError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Reads Mojang's launcher metadata. The version manifest is fetched each time to find a version;
 * the version file and the asset index are hash-checked and kept in [store].
 */
public class MojangMetadata(
    private val http: Http,
    private val store: ArtifactStore,
    private val manifestUrl: String = MANIFEST_URL,
    private val resourcesUrl: String = RESOURCES_URL,
) {
    /** Resolves version [id] for [platform], keeping the arguments whose rules accept [features]. */
    public suspend fun version(
        id: String,
        platform: Platform,
        features: Set<String>,
    ): Outcome<GameVersion, MetadataError> =
        http
            .text(manifestUrl, MAX_MANIFEST_BYTES)
            .mapError { MetadataError.Download(it) }
            .flatMap { Codec.decode<VersionManifestJson>("version manifest", it) }
            .flatMap { manifest ->
                manifest.versions.find { it.id == id }?.let { Outcome.Ok(it) }
                    ?: Outcome.Err(MetadataError.UnknownVersion(id))
            }.flatMap { Resolution.artifact("version manifest", it.url, it.sha1, null) }
            .flatMap { obtainText(it, "version $id") }
            .flatMap { Codec.decode<VersionJson>("version $id", it) }
            .flatMap { Resolution.resolve(it, platform, features) }

    /** Lists the assets of [version], from its hash-checked asset index. */
    public suspend fun assets(version: GameVersion): Outcome<List<Asset>, MetadataError> {
        val source = "asset index ${version.assetIndexId}"
        return obtainText(version.assetIndex, source)
            .flatMap { Codec.decode<AssetIndexJson>(source, it) }
            .flatMap { Resolution.assets(it, resourcesUrl) }
    }

    private suspend fun obtainText(
        artifact: Artifact,
        source: String,
    ): Outcome<String, MetadataError> =
        store
            .obtain(artifact, ".json")
            .mapError { MetadataError.Download(it) }
            .flatMap { read(it, source) }

    private suspend fun read(
        path: Path,
        source: String,
    ): Outcome<String, MetadataError> =
        withContext(Dispatchers.IO) {
            try {
                Outcome.Ok(path.readText())
            } catch (e: IOException) {
                Outcome.Err(MetadataError.Download(DownloadError.Storage(path, e.message ?: "cannot read $source")))
            }
        }

    /** Mojang's endpoints. */
    public companion object {
        /** The list of every game version. */
        public const val MANIFEST_URL: String = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

        /** Where assets are served, by hash. */
        public const val RESOURCES_URL: String = "https://resources.download.minecraft.net"

        private const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024
    }
}
