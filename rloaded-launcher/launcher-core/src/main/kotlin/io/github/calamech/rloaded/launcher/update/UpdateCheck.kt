package io.github.calamech.rloaded.launcher.update

import io.github.calamech.rloaded.core.download.Artifact
import io.github.calamech.rloaded.core.download.DownloadError
import io.github.calamech.rloaded.core.download.Http
import io.github.calamech.rloaded.core.mojang.Architecture
import io.github.calamech.rloaded.core.mojang.OperatingSystem
import io.github.calamech.rloaded.core.mojang.Platform
import io.github.calamech.rloaded.protocol.Checksum
import io.github.calamech.rloaded.protocol.HashAlgorithm
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.flatMap
import io.github.calamech.rloaded.protocol.map
import io.github.calamech.rloaded.protocol.mapError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Where releases are listed and where their files may come from. */
public data class ReleaseSource(
    /** The latest release, as the GitHub API describes it. */
    val latestUrl: String,
    /** Every file of a release must be downloaded from under this prefix. */
    val downloadPrefix: String,
) {
    /** Sources used by Rloaded. */
    public companion object {
        /** The releases of the Rloaded repository on GitHub. */
        public val GITHUB: ReleaseSource =
            ReleaseSource(
                "https://api.github.com/repos/calamech/rloaded/releases/latest",
                "https://github.com/calamech/rloaded/releases/download/",
            )
    }
}

/** A newer release than this launcher, with the installer for this platform. */
public data class Update(
    val version: Version,
    /** The file name of the installer, as the release lists it. */
    val fileName: String,
    /** The installer, with the SHA-256 that the release's `SHA256SUMS` gives for it. */
    val installer: Artifact,
)

/** Why the update check could not tell whether a newer release exists. */
public sealed interface UpdateError {
    /** The release list or its sums could not be read. */
    public data class Unreachable(
        val error: DownloadError,
    ) : UpdateError

    /** The release does not look like a Rloaded release. */
    public data class Malformed(
        val reason: String,
    ) : UpdateError

    /** The release has no installer for this platform. */
    public data class NoInstaller(
        val fileName: String,
    ) : UpdateError
}

/**
 * Asks [source] for the latest release and returns it when it is newer than [current] and has an
 * installer for [platform]. A platform without an installer of its own never gets an update.
 */
public class UpdateCheck(
    private val http: Http,
    private val source: ReleaseSource,
    private val current: Version,
    private val platform: Platform,
) {
    /** The newer release, or `null` when this launcher is up to date. */
    public suspend fun check(): Outcome<Update?, UpdateError> {
        val suffix = installerSuffix(platform) ?: return Outcome.Ok(null)
        return http
            .text(source.latestUrl, MAX_RELEASE_BYTES)
            .mapError<String, DownloadError, UpdateError> { UpdateError.Unreachable(it) }
            .flatMap(::release)
            .flatMap { release ->
                val version = Version.ofTag(release.tag)
                when {
                    version == null -> Outcome.Err(UpdateError.Malformed("the latest release is tagged ${release.tag}"))
                    version <= current -> Outcome.Ok(null)
                    else -> update(release, version, "Rloaded-$version-$suffix")
                }
            }
    }

    private suspend fun update(
        release: ReleaseJson,
        version: Version,
        fileName: String,
    ): Outcome<Update?, UpdateError> {
        val installer = release.assets.find { it.name == fileName } ?: return Outcome.Err(UpdateError.NoInstaller(fileName))
        val sums = release.assets.find { it.name == SUMS } ?: return Outcome.Err(UpdateError.Malformed("the release has no $SUMS"))
        val outside = listOf(installer, sums).find { !it.url.startsWith(source.downloadPrefix) }
        if (outside != null) return Outcome.Err(UpdateError.Malformed("${outside.name} is served from ${outside.url}"))
        return http
            .text(sums.url, MAX_SUMS_BYTES)
            .mapError<String, DownloadError, UpdateError> { UpdateError.Unreachable(it) }
            .flatMap { text -> checksumOf(text, fileName) }
            .map { checksum -> Update(version, fileName, Artifact(installer.url, checksum, installer.size)) }
    }

    private fun release(text: String): Outcome<ReleaseJson, UpdateError> =
        try {
            Outcome.Ok(JSON.decodeFromString(ReleaseJson.serializer(), text))
        } catch (e: SerializationException) {
            Outcome.Err(UpdateError.Malformed("the release cannot be read: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            Outcome.Err(UpdateError.Malformed("the release cannot be read: ${e.message}"))
        }

    private companion object {
        const val SUMS = "SHA256SUMS"
        const val MAX_RELEASE_BYTES = 1L * 1024 * 1024
        const val MAX_SUMS_BYTES = 64L * 1024

        // GitHub describes a release with many fields; only these matter here.
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** The end of the installer's name for [platform], or `null` when no installer is built for it. */
internal fun installerSuffix(platform: Platform): String? =
    when (platform.os to platform.architecture) {
        OperatingSystem.MACOS to Architecture.ARM64 -> "macos-arm64.dmg"
        OperatingSystem.MACOS to Architecture.X86_64 -> "macos-x64.dmg"
        OperatingSystem.WINDOWS to Architecture.X86_64 -> "windows-x64.msi"
        OperatingSystem.LINUX to Architecture.X86_64 -> "linux-x64.deb"
        else -> null
    }

/** Reads the line of `sha256sum` output that names [fileName]. */
internal fun checksumOf(
    sums: String,
    fileName: String,
): Outcome<Checksum, UpdateError> {
    val line =
        sums.lines().map { it.trim().split(Regex("\\s+\\*?"), limit = 2) }.find { it.size == 2 && it[1] == fileName }
            ?: return Outcome.Err(UpdateError.Malformed("SHA256SUMS has no line for $fileName"))
    return Checksum
        .parse(HashAlgorithm.SHA256, line[0])
        .mapError { UpdateError.Malformed("SHA256SUMS has an invalid hash for $fileName") }
}

@Serializable
private class ReleaseJson(
    @SerialName("tag_name") val tag: String,
    val assets: List<AssetJson>,
)

@Serializable
private class AssetJson(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long,
)
