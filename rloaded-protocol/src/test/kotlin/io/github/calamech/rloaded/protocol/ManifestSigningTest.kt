package io.github.calamech.rloaded.protocol

import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import kotlin.test.assertEquals

class ManifestSigningTest {
    private val server = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val entries = listOf(entry("P7dR8mSH"), entry("AANobbMI", fileName = "other.jar"))
    private val challenge = JoinChallenge.random()

    @Test
    fun `accepts a manifest signed by the trusted key`() {
        val manifest = ManifestSigning.sign(ManifestKind.FULL, entries, challenge, server.private)

        assertEquals(Outcome.Ok(manifest), ManifestSigning.verify(manifest, challenge, ServerKey.of(server.public)))
    }

    @Test
    fun `refuses a manifest signed by another key`() {
        val manifest = ManifestSigning.sign(ManifestKind.FULL, entries, challenge, other.private)

        assertEquals(Outcome.Err(InvalidSignature), ManifestSigning.verify(manifest, challenge, ServerKey.of(server.public)))
    }

    @Test
    fun `refuses a manifest whose entries or kind changed after signing`() {
        val manifest = ManifestSigning.sign(ManifestKind.DELTA, entries, challenge, server.private)
        val key = ServerKey.of(server.public)

        assertEquals(Outcome.Err(InvalidSignature), ManifestSigning.verify(manifest.copy(entries = entries.take(1)), challenge, key))
        assertEquals(Outcome.Err(InvalidSignature), ManifestSigning.verify(manifest.copy(kind = ManifestKind.FULL), challenge, key))
        assertEquals(
            Outcome.Err(InvalidSignature),
            ManifestSigning.verify(manifest.copy(entries = listOf(entries[0].copy(size = 2048), entries[1])), challenge, key),
        )
    }

    @Test
    fun `refuses a manifest the same key signed for another join`() {
        val replayed = ManifestSigning.sign(ManifestKind.FULL, entries, JoinChallenge.random(), server.private)

        assertEquals(Outcome.Err(InvalidSignature), ManifestSigning.verify(replayed, challenge, ServerKey.of(server.public)))
    }

    @Test
    fun `reads keys and signatures of the right size only`() {
        assertEquals(Outcome.Err(InvalidLength(32, 31)), ServerKey.of(ByteArray(31)))
        assertEquals(Outcome.Err(InvalidLength(64, 65)), ManifestSignature.of(ByteArray(65)))
        assertEquals(Outcome.Err(InvalidLength(32, 16)), JoinChallenge.of(ByteArray(16)))
        assertEquals(Outcome.Ok(challenge), JoinChallenge.of(challenge.bytes()))
        assertEquals(Outcome.Ok(ServerKey.of(server.public)), ServerKey.of(ServerKey.of(server.public).bytes()))
    }
}
