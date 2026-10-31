package io.github.calamech.rloaded.core.link

import io.github.calamech.rloaded.protocol.LinkEvent
import io.github.calamech.rloaded.protocol.LinkRequest
import io.github.calamech.rloaded.protocol.Outcome
import org.junit.jupiter.api.Test
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LinkTest {
    private val settings = LinkSettings(Duration.ofSeconds(5), Duration.ofSeconds(2))

    private fun open() = (GameLink.open(settings) as Outcome.Ok).value

    private fun connect(environment: Map<String, String>) = LauncherLink.connect(environment::get, settings)

    @Test
    fun `carries requests to the game and events back`() {
        open().use { link ->
            val accepted = CompletableFuture.supplyAsync { link.accept() }
            val game = (connect(link.environment) as Outcome.Ok).value
            val launcher = (accepted.get() as Outcome.Ok).value

            launcher.send(LinkRequest.Add("r1", "content-basic", null))
            assertEquals(Outcome.Ok(LinkRequest.Add("r1", "content-basic", null)), game?.receive())
            game?.send(LinkEvent.Added("r1", listOf("content-basic.jar")))
            assertEquals(Outcome.Ok(LinkEvent.Added("r1", listOf("content-basic.jar"))), launcher.receive())

            game?.close()
            assertIs<Outcome.Err<LinkError.Closed>>(launcher.receive())
            launcher.close()
        }
    }

    @Test
    fun `listens on the loopback interface only`() {
        open().use { link ->
            val port = link.environment.getValue(Link.PORT_VARIABLE).toInt()
            assertTrue(port > 0)
            val probe = java.net.ServerSocket()
            // The port is taken on the loopback address: binding it there again fails.
            assertTrue(runCatching { probe.bind(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port)) }.isFailure)
            probe.close()
        }
    }

    @Test
    fun `refuses a game with another token`() {
        open().use { link ->
            val accepted = CompletableFuture.supplyAsync { link.accept() }
            connect(link.environment + (Link.TOKEN_VARIABLE to "0".repeat(64)))

            assertEquals(Outcome.Err(LinkError.WrongToken), accepted.get())
        }
    }

    @Test
    fun `refuses a game that speaks another version`() {
        open().use { link ->
            val accepted = CompletableFuture.supplyAsync { link.accept() }
            val token = link.environment.getValue(Link.TOKEN_VARIABLE)
            Socket(InetAddress.getLoopbackAddress(), link.environment.getValue(Link.PORT_VARIABLE).toInt()).use { socket ->
                val hello =
                    io.github.calamech.rloaded.protocol
                        .LinkHello(2, token)
                Link.write(
                    DataOutputStream(socket.getOutputStream()),
                    io.github.calamech.rloaded.protocol.LinkHello
                        .serializer(),
                    hello,
                )

                assertEquals(Outcome.Err(LinkError.OtherProtocol(2)), accepted.get())
            }
        }
    }

    @Test
    fun `refuses a message larger than the limit`() {
        open().use { link ->
            val accepted = CompletableFuture.supplyAsync { link.accept() }
            Socket(InetAddress.getLoopbackAddress(), link.environment.getValue(Link.PORT_VARIABLE).toInt()).use { socket ->
                DataOutputStream(socket.getOutputStream()).writeInt(Link.MAX_MESSAGE_BYTES + 1)

                assertEquals(Outcome.Err(LinkError.TooLarge(Link.MAX_MESSAGE_BYTES + 1)), accepted.get())
            }
        }
    }

    @Test
    fun `gives up on a game that does not say hello, or never comes`() {
        val quick = LinkSettings(Duration.ofMillis(300), Duration.ofMillis(300))
        (GameLink.open(quick) as Outcome.Ok).value.use { link ->
            assertEquals(Outcome.Err(LinkError.TimedOut), link.accept())

            val accepted = CompletableFuture.supplyAsync { link.accept() }
            Socket(InetAddress.getLoopbackAddress(), link.environment.getValue(Link.PORT_VARIABLE).toInt()).use {
                assertIs<Outcome.Err<LinkError.Closed>>(accepted.get())
            }
        }
    }

    @Test
    fun `has no link when the launcher did not start the game, and refuses a broken token`() {
        assertEquals(Outcome.Ok(null), connect(emptyMap()))
        assertEquals(Outcome.Err(LinkError.WrongToken), connect(mapOf(Link.PORT_VARIABLE to "1", Link.TOKEN_VARIABLE to "short")))
    }
}
