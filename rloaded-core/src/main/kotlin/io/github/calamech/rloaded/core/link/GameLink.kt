package io.github.calamech.rloaded.core.link

import io.github.calamech.rloaded.protocol.LINK_PROTOCOL_VERSION
import io.github.calamech.rloaded.protocol.LinkEvent
import io.github.calamech.rloaded.protocol.LinkHello
import io.github.calamech.rloaded.protocol.LinkRequest
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.flatMap
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The launcher's end of the link: listens on the loopback interface for the game it starts, which
 * proves with [token] that the launcher started it. Pass [environment] to the game's process.
 */
public class GameLink private constructor(
    private val socket: ServerSocket,
    private val token: String,
    private val settings: LinkSettings,
) : AutoCloseable {
    /** The variables that tell the game where its launcher listens and with which token. */
    public val environment: Map<String, String>
        get() = mapOf(Link.PORT_VARIABLE to socket.localPort.toString(), Link.TOKEN_VARIABLE to token)

    /** Waits for the game, and returns its connection once it proved its token. */
    public fun accept(): Outcome<GameConnection, LinkError> =
        connection().flatMap { connection -> hello(connection).also { if (it is Outcome.Err) connection.close() } }

    private fun connection(): Outcome<Socket, LinkError> =
        try {
            socket.soTimeout = settings.connectTimeout.toMillis().toInt()
            Outcome.Ok(socket.accept())
        } catch (_: SocketTimeoutException) {
            Outcome.Err(LinkError.TimedOut)
        } catch (e: IOException) {
            Outcome.Err(LinkError.Closed(e.message ?: e.javaClass.simpleName))
        }

    private fun hello(connection: Socket): Outcome<GameConnection, LinkError> {
        val streams =
            try {
                connection.soTimeout = settings.helloTimeout.toMillis().toInt()
                DataInputStream(connection.getInputStream()) to DataOutputStream(connection.getOutputStream())
            } catch (e: IOException) {
                return Outcome.Err(LinkError.Closed(e.message ?: e.javaClass.simpleName))
            }
        return Link.read(streams.first, LinkHello.serializer()).flatMap { hello ->
            when {
                hello.protocol != LINK_PROTOCOL_VERSION -> Outcome.Err(LinkError.OtherProtocol(hello.protocol))
                !MessageDigest.isEqual(hello.token.toByteArray(), token.toByteArray()) -> Outcome.Err(LinkError.WrongToken)
                else -> Outcome.Ok(GameConnection(connection, streams.first, streams.second))
            }
        }
    }

    override fun close() {
        socket.close()
    }

    /** Opening the launcher's end. */
    public companion object {
        private const val TOKEN_BYTES = 32

        /** Listens on a free loopback port, with a new random token. */
        public fun open(settings: LinkSettings): Outcome<GameLink, LinkError> =
            try {
                val socket = ServerSocket()
                socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1)
                val bytes = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
                Outcome.Ok(GameLink(socket, bytes.toHexString(), settings))
            } catch (e: IOException) {
                Outcome.Err(LinkError.Unavailable(e.message ?: e.javaClass.simpleName))
            }
    }
}

/** The launcher's connection to its game: sends requests and reads the game's events. */
public class GameConnection internal constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
) : AutoCloseable {
    /** Sends [request] to the game. */
    @Synchronized
    public fun send(request: LinkRequest): Outcome<Unit, LinkError> = Link.write(output, LinkRequest.serializer(), request)

    /** Waits for the game's next event, as long as the game runs. */
    public fun receive(): Outcome<LinkEvent, LinkError> = setNoTimeout().flatMap { Link.read(input, LinkEvent.serializer()) }

    private fun setNoTimeout(): Outcome<Unit, LinkError> =
        try {
            socket.soTimeout = 0
            Outcome.Ok(Unit)
        } catch (e: IOException) {
            Outcome.Err(LinkError.Closed(e.message ?: e.javaClass.simpleName))
        }

    override fun close() {
        socket.close()
    }
}
