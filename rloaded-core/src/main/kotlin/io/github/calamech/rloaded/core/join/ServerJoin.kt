package io.github.calamech.rloaded.core.join

import io.github.calamech.rloaded.core.lockfile.Lockfile
import io.github.calamech.rloaded.core.lockfile.entries
import io.github.calamech.rloaded.core.trust.ServerIdentity
import io.github.calamech.rloaded.protocol.ClientHello
import io.github.calamech.rloaded.protocol.DecodeError
import io.github.calamech.rloaded.protocol.InstallResult
import io.github.calamech.rloaded.protocol.Manifest
import io.github.calamech.rloaded.protocol.ManifestKind
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.PROTOCOL_VERSION
import io.github.calamech.rloaded.protocol.ProtocolCodec
import io.github.calamech.rloaded.protocol.ServerHello
import io.github.calamech.rloaded.protocol.flatMap
import io.github.calamech.rloaded.protocol.mapError

/** Why the server ends a player's join. */
public sealed interface JoinRefusal {
    /** The client speaks another version of the protocol. */
    public data class OtherProtocol(
        val clientProtocol: Int,
    ) : JoinRefusal

    /** A message from the client cannot be read. */
    public data class Malformed(
        val reason: String,
    ) : JoinRefusal
}

/**
 * The server's side of a join, without the game: the messages it sends and how it reads the
 * client's answers. The manifest lists every mod of the lockfile, signed with the server identity.
 */
public class ServerJoin(
    private val identity: ServerIdentity,
    private val lockfile: Lockfile,
    private val serverName: String,
) {
    /** The encoded [ServerHello]. */
    public fun hello(): ByteArray =
        ProtocolCodec.encode(ServerHello.serializer(), ServerHello(PROTOCOL_VERSION, identity.publicKey, serverName, lockfile.hash()))

    /** The encoded full manifest. */
    public fun manifest(): ByteArray =
        ProtocolCodec.encode(
            Manifest.serializer(),
            identity.sign(ManifestKind.FULL, lockfile.entries()),
        )

    /** Reads the client's hello, refusing another protocol version. */
    public fun clientHello(bytes: ByteArray): Outcome<ClientHello, JoinRefusal> =
        ProtocolCodec
            .decode(ClientHello.serializer(), bytes)
            .mapError<ClientHello, DecodeError, JoinRefusal> { JoinRefusal.Malformed(it.toString()) }
            .flatMap { hello ->
                if (hello.protocol == PROTOCOL_VERSION) Outcome.Ok(hello) else Outcome.Err(JoinRefusal.OtherProtocol(hello.protocol))
            }

    /** Reads how the client's install ended. */
    public fun result(bytes: ByteArray): Outcome<InstallResult, JoinRefusal> =
        ProtocolCodec.decode(InstallResult.serializer(), bytes).mapError { JoinRefusal.Malformed(it.toString()) }
}
