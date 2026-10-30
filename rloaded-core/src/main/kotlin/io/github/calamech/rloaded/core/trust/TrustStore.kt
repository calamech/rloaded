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

/**
 * A server the player trusts, by its key: its later adds install without asking. [optedOutMods] are
 * the client-only mods the player chose not to install, by Modrinth project id.
 */
public data class TrustedServer(
    val key: ServerKey,
    val name: String,
    val optedOutMods: Set<String>,
) {
    init {
        require(name.length in 1..MAX_NAME) { "name must have 1 to $MAX_NAME characters" }
        require(optedOutMods.all { PROJECT_ID.matches(it) }) { "optedOutMods must be Modrinth project ids" }
    }

    private companion object {
        const val MAX_NAME = 64
        val PROJECT_ID = Regex("[A-Za-z0-9]{1,16}")
    }
}

/** The servers the player trusts, kept in one file per player. Each change is written at once. */
public class TrustStore private constructor(
    private val file: Path,
    private val servers: Map<ServerKey, TrustedServer>,
) {
    /** The trust given to the server with [key], or null if the player never accepted it. */
    public fun find(key: ServerKey): TrustedServer? = servers[key]

    /** Records [server], replacing an earlier record of the same key, and writes the file. */
    public fun trust(server: TrustedServer): Outcome<TrustStore, TrustStoreError> {
        val updated = servers + (server.key to server)
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
        private const val SCHEMA = 1
        private val FORMAT = Json { prettyPrint = true }

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
                val json = Json.decodeFromString(StoreJson.serializer(), text)
                if (json.schema != SCHEMA) {
                    Outcome.Err(TrustStoreError.Malformed(file, "schema ${json.schema} is not supported"))
                } else {
                    when (val servers = json.servers.traverse { it.toServer(file) }) {
                        is Outcome.Ok -> Outcome.Ok(TrustStore(file, servers.value.associateBy { it.key }))
                        is Outcome.Err -> servers
                    }
                }
            } catch (e: SerializationException) {
                Outcome.Err(TrustStoreError.Malformed(file, e.message ?: "invalid JSON"))
            } catch (e: IllegalArgumentException) {
                Outcome.Err(TrustStoreError.Malformed(file, e.message ?: "invalid JSON"))
            }

        private fun TrustedServer.toJson() = ServerJson(key.bytes().toHexString(), name, optedOutMods.sorted())

        private fun ServerJson.toServer(file: Path): Outcome<TrustedServer, TrustStoreError> =
            when (val parsed = ServerKey.of(key.hexToByteArray())) {
                is Outcome.Ok -> Outcome.Ok(TrustedServer(parsed.value, name, optedOutMods.toSet()))
                is Outcome.Err -> Outcome.Err(TrustStoreError.Malformed(file, "a server key has ${parsed.error.actual} bytes"))
            }
    }

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
    )
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
