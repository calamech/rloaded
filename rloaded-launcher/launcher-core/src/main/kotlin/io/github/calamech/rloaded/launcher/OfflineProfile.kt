package io.github.calamech.rloaded.launcher

import io.github.calamech.rloaded.protocol.Outcome
import java.util.UUID

/**
 * A player without a Microsoft account, for development against servers in offline mode. The UUID
 * is the one an offline server derives from the name, so the player keeps the same identity.
 */
public class OfflineProfile private constructor(
    /** The player name shown in game. */
    public val name: String,
) {
    /** The UUID an offline server gives this name. */
    public val uuid: UUID = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))

    override fun equals(other: Any?): Boolean = other is OfflineProfile && other.name == name

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = "OfflineProfile($name)"

    /** Creation from user input. */
    public companion object {
        private val VALID_NAME = Regex("[A-Za-z0-9_]{3,16}")

        /** Accepts the names Minecraft accepts: 3 to 16 letters, digits or underscores. */
        public fun of(name: String): Outcome<OfflineProfile, InvalidPlayerName> =
            if (VALID_NAME.matches(name)) Outcome.Ok(OfflineProfile(name)) else Outcome.Err(InvalidPlayerName(name))
    }
}

/** A player name Minecraft would refuse. */
public data class InvalidPlayerName(
    val name: String,
)

/** A server to join at startup. */
public data class ServerAddress(
    val host: String,
    val port: Int = DEFAULT_PORT,
) {
    override fun toString(): String = if (port == DEFAULT_PORT) host else "$host:$port"

    /** Reading addresses typed by the player. */
    public companion object {
        private const val DEFAULT_PORT = 25565
        private val PORTS = 1..65_535
        private val HOST = Regex("[A-Za-z0-9.-]+")

        /** Reads `host` or `host:port`. */
        public fun parse(text: String): Outcome<ServerAddress, InvalidServerAddress> {
            val host = text.trim().substringBeforeLast(':')
            val port = if (':' in text) text.trim().substringAfterLast(':').toIntOrNull() else DEFAULT_PORT
            return if (HOST.matches(host) && port != null && port in PORTS) {
                Outcome.Ok(ServerAddress(host, port))
            } else {
                Outcome.Err(InvalidServerAddress(text))
            }
        }
    }
}

/** A server address that is not `host` or `host:port`. */
public data class InvalidServerAddress(
    val text: String,
)
