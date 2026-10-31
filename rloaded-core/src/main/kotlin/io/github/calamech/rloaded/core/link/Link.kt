package io.github.calamech.rloaded.core.link

import io.github.calamech.rloaded.protocol.DecodeError
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.ProtocolCodec
import io.github.calamech.rloaded.protocol.flatMap
import io.github.calamech.rloaded.protocol.mapError
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.time.Duration

/**
 * The link between the launcher and the game it started: a loopback TCP connection that the game
 * opens to its launcher, authenticated by a token the launcher gives it at start. Messages are the
 * protocol's CBOR, each preceded by its length.
 */
public object Link {
    /** The environment variable that gives the game the launcher's port. */
    public const val PORT_VARIABLE: String = "RLOADED_LINK_PORT"

    /** The environment variable that gives the game the launcher's token. */
    public const val TOKEN_VARIABLE: String = "RLOADED_LINK_TOKEN"

    /** The largest message either side accepts, in bytes. */
    public const val MAX_MESSAGE_BYTES: Int = 64 * 1024

    internal fun <T> write(
        output: DataOutputStream,
        serializer: SerializationStrategy<T>,
        message: T,
    ): Outcome<Unit, LinkError> =
        try {
            val bytes = ProtocolCodec.encode(serializer, message)
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
            Outcome.Ok(Unit)
        } catch (e: IOException) {
            Outcome.Err(LinkError.Closed(e.message ?: e.javaClass.simpleName))
        }

    internal fun <T> read(
        input: DataInputStream,
        serializer: DeserializationStrategy<T>,
    ): Outcome<T, LinkError> =
        frame(input).flatMap { bytes ->
            ProtocolCodec.decode(serializer, bytes).mapError { LinkError.Malformed(it) }
        }

    private fun frame(input: DataInputStream): Outcome<ByteArray, LinkError> =
        try {
            val size = input.readInt()
            if (size !in 0..MAX_MESSAGE_BYTES) {
                Outcome.Err(LinkError.TooLarge(size))
            } else {
                Outcome.Ok(ByteArray(size).also { input.readFully(it) })
            }
        } catch (_: EOFException) {
            Outcome.Err(LinkError.Closed("the other side closed the link"))
        } catch (e: IOException) {
            Outcome.Err(LinkError.Closed(e.message ?: e.javaClass.simpleName))
        }
}

/** How long each side waits for the other. */
public data class LinkSettings(
    /** How long the launcher waits for the game to connect, and the game for the connection. */
    val connectTimeout: Duration,
    /** How long the launcher waits for the game's hello once it is connected. */
    val helloTimeout: Duration,
) {
    /** The settings players run with. */
    public companion object {
        /** A game takes up to a few minutes to start on a slow machine. */
        public val DEFAULT: LinkSettings = LinkSettings(Duration.ofMinutes(5), Duration.ofSeconds(10))
    }
}

/** Why the link failed. */
public sealed interface LinkError {
    /** The listening socket could not be opened. */
    public data class Unavailable(
        val reason: String,
    ) : LinkError

    /** The other side did not come in time. */
    public data object TimedOut : LinkError

    /** The game gave another token than the one the launcher gave it. */
    public data object WrongToken : LinkError

    /** The game speaks another version of the link messages. */
    public data class OtherProtocol(
        val protocol: Int,
    ) : LinkError

    /** A message announced more bytes than allowed. */
    public data class TooLarge(
        val size: Int,
    ) : LinkError

    /** A message could not be read. */
    public data class Malformed(
        val error: DecodeError,
    ) : LinkError

    /** The connection closed or broke. */
    public data class Closed(
        val reason: String,
    ) : LinkError
}
