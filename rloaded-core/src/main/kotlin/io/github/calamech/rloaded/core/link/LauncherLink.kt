package io.github.calamech.rloaded.core.link

import io.github.calamech.rloaded.protocol.LINK_PROTOCOL_VERSION
import io.github.calamech.rloaded.protocol.LinkEvent
import io.github.calamech.rloaded.protocol.LinkHello
import io.github.calamech.rloaded.protocol.LinkRequest
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.flatMap
import io.github.calamech.rloaded.protocol.map
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** The game's end of the link: reads the launcher's requests and sends it events. */
public class LauncherLink private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
) : AutoCloseable {
    /** Waits for the launcher's next request, as long as the launcher runs. */
    public fun receive(): Outcome<LinkRequest, LinkError> = Link.read(input, LinkRequest.serializer())

    /** Sends [event] to the launcher. */
    @Synchronized
    public fun send(event: LinkEvent): Outcome<Unit, LinkError> = Link.write(output, LinkEvent.serializer(), event)

    override fun close() {
        socket.close()
    }

    /** Connecting to the launcher. */
    public companion object {
        /**
         * Connects to the launcher that [environment] names, and proves the game is the one it
         * started. A game started without a launcher has no link: the result is null then.
         */
        public fun connect(
            environment: (String) -> String?,
            settings: LinkSettings,
        ): Outcome<LauncherLink?, LinkError> {
            val port = environment(Link.PORT_VARIABLE)?.toIntOrNull()
            val token = environment(Link.TOKEN_VARIABLE)
            if (port == null || token == null) return Outcome.Ok(null)
            return hello(token).flatMap { hello ->
                open(port, settings).flatMap { link ->
                    Link.write(link.output, LinkHello.serializer(), hello).map { link }.also { if (it is Outcome.Err) link.close() }
                }
            }
        }

        private fun hello(token: String): Outcome<LinkHello, LinkError> =
            try {
                Outcome.Ok(LinkHello(LINK_PROTOCOL_VERSION, token))
            } catch (_: IllegalArgumentException) {
                Outcome.Err(LinkError.WrongToken)
            }

        private fun open(
            port: Int,
            settings: LinkSettings,
        ): Outcome<LauncherLink, LinkError> {
            val socket = Socket()
            return try {
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), settings.connectTimeout.toMillis().toInt())
                Outcome.Ok(LauncherLink(socket, DataInputStream(socket.getInputStream()), DataOutputStream(socket.getOutputStream())))
            } catch (e: IOException) {
                socket.close()
                Outcome.Err(LinkError.Closed(e.message ?: e.javaClass.simpleName))
            }
        }
    }
}
