# ADR 0052: A game gets a singleplayer world's mods before the world opens

- Status: Accepted
- Date: 2026-11-01

## Context

A singleplayer world lists the mods added to it in `<world>/rloaded/lockfile.json` (ADR 0049). A fresh game that opens the world has none of them loaded, so their blocks and items do not exist when the world's chunks are read. A game can also hold mods the world does not list, loaded at a server join or for another world; their content would end up saved in the world, which then could not load without them. Since ADR 0051 the launcher does not know which world the game has open, so it cannot start the game again in a given world.

## Decision

- Before a world opens, a mixin at the start of `WorldOpenFlows.openWorld` (Minecraft 26.3) asks `WorldGate`, which reads the world's lockfile and decides with `WorldOpening`, from the mods Rloaded loaded in this game (`LoadedMods`, filled at joins, by adds and here):
  - the game has the world's mods and no other: the world opens;
  - the game misses some: it downloads them into the launcher's mod store, checking their SHA-512 as at a join, loads them with `HotAdd`, reloads its resources, then opens the world;
  - the game holds mods the world does not list, or other versions of them: it writes the world's folder name to `rloaded/open-world.txt` in the game folder and stops with exit code 75 (ADR 0043); the launcher starts it again, and at its title screen the game opens that world, which then goes through the gate in a fresh game.
- Mods for dedicated servers only are left out: a game never loads them.
- A lockfile that cannot be read, mods that cannot be downloaded or loaded, or a game started without the launcher's mod store: the world does not open, and a screen says why. A failure after mods started loading restarts the game.
- A game that loaded mods does not create a new world: at `WorldOpenFlows.createFreshLevel` it stops with exit code 75 first, and the launcher starts it again on the title screen.
- Joining a singleplayer world no longer triggers ADR 0047's restart at login: the gate decided before the world loaded.

Checked on 2026-11-01 with `singleplayerTest` (CI job `client-game-test`), now three games in one game folder, green 3 times out of 3 in local, about 70 seconds each. The first adds content-basic and mixin-field-init to Island from the Mods screen, places the block, and opens Island again in the same game, which needs nothing. A fresh game, with an empty mod store, opens Island: it downloads and loads both mods first, and the block is there with its model, every client entity having mixin-field-init's field. It then opens Plain, which lists no mod, and exits with code 75. Started again, it opens Plain by itself, without content-basic. With the gate letting every world open, the fresh game did not get into Island within three minutes; with the restart left out, it did not exit with code 75 and the check failed.

## Alternatives

- **Letting the launcher start the game in the world with quick play.** The launcher does not know the world any more (ADR 0051); the game folder is the game's own.
- **Loading the mods once the world's server started.** The world's registries and chunks are read as it opens; mods loaded after that come too late for the blocks already read.
- **Opening the world and letting unknown blocks go.** The world would lose them at its next save.

## Consequences

- A player who creates a world in a game that loaded mods sees the game start again and creates the world again.
- The restart takes the time of a game start.

## Enforcement

`WorldOpeningTest` in `check`; `singleplayerTest` in the `client-game-test` CI job.
