package io.github.calamech.rloaded.admin.staging

import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.flatMap
import io.github.calamech.rloaded.protocol.map
import java.time.Duration

/** Whether an add is tried on a staging server first, and how long that may take. */
public data class StagingSettings(
    val enabled: Boolean,
    val timeout: Duration,
) {
    /** Bounds, defaults, and the system properties that carry the settings to the game. */
    public companion object {
        /** `true` or `false`, as a system property. */
        public const val ENABLED_PROPERTY: String = "rloaded.staging.enabled"

        /** Seconds a staging run may take, as a system property. */
        public const val TIMEOUT_PROPERTY: String = "rloaded.staging.timeout-seconds"

        /** Allowed timeouts, in seconds. */
        public val TIMEOUT_SECONDS: LongRange = 30L..3_600L

        /** The timeout of a new server, in seconds. */
        public const val DEFAULT_TIMEOUT_SECONDS: Long = 180

        /** Reads the settings from the system properties [lookup] returns; a missing or invalid value is refused. */
        public fun fromProperties(lookup: (String) -> String?): Outcome<StagingSettings, String> =
            enabled(lookup(ENABLED_PROPERTY)).flatMap { enabled ->
                timeout(lookup(TIMEOUT_PROPERTY)).map { seconds -> StagingSettings(enabled, Duration.ofSeconds(seconds)) }
            }

        private fun enabled(value: String?): Outcome<Boolean, String> =
            when (value) {
                null -> Outcome.Err("$ENABLED_PROPERTY is not set; start the server with rloaded-server.jar")
                "true" -> Outcome.Ok(true)
                "false" -> Outcome.Ok(false)
                else -> Outcome.Err("$ENABLED_PROPERTY must be true or false, not $value")
            }

        private fun timeout(value: String?): Outcome<Long, String> {
            val seconds = value?.toLongOrNull()
            return when {
                value == null -> {
                    Outcome.Err("$TIMEOUT_PROPERTY is not set; start the server with rloaded-server.jar")
                }

                seconds == null || seconds !in TIMEOUT_SECONDS -> {
                    Outcome.Err("$TIMEOUT_PROPERTY must be between ${TIMEOUT_SECONDS.first} and ${TIMEOUT_SECONDS.last}, not $value")
                }

                else -> {
                    Outcome.Ok(seconds)
                }
            }
        }
    }
}
