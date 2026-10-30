package io.github.calamech.rloaded.gametests

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.fileSize
import kotlin.io.path.name
import kotlin.io.path.readBytes

/** A mod the fake Modrinth serves: its ids, its title and its jar. */
internal data class FakeMod(
    val projectId: String,
    val versionId: String,
    val title: String,
    val jar: Path,
) {
    val sha512: String get() = MessageDigest.getInstance("SHA-512").digest(jar.readBytes()).toHexString()
    val size: Long get() = jar.fileSize()
}

/**
 * Stands in for Modrinth on the loopback interface, for mods that are not on Modrinth: the versions,
 * projects and teams endpoints Rloaded reads, answering the way Modrinth does (unknown ids left out),
 * and the files.
 */
internal class FakeModrinth(
    private val mods: List<FakeMod>,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(LOOPBACK, 0), 0)

    /** The base URL clients use instead of Modrinth's. */
    val url: String get() = "http://$LOOPBACK:${server.address.port}"

    init {
        server.createContext("/v2/versions") { exchange -> json(exchange, wanted(exchange).joinToString(",", "[", "]") { version(it) }) }
        server.createContext("/v2/projects") { exchange ->
            json(exchange, wanted(exchange, FakeMod::projectId).joinToString(",", "[", "]") { project(it) })
        }
        server.createContext("/v2/teams") { exchange ->
            val teams = mods.filter { "team${it.projectId}" in ids(exchange) }
            json(exchange, teams.joinToString(",", "[", "]") { """[{"team_id": "team${it.projectId}", "user": {"username": "Tester"}}]""" })
        }
        server.createContext("/cdn/") { exchange ->
            val mod = mods.find { exchange.requestURI.path == "/cdn/${it.jar.name}" }
            val body = mod?.jar?.readBytes() ?: ByteArray(0)
            exchange.sendResponseHeaders(if (mod == null) NOT_FOUND else OK, if (body.isEmpty()) -1 else body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    private fun version(mod: FakeMod): String =
        """{"id": "${mod.versionId}", "project_id": "${mod.projectId}", "files": [{"url": "$url/cdn/${mod.jar.name}", """ +
            """"filename": "${mod.jar.name}", "size": ${mod.size}, "hashes": {"sha512": "${mod.sha512}"}}]}"""

    private fun project(mod: FakeMod): String =
        """{"id": "${mod.projectId}", "title": "${mod.title}", "downloads": 1234, "team": "team${mod.projectId}"}"""

    private fun wanted(
        exchange: HttpExchange,
        id: (FakeMod) -> String = FakeMod::versionId,
    ): List<FakeMod> = mods.filter { id(it) in ids(exchange) }

    private fun ids(exchange: HttpExchange): List<String> =
        URLDecoder
            .decode(exchange.requestURI.rawQuery.removePrefix("ids="), Charsets.UTF_8)
            .removeSurrounding("[", "]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }

    private fun json(
        exchange: HttpExchange,
        body: String,
    ) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(OK, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.stop(0)
    }

    private companion object {
        const val OK = 200
        const val NOT_FOUND = 404
    }
}
