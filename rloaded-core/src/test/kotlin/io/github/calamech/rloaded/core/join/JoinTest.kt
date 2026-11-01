package io.github.calamech.rloaded.core.join

import io.github.calamech.rloaded.core.install.VersionConflict
import io.github.calamech.rloaded.core.lockfile.LockedMod
import io.github.calamech.rloaded.core.lockfile.Lockfile
import io.github.calamech.rloaded.core.trust.ServerIdentity
import io.github.calamech.rloaded.core.trust.TrustStore
import io.github.calamech.rloaded.protocol.Checksum
import io.github.calamech.rloaded.protocol.ClientHello
import io.github.calamech.rloaded.protocol.HashAlgorithm
import io.github.calamech.rloaded.protocol.InstallResult
import io.github.calamech.rloaded.protocol.InstallStage
import io.github.calamech.rloaded.protocol.JoinChallenge
import io.github.calamech.rloaded.protocol.ModEntry
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.PROTOCOL_VERSION
import io.github.calamech.rloaded.protocol.Progress
import io.github.calamech.rloaded.protocol.ProtocolCodec
import io.github.calamech.rloaded.protocol.Side
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class JoinTest {
    private companion object {
        const val ADDRESS = "127.0.0.1:25565"
    }

    @TempDir
    lateinit var directory: Path

    private val both = entry("bothMod", Side.BOTH)
    private val client = entry("clientMod", Side.CLIENT)
    private val server = entry("serverMod", Side.SERVER)

    private val identity get() = ok(ServerIdentity.loadOrCreate(directory.resolve("server/identity.key")))
    private val lockfile =
        Lockfile("26.3", "0.1.0", emptyList(), listOf(both, client, server).map { LockedMod(it, Instant.EPOCH, "console") }, emptyList())
    private val trustFile get() = directory.resolve("client/trust.json")

    @Test
    fun `a first join asks for trust, and the next one installs at once without the opted out mod`() {
        val serverJoin = ServerJoin(identity, lockfile, "Friends SMP")
        val first = ClientJoin(ok(TrustStore.open(trustFile)), emptyMap(), "0.1.0", ADDRESS)

        val hello = ok(serverJoin.clientHello(ok(first.serverHello(serverJoin.hello()))))
        assertEquals("0.1.0", hello.rloadedVersion)
        val step = ok(first.manifest(serverJoin.manifest(hello)))
        assertEquals(ManifestStep.AskTrust("Friends SMP", listOf(both, client, server)), step)
        assertEquals(listOf(both), ok(first.trust(listOf(both, client, server), setOf("clientMod"))))

        val second = ClientJoin(ok(TrustStore.open(trustFile)), emptyMap(), "0.1.0", ADDRESS)

        assertEquals(ManifestStep.Install(listOf(both)), ok(second.manifest(manifestFor(second, serverJoin))))
    }

    @Test
    fun `warns when another key answers at an address where the player trusted a server`() {
        trustFriendsAt(ADDRESS)
        val reinstalled = ServerJoin(ok(ServerIdentity.loadOrCreate(directory.resolve("reinstalled.key"))), lockfile, "Friends SMP")
        val join = ClientJoin(ok(TrustStore.open(trustFile)), emptyMap(), "0.1.0", ADDRESS)

        val step = ok(join.manifest(manifestFor(join, reinstalled)))

        assertEquals(ManifestStep.KeyChanged("Friends SMP", ManifestStep.AskTrust("Friends SMP", listOf(both, client, server))), step)
        ok(join.trust(listOf(both, client, server), emptySet()))
        val moved = ok(TrustStore.open(trustFile))
        assertEquals(listOf(ADDRESS), moved.at(ADDRESS)?.addresses)
        assertNotEquals(identity.publicKey, moved.at(ADDRESS)?.key)
        assertEquals(emptyList(), moved.find(identity.publicKey)?.addresses, "the old key keeps its trust, not the address")
    }

    @Test
    fun `asks as for a new server when the address was never trusted, and remembers a new address of a trusted key`() {
        trustFriendsAt(ADDRESS)
        val serverJoin = ServerJoin(identity, lockfile, "Friends SMP")
        val elsewhere = ClientJoin(ok(TrustStore.open(trustFile)), emptyMap(), "0.1.0", "friends.example.org:25565")

        assertEquals(ManifestStep.Install(listOf(both, client)), ok(elsewhere.manifest(manifestFor(elsewhere, serverJoin))))
        assertEquals(listOf(ADDRESS, "friends.example.org:25565"), ok(TrustStore.open(trustFile)).find(identity.publicKey)?.addresses)

        val stranger = ServerJoin(ok(ServerIdentity.loadOrCreate(directory.resolve("stranger.key"))), lockfile, "Stranger")
        val unknown = ClientJoin(ok(TrustStore.open(trustFile)), emptyMap(), "0.1.0", "stranger.example.org:25565")
        assertIs<ManifestStep.AskTrust>(ok(unknown.manifest(manifestFor(unknown, stranger))))
    }

    @Test
    fun `refuses a manifest signed by another key than the one announced`() {
        val announced = ServerJoin(identity, lockfile, "Friends SMP")
        val impostor = ServerJoin(ok(ServerIdentity.loadOrCreate(directory.resolve("impostor.key"))), lockfile, "Friends SMP")
        val join = ClientJoin(ok(TrustStore.open(trustFile)), emptyMap(), "0.1.0", ADDRESS)
        val hello = ok(announced.clientHello(ok(join.serverHello(announced.hello()))))

        assertEquals(Outcome.Err(JoinFailure.BadSignature), join.manifest(impostor.manifest(hello)))
    }

    @Test
    fun `refuses a manifest the server signed for another join`() {
        val serverJoin = ServerJoin(identity, lockfile, "Friends SMP")
        val earlier = ClientJoin(ok(TrustStore.open(trustFile)), emptyMap(), "0.1.0", ADDRESS)
        val replayed = manifestFor(earlier, serverJoin)
        val join = ClientJoin(ok(TrustStore.open(trustFile)), emptyMap(), "0.1.0", ADDRESS)
        ok(join.serverHello(serverJoin.hello()))

        assertEquals(Outcome.Err(JoinFailure.BadSignature), join.manifest(replayed))
    }

    @Test
    fun `refuses a manifest that comes before the hello, and bytes that are not a message`() {
        val join = ClientJoin(ok(TrustStore.open(trustFile)), emptyMap(), "0.1.0", ADDRESS)

        val other = ClientJoin(ok(TrustStore.open(trustFile)), emptyMap(), "0.1.0", ADDRESS)

        assertEquals(Outcome.Err(JoinFailure.NoHello), join.manifest(manifestFor(other, ServerJoin(identity, lockfile, "Friends SMP"))))
        assertIs<JoinFailure.Malformed>((join.serverHello(byteArrayOf(1, 2, 3)) as Outcome.Err).error)
    }

    @Test
    fun `reports mods the game loaded in another version`() {
        val serverJoin = ServerJoin(identity, lockfile, "Friends SMP")
        val join = ClientJoin(ok(TrustStore.open(trustFile)), mapOf("bothMod" to "old"), "0.1.0", ADDRESS)
        ok(join.manifest(manifestFor(join, serverJoin)))

        assertEquals(
            Outcome.Err(JoinFailure.Conflicts(listOf(VersionConflict("bothMod", "old", "v1")))),
            join.trust(listOf(both, client), emptySet()),
        )
    }

    @Test
    fun `the server refuses another protocol version and reads the install result`() {
        val serverJoin = ServerJoin(identity, lockfile, "Friends SMP")
        val newer =
            ProtocolCodec.encode(
                ClientHello.serializer(),
                ClientHello(PROTOCOL_VERSION + 1, "9.0.0", ClientJoin.loadedSetHash(emptyMap()), JoinChallenge.random()),
            )
        val failed = InstallResult.Failed("bothMod", "no space left")

        assertEquals(Outcome.Err(JoinRefusal.OtherProtocol(PROTOCOL_VERSION + 1)), serverJoin.clientHello(newer))
        assertEquals(Outcome.Ok(failed), serverJoin.result(ClientJoin.result(failed)))
        assertIs<JoinRefusal.Malformed>((serverJoin.result(byteArrayOf(9)) as Outcome.Err).error)
    }

    @Test
    fun `the server reads the client's progress and refuses bytes that are not progress`() {
        val serverJoin = ServerJoin(identity, lockfile, "Friends SMP")

        assertEquals(Outcome.Ok(Progress(InstallStage.LOAD, 0)), serverJoin.progress(ClientJoin.progress(InstallStage.LOAD, 0)))
        assertIs<JoinRefusal.Malformed>((serverJoin.progress(ClientJoin.result(InstallResult.Loaded)) as Outcome.Err).error)
    }

    @Test
    fun `the loaded set hash does not depend on order and changes with any version`() {
        val one = ClientJoin.loadedSetHash(linkedMapOf("a" to "1", "b" to "2"))
        val reversed = ClientJoin.loadedSetHash(linkedMapOf("b" to "2", "a" to "1"))
        val other = ClientJoin.loadedSetHash(mapOf("a" to "1", "b" to "3"))

        assertEquals(one, reversed)
        assertNotEquals(one, other)
    }

    private fun trustFriendsAt(address: String) {
        val first = ClientJoin(ok(TrustStore.open(trustFile)), emptyMap(), "0.1.0", address)
        ok(first.manifest(manifestFor(first, ServerJoin(identity, lockfile, "Friends SMP"))))
        ok(first.trust(listOf(both, client, server), emptySet()))
    }

    // The client answers the server's hello, and the server signs its manifest for that answer.
    private fun manifestFor(
        client: ClientJoin,
        server: ServerJoin,
    ): ByteArray = server.manifest(ok(server.clientHello(ok(client.serverHello(server.hello())))))

    private fun entry(
        projectId: String,
        side: Side,
    ): ModEntry {
        val sha = (Checksum.parse(HashAlgorithm.SHA512, "a".repeat(128)) as Outcome.Ok).value
        return ModEntry(projectId, "v1", "$projectId.jar", sha, 10, side)
    }

    private fun <T, E> ok(outcome: Outcome<T, E>): T =
        when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw AssertionError("expected a value, got ${outcome.error}")
        }
}
