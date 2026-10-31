package io.github.calamech.rloaded.launcher

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelaunchTest {
    @Test
    fun `starts again a game that asks for it, up to the bound`() {
        assertTrue(Relaunch.wanted(75, 0))
        assertTrue(Relaunch.wanted(75, Relaunch.MAX_IN_A_ROW - 1))
        assertFalse(Relaunch.wanted(75, Relaunch.MAX_IN_A_ROW))
    }

    @Test
    fun `leaves a game that stopped or crashed`() {
        assertFalse(Relaunch.wanted(0, 0))
        assertFalse(Relaunch.wanted(-1, 0))
        assertFalse(Relaunch.wanted(1, 0))
    }
}
