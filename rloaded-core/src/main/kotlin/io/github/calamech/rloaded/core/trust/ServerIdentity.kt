package io.github.calamech.rloaded.core.trust

import io.github.calamech.rloaded.protocol.Manifest
import io.github.calamech.rloaded.protocol.ManifestKind
import io.github.calamech.rloaded.protocol.ManifestSigning
import io.github.calamech.rloaded.protocol.ModEntry
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.ServerKey
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The server's Ed25519 key pair, the identity players trust. It is kept in `rloaded/identity.key`,
 * created at the first start and readable by its owner only. The private key never leaves this class.
 */
public class ServerIdentity private constructor(
    private val keys: KeyPair,
) {
    /** The public key sent to players. */
    public val publicKey: ServerKey get() = ServerKey.of(keys.public)

    /** Builds the manifest of [entries], signed with this identity. */
    public fun sign(
        kind: ManifestKind,
        entries: List<ModEntry>,
    ): Manifest = ManifestSigning.sign(kind, entries, keys.private)

    override fun toString(): String = "ServerIdentity(${publicKey.bytes().toHexString()})"

    /** Loading and creating identities. */
    public companion object {
        private const val ALGORITHM = "Ed25519"
        private const val PRIVATE = "private "
        private const val PUBLIC = "public "

        /** Reads the identity in [file], or creates one there if the file does not exist. */
        public fun loadOrCreate(file: Path): Outcome<ServerIdentity, IdentityError> =
            try {
                read(file, Files.readAllLines(file))
            } catch (_: NoSuchFileException) {
                create(file)
            } catch (e: IOException) {
                Outcome.Err(IdentityError.Unreadable(file, e.message ?: e.javaClass.simpleName))
            }

        private fun read(
            file: Path,
            lines: List<String>,
        ): Outcome<ServerIdentity, IdentityError> {
            val private = lines.singleOrNull { it.startsWith(PRIVATE) }?.removePrefix(PRIVATE)
            val public = lines.singleOrNull { it.startsWith(PUBLIC) }?.removePrefix(PUBLIC)
            if (lines.size != 2 || private == null || public == null) {
                return Outcome.Err(IdentityError.Malformed(file, "expected a private and a public line"))
            }
            return try {
                val factory = KeyFactory.getInstance(ALGORITHM)
                val keys =
                    KeyPair(
                        factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(public))),
                        factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(private))),
                    )
                val paired = matches(keys)
                if (paired) Outcome.Ok(ServerIdentity(keys)) else Outcome.Err(IdentityError.Malformed(file, "the keys do not form a pair"))
            } catch (e: IllegalArgumentException) {
                Outcome.Err(IdentityError.Malformed(file, e.message ?: "invalid Base64"))
            } catch (e: GeneralSecurityException) {
                Outcome.Err(IdentityError.Malformed(file, e.message ?: "invalid key"))
            }
        }

        private fun create(file: Path): Outcome<ServerIdentity, IdentityError> {
            val keys = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()
            val encoder = Base64.getEncoder()
            val text = "$PRIVATE${encoder.encodeToString(keys.private.encoded)}\n$PUBLIC${encoder.encodeToString(keys.public.encoded)}\n"
            return try {
                AtomicFiles.write(file, text.encodeToByteArray())
                Outcome.Ok(ServerIdentity(keys))
            } catch (e: IOException) {
                Outcome.Err(IdentityError.Unwritable(file, e.message ?: e.javaClass.simpleName))
            }
        }

        private fun matches(keys: KeyPair): Boolean {
            val probe = ManifestSigning.sign(ManifestKind.FULL, emptyList(), keys.private)
            return ManifestSigning.verify(probe, ServerKey.of(keys.public)) is Outcome.Ok
        }
    }
}

/** Why the server identity cannot be used. Each one stops the server before players join. */
public sealed interface IdentityError {
    /** The key file exists but cannot be read. */
    public data class Unreadable(
        val path: Path,
        val reason: String,
    ) : IdentityError

    /** The key file is not a key pair written by Rloaded. */
    public data class Malformed(
        val path: Path,
        val reason: String,
    ) : IdentityError

    /** A new key file cannot be written. */
    public data class Unwritable(
        val path: Path,
        val reason: String,
    ) : IdentityError
}
