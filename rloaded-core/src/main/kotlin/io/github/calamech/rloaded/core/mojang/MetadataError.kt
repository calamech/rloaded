package io.github.calamech.rloaded.core.mojang

import io.github.calamech.rloaded.core.download.DownloadError

/** Why game metadata could not be obtained. */
public sealed interface MetadataError {
    /** A metadata file could not be downloaded. */
    public data class Download(
        val error: DownloadError,
    ) : MetadataError

    /** A metadata file does not have the expected shape. */
    public data class Malformed(
        val source: String,
        val reason: String,
    ) : MetadataError

    /** The version manifest does not list this version. */
    public data class UnknownVersion(
        val id: String,
    ) : MetadataError
}
