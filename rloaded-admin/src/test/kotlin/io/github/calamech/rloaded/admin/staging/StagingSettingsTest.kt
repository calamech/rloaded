package io.github.calamech.rloaded.admin.staging

import io.github.calamech.rloaded.protocol.Outcome
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

class StagingSettingsTest {
    private fun read(vararg properties: Pair<String, String>) = StagingSettings.fromProperties(mapOf(*properties)::get)

    @Test
    fun `reads the settings the bootstrap passes`() {
        assertEquals(
            Outcome.Ok(StagingSettings(true, Duration.ofSeconds(180))),
            read(StagingSettings.ENABLED_PROPERTY to "true", StagingSettings.TIMEOUT_PROPERTY to "180"),
        )
        assertEquals(
            Outcome.Ok(StagingSettings(false, Duration.ofSeconds(30))),
            read(StagingSettings.ENABLED_PROPERTY to "false", StagingSettings.TIMEOUT_PROPERTY to "30"),
        )
    }

    @Test
    fun `refuses a missing setting, a word other than true or false, and a timeout out of range`() {
        assertEquals(
            Outcome.Err("rloaded.staging.enabled is not set; start the server with rloaded-server.jar"),
            read(StagingSettings.TIMEOUT_PROPERTY to "180"),
        )
        assertEquals(
            Outcome.Err("rloaded.staging.enabled must be true or false, not yes"),
            read(StagingSettings.ENABLED_PROPERTY to "yes", StagingSettings.TIMEOUT_PROPERTY to "180"),
        )
        assertEquals(
            Outcome.Err("rloaded.staging.timeout-seconds must be between 30 and 3600, not 29"),
            read(StagingSettings.ENABLED_PROPERTY to "true", StagingSettings.TIMEOUT_PROPERTY to "29"),
        )
        assertEquals(
            Outcome.Err("rloaded.staging.timeout-seconds is not set; start the server with rloaded-server.jar"),
            read(StagingSettings.ENABLED_PROPERTY to "true"),
        )
    }
}
