package io.github.calamech.rloaded.core.trust

import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.ServerKey
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TrustStoreTest {
    @TempDir
    lateinit var directory: Path

    private val file get() = directory.resolve("trust.json")
    private val first = key(1)
    private val second = key(2)

    @Test
    fun `an absent file is an empty store`() {
        assertNull(ok(TrustStore.open(file)).find(first))
    }

    @Test
    fun `remembers a trusted server and its opted out mods across openings`() {
        ok(ok(TrustStore.open(file)).trust(TrustedServer(first, "Friends", setOf("mOgUt4GM"))))

        val reopened = ok(TrustStore.open(file))

        assertEquals(TrustedServer(first, "Friends", setOf("mOgUt4GM")), reopened.find(first))
        assertNull(reopened.find(second))
    }

    @Test
    fun `trusting a server again replaces its record`() {
        val store = ok(ok(TrustStore.open(file)).trust(TrustedServer(first, "Friends", setOf("mOgUt4GM"))))

        ok(store.trust(TrustedServer(first, "Friends", emptySet())))

        assertEquals(TrustedServer(first, "Friends", emptySet()), ok(TrustStore.open(file)).find(first))
    }

    @Test
    fun `refuses invalid names and mod ids`() {
        val longName = runCatching { TrustedServer(first, "x".repeat(65), emptySet()) }.exceptionOrNull()
        val badMod = runCatching { TrustedServer(first, "Friends", setOf("../x")) }.exceptionOrNull()

        assertEquals("name must have 1 to 64 characters", longName?.message)
        assertEquals("optedOutMods must be Modrinth project ids", badMod?.message)
    }

    @Test
    fun `refuses a file it did not write`() {
        file.writeText("""{"schema": 2, "servers": []}""")
        val otherSchema = TrustStore.open(file)
        file.writeText("""{"schema": 1, "servers": [{"key": "abcd", "name": "Friends", "optedOutMods": []}]}""")
        val shortKey = TrustStore.open(file)
        file.writeText("""{"schema": 1, "servers": [], "extra": true}""")
        val unknownField = TrustStore.open(file)

        assertEquals(Outcome.Err(TrustStoreError.Malformed(file, "schema 2 is not supported")), otherSchema)
        assertEquals(Outcome.Err(TrustStoreError.Malformed(file, "a server key has 2 bytes")), shortKey)
        assertIs<TrustStoreError.Malformed>((unknownField as Outcome.Err).error)
    }

    private fun key(seed: Int): ServerKey =
        when (val parsed = ServerKey.of(ByteArray(ServerKey.SIZE) { seed.toByte() })) {
            is Outcome.Ok -> parsed.value
            is Outcome.Err -> throw AssertionError("invalid test key")
        }

    private fun <T> ok(outcome: Outcome<T, TrustStoreError>): T =
        when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw AssertionError("expected a value, got ${outcome.error}")
        }
}
