package io.github.calamech.rloaded.core.mojang

import io.github.calamech.rloaded.protocol.Outcome
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Resolves the recorded 26.3 version file from Mojang (sha1 5df1795e…, fetched 2026-10-29). */
class ResolutionTest {
    private val version =
        (Codec.decode<VersionJson>("fixture", javaClass.getResource("/mojang/26.3.json")!!.readText()) as Outcome.Ok).value

    private val macArm = Platform(OperatingSystem.MACOS, Architecture.ARM64)
    private val linux = Platform(OperatingSystem.LINUX, Architecture.X86_64)

    @Test
    fun `resolves the game files`() {
        val game = resolve(macArm, emptySet())

        assertEquals("26.3", game.id)
        assertEquals("net.minecraft.client.main.Main", game.mainClass)
        assertEquals(25, game.javaMajorVersion)
        assertEquals("sha1:e877b6a07acd633fb3bb475002175cec036e7b87", game.client.checksum.toString())
        assertEquals(41483720, game.client.size)
        assertEquals("34", game.assetIndexId)
        assertEquals("client-1.21.2.xml", game.logging?.fileName)
        assertEquals("-Dlog4j.configurationFile=\${path}", game.logging?.argument)
    }

    @Test
    fun `keeps the libraries of the platform only`() {
        val mac = resolve(macArm, emptySet()).libraries.map { it.url.substringAfterLast('/') }
        val onLinux = resolve(linux, emptySet()).libraries.map { it.url.substringAfterLast('/') }

        assertTrue("java-objc-bridge-1.1.jar" in mac)
        assertTrue("jtracy-1.14.38-natives-macos-arm64.jar" in mac)
        assertFalse("jtracy-1.14.38-natives-linux.jar" in mac)
        assertFalse("java-objc-bridge-1.1.jar" in onLinux)
        assertTrue("jtracy-1.14.38-natives-linux.jar" in onLinux)
        assertTrue("lz4-java-1.10.1.jar" in mac && "lz4-java-1.10.1.jar" in onLinux)
    }

    @Test
    fun `keeps the JVM arguments of the platform only`() {
        val mac = resolve(macArm, emptySet()).jvmArguments
        val onLinux = resolve(linux, emptySet()).jvmArguments

        assertEquals("-XstartOnFirstThread", mac.first())
        assertFalse("-XstartOnFirstThread" in onLinux)
        assertFalse("-Xss1M" in mac || "-Xss1M" in onLinux)
        assertEquals(listOf("-cp", "\${classpath}"), mac.takeLast(2))
    }

    @Test
    fun `adds quick play arguments when the feature is on`() {
        val without = resolve(macArm, emptySet()).gameArguments
        val with = resolve(macArm, setOf(LaunchFeatures.QUICK_PLAY_MULTIPLAYER)).gameArguments

        assertFalse("--quickPlayMultiplayer" in without)
        assertEquals(listOf("--quickPlayMultiplayer", "\${quickPlayMultiplayer}"), with.takeLast(2))
        assertFalse("--demo" in with)
        assertEquals(listOf("--username", "\${auth_player_name}"), with.take(2))
    }

    @Test
    fun `lets the last matching rule decide`() {
        val rules =
            listOf(
                RuleJson(RuleAction.ALLOW),
                RuleJson(RuleAction.DISALLOW, os = OsRuleJson(name = "osx")),
            )

        assertFalse(Resolution.allowed(rules, macArm, emptySet()))
        assertTrue(Resolution.allowed(rules, linux, emptySet()))
        assertTrue(Resolution.allowed(emptyList(), linux, emptySet()))
    }

    @Test
    fun `refuses an argument that is neither a string nor an object`() {
        val broken = version.copy(arguments = version.arguments.copy(game = listOf(kotlinx.serialization.json.JsonPrimitive(1))))

        assertEquals(
            Outcome.Err(MetadataError.Malformed("version 26.3", "argument is not a string or an object")),
            Resolution.resolve(broken, macArm, emptySet()),
        )
    }

    @Test
    fun `refuses a library with an invalid hash`() {
        val library = version.libraries.first()
        val broken =
            version.copy(
                libraries = listOf(library.copy(downloads = LibraryDownloadsJson(library.downloads.artifact.copy(sha1 = "x")))),
            )

        val result = Resolution.resolve(broken, macArm, emptySet())

        assertEquals(true, result is Outcome.Err && result.error is MetadataError.Malformed)
    }

    @Test
    fun `refuses a version without a server download`() {
        val broken = version.copy(downloads = version.downloads - "server")

        assertEquals(
            Outcome.Err(MetadataError.Malformed("version 26.3", "missing client or server download")),
            Resolution.resolve(broken, macArm, emptySet()),
        )
    }

    private fun resolve(
        platform: Platform,
        features: Set<String>,
    ): GameVersion = (Resolution.resolve(version, platform, features) as Outcome.Ok).value
}
