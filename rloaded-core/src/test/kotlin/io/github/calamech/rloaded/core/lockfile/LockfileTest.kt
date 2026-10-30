package io.github.calamech.rloaded.core.lockfile

import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.Side
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LockfileTest {
    private val fabricApiSha = "0a4b8a14e847b71567b557b43d906c2167fa263eeeef37454a821ba286996e3d" + "a".repeat(64)
    private val modMenuSha = "588c0c557963f4778572ee81bfafc190b332c26d5926c5fe7e3e1614dcf8e14f" + "b".repeat(64)

    private fun lockfile(
        mods: String = mod(),
        schema: Int = 1,
        extra: String = "",
    ) = """
        {
          "schema": $schema,
          "minecraft": "26.3",
          "rloaded": "0.1.0",$extra
          "baseSet": [
            { "projectId": "P7dR8mSH", "versionId": "EQKgpeKu", "sha512": "$fabricApiSha" }
          ],
          "mods": [$mods],
          "pendingRemovals": []
        }
        """.trimIndent()

    private fun mod(
        projectId: String = "mOgUt4GM",
        fileName: String = "modmenu-21.0.0.jar",
        sha512: String = modMenuSha,
        addedAt: String = "2026-10-29T18:00:00Z",
    ) = """
        {
          "projectId": "$projectId", "versionId": "kyy7dbrZ", "fileName": "$fileName", "sha512": "$sha512",
          "size": 300000, "side": "CLIENT", "addedAt": "$addedAt", "addedBy": "console"
        }
        """.trimIndent()

    @Test
    fun `reads a lockfile and lists its mods as manifest entries`() {
        val lockfile = ok(Lockfile.parse(lockfile()))

        assertEquals("26.3", lockfile.minecraft)
        assertEquals(listOf("P7dR8mSH"), lockfile.baseSet.map { it.projectId })
        val entry = lockfile.entries().single()
        assertEquals("mOgUt4GM", entry.projectId)
        assertEquals("kyy7dbrZ", entry.versionId)
        assertEquals(Side.CLIENT, entry.side)
        assertEquals(modMenuSha, entry.sha512.hex)
        assertEquals(Instant.parse("2026-10-29T18:00:00Z"), lockfile.mods.single().addedAt)
    }

    @Test
    fun `hashes the base set and the mods as compact JSON in declaration order`() {
        val canonical =
            """{"baseSet":[{"projectId":"P7dR8mSH","versionId":"EQKgpeKu","sha512":"$fabricApiSha"}],""" +
                """"mods":[{"projectId":"mOgUt4GM","versionId":"kyy7dbrZ","fileName":"modmenu-21.0.0.jar","sha512":"$modMenuSha",""" +
                """"size":300000,"side":"CLIENT","addedAt":"2026-10-29T18:00:00Z","addedBy":"console"}]}"""
        val expected = MessageDigest.getInstance("SHA-256").digest(canonical.encodeToByteArray()).toHexString()

        assertEquals(expected, ok(Lockfile.parse(lockfile())).hash().hex)
    }

    @Test
    fun `the hash follows the mods, not the layout or the pending removals`() {
        val base = ok(Lockfile.parse(lockfile())).hash()
        val reformatted = ok(Lockfile.parse(lockfile().replace("\n", " "))).hash()
        val withRemoval = ok(Lockfile.parse(lockfile().replace("\"pendingRemovals\": []", "\"pendingRemovals\": [\"mOgUt4GM\"]"))).hash()
        val otherMod = ok(Lockfile.parse(lockfile(mod(addedAt = "2026-10-30T09:00:00Z")))).hash()

        assertEquals(base, reformatted)
        assertEquals(base, withRemoval)
        assertNotEquals(base, otherMod)
    }

    @Test
    fun `refuses an unknown field`() {
        val error = (Lockfile.parse(lockfile(extra = "\n  \"autoUpdate\": true,")) as Outcome.Err).error

        assertIs<LockfileError.Malformed>(error)
        assertTrue(error.reason.contains("autoUpdate"), error.reason)
    }

    @Test
    fun `refuses another schema`() {
        assertEquals(Outcome.Err(LockfileError.UnsupportedSchema(2)), Lockfile.parse(lockfile(schema = 2)))
    }

    @Test
    fun `refuses an invalid hash, file name or date, and a project listed twice`() {
        assertEquals(Outcome.Err(LockfileError.Malformed("mOgUt4GM has no valid SHA-512")), Lockfile.parse(lockfile(mod(sha512 = "abc"))))
        assertEquals(
            Outcome.Err(LockfileError.Malformed("mod mOgUt4GM: fileName must be a .jar name of at most 255 characters, without a path")),
            Lockfile.parse(lockfile(mod(fileName = "../evil.jar"))),
        )
        assertEquals(
            Outcome.Err(LockfileError.Malformed("mod mOgUt4GM: addedAt is not an ISO-8601 instant")),
            Lockfile.parse(lockfile(mod(addedAt = "yesterday"))),
        )
        assertEquals(
            Outcome.Err(LockfileError.Malformed("project mOgUt4GM is listed twice")),
            Lockfile.parse(lockfile(mod() + "," + mod())),
        )
    }

    @Test
    fun `reads a file and refuses a missing or oversized one`(
        @TempDir directory: Path,
    ) {
        val file = directory.resolve("lockfile.json")
        file.writeText(lockfile())
        val missing = directory.resolve("missing.json")
        val large = directory.resolve("large.json")
        large.writeText(" ".repeat((Lockfile.MAX_BYTES + 1).toInt()))

        assertEquals(Lockfile.parse(lockfile()), Lockfile.read(file))
        assertIs<LockfileError.Unreadable>((Lockfile.read(missing) as Outcome.Err).error)
        assertEquals(Outcome.Err(LockfileError.TooLarge(large, Lockfile.MAX_BYTES)), Lockfile.read(large))
    }

    @Test
    fun `writes a lockfile that reads back the same, and reports one it cannot write`(
        @TempDir directory: Path,
    ) {
        val lockfile = ok(Lockfile.parse(lockfile()))
        val file = directory.resolve("rloaded/lockfile.json")

        assertEquals(Outcome.Ok(Unit), lockfile.write(file))
        assertEquals(Outcome.Ok(lockfile), Lockfile.read(file))
        assertTrue("\n    \"schema\": 1," in file.readText(), "one field per line")

        val blocked = directory.resolve("blocked").also { it.writeText("a file, not a folder") }
        assertIs<LockfileError.Unwritable>((lockfile.write(blocked.resolve("lockfile.json")) as Outcome.Err).error)
    }

    private fun <T> ok(outcome: Outcome<T, LockfileError>): T =
        when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw AssertionError("expected a value, got ${outcome.error}")
        }
}
