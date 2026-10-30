package io.github.calamech.rloaded.bootstrap

import io.github.calamech.rloaded.protocol.Outcome
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerConfigTest {
    @Test
    fun `reads the template with its defaults`() {
        assertEquals(Outcome.Ok(ServerConfig()), ServerConfig.parse(ServerConfig.TEMPLATE))
    }

    @Test
    fun `reads changed values`() {
        val config = ServerConfig.parse("[server]\nname = \"Friends\"\nmaxMemoryMiB = 8192\n")

        assertEquals(Outcome.Ok(ServerConfig(ServerSection("Friends", 8192))), config)
    }

    @Test
    fun `refuses an unknown key`() {
        val result = ServerConfig.parse("[server]\nmemory = 1\n")

        assertTrue(result is Outcome.Err && "memory" in result.error)
    }

    @Test
    fun `reads the client timeout and refuses one out of range`() {
        assertEquals(Outcome.Ok(ServerConfig(rollout = RolloutSection(30))), ServerConfig.parse("[rollout]\nclientTimeoutSeconds = 30\n"))
        assertEquals(
            Outcome.Err("rollout.clientTimeoutSeconds must be between 10 and 3600"),
            ServerConfig.parse("[rollout]\nclientTimeoutSeconds = 5\n"),
        )
    }

    @Test
    fun `refuses memory out of range`() {
        assertEquals(
            Outcome.Err("server.maxMemoryMiB must be between 512 and 65536"),
            ServerConfig.parse("[server]\nmaxMemoryMiB = 100\n"),
        )
    }
}
