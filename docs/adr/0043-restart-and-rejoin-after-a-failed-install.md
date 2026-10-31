# ADR 0043: A game that fails an install in session restarts and joins again; the server records the version as broken

- Status: Accepted
- Date: 2026-10-31

## Context

When a player's game cannot install the mods of an add, it tells the server, which disconnects it with the reason (ADR 0030, ADR 0041). Until now the player had to start the game and join again by hand, and the server forgot the failure, so the same version could be added again later.

A failure during an add can leave the game half changed: `HotAdd` may have registered a mod, applied its mixins and run some of its entrypoints before one of them threw (ADR 0026). Only a fresh process cleans that. A fresh game installs the server's mods at join, before any world exists, which is a different and simpler path; it may succeed where the running game failed.

Read in the Minecraft 26.3 sources on 2026-10-31: the client's main thread runs the game loop, then closes the game and returns, so the process exits with code 0 unless something calls `System.exit`. A task that throws on the client thread is logged and dropped (`BlockableEventLoop.doRunTask`): an entrypoint that threw during a client install sent no result, and the server waited for its join timeout.

## Decision

- Exit code 75 (`GameExit.RESTART`, in `rloaded-core`) means "start me again" for every process the runtime mod runs in: the server bootstrap already reads it (ADR 0041), and the launcher now does too.
- `HotAdd` names the mod whose entrypoint failed (`HotAddFailure`). The client reports any load failure to the server as a failed install, with the failing project when it is known, instead of dropping it.
- A client whose install fails in a game that already played on this server, or already loaded mods from it, stops once the server disconnects it, then exits with code 75 after the game thread ended. The launcher starts the game again with the same server, so the player joins again. A fresh game that fails at its first join never asks: a restart cannot change its result, and this is what keeps the relaunch from looping. The launcher still stops after 10 relaunches in a row, as the bootstrap does with restarts.
- When the failure names a mod of the lockfile, the server records that version as `broken` in `rloaded/compat-local.json`, with the Rloaded version and a one-line note, and logs an error naming the player, the project and the version. The file has the format of `compat/compat.json` and lists versions only. The server reads it with the shipped database when it starts, and refuses to start if it cannot read it. Admission checks the shipped database with the recorded versions on top, so the version is refused by the next add like any broken version (ADR 0038). The server keeps running with the mod.
- The report a client sends is kept to one line and bounded before the server logs it or writes it.

Checked on 2026-10-31 with `recoveryTest` (CI job `client-game-test`): a server in its own process, staging off, and one player, Alice, who trusts it and joins by quick play like a player's game. The console adds fails-on-client-once, a test mod that the server runs and whose client entrypoint fails once. Alice's game exits with code 75; the check starts it again as the launcher does; the fresh game joins with the mod. The server logs the failure as an error and `compat-local.json` lists the version as broken. About 45 seconds in local. With the relaunch left out on the client, the game stayed on the disconnection screen and the check failed.

## Alternatives

- **A local connection between the launcher and the game to ask for the relaunch.** The launcher starts the game and waits for it to end, so the exit code already reaches it, with nothing to authenticate. The connection will come for requests the game cannot express by exiting.
- **Relaunching after every failed install, at join too.** A mod that fails at join fails again in a fresh game: the player would loop through restarts.
- **Keeping the overlay in memory only.** A restart would forget the failure, and the version could be added again.
- **Removing the mod from the server.** A failure on one player's game does not prove the mod is broken for everyone, and removing a mod needs a restart of the server (#62). The admin decides.

## Consequences

- A mod that fails on players' games at join stays on the server: every player who joins afterwards is disconnected with the reason until the admin removes it (#62). The log line says which version failed.
- A player's game can mark any version of the lockfile as broken by reporting it. That only stops this version from being added again; it changes nothing the server runs.
- A player disconnected by the join timeout (ADR 0033) is not relaunched yet (#80).
- The player sees the game close and open again, without a message in between.

## Enforcement

`recoveryTest` in the `client-game-test` CI job, `RelaunchTest` and `LauncherStateTest` (relaunch and its bound), `LocalCompatTest`, and `AdmissionServiceTest` (a version recorded after start is refused).
