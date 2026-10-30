package io.github.calamech.rloaded.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec

/** A server's Ed25519 public key: the identity a player trusts. Create one with [ServerKey.of]. */
@Serializable(with = ServerKeySerializer::class)
public class ServerKey private constructor(
    private val raw: ByteArray,
) {
    /** The 32 bytes of the key. */
    public fun bytes(): ByteArray = raw.copyOf()

    internal fun toPublicKey(): PublicKey = KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(X509_PREFIX + raw))

    override fun equals(other: Any?): Boolean = other is ServerKey && other.raw.contentEquals(raw)

    override fun hashCode(): Int = raw.contentHashCode()

    override fun toString(): String = "ServerKey(${raw.toHexString()})"

    /** Constructors. */
    public companion object {
        /** The size of a raw Ed25519 public key. */
        public const val SIZE: Int = 32

        internal const val ALGORITHM = "Ed25519"

        // The X.509 header the JDK puts before the 32 raw bytes of an Ed25519 public key.
        private val X509_PREFIX = "302a300506032b6570032100".hexToByteArray()

        /** Reads a key from its raw bytes, refusing any other size. */
        public fun of(bytes: ByteArray): Outcome<ServerKey, InvalidLength> =
            if (bytes.size == SIZE) Outcome.Ok(ServerKey(bytes.copyOf())) else Outcome.Err(InvalidLength(SIZE, bytes.size))

        /** Takes the raw bytes of a JDK Ed25519 public key. */
        public fun of(key: PublicKey): ServerKey {
            val encoded = key.encoded
            require(key.algorithm == ALGORITHM || key.algorithm == "EdDSA") { "not an Ed25519 key: ${key.algorithm}" }
            require(encoded.size == X509_PREFIX.size + SIZE && encoded.copyOf(X509_PREFIX.size).contentEquals(X509_PREFIX)) {
                "unexpected Ed25519 key encoding"
            }
            return ServerKey(encoded.copyOfRange(X509_PREFIX.size, encoded.size))
        }
    }
}

/** An Ed25519 signature of a manifest. Create one with [ManifestSignature.of]. */
@Serializable(with = ManifestSignatureSerializer::class)
public class ManifestSignature private constructor(
    private val raw: ByteArray,
) {
    /** The 64 bytes of the signature. */
    public fun bytes(): ByteArray = raw.copyOf()

    override fun equals(other: Any?): Boolean = other is ManifestSignature && other.raw.contentEquals(raw)

    override fun hashCode(): Int = raw.contentHashCode()

    override fun toString(): String = "ManifestSignature(${raw.toHexString()})"

    /** Constructors. */
    public companion object {
        /** The size of an Ed25519 signature. */
        public const val SIZE: Int = 64

        /** Reads a signature from its bytes, refusing any other size. */
        public fun of(bytes: ByteArray): Outcome<ManifestSignature, InvalidLength> =
            if (bytes.size == SIZE) Outcome.Ok(ManifestSignature(bytes.copyOf())) else Outcome.Err(InvalidLength(SIZE, bytes.size))
    }
}

/** A key or signature with [actual] bytes where [expected] are needed. */
public data class InvalidLength(
    val expected: Int,
    val actual: Int,
)

internal object ServerKeySerializer : KSerializer<ServerKey> {
    override val descriptor: SerialDescriptor = SerialDescriptor("io.github.calamech.rloaded.ServerKey", ByteArraySerializer().descriptor)

    override fun serialize(
        encoder: Encoder,
        value: ServerKey,
    ): Unit = encoder.encodeSerializableValue(ByteArraySerializer(), value.bytes())

    override fun deserialize(decoder: Decoder): ServerKey =
        when (val key = ServerKey.of(decoder.decodeSerializableValue(ByteArraySerializer()))) {
            is Outcome.Ok -> key.value
            is Outcome.Err -> throw SerializationException("a server key has ${key.error.expected} bytes, not ${key.error.actual}")
        }
}

internal object ManifestSignatureSerializer : KSerializer<ManifestSignature> {
    override val descriptor: SerialDescriptor =
        SerialDescriptor("io.github.calamech.rloaded.ManifestSignature", ByteArraySerializer().descriptor)

    override fun serialize(
        encoder: Encoder,
        value: ManifestSignature,
    ): Unit = encoder.encodeSerializableValue(ByteArraySerializer(), value.bytes())

    override fun deserialize(decoder: Decoder): ManifestSignature =
        when (val signature = ManifestSignature.of(decoder.decodeSerializableValue(ByteArraySerializer()))) {
            is Outcome.Ok -> {
                signature.value
            }

            is Outcome.Err -> {
                throw SerializationException("a manifest signature has ${signature.error.expected} bytes, not ${signature.error.actual}")
            }
        }
}
