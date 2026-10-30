package io.github.calamech.rloaded.core.install

import io.github.calamech.rloaded.protocol.ModEntry
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.Side

/** A mod the running game already loaded in another version than the server wants. */
public data class VersionConflict(
    val projectId: String,
    val loadedVersionId: String,
    val wantedVersionId: String,
)

/** Choosing which entries of a manifest a client installs. */
public object InstallPlan {
    /**
     * The entries of a manifest this client installs, in manifest order: not server-only mods, not
     * client-only mods the player left out ([optedOut], by project id), and not mods this game
     * already loaded ([loaded], version id by project id). A mod loaded in another version cannot be
     * replaced in a running game: every such mod is reported.
     */
    public fun entriesToInstall(
        entries: List<ModEntry>,
        optedOut: Set<String>,
        loaded: Map<String, String>,
    ): Outcome<List<ModEntry>, List<VersionConflict>> {
        val wanted = entries.filter { it.side != Side.SERVER && !(it.side == Side.CLIENT && it.projectId in optedOut) }
        val conflicts =
            wanted.mapNotNull { entry ->
                loaded[entry.projectId]?.takeIf { it != entry.versionId }?.let { VersionConflict(entry.projectId, it, entry.versionId) }
            }
        return if (conflicts.isEmpty()) Outcome.Ok(wanted.filter { it.projectId !in loaded }) else Outcome.Err(conflicts)
    }
}
