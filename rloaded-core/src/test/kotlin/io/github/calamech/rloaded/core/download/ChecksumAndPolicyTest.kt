package io.github.calamech.rloaded.core.download

import io.github.calamech.rloaded.core.Outcome
import io.github.calamech.rloaded.core.flatMap
import io.github.calamech.rloaded.core.map
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ChecksumAndPolicyTest {
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

    @Test
    fun `chains outcomes`() {
        val ok: Outcome<Int, String> = Outcome.Ok(2)
        val err: Outcome<Int, String> = Outcome.Err("no")

        assertEquals(Outcome.Ok(4), ok.flatMap { Outcome.Ok(it * 2) })
        assertEquals(Outcome.Err("no"), err.flatMap { Outcome.Ok(it * 2) })
        assertEquals(Outcome.Err("no"), err.map { it * 2 })
    }

    @Test
    fun `allows production origins over HTTPS only`() {
        assertNull(UrlPolicy.PRODUCTION.refusalReason("https://piston-data.mojang.com/v1/objects/abc/client.jar"))
        assertNull(UrlPolicy.PRODUCTION.refusalReason("https://CDN.modrinth.com:443/data/abc/file.jar"))
        assertEquals(
            "http://cdn.modrinth.com:80 is not an allowed origin",
            UrlPolicy.PRODUCTION.refusalReason("http://cdn.modrinth.com/data/abc/file.jar"),
        )
        assertEquals(
            "https://evil.example:443 is not an allowed origin",
            UrlPolicy.PRODUCTION.refusalReason("https://evil.example/file.jar"),
        )
    }

    @Test
    fun `refuses URLs it cannot read`() {
        assertEquals("not a valid URL", UrlPolicy.PRODUCTION.refusalReason("https://bad host/"))
        assertEquals("no scheme", UrlPolicy.PRODUCTION.refusalReason("/relative"))
        assertEquals("no host", UrlPolicy.PRODUCTION.refusalReason("file:///etc/passwd"))
    }
}
