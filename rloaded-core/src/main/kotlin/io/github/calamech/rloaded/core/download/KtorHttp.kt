package io.github.calamech.rloaded.core.download

import io.github.calamech.rloaded.protocol.Outcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.UnresolvedAddressException
import java.nio.file.Path
import kotlin.io.path.outputStream

/**
 * [Http] over the Ktor CIO engine. This is the only file that touches Ktor's client API.
 *
 * Redirects are followed here rather than by Ktor, so that each target goes through [policy].
 */
public class KtorHttp(
    private val policy: UrlPolicy,
    private val settings: HttpSettings,
) : Http,
    AutoCloseable {
    private val client =
        HttpClient(CIO) {
            followRedirects = false
            expectSuccess = false
            engine {
                endpoint.connectAttempts = 1
                requestTimeout = settings.requestTimeout.inWholeMilliseconds
            }
            install(HttpTimeout) {
                connectTimeoutMillis = settings.connectTimeout.inWholeMilliseconds
                socketTimeoutMillis = settings.socketTimeout.inWholeMilliseconds
                requestTimeoutMillis = settings.requestTimeout.inWholeMilliseconds
            }
            install(UserAgent) {
                agent = settings.userAgent
            }
        }

    override suspend fun download(
        url: String,
        target: Path,
        maxBytes: Long,
    ): Outcome<Unit, DownloadError> =
        fetch(url, maxBytes) { finalUrl, body ->
            try {
                target.outputStream().use { copy(finalUrl, body, it, maxBytes) }
            } catch (e: IOException) {
                Outcome.Err(DownloadError.Storage(target, e.message ?: e.javaClass.simpleName))
            }
        }

    override suspend fun text(
        url: String,
        maxBytes: Long,
    ): Outcome<String, DownloadError> =
        fetch(url, maxBytes) { finalUrl, body ->
            val buffer = ByteArrayOutputStream()
            when (val copied = copy(finalUrl, body, buffer, maxBytes)) {
                is Outcome.Ok -> Outcome.Ok(buffer.toString(Charsets.UTF_8))
                is Outcome.Err -> copied
            }
        }

    override fun close() {
        client.close()
    }

    private suspend fun <T> fetch(
        url: String,
        maxBytes: Long,
        read: suspend (String, ByteReadChannel) -> Outcome<T, DownloadError>,
    ): Outcome<T, DownloadError> {
        var current = url
        var redirects = 0
        while (true) {
            val refusal = policy.refusalReason(current)
            val step =
                if (refusal ==
                    null
                ) {
                    request(current, maxBytes, read)
                } else {
                    Step.Done(Outcome.Err(DownloadError.Refused(current, refusal)))
                }
            if (step is Step.Done) return step.outcome
            if (redirects == settings.maxRedirects) {
                return Outcome.Err(DownloadError.Refused(url, "more than ${settings.maxRedirects} redirects"))
            }
            redirects++
            current = (step as Step.Redirect).location
        }
    }

    private suspend fun <T> request(
        url: String,
        maxBytes: Long,
        read: suspend (String, ByteReadChannel) -> Outcome<T, DownloadError>,
    ): Step<T> =
        try {
            client.prepareGet(url).execute { response -> handle(url, response, maxBytes, read) }
        } catch (e: IOException) {
            Step.Done(Outcome.Err(DownloadError.Unreachable(url, e.message ?: e.javaClass.simpleName)))
        } catch (_: UnresolvedAddressException) {
            Step.Done(Outcome.Err(DownloadError.Unreachable(url, "unknown host")))
        }

    private suspend fun <T> handle(
        url: String,
        response: HttpResponse,
        maxBytes: Long,
        read: suspend (String, ByteReadChannel) -> Outcome<T, DownloadError>,
    ): Step<T> {
        val status = response.status.value
        val location = response.headers[HttpHeaders.Location]
        return when {
            status in REDIRECTS && location != null -> {
                Step.Redirect(URLBuilder(url).takeFrom(location).buildString())
            }

            status !in SUCCESS -> {
                Step.Done(Outcome.Err(DownloadError.HttpStatus(url, status)))
            }

            (response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > maxBytes -> {
                Step.Done(Outcome.Err(DownloadError.TooLarge(url, maxBytes)))
            }

            else -> {
                Step.Done(read(url, response.bodyAsChannel()))
            }
        }
    }

    private suspend fun copy(
        url: String,
        body: ByteReadChannel,
        out: OutputStream,
        maxBytes: Long,
    ): Outcome<Unit, DownloadError> {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = body.readAvailable(buffer)
            if (read == -1) return Outcome.Ok(Unit)
            total += read
            if (total > maxBytes) return Outcome.Err(DownloadError.TooLarge(url, maxBytes))
            withContext(Dispatchers.IO) { out.write(buffer, 0, read) }
        }
    }

    private sealed interface Step<out T> {
        data class Done<T>(
            val outcome: Outcome<T, DownloadError>,
        ) : Step<T>

        data class Redirect(
            val location: String,
        ) : Step<Nothing>
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        val SUCCESS = 200..299
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
