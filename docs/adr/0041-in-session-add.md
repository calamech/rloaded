# ADR 0041: `rloaded add` loads mods into the running server and installs them on connected players

- Status: Accepted
- Date: 2026-10-31

## Context

Until now an admitted mod was written to the lockfile and loaded at the next server start (ADR 0036). The pieces to add it while players are connected exist: `HotAdd` loads jars into a running game (ADR 0026), entities and block entities can be rebuilt from storage (ADR 0015, ADR 0016), and players can go through the configuration phase again, with Fabric syncing raw ids (ADR 0023, ADR 0024, ADR 0040). The join task already installs a lockfile's missing mods on a client (ADR 0030). The product owner decided on 2026-10-30 that a player who trusts a server installs its later mods without being asked, and sees a toast listing them.

Read in the Minecraft 26.3 sources on 2026-10-31: `ServerLevel.tick` runs `entityManager.tick()` even when ticks are frozen, so entities rebuilt from storage come back while the world is frozen. Item components are bound only at start and at each data pack reload (ADR 0039).

## Decision

- After the staging run, `AdmissionService` hands the jars the server runs to `LiveApply`, which the runtime mod implements as `LiveAdd`, and writes the lockfile only once they are loaded. `LiveAdd`, on the server thread: freezes ticks, saves everything, loads the jars with `HotAdd`, reloads the data packs, then rebuilds the entities and block entities of every level. A `HotAdd` refusal changes nothing and lets ticks run again; any later failure stops the server with exit code 75, which the bootstrap reads as a restart, and the lockfile does not list the mods.
- Once the lockfile records the mods, the join task sends the new manifest, and every connected player is sent to the configuration phase with `switchToConfig`, then `startConfiguration`. Clients install what they miss, as at join. Ticks run again once every player is back in the world or gone; a player whose install stalls is disconnected by the join timeout (ADR 0033). Clients only get mods for the client through the rollout, so a client-only add freezes ticks for the rollout too.
- The lockfile is written after the server loaded the mods and before players install them, rather than at the very end: players can only be sent a manifest the lockfile backs, and the server already runs the mods. If the lockfile cannot be written after the load, the server restarts so that what it runs and what it lists agree.
- One add at a time: an add that arrives while players are still installing the previous one is refused with that reason.
- A client that was already in the world on this connection shows a toast naming the new mods, with their Modrinth titles, or their project ids when Modrinth cannot be reached.
- The staging run starts with the lockfile's server mods too, not only those the server started with, since the server may have added some since (ADR 0039).
- The console says `Added <files>. Players install them now.`

Checked on 2026-10-31 with `addTest` (CI job `client-game-test`): a server in its own process and two clients, Alice and Bob, who trust it. The console adds content-basic, gives each player its block, then adds mixin-loaded-class. Both players stay on their connection, see the toast, receive the block, and stone answers the mixin's greeting; the server logs two completed rollouts and no log has an error line from the first add on. About 1 minute 40 seconds in local, staging included. With the data pack reload after `HotAdd` left out, the server could not give the new block and the check failed. A first run lost Bob to a slime during the second add: test servers now run in peaceful difficulty.

## Alternatives

- **Writing the lockfile last, after every player installed.** The manifest players are sent comes from the lockfile, and a player joining during the rollout would get the old one while the server already runs the new mods.
- **Disconnecting players and letting them join again.** Rejected in ADR 0023.
- **Asking players again for each add.** The product owner decided against it on 2026-10-30.

## Consequences

- Players see "left the game" and "joined the game" for everyone at each add, and the server's resource pack and code of conduct are offered again (#42). Issue #72 tracks the chat lines.
- A player whose game fails to install the mods is disconnected with the reason; the launcher does not yet start the game again and rejoin (#73).
- The restart after a failure past the point of no return is checked by review only; no test mod fails in the running server and not on the staging server (#74).
- Players are rebuilt by the reconfiguration; their position is kept.

## Enforcement

`AdmissionServiceTest` (live apply, refusals, lockfile written after the load), `StagingServerTest` (mods installed since start), `addTest` in the `client-game-test` CI job, and `AdminAddGameTest` in the `game-test` CI job. The restart path: review.

Amended 2026-10-31: `addTest` now adds content-basic, then mixin-field-init, whose mixin gives every entity a field set by its constructor. An armor stand is summoned before the adds; after them, all 15 entities of the server have the field (`/fieldinit`), and so do the clients' entities. With the entities not rebuilt, 11 of 15 lacked it and the check failed.

Amended 2026-10-31: `addTest` now adds content-basic, mixin-field-init, then keybind-and-tab to two connected players, with no crash and no error line (ADR 0042).
