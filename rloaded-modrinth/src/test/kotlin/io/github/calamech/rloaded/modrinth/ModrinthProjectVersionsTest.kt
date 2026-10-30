package io.github.calamech.rloaded.modrinth

import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.TestServer
import io.github.calamech.rloaded.core.download.respond
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ModrinthProjectVersionsTest {
    private val server = TestServer()
    private val http = KtorHttp(server.policy, HttpSettings())
    private val modrinth = Modrinth(http, ModrinthSettings(apiUrl = server.url("/v2")))
    private val queries = mutableListOf<String>()

    @AfterEach
    fun close() {
        http.close()
        server.close()
    }

    @Test
    fun `lists a project's Fabric versions for the game version, newest first, with their dependencies`() {
        serve("modmenu", recording("modmenu"))

        val versions = ok(versions("modmenu"))

        assertEquals(listOf("21.0.0", "21.0.0-beta.1"), versions.map { it.versionNumber })
        val release = versions.first()
        assertEquals(VersionType.RELEASE, release.type)
        assertEquals(VersionType.BETA, versions.last().type)
        assertEquals(Instant.parse("2026-09-23T21:31:03.843943Z"), release.publishedAt)
        assertEquals(
            listOf(
                ModrinthDependency("eXts2L7r", null, DependencyType.REQUIRED),
                ModrinthDependency("P7dR8mSH", null, DependencyType.REQUIRED),
            ),
            release.dependencies,
        )
        assertEquals("modmenu-21.0.0.jar", release.primaryFile?.fileName)
        assertEquals(
            listOf("""loaders=["fabric"]&game_versions=["26.3"]&include_changelog=false"""),
            queries.map { URLDecoder.decode(it, StandardCharsets.UTF_8) },
        )
    }

    @Test
    fun `orders by publication date whatever order Modrinth answers in`() {
        serve("P7dR8mSH", JsonArray(Json.parseToJsonElement(recording("P7dR8mSH")).jsonArray.reversed()).toString())

        val versions = ok(versions("P7dR8mSH"))

        assertEquals(versions.sortedByDescending { it.publishedAt }, versions)
        assertEquals("0.165.0+26.3", versions.first().versionNumber)
    }

    @Test
    fun `reports a project Modrinth does not have`() {
        server.serve("/v2/project/nothing/version", ByteArray(0), status = 404)

        assertEquals(Outcome.Err(ModrinthError.UnknownProjects(listOf("nothing"))), versions("nothing"))
    }

    @Test
    fun `refuses a slug that would need escaping, without asking Modrinth`() {
        assertEquals(Outcome.Err(ModrinthError.InvalidId("../projects")), versions("../projects"))
        assertEquals(Outcome.Err(ModrinthError.InvalidId("a b")), versions("a b"))
    }

    @Test
    fun `refuses an answer with a version for another loader, or an unknown dependency type`() {
        serve("modmenu", recording("modmenu").replace("\"loaders\":[\"fabric\",\"quilt\"]", "\"loaders\":[\"quilt\"]"))
        assertEquals(Outcome.Err(ModrinthError.Malformed("version kyy7dbrZ is not for Fabric 26.3")), versions("modmenu"))

        serve("modmenu", recording("modmenu").replace("\"dependency_type\":\"required\"", "\"dependency_type\":\"recommended\""))
        assertIs<ModrinthError.Malformed>((versions("modmenu") as Outcome.Err).error)
    }

    private fun versions(project: String) = runBlocking { modrinth.projectVersions(project, "26.3") }

    private fun serve(
        project: String,
        body: String,
    ) = server.route("/v2/project/$project/version") { exchange, _ ->
        queries += exchange.requestURI.rawQuery
        exchange.respond(200, body.toByteArray())
    }

    private fun recording(project: String): String = ProjectVersionRecordings.of(project)

    private fun <T> ok(outcome: Outcome<T, ModrinthError>): T =
        when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw AssertionError("expected versions, got ${outcome.error}")
        }
}
