package io.github.calamech.rloaded.core.runtime

import io.github.calamech.rloaded.core.download.Artifact
import io.github.calamech.rloaded.core.download.ArtifactStore
import io.github.calamech.rloaded.core.download.Checksum
import io.github.calamech.rloaded.core.download.DownloadError
import io.github.calamech.rloaded.core.download.HashAlgorithm
import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.TestServer
import io.github.calamech.rloaded.core.mojang.OperatingSystem
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.FileSystems
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JetBrainsRuntimeInstallerTest {
    private val server = TestServer()
    private val http = KtorHttp(server.policy, HttpSettings())

    @TempDir
    lateinit var root: Path

    private val runtimes get() = root.resolve("runtimes")

    @AfterEach
    fun close() {
        http.close()
        server.close()
    }

    @Test
    fun `installs a macOS runtime and returns its java executable`() =
        runBlocking {
            val archive =
                serve(
                    tarGz {
                        file("jbr-x/Contents/Home/bin/java", "#!/bin/sh", mode = 0b111_101_101)
                        file("jbr-x/Contents/Home/legal/java.base/LICENSE", "license")
                    },
                )

            val java = assertOk(installer(OperatingSystem.MACOS).install(archive, "1"))

            assertEquals(runtimes.resolve("jbr-1/jbr-x/Contents/Home/bin/java"), java)
            assertEquals("#!/bin/sh", java.readText())
            if (posix) assertTrue(java.isExecutable())
        }

    @Test
    fun `installs a Linux runtime`() =
        runBlocking {
            val archive = serve(tarGz { file("jbr-x/bin/java", "java", mode = 0b111_101_101) })

            val java = assertOk(installer(OperatingSystem.LINUX).install(archive, "1"))

            assertEquals(runtimes.resolve("jbr-1/jbr-x/bin/java"), java)
        }

    @Test
    fun `installs a Windows runtime`() =
        runBlocking {
            val archive = serve(tarGz { file("jbr-x/bin/java.exe", "java") })

            val java = assertOk(installer(OperatingSystem.WINDOWS).install(archive, "1"))

            assertEquals(runtimes.resolve("jbr-1/jbr-x/bin/java.exe"), java)
        }

    @Test
    fun `keeps symbolic links inside the runtime`() =
        runBlocking {
            assumeTrue(posix, "symbolic links need a POSIX file system")
            val archive =
                serve(
                    tarGz {
                        file("jbr-x/bin/java", "java", mode = 0b111_101_101)
                        file("jbr-x/legal/java.base/LICENSE", "license")
                        symlink("jbr-x/legal/java.compiler/LICENSE", "../java.base/LICENSE")
                    },
                )

            assertOk(installer(OperatingSystem.LINUX).install(archive, "1"))

            val link = runtimes.resolve("jbr-1/jbr-x/legal/java.compiler/LICENSE")
            assertTrue(link.isSymbolicLink())
            assertEquals("license", link.readText())
        }

    @Test
    fun `uses an installed runtime without downloading it again`() =
        runBlocking {
            val archive = serve(tarGz { file("jbr-x/bin/java", "java") })
            val installer = installer(OperatingSystem.LINUX)

            val first = installer.install(archive, "1")
            val second = installer.install(archive, "1")

            assertEquals(first, second)
            assertEquals(1, server.hits("/jbr.tar.gz"))
        }

    @Test
    fun `refuses an entry that climbs out of the runtime`() =
        runBlocking {
            val archive = serve(tarGz { file("jbr-x/../../escaped", "evil") })

            val result = installer(OperatingSystem.LINUX).install(archive, "1")

            assertEquals(
                Outcome.Err(InstallError.Extraction(ExtractionError.Unsafe("jbr-x/../../escaped", "points outside the archive"))),
                result,
            )
            assertNothingInstalled()
        }

    @Test
    fun `refuses a symbolic link that points out of the runtime`() =
        runBlocking {
            val archive = serve(tarGz { symlink("jbr-x/bin/java", "../../../../usr/bin/java") })

            val result = installer(OperatingSystem.LINUX).install(archive, "1")

            assertEquals(
                Outcome.Err(InstallError.Extraction(ExtractionError.Unsafe("jbr-x/bin/java", "points outside the archive"))),
                result,
            )
            assertNothingInstalled()
        }

    @Test
    fun `refuses an archive without a java executable`() =
        runBlocking {
            val archive = serve(tarGz { file("jbr-x/README", "no java here") })

            val result = installer(OperatingSystem.LINUX).install(archive, "1")

            assertEquals(Outcome.Err(InstallError.NoJavaExecutable(archive.url)), result)
            assertNothingInstalled()
        }

    @Test
    fun `refuses a damaged archive`() =
        runBlocking {
            val archive = serve("not a tar.gz".toByteArray())

            val result = installer(OperatingSystem.LINUX).install(archive, "1")

            assertTrue(result is Outcome.Err && result.error is InstallError.Extraction)
            assertNothingInstalled()
        }

    @Test
    fun `refuses an archive with another hash`() =
        runBlocking {
            val archive = serve(tarGz { file("jbr-x/bin/java", "java") }).copy(checksum = sha512("other".toByteArray()))

            val result = installer(OperatingSystem.LINUX).install(archive, "1")

            assertTrue(result is Outcome.Err && (result.error as InstallError.Download).error is DownloadError.ChecksumMismatch)
            assertNothingInstalled()
        }

    private fun installer(os: OperatingSystem) = JetBrainsRuntimeInstaller(ArtifactStore(root.resolve("store"), http), runtimes, os)

    private fun serve(bytes: ByteArray): Artifact {
        server.serve("/jbr.tar.gz", bytes)
        return Artifact(server.url("/jbr.tar.gz"), sha512(bytes), bytes.size.toLong())
    }

    private fun assertNothingInstalled() {
        assertTrue(!runtimes.exists() || runtimes.listDirectoryEntries().isEmpty(), "runtimes: ${runtimes.toFile().list()?.toList()}")
    }

    private fun <T> assertOk(outcome: Outcome<T, InstallError>): T {
        assertTrue(outcome is Outcome.Ok, "Expected success, got $outcome")
        return outcome.value
    }

    private class TarBuilder(
        val tar: TarArchiveOutputStream,
    ) {
        fun file(
            name: String,
            content: String,
            mode: Int = 0b110_100_100,
        ) {
            val bytes = content.toByteArray()
            tar.putArchiveEntry(TarArchiveEntry(name).also { it.size = bytes.size.toLong() }.also { it.mode = mode })
            tar.write(bytes)
            tar.closeArchiveEntry()
        }

        fun symlink(
            name: String,
            target: String,
        ) {
            tar.putArchiveEntry(TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK).also { it.linkName = target })
            tar.closeArchiveEntry()
        }
    }

    private fun tarGz(build: TarBuilder.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(GZIPOutputStream(bytes)).use { TarBuilder(it).build() }
        return bytes.toByteArray()
    }

    private fun sha512(bytes: ByteArray): Checksum =
        (Checksum.parse(HashAlgorithm.SHA512, MessageDigest.getInstance("SHA-512").digest(bytes).toHexString()) as Outcome.Ok).value

    private companion object {
        val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()
    }
}
