package io.github.calamech.rloaded.core.download

import io.github.calamech.rloaded.protocol.Checksum
import java.nio.file.Path

/** Why a download failed. [retryable] tells whether trying again may succeed. */
public sealed interface DownloadError {
    /** Whether the same request may succeed later. */
    public val retryable: Boolean

    /** The URL, or a redirect target, is not allowed by the [UrlPolicy]. Never retried. */
    public data class Refused(
        val url: String,
        val reason: String,
    ) : DownloadError {
        override val retryable: Boolean get() = false
    }

    /** The server could not be reached, or stopped answering in time. */
    public data class Unreachable(
        val url: String,
        val reason: String,
    ) : DownloadError {
        override val retryable: Boolean get() = true
    }

    /** The server answered with a status other than 2xx. */
    public data class HttpStatus(
        val url: String,
        val status: Int,
    ) : DownloadError {
        override val retryable: Boolean get() = status >= SERVER_ERRORS || status in RETRYABLE_CLIENT_ERRORS
    }

    /** The body is larger than allowed. */
    public data class TooLarge(
        val url: String,
        val limit: Long,
    ) : DownloadError {
        override val retryable: Boolean get() = false
    }

    /** The file does not have the expected size. */
    public data class SizeMismatch(
        val url: String,
        val expected: Long,
        val actual: Long,
    ) : DownloadError {
        override val retryable: Boolean get() = false
    }

    /** The file does not have the expected hash. Never retried: the source serves a different file. */
    public data class ChecksumMismatch(
        val url: String,
        val expected: Checksum,
        val actual: Checksum,
    ) : DownloadError {
        override val retryable: Boolean get() = false
    }

    /** The file could not be written to disk. */
    public data class Storage(
        val path: Path,
        val reason: String,
    ) : DownloadError {
        override val retryable: Boolean get() = false
    }

    private companion object {
        const val SERVER_ERRORS = 500
        val RETRYABLE_CLIENT_ERRORS = setOf(408, 429)
    }
}
