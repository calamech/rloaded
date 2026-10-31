# ADR 0047: A game that loaded mods while in a world restarts before it joins a server again

- Status: Accepted
- Date: 2026-10-31

## Context

Read in the Fabric API 0.165.0+26.3 sources (`fabric-registry-sync-v0`) on 2026-10-31: the first time a connection synchronizes a registry with the server, Fabric API keeps a copy of that registry (`MappedRegistryMixin.remap`, `fabric_prevEntries`). When the client disconnects, it puts that copy back (`MappedRegistryMixin.unmap`, called after `Minecraft.disconnect`). Only registries marked synced and holding modded entries are synchronized: `FabricRegistryInit` marks blocks and items, among others, but not creative tabs.

Measured on 2026-10-31 with `addTest`, players leaving the server for the title screen and joining it again from the same game:

- content-basic's block and item, added while the players were in the world but before any modded item existed, stayed registered after they left: the client loads an add's mods before Fabric's synchronization runs, so the first copy already held them.
- late-item's item, added after content-basic, was gone from the game's registries once the players left. Joining again, the client was disconnected with "Received a registry entry that is unknown to this client."
- keybind-and-tab's creative tab, added after content-basic too, stayed: creative tabs are not synchronized.

## Decision

- A game that loaded mods while it was in a world restarts before it joins a server again: when it starts connecting, it stops and exits with code 75 (ADR 0043), and the launcher starts it again for its server. The fresh game installs every mod of the server at join, before any world exists.
- A game that only installed mods at join never restarts for this: the first copy of its registries holds them.

Checked on 2026-10-31 with `addTest` (CI job `client-game-test`): after content-basic, mixin-field-init, keybind-and-tab and late-item were added, and late-item's item given, both players leave and join again from the same game. Each game exits with code 75 before joining; the check starts them again, as the launcher does, and both players come back with content-basic's and late-item's items in their inventory. Without the restart, the games tried to join and were disconnected, and the check failed.

## Alternatives

- **Adding the new entries to Fabric API's copy when a mod loads.** It keeps the game running, but writes to fields that Fabric API adds to the game's registries and does not publish; any change to them would break the client silently.
- **Restarting as soon as the player leaves.** A player who leaves to quit the game, or to play alone, would see it start again for nothing.
- **Restarting only when a synchronized registry got an entry after the copy.** It would spare a restart in cases like content-basic's, but depends on the same unpublished fields.

## Consequences

- The game always starts again for the server the launcher started it for. A player who joins another server from the game's own list, after mods were added, lands on the launcher's server; the launcher's one game per server keeps that rare.
- The restart takes the time of a game start, during which the player sees the game close and open again.
- Singleplayer worlds opened from the same game after leaving are not covered by this decision.

## Enforcement

`addTest` in the `client-game-test` CI job.

Amended 2026-10-31: a game that loaded mods into an open singleplayer world restarts the same way before it opens a world or joins a server again (ADR 0049).
