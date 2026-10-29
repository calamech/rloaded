package io.github.calamech.rloaded.core.download

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A real HTTP server on a free loopback port, with per-path request counters. */
class TestServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val routes = ConcurrentHashMap.newKeySet<String>()

    val origin: Origin get() = Origin("http", "127.0.0.1", server.address.port)

    /** A policy that allows this server only. */
    val policy: UrlPolicy get() = UrlPolicy(setOf(origin))

    init {
        server.start()
    }

    fun url(path: String): String = "http://127.0.0.1:${server.address.port}$path"

    /** Answers [path] with [handler], which receives the 1-based number of the request. Replaces an earlier route. */
    fun route(
        path: String,
        handler: (HttpExchange, Int) -> Unit,
    ) {
        if (!routes.add(path)) server.removeContext(path)
        server.createContext(path) { exchange ->
            val count = hits.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            exchange.use { handler(it, count) }
        }
    }

    fun serve(
        path: String,
        body: ByteArray,
        status: Int = 200,
    ) = route(path) { exchange, _ -> exchange.respond(status, body) }

    fun hits(path: String): Int = hits[path]?.get() ?: 0

    override fun close() {
        server.stop(0)
    }
}

fun HttpExchange.respond(
    status: Int,
    body: ByteArray = ByteArray(0),
) {
    sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
    if (body.isNotEmpty()) responseBody.write(body)
}

fun HttpExchange.redirect(location: String) {
    responseHeaders.add("Location", location)
    sendResponseHeaders(FOUND, -1)
}

private const val FOUND = 302
