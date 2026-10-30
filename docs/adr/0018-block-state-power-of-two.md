# ADR 0018: Adds that push the block state count past a power of two are refused

- Status: Accepted
- Date: 2026-10-30

## Context

Every block state has a global id. New blocks take the ids after the last existing state (ADR 0017), so an add can push the number of states past a power of two. The question was whether chunks stay correct when that happens.

Read in Minecraft 26.3, decompiled with Vineflower 1.12.0 on 2026-10-30:

- A chunk section keeps its states in a `PalettedContainer`. Up to 256 different states, it stores indexes into its own palette. Above that, it stores global state ids, with `Strategy.globalPaletteBitsInMemory` bits each, which is `Mth.ceillog2` of the state count when the `Strategy` was created.
- Each `Level` creates its `PalettedContainerFactory`, and so its `Strategy`, in its constructor. A server creates its levels at startup and keeps them; a client creates a new level when it comes back from the configuration phase (ADR 0013).
- `SimpleBitStorage.set` refuses a value wider than its bits. Its constructor from an array, used when a saved section is read, masks each value to its bits without a check.
- `PalettedContainer.write` sends the section's own bit count and its raw longs; `read` builds the storage with the reading level's bit count and reads that many longs.

## Decision

An add whose blocks would need more bits than the server's levels were created with is refused before anything is registered, with a message telling the admin to restart the server with the mod installed. The check belongs to the add pipeline: issue #29.

The spike behind this decision, checked on 2026-10-30 with `PaletteBoundaryGameTest` on the production stack (JetBrains Runtime 25.0.4.1 build 508.40, the agent, Fabric Loader 0.20.1, Fabric API 0.165.0, Minecraft 26.3 game test server). The game test server has 35,728 block states with Fabric API and the test mods, so 16 bits, and the next boundary is 65,536. The test builds sections of more than 256 states with the level's factory, as the server does, and reads them with a new factory, as a returning client does.

- After registering a block with four states, below the boundary: a section stores its state, a saved section reloads it, and a resent section reads back unchanged.
- After registering a block with 32,768 states, past the boundary: a section of the level refuses a new state, a saved section that holds one reloads as another state, and a resent section cannot be decoded, because the client expects 17-bit values and more longs than the server sent.

It passed in 10 server runs out of 10 in a row.

## Alternatives

- **Rebuilding the server's levels' strategies** and repacking every loaded section in global mode. The bit count is a final field read by every section of every level, and a section that is not loaded would still be read with the old strategy until it is. Possible later if refusals become common.
- **Refusing only the blocks of an add, not the whole add.** A mod whose blocks are missing does not work.

## Consequences

- With Fabric API, 26.3 starts around 35,700 states, so an add has to bring about 29,800 states to cross the next boundary. Large mod packs installed at startup can sit closer to a boundary; the admin then has to restart with the mod preinstalled.
- The refusal is decided before the add starts, so nothing is left half registered.

## Enforcement

`PaletteBoundaryGameTest`, in the runtime mod's `gametest` source set, run by the `game-test` CI job, shows the failure past the boundary and the safe case below it. The refusal itself is tested with the add pipeline (#29).
