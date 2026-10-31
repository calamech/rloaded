package io.github.calamech.rloaded.core.runtime

/** Exit codes the runtime mod gives the process that started the game. */
public object GameExit {
    /**
     * Start the game again: a server asks its bootstrap, a client asks its launcher, when only a
     * fresh process can clean what a failed add left behind.
     */
    public const val RESTART: Int = 75
}
