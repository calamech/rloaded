package io.github.calamech.rloaded.gametests

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RegistrySyncSetupTest {
    @Test
    fun `the server listens on the given port, offline, open to anyone, on the loopback interface only`() {
        val lines = serverProperties(25_570).lines()

        assertTrue("server-port=25570" in lines, lines.toString())
        assertTrue("server-ip=127.0.0.1" in lines, lines.toString())
        assertTrue("online-mode=false" in lines, lines.toString())
        assertTrue("white-list=false" in lines, lines.toString())
        assertTrue("level-type=minecraft\\:flat" in lines, lines.toString())
    }

    @Test
    fun `one client registers the added blocks in another order than the server`() {
        assertEquals(SERVER_ORDER, CLIENT_ORDERS[0])
        assertNotEquals(SERVER_ORDER, CLIENT_ORDERS[1])
        assertEquals(SERVER_ORDER.split(",").sorted(), CLIENT_ORDERS[1].split(",").sorted())
        assertEquals(ADDED_BLOCKS, SERVER_ORDER.split(",").map { "rloaded-gametest:$it" })
    }

    @Test
    fun `the check properties name the output directory and the order`() {
        val output = Path.of("build", "client-1")

        assertEquals(
            mapOf("rloaded.registry-sync.output" to output.toString(), "rloaded.registry-sync.order" to "sync_b,sync_a"),
            checkProperties(output, "sync_b,sync_a"),
        )
    }
}
