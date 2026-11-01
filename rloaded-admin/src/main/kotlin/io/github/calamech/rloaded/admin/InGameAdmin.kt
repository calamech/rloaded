package io.github.calamech.rloaded.admin

import io.github.calamech.rloaded.protocol.Outcome

/** Whether operators of a dedicated server may add mods from the game's Mods screen. */
public data class InGameAdmin(
    val enabled: Boolean,
) {
    /** Whether a player may add mods from the Mods screen: only an operator of level 4, and only when [enabled]. */
    public fun allows(ownerLevel: Boolean): Boolean = enabled && ownerLevel

    /** The system property that carries the setting to the game. */
    public companion object {
        /** `true` or `false`, as a system property. */
        public const val PROPERTY: String = "rloaded.admin.in-game"

        /** Reads the setting from the system properties [lookup] returns; a missing or invalid value is refused. */
        public fun fromProperties(lookup: (String) -> String?): Outcome<InGameAdmin, String> =
            when (val value = lookup(PROPERTY)) {
                null -> Outcome.Err("$PROPERTY is not set; start the server with rloaded-server.jar")
                "true" -> Outcome.Ok(InGameAdmin(true))
                "false" -> Outcome.Ok(InGameAdmin(false))
                else -> Outcome.Err("$PROPERTY must be true or false, not $value")
            }
    }
}
