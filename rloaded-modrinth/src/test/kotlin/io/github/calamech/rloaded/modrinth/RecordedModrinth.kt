package io.github.calamech.rloaded.modrinth

import com.sun.net.httpserver.HttpExchange
import io.github.calamech.rloaded.core.download.TestServer
import io.github.calamech.rloaded.core.download.respond
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Answers `/v2/versions` from versions recorded on api.modrinth.com, the way Modrinth does: only
 * the requested versions it knows, unknown ids left out. Recorded on 2026-10-30.
 */
class RecordedModrinth(
    private val server: TestServer,
) {
    val settings: ModrinthSettings get() = ModrinthSettings(apiUrl = server.url("/v2"))

    /** The query string of each request, in order. */
    val queries: MutableList<String> = mutableListOf()

    fun answerFromRecording() =
        server.route(PATH) { exchange, _ ->
            queries += exchange.requestURI.rawQuery
            val wanted = requestedIds(exchange)
            val answer =
                JsonArray(
                    RECORDED.filter {
                        it.jsonObject
                            .getValue("id")
                            .jsonPrimitive.content in wanted
                    },
                )
            exchange.respond(200, answer.toString().toByteArray())
        }

    fun answer(
        body: String,
        status: Int = 200,
    ) = server.serve(PATH, body.toByteArray(), status)

    fun hits(): Int = server.hits(PATH)

    companion object {
        const val PATH = "/v2/versions"
        const val FABRIC_API = "EQKgpeKu"
        const val MOD_MENU = "kyy7dbrZ"

        /** The versions recorded, as Modrinth returned them. */
        val RECORDED: JsonArray =
            Json
                .parseToJsonElement(
                    RecordedModrinth::class.java.getResource("versions-EQKgpeKu-kyy7dbrZ-zzzzzzzz.json")?.readText()
                        ?: error("recording missing"),
                ).jsonArray

        /** One recorded version with [change] applied to its JSON object. */
        fun recorded(
            id: String,
            change: (JsonObject) -> JsonObject = { it },
        ): String =
            JsonArray(
                listOf(
                    change(
                        RECORDED
                            .first {
                                it.jsonObject
                                    .getValue("id")
                                    .jsonPrimitive.content == id
                            }.jsonObject,
                    ),
                ),
            ).toString()

        private fun requestedIds(exchange: HttpExchange): Set<String> {
            val query = URLDecoder.decode(exchange.requestURI.rawQuery.removePrefix("ids="), StandardCharsets.UTF_8)
            return Json
                .parseToJsonElement(query)
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()
        }
    }
}
