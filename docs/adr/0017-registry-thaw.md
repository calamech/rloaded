# ADR 0017: Built-in registries are opened and frozen again to add entries

- Status: Accepted
- Date: 2026-10-30

## Context

A mod added while the game runs registers blocks, items and other entries in the built-in registries. Those registries freeze once startup is done, and refuse new entries afterwards.

Read in Minecraft 26.3, decompiled with Vineflower 1.12.0 on 2026-10-30, and in Fabric API's `fabric-registry-sync-v0` 8.0.4, which Fabric API 0.165.0 ships:

- `MappedRegistry.register` refuses new entries once the private field `frozen` is set. A block or an item creates its registry holder in its constructor, through `createIntrusiveHolder`, which needs the private map `unregisteredIntrusiveHolders`; `freeze` sets that map to null.
- `MappedRegistry.freeze` cannot run twice: after the first call the registry's tags are bound, and a second call throws `Tags already present before freezing`.
- `register` appends: the new entry takes the next raw id, and existing ids never move.
- Fabric API listens to new block registrations. Its `StateIdTracker` appends the new block's states to `Block.BLOCK_STATE_REGISTRY` when the block takes the next raw id, and `BlocksMixin` initializes their shape caches.

## Decision

`RegistryThaw.thaw` opens a frozen registry: it gives it a new map of intrusive holders and clears `frozen`. `RegistryThaw.freeze` closes it again by repeating only what `freeze` does for new entries: it binds every value to its holder, drops the intrusive holder map, rebuilds the component lookup, gives every holder its tags (none for a new entry), and sets `frozen`. If a value created a holder but was never registered, `freeze` refuses with an `IllegalStateException` naming it and leaves the registry open, as `MappedRegistry.freeze` would refuse at startup. The private members are reached through two accessor mixins in the runtime mod's required configuration, `MappedRegistryAccess` and `HolderReferenceAccess`.

Checked on 2026-10-30 with `RegistryThawGameTest` on the production stack (JetBrains Runtime 25.0.4.1 build 508.40, the agent, Fabric Loader 0.20.1, Fabric API 0.165.0, Minecraft 26.3 game test server):

- A block with four states, registered after startup, takes the next raw id; stone keeps its id; the block's four states take the four ids after the last existing state; its holder has no tag; the registry refuses new blocks once frozen again; and the block can be placed in the world.
- A block created but not registered makes `freeze` refuse with the expected message, and registering it afterwards lets `freeze` succeed.

The tests failed when tags were not given to holders, and when the unregistered block was not checked. They passed in 20 server runs out of 20 in a row (218 s in total), each on a fresh server.

## Alternatives

- **Calling `MappedRegistry.freeze` again** after clearing its tags. It would also rebuild every tag set, and fail on the tags check unless they are unbound first, which breaks every holder's tags until they are bound again.
- **Registering through a registry Rloaded creates** instead of the built-in one. Mods register into `BuiltInRegistries` directly, so their entries have to land there.

## Consequences

- A new entry has no tags until the server reloads its data packs, which binds tags again for every holder. Hot-adding has to reload them after registration.
- Other threads read the registries and `Block.BLOCK_STATE_REGISTRY` while the server runs, such as chunk saving. Opening a registry is safe only while nothing else uses it: the add pipeline has to pause those threads, or run when they are idle.
- Raw ids of new entries are known only on the server until clients learn them: issue #14.
- The runtime mod depends on more private members of Minecraft 26.3: `MappedRegistry.frozen`, `unregisteredIntrusiveHolders`, `byValue`, `byId`, `componentLookup`, `refreshTagsInHolders`, and `Holder.Reference.bindValue`. A rename stops the game when the class loads, as the configuration is required.

## Enforcement

`RegistryThawGameTest`, in the runtime mod's `gametest` source set, run by the `game-test` CI job.
