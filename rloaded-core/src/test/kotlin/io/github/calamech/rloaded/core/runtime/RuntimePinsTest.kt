package io.github.calamech.rloaded.core.runtime

import io.github.calamech.rloaded.core.download.HashAlgorithm
import io.github.calamech.rloaded.core.download.UrlPolicy
import io.github.calamech.rloaded.core.mojang.Architecture
import io.github.calamech.rloaded.core.mojang.OperatingSystem
import io.github.calamech.rloaded.core.mojang.Platform
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimePinsTest {
    private val pins = RuntimePins.CURRENT
    private val platforms = OperatingSystem.entries.flatMap { os -> Architecture.entries.map { Platform(os, it) } }
    private val artifacts = pins.fabricLoaderLibraries + pins.baseSet.map { it.artifact } + platforms.map(pins::jetBrainsRuntime)

    @Test
    fun `pins the supported game version`() {
        assertEquals("26.3", pins.minecraftVersion)
        assertEquals("0.20.1", pins.fabricLoaderVersion)
        assertEquals("25.0.4.1-b508.40", pins.jetBrainsRuntimeVersion)
    }

    @Test
    fun `pins a JetBrains Runtime for every supported platform`() {
        assertEquals(6, platforms.map(pins::jetBrainsRuntime).distinct().size)
        assertTrue(pins.jetBrainsRuntime(Platform(OperatingSystem.MACOS, Architecture.ARM64)).url.endsWith("osx-aarch64-b508.40.tar.gz"))
        assertTrue(pins.jetBrainsRuntime(Platform(OperatingSystem.WINDOWS, Architecture.X86_64)).url.endsWith("windows-x64-b508.40.tar.gz"))
    }

    @Test
    fun `pins every file by SHA-512 from an allowed origin`() {
        for (artifact in artifacts) {
            assertEquals(HashAlgorithm.SHA512, artifact.checksum.algorithm, artifact.url)
            assertNull(UrlPolicy.PRODUCTION.refusalReason(artifact.url), artifact.url)
        }
    }

    @Test
    fun `pins Fabric Loader with its libraries`() {
        val names = pins.fabricLoaderLibraries.map { it.url.substringAfterLast('/') }

        assertTrue("fabric-loader-0.20.1.jar" in names)
        assertTrue(names.any { it.startsWith("sponge-mixin-") })
    }

    @Test
    fun `pins Fabric API and Fabric Language Kotlin as the base set`() {
        assertEquals(listOf("P7dR8mSH", "Ha28R6CL"), pins.baseSet.map { it.projectId })
        assertTrue(pins.baseSet.all { it.artifact.url.endsWith(it.fileName.replace("+", "%2B")) })
    }

    @Test
    fun `runs the versions the runtime mod is built against`() {
        val catalog = java.io.File(System.getProperty("rloaded.catalog")).readText()

        fun version(name: String): String? = Regex("""(?m)^$name = "([^"]+)"""").find(catalog)?.groupValues?.get(1)

        assertEquals(version("minecraft"), pins.minecraftVersion)
        assertEquals(version("fabric-loader"), pins.fabricLoaderVersion)
        assertEquals("fabric-api-${version("fabric-api")}.jar", pins.baseSet.first().fileName)
    }

    @Test
    fun `refuses a pins file with an unknown field`() {
        val text = javaClass.getResource("runtime-pins.json")!!.readText().replaceFirst("{", """{"extra": 1,""")

        assertThrows<SerializationException> { RuntimePins.parse(text) }
    }

    @Test
    fun `refuses a pins file without a runtime for every platform`() {
        val text = javaClass.getResource("runtime-pins.json")!!.readText().replace("\"windows-arm64\"", "\"windows-arm\"")

        val error = assertThrows<IllegalStateException> { RuntimePins.parse(text) }
        assertEquals("No JetBrains Runtime pinned for Platform(os=WINDOWS, architecture=ARM64)", error.message)
    }
}
