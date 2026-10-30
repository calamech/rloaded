package io.github.calamech.rloaded.gametests

import io.github.calamech.rloaded.protocol.Outcome
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameTestReportTest {
    @Test
    fun `reads passed and failed tests`() {
        val xml =
            """<?xml version="1.0" encoding="UTF-8"?><testsuite><testsuite time="2.3">""" +
                """<testcase classname="minecraft:empty" name="minecraft:always_pass" time="0.006"/>""" +
                """<testcase name="rloaded-gametest:late"><failure message="(1, 4, 2) Mixin did not register"/></testcase>""" +
                "</testsuite></testsuite>"

        val report = (GameTestReport.parse(xml) as Outcome.Ok).value

        assertEquals(listOf("minecraft:always_pass", "rloaded-gametest:late"), report.tests)
        assertEquals(mapOf("rloaded-gametest:late" to "(1, 4, 2) Mixin did not register"), report.failures)
        assertFalse(report.passed)
    }

    @Test
    fun `passes only when tests ran and none failed`() {
        val passed = (GameTestReport.parse("""<testsuite><testcase name="a"/></testsuite>""") as Outcome.Ok).value
        val empty = (GameTestReport.parse("<testsuite/>") as Outcome.Ok).value

        assertTrue(passed.passed)
        assertFalse(empty.passed)
    }

    @Test
    fun `refuses a document that is not XML`() {
        assertTrue(GameTestReport.parse("not xml") is Outcome.Err)
    }

    @Test
    fun `refuses a document type declaration`() {
        val xml = """<?xml version="1.0"?><!DOCTYPE t [<!ENTITY x SYSTEM "file:///etc/passwd">]><testsuite>&x;</testsuite>"""

        val result = GameTestReport.parse(xml)

        assertTrue(result is Outcome.Err && "DOCTYPE" in result.error)
    }
}
