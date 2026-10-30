package io.github.calamech.rloaded.bootstrap

import com.akuleshov7.ktoml.Toml
import io.github.calamech.rloaded.core.join.JoinDeadlines
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.flatMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** Settings of `rloaded-server.toml`. Unknown keys are refused, so a typo never goes unnoticed. */
@Serializable
public data class ServerConfig(
    val server: ServerSection = ServerSection(),
    val rollout: RolloutSection = RolloutSection(),
) {
    /** Reading and writing `rloaded-server.toml`. */
    public companion object {
        /** The file written at first start, with every setting at its default. */
        public val TEMPLATE: String =
            """
            # Rloaded server settings. Restart the server after changing them.

            [server]
            # Name shown to players when they first join. Empty means the name of this folder.
            name = ""
            # Memory given to the game, in MiB.
            maxMemoryMiB = 4096

            [rollout]
            # Seconds the server waits for a player's game to report progress while it installs the
            # server's mods, before it disconnects the player.
            clientTimeoutSeconds = 120

            """.trimIndent()

        private val MEMORY_RANGE = 512..65_536

        /** Reads [text], refusing unknown keys and values out of range. */
        public fun parse(text: String): Outcome<ServerConfig, String> =
            decode(text).flatMap { config ->
                val timeouts = JoinDeadlines.TIMEOUT_SECONDS
                when {
                    config.server.maxMemoryMiB !in MEMORY_RANGE -> {
                        Outcome.Err("server.maxMemoryMiB must be between ${MEMORY_RANGE.first} and ${MEMORY_RANGE.last}")
                    }

                    config.rollout.clientTimeoutSeconds !in timeouts -> {
                        Outcome.Err("rollout.clientTimeoutSeconds must be between ${timeouts.first} and ${timeouts.last}")
                    }

                    else -> {
                        Outcome.Ok(config)
                    }
                }
            }

        private fun decode(text: String): Outcome<ServerConfig, String> =
            try {
                Outcome.Ok(Toml.decodeFromString(serializer(), text))
            } catch (e: SerializationException) {
                Outcome.Err(e.message ?: "invalid TOML")
            } catch (e: IllegalArgumentException) {
                Outcome.Err(e.message ?: "invalid TOML")
            }
    }
}

/** The `[server]` table. */
@Serializable
public data class ServerSection(
    val name: String = "",
    val maxMemoryMiB: Int = DEFAULT_MEMORY_MIB,
) {
    private companion object {
        const val DEFAULT_MEMORY_MIB = 4096
    }
}

/** The `[rollout]` table. */
@Serializable
public data class RolloutSection(
    val clientTimeoutSeconds: Int = JoinDeadlines.DEFAULT_TIMEOUT_SECONDS,
)
