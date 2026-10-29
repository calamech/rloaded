package io.github.calamech.rloaded.launcher

import io.github.calamech.rloaded.core.mojang.OperatingSystem
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals

class DataDirectoryTest {
    private val home = Path.of("/home/player")

    @Test
    fun `uses Application Support on macOS`() {
        assertEquals(home.resolve("Library/Application Support/Rloaded"), DataDirectory.of(OperatingSystem.MACOS, emptyMap(), home))
    }

    @Test
    fun `uses APPDATA on Windows`() {
        assertEquals(Path.of("/roaming/Rloaded"), DataDirectory.of(OperatingSystem.WINDOWS, mapOf("APPDATA" to "/roaming"), home))
        assertEquals(home.resolve("AppData/Roaming/Rloaded"), DataDirectory.of(OperatingSystem.WINDOWS, mapOf("APPDATA" to " "), home))
    }

    @Test
    fun `uses XDG_DATA_HOME on Linux`() {
        assertEquals(Path.of("/data/rloaded"), DataDirectory.of(OperatingSystem.LINUX, mapOf("XDG_DATA_HOME" to "/data"), home))
        assertEquals(home.resolve(".local/share/rloaded"), DataDirectory.of(OperatingSystem.LINUX, emptyMap(), home))
    }
}
