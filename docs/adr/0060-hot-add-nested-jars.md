# ADR 0060: A mod's nested jars load with it, by Fabric's version rule

- Status: Accepted
- Date: 2026-11-01

## Context

`HotAdd` refused any mod whose `fabric.mod.json` lists nested jars (ADR 0026, #47). Many mods on Modrinth ship their libraries that way, Fabric API modules included, so most of the catalog could not be added to a running game.

Read in Fabric Loader 0.20.1 on 2026-11-01:

- `ModDiscoverer` reads the jars a mod lists under `jars`, at any depth, as candidates of their own (`ModCandidateImpl.createNested`).
- `ModResolver` keeps one candidate per mod id, the newest version.
- `FabricLoaderImpl.setup` writes each nested candidate it keeps to `.fabric/processedMods` in the game folder (`ModCandidateImpl.copyToDir`), then adds it like any mod.

## Decision

- `LoaderBridge.read` reads a jar's metadata, then the metadata of each jar it nests, in memory, down to 4 levels like the jar classifier, each nested jar at most 64 MiB. A listed jar that is missing, or a nested jar without `fabric.mod.json`, refuses the add with its name.
- `HotAdd` applies Fabric's rule to the nested mods: the newest version of an id wins within the add. A nested mod the game already runs, in the same version or a newer one, is left out and the game keeps its own. One newer than the game's is refused ("it nests fabric-api-base 999.0.0, newer than the … the game runs"), since a loaded mod cannot be replaced in a running game. A jar of the add itself whose id is loaded is still refused.
- Each nested mod goes through the same checks as a jar of the add (environment, access widener, language adapters, `preLaunch`, dependencies), except the jar classifier, which already reads nested jars with the jar that holds them.
- Before anything is registered, nested mods are written to `.fabric/processedMods/<id>-<version>-<hash>.jar`, as Fabric does at startup, then registered, mixed in and initialized with the others, in dependency order.

Checked on 2026-11-01 by `NestedJarsGameTest` on the game test server: a mod nesting a library, which nests another mod, plus an older `fabric-api-base`, loads with both nested mods; the loader lists them, a class of the library loads from its extracted jar, and the game keeps its own `fabric-api-base`. A mod nesting a newer `fabric-api-base`, and one nesting a jar without `fabric.mod.json`, are refused with the reason and not registered. With nested jars left unread, the test fails.

## Alternatives

- **Refusing any nested mod the game already runs.** Most mods nest Fabric API modules older than the server's; they would all be refused, which is what #47 is about.
- **Extracting nested jars to a folder of Rloaded's own.** It works the same, but a later start of the game would extract them again to Fabric's folder; using Fabric's keeps one copy.

## Consequences

- A mod whose nested library is newer than the one the game runs is refused, though Fabric would load it at the next start.
- Nested mods are not linked to the mod that holds them (`ModContainer.getContainingMod` is empty), unlike at startup. Nothing in Rloaded reads that link.
- Nested jars of an add stay in `.fabric/processedMods`, as Fabric leaves them.

## Enforcement

`NestedJarsGameTest` and `HotAddRefusalGameTest`, in the runtime mod's `gametest` source set, run by the `game-test` CI job.
