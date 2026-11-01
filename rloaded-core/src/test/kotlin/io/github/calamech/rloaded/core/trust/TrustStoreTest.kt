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
        ok(ok(TrustStore.open(file)).trust(TrustedServer(first, "Friends", setOf("mOgUt4GM"), listOf(FRIENDS))))

        val reopened = ok(TrustStore.open(file))

        assertEquals(TrustedServer(first, "Friends", setOf("mOgUt4GM"), listOf(FRIENDS)), reopened.find(first))
        assertNull(reopened.find(second))
    }

    @Test
    fun `trusting a server again replaces its record`() {
        val store = ok(ok(TrustStore.open(file)).trust(TrustedServer(first, "Friends", setOf("mOgUt4GM"), listOf(FRIENDS))))

        ok(store.trust(TrustedServer(first, "Friends", emptySet(), listOf(FRIENDS))))

        assertEquals(TrustedServer(first, "Friends", emptySet(), listOf(FRIENDS)), ok(TrustStore.open(file)).find(first))
    }

    @Test
    fun `refuses invalid names and mod ids`() {
        val longName = runCatching { TrustedServer(first, "x".repeat(65), emptySet(), emptyList()) }.exceptionOrNull()
        val badMod = runCatching { TrustedServer(first, "Friends", setOf("../x"), emptyList()) }.exceptionOrNull()

        assertEquals("name must have 1 to 64 characters", longName?.message)
        assertEquals("optedOutMods must be Modrinth project ids", badMod?.message)
        val upperCase = runCatching { TrustedServer(first, "Friends", emptySet(), listOf("Play.Example.org:25565")) }.exceptionOrNull()
        assertEquals("addresses must be lowercase host:port", upperCase?.message)
    }

    @Test
    fun `writes addresses with the host in lowercase and refuses invalid ones`() {
        assertEquals("play.example.org:25565", TrustedServer.addressOf("Play.Example.org", 25565))
        assertEquals("invalid port 0", runCatching { TrustedServer.addressOf("play.example.org", 0) }.exceptionOrNull()?.message)
        assertEquals("invalid host", runCatching { TrustedServer.addressOf("play example", 25565) }.exceptionOrNull()?.message)
    }

    @Test
    fun `finds the server trusted at an address, and gives the address to the last key trusted at it`() {
        val store = ok(ok(TrustStore.open(file)).trust(TrustedServer(first, "Friends", emptySet(), listOf(FRIENDS, OTHER))))

        ok(store.trust(TrustedServer(second, "Friends again", emptySet(), listOf(FRIENDS))))

        val reopened = ok(TrustStore.open(file))
        assertEquals(second, reopened.at(FRIENDS)?.key)
        assertEquals(listOf(OTHER), reopened.find(first)?.addresses)
        assertNull(reopened.at("nowhere.example.org:25565"))
    }

    @Test
    fun `keeps the newest addresses of a server`() {
        val many = (1..TrustedServer.MAX_ADDRESSES).map { "host$it.example.org:25565" }

        val server = TrustedServer(first, "Friends", emptySet(), many).at(FRIENDS)

        assertEquals(many.drop(1) + FRIENDS, server.addresses)
        assertEquals(server, server.at(FRIENDS))
    }

    @Test
    fun `reads a store written before addresses were kept`() {
        file.writeText("""{"schema": 1, "servers": [{"key": "${"01".repeat(32)}", "name": "Friends", "optedOutMods": ["mOgUt4GM"]}]}""")

        assertEquals(TrustedServer(first, "Friends", setOf("mOgUt4GM"), emptyList()), ok(TrustStore.open(file)).find(first))
    }

    @Test
    fun `refuses a file it did not write`() {
        file.writeText("""{"schema": 3, "servers": []}""")
        val otherSchema = TrustStore.open(file)
        file.writeText("""{"schema": 2, "servers": [{"key": "abcd", "name": "Friends", "optedOutMods": [], "addresses": []}]}""")
        val shortKey = TrustStore.open(file)
        file.writeText("""{"schema": 2, "servers": [], "extra": true}""")
        val unknownField = TrustStore.open(file)
        file.writeText("""{"schema": 2, "servers": [{"key": "${"01".repeat(32)}", "name": "Friends", "optedOutMods": []}]}""")
        val noAddresses = TrustStore.open(file)

        assertEquals(Outcome.Err(TrustStoreError.Malformed(file, "schema 3 is not supported")), otherSchema)
        assertIs<TrustStoreError.Malformed>((noAddresses as Outcome.Err).error)
        assertEquals(Outcome.Err(TrustStoreError.Malformed(file, "a server key has 2 bytes")), shortKey)
        assertIs<TrustStoreError.Malformed>((unknownField as Outcome.Err).error)
    }

    private companion object {
        const val FRIENDS = "friends.example.org:25565"
        const val OTHER = "10.0.0.2:25570"
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
