# ADR 0045: An add does not ask players again for the code of conduct or the resource pack

- Status: Accepted
- Date: 2026-10-31

## Context

An add sends every connected player through the configuration phase with `startConfiguration` (ADR 0024, ADR 0041). Read in the Minecraft 26.3 sources on 2026-10-31: `startConfiguration` queues the server's optional tasks again (`addOptionalTasks`): the code of conduct, when `enable-code-of-conduct` is on, and the server resource pack, when `resource-pack` is set. The client shows the code of conduct again unless the player ticked "don't show again" the first time. It shows the resource pack prompt again unless the player accepted it, in which case it pushes the pack again. The client keeps its server packs through the configuration phase; it drops them when it disconnects.

## Decision

- A mixin on `ServerConfigurationPacketListenerImpl.addOptionalTasks` skips both tasks for a player sent to the configuration phase by an add (`Reconfigurations`, ADR 0044). The player accepted the code of conduct when it joined, or it would have been disconnected, and its game still has the resource pack it chose.
- A player who joins, including one that the launcher starts again after a failed install (ADR 0043), goes through both tasks as usual.

Checked on 2026-10-31 with `addTest` (CI job `client-game-test`), whose server now has a code of conduct and an optional resource pack. Both players accept them when they join; the check fails as soon as either question shows during one of the three adds. With the tasks not skipped, the code of conduct showed again at the first add and the check failed.

## Alternatives

- **Accepting the code of conduct on the client without showing it.** The server would still ask and push the pack again, and the client would answer for the player.
- **Keeping the tasks and relying on "don't show again".** It is off by default, so every player would be asked at every add.

## Consequences

- A server that changes its code of conduct or its resource pack while running cannot do so in 26.3: both are read when the server starts. A restart asks every player again, as a join does.

## Enforcement

`addTest` in the `client-game-test` CI job.
