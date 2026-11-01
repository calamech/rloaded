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
    fun `reads the reputation rules, zero turning one off, and refuses values out of range`() {
        assertEquals(
            Outcome.Ok(ServerConfig(reputation = ReputationSection(0, 50))),
            ServerConfig.parse("[reputation]\nminVersionAgeHours = 0\nminProjectDownloads = 50\n"),
        )
        assertEquals(
            Outcome.Err("reputation.minVersionAgeHours must be between 0 and 8760"),
            ServerConfig.parse("[reputation]\nminVersionAgeHours = 9000\n"),
        )
        assertEquals(
            Outcome.Err("reputation.minProjectDownloads must be between 0 and 1000000000"),
            ServerConfig.parse("[reputation]\nminProjectDownloads = -1\n"),
        )
    }

    @Test
    fun `reads the staging settings, turned off explicitly, and refuses a timeout out of range`() {
        assertEquals(
            Outcome.Ok(ServerConfig(staging = StagingSection(false, 600))),
            ServerConfig.parse("[staging]\nenabled = false\ntimeoutSeconds = 600\n"),
        )
        assertEquals(
            Outcome.Err("staging.timeoutSeconds must be between 30 and 3600"),
            ServerConfig.parse("[staging]\ntimeoutSeconds = 10\n"),
        )
        assertTrue(ServerConfig.parse("[staging]\nenabled = \"no\"\n") is Outcome.Err)
    }

    @Test
    fun `reads whether operators may add mods in the game`() {
        assertEquals(Outcome.Ok(ServerConfig(admin = AdminSection(false))), ServerConfig.parse("[admin]\ninGame = false\n"))
        assertTrue(ServerConfig.parse("[admin]\ninGame = \"no\"\n") is Outcome.Err)
    }

    @Test
    fun `passes the settings the game reads as system properties`() {
        assertEquals(
            mapOf(
                "rloaded.client-timeout-seconds" to "120",
                "rloaded.reputation.min-version-age-hours" to "72",
                "rloaded.reputation.min-project-downloads" to "1000",
                "rloaded.staging.enabled" to "true",
                "rloaded.staging.timeout-seconds" to "180",
                "rloaded.admin.in-game" to "true",
            ),
            ServerConfig().gameProperties(),
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
