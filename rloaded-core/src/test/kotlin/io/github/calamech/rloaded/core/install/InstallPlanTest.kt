package io.github.calamech.rloaded.core.install

import io.github.calamech.rloaded.protocol.Checksum
import io.github.calamech.rloaded.protocol.HashAlgorithm
import io.github.calamech.rloaded.protocol.ModEntry
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.Side
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class InstallPlanTest {
    private val both = entry("bothMod", Side.BOTH)
    private val client = entry("clientMod", Side.CLIENT)
    private val server = entry("serverMod", Side.SERVER)
    private val manifest = listOf(both, client, server)

    @Test
    fun `installs mods both sides need and client mods, never server mods`() {
        assertEquals(Outcome.Ok(listOf(both, client)), InstallPlan.entriesToInstall(manifest, emptySet(), emptyMap()))
    }

    @Test
    fun `leaves out the client mods the player opted out of, but not mods both sides need`() {
        val plan = InstallPlan.entriesToInstall(manifest, setOf("clientMod", "bothMod"), emptyMap())

        assertEquals(Outcome.Ok(listOf(both)), plan)
    }

    @Test
    fun `skips mods already loaded in the same version`() {
        assertEquals(Outcome.Ok(listOf(client)), InstallPlan.entriesToInstall(manifest, emptySet(), mapOf("bothMod" to "v1")))
    }

    @Test
    fun `reports every mod loaded in another version`() {
        val plan = InstallPlan.entriesToInstall(manifest, emptySet(), mapOf("bothMod" to "v0", "clientMod" to "v0", "serverMod" to "v0"))

        assertEquals(
            Outcome.Err(listOf(VersionConflict("bothMod", "v0", "v1"), VersionConflict("clientMod", "v0", "v1"))),
            plan,
        )
    }

    private fun entry(
        projectId: String,
        side: Side,
    ): ModEntry {
        val sha = (Checksum.parse(HashAlgorithm.SHA512, "a".repeat(128)) as Outcome.Ok).value
        return ModEntry(projectId, "v1", "$projectId.jar", sha, 10, side)
    }
}
