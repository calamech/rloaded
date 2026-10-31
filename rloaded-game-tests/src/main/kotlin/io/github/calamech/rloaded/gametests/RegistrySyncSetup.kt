package io.github.calamech.rloaded.gametests

import java.nio.file.Path

/** The order the server registers the added blocks in. */
internal const val SERVER_ORDER = "sync_a,sync_b"

/** One order per client; the second differs from the server's, so its raw ids do before the sync. */
internal val CLIENT_ORDERS = listOf("sync_a,sync_b", "sync_b,sync_a")

/** The blocks every process adds while the players are connected. */
internal val ADDED_BLOCKS = listOf("rloaded-gametest:sync_a", "rloaded-gametest:sync_b")

/** The address the server listens on and the clients join. */
internal const val LOOPBACK = "127.0.0.1"

// A first launch shows an accessibility screen, which would hold the client before it joins.
internal const val CLIENT_OPTIONS = "onboardAccessibility:false\npauseOnLostFocus:false\nrenderDistance:2\n"

/** The properties that turn the check on in the game test mod, on the server and on clients. */
internal fun checkProperties(
    output: Path,
    order: String,
): Map<String, String> = mapOf("rloaded.registry-sync.output" to output.toString(), "rloaded.registry-sync.order" to order)

/**
 * A small offline server on the loopback interface, which clients without an account can join; the
 * white list is on by default in 26.3.
 */
internal fun serverProperties(port: Int): String =
    listOf(
        "server-port=$port",
        "server-ip=$LOOPBACK",
        "online-mode=false",
        "white-list=false",
        "enforce-secure-profile=false",
        "level-type=minecraft\\:flat",
        "generate-structures=false",
        // Players of a check stand still: on a flat world, slimes would kill them.
        "difficulty=peaceful",
        "view-distance=2",
        "simulation-distance=2",
        "spawn-protection=0",
    ).joinToString("\n", postfix = "\n")
