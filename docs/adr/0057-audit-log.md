# ADR 0057: Every add is recorded in an append-only audit log

- Status: Accepted
- Date: 2026-11-01

## Context

A server lets the console and level 4 operators add mods that then run on every player's computer. The lockfile keeps who added each mod that is in it (`addedBy`, `addedAt`), but nothing kept the adds that were refused, the players who tried without the right to, or the version and hash each add brought, once a later change rewrote the lockfile. The game's log has some of it, mixed with everything else, and rotates.

## Decision

`AdmissionService`, the only writer of the lockfile, is also the only writer of `audit.log`, next to the lockfile: `rloaded/audit.log` on a server, `<world>/rloaded/audit.log` in singleplayer. `AuditLog` (in `rloaded-admin`) appends one JSON object per line and flushes it to disk before it returns:

- `requested`: the actor (`console`, `player` with name and UUID for a dedicated server, `world_player` for a singleplayer world), the project and the version asked for, or `null` for the newest release.
- then `added`, with project, version id, version number and SHA-512 of each mod written to the lockfile, or `refused`, with the problem the Mods screen shows (`AddProblem`) and the reason, cut to 1,000 characters.

Lines of one add share an `add` id, a version 7 UUID, so ids sort by time. Each line has `schema: 1` and the server's UTC time.

The `requested` line is written before anything else: if it cannot be written, the add is refused (`AddRefusal.NotAudited`, shown as a storage problem) and nothing is downloaded or loaded. The outcome is written after the add; if that write fails, the add stands and the game logs an error naming the file. A refusal decided by the Mods screen's server side before the add starts (a player who may not add, a world open on the local network) goes through `AdmissionService.refused`, so it is recorded the same way.

`AddActor` replaces the free text the surfaces passed as `addedBy`; the lockfile keeps the same values (`console`, the operator's name, `owner`).

## Alternatives

- **A line in the game's log.** It is already there for most adds, but mixed with everything the game logs, and `logs/latest.log` is replaced at each start.
- **A table in the lockfile.** The lockfile is rewritten as a whole at each add; a history there would grow the file every player's game reads at join.
- **Writing only the outcome.** An add the game stopped during (crash, kill) would leave no trace; with the request written first, a `requested` line without an outcome shows it.

## Consequences

- An add needs a writable `rloaded` folder before it starts; that was already true of the lockfile.
- The file only grows, by two lines of a few hundred bytes per add. Nothing rotates it: adds are rare actions of an admin. If a server's log passes 10 MB, rotation will be worth a decision.
- Code can only append to it, but nothing prevents an admin from editing the file: it records what the server did, it does not prove it to a third party.
- An outcome that cannot be written leaves the add without its result in the file, with an error in the game's log.

## Enforcement

- `AdmissionServiceTest`: request then added mods with their hashes, a refusal with its problem, no add started when the request cannot be written, a refusal from the surface, an outcome that cannot be written. `AuditLogTest`: version 7 ids, actors.
- `AdminAddGameTest` (CI job `game-test`): the exact trail of one console add and four refusals.
- `addTest` (CI job `client-game-test`): console adds, Alice's add from her Mods screen, and Bob refused as `NOT_ALLOWED`.
