package io.github.calamech.rloaded.core.mojang

import io.github.calamech.rloaded.protocol.Outcome
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformsTest {
    @Test
    fun `reads the names the JVM reports`() {
        assertEquals(Outcome.Ok(Platform(OperatingSystem.MACOS, Architecture.ARM64)), Platforms.detect("Mac OS X", "aarch64"))
        assertEquals(Outcome.Ok(Platform(OperatingSystem.LINUX, Architecture.X86_64)), Platforms.detect("Linux", "amd64"))
        assertEquals(Outcome.Ok(Platform(OperatingSystem.WINDOWS, Architecture.X86_64)), Platforms.detect("Windows 11", "amd64"))
        assertEquals(Outcome.Ok(Platform(OperatingSystem.WINDOWS, Architecture.ARM64)), Platforms.detect("Windows 11", "aarch64"))
    }

    @Test
    fun `refuses platforms Rloaded does not support`() {
        assertEquals(Outcome.Err(UnsupportedPlatform("FreeBSD", "amd64")), Platforms.detect("FreeBSD", "amd64"))
        assertEquals(Outcome.Err(UnsupportedPlatform("Linux", "x86")), Platforms.detect("Linux", "x86"))
    }

    @Test
    fun `detects the platform of this JVM`() {
        assertTrue(Platforms.current() is Outcome.Ok)
    }
}
