package io.github.calamech.rloaded.protocol

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ChecksumTest {
    @Test
    fun `parses a hash and normalizes it to lowercase`() {
        val parsed = Checksum.parse(HashAlgorithm.SHA1, "A".repeat(40))

        assertEquals("sha1:${"a".repeat(40)}", parsed.map { it.toString() }.let { (it as Outcome.Ok).value })
        assertEquals(parsed, Checksum.parse(HashAlgorithm.SHA1, "a".repeat(40)))
        assertNotEquals(parsed, Checksum.parse(HashAlgorithm.SHA1, "b".repeat(40)))
    }

    @Test
    fun `refuses a hash with the wrong length or characters`() {
        assertEquals(Outcome.Err(InvalidChecksum(HashAlgorithm.SHA512, "abc")), Checksum.parse(HashAlgorithm.SHA512, "abc"))
        assertEquals(
            Outcome.Err(InvalidChecksum(HashAlgorithm.SHA256, "z".repeat(64))),
            Checksum.parse(HashAlgorithm.SHA256, "z".repeat(64)),
        )
    }

    @Test
    fun `hashes a file`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("file").also { it.writeText("abc") }

        val expected = Checksum.parse(HashAlgorithm.SHA256, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

        assertEquals(expected, Outcome.Ok(Checksum.of(HashAlgorithm.SHA256, file)))
    }
}
