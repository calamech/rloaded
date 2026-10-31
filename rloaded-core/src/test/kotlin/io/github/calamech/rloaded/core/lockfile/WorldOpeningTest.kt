package io.github.calamech.rloaded.core.lockfile

import io.github.calamech.rloaded.protocol.Checksum
import io.github.calamech.rloaded.protocol.HashAlgorithm
import io.github.calamech.rloaded.protocol.ModEntry
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.Side
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class WorldOpeningTest {
    private val basic = entry("cbProject", "v1")
    private val field = entry("mfiProject", "v1")

    @Test
    fun `opens a world whose mods the game has, and only those`() {
        assertEquals(WorldOpening.Open, WorldOpening.of(listOf(basic, field), mapOf("cbProject" to "v1", "mfiProject" to "v1")))
        assertEquals(WorldOpening.Open, WorldOpening.of(emptyList(), emptyMap()))
    }

    @Test
    fun `installs the mods the game misses, leaving out those for dedicated servers`() {
        val serverOnly = entry("srvProject", "v1", Side.SERVER)

        assertEquals(WorldOpening.Install(listOf(field)), WorldOpening.of(listOf(basic, field, serverOnly), mapOf("cbProject" to "v1")))
    }

    @Test
    fun `starts again when the game has mods the world does not list, or other versions`() {
        assertEquals(WorldOpening.Restart(listOf("cbProject")), WorldOpening.of(emptyList(), mapOf("cbProject" to "v1")))
        assertEquals(
            WorldOpening.Restart(listOf("cbProject", "xProject")),
            WorldOpening.of(listOf(entry("cbProject", "v2"), field), mapOf("xProject" to "v1", "cbProject" to "v1")),
        )
    }

    private fun entry(
        projectId: String,
        versionId: String,
        side: Side = Side.BOTH,
    ): ModEntry {
        val sha = (Checksum.parse(HashAlgorithm.SHA512, "a".repeat(128)) as Outcome.Ok).value
        return ModEntry(projectId, versionId, "$projectId.jar", sha, 10, side)
    }
}
