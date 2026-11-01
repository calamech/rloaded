package io.github.calamech.rloaded.core.join

import io.github.calamech.rloaded.core.install.InstallPlan
import io.github.calamech.rloaded.core.install.VersionConflict
import io.github.calamech.rloaded.core.trust.TrustStore
import io.github.calamech.rloaded.core.trust.TrustStoreError
import io.github.calamech.rloaded.core.trust.TrustedServer
import io.github.calamech.rloaded.protocol.Checksum
import io.github.calamech.rloaded.protocol.ClientHello
import io.github.calamech.rloaded.protocol.DecodeError
import io.github.calamech.rloaded.protocol.HashAlgorithm
import io.github.calamech.rloaded.protocol.InstallResult
import io.github.calamech.rloaded.protocol.InstallStage
import io.github.calamech.rloaded.protocol.JoinChallenge
import io.github.calamech.rloaded.protocol.Manifest
import io.github.calamech.rloaded.protocol.ManifestSigning
import io.github.calamech.rloaded.protocol.ModEntry
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.PROTOCOL_VERSION
import io.github.calamech.rloaded.protocol.Progress
import io.github.calamech.rloaded.protocol.ProtocolCodec
import io.github.calamech.rloaded.protocol.ServerHello
import io.github.calamech.rloaded.protocol.flatMap
import io.github.calamech.rloaded.protocol.map
import io.github.calamech.rloaded.protocol.mapError
import java.security.MessageDigest

/** Why a client cannot install a server's mods. Each one ends the join. */
public sealed interface JoinFailure {
    /** A message from the server cannot be read. */
    public data class Malformed(
        val reason: String,
    ) : JoinFailure

    /** The manifest came before the server said who it is. */
    public data object NoHello : JoinFailure

    /** The manifest is not signed by the key the server announced. */
    public data object BadSignature : JoinFailure

    /** Mods the game already loaded in other versions; a restart is needed. */
    public data class Conflicts(
        val conflicts: List<VersionConflict>,
    ) : JoinFailure

    /** The player's trust decision cannot be saved. */
    public data class Trust(
        val error: TrustStoreError,
    ) : JoinFailure
}

/** What the client does with a verified manifest. */
public sealed interface ManifestStep {
    /** The player already trusts this server: install [entries]. */
    public data class Install(
        val entries: List<ModEntry>,
    ) : ManifestStep

    /** The player has never trusted this server: show them [entries] and ask. */
    public data class AskTrust(
        val serverName: String,
        val entries: List<ModEntry>,
    ) : ManifestStep

    /**
     * Another key than the one the player trusted at this address answers, [previousName] being the
     * server they trusted there: warn them first, then [ask] as for a new server.
     */
    public data class KeyChanged(
        val previousName: String,
        val ask: AskTrust,
    ) : ManifestStep
}

/**
 * The client's side of a join, without the game: it answers the server's hello with a new challenge,
 * checks the manifest against the key the server announced and that challenge, looks the key up in
 * the trust store, and picks the entries
 * to install. One instance serves one join; [loaded] maps the project ids this game already loaded to
 * their version ids, and [address] is where the player joins, as [TrustedServer.addressOf] writes it,
 * or null when the game does not know it.
 */
public class ClientJoin(
    private var trustStore: TrustStore,
    private val loaded: Map<String, String>,
    private val rloadedVersion: String,
    private val address: String?,
) {
    private var hello: ServerHello? = null
    private val challenge = JoinChallenge.random()

    /** The name the server gave in its hello, once it came. */
    public val serverName: String? get() = hello?.serverName

    /** Reads the server's hello and returns the encoded answer. */
    public fun serverHello(bytes: ByteArray): Outcome<ByteArray, JoinFailure> =
        ProtocolCodec.decode(ServerHello.serializer(), bytes).mapError { JoinFailure.Malformed(it.toString()) }.map { received ->
            hello = received
            ProtocolCodec.encode(ClientHello.serializer(), ClientHello(PROTOCOL_VERSION, rloadedVersion, loadedSetHash(loaded), challenge))
        }

    /** Reads and checks the manifest, and says whether to install at once or ask the player first. */
    public fun manifest(bytes: ByteArray): Outcome<ManifestStep, JoinFailure> {
        val server = hello ?: return Outcome.Err(JoinFailure.NoHello)
        return ProtocolCodec
            .decode(Manifest.serializer(), bytes)
            .mapError<Manifest, DecodeError, JoinFailure> { JoinFailure.Malformed(it.toString()) }
            .flatMap { manifest -> ManifestSigning.verify(manifest, challenge, server.serverKey).mapError { JoinFailure.BadSignature } }
            .flatMap { manifest ->
                when (val trusted = trustStore.find(server.serverKey)) {
                    null -> Outcome.Ok(askTrust(server.serverName, manifest.entries))
                    else -> remember(trusted).flatMap { plan(manifest.entries, trusted.optedOutMods) }.map { ManifestStep.Install(it) }
                }
            }
    }

    private fun askTrust(
        serverName: String,
        entries: List<ModEntry>,
    ): ManifestStep {
        val ask = ManifestStep.AskTrust(serverName, entries)
        val previous = address?.let { trustStore.at(it) }
        return if (previous == null) ask else ManifestStep.KeyChanged(previous.name, ask)
    }

    // A trusted server joined at a new address keeps that address too.
    private fun remember(trusted: TrustedServer): Outcome<Unit, JoinFailure> =
        if (address == null || address in trusted.addresses) {
            Outcome.Ok(Unit)
        } else {
            save(trusted.at(address))
        }

    private fun save(server: TrustedServer): Outcome<Unit, JoinFailure> =
        trustStore.trust(server).mapError<TrustStore, TrustStoreError, JoinFailure> { JoinFailure.Trust(it) }.map { updated ->
            trustStore = updated
        }

    /**
     * Records that the player trusts the server, leaving out the client-only mods in [optedOut], and
     * returns the entries to install.
     */
    public fun trust(
        entries: List<ModEntry>,
        optedOut: Set<String>,
    ): Outcome<List<ModEntry>, JoinFailure> {
        val server = hello ?: return Outcome.Err(JoinFailure.NoHello)
        return save(TrustedServer(server.serverKey, server.serverName, optedOut, listOfNotNull(address)))
            .flatMap { plan(entries, optedOut) }
    }

    private fun plan(
        entries: List<ModEntry>,
        optedOut: Set<String>,
    ): Outcome<List<ModEntry>, JoinFailure> = InstallPlan.entriesToInstall(entries, optedOut, loaded).mapError { JoinFailure.Conflicts(it) }

    /** Encoding the client's other messages. */
    public companion object {
        /** The encoded [Progress]. */
        public fun progress(
            stage: InstallStage,
            percent: Int,
        ): ByteArray = ProtocolCodec.encode(Progress.serializer(), Progress(stage, percent))

        /** The encoded [InstallResult]. */
        public fun result(result: InstallResult): ByteArray = ProtocolCodec.encode(InstallResult.serializer(), result)

        /** SHA-256 of the loaded mods, one `projectId:versionId` line each, sorted. */
        public fun loadedSetHash(loaded: Map<String, String>): Checksum {
            val text =
                loaded.entries
                    .map { "${it.key}:${it.value}\n" }
                    .sorted()
                    .joinToString("")
            val hex = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).toHexString()
            return when (val parsed = Checksum.parse(HashAlgorithm.SHA256, hex)) {
                is Outcome.Ok -> parsed.value
                is Outcome.Err -> error("SHA-256 produced ${hex.length} hexadecimal digits")
            }
        }
    }
}
