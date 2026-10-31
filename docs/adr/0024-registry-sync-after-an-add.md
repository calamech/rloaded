# ADR 0024: Players restart the configuration phase so Fabric syncs raw ids again

- Status: Accepted
- Date: 2026-10-30

## Context

Blocks, items and other entries travel between server and client as raw ids, plain numbers. A mod added while players are connected registers new entries on the server and on each client, in an order each side picks, so the same block can get different raw ids on each side. Fabric API syncs raw ids when a player joins: the server sends its id maps and the client remaps its registries to match. After an add, that sync has to run again during the reconfiguration that installs the mod (ADR 0023).

Read in Fabric API 0.165.0 (`fabric-registry-sync-v0` 8.0.4, `fabric-networking-api-v1` 6.4.0) and Minecraft 26.3, decompiled with Vineflower 1.12.0 on 2026-10-30:

- `RegistrySyncManager.configureClient` queues the sync task, `fabric:registry/sync`. It listens to `ServerConfigurationConnectionEvents.BEFORE_CONFIGURE`, and syncs the registries marked as modded; a registry gets that mark when an entry is registered after startup.
- Fabric fires its configuration events from a mixin at the head of `ServerConfigurationPacketListenerImpl.startConfiguration`. The game calls that method once, at login.
- After `ServerGamePacketListenerImpl.switchToConfig`, the game creates a new configuration listener and waits. The debug `unconfig` command, and the tests of ADR 0022 and 0023, then call `returnToWorld`, which queues the spawn and join tasks only: Fabric's events do not fire, and the sync does not run.
- `startConfiguration` on the new listener runs the same steps as a login: Fabric registers channels, fires its events, runs its tasks, then the game sends its brand, enabled features and data pack registries, queues its optional tasks and calls `returnToWorld` itself.
- On the client, `ClientRegistrySyncHandler` applies the maps on the game thread. Nothing refuses a second sync on the same connection. The client has no level by then: `handleConfigurationStart` drops it.
- Fabric's `StateIdTracker` rebuilds `Block.BLOCK_STATE_REGISTRY` in raw id order after a remap, so block state ids follow block ids.

## Decision

To install mods during a reconfiguration, the server sends the player to the configuration phase with `switchToConfig`, then calls `startConfiguration` on the player's new configuration listener instead of `returnToWorld`. Rloaded relies on Fabric's registry sync for raw ids and does not sync them itself.

Checked with `:rloaded-game-tests:registrySyncTest`. It installs the pinned server and client with the bootstrap's and the launcher's code, starts the server in its own process (offline, on the loopback interface, `eula.txt` written: the project owner accepted the EULA for these test directories on 2026-10-30), and two clients that join it with quick play. Once in the world, each client registers two blocks of four states, `rloaded-gametest:sync_a` and `sync_b`, the first client in the server's order and the second in the reverse order. When both have done so, the server registers the two blocks and sends both players through the configuration phase again. Each process writes the raw id of every block and block state, the clients once before the sync and once back in the world; the harness fails unless the second client's ids differed from the server's before the sync, and both clients match the server on every id after it. A client that gets disconnected quits, and the harness stops everything after 15 minutes: a refused join, as with the white list a 26.3 server turns on by default, failed the check in 26 s.

Measured on 2026-10-30 on macOS with JetBrains Runtime 25.0.4.1 build 508.40, the agent, Fabric Loader 0.20.1, Fabric API 0.165.0 and Minecraft 26.3:

- With `returnToWorld`, the check failed: the second client still had `sync_a` and `sync_b` swapped, 1288 and 1287 against 1287 and 1288 on the server, and 10 ids differed in all.
- With `startConfiguration`, both clients matched the server on all 37,021 block and block state ids, with no error in the three logs. The check passed 10 runs out of 10 in a row, 28 s each with Gradle, client and server start included.

## Alternatives

- **`returnToWorld` alone**, as the debug command does: measured above, the sync does not run.
- **A raw id sync of Rloaded's own** in its configuration task. Fabric's sync already remaps every modded registry and the block states with it; a second implementation would have to match it registry by registry.
- **Queueing Fabric's sync task directly.** `RegistrySyncManager` is in an `impl` package, not Fabric's API, and the channels of the new mods would still not be registered with the client.

## Consequences

- Every add replays the whole configuration phase: the game sends its data pack registries again and queues its optional tasks, the code of conduct and the server resource pack. Whether players are asked about them again at each add is not checked yet: issue #42.
- The stability test of ADR 0023 still reconfigures with `returnToWorld`; the path chosen here has not been through 100 rounds yet: issue #43.
- Fabric restores each client registry to the copy it made at the first remap when the client disconnects (`MappedRegistryMixin.unmap`), so entries added during the session leave the registries on disconnect. What a client keeps of an add after it leaves the server has to be checked before a player can rejoin or switch servers: issue #44.
- The check covers blocks and block states. Items, entity types and other registries follow the same sync and are not compared.
- The job runs a server and two clients at once, with 2 GiB of heap each.

## Enforcement

`:rloaded-game-tests:registrySyncTest`, run by the `client-game-test` CI job. `RegistryIdsTest` checks the comparison, including a run where no client diverged, which proves nothing and fails.

Amended 2026-10-31: a game that loaded mods while in a world now restarts before it joins a server again, since Fabric's restored copy can lack what those mods registered (ADR 0047).
