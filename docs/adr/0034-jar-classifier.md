# ADR 0034: One classifier decides which mod jars a running game refuses

- Status: Accepted
- Date: 2026-10-30

## Context

Some mods cannot be added to a game that already started: a `preLaunch` entrypoint runs before the game starts, language adapters are read once at startup, and the registries the game fills from data packs (biomes, dimensions, enchantments and the like) are read only when a world loads. `HotAdd` refused the first two from Fabric's parsed metadata (ADR 0026), and nothing refused the third. Admission of an add will need the same rules before anything is downloaded by players, so they need one owner that does not depend on the game.

Read in the sources of Minecraft 26.3 and Fabric API 0.165.0 on 2026-10-30: `RegistryDataLoader.WORLD_REGISTRIES` and `DIMENSION_REGISTRIES` list the registries loaded from data packs with a world (51 and 1), `RELOADABLE_REGISTRIES` those `/reload` reads again (loot tables, recipes, advancements and others). `Registries.elementsDirPath` gives a registry's folder, its path without namespace. Fabric API's `DynamicRegistries.getWorldRegistries()` returns the game's world registries plus those mods register. Fabric Language Kotlin 1.14.2 declares the `kotlin` adapter; Fabric Loader's own is `default`.

## Decision

- `JarClassifier` in `rloaded-core` reads a jar's `fabric.mod.json` and entry names, and those of the jars nested in it, and returns every `Refusal`: `PreLaunch`, `LanguageAdapter` (declared, or used by an entrypoint and not one of the base set's), `DatapackRegistry` (an entry under `data/<namespace>/<registry>/` for a registry the game reads with a world). A jar it cannot read is a `JarProblem`, which refuses the mod too. Each refusal and problem carries its reason in words. Files over 64 MiB and nesting deeper than four jars are refused rather than read.
- The classifier gets the registries and adapters from its caller. In the game, `Admission.classifier()` builds it from `DynamicRegistries.getWorldRegistries()` and `DIMENSION_REGISTRIES`, so registries other mods add are covered, and from the adapters `default` and `kotlin`.
- `HotAdd` asks the classifier instead of checking `preLaunch` and adapters itself, and joins every reason in its refusal. Its own checks stay for what only the engine knows: the environment, nested jars, access wideners, dependencies, ids already loaded.

## Alternatives

- **A list of data pack registries written in Rloaded.** It would miss registries other mods add, and drift from the game's list when a version adds one.
- **Keeping the rules in `HotAdd`.** Admission on the server has to refuse a mod before players download it, and these rules then live in two places.

## Consequences

- The rules that need more than the jar come with the resolver and the compatibility database: a library in another version than a loaded one, no Fabric 26.3 version, mods known to break or to replace core systems.
- Tags of world registries (`data/<namespace>/tags/worldgen/biome/…`) are not refused.
- A `fabric.mod.json` with comments, which Fabric Loader tolerates, is refused as malformed.
- Each jar `HotAdd` loads is read twice, once by Fabric's parser and once by the classifier.

## Enforcement

`JarClassifierTest` (each rule, nested jars, unreadable jars, limits, reasons) and `HotAddRefusalGameTest` on the game test server, which now also refuses a mod with biome and enchantment entries and one whose entrypoint needs another adapter. With the classifier given no registries, the biome mod loaded and the game test failed.

Amended 2026-10-31: the classifier also reports the mod id and the side the jar declares, which admission writes to the lockfile (ADR 0036).
