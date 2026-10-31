package io.github.calamech.rloaded.core.lockfile

import io.github.calamech.rloaded.protocol.ModEntry
import io.github.calamech.rloaded.protocol.Side

/** What a game does before it opens a singleplayer world, given the mods it loaded and the world's lockfile. */
public sealed interface WorldOpening {
    /** The game has every mod of the world, and no other: it opens the world. */
    public data object Open : WorldOpening

    /** The game installs and loads [missing] first, then opens the world. */
    public data class Install(
        val missing: List<ModEntry>,
    ) : WorldOpening

    /**
     * The game loaded mods the world does not list, or other versions of them: [extra] by project
     * id. Their content would end up saved in the world, which then could not load without them;
     * the game starts again and opens the world fresh.
     */
    public data class Restart(
        val extra: List<String>,
    ) : WorldOpening

    /** Deciding. */
    public companion object {
        /**
         * What a game that loaded [loaded] (version ids by project id) does before it opens a world
         * whose lockfile lists [worldMods]. Mods for dedicated servers only are left out: a game
         * never loads them.
         */
        public fun of(
            worldMods: List<ModEntry>,
            loaded: Map<String, String>,
        ): WorldOpening {
            val wanted = worldMods.filter { it.side != Side.SERVER }
            val versions = wanted.associate { it.projectId to it.versionId }
            val extra = loaded.filter { (project, version) -> versions[project] != version }.keys.sorted()
            val missing = wanted.filter { it.projectId !in loaded }
            return when {
                extra.isNotEmpty() -> Restart(extra)
                missing.isNotEmpty() -> Install(missing)
                else -> Open
            }
        }
    }
}
