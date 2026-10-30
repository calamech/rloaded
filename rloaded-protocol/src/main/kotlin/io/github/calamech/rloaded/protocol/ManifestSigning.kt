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
 * The signature covers the manifest's kind and entries, written as compact JSON with every field in
 * declaration order, so the server and its clients sign and check the same bytes.
 */
public object ManifestSigning {
    private val canonical =
        Json {
            encodeDefaults = true
            explicitNulls = true
            prettyPrint = false
        }

    /** Builds the manifest of [entries], signed with the server's [key]. */
    public fun sign(
        kind: ManifestKind,
        entries: List<ModEntry>,
        key: PrivateKey,
    ): Manifest {
        val signer = Signature.getInstance(ServerKey.ALGORITHM)
        signer.initSign(key)
        signer.update(signedBytes(kind, entries))
        return when (val signature = ManifestSignature.of(signer.sign())) {
            is Outcome.Ok -> Manifest(kind, entries, signature.value)
            is Outcome.Err -> error("Ed25519 produced a ${signature.error.actual}-byte signature")
        }
    }

    /** Returns [manifest] if it was signed by [key]. */
    public fun verify(
        manifest: Manifest,
        key: ServerKey,
    ): Outcome<Manifest, InvalidSignature> {
        val verifier = Signature.getInstance(ServerKey.ALGORITHM)
        verifier.initVerify(key.toPublicKey())
        verifier.update(signedBytes(manifest.kind, manifest.entries))
        return if (verifier.verify(manifest.signature.bytes())) Outcome.Ok(manifest) else Outcome.Err(InvalidSignature)
    }

    private fun signedBytes(
        kind: ManifestKind,
        entries: List<ModEntry>,
    ): ByteArray = canonical.encodeToString(SignedContent.serializer(), SignedContent(kind, entries)).encodeToByteArray()

    @Serializable
    private data class SignedContent(
        val kind: ManifestKind,
        val entries: List<ModEntry>,
    )
}
