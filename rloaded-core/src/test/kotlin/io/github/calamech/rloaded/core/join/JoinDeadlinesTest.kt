package io.github.calamech.rloaded.core.join

import io.github.calamech.rloaded.protocol.Outcome
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

class JoinDeadlinesTest {
    private var now = Long.MAX_VALUE - Duration.ofMinutes(1).toNanos()
    private val deadlines = JoinDeadlines<String>(Duration.ofSeconds(TIMEOUT)) { now }

    @Test
    fun `ends a join once the timeout passes without progress, once`() {
        deadlines.start("slow")
        deadlines.start("late")
        advance(TIMEOUT - 1)
        deadlines.start("late")

        assertEquals(emptyList(), deadlines.expired())
        advance(1)
        assertEquals(listOf("slow"), deadlines.expired())
        assertEquals(emptyList(), deadlines.expired())
    }

    @Test
    fun `waits again after each progress message, across the wrap of the clock`() {
        deadlines.start("downloading")
        repeat(PROGRESS_MESSAGES) {
            advance(TIMEOUT - 1)
            deadlines.progress("downloading")
            assertEquals(emptyList(), deadlines.expired())
        }

        advance(TIMEOUT)
        assertEquals(listOf("downloading"), deadlines.expired())
    }

    @Test
    fun `forgets a join that ended, and ignores progress from a join it does not wait for`() {
        deadlines.start("done")
        deadlines.end("done")
        deadlines.progress("unknown")
        advance(TIMEOUT)

        assertEquals(emptyList(), deadlines.expired())
    }

    @Test
    fun `reads the timeout setting and refuses a missing or out of range value`() {
        assertEquals(Outcome.Ok(Duration.ofSeconds(10)), JoinDeadlines.timeout("10"))
        assertEquals(
            Outcome.Err("rloaded.client-timeout-seconds is not set; start the server with rloaded-server.jar"),
            JoinDeadlines.timeout(null),
        )
        for (value in listOf("9", "3601", "two minutes", "")) {
            assertEquals(
                Outcome.Err("rloaded.client-timeout-seconds must be between 10 and 3600, not $value"),
                JoinDeadlines.timeout(value),
            )
        }
    }

    private fun advance(seconds: Long) {
        now += Duration.ofSeconds(seconds).toNanos()
    }

    private companion object {
        const val TIMEOUT = 120L
        const val PROGRESS_MESSAGES = 3
    }
}
