package io.github.calamech.rloaded.core.download

import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readText
import kotlin.test.assertEquals

class DownloadsTest {
    private val server = TestServer()
    private val http = KtorHttp(server.policy, HttpSettings())

    @TempDir
    lateinit var root: Path

    @AfterEach
    fun close() {
        http.close()
        server.close()
    }

    @Test
    fun `returns the paths in the order of the downloads`() =
        runBlocking {
            val store = ArtifactStore(root, http)
            val downloads = (1..20).map { Download(store, serve("/file$it", "content $it")) }

            val paths = (Downloads.obtainAll(downloads, parallelism = 4) as Outcome.Ok).value

            assertEquals((1..20).map { "content $it" }, paths.map { it.readText() })
        }

    @Test
    fun `reports every failure and still finishes the other downloads`() =
        runBlocking {
            val store = ArtifactStore(root, http, RetryPolicy(maxAttempts = 1))
            val good = serve("/good", "good")
            val missing = Artifact(server.url("/missing"), good.checksum, good.size)
            val wrong = serve("/wrong", "wrong").copy(checksum = good.checksum)
            val progress = mutableListOf<Int>()

            val result =
                Downloads.obtainAll(listOf(Download(store, missing), Download(store, good), Download(store, wrong)), 2) { done, _ ->
                    synchronized(progress) { progress += done }
                }

            val failures = (result as Outcome.Err).error
            assertEquals(3, failures.total)
            assertEquals(2, failures.failures.size)
            assertEquals(1, server.hits("/good"))
            assertEquals(listOf(1, 2, 3), progress.sorted())
        }

    private fun serve(
        path: String,
        content: String,
    ): Artifact {
        val bytes = content.toByteArray()
        server.serve(path, bytes)
        val sha1 = MessageDigest.getInstance("SHA-1").digest(bytes).toHexString()
        return Artifact(server.url(path), (Checksum.parse(HashAlgorithm.SHA1, sha1) as Outcome.Ok).value, bytes.size.toLong())
    }
}
