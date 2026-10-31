# ADR 0049: The launcher adds mods to an open singleplayer world through its integrated server

- Status: Accepted
- Date: 2026-10-31

## Context

A player who plays alone adds mods from the launcher while their world is open, over the link the game opens to its launcher (ADR 0048). A singleplayer world runs on an integrated server inside the game's own process: client and server share the registries, the classes and the mods loaded. Read in the Minecraft 26.3 sources and measured on 2026-10-31:

- `HotAdd` (ADR 0026) loads a mod once per process. In a client it runs the mod's `main` entrypoints, then its `client` ones, which expect the client thread: key mappings (ADR 0042), renderers. While it registers content, no other thread may read the registries, so the server thread must wait for it.
- An integrated server pauses when the game shows a pause screen, as when the player switches to the launcher, and also as soon as its player list is empty (`IntegratedServer.tickServer`), which it is while its only player is in the configuration phase. Paused, it runs only `tickConnection` and its queued tasks, not Fabric API's `END_SERVER_TICK`. A dedicated server does the same once empty for `pause-when-empty-seconds` (60 by default).
- The client's entities are objects of their own: after an add with mixin-field-init, whose mixin gives every entity a field set by its constructor, 1 of 1 client entity lacked the field; after sending the player through the configuration phase (`switchToConfig`, then `startConfiguration`, ADR 0024), 0 of 1 did.
- Fabric API's client game tests move the client, server and test threads forward together, a tick at a time (`ThreadingImpl.PHASER`). When the server thread waited, inside one of its tasks, for the client thread to run the load, the load ran in one run; in the next, the client thread had already finished its tick and waited for the server at the end of it, and the add was called off after 30 seconds.
- `DataReload`, which completes the data pack reload of an add (ADR 0039), listened on dedicated servers only.

## Decision

- **Who asks.** The game serves the launcher's requests when the launcher started it. A request is refused with `NO_WORLD` when no singleplayer world is open, and with `SHARED_WORLD` when the world is open to the local network: other players would join without the mods. The launcher is the only one that can ask, as it started the game for the world's owner.
- **Admission.** The world's integrated server runs the `AdmissionService` of dedicated servers with `LiveGame.SINGLEPLAYER`: it checks and loads every jar of the add, the mods for the client included, since the game is the client too; a mod for dedicated servers only is refused by the game's check ("it does not load on the client"). There is no staging server. Versions are held to the default reputation rules (72 hours, 1,000 downloads) and to the compatibility database shipped in the mod. Jars go to the launcher's mod store; the lockfile is the world's own, `<world>/rloaded/lockfile.json`, with `addedBy: launcher`. Each refusal is sent with its `AddProblem` key (`AddRefusal.problem()`).
- **Load.** `LiveAdd` serves both kinds of servers, with what differs behind `LiveAdd.Host`. On the integrated server, the server thread freezes ticks and saves, then the two threads meet through `Handoff` (in `rloaded-core`): at each of its ticks, the server thread parks and waits up to 1 second for the client thread; at each of its ticks (`END_CLIENT_TICK`), the client thread offers to run the load and waits up to 1 second for the server thread to park. Whichever comes first waits for the other, and on a miss both go on with their tick and come back at the next one, so neither can hold the other for good. Once the client thread runs the load, the server thread waits for its end. The load is called off, with nothing loaded, if it has not started within 30 seconds or if the world closes first.
- **Rollout.** Then, as on a dedicated server: data packs reload, loaded entities and block entities are rebuilt, the lockfile records the mods. The client reloads its resources, and its player goes through the configuration phase, which rebuilds the client's world with the new classes. Ticks run again once the player is back, and the launcher gets `Added`.
- **Ticks while paused.** The rollout and the handoff move on from a mixin at the end of `MinecraftServer.tickConnection`, which runs at every tick and while the server pauses, for both kinds of servers; `LiveAdd` no longer uses `END_SERVER_TICK`. `DataReload` listens in every game.
- **Failures.** A failure after the load started stops the game with exit code 75 (ADR 0043), after the world was saved; the world's lockfile does not list the mods. A game that loaded mods into a world restarts the same way before it opens a world or joins a server again, as after an add on a server (ADR 0047).

Checked on 2026-10-31 with `singleplayerTest` (CI job `client-game-test`), about 35 seconds in local: the check plays the launcher's end of the link against a real game and a fake Modrinth. It asks for content-basic before a world is open (`NO_WORLD`), then while the world is open to the local network (`SHARED_WORLD`), then for a project Modrinth does not have (`NOT_FOUND`); with the game paused, it asks for content-basic, then mixin-field-init, and both are added. The game then places content-basic's block, which its client shows with its model; every client entity has mixin-field-init's field; the world's lockfile lists both mods; no log line is an error from the first add on. With the player not sent through the configuration phase, the check failed on "1 client entities lack mixin-field-init's field". With the rollout moved on only while the server does not pause, the add stopped after "Installing the new mods on 1 players". In `HandoffTest`, two threads moving on together a tick at a time meet in their first tick in 50 runs out of 50; with the offering thread not waiting, they needed 3 ticks.

## Alternatives

- **The server thread waiting, inside one of its tasks, for the client thread to run the load.** It deadlocked under the client game tests' ticks (measured above), and in any game it holds as long as the client thread waits for the server elsewhere, as when the player leaves the world.
- **Loading the mods on the server thread.** The `client` entrypoints would run off the client thread, and the client thread would read the registries while they change.
- **Leaving the player in the world.** The client's entities built before the add keep their old objects (measured above).
- **A staging server in singleplayer.** It would start a second server process on the player's machine at each add, for a world only that player plays and can load from its last save.

## Consequences

- Only a game the launcher started can get mods in its worlds.
- A world opened again in a fresh game does not load the mods of its lockfile yet, and after a restart the launcher opens the title screen rather than the world (#88).
- A world open to the local network cannot get mods, and players who join a world through the local network do not install its mods.
- Mods for dedicated servers only cannot be added to a singleplayer world, as Fabric Loader does not load them in a client either.
- Stalled joins on a dedicated server that pauses while empty are not expired yet, for the same reason the rollout did not move on (#89).

## Enforcement

`singleplayerTest` in the `client-game-test` CI job; `HandoffTest`, `AdmissionServiceTest` (singleplayer world) and `AddProblemsTest` in `check`; `addTest`, `soakTest` and `restartTest` for dedicated servers.
