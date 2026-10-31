package io.github.calamech.rloaded.bootstrap

import com.akuleshov7.ktoml.Toml
import io.github.calamech.rloaded.admin.ReputationRules
import io.github.calamech.rloaded.admin.staging.StagingSettings
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
    val reputation: ReputationSection = ReputationSection(),
    val staging: StagingSection = StagingSection(),
) {
    /** The settings the game reads, as the system properties the bootstrap starts it with. */
    public fun gameProperties(): Map<String, String> =
        mapOf(
            JoinDeadlines.TIMEOUT_PROPERTY to rollout.clientTimeoutSeconds.toString(),
            ReputationRules.VERSION_AGE_PROPERTY to reputation.minVersionAgeHours.toString(),
            ReputationRules.DOWNLOADS_PROPERTY to reputation.minProjectDownloads.toString(),
            StagingSettings.ENABLED_PROPERTY to staging.enabled.toString(),
            StagingSettings.TIMEOUT_PROPERTY to staging.timeoutSeconds.toString(),
        )

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

            [reputation]
            # A mod version is added only once it has been public this many hours. 0 turns the rule off.
            minVersionAgeHours = 72
            # A mod is added only once its project has been downloaded this many times. 0 turns the rule off.
            minProjectDownloads = 1000

            [staging]
            # Before a mod is added, try it with the server's mods on a test server that nobody plays on,
            # in a new flat world, and refuse it if it fails there.
            enabled = true
            # Seconds the test server may run before the mod is refused.
            timeoutSeconds = 180

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

                    config.reputation.minVersionAgeHours !in ReputationRules.VERSION_AGE_HOURS -> {
                        Outcome.Err(outOfRange("reputation.minVersionAgeHours", ReputationRules.VERSION_AGE_HOURS))
                    }

                    config.reputation.minProjectDownloads !in ReputationRules.PROJECT_DOWNLOADS -> {
                        Outcome.Err(outOfRange("reputation.minProjectDownloads", ReputationRules.PROJECT_DOWNLOADS))
                    }

                    config.staging.timeoutSeconds !in StagingSettings.TIMEOUT_SECONDS -> {
                        Outcome.Err(outOfRange("staging.timeoutSeconds", StagingSettings.TIMEOUT_SECONDS))
                    }

                    else -> {
                        Outcome.Ok(config)
                    }
                }
            }

        private fun outOfRange(
            key: String,
            range: LongRange,
        ): String = "$key must be between ${range.first} and ${range.last}"

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

/** The `[reputation]` table. */
@Serializable
public data class ReputationSection(
    val minVersionAgeHours: Long = ReputationRules.DEFAULT_VERSION_AGE_HOURS,
    val minProjectDownloads: Long = ReputationRules.DEFAULT_PROJECT_DOWNLOADS,
)

/** The `[staging]` table. */
@Serializable
public data class StagingSection(
    val enabled: Boolean = true,
    val timeoutSeconds: Long = StagingSettings.DEFAULT_TIMEOUT_SECONDS,
)
