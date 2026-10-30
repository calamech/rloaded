package io.github.calamech.rloaded.bootstrap

import io.github.calamech.rloaded.core.download.ArtifactStore
import io.github.calamech.rloaded.core.download.Download
import io.github.calamech.rloaded.core.download.DownloadFailures
import io.github.calamech.rloaded.core.download.Downloads
import io.github.calamech.rloaded.core.download.Http
import io.github.calamech.rloaded.core.mojang.GameVersion
import io.github.calamech.rloaded.core.mojang.MetadataError
import io.github.calamech.rloaded.core.mojang.MojangEndpoints
import io.github.calamech.rloaded.core.mojang.MojangMetadata
import io.github.calamech.rloaded.core.mojang.Platform
import io.github.calamech.rloaded.core.runtime.InstallError
import io.github.calamech.rloaded.core.runtime.JetBrainsRuntimeInstaller
import io.github.calamech.rloaded.core.runtime.RuntimePins
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.flatMap
import io.github.calamech.rloaded.protocol.map
import io.github.calamech.rloaded.protocol.mapError
import java.nio.file.Path

/** The pinned runtime, the server jar, Fabric Loader and the base set, on disk and verified. */
public data class InstalledServer(
    val java: Path,
    /** Fabric Loader and its libraries, then the server jar. */
    val classpath: List<Path>,
    val mods: List<Path>,
)

/** Installs what the server needs into the server's `rloaded/` folder. */
public class ServerInstaller(
    http: Http,
    data: Path,
    private val platform: Platform,
    private val pins: RuntimePins,
    endpoints: MojangEndpoints = MojangEndpoints(),
) {
    private val libraries = ArtifactStore(data.resolve("cache/libraries"), http)
    private val mods = ArtifactStore(data.resolve("cache/mods"), http)
    private val mojang = MojangMetadata(http, ArtifactStore(data.resolve("cache/metadata"), http), endpoints)
    private val runtime =
        JetBrainsRuntimeInstaller(ArtifactStore(data.resolve("cache/runtime-archives"), http), data.resolve("runtimes"), platform.os)

    /** Installs everything, describing a failure in words for the admin. */
    public suspend fun install(): Outcome<InstalledServer, String> =
        runtime
            .install(pins.jetBrainsRuntime(platform), pins.jetBrainsRuntimeVersion)
            .mapError<Path, InstallError, String> { "cannot install the Java runtime: $it" }
            .flatMap { java ->
                mojang
                    .version(pins.minecraftVersion, platform, emptySet())
                    .mapError<GameVersion, MetadataError, String> { "cannot read the game metadata: $it" }
                    .flatMap { game -> installFiles(java, game) }
            }

    private suspend fun installFiles(
        java: Path,
        game: GameVersion,
    ): Outcome<InstalledServer, String> {
        val classpath = pins.fabricLoaderLibraries + game.server
        val downloads = classpath.map { Download(libraries, it, ".jar") } + pins.baseSet.map { Download(mods, it.artifact, ".jar") }
        return Downloads
            .obtainAll(downloads, PARALLELISM)
            .mapError<List<Path>, DownloadFailures, String> { "${it.failures.size} of ${it.total} downloads failed: ${it.failures}" }
            .map { paths -> InstalledServer(java, paths.take(classpath.size), paths.drop(classpath.size)) }
    }

    private companion object {
        const val PARALLELISM = 8
    }
}
