package io.github.calamech.rloaded.admin

import io.github.calamech.rloaded.protocol.Outcome
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CompatDatabaseTest {
    @Test
    fun `reads the database the repository ships`() {
        val database = CompatDatabase.parse(Path.of(System.getProperty("rloaded.compatDatabase")).readText())

        val read = (database as Outcome.Ok).value
        assertEquals(setOf("optimization"), read.deniedCategories.keys)
        assertTrue("AANobbMI" in read.deniedProjects, "Sodium is refused")
        assertEquals(9, read.deniedProjects.size)
    }

    @Test
    fun `reads tested versions`() {
        val text =
            """{"schema": 1, "deniedCategories": [], "deniedProjects": [], "versions": [""" +
                """{"versionId": "abc", "status": "broken", "rloaded": "0.1.0", "note": "crashes"}]}"""

        assertEquals(
            Outcome.Ok(CompatDatabase(emptyMap(), emptyMap(), mapOf("abc" to TestedVersion(CompatStatus.BROKEN, "0.1.0", "crashes")))),
            CompatDatabase.parse(text),
        )
    }

    @Test
    fun `refuses an unknown field, another schema, an unknown status or a project listed twice`() {
        val empty = """{"schema": 1, "deniedCategories": [], "deniedProjects": [], "versions": []}"""
        val project = """{"projectId": "a", "slug": "a", "reason": "r"}"""

        assertIs<Outcome.Err<String>>(CompatDatabase.parse(empty.replace("}", ", \"extra\": 1}")))
        assertEquals(Outcome.Err("schema 2 is not 1"), CompatDatabase.parse(empty.replace("\"schema\": 1", "\"schema\": 2")))
        assertIs<Outcome.Err<String>>(
            CompatDatabase.parse(
                empty.replace("\"versions\": []", """"versions": [{"versionId": "v", "status": "fine", "rloaded": "1", "note": ""}]"""),
            ),
        )
        assertEquals(
            Outcome.Err("a project is listed twice"),
            CompatDatabase.parse(empty.replace("\"deniedProjects\": []", "\"deniedProjects\": [$project, $project]")),
        )
    }
}
