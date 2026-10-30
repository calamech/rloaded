package io.github.calamech.rloaded.modrinth

import io.github.calamech.rloaded.protocol.Checksum
import io.github.calamech.rloaded.protocol.HashAlgorithm
import io.github.calamech.rloaded.protocol.ModEntry
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.Side
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FileCheckTest {
    private val sha = checksum('a')
    private val file = ModrinthFile("https://cdn.modrinth.com/data/P7dR8mSH/versions/EQKgpeKu/mod.jar", "mod.jar", 100, sha)
    private val other =
        ModrinthFile("https://cdn.modrinth.com/data/P7dR8mSH/versions/EQKgpeKu/sources.jar", "sources.jar", 50, checksum('c'))
    private val version = ModrinthVersion("EQKgpeKu", "P7dR8mSH", listOf(other, file))

    @Test
    fun `returns the file when the record agrees with the entry`() {
        assertEquals(Outcome.Ok(file), version.fileFor(entry()))
    }

    @Test
    fun `refuses a record of another version`() {
        assertEquals(Outcome.Err(FileMismatch.OtherVersion("LEklt8GT", "EQKgpeKu")), version.fileFor(entry(versionId = "LEklt8GT")))
    }

    @Test
    fun `refuses a version of another project`() {
        assertEquals(Outcome.Err(FileMismatch.OtherProject("mOgUt4GM", "P7dR8mSH")), version.fileFor(entry(projectId = "mOgUt4GM")))
    }

    @Test
    fun `refuses a file the version does not have`() {
        assertEquals(Outcome.Err(FileMismatch.NoSuchFile("EQKgpeKu", "evil.jar")), version.fileFor(entry(fileName = "evil.jar")))
    }

    @Test
    fun `refuses another size`() {
        assertEquals(Outcome.Err(FileMismatch.OtherSize(101, 100)), version.fileFor(entry(size = 101)))
    }

    @Test
    fun `refuses another hash`() {
        val forged = checksum('b')

        assertEquals(Outcome.Err(FileMismatch.OtherHash(forged, sha)), version.fileFor(entry(sha512 = forged)))
    }

    private fun entry(
        projectId: String = "P7dR8mSH",
        versionId: String = "EQKgpeKu",
        fileName: String = "mod.jar",
        size: Long = 100,
        sha512: Checksum = sha,
    ) = ModEntry(projectId, versionId, fileName, sha512, size, Side.BOTH)

    private fun checksum(digit: Char): Checksum =
        when (val parsed = Checksum.parse(HashAlgorithm.SHA512, digit.toString().repeat(128))) {
            is Outcome.Ok -> parsed.value
            is Outcome.Err -> throw AssertionError("invalid test checksum")
        }
}
