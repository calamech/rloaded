package io.github.calamech.rloaded.core.trust

import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.ServerKey
import io.github.calamech.rloaded.protocol.traverse
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.Locale

/**
 * A server the player trusts, by its key: its later adds install without asking. [optedOutMods] are
 * the client-only mods the player chose not to install, by Modrinth project id. [addresses] are the
 * addresses the player joined it at, oldest first, as [addressOf] writes them: another key at one of
 * them is a changed identity.
 */
public data class TrustedServer(
    val key: ServerKey,
    val name: String,
    val optedOutMods: Set<String>,
    val addresses: List<String>,
) {
    init {
        require(name.length in 1..MAX_NAME) { "name must have 1 to $MAX_NAME characters" }
        require(optedOutMods.all { PROJECT_ID.matches(it) }) { "optedOutMods must be Modrinth project ids" }
        require(addresses.size <= MAX_ADDRESSES && addresses.toSet().size == addresses.size) {
            "addresses must be at most $MAX_ADDRESSES different addresses"
        }
        require(addresses.all { ADDRESS.matches(it) }) { "addresses must be lowercase host:port" }
    }

    /** This server, also known at [address]; the oldest address goes past [MAX_ADDRESSES]. */
    public fun at(address: String): TrustedServer =
        if (address in addresses) this else copy(addresses = (addresses + address).takeLast(MAX_ADDRESSES))

    /** Building addresses. */
    public companion object {
        /** The most addresses kept for one server. */
        public const val MAX_ADDRESSES: Int = 16

        private const val MAX_NAME = 64
        private const val MAX_HOST = 253
        private const val MAX_PORT = 65_535
        private val PROJECT_ID = Regex("[A-Za-z0-9]{1,16}")
        private val ADDRESS = Regex("[^A-Z\\s]{1,$MAX_HOST}:[0-9]{1,5}")

        /** The address a player joins at, as the store keeps it: the host in lowercase, then the port. */
        public fun addressOf(
            host: String,
            port: Int,
        ): String {
            require(host.isNotBlank() && host.length <= MAX_HOST && host.none { it.isWhitespace() }) { "invalid host" }
            require(port in 1..MAX_PORT) { "invalid port $port" }
            return "${host.lowercase(Locale.ROOT)}:$port"
        }
    }
}

/** The servers the player trusts, kept in one file per player. Each change is written at once. */
public class TrustStore private constructor(
    private val file: Path,
    private val servers: Map<ServerKey, TrustedServer>,
) {
    /** The trust given to the server with [key], or null if the player never accepted it. */
    public fun find(key: ServerKey): TrustedServer? = servers[key]

    /** The trusted server last joined at [address], or null if none was. */
    public fun at(address: String): TrustedServer? = servers.values.firstOrNull { address in it.addresses }

    /**
     * Records [server], replacing an earlier record of the same key, and writes the file. Its
     * addresses leave any other server: an address belongs to the last key trusted at it.
     */
    public fun trust(server: TrustedServer): Outcome<TrustStore, TrustStoreError> {
        val updated =
            servers.mapValues { (_, other) -> other.copy(addresses = other.addresses - server.addresses.toSet()) } +
                (server.key to server)
        val json = StoreJson(SCHEMA, updated.values.sortedBy { it.key.bytes().toHexString() }.map { it.toJson() })
        return try {
            AtomicFiles.write(file, FORMAT.encodeToString(StoreJson.serializer(), json).encodeToByteArray())
            Outcome.Ok(TrustStore(file, updated))
        } catch (e: IOException) {
            Outcome.Err(TrustStoreError.Unwritable(file, e.message ?: e.javaClass.simpleName))
        }
    }

    /** Opening trust stores. */
    public companion object {
        private const val SCHEMA = 2

        // Written before addresses were kept; its servers are read with none.
        private const val SCHEMA_WITHOUT_ADDRESSES = 1
        private val FORMAT = Json { prettyPrint = true }
        private val SCHEMA_ONLY = Json { ignoreUnknownKeys = true }

        /** Reads the store in [file]; a missing file is an empty store. */
        public fun open(file: Path): Outcome<TrustStore, TrustStoreError> =
            try {
                parse(file, Files.readString(file))
            } catch (_: NoSuchFileException) {
                Outcome.Ok(TrustStore(file, emptyMap()))
            } catch (e: IOException) {
                Outcome.Err(TrustStoreError.Unreadable(file, e.message ?: e.javaClass.simpleName))
            }

        private fun parse(
            file: Path,
            text: String,
        ): Outcome<TrustStore, TrustStoreError> =
            try {
                val servers =
                    when (val schema = SCHEMA_ONLY.decodeFromString(SchemaJson.serializer(), text).schema) {
                        SCHEMA -> {
                            Json.decodeFromString(StoreJson.serializer(), text).servers
                        }

                        SCHEMA_WITHOUT_ADDRESSES -> {
                            Json.decodeFromString(StoreJsonWithoutAddresses.serializer(), text).servers.map {
                                ServerJson(it.key, it.name, it.optedOutMods, emptyList())
                            }
                        }

                        else -> {
                            return Outcome.Err(TrustStoreError.Malformed(file, "schema $schema is not supported"))
                        }
                    }
                when (val parsed = servers.traverse { it.toServer(file) }) {
                    is Outcome.Ok -> Outcome.Ok(TrustStore(file, parsed.value.associateBy { it.key }))
                    is Outcome.Err -> parsed
                }
            } catch (e: SerializationException) {
                Outcome.Err(TrustStoreError.Malformed(file, e.message ?: "invalid JSON"))
            } catch (e: IllegalArgumentException) {
                Outcome.Err(TrustStoreError.Malformed(file, e.message ?: "invalid JSON"))
            }

        private fun TrustedServer.toJson() = ServerJson(key.bytes().toHexString(), name, optedOutMods.sorted(), addresses)

        private fun ServerJson.toServer(file: Path): Outcome<TrustedServer, TrustStoreError> =
            when (val parsed = ServerKey.of(key.hexToByteArray())) {
                is Outcome.Ok -> Outcome.Ok(TrustedServer(parsed.value, name, optedOutMods.toSet(), addresses))
                is Outcome.Err -> Outcome.Err(TrustStoreError.Malformed(file, "a server key has ${parsed.error.actual} bytes"))
            }
    }

    // Only the schema, read first to pick the format of the rest.
    @Serializable
    private data class SchemaJson(
        val schema: Int,
    )

    @Serializable
    private data class StoreJson(
        val schema: Int,
        val servers: List<ServerJson>,
    )

    @Serializable
    private data class ServerJson(
        val key: String,
        val name: String,
        val optedOutMods: List<String>,
        val addresses: List<String>,
    )

    @Serializable
    private data class StoreJsonWithoutAddresses(
        val schema: Int,
        val servers: List<ServerJsonWithoutAddresses>,
    ) {
        @Serializable
        data class ServerJsonWithoutAddresses(
            val key: String,
            val name: String,
            val optedOutMods: List<String>,
        )
    }
}

/** Why the trust store cannot be used. */
public sealed interface TrustStoreError {
    /** The file exists but cannot be read. */
    public data class Unreadable(
        val path: Path,
        val reason: String,
    ) : TrustStoreError

    /** The file is not a trust store written by this version. */
    public data class Malformed(
        val path: Path,
        val reason: String,
    ) : TrustStoreError

    /** The file cannot be written. */
    public data class Unwritable(
        val path: Path,
        val reason: String,
    ) : TrustStoreError
}
