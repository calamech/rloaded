package io.github.calamech.rloaded.modrinth

import io.github.calamech.rloaded.core.download.DownloadError
import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.TestServer
import io.github.calamech.rloaded.modrinth.RecordedModrinth.Companion.FABRIC_API
import io.github.calamech.rloaded.modrinth.RecordedModrinth.Companion.MOD_MENU
import io.github.calamech.rloaded.modrinth.RecordedModrinth.Companion.recorded
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ModrinthTest {
    private val server = TestServer()
    private val recording = RecordedModrinth(server)
    private val http = KtorHttp(server.policy, HttpSettings())

    @AfterEach
    fun close() {
        http.close()
        server.close()
    }

    @Test
    fun `reads the recorded versions in one request`() {
        recording.answerFromRecording()

        val versions = ok(versions(listOf(MOD_MENU, FABRIC_API)))

        assertEquals(setOf(FABRIC_API, MOD_MENU), versions.keys)
        val fabricApi = versions.getValue(FABRIC_API)
        assertEquals("P7dR8mSH", fabricApi.projectId)
        val file = fabricApi.files.single()
        assertEquals("fabric-api-0.165.0+26.3.jar", file.fileName)
        assertEquals(1_778_588, file.size)
        assertEquals("https://cdn.modrinth.com/data/P7dR8mSH/versions/EQKgpeKu/fabric-api-0.165.0%2B26.3.jar", file.url)
        // The same hash as runtime-pins.json, recorded from Modrinth's per-project listing on another day.
        assertEquals(
            "0a4b8a14e847b71567b557b43d906c2167fa263eeeef37454a821ba286996e3d" +
                "a1616dc2d9647736d28a250d5ecf400541cae808359b29a62324a8a6f2ad5e30",
            file.sha512.hex,
        )
        assertEquals(listOf("ids=%5B%22EQKgpeKu%22%2C%22kyy7dbrZ%22%5D"), recording.queries)
    }

    @Test
    fun `refuses when Modrinth leaves a requested version out`() {
        recording.answerFromRecording()

        assertEquals(Outcome.Err(ModrinthError.UnknownVersions(listOf("zzzzzzzz"))), versions(listOf(FABRIC_API, "zzzzzzzz")))
    }

    @Test
    fun `splits large sets into several requests`() {
        recording.answerFromRecording()

        val versions =
            ok(
                Modrinth(
                    http,
                    recording.settings.copy(maxIdsPerRequest = 1),
                ).let { runBlocking { it.versions(listOf(FABRIC_API, MOD_MENU)) } },
            )

        assertEquals(setOf(FABRIC_API, MOD_MENU), versions.keys)
        assertEquals(2, recording.hits())
    }

    @Test
    fun `refuses an id that is not a Modrinth id without asking Modrinth`() {
        recording.answerFromRecording()

        assertEquals(Outcome.Err(ModrinthError.InvalidId("bad\"id")), versions(listOf(FABRIC_API, "bad\"id")))
        assertEquals(0, recording.hits())
    }

    @Test
    fun `tells a rate limit apart from other failures`() {
        recording.answer("", status = 429)
        val limited = versions(listOf(FABRIC_API))
        recording.answer("", status = 503)
        val failed = versions(listOf(FABRIC_API))

        assertIs<ModrinthError.RateLimited>((limited as Outcome.Err).error)
        assertEquals(Outcome.Err(ModrinthError.Download(DownloadError.HttpStatus(urlFor(FABRIC_API), 503))), failed)
    }

    @Test
    fun `refuses versions that were not asked for`() {
        recording.answer(RecordedModrinth.RECORDED.toString())

        assertEquals(Outcome.Err(ModrinthError.Malformed("versions not asked for: [$MOD_MENU]")), versions(listOf(FABRIC_API)))
    }

    @Test
    fun `refuses a version listed twice`() {
        val once = recorded(FABRIC_API)
        recording.answer("[${once.removeSurrounding("[", "]")},${once.removeSurrounding("[", "]")}]")

        assertEquals(Outcome.Err(ModrinthError.Malformed("a version is listed twice")), versions(listOf(FABRIC_API)))
    }

    @Test
    fun `refuses a file without a valid SHA-512`() {
        recording.answer(recorded(FABRIC_API) { withFirstFileHash(it, "0a4b") })

        assertEquals(
            Outcome.Err(ModrinthError.Malformed("version $FABRIC_API: fabric-api-0.165.0+26.3.jar has no valid SHA-512")),
            versions(listOf(FABRIC_API)),
        )
    }

    @Test
    fun `refuses an answer without a field Rloaded checks`() {
        recording.answer(recorded(FABRIC_API) { JsonObject(it - "project_id") })

        val error = (versions(listOf(FABRIC_API)) as Outcome.Err).error

        assertIs<ModrinthError.Malformed>(error)
        assertEquals(true, error.reason.contains("project_id"), error.reason)
    }

    @Test
    fun `refuses an answer that is not JSON`() {
        recording.answer("<html>maintenance</html>")

        assertIs<ModrinthError.Malformed>((versions(listOf(FABRIC_API)) as Outcome.Err).error)
    }

    private fun versions(ids: List<String>) = runBlocking { Modrinth(http, recording.settings).versions(ids) }

    private fun urlFor(id: String) = server.url("/v2/versions?ids=%5B%22$id%22%5D")

    private fun <T> ok(outcome: Outcome<T, ModrinthError>): T =
        when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw AssertionError("expected a value, got ${outcome.error}")
        }

    private fun withFirstFileHash(
        version: JsonObject,
        sha512: String,
    ): JsonObject {
        val file =
            version
                .getValue("files")
                .jsonArray
                .first()
                .jsonObject
        val hashes = JsonObject(file.getValue("hashes").jsonObject + ("sha512" to JsonPrimitive(sha512)))
        return JsonObject(version + ("files" to kotlinx.serialization.json.JsonArray(listOf(JsonObject(file + ("hashes" to hashes))))))
    }
}
