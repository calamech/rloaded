package io.github.calamech.rloaded.launcher.update

import io.github.calamech.rloaded.core.download.Artifact
import io.github.calamech.rloaded.core.download.DownloadError
import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.TestServer
import io.github.calamech.rloaded.core.mojang.Architecture
import io.github.calamech.rloaded.core.mojang.OperatingSystem
import io.github.calamech.rloaded.core.mojang.Platform
import io.github.calamech.rloaded.protocol.Checksum
import io.github.calamech.rloaded.protocol.HashAlgorithm
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UpdateCheckTest {
    private val server = TestServer()
    private val http = KtorHttp(server.policy, HttpSettings())
    private val source = ReleaseSource(server.url("/latest"), server.url("/download/"))
    private val mac = Platform(OperatingSystem.MACOS, Architecture.ARM64)
    private val hash = "a".repeat(64)

    @AfterEach
    fun close() {
        http.close()
        server.close()
    }

    private fun check(
        current: String = "1.0.0",
        platform: Platform = mac,
    ): Outcome<Update?, UpdateError> = runBlocking { UpdateCheck(http, source, Version(current), platform).check() }

    private fun release(
        tag: String,
        assets: List<Pair<String, String>>,
    ) {
        val listed =
            assets.joinToString(",") { (name, url) ->
                """{"name": "$name", "browser_download_url": "$url", "size": 42, "uploader": {"login": "x"}}"""
            }
        server.serve("/latest", """{"tag_name": "$tag", "draft": false, "assets": [$listed]}""".toByteArray())
    }

    private fun publish(
        tag: String,
        installer: String,
        sums: String = "$hash  $installer\n${"b".repeat(64)}  rloaded-server.jar\n",
    ) {
        release(tag, listOf(installer to server.url("/download/$tag/$installer"), "SHA256SUMS" to server.url("/download/$tag/SHA256SUMS")))
        server.serve("/download/$tag/SHA256SUMS", sums.toByteArray())
    }

    @Test
    fun `offers a newer release with this platform's installer and its hash`() {
        publish("v1.2.0", "Rloaded-1.2.0-macos-arm64.dmg")

        val expected =
            Update(
                Version(1, 2, 0),
                "Rloaded-1.2.0-macos-arm64.dmg",
                Artifact(server.url("/download/v1.2.0/Rloaded-1.2.0-macos-arm64.dmg"), sha256(hash), 42),
            )
        assertEquals(Outcome.Ok(expected), check())
    }

    @Test
    fun `offers nothing when the latest release is this version or an older one`() {
        publish("v1.0.0", "Rloaded-1.0.0-macos-arm64.dmg")
        assertEquals(Outcome.Ok(null), check(current = "1.0.0"))
        assertEquals(Outcome.Ok(null), check(current = "1.10.0"))
    }

    @Test
    fun `asks nothing on a platform that has no installer`() {
        publish("v1.2.0", "Rloaded-1.2.0-macos-arm64.dmg")

        assertEquals(Outcome.Ok(null), check(platform = Platform(OperatingSystem.LINUX, Architecture.ARM64)))
        assertEquals(0, server.hits("/latest"))
    }

    @Test
    fun `refuses a release without this platform's installer`() {
        publish("v1.2.0", "Rloaded-1.2.0-windows-x64.msi")

        assertEquals(Outcome.Err(UpdateError.NoInstaller("Rloaded-1.2.0-macos-arm64.dmg")), check())
    }

    @Test
    fun `refuses an installer served from outside the release downloads`() {
        val elsewhere = server.url("/elsewhere/Rloaded-1.2.0-macos-arm64.dmg")
        release("v1.2.0", listOf("Rloaded-1.2.0-macos-arm64.dmg" to elsewhere, "SHA256SUMS" to server.url("/download/v1.2.0/SHA256SUMS")))

        assertEquals(Outcome.Err(UpdateError.Malformed("Rloaded-1.2.0-macos-arm64.dmg is served from $elsewhere")), check())
    }

    @Test
    fun `refuses a release whose sums do not name the installer`() {
        publish("v1.2.0", "Rloaded-1.2.0-macos-arm64.dmg", sums = "$hash  Rloaded-1.2.0-windows-x64.msi\n")

        assertEquals(Outcome.Err(UpdateError.Malformed("SHA256SUMS has no line for Rloaded-1.2.0-macos-arm64.dmg")), check())
    }

    @Test
    fun `refuses a release without sums`() {
        release("v1.2.0", listOf("Rloaded-1.2.0-macos-arm64.dmg" to server.url("/download/v1.2.0/Rloaded-1.2.0-macos-arm64.dmg")))

        assertEquals(Outcome.Err(UpdateError.Malformed("the release has no SHA256SUMS")), check())
    }

    @Test
    fun `refuses a tag that is not a version`() {
        publish("v1.2.0-beta", "Rloaded-1.2.0-macos-arm64.dmg")

        assertEquals(Outcome.Err(UpdateError.Malformed("the latest release is tagged v1.2.0-beta")), check())
    }

    @Test
    fun `refuses a release list it cannot read`() {
        server.serve("/latest", """{"tag": "v1.2.0"}""".toByteArray())

        assertIs<UpdateError.Malformed>((check() as Outcome.Err).error)
    }

    @Test
    fun `says when the release list cannot be reached`() {
        server.serve("/latest", ByteArray(0), status = 503)

        assertEquals(Outcome.Err(UpdateError.Unreachable(DownloadError.HttpStatus(server.url("/latest"), 503))), check())
    }

    @Test
    fun `names the installer of each platform built`() {
        assertEquals("macos-x64.dmg", installerSuffix(Platform(OperatingSystem.MACOS, Architecture.X86_64)))
        assertEquals("windows-x64.msi", installerSuffix(Platform(OperatingSystem.WINDOWS, Architecture.X86_64)))
        assertEquals("linux-x64.deb", installerSuffix(Platform(OperatingSystem.LINUX, Architecture.X86_64)))
        assertEquals(null, installerSuffix(Platform(OperatingSystem.WINDOWS, Architecture.ARM64)))
    }

    @Test
    fun `reads sums written in binary mode`() {
        assertEquals(Outcome.Ok(sha256(hash)), checksumOf("$hash *Rloaded.msi\n", "Rloaded.msi"))
    }

    private fun sha256(hex: String): Checksum = (Checksum.parse(HashAlgorithm.SHA256, hex) as Outcome.Ok).value

    private fun Version(text: String): Version = (Version.parse(text) ?: error("not a version: $text"))
}
