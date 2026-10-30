package io.github.calamech.rloaded.modrinth

import io.github.calamech.rloaded.core.download.ArtifactStore
import io.github.calamech.rloaded.core.download.DownloadError
import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.RetryPolicy
import io.github.calamech.rloaded.core.download.TestServer
import io.github.calamech.rloaded.protocol.Checksum
import io.github.calamech.rloaded.protocol.HashAlgorithm
import io.github.calamech.rloaded.protocol.ModEntry
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.Side
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readBytes
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ModInstallerTest {
    @TempDir
    lateinit var directory: Path

    private val server = TestServer()
    private val http = KtorHttp(server.policy, HttpSettings())
    private val jarA = "jar A".toByteArray()
    private val jarB = "jar B, a bit longer".toByteArray()
    private val entryA = entry("projA", "verA", "a.jar", jarA)
    private val entryB = entry("projB", "verB", "b.jar", jarB)

    @AfterEach
    fun close() {
        http.close()
        server.close()
    }

    @Test
    fun `downloads every entry from the URL in Modrinth's record and returns the jars in entry order`() {
        recordsFor(entryA to "/cdn/a.jar", entryB to "/cdn/b.jar")
        server.serve("/cdn/a.jar", jarA)
        server.serve("/cdn/b.jar", jarB)
        val progress = mutableListOf<Pair<Int, Int>>()
        val ended = mutableListOf<String>()

        val installed =
            ok(
                install(listOf(entryB, entryA)) { entry, done, total ->
                    synchronized(progress) {
                        progress += done to total
                        ended += entry.projectId
                    }
                },
            )

        assertEquals(listOf(entryB, entryA), installed.map { it.entry })
        assertEquals(listOf(jarB.toList(), jarA.toList()), installed.map { it.jar.readBytes().toList() })
        assertEquals(listOf(1 to 2, 2 to 2), progress.sortedBy { it.first })
        assertEquals(listOf("projA", "projB"), ended.sorted())
    }

    @Test
    fun `finds a jar in the store again without downloading it`() {
        recordsFor(entryA to "/cdn/a.jar")
        server.serve("/cdn/a.jar", jarA)
        ok(install(listOf(entryA)))

        ok(install(listOf(entryA)))

        assertEquals(1, server.hits("/cdn/a.jar"))
    }

    @Test
    fun `refuses every entry that disagrees with Modrinth's record before downloading anything`() {
        val forged = entry("projA", "verA", "a.jar", "jar X".toByteArray())
        val renamed = entryB.copy(fileName = "evil.jar")
        recordsFor(entryA to "/cdn/a.jar", entryB to "/cdn/b.jar")
        server.serve("/cdn/a.jar", jarA)

        val error = (install(listOf(forged, renamed)) as Outcome.Err).error

        assertEquals(
            InstallError.Mismatches(
                mapOf(
                    "projA" to FileMismatch.OtherHash(forged.sha512, entryA.sha512),
                    "projB" to FileMismatch.NoSuchFile("verB", "evil.jar"),
                ),
            ),
            error,
        )
        assertEquals(0, server.hits("/cdn/a.jar"))
    }

    @Test
    fun `reports a file that cannot be downloaded`() {
        recordsFor(entryA to "/cdn/a.jar")

        val error = (install(listOf(entryA)) as Outcome.Err).error

        assertIs<InstallError.Downloads>(error)
        assertEquals(listOf(DownloadError.HttpStatus(server.url("/cdn/a.jar"), 404)), error.failures.failures)
    }

    @Test
    fun `reports a version Modrinth does not know`() {
        recordsFor()

        assertEquals(Outcome.Err(InstallError.Records(ModrinthError.UnknownVersions(listOf("verA")))), install(listOf(entryA)))
    }

    private fun install(
        entries: List<ModEntry>,
        progress: DownloadProgress = DownloadProgress { _, _, _ -> },
    ): Outcome<List<InstalledMod>, InstallError> {
        val store = ArtifactStore(Files.createDirectories(directory.resolve("store")), http, RetryPolicy(maxAttempts = 1))
        val installer = ModInstaller(Modrinth(http, ModrinthSettings(apiUrl = server.url("/v2"))), store)
        return installer.installAsync(entries, progress).join()
    }

    private fun recordsFor(vararg files: Pair<ModEntry, String>) {
        val versions =
            files.joinToString(",", "[", "]") { (entry, path) ->
                """{"id": "${entry.versionId}", "project_id": "${entry.projectId}", "files": [{"url": "${server.url(path)}", """ +
                    """"filename": "${entry.fileName}", "size": ${entry.size}, "hashes": {"sha512": "${entry.sha512.hex}"}}]}"""
            }
        server.serve("/v2/versions", versions.toByteArray())
    }

    private fun entry(
        projectId: String,
        versionId: String,
        fileName: String,
        bytes: ByteArray,
    ): ModEntry {
        val hex = MessageDigest.getInstance("SHA-512").digest(bytes).toHexString()
        val sha = (Checksum.parse(HashAlgorithm.SHA512, hex) as Outcome.Ok).value
        return ModEntry(projectId, versionId, fileName, sha, bytes.size.toLong(), Side.BOTH)
    }

    private fun <T> ok(outcome: Outcome<T, InstallError>): T =
        when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw AssertionError("expected a value, got ${outcome.error}")
        }
}
