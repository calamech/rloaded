package io.github.calamech.rloaded.launcher.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LauncherVersionTest {
    @Test
    fun `is the version of the catalog`() {
        assertEquals(System.getProperty("rloaded.catalog-version"), LauncherVersion.current)
    }
}
