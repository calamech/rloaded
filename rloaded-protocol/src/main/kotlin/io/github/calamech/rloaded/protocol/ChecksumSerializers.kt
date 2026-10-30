package io.github.calamech.rloaded.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Writes a [Checksum] of one algorithm as its hexadecimal string, and refuses any other on read. */
internal sealed class ChecksumSerializer(
    private val algorithm: HashAlgorithm,
) : KSerializer<Checksum> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.github.calamech.rloaded.${algorithm.name}", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Checksum,
    ) {
        require(value.algorithm == algorithm) { "expected a ${algorithm.name} checksum, not ${value.algorithm.name}" }
        encoder.encodeString(value.hex)
    }

    override fun deserialize(decoder: Decoder): Checksum =
        when (val parsed = Checksum.parse(algorithm, decoder.decodeString())) {
            is Outcome.Ok -> parsed.value
            is Outcome.Err -> throw SerializationException("not a ${algorithm.name} checksum: ${parsed.error.value.take(PREVIEW)}")
        }

    private companion object {
        const val PREVIEW = 16
    }
}

internal object Sha256Serializer : ChecksumSerializer(HashAlgorithm.SHA256)

internal object Sha512Serializer : ChecksumSerializer(HashAlgorithm.SHA512)
