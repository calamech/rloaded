package io.github.calamech.rloaded.admin

import io.github.calamech.rloaded.protocol.Outcome
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

class ReputationRulesTest {
    @Test
    fun `reads the rules from the properties, zero turning a rule off`() {
        val properties = mapOf(ReputationRules.VERSION_AGE_PROPERTY to "72", ReputationRules.DOWNLOADS_PROPERTY to "0")

        assertEquals(Outcome.Ok(ReputationRules(Duration.ofHours(72), 0)), ReputationRules.fromProperties { properties[it] })
    }

    @Test
    fun `refuses a missing, unreadable or out of range value`() {
        assertEquals(
            Outcome.Err("rloaded.reputation.min-version-age-hours is not set; start the server with rloaded-server.jar"),
            ReputationRules.fromProperties { null },
        )
        assertEquals(
            Outcome.Err("rloaded.reputation.min-project-downloads must be between 0 and 1000000000, not -1"),
            ReputationRules.fromProperties { if (it == ReputationRules.VERSION_AGE_PROPERTY) "1" else "-1" },
        )
        assertEquals(
            Outcome.Err("rloaded.reputation.min-version-age-hours must be between 0 and 8760, not a week"),
            ReputationRules.fromProperties { "a week" },
        )
    }
}
