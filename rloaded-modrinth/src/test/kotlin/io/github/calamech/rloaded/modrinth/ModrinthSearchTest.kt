package io.github.calamech.rloaded.modrinth

import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.TestServer
import io.github.calamech.rloaded.core.download.respond
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ModrinthSearchTest {
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
    fun `lists the Fabric mods for the game version that match, as Modrinth ranks them`() {
        serve(SearchRecordings.of("sodium"))

        val hits = (runBlocking { modrinth.search(" sodium ", "26.3", 3) } as Outcome.Ok).value

        assertEquals(ModrinthHit("AANobbMI", "sodium", "Sodium", "jellysquid3", 257_628_792), hits.first())
        assertEquals(3, hits.size)
        assertEquals(
            listOf("""query=sodium&limit=3&facets=[["categories:fabric"],["versions:26.3"],["project_type:mod"]]"""),
            queries.map { URLDecoder.decode(it, StandardCharsets.UTF_8) },
        )
    }

    @Test
    fun `sends no empty or overlong query, and refuses an answer it cannot trust`() {
        assertEquals(Outcome.Err(ModrinthError.InvalidQuery(" ")), runBlocking { modrinth.search(" ", "26.3", 3) })
        val long = "a".repeat(Modrinth.MAX_QUERY + 1)
        assertEquals(Outcome.Err(ModrinthError.InvalidQuery(long)), runBlocking { modrinth.search(long, "26.3", 3) })
        assertEquals(emptyList(), queries)

        serve(SearchRecordings.of("sodium"))
        assertEquals(
            Outcome.Err(ModrinthError.Malformed("3 search hits for 2 asked")),
            runBlocking { modrinth.search("sodium", "26.3", 2) },
        )
        serve("""{"hits": [{"project_id": "../x", "slug": "x", "title": "X", "author": "a", "downloads": 1}]}""")
        assertIs<Outcome.Err<ModrinthError.Malformed>>(runBlocking { modrinth.search("x", "26.3", 3) })
    }

    private fun serve(body: String) {
        server.route("/v2/search") { exchange, _ ->
            queries += exchange.requestURI.rawQuery
            exchange.respond(200, body.toByteArray())
        }
    }
}
