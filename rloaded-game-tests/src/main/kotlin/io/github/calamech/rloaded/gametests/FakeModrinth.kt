package io.github.calamech.rloaded.gametests

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.io.path.fileSize
import kotlin.io.path.name
import kotlin.io.path.readBytes

/** A mod the fake Modrinth serves: its ids, its title, its jar, and how long its file takes to come. */
internal data class FakeMod(
    val projectId: String,
    val versionId: String,
    val title: String,
    val jar: Path,
    val fileDelay: Duration = Duration.ZERO,
) {
    val sha512: String get() = MessageDigest.getInstance("SHA-512").digest(jar.readBytes()).toHexString()
    val size: Long get() = jar.fileSize()
}

/**
 * Stands in for Modrinth on the loopback interface, for mods that are not on Modrinth: the versions,
 * project versions, projects and teams endpoints Rloaded reads, answering the way Modrinth does (unknown ids left out),
 * and the files. It also serves [files] that are not mods, under `/files/<name>`.
 */
internal class FakeModrinth(
    private val mods: List<FakeMod>,
    private val files: Map<String, ByteArray> = emptyMap(),
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(LOOPBACK, 0), 0)

    // A thread per request, so a delayed file does not hold back the others.
    private val executor = Executors.newCachedThreadPool()

    /** The base URL clients use instead of Modrinth's. */
    val url: String get() = "http://$LOOPBACK:${server.address.port}"

    init {
        server.createContext("/v2/versions") { exchange -> json(exchange, wanted(exchange).joinToString(",", "[", "]") { version(it) }) }
        // A project's versions, the way an add resolves it.
        server.createContext("/v2/project/") { exchange ->
            val project =
                exchange.requestURI.path
                    .removePrefix("/v2/project/")
                    .removeSuffix("/version")
            val listed = mods.filter { it.projectId == project }
            if (listed.isEmpty()) {
                exchange.sendResponseHeaders(NOT_FOUND, -1)
                exchange.close()
            } else {
                json(exchange, listed.joinToString(",", "[", "]") { version(it) })
            }
        }
        server.createContext("/v2/projects") { exchange ->
            json(exchange, wanted(exchange, FakeMod::projectId).joinToString(",", "[", "]") { project(it) })
        }
        server.createContext("/v2/teams") { exchange ->
            val teams = mods.filter { "team${it.projectId}" in ids(exchange) }
            json(exchange, teams.joinToString(",", "[", "]") { """[{"team_id": "team${it.projectId}", "user": {"username": "Tester"}}]""" })
        }
        server.createContext("/cdn/") { exchange ->
            val mod = mods.find { exchange.requestURI.path == "/cdn/${it.jar.name}" }
            Thread.sleep(mod?.fileDelay ?: Duration.ZERO)
            val body = mod?.jar?.readBytes() ?: ByteArray(0)
            exchange.sendResponseHeaders(if (mod == null) NOT_FOUND else OK, if (body.isEmpty()) -1 else body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/files/") { exchange ->
            val body = files[exchange.requestURI.path.removePrefix("/files/")]
            exchange.sendResponseHeaders(if (body == null) NOT_FOUND else OK, body?.size?.toLong() ?: -1)
            exchange.responseBody.use { it.write(body ?: ByteArray(0)) }
        }
        server.executor = executor
        server.start()
    }

    private fun version(mod: FakeMod): String =
        """{"id": "${mod.versionId}", "project_id": "${mod.projectId}", "files": [{"url": "$url/cdn/${mod.jar.name}", """ +
            """"filename": "${mod.jar.name}", "size": ${mod.size}, "hashes": {"sha512": "${mod.sha512}"}, """ +
            """"primary": true}], "version_number": "1.0.0", "version_type": "release", """ +
            """"date_published": "2026-10-01T00:00:00Z", """ +
            """"game_versions": ["26.3"], "loaders": ["fabric"], "dependencies": []}"""

    private fun project(mod: FakeMod): String =
        """{"id": "${mod.projectId}", "title": "${mod.title}", "downloads": 1234, "team": "team${mod.projectId}", """ +
            """"categories": ["utility"], "additional_categories": []}"""

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
        executor.shutdownNow()
    }

    private companion object {
        const val OK = 200
        const val NOT_FOUND = 404
    }
}
