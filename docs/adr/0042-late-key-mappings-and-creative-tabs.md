# ADR 0042: Mods added while the game runs can register key mappings and creative tabs

- Status: Accepted
- Date: 2026-10-31

## Context

Many client mods register a key mapping, and many content mods a creative tab. Both must work for a mod added during play (ADR 0041) and for one installed at join, which is also loaded after the game started (ADR 0030).

Measured on 2026-10-31 with a test mod, keybind-and-tab, that registers a creative tab in its main entrypoint and a key mapping in a category of its own in its client entrypoint:

- The staging server refused it: registering the tab threw `AssertionError: Missing intrusive holder for ResourceKey[minecraft:creative_mode_tab / keybind-and-tab:tab]`. `RegistryThaw` (ADR 0017) gave every thawed registry a map of intrusive holders. In Minecraft 26.3, `MappedRegistry` creates that map only for registries whose values create their holder in their constructor, such as blocks and items, and forgets which ones they are once frozen; with the map present, `register` asserts that the value made a holder, which a creative tab never does.
- The `AssertionError` escaped both the staging server and the live add, which only caught `RuntimeException`: the staging server crashed instead of reporting.
- With the tab fixed, the client refused the key mapping: `KeyMappingRegistryImpl.registerKeyMapping` in Fabric API's `fabric-key-mapping-api-v1` 2.0.8 (read on 2026-10-31) throws `IllegalStateException: GameOptions has already been initialised` once `Minecraft.options` exists. Its `OptionsMixin` adds the registered mappings to `Options.keyMappings` at the start of `Options.load`, which ends with `KeyMapping.resetMapping`.
- Creative tabs need nothing more: `CreativeModeTabs.tryRebuildTabContents` builds the tabs again when the registries change, which a reconfiguration does, and Fabric API's `CreativeModeTabsMixin` pages them at the end of that build.

## Decision

- `RegistryThaw.rememberIntrusiveRegistries`, called as the runtime mod starts, before the registries freeze, notes which built-in registries have a map of intrusive holders. `thaw` recreates the map for those only.
- The staging server and `LiveAdd` treat an `AssertionError` thrown while loading mods like an exception: the mods fail, and past the point of no return the server restarts.
- A mixin on Fabric API's `KeyMappingRegistryImpl` makes `registerKeyMapping` see no options while a client add runs (`LateKeyMappings`, opened and closed around `HotAdd.load` in `JoinClient`). When the add registered a mapping, the client then calls `Options.load`, so the mapping joins the controls screen with the key the player saved for it, and the key map is rebuilt.

Checked on 2026-10-31: `addTest` adds content-basic, mixin-field-init, then keybind-and-tab to two connected players: both list the key mapping in their options and the tab among the creative tabs, with its item; three rollouts, no error line. Without the mixin, both clients failed with "GameOptions has already been initialised" and the check failed. `RegistryThawGameTest.creativeTabRegisteredAfterStartup` registers a tab in the thawed registry; with the map given to every registry again, the game test server crashed.

## Alternatives

- **Rebuilding `Options.keyMappings` ourselves instead of `Options.load`.** It would duplicate what Fabric API's mixin does, and miss the key the player saved for the mapping.
- **Telling intrusive registries apart by their holders' type.** `Holder.Reference.Type` is protected, so it would take reflection where noting the registries at start takes the game's own field.

## Consequences

- The mixin targets an implementation class of Fabric API; a Fabric API update that renames it fails at start, since the configuration is required, and the client tests catch it.
- `Options.load` reads `options.txt` again during an add, so a setting changed in the game and not yet saved would be lost; the game saves options when they change.
- Registries created by mods, rather than built in, are thawed without intrusive holders.

## Enforcement

`RegistryThawGameTest` in the `game-test` CI job; `addTest` in the `client-game-test` CI job.
