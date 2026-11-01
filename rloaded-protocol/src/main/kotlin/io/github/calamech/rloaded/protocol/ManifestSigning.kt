package io.github.calamech.rloaded.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.PrivateKey
import java.security.Signature

/** A manifest whose signature does not match the server key the client trusts. */
public data object InvalidSignature

/**
 * Signs and checks manifests with the server's Ed25519 key.
 *
 * The signature covers the client's challenge and the manifest's kind and entries, written as
 * compact JSON with every field in declaration order, so the server and its clients sign and check
 * the same bytes, and a manifest only verifies for the join it was sent to.
 */
public object ManifestSigning {
    private val canonical =
        Json {
            encodeDefaults = true
            explicitNulls = true
            prettyPrint = false
        }

    /** Builds the manifest of [entries] for the join that sent [challenge], signed with the server's [key]. */
    public fun sign(
        kind: ManifestKind,
        entries: List<ModEntry>,
        challenge: JoinChallenge,
        key: PrivateKey,
    ): Manifest {
        val signer = Signature.getInstance(ServerKey.ALGORITHM)
        signer.initSign(key)
        signer.update(signedBytes(challenge, kind, entries))
        return when (val signature = ManifestSignature.of(signer.sign())) {
            is Outcome.Ok -> Manifest(kind, entries, signature.value)
            is Outcome.Err -> error("Ed25519 produced a ${signature.error.actual}-byte signature")
        }
    }

    /** Returns [manifest] if [key] signed it for the join that sent [challenge]. */
    public fun verify(
        manifest: Manifest,
        challenge: JoinChallenge,
        key: ServerKey,
    ): Outcome<Manifest, InvalidSignature> {
        val verifier = Signature.getInstance(ServerKey.ALGORITHM)
        verifier.initVerify(key.toPublicKey())
        verifier.update(signedBytes(challenge, manifest.kind, manifest.entries))
        return if (verifier.verify(manifest.signature.bytes())) Outcome.Ok(manifest) else Outcome.Err(InvalidSignature)
    }

    private fun signedBytes(
        challenge: JoinChallenge,
        kind: ManifestKind,
        entries: List<ModEntry>,
    ): ByteArray =
        canonical
            .encodeToString(SignedContent.serializer(), SignedContent(challenge.bytes().toHexString(), kind, entries))
            .encodeToByteArray()

    @Serializable
    private data class SignedContent(
        val challenge: String,
        val kind: ManifestKind,
        val entries: List<ModEntry>,
    )
}
