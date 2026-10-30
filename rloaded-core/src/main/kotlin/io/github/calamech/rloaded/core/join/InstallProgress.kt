package io.github.calamech.rloaded.core.join

import io.github.calamech.rloaded.protocol.InstallStage
import io.github.calamech.rloaded.protocol.ModEntry
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a client's install of [entries] is, for the screen the player watches. Downloads report from
 * their own threads; the screen reads [snapshot] on the game thread.
 */
public class InstallProgress(
    private val entries: List<ModEntry>,
) {
    private val downloaded = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var stage = InstallStage.DOWNLOAD

    /** The jar of [entry] is in the store. */
    public fun downloaded(entry: ModEntry) {
        downloaded += entry.projectId
    }

    /** The install moved on to [next]. */
    public fun stage(next: InstallStage) {
        stage = next
    }

    /** What the screen shows now. */
    public fun snapshot(): Snapshot {
        val current = stage
        val done = entries.filter { it.projectId in downloaded }
        val states =
            entries.map { entry ->
                when {
                    current == InstallStage.RELOAD -> ModState.LOADED
                    entry.projectId in downloaded -> ModState.DOWNLOADED
                    else -> ModState.WAITING
                }
            }
        return Snapshot(current, done.size, entries.size, done.sumOf { it.size }, entries.sumOf { it.size }, states)
    }

    /** One mod's state, in [entries] order. */
    public enum class ModState {
        /** Not downloaded yet. */
        WAITING,

        /** In the store, not loaded yet. */
        DOWNLOADED,

        /** Loaded in the game. */
        LOADED,
    }

    /** The install at one moment. */
    public data class Snapshot(
        val stage: InstallStage,
        val downloadedMods: Int,
        val totalMods: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val states: List<ModState>,
    ) {
        /** How far the install is, from 0 to 1: downloaded bytes while downloading, all done after. */
        val fraction: Double
            get() =
                when {
                    stage != InstallStage.DOWNLOAD && stage != InstallStage.TRUST -> 1.0
                    totalBytes == 0L -> 0.0
                    else -> downloadedBytes.toDouble() / totalBytes
                }
    }
}
