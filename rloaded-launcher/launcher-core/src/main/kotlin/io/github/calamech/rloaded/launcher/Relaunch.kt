package io.github.calamech.rloaded.launcher

import io.github.calamech.rloaded.core.runtime.GameExit

/**
 * When the launcher starts the game again on its own: the game exited with [GameExit.RESTART]
 * because an add failed while it ran, and a fresh game joins the same server again. A fresh game never
 * asks for it at its first join, so this does not loop; the bound is a second guard, as the
 * server bootstrap has.
 */
public object Relaunch {
    /** Relaunches in a row, after which the launcher shows the exit instead. */
    public const val MAX_IN_A_ROW: Int = 10

    /** Whether a game that exited with [exitCode] is started again, after [relaunches] in a row. */
    public fun wanted(
        exitCode: Int,
        relaunches: Int,
    ): Boolean = exitCode == GameExit.RESTART && relaunches < MAX_IN_A_ROW
}
