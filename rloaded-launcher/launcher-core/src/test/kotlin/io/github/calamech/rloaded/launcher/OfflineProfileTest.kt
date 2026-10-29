package io.github.calamech.rloaded.launcher

import io.github.calamech.rloaded.core.Outcome
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class OfflineProfileTest {
    @Test
    fun `derives the UUID an offline server gives the name`() {
        val profile = (OfflineProfile.of("Notch") as Outcome.Ok).value

        assertEquals("b50ad385-829d-3141-a216-7e7d7539ba7f", profile.uuid.toString())
        assertEquals("OfflineProfile(Notch)", profile.toString())
        assertEquals(OfflineProfile.of("Notch"), OfflineProfile.of("Notch"))
    }

    @Test
    fun `refuses names Minecraft refuses`() {
        for (name in listOf("ab", "a".repeat(17), "with space", "é_name")) {
            assertEquals(Outcome.Err(InvalidPlayerName(name)), OfflineProfile.of(name))
        }
    }

    @Test
    fun `prints a server address without the default port`() {
        assertEquals("localhost", ServerAddress("localhost").toString())
        assertEquals("localhost:25566", ServerAddress("localhost", 25566).toString())
    }
}
