package io.github.calamech.rloaded.modrinth

import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.TestServer
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ModrinthProjectsTest {
    private val server = TestServer()
    private val recording = RecordedModrinth(server)
    private val http = KtorHttp(server.policy, HttpSettings())

    @AfterEach
    fun close() {
        http.close()
        server.close()
    }

    @Test
    fun `reads titles, download counts and team members in Modrinth's order`() {
        recording.answerProjectsFromRecording()

        val projects = runBlocking { Modrinth(http, recording.settings).projects(listOf("P7dR8mSH", "mOgUt4GM")) }

        assertEquals(
            Outcome.Ok(
                mapOf(
                    "P7dR8mSH" to
                        ModrinthProject("P7dR8mSH", "Fabric API", 293_803_425, listOf("Player7457", "modmuss50"), listOf("library")),
                    "mOgUt4GM" to
                        ModrinthProject(
                            "mOgUt4GM",
                            "Mod Menu",
                            163_985_152,
                            listOf("gniftygnome", "Prospector", "modmuss50"),
                            listOf("utility"),
                        ),
                ),
            ),
            projects,
        )
        assertEquals(1, server.hits(RecordedModrinth.PROJECTS))
        assertEquals(1, server.hits(RecordedModrinth.TEAMS))
    }

    @Test
    fun `refuses when Modrinth leaves a requested project out`() {
        recording.answerProjectsFromRecording()

        val projects = runBlocking { Modrinth(http, recording.settings).projects(listOf("P7dR8mSH", "zzzzzzzz")) }

        assertEquals(Outcome.Err(ModrinthError.UnknownProjects(listOf("zzzzzzzz"))), projects)
        assertEquals(0, server.hits(RecordedModrinth.TEAMS))
    }

    @Test
    fun `a team Modrinth does not return has no members`() {
        recording.answerProjectsFromRecording()
        server.serve(RecordedModrinth.TEAMS, "[]".toByteArray())

        val projects = runBlocking { Modrinth(http, recording.settings).projects(listOf("P7dR8mSH")) }

        assertEquals(
            Outcome.Ok(mapOf("P7dR8mSH" to ModrinthProject("P7dR8mSH", "Fabric API", 293_803_425, emptyList(), listOf("library")))),
            projects,
        )
    }
}
