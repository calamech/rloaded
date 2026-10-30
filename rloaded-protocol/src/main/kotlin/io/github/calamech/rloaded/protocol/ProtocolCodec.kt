package io.github.calamech.rloaded.protocol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.cbor.Cbor

/** Why bytes received from a peer are not a valid message. */
public sealed interface DecodeError {
    /** The message is larger than [limit] bytes. */
    public data class TooLarge(
        val size: Int,
        val limit: Int,
    ) : DecodeError

    /** The bytes are not the expected message, or a field is out of its bounds; [reason] says which. */
    public data class Malformed(
        val reason: String,
    ) : DecodeError
}

// kotlinx-serialization marks its CBOR format experimental; the version is pinned to the game's (ADR 0019).

/** Turns messages into CBOR bytes and back. Fields a message does not know are refused. */
@OptIn(ExperimentalSerializationApi::class)
public object ProtocolCodec {
    /** The largest message accepted, in bytes. */
    public const val MAX_MESSAGE_BYTES: Int = 1024 * 1024

    private val cbor =
        Cbor {
            ignoreUnknownKeys = false
            encodeDefaults = true
        }

    /** Encodes [message]. */
    public fun <T> encode(
        serializer: SerializationStrategy<T>,
        message: T,
    ): ByteArray = cbor.encodeToByteArray(serializer, message)

    /** Decodes [bytes] received from a peer as a message of [serializer]'s type. */
    public fun <T> decode(
        serializer: DeserializationStrategy<T>,
        bytes: ByteArray,
    ): Outcome<T, DecodeError> =
        if (bytes.size > MAX_MESSAGE_BYTES) {
            Outcome.Err(DecodeError.TooLarge(bytes.size, MAX_MESSAGE_BYTES))
        } else {
            try {
                Outcome.Ok(cbor.decodeFromByteArray(serializer, bytes))
            } catch (e: SerializationException) {
                Outcome.Err(DecodeError.Malformed(e.message ?: e.javaClass.simpleName))
            } catch (e: IllegalArgumentException) {
                Outcome.Err(DecodeError.Malformed(e.message ?: e.javaClass.simpleName))
            }
        }
}
