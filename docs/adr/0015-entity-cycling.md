# ADR 0015: Entities are rebuilt from storage after a late mixin

- Status: Accepted
- Date: 2026-10-30

## Context

A field that a late mixin adds to a loaded class holds its default value on objects that already exist, because constructors and field initializers do not run again (ADR 0012). Entities are the objects that matter most: a mod that adds a field to an entity class expects every entity to have it set. The question was whether the server can rebuild the entities it has in memory, so that they become new objects of the new classes, without restarting.

Read in the Minecraft 26.3 server, decompiled with Vineflower 1.12.0 on 2026-10-30:

- `ServerLevel` keeps its entities in a `PersistentEntitySectionManager`, by chunk.
- When a chunk's entities unload, `PersistentEntitySectionManager.processChunkUnload` saves its root entities, passengers included, to the entity storage, then marks each one removed with `Entity.RemovalReason.UNLOADED_TO_CHUNK`. When they load again, `requestChunkLoad` reads them from storage, and the next tick adds them to the level as new objects.
- A chunk where a player stands cannot unload, but these two steps do not depend on the chunk being unloaded.
- Players are not saved with chunks. When a player goes back to the configuration phase, `ServerGamePacketListenerImpl.switchToConfig` removes them from the player list, and `PrepareSpawnTask.spawnPlayer` creates a new `ServerPlayer` from the saved player data when configuration ends.

## Decision

After late mixins are applied, `EntityCycling.cycle` runs on the server thread, for each level. For every chunk whose entities are loaded, it calls `processChunkUnload` and then `requestChunkLoad`. The chunks themselves stay loaded. `EntityCycling.isComplete` tells when every cycled chunk has its entities back. The runtime mod reaches these private members through two accessor mixins of its own, `ServerLevelAccess` and `PersistentEntitySectionManagerAccess`, in the required configuration `rloaded.mixins.json`.

Players are left to reconfiguration, which rebuilds them.

Checked on 2026-10-30 with `EntityCyclingGameTest` on the production stack (JetBrains Runtime 25.0.4.1 build 508.40, the agent, Fabric Loader 0.20.1, Fabric API 0.165.0, Minecraft 26.3 game test server). The test spawns an armor stand, and a second one riding a minecart, then applies a required late mixin that adds a field with an initializer to `ArmorStand`. The existing armor stand reads the field as null. After cycling, both armor stands are new objects, the passenger still rides its minecart, and every armor stand in the level reads the initialized value. The test failed when the cycle did nothing, and when it did not read the entities back. It passed in 20 server runs out of 20 in a row (212 s in total), each on a fresh server.

## Alternatives

- **Unloading and reloading whole chunks.** The chunks around players hold tickets and do not unload while the players are there, and reloading the terrain is not needed for entities.
- **Rebuilding each entity by hand**, saving it and adding a copy. It duplicates what the entity manager already does on unload, including passengers, and would have to keep up with it.
- **Refusing mods whose mixins add initialized fields to entity classes.** Kept as the fallback if cycling breaks on a later Minecraft version.

## Consequences

- Entities that a mod expects to be set up by its own constructor code are rebuilt; their saved data round-trips through storage, as on a normal chunk unload. An entity that does not save some of its state loses it, as it would when its chunk unloads.
- Clients see the cycled entities disappear and come back, unless the cycle runs while they are in the configuration phase.
- Block entities stay the same objects: issue #25.
- The runtime mod now depends on private members of Minecraft 26.3 (`ServerLevel.entityManager`, `PersistentEntitySectionManager.chunkLoadStatuses`, `processChunkUnload`, `requestChunkLoad`). A renamed member makes the game fail when these classes load, as the configuration is required: with the field name changed on purpose, the game test server stopped on `InvalidAccessorException` when it created its first level.

## Enforcement

`EntityCyclingGameTest`, in the runtime mod's `gametest` source set, run by the `game-test` CI job.
