package io.github.calamech.rloaded.admin.staging

import io.github.calamech.rloaded.protocol.Outcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StagingReportTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `keeps at most twenty failures, each cut to 500 characters`() {
        val report = (1..25).fold(StagingReport.of(listOf("mod"))) { report, n -> report.failing("x".repeat(600) + n) }

        assertEquals(20, report.failures.size)
        assertEquals("x".repeat(500), report.failures.first())
    }

    @Test
    fun `refuses a report with an unknown field or too many failures`() {
        val file = directory.resolve("report.json")
        file.writeText(
            """{"mods": [], "blocksPlaced": 0, "entitiesSpawned": 0, "itemsDropped": 0, "ticks": 0, "failures": [], "passed": true}""",
        )
        assertTrue((StagingReport.read(file) as Outcome.Err).error.contains("passed"))

        file.writeText(
            """{"mods": [], "blocksPlaced": 0, "entitiesSpawned": 0, "itemsDropped": 0, "ticks": 0, "failures": [""" +
                (1..21).joinToString(",") { "\"f$it\"" } + "]}",
        )
        assertEquals(Outcome.Err("the report lists more than 20 failures"), StagingReport.read(file))
    }
}
