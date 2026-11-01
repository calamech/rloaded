package io.github.calamech.rloaded.admin

import io.github.calamech.rloaded.protocol.Outcome
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class InGameAdminTest {
    @Test
    fun `reads the setting the bootstrap passes, and refuses a missing or invalid one`() {
        assertEquals(Outcome.Ok(InGameAdmin(true)), InGameAdmin.fromProperties(mapOf(InGameAdmin.PROPERTY to "true")::get))
        assertEquals(Outcome.Ok(InGameAdmin(false)), InGameAdmin.fromProperties(mapOf(InGameAdmin.PROPERTY to "false")::get))
        assertEquals(
            Outcome.Err("rloaded.admin.in-game is not set; start the server with rloaded-server.jar"),
            InGameAdmin.fromProperties(emptyMap<String, String>()::get),
        )
        assertEquals(
            Outcome.Err("rloaded.admin.in-game must be true or false, not yes"),
            InGameAdmin.fromProperties(mapOf(InGameAdmin.PROPERTY to "yes")::get),
        )
    }

    @Test
    fun `lets only operators of level 4 add, and nobody when the setting is off`() {
        assertEquals(listOf(true, false), listOf(true, false).map { InGameAdmin(true).allows(ownerLevel = it) })
        assertEquals(listOf(false, false), listOf(true, false).map { InGameAdmin(false).allows(ownerLevel = it) })
    }
}
