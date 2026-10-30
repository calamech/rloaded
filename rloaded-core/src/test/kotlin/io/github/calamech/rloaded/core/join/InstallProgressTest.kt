package io.github.calamech.rloaded.core.join

import io.github.calamech.rloaded.core.join.InstallProgress.ModState
import io.github.calamech.rloaded.protocol.Checksum
import io.github.calamech.rloaded.protocol.HashAlgorithm
import io.github.calamech.rloaded.protocol.InstallStage
import io.github.calamech.rloaded.protocol.ModEntry
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.Side
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class InstallProgressTest {
    private val small = entry("small", 1_000)
    private val large = entry("large", 3_000)
    private val progress = InstallProgress(listOf(small, large))

    @Test
    fun `starts with every mod waiting and nothing downloaded`() {
        assertEquals(
            InstallProgress.Snapshot(InstallStage.DOWNLOAD, 0, 2, 0, 4_000, listOf(ModState.WAITING, ModState.WAITING)),
            progress.snapshot(),
        )
        assertEquals(0.0, progress.snapshot().fraction)
    }

    @Test
    fun `counts downloaded mods and bytes, in entry order`() {
        progress.downloaded(large)

        val snapshot = progress.snapshot()

        assertEquals(listOf(ModState.WAITING, ModState.DOWNLOADED), snapshot.states)
        assertEquals(1 to 3_000L, snapshot.downloadedMods to snapshot.downloadedBytes)
        assertEquals(0.75, snapshot.fraction)
    }

    @Test
    fun `is complete once loading starts, and every mod is loaded when resources reload`() {
        progress.downloaded(small)
        progress.downloaded(large)
        progress.stage(InstallStage.LOAD)
        assertEquals(1.0, progress.snapshot().fraction)
        assertEquals(listOf(ModState.DOWNLOADED, ModState.DOWNLOADED), progress.snapshot().states)

        progress.stage(InstallStage.RELOAD)

        assertEquals(listOf(ModState.LOADED, ModState.LOADED), progress.snapshot().states)
    }

    @Test
    fun `an install with nothing to download is at zero until it moves on`() {
        val empty = InstallProgress(emptyList())

        assertEquals(0.0, empty.snapshot().fraction)
    }

    private fun entry(
        id: String,
        size: Long,
    ): ModEntry {
        val sha = (Checksum.parse(HashAlgorithm.SHA512, "a".repeat(128)) as Outcome.Ok).value
        return ModEntry(id, "v$id", "$id.jar", sha, size, Side.BOTH)
    }
}
