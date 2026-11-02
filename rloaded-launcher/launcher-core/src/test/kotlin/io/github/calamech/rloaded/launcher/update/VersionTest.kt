package io.github.calamech.rloaded.launcher.update

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionTest {
    @Test
    fun `reads major, minor and patch`() {
        assertEquals(Version(1, 20, 3), Version.parse("1.20.3"))
        assertEquals(Version(1, 0, 0), Version.ofTag("v1.0.0"))
    }

    @Test
    fun `refuses anything else`() {
        listOf("1.0", "1.0.0-beta", "01.0.0", "1.0.0.0", "", "v1.0.0", "1.0.x").forEach { assertEquals(null, Version.parse(it), it) }
        assertEquals(null, Version.ofTag("1.0.0"))
    }

    @Test
    fun `compares numbers, not text`() {
        assertTrue(Version(1, 10, 0) > Version(1, 9, 9))
        assertTrue(Version(2, 0, 0) > Version(1, 99, 99))
        assertEquals("1.10.0", Version(1, 10, 0).toString())
    }
}
