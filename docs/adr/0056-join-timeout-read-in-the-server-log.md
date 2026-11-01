# ADR 0056: The join check reads a timeout in the server's log

- Status: Accepted
- Date: 2026-11-01

## Context

A player whose install stops making progress is disconnected with "Your game stopped answering while it installed this server's mods." (ADR 0033). The join check expected that sentence on the player's disconnection screen. Twice, on 2026-10-31 and 2026-11-01, CI showed "Connection Lost. Disconnected" instead, while the server had sent the right reason (#86).

Read in the Minecraft 26.3 sources on 2026-11-01:

- `ClientCommonPacketListenerImpl.handleDisconnect` runs on the network thread and calls `Connection.disconnect(details)`.
- `Connection.disconnect` closes the channel, waits for it to close, and only then stores `disconnectionDetails`.
- On the client thread, `Connection.tick` calls `handleDisconnection` as soon as the channel is closed. With no details stored yet, it reports `multiplayer.disconnect.generic`, "Disconnected".

So when the client thread ticks between the two steps of the network thread, the player sees the generic reason. The server side has the same order, so its "lost connection" line can lose the reason too.

## Decision

`JoinServer` logs its own line when a join runs out of time, naming the player, before it disconnects them. The join check reads that line in the server's log, and only checks that the stalled player ended on the disconnection screen, whatever the reason shown.

The join test mod has a mixin, `LateDisconnectReason`, that holds the network thread for 500 ms between closing the connection and storing the reason, for the stalled player only. The player then sees "Disconnected" at every run. With it, the former check failed at once on 2026-11-01 ("stalled player: disconnected: Connection Lost. Disconnected"); the new one passed in 3 runs out of 3, the stalled player still reading "Disconnected". Without the server's line, it fails.

## Alternatives

- **Storing the reason before the channel closes, with a mixin on `Connection`.** It changes how every connection of the game ends, client and server, to fix a rare message from the base game. Kept for later if players report it.
- **Accepting either reason on the client.** The check would still pass when the server never timed the join out and the connection dropped for another reason.

## Consequences

- A player kicked for a timeout can still read "Disconnected", rarely. The admin always has the reason in the server's log.
- The check depends on the wording of the log line; changing it means changing `JoinMain` too.

## Enforcement

`joinTest` (CI job `client-game-test`), with `LateDisconnectReason` in the join test mod.
