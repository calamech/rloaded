package io.github.calamech.rloaded.admin

import java.util.UUID

/** Who asks for an add, which also says from where. */
public sealed interface AddActor {
    /** What the lockfile records as the mod's `addedBy`. */
    public val label: String

    /** The server console, or RCON, with `rloaded add`. */
    public data object Console : AddActor {
        override val label: String = "console"
    }

    /** A player of a dedicated server, from the game's Mods screen. */
    public data class DedicatedPlayer(
        val name: String,
        val id: UUID,
    ) : AddActor {
        override val label: String get() = name
    }

    /** A player of a singleplayer world, from the game's Mods screen; only its owner may add. */
    public data class WorldPlayer(
        val name: String,
        val id: UUID,
    ) : AddActor {
        override val label: String = "owner"
    }
}
