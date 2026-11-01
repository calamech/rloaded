# ADR 0032: The bootstrap obtains the lockfile's mods at each server start

- Status: Accepted
- Date: 2026-10-30
- Amended: 2026-11-01, removals marked in the lockfile apply before the mods are obtained, after the world is saved (ADR 0062)

## Context

A server sends joining players the mods of `rloaded/lockfile.json` (ADR 0028, ADR 0030), but it did not run them itself: the join check started the server with the jars among its mods. A server has to run what it sends, or its players get blocks and items it does not know.

## Decision

- Before each start of the game, first start and every restart alike, the bootstrap reads the lockfile and obtains its mods with `LockfileMods`. It uses `ModInstaller`, the code a joining client uses: each entry is checked against Modrinth's record of its version, and the file is downloaded from the URL in that record into `rloaded/cache/mods`, where its size and SHA-512 are verified. A jar already in the store is not downloaded again.
- Mods with `side: CLIENT` are left out. `SERVER` and `BOTH` mods are passed to Fabric after the runtime mod, in lockfile order.
- No lockfile means no mods. A lockfile that cannot be read, an entry that disagrees with Modrinth, or a file that cannot be downloaded stops the bootstrap with the reason before the game starts.
- The lockfile is read again at each restart, because a restart is how mods leave it.

Checked on 2026-10-30 with `:rloaded-game-tests:joinTest`: the server now gets `content-basic` and `mixin-loaded-class` through `LockfileMods` from the fake Modrinth, and the check reads both in the mod list of the server's log. With the obtained jars left off the command line, the check failed ("server loaded []"), while the client still joined.

## Alternatives

- **The runtime mod loading the lockfile's mods with `HotAdd` while the server starts.** Fabric Loader can load them from the command line before the game starts, with no hot add involved; `HotAdd` refuses nested jars and access wideners (#47, #48), which a cold start handles.
- **Copying the jars into `mods/`.** Files there outlive the lockfile and an admin can drop anything in; the command line lists exactly the lockfile's mods.

## Consequences

- A server whose lockfile has mods needs Modrinth at startup the first time; later starts find the jars in the store. Modrinth's records are still read at each start.
- `pendingRemovals` is not applied: nothing writes it yet, and the bootstrap loads every listed mod. Issue #62.
- The lockfile's `minecraft` and `rloaded` fields are not compared with the running versions.

## Enforcement

`LockfileModsTest` and `BootstrapTest` (server-side mods only, lockfile order, read again at restart, refusals), and `:rloaded-game-tests:joinTest` in the `client-game-test` CI job.
