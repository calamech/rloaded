package io.github.calamech.rloaded.protocol

import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProtocolCodecTest {
    private val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val serverKey = ServerKey.of(key.public)

    @Test
    fun `round-trips every message`() {
        val messages =
            listOf(
                ServerHello.serializer() to ServerHello(PROTOCOL_VERSION, serverKey, "Friends", sha256('a')),
                ClientHello.serializer() to ClientHello(PROTOCOL_VERSION, "0.1.0", sha256('b'), JoinChallenge.random()),
                Manifest.serializer() to
                    ManifestSigning.sign(ManifestKind.FULL, listOf(entry("P7dR8mSH")), JoinChallenge.random(), key.private),
                Progress.serializer() to Progress(InstallStage.DOWNLOAD, 40),
                InstallResult.serializer() to InstallResult.Loaded,
                InstallResult.serializer() to InstallResult.RefusedByUser,
                InstallResult.serializer() to InstallResult.Failed("P7dR8mSH", "crashed in onInitialize"),
                InstallResult.serializer() to InstallResult.Failed(null, "out of disk space"),
            )

        for ((serializer, message) in messages) {
            @Suppress("UNCHECKED_CAST")
            val typed = serializer as kotlinx.serialization.KSerializer<Any>
            assertEquals(Outcome.Ok(message), ProtocolCodec.decode(typed, ProtocolCodec.encode(typed, message)))
        }
    }

    @Test
    fun `refuses a field the message does not know`() {
        val bytes = ProtocolCodec.encode(ProgressWithExtra.serializer(), ProgressWithExtra(InstallStage.LOAD, 10, "x"))

        val decoded = ProtocolCodec.decode(Progress.serializer(), bytes)

        assertIs<DecodeError.Malformed>((decoded as Outcome.Err).error)
    }

    @Test
    fun `refuses a field out of its bounds`() {
        val bytes = ProtocolCodec.encode(RawProgress.serializer(), RawProgress(InstallStage.LOAD, 101))

        assertEquals(
            Outcome.Err(DecodeError.Malformed("percent must be between 0 and 100")),
            ProtocolCodec.decode(Progress.serializer(), bytes),
        )
    }

    @Test
    fun `refuses a message over the size limit`() {
        val bytes = ByteArray(ProtocolCodec.MAX_MESSAGE_BYTES + 1)

        assertEquals(
            Outcome.Err(DecodeError.TooLarge(ProtocolCodec.MAX_MESSAGE_BYTES + 1, ProtocolCodec.MAX_MESSAGE_BYTES)),
            ProtocolCodec.decode(Progress.serializer(), bytes),
        )
    }

    @Test
    fun `turns any garbage into an error, never an exception`() {
        val random = Random(42)
        val valid =
            ProtocolCodec.encode(
                Manifest.serializer(),
                ManifestSigning.sign(ManifestKind.DELTA, listOf(entry("AANobbMI")), JoinChallenge.random(), key.private),
            )

        repeat(GARBAGE_RUNS) {
            val flipped = valid.copyOf().also { it[random.nextInt(it.size)] = random.nextInt().toByte() }
            val garbage = flipped.copyOf(random.nextInt(valid.size))
            ProtocolCodec.decode(Manifest.serializer(), garbage)
            ProtocolCodec.decode(Manifest.serializer(), random.nextBytes(random.nextInt(GARBAGE_SIZE)))
        }
    }

    @Test
    fun `refuses mod entries that break the bounds`() {
        assertRefused("projectId must be 1 to 16 letters or digits") { entry("../x") }
        assertRefused(
            "fileName must be a .jar name of at most 255 characters, without a path",
        ) { entry("P7dR8mSH", fileName = "../evil.jar") }
        assertRefused(
            "fileName must be a .jar name of at most 255 characters, without a path",
        ) { entry("P7dR8mSH", fileName = "notes.txt") }
        assertRefused("size must be between 1 and 536870912 bytes") { entry("P7dR8mSH", size = 0) }
        assertRefused("a manifest lists each project once") {
            ManifestSigning.sign(ManifestKind.FULL, listOf(entry("P7dR8mSH"), entry("P7dR8mSH")), JoinChallenge.random(), key.private)
        }
    }

    private fun assertRefused(
        message: String,
        build: () -> Any,
    ) {
        val refused = runCatching(build).exceptionOrNull()
        assertEquals(message, (refused as IllegalArgumentException).message)
    }

    @Serializable
    private data class ProgressWithExtra(
        val stage: InstallStage,
        val percent: Int,
        val extra: String?,
    )

    @Serializable
    private data class RawProgress(
        val stage: InstallStage,
        val percent: Int,
    )

    private companion object {
        const val GARBAGE_RUNS = 2000
        const val GARBAGE_SIZE = 256
    }
}

internal fun sha256(digit: Char): Checksum = (Checksum.parse(HashAlgorithm.SHA256, digit.toString().repeat(64)) as Outcome.Ok).value

internal fun entry(
    projectId: String,
    fileName: String = "mod.jar",
    size: Long = 1024,
): ModEntry =
    ModEntry(
        projectId,
        "SKW1BTL3",
        fileName,
        (Checksum.parse(HashAlgorithm.SHA512, "c".repeat(128)) as Outcome.Ok).value,
        size,
        Side.BOTH,
    )
