package io.github.calamech.rloaded.gametests

import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.map
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.IOException
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/** What a game test server reported: every test that ran, and the ones that failed with their message. */
public data class GameTestReport(
    val tests: List<String>,
    val failures: Map<String, String>,
) {
    /** Whether at least one test ran and none failed. */
    public val passed: Boolean get() = tests.isNotEmpty() && failures.isEmpty()

    /** Reading the JUnit XML file Fabric's game test runner writes. */
    public companion object {
        /** Reads [xml]; external entities and DTDs are refused. */
        public fun parse(xml: String): Outcome<GameTestReport, String> =
            document(xml).map { document ->
                val cases = document.getElementsByTagName("testcase")
                val tests = (0 until cases.length).map { cases.item(it) as Element }
                val failures =
                    tests
                        .mapNotNull { case ->
                            val failure = case.getElementsByTagName("failure").item(0) as Element?
                            failure?.let { case.getAttribute("name") to it.getAttribute("message") }
                        }.toMap()
                GameTestReport(tests.map { it.getAttribute("name") }, failures)
            }

        private fun document(xml: String): Outcome<Document, String> =
            try {
                val factory = DocumentBuilderFactory.newInstance()
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                Outcome.Ok(factory.newDocumentBuilder().parse(InputSource(StringReader(xml))))
            } catch (e: SAXException) {
                Outcome.Err("the report is not valid XML: ${e.message}")
            } catch (e: IOException) {
                Outcome.Err("the report cannot be read: ${e.message}")
            } catch (e: ParserConfigurationException) {
                Outcome.Err("no XML parser: ${e.message}")
            }
    }
}
