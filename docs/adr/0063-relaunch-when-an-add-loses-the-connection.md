# ADR 0063: A game that loses the connection during an add before it answered starts again

- Status: Accepted
- Date: 2026-11-02

## Context

During an add, the server sends each player back to the configuration phase, and the player's game downloads and loads the new mods there (ADR 0041). A game that takes longer than `rollout.clientTimeoutSeconds` between two progress messages is disconnected by the server (ADR 0033). A game that fails the install already stops and asks the launcher to start it again (ADR 0043), but a game disconnected by the timeout did not: it stayed on the disconnection screen, and the player had to join again by hand (#80).

The game cannot tell the timeout apart from other ends of the connection. The server's reason can reach the game after the channel closed, and then the game shows the generic "Disconnected" (ADR 0056).

## Decision

- From the server's hello until the game sends its result, the game restarts if the connection ends during an add, whatever the reason. Once the game sent its result, or the player chose "Leave", the end of the connection does not restart it. A failed install keeps the rule of ADR 0043.
- The restart is the one of ADR 0043: exit code 75 once the game thread ended, then the launcher starts the game again with the same server.
- A fresh game never restarts at its first join, so a game that times out again after the restart stays on the disconnection screen: this does not loop.

Checked on 2026-11-02 with `recoveryTest`: after the failed install of ADR 0043, the console adds content-basic, whose file reaches the player's game 22 seconds after it asked, with the server's timeout at 15 seconds. The server logs that the player stopped answering and disconnects the game, which exits with code 75. The check starts it again as the launcher does, the file comes at once, and the fresh game joins with the mod. With the change left out, the game stayed on the disconnection screen and the check failed. `joinTest` still requires the player disconnected at its first join to end with code 0.

## Alternatives

- **A disconnect reason the game can read.** The reason can be lost on the game's side (ADR 0056), and the game would need to trust any reason a server sends to decide to restart.
- **Restarting only when the server says the timeout passed, with a message before closing.** The server stops answering a game it considers stuck; a message sent just before the close can arrive after it, for the same reason as above.

## Consequences

- A player kicked by an operator, or whose network drops, while the game installs an add's mods starts again and joins the server again. A ban still refuses the new join.
- If the server stops during an add, the players' games restart and try to join a server that may not be back yet; they stay on the disconnection screen then.

## Enforcement

`recoveryTest` and `joinTest` in the `client-game-test` CI job.
