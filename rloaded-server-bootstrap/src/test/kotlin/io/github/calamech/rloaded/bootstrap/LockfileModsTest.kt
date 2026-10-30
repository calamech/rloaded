package io.github.calamech.rloaded.bootstrap

import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.TestServer
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LockfileModsTest {
    @TempDir
    lateinit var directory: Path

    private val server = TestServer()
    private val http = KtorHttp(server.policy, HttpSettings())
    private val modrinth = FakeModrinthMods(server)
    private val both = LockedJar("both", "BOTH")
    private val serverOnly = LockedJar("serverOnly", "SERVER")
    private val clientOnly = LockedJar("clientOnly", "CLIENT")

    @AfterEach
    fun close() {
        http.close()
        server.close()
    }

    @Test
    fun `obtains the mods that run on the server, in lockfile order`() {
        modrinth.publish(both, serverOnly, clientOnly)
        val lockfile = directory.resolve("lockfile.json").also { it.writeText(modrinth.lockfile(serverOnly, clientOnly, both)) }

        val jars = ok(obtain(lockfile))

        assertEquals(listOf(serverOnly.bytes.toList(), both.bytes.toList()), jars.map { it.readBytes().toList() })
        assertEquals(0, server.hits("/cdn/${clientOnly.fileName}"))
    }

    @Test
    fun `means no mods without a lockfile, without asking Modrinth`() {
        assertEquals(Outcome.Ok(emptyList()), obtain(directory.resolve("lockfile.json")))
        assertEquals(0, server.hits("/v2/versions"))
    }

    @Test
    fun `refuses a lockfile it cannot read`() {
        val lockfile =
            directory
                .resolve(
                    "lockfile.json",
                ).also { it.writeText(modrinth.lockfile(both).replace("\"schema\": 1", "\"schema\": 2")) }

        val error = assertIs<Outcome.Err<String>>(obtain(lockfile)).error

        assertEquals("rloaded/lockfile.json cannot be used: UnsupportedSchema(schema=2)", error)
    }

    @Test
    fun `refuses a mod whose file cannot be downloaded`() {
        modrinth.publish(both)
        server.serve("/cdn/${both.fileName}", ByteArray(0), status = 404)
        val lockfile = directory.resolve("lockfile.json").also { it.writeText(modrinth.lockfile(both)) }

        val error = assertIs<Outcome.Err<String>>(obtain(lockfile)).error

        assertTrue(error.startsWith("the mods of rloaded/lockfile.json cannot be obtained: Downloads("), error)
        assertTrue("HttpStatus(url=${server.url("/cdn/${both.fileName}")}, status=404)" in error, error)
    }

    @Test
    fun `refuses a mod that disagrees with Modrinth's record`() {
        modrinth.publish(both)
        val forged = both.copy(bytes = "another jar".toByteArray())
        val lockfile = directory.resolve("lockfile.json").also { it.writeText(modrinth.lockfile(forged)) }

        val error = assertIs<Outcome.Err<String>>(obtain(lockfile)).error

        assertTrue(error.startsWith("the mods of rloaded/lockfile.json cannot be obtained: Mismatches("), error)
        assertEquals(0, server.hits("/cdn/${both.fileName}"))
    }

    private fun <T> ok(outcome: Outcome<T, String>): T =
        when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw AssertionError("expected mods, got ${outcome.error}")
        }

    private fun obtain(lockfile: Path): Outcome<List<Path>, String> =
        runBlocking { modrinth.lockfileMods(http, directory.resolve("store")).obtain(lockfile) }
}
