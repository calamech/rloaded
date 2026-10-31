# ADR 0044: An add does not announce players leaving and joining

- Status: Accepted
- Date: 2026-10-31

## Context

An add sends every connected player through the configuration phase (ADR 0041). Read in the Minecraft 26.3 sources on 2026-10-31: `ServerGamePacketListenerImpl.switchToConfig` calls `removePlayerFromWorld`, which broadcasts "<player> left the game", and the player's return goes through `PlayerList.placeNewPlayer`, which broadcasts "<player> joined the game". Measured with `soakTest` on 2026-10-31: with the messages left as the game sends them, 20 adds made the server announce 21 joins and 21 leaves for each of the two players, who never left.

## Decision

- The runtime mod remembers each player sent to the configuration phase with `switchToConfig` (`Reconfigurations`). Two mixins, with MixinExtras' `@WrapWithCondition` (shipped with Fabric Loader), skip the leave message of `removePlayerFromWorld` and the join message of `placeNewPlayer` for such a player. Its return to the world clears the mark.
- A player who disconnects during that configuration did leave: the server announces it then, with the game's own message. A player who disconnects before its configuration starts loses the mark and is announced by the game as usual.
- Every other join and leave is announced as before.

Checked on 2026-10-31 with `addTest` and `soakTest` (CI job `client-game-test`), which now require each player's join and leave to be announced exactly once: three adds and twenty adds, one join and one leave per player. With the messages not skipped, `soakTest` counted 21 of each per player and failed. `recoveryTest` checks that a player whose install fails during an add, and who is disconnected, is announced as leaving once; without that announcement it counted none and failed.

## Alternatives

- **Filtering the messages on the clients.** The server would still send them and write them to its log, and every client would have to tell an add from a real join.
- **Hiding all join and leave messages while an add runs.** A player who really joins or leaves during an add would not be announced.

## Consequences

- A mod that hooks the same two calls with a `@Redirect` would conflict with these mixins and fail at start; `@WrapWithCondition` composes with other MixinExtras injectors.
- The player list (tab) still drops and adds the players during an add; only the chat stays quiet.

## Enforcement

`addTest`, `soakTest` and `recoveryTest` in the `client-game-test` CI job count the announcements in the server log.
