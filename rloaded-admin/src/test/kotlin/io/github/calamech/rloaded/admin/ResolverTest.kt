package io.github.calamech.rloaded.admin

import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.TestServer
import io.github.calamech.rloaded.core.download.respond
import io.github.calamech.rloaded.modrinth.Modrinth
import io.github.calamech.rloaded.modrinth.ModrinthSettings
import io.github.calamech.rloaded.modrinth.ModrinthVersion
import io.github.calamech.rloaded.modrinth.ProjectVersionRecordings
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ResolverTest {
    private val server = TestServer()
    private val http = KtorHttp(server.policy, HttpSettings())
    private val resolver = Resolver(Modrinth(http, ModrinthSettings(apiUrl = server.url("/v2"))), "26.3")
    private val baseSet = mapOf(FABRIC_API to "EQKgpeKu", "Ha28R6CL" to "SKW1BTL3")

    @AfterEach
    fun close() {
        http.close()
        server.close()
    }

    @Test
    fun `picks the newest release and adds the required dependencies that are not installed, dependencies first`() {
        serveRecorded("modmenu", PLACEHOLDER_API)

        assertEquals(listOf("lXytLqWj", MOD_MENU_RELEASE), ids(resolve("modmenu", installed = baseSet)))
    }

    @Test
    fun `resolves dependencies that are not installed either, each at its newest release`() {
        serveRecorded("modmenu", PLACEHOLDER_API, FABRIC_API)

        assertEquals(listOf("lXytLqWj", "EQKgpeKu", MOD_MENU_RELEASE), ids(resolve("modmenu", installed = emptyMap())))
    }

    @Test
    fun `takes the version asked for by number or id, and refuses a beta or a version it does not list`() {
        serveRecorded("modmenu", PLACEHOLDER_API)

        assertEquals(listOf("lXytLqWj", MOD_MENU_RELEASE), ids(resolve("modmenu", "21.0.0")))
        assertEquals(listOf("lXytLqWj", MOD_MENU_RELEASE), ids(resolve("modmenu", MOD_MENU_RELEASE)))
        assertEquals(Outcome.Err(ResolveError.Prerelease("modmenu", "21.0.0-beta.1")), resolve("modmenu", "21.0.0-beta.1"))
        assertEquals(Outcome.Err(ResolveError.UnknownVersion("modmenu", "9.9.9")), resolve("modmenu", "9.9.9"))
    }

    @Test
    fun `refuses a project that is installed, unknown, without a version for the game, or without a release`() {
        serveRecorded("modmenu")
        server.serve("/v2/project/nothing/version", ByteArray(0), status = 404)
        serve("old", "[]")
        serve("betas", ProjectVersionRecordings.of("modmenu").replace("\"version_type\":\"release\"", "\"version_type\":\"beta\""))

        assertEquals(Outcome.Err(ResolveError.AlreadyInstalled("modmenu")), resolve("modmenu", installed = baseSet + ("mOgUt4GM" to "x")))
        assertEquals(Outcome.Err(ResolveError.UnknownProject("nothing")), resolve("nothing"))
        assertEquals(Outcome.Err(ResolveError.NoVersion("old", "26.3")), resolve("old"))
        assertEquals(Outcome.Err(ResolveError.NoRelease("betas")), resolve("betas"))
    }

    @Test
    fun `refuses a dependency pinned to another version than the installed one`() {
        serve("modmenu", modMenuReleaseDependency("""{"version_id":"LEklt8GT","project_id":"P7dR8mSH","dependency_type":"required"}"""))
        serveRecorded(PLACEHOLDER_API)
        serveVersions(FABRIC_API)

        assertEquals(Outcome.Err(ResolveError.Conflict(FABRIC_API, "EQKgpeKu", "LEklt8GT")), resolve("modmenu", installed = baseSet))
    }

    @Test
    fun `adds a pinned dependency, and refuses one that is not for Fabric on this game`() {
        serve("modmenu", modMenuReleaseDependency("""{"version_id":"LEklt8GT","project_id":"P7dR8mSH","dependency_type":"required"}"""))
        serveRecorded(PLACEHOLDER_API)
        serveVersions(FABRIC_API)

        assertEquals(listOf("lXytLqWj", "LEklt8GT", MOD_MENU_RELEASE), ids(resolve("modmenu", installed = emptyMap())))

        serveVersions(FABRIC_API) { it.replace("\"loaders\":[\"fabric\"]", "\"loaders\":[\"quilt\"]") }
        assertEquals(Outcome.Err(ResolveError.DependencyNotForGame("LEklt8GT")), resolve("modmenu", installed = emptyMap()))
    }

    @Test
    fun `refuses a version incompatible with an installed project, and a dependency outside Modrinth`() {
        serve("modmenu", modMenuReleaseDependency("""{"project_id":"P7dR8mSH","dependency_type":"incompatible"}"""))
        serveRecorded(PLACEHOLDER_API)
        assertEquals(Outcome.Err(ResolveError.Incompatible(MOD_MENU_RELEASE, FABRIC_API)), resolve("modmenu", installed = baseSet))

        serve("modmenu", modMenuReleaseDependency("""{"file_name":"external.jar","dependency_type":"required"}"""))
        assertEquals(Outcome.Err(ResolveError.ExternalDependency(MOD_MENU_RELEASE)), resolve("modmenu", installed = baseSet))
    }

    @Test
    fun `follows a dependency cycle once`() {
        serveRecorded("modmenu")
        serve(
            PLACEHOLDER_API,
            ProjectVersionRecordings
                .of(PLACEHOLDER_API)
                .replace("\"dependencies\":[]", """"dependencies":[{"project_id":"mOgUt4GM","dependency_type":"required"}]"""),
        )

        assertEquals(listOf("lXytLqWj", MOD_MENU_RELEASE), ids(resolve("modmenu", installed = baseSet)))
    }

    @Test
    fun `explains each refusal`() {
        assertEquals(
            listOf(
                "Modrinth has no project nothing",
                "old has no Fabric version for Minecraft 26.3",
                "modmenu is already installed",
                "a dependency needs version b of p, but version a is installed or chosen",
            ),
            listOf(
                ResolveError.UnknownProject("nothing"),
                ResolveError.NoVersion("old", "26.3"),
                ResolveError.AlreadyInstalled("modmenu"),
                ResolveError.Conflict("p", "a", "b"),
            ).map { it.reason },
        )
    }

    private fun resolve(
        project: String,
        version: String? = null,
        installed: Map<String, String> = baseSet,
    ) = runBlocking { resolver.resolve(project, version, installed) }

    private fun ids(outcome: Outcome<List<ModrinthVersion>, ResolveError>): List<String> =
        when (outcome) {
            is Outcome.Ok -> outcome.value.map { it.id }
            is Outcome.Err -> throw AssertionError("expected versions, got ${outcome.error.reason}")
        }

    private fun serveRecorded(vararg projects: String) = projects.forEach { serve(it, ProjectVersionRecordings.of(it)) }

    private fun serve(
        project: String,
        body: String,
    ) = server.route("/v2/project/$project/version") { exchange, _ -> exchange.respond(200, body.toByteArray()) }

    // Answers /v2/versions with the recorded versions of [project] that are asked for.
    private fun serveVersions(
        project: String,
        change: (String) -> String = { it },
    ) = server.route("/v2/versions") { exchange, _ ->
        val asked = exchange.requestURI.query.removePrefix("ids=")
        val versions = Json.parseToJsonElement(change(ProjectVersionRecordings.of(project))).jsonArray
        val answer = JsonArray(versions.filter { "\"${it.jsonObject.getValue("id").jsonPrimitive.content}\"" in asked })
        exchange.respond(200, answer.toString().toByteArray())
    }

    // Mod Menu's recorded versions, with the release's second dependency replaced by [dependency].
    private fun modMenuReleaseDependency(dependency: String): String =
        ProjectVersionRecordings
            .of("modmenu")
            .replaceFirst("""{"version_id":null,"project_id":"P7dR8mSH","file_name":null,"dependency_type":"required"}""", dependency)

    private companion object {
        const val FABRIC_API = "P7dR8mSH"
        const val PLACEHOLDER_API = "eXts2L7r"
        const val MOD_MENU_RELEASE = "kyy7dbrZ"
    }
}
