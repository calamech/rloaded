package io.github.calamech.rloaded.core.mojang

import io.github.calamech.rloaded.core.download.ArtifactStore
import io.github.calamech.rloaded.core.download.DownloadError
import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.TestServer
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals

class MojangMetadataTest {
    private val server = TestServer()
    private val http = KtorHttp(server.policy, HttpSettings())
    private val platform = Platform(OperatingSystem.LINUX, Architecture.X86_64)
    private val versionFile = javaClass.getResource("/mojang/26.3.json")!!.readBytes()

    @TempDir
    lateinit var root: Path

    @AfterEach
    fun close() {
        http.close()
        server.close()
    }

    @Test
    fun `finds a version through the manifest and resolves it`() =
        runBlocking {
            serveManifest(sha1(versionFile))
            server.serve("/v1/26.3.json", versionFile)

            val result = metadata().version("26.3", platform, emptySet())

            assertEquals("26.3", (result as Outcome.Ok).value.id)
        }

    @Test
    fun `reads a stored version file without downloading it again`() =
        runBlocking {
            serveManifest(sha1(versionFile))
            server.serve("/v1/26.3.json", versionFile)

            metadata().version("26.3", platform, emptySet())
            metadata().version("26.3", platform, emptySet())

            assertEquals(2, server.hits("/manifest.json"))
            assertEquals(1, server.hits("/v1/26.3.json"))
        }

    @Test
    fun `reports a version the manifest does not list`() =
        runBlocking {
            serveManifest(sha1(versionFile))

            assertEquals(Outcome.Err(MetadataError.UnknownVersion("1.0")), metadata().version("1.0", platform, emptySet()))
        }

    @Test
    fun `refuses a version file that does not match the manifest`() =
        runBlocking {
            serveManifest("0".repeat(40))
            server.serve("/v1/26.3.json", versionFile)

            val result = metadata().version("26.3", platform, emptySet())

            val error = ((result as Outcome.Err).error as MetadataError.Download).error
            assertEquals(true, error is DownloadError.ChecksumMismatch)
        }

    @Test
    fun `reports a manifest that cannot be downloaded`() =
        runBlocking {
            server.serve("/manifest.json", ByteArray(0), status = 404)

            assertEquals(
                Outcome.Err(MetadataError.Download(DownloadError.HttpStatus(server.url("/manifest.json"), 404))),
                metadata().version("26.3", platform, emptySet()),
            )
        }

    @Test
    fun `reports a manifest that is not JSON`() =
        runBlocking {
            server.serve("/manifest.json", "<html>".toByteArray())

            val result = metadata().version("26.3", platform, emptySet())

            assertEquals("version manifest", ((result as Outcome.Err).error as MetadataError.Malformed).source)
        }

    @Test
    fun `lists assets from the asset index`() =
        runBlocking {
            val index = """{"objects":{"icons/icon_16x16.png":{"hash":"5ff04807c356f1beed0b86ccf659b44b9983e3fa","size":781}}}"""
            val game = gameWithAssetIndex(index.toByteArray())

            val assets = (metadata().assets(game) as Outcome.Ok).value

            assertEquals(listOf("icons/icon_16x16.png"), assets.map { it.path })
            assertEquals(server.url("/resources/5f/5ff04807c356f1beed0b86ccf659b44b9983e3fa"), assets.single().artifact.url)
            assertEquals(781, assets.single().artifact.size)
        }

    @Test
    fun `refuses an asset with an invalid hash`() =
        runBlocking {
            val game = gameWithAssetIndex("""{"objects":{"a.png":{"hash":"nothex","size":1}}}""".toByteArray())

            val result = metadata().assets(game)

            assertEquals(
                Outcome.Err(MetadataError.Malformed("asset a.png", "invalid sha1 'nothex' for ${server.url("/resources/no/nothex")}")),
                result,
            )
        }

    private fun metadata() =
        MojangMetadata(http, ArtifactStore(root, http), MojangEndpoints(server.url("/manifest.json"), server.url("/resources")))

    private fun serveManifest(versionSha1: String) {
        val manifest = """{"versions":[{"id":"26.3","type":"release","url":"${server.url("/v1/26.3.json")}","sha1":"$versionSha1"}]}"""
        server.serve("/manifest.json", manifest.toByteArray())
    }

    private suspend fun gameWithAssetIndex(index: ByteArray): GameVersion {
        serveManifest(sha1(versionFile))
        server.serve("/v1/26.3.json", versionFile)
        server.serve("/index.json", index)
        val game = (metadata().version("26.3", platform, emptySet()) as Outcome.Ok).value
        val assetIndex = (Resolution.artifact("test", server.url("/index.json"), sha1(index), index.size.toLong()) as Outcome.Ok).value
        return game.copy(assetIndex = assetIndex)
    }

    private fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).toHexString()
}
