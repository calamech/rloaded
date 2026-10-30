package io.github.calamech.rloaded.protocol

import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.inputStream

/** Hash algorithms used by the sources Rloaded downloads from. */
public enum class HashAlgorithm(
    internal val jcaName: String,
    internal val hexLength: Int,
) {
    /** Used by Mojang for game files, libraries and assets. */
    SHA1("SHA-1", 40),

    /** Used for Rloaded's own release files. */
    SHA256("SHA-256", 64),

    /** Used by Modrinth for mod files and by JetBrains for runtimes. */
    SHA512("SHA-512", 128),
}

/** An expected file hash, as lowercase hexadecimal. Create one with [Checksum.parse]. */
public class Checksum private constructor(
    /** The algorithm that produced [hex]. */
    public val algorithm: HashAlgorithm,
    /** The hash in lowercase hexadecimal. */
    public val hex: String,
) {
    override fun equals(other: Any?): Boolean = other is Checksum && other.algorithm == algorithm && other.hex == hex

    override fun hashCode(): Int = 31 * algorithm.hashCode() + hex.hashCode()

    override fun toString(): String = "${algorithm.name.lowercase()}:$hex"

    /** Constructors and hashing. */
    public companion object {
        private val HEX = Regex("[0-9a-fA-F]+")

        /** Parses [hex] as a hash of [algorithm], refusing anything with the wrong length or characters. */
        public fun parse(
            algorithm: HashAlgorithm,
            hex: String,
        ): Outcome<Checksum, InvalidChecksum> =
            if (hex.length == algorithm.hexLength && HEX.matches(hex)) {
                Outcome.Ok(Checksum(algorithm, hex.lowercase()))
            } else {
                Outcome.Err(InvalidChecksum(algorithm, hex))
            }

        /** Hashes the content of [file] with [algorithm]. */
        public fun of(
            algorithm: HashAlgorithm,
            file: Path,
        ): Checksum {
            val digest = MessageDigest.getInstance(algorithm.jcaName)
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            return Checksum(algorithm, digest.digest().toHexString())
        }

        private const val BUFFER_SIZE = 64 * 1024
    }
}

/** A hash string that is not a valid [algorithm] hash. */
public data class InvalidChecksum(
    val algorithm: HashAlgorithm,
    val value: String,
)
