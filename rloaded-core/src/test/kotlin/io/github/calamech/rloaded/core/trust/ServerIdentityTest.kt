package io.github.calamech.rloaded.core.trust

import io.github.calamech.rloaded.protocol.JoinChallenge
import io.github.calamech.rloaded.protocol.ManifestKind
import io.github.calamech.rloaded.protocol.ManifestSigning
import io.github.calamech.rloaded.protocol.Outcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerIdentityTest {
    @TempDir
    lateinit var directory: Path

    private val file get() = directory.resolve("rloaded").resolve("identity.key")

    @Test
    fun `creates a key at the first start and reads the same key afterwards`() {
        val created = ok(ServerIdentity.loadOrCreate(file))
        val reloaded = ok(ServerIdentity.loadOrCreate(file))

        assertEquals(created.publicKey, reloaded.publicKey)
        val challenge = JoinChallenge.random()
        val manifest = reloaded.sign(ManifestKind.FULL, emptyList(), challenge)
        assertIs<Outcome.Ok<*>>(ManifestSigning.verify(manifest, challenge, created.publicKey))
    }

    @Test
    fun `the key file is readable by its owner only`() {
        assumeTrue("posix" in directory.fileSystem.supportedFileAttributeViews())

        ok(ServerIdentity.loadOrCreate(file))

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }

    @Test
    fun `never prints the private key`() {
        val identity = ok(ServerIdentity.loadOrCreate(file))
        val privateLine =
            file
                .readText()
                .lines()
                .first()
                .removePrefix("private ")

        assertTrue(!identity.toString().contains(privateLine), identity.toString())
    }

    @Test
    fun `refuses a file that is not a key pair`() {
        Files.createDirectories(file.parent)
        file.writeText("private abc\n")
        val truncated = ServerIdentity.loadOrCreate(file)
        val other = directory.resolve("other.key")
        ok(ServerIdentity.loadOrCreate(other))
        val mixed = directory.resolve("mixed.key")
        val third = directory.resolve("third.key")
        ok(ServerIdentity.loadOrCreate(third))
        mixed.writeText(other.readText().lines()[0] + "\n" + third.readText().lines()[1] + "\n")

        assertEquals(Outcome.Err(IdentityError.Malformed(file, "expected a private and a public line")), truncated)
        assertEquals(Outcome.Err(IdentityError.Malformed(mixed, "the keys do not form a pair")), ServerIdentity.loadOrCreate(mixed))
    }

    private fun <T> ok(outcome: Outcome<T, IdentityError>): T =
        when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw AssertionError("expected a value, got ${outcome.error}")
        }
}
