package io.github.calamech.rloaded.core.runtime

import io.github.calamech.rloaded.core.Outcome
import io.github.calamech.rloaded.core.download.Artifact
import io.github.calamech.rloaded.core.download.Checksum
import io.github.calamech.rloaded.core.download.HashAlgorithm
import io.github.calamech.rloaded.core.mojang.Architecture
import io.github.calamech.rloaded.core.mojang.OperatingSystem
import io.github.calamech.rloaded.core.mojang.Platform
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A mod file from Modrinth. */
public data class ModFile(
    val projectId: String,
    val versionId: String,
    val fileName: String,
    val artifact: Artifact,
)

/**
 * The exact runtime a Rloaded release starts the game with: Fabric Loader and its libraries,
 * the base set of mods, and the JetBrains Runtime. Every file is pinned by its SHA-512 in
 * `runtime-pins.json`, recorded from Fabric's Maven repository, Modrinth and the JetBrains Runtime
 * release page.
 */
public class RuntimePins private constructor(
    /** The only Minecraft version Rloaded supports. */
    public val minecraftVersion: String,
    /** The Fabric Loader version, the same on the client and the server. */
    public val fabricLoaderVersion: String,
    /** Fabric Loader and its libraries, as listed by Fabric's launcher profile. */
    public val fabricLoaderLibraries: List<Artifact>,
    /** Mods loaded at every start and never hot-loaded. */
    public val baseSet: List<ModFile>,
    /** The JetBrains Runtime version, build number included. */
    public val jetBrainsRuntimeVersion: String,
    private val jetBrainsRuntimes: Map<Platform, Artifact>,
) {
    /** The JetBrains Runtime archive for [platform]. Every supported platform has one. */
    public fun jetBrainsRuntime(platform: Platform): Artifact = jetBrainsRuntimes.getValue(platform)

    /** The pins of this release. */
    public companion object {
        /** The client entry point of Fabric Loader. */
        public const val FABRIC_CLIENT_MAIN_CLASS: String = "net.fabricmc.loader.impl.launch.knot.KnotClient"

        /** The server entry point of Fabric Loader. */
        public const val FABRIC_SERVER_MAIN_CLASS: String = "net.fabricmc.loader.impl.launch.knot.KnotServer"

        /** Pins shipped with this build. A broken file is a build defect, so it fails at first use. */
        public val CURRENT: RuntimePins by lazy {
            val text =
                checkNotNull(RuntimePins::class.java.getResource("runtime-pins.json")) { "runtime-pins.json is missing" }.readText()
            parse(text)
        }

        internal fun parse(text: String): RuntimePins {
            val file = Json.decodeFromString<PinsFile>(text)
            val platforms = OperatingSystem.entries.flatMap { os -> Architecture.entries.map { Platform(os, it) } }
            return RuntimePins(
                minecraftVersion = file.minecraft,
                fabricLoaderVersion = file.fabricLoader.version,
                fabricLoaderLibraries = file.fabricLoader.libraries.map { artifact(it.url, it.size, it.sha512) },
                baseSet = file.baseSet.map { ModFile(it.projectId, it.versionId, it.fileName, artifact(it.url, it.size, it.sha512)) },
                jetBrainsRuntimeVersion = file.jetBrainsRuntime.version,
                jetBrainsRuntimes =
                    platforms.associateWith { platform ->
                        val archive =
                            checkNotNull(file.jetBrainsRuntime.archives[key(platform)]) { "No JetBrains Runtime pinned for $platform" }
                        artifact(archive.url, null, archive.sha512)
                    },
            )
        }

        private fun key(platform: Platform): String = "${platform.os.name.lowercase()}-${platform.architecture.name.lowercase()}"

        private fun artifact(
            url: String,
            size: Long?,
            sha512: String,
        ): Artifact =
            when (val checksum = Checksum.parse(HashAlgorithm.SHA512, sha512)) {
                is Outcome.Ok -> Artifact(url, checksum.value, size)
                is Outcome.Err -> error("Invalid pinned SHA-512 for $url")
            }
    }
}

// Rloaded's own file, so unknown fields are refused rather than ignored.

@Serializable
internal data class PinsFile(
    val recorded: String,
    val minecraft: String,
    val fabricLoader: FabricLoaderPins,
    val baseSet: List<ModPin>,
    val jetBrainsRuntime: JetBrainsRuntimePins,
)

@Serializable
internal data class FabricLoaderPins(
    val version: String,
    val libraries: List<FilePin>,
)

@Serializable
internal data class FilePin(
    val url: String,
    val size: Long,
    val sha512: String,
)

@Serializable
internal data class ModPin(
    val projectId: String,
    val versionId: String,
    val fileName: String,
    val url: String,
    val size: Long,
    val sha512: String,
)

@Serializable
internal data class JetBrainsRuntimePins(
    val version: String,
    val archives: Map<String, ArchivePin>,
)

@Serializable
internal data class ArchivePin(
    val url: String,
    val sha512: String,
)
