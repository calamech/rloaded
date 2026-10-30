package io.github.calamech.rloaded.core.download

import io.github.calamech.rloaded.protocol.Outcome
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Narrow port over HTTP GET. Implementations check every URL and redirect target against a
 * [UrlPolicy], never retry on their own, and enforce [HttpSettings] timeouts.
 */
public interface Http {
    /** Writes the body of [url] to [target], refusing bodies over [maxBytes]. */
    public suspend fun download(
        url: String,
        target: Path,
        maxBytes: Long,
    ): Outcome<Unit, DownloadError>

    /** Returns the body of [url] as UTF-8 text, refusing bodies over [maxBytes]. */
    public suspend fun text(
        url: String,
        maxBytes: Long,
    ): Outcome<String, DownloadError>
}

/** Limits applied to every HTTP request. */
public data class HttpSettings(
    /** Time allowed to open a connection. */
    val connectTimeout: Duration = 10.seconds,
    /** Longest silence allowed between two reads while receiving a body. */
    val socketTimeout: Duration = 30.seconds,
    /** Time allowed for a whole request, body included. */
    val requestTimeout: Duration = 10.minutes,
    /** Redirects followed before giving up. */
    val maxRedirects: Int = 5,
    /** Sent with every request; Modrinth requires one that identifies the application. */
    val userAgent: String = "calamech/rloaded (github.com/calamech/rloaded)",
)
