# ADR 0026: HotAdd registers mod jars with Fabric Loader and runs them with the registries open

- Status: Accepted
- Date: 2026-10-30
- Amended: 2026-10-30, client side and resource reload (see the end of Decision)

## Context

Rloaded loads mods into a game that already started: on the client while it joins a server, before the world exists, and on the server when an admin adds a mod. Fabric Loader discovers mods once, at startup. Earlier decisions cover two parts of what a mod needs later: Mixin configurations applied to loaded classes (ADR 0012, 0014) and registries opened again (ADR 0017). The mod itself also has to be known to the loader, on the class path, and its entrypoints have to run.

Read in Fabric Loader 0.20.1, decompiled with Vineflower 1.12.0 on 2026-10-30:

- `FabricLoaderImpl.setup` turns each discovered jar into a `ModCandidateImpl` with the package-private `ModCandidateImpl.createPlain`, then `addMod` wraps it in a `ModContainerImpl` and adds it to the protected fields `mods` and `modMap`, under its id and every id it provides. `getAllMods` returns a view of `mods`.
- `finishModLoading` adds each mod's paths to the class path with `FabricLauncherBase.getLauncher().addToClassPath`, then `setupMods` registers every entrypoint with the public `EntrypointStorage.add`, using the private `adapterMap` of language adapters.
- `ModMetadataParser.parseMetadata` is public and reads a `fabric.mod.json` the way discovery does.
- Fabric applies access wideners and class tweakers to classes as they load, reads nested jars during discovery, and loads language adapters before any entrypoint; none of this runs again later.

## Decision

`HotAdd.load(jars, instrumentation)` loads a group of mod jars on the game thread:

1. It reads each jar's `fabric.mod.json` with `ModMetadataParser`, and refuses the whole group, before anything is registered, if a jar has no valid metadata, a mod id is already loaded or appears twice, a mod does not load in this environment, lists nested jars, declares an access widener or class tweaker, brings a language adapter, has a `preLaunch` entrypoint, depends on a mod that is neither loaded nor in the group in a matching version, or breaks with one that is. The refusal is a checked `HotAddRefusal` that names the jar and the reason.
2. In dependency order within the group, `LoaderBridge.register` adds the jar to the class path and registers the mod and its entrypoints the way `setup` and `finishModLoading` do.
3. Each mod's Mixin configurations for this environment go through `LateMixins.apply`.
4. Every built-in registry is opened with `RegistryThaw`, the `main` entrypoints of the new mods run, then `client` or `server`, and every registry is frozen again, also when an entrypoint throws.

`LoaderBridge` is the only class that touches Loader internals. It is one class for the one pinned Loader version; an interface waits for a second version.

Checked on 2026-10-30 on the game test server (JetBrains Runtime 25.0.4.1 build 508.40, the agent, Fabric Loader 0.20.1, Fabric API 0.165.0, Minecraft 26.3), with two test mods built by the runtime's Gradle build and kept out of the server's mods:

- `HotAddGameTest` loads `content-basic`, which registers a block and its item, and `mixin-loaded-class`, which adds an interface, a field and a method to `Item`. Afterwards the loader lists both mods, the block knows its item and can be placed, and the stone item that existed before the add answers the new method with a counter kept in the new field. No error was logged. It failed when the mixins were skipped and when the entrypoints were not registered.
- `HotAddRefusalGameTest` checks the exact reason of each refusal with jars that hold only a `fabric.mod.json`, and that none of the refused mods was registered. It failed when the `preLaunch` check was removed.

Amendment of 2026-10-30, client side: `HotAddClientGameTest` loads both test mods into the client at the title screen, before any world exists, then calls `Minecraft.reloadResourcePacks`. The new block and its item then have their own models, and the stone item answers the mixin's method. The reload sees the new mod's assets because Fabric's `ModResourcePackCreator` lists `FabricLoader.getAllMods()` each time packs are reloaded (read in `fabric-resource-loader-v1` 3.1.0). The test failed with the missing model when the reload was skipped. Server data packs (loot, recipes) are not reloaded yet.

## Alternatives

- **Restarting the game with the new mods.** It is what Rloaded exists to avoid for players.
- **Fabric Loader's public API only.** It has no way to add a mod after startup in 0.20.1.
- **A fork of Fabric Loader** with an add method. It would have to be rebased on every Loader release and shipped instead of the official Loader; reflection on four members is a smaller surface.

## Consequences

- Mods with nested jars are refused for now: issue #47. Mods with an access widener are refused for now: issue #48. Many mods on Modrinth use one or the other, so this matters before admins can add arbitrary mods.
- Resources and data packs are not reloaded yet: a new block has no model or loot until they are. The client side, which reloads its resources before the world exists, comes next.
- Opening every built-in registry also opens registries no new mod uses; freezing them again repeats work that is idempotent.
- A failure after registration leaves mod containers registered and possibly classes redefined; only a restart cleans it. The pipeline that calls `HotAdd` has to treat it as fatal.
- `LoaderBridge` depends on `FabricLoaderImpl.mods`, `modMap`, `entrypointStorage`, `adapterMap` and `ModCandidateImpl.createPlain`. A Loader update that renames one fails `HotAddGameTest`.

## Enforcement

`HotAddGameTest` and `HotAddRefusalGameTest`, run by the `game-test` CI job.
