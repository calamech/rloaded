package io.github.calamech.rloaded.protocol

import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import kotlin.test.assertEquals

class ManifestSigningTest {
    private val server = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val entries = listOf(entry("P7dR8mSH"), entry("AANobbMI", fileName = "other.jar"))

    @Test
    fun `accepts a manifest signed by the trusted key`() {
        val manifest = ManifestSigning.sign(ManifestKind.FULL, entries, server.private)

        assertEquals(Outcome.Ok(manifest), ManifestSigning.verify(manifest, ServerKey.of(server.public)))
    }

    @Test
    fun `refuses a manifest signed by another key`() {
        val manifest = ManifestSigning.sign(ManifestKind.FULL, entries, other.private)

        assertEquals(Outcome.Err(InvalidSignature), ManifestSigning.verify(manifest, ServerKey.of(server.public)))
    }

    @Test
    fun `refuses a manifest whose entries or kind changed after signing`() {
        val manifest = ManifestSigning.sign(ManifestKind.DELTA, entries, server.private)
        val key = ServerKey.of(server.public)

        assertEquals(Outcome.Err(InvalidSignature), ManifestSigning.verify(manifest.copy(entries = entries.take(1)), key))
        assertEquals(Outcome.Err(InvalidSignature), ManifestSigning.verify(manifest.copy(kind = ManifestKind.FULL), key))
        assertEquals(
            Outcome.Err(InvalidSignature),
            ManifestSigning.verify(manifest.copy(entries = listOf(entries[0].copy(size = 2048), entries[1])), key),
        )
    }

    @Test
    fun `reads keys and signatures of the right size only`() {
        assertEquals(Outcome.Err(InvalidLength(32, 31)), ServerKey.of(ByteArray(31)))
        assertEquals(Outcome.Err(InvalidLength(64, 65)), ManifestSignature.of(ByteArray(65)))
        assertEquals(Outcome.Ok(ServerKey.of(server.public)), ServerKey.of(ServerKey.of(server.public).bytes()))
    }
}
