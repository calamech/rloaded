package io.github.calamech.rloaded.core.download

import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class KtorHttpTest {
    private val server = TestServer()
    private val http = KtorHttp(server.policy, HttpSettings(socketTimeout = 300.milliseconds))

    @AfterEach
    fun close() {
        http.close()
        server.close()
    }

    @Test
    fun `downloads a body to a file`(
        @TempDir dir: Path,
    ) = runBlocking {
        server.serve("/file", "content".toByteArray())
        val target = dir.resolve("file")

        assertEquals(Outcome.Ok(Unit), http.download(server.url("/file"), target, 100))
        assertEquals("content", target.readText())
    }

    @Test
    fun `returns a body as text`() =
        runBlocking {
            server.serve("/text", "héllo".toByteArray())

            assertEquals(Outcome.Ok("héllo"), http.text(server.url("/text"), 100))
        }

    @Test
    fun `follows a redirect to an allowed origin`() =
        runBlocking {
            server.route("/old") { exchange, _ -> exchange.redirect("/new") }
            server.serve("/new", "moved".toByteArray())

            assertEquals(Outcome.Ok("moved"), http.text(server.url("/old"), 100))
        }

    @Test
    fun `refuses a URL outside the policy without sending a request`() =
        runBlocking {
            val result = http.text("https://example.com/file", 100)

            assertEquals(
                Outcome.Err(DownloadError.Refused("https://example.com/file", "https://example.com:443 is not an allowed origin")),
                result,
            )
        }

    @Test
    fun `refuses a redirect outside the policy`() =
        runBlocking {
            server.route("/escape") { exchange, _ -> exchange.redirect("https://example.com/file") }

            val result = http.text(server.url("/escape"), 100)

            assertEquals(
                Outcome.Err(DownloadError.Refused("https://example.com/file", "https://example.com:443 is not an allowed origin")),
                result,
            )
        }

    @Test
    fun `stops after too many redirects`() =
        runBlocking {
            server.route("/loop") { exchange, _ -> exchange.redirect("/loop") }

            val result = http.text(server.url("/loop"), 100)

            assertEquals(Outcome.Err(DownloadError.Refused(server.url("/loop"), "more than 5 redirects")), result)
            assertEquals(6, server.hits("/loop"))
        }

    @Test
    fun `reports the status of a failed request`() =
        runBlocking {
            server.serve("/missing", ByteArray(0), status = 404)
            server.serve("/down", ByteArray(0), status = 503)

            val missing = http.text(server.url("/missing"), 100)
            val down = http.text(server.url("/down"), 100)

            assertEquals(Outcome.Err(DownloadError.HttpStatus(server.url("/missing"), 404)), missing)
            assertEquals(false, (missing as Outcome.Err).error.retryable)
            assertEquals(true, (down as Outcome.Err).error.retryable)
        }

    @Test
    fun `refuses a body larger than the limit`(
        @TempDir dir: Path,
    ) = runBlocking {
        server.serve("/big", ByteArray(1000))

        val result = http.download(server.url("/big"), dir.resolve("big"), 999)

        assertEquals(Outcome.Err(DownloadError.TooLarge(server.url("/big"), 999)), result)
    }

    @Test
    fun `refuses a streamed body that grows past the limit`() =
        runBlocking {
            server.route("/stream") { exchange, _ ->
                exchange.sendResponseHeaders(200, 0)
                repeat(10) { exchange.responseBody.write(ByteArray(100)) }
            }

            val result = http.text(server.url("/stream"), 999)

            assertEquals(Outcome.Err(DownloadError.TooLarge(server.url("/stream"), 999)), result)
        }

    @Test
    fun `gives up on a server that stops sending`() =
        runBlocking {
            server.route("/stall") { exchange, _ ->
                exchange.sendResponseHeaders(200, 10)
                exchange.responseBody.write(ByteArray(1))
                exchange.responseBody.flush()
                Thread.sleep(2_000)
            }

            val result = http.text(server.url("/stall"), 100)

            assertEquals(true, result is Outcome.Err && result.error is DownloadError.Unreachable)
        }

    @Test
    fun `reports a server that refuses connections`() =
        runBlocking {
            val port = ServerSocket(0).use { it.localPort }
            val closed = KtorHttp(UrlPolicy(setOf(Origin("http", "127.0.0.1", port))), HttpSettings())

            val result = closed.use { it.text("http://127.0.0.1:$port/file", 100) }

            assertEquals(true, result is Outcome.Err && result.error is DownloadError.Unreachable)
        }
}
