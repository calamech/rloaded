package io.github.calamech.rloaded.core.download

import io.github.calamech.rloaded.protocol.Checksum
import io.github.calamech.rloaded.protocol.HashAlgorithm
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds

class ArtifactStoreTest {
    private val server = TestServer()
    private val http = KtorHttp(server.policy, HttpSettings())
    private val fastRetry = RetryPolicy(maxAttempts = 3, initialBackoff = 1.milliseconds, maxBackoff = 2.milliseconds)

    @TempDir
    lateinit var root: Path

    @AfterEach
    fun close() {
        http.close()
        server.close()
    }

    @Test
    fun `downloads a file under its hash`() =
        runBlocking {
            server.serve("/a.jar", CONTENT)
            val store = ArtifactStore(root, http, fastRetry)

            val path = assertOk(store.obtain(artifact("/a.jar"), suffix = ".jar"))

            assertEquals(root.resolve(SHA1.hex.take(2)).resolve("${SHA1.hex}.jar"), path)
            assertEquals("library content", path.readText())
        }

    @Test
    fun `downloads a stored file only once`() =
        runBlocking {
            server.serve("/a.jar", CONTENT)
            val store = ArtifactStore(root, http, fastRetry)

            store.obtain(artifact("/a.jar"))
            store.obtain(artifact("/a.jar"))

            assertEquals(1, server.hits("/a.jar"))
        }

    @Test
    fun `refuses a file with another hash and keeps nothing`() =
        runBlocking {
            server.serve("/a.jar", "tampered content".toByteArray())
            val store = ArtifactStore(root, http, fastRetry)

            val result = store.obtain(artifact("/a.jar", size = null))

            val error = (result as Outcome.Err).error
            assertEquals(true, error is DownloadError.ChecksumMismatch)
            assertEquals(1, server.hits("/a.jar"))
            assertFalse(store.pathOf(SHA1).exists())
            assertEquals(emptyList(), store.pathOf(SHA1).parent.listDirectoryEntries())
        }

    @Test
    fun `refuses a file larger than its published size`() =
        runBlocking {
            server.serve("/a.jar", CONTENT)
            val store = ArtifactStore(root, http, fastRetry)

            val result = store.obtain(artifact("/a.jar", size = CONTENT.size - 1L))

            assertEquals(Outcome.Err(DownloadError.TooLarge(server.url("/a.jar"), CONTENT.size - 1L)), result)
        }

    @Test
    fun `refuses a truncated file`() =
        runBlocking {
            server.serve("/a.jar", CONTENT.copyOf(CONTENT.size - 1))
            val store = ArtifactStore(root, http, fastRetry)

            val result = store.obtain(artifact("/a.jar"))

            assertEquals(Outcome.Err(DownloadError.SizeMismatch(server.url("/a.jar"), CONTENT.size.toLong(), CONTENT.size - 1L)), result)
        }

    @Test
    fun `retries a server error until the download succeeds`() =
        runBlocking {
            server.route("/flaky") { exchange, count -> if (count < 3) exchange.respond(503) else exchange.respond(200, CONTENT) }
            val store = ArtifactStore(root, http, fastRetry)

            assertOk(store.obtain(artifact("/flaky")))
            assertEquals(3, server.hits("/flaky"))
        }

    @Test
    fun `stops retrying after the last attempt`() =
        runBlocking {
            server.serve("/down", ByteArray(0), status = 503)
            val store = ArtifactStore(root, http, fastRetry)

            val result = store.obtain(artifact("/down"))

            assertEquals(Outcome.Err(DownloadError.HttpStatus(server.url("/down"), 503)), result)
            assertEquals(3, server.hits("/down"))
        }

    @Test
    fun `does not retry a missing file`() =
        runBlocking {
            server.serve("/missing", ByteArray(0), status = 404)
            val store = ArtifactStore(root, http, fastRetry)

            store.obtain(artifact("/missing"))

            assertEquals(1, server.hits("/missing"))
        }

    @Test
    fun `reports a store it cannot write to`() =
        runBlocking {
            server.serve("/a.jar", CONTENT)
            val blocked = Files.writeString(root.resolve("blocked"), "a file where a directory should be")
            val store = ArtifactStore(blocked, http, fastRetry)

            val result = store.obtain(artifact("/a.jar"))

            assertEquals(true, result is Outcome.Err && result.error is DownloadError.Storage)
            assertEquals(0, server.hits("/a.jar"))
        }

    private fun artifact(
        path: String,
        size: Long? = CONTENT.size.toLong(),
    ) = Artifact(server.url(path), SHA1, size)

    private fun <T> assertOk(outcome: Outcome<T, DownloadError>): T {
        assertEquals(true, outcome is Outcome.Ok, "Expected success, got $outcome")
        return (outcome as Outcome.Ok).value
    }

    private companion object {
        val CONTENT = "library content".toByteArray()
        val SHA1 =
            (
                Checksum.parse(
                    HashAlgorithm.SHA1,
                    MessageDigest.getInstance("SHA-1").digest(CONTENT).toHexString(),
                ) as Outcome.Ok
            ).value
    }
}
