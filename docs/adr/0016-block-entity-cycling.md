# ADR 0016: Block entities are rebuilt from their saved data after a late mixin

- Status: Accepted
- Date: 2026-10-30

## Context

Entities are rebuilt after a late mixin so that they run its field initializers (ADR 0015). Block entities, such as chests, furnaces and signs, have the same problem, but they live in the chunk itself, not in the entity storage, so entity cycling leaves them as they are.

Read in the Minecraft 26.3 server, decompiled with Vineflower 1.12.0 on 2026-10-30:

- `BlockEntity.saveWithFullMetadata` writes a block entity's type, position and data. `BlockEntity.loadStatic` creates a new block entity from that data; it logs the error and returns null when the data cannot be read.
- `LevelChunk.removeBlockEntity` unregisters a block entity's ticker and game event listener and marks it removed. `LevelChunk.addAndRegisterBlockEntity` puts a block entity in the chunk and registers its ticker and listener, as the chunk does for each block entity after it loads.
- `ServerChunkCache.getChunkNow` returns a chunk that is fully loaded, on the server thread. The chunks a level holds are in the private `ChunkMap.visibleChunkMap`.

## Decision

`BlockEntityCycling.cycle` runs on the server thread, for each level, after late mixins are applied. For every fully loaded chunk, it saves each block entity, reads it back with `loadStatic`, removes the old one and adds the new one. A block entity that cannot be read back keeps its old object, and its position is reported in the result, so that a failure is never silent. The private chunk map is reached through a third accessor mixin, `ChunkMapAccess`, in the runtime mod's required configuration.

Checked on 2026-10-30 with `BlockEntityCyclingGameTest` on the production stack (JetBrains Runtime 25.0.4.1 build 508.40, the agent, Fabric Loader 0.20.1, Fabric API 0.165.0, Minecraft 26.3 game test server):

- A chest holding three diamonds gets a required late mixin that adds a field with an initializer to `ChestBlockEntity`. The existing chest reads the field as null. After the cycle, the chest at that position is a new object that reads the initialized value and still holds the three diamonds, and the old one is marked removed.
- A test block entity that throws when it is read back stays in place, is not marked removed, and its position is in the result.

Both tests failed when the cycle did not replace block entities and did not report failures. They passed in 20 server runs out of 20 in a row (217 s in total), each on a fresh server.

## Alternatives

- **Reloading whole chunks.** Chunks around players hold tickets and do not unload while the players are there (ADR 0015).
- **Dropping a block entity that cannot be read back.** Its data would be lost; keeping the old object loses only the new fields.
- **Leaving block entities as they are**, and refusing mods whose mixins add initialized fields to block entity classes. Kept as the fallback if cycling breaks on a later Minecraft version.

## Consequences

- Code that keeps a reference to a block entity holds the old, removed object after a cycle, as it would after the block entity's chunk unloads. The game test framework is one such holder: it keeps the test instance block entity, and the tests still run.
- A player with a block entity's menu open would keep using the old object. Running the cycle while players are in the configuration phase avoids it: leaving play goes through `Player.remove`, which closes the open menu.
- Block entities that are not saved with their data lose what they do not save, as on a chunk unload.
- One more private member of Minecraft 26.3: `ChunkMap.visibleChunkMap`. A rename stops the game when `ChunkMap` loads, as the configuration is required.

## Enforcement

`BlockEntityCyclingGameTest`, in the runtime mod's `gametest` source set, run by the `game-test` CI job.
