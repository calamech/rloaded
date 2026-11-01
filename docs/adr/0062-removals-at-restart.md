# ADR 0062: Mods leave a server at its next start, after the world is saved

- Status: Accepted
- Date: 2026-11-01

## Context

A mod cannot leave a running game: its classes, registry entries and mixins stay until the process ends. The lockfile has had a `pendingRemovals` list since ADR 0028, but nothing wrote it, and the bootstrap started the server with every mod of the lockfile (#62). Removing a mod can also lose what the world holds of it, its blocks and items.

The product owner decided on 2026-11-01 that:

- an admin marks a mod for removal from the console, `rloaded remove <project>`, or from the Mods screen, with the same rights as an add, and can change their mind (`rloaded keep <project>`); every request goes to the audit log;
- the mod leaves at the next start of the server the admin chooses; nothing restarts the server for it;
- the world is saved to a zip in `rloaded/backups` first, the newest three kept; if the backup fails, the removal waits, the mod stays, and an error says so;
- a mod that other mods of the server need is not removed, and the refusal names them.

## Decision

- `Removals` (in `rloaded-admin`, reached from `AdmissionService.removals`) writes `pendingRemovals`. It shares the add's lock, so an add never writes over a mark, and answers `Busy` while one runs. It takes a project id or a slug (Modrinth's project versions name the id), refuses a mod the lockfile does not list, one of the base set, one already marked or not marked, and one that a mod staying needs, by the `required` dependencies of the installed versions on Modrinth. Each refusal is a `RemovalRefusal` with a `RemovalProblem` for the Mods screen.
- The console commands `rloaded remove <project>` and `rloaded keep <project>` call it, for the console only like `rloaded add`.
- `PendingRemovals`, in the bootstrap, runs before each start reads the lockfile: it zips the world folder (`level-name` of `server.properties`, without `session.lock`) to `rloaded/backups/<UTC time>-<world>.zip`, written aside then moved in place, deletes all but the newest three, then writes the lockfile without the marked mods. If the world cannot be saved or the lockfile written, it prints an error and leaves the lockfile as it was. A server with no world yet needs no backup.
- The audit log goes to schema 2: each request names its action (`add`, `remove`, `keep`) and its id is `request`; marks end with `marked` or `kept`, and starts write `removed` (with the backup's name) or `postponed` (with the reason), as actor `restart`.
- Players need nothing: a game that loaded mods from a server starts again before it joins one (ADR 0047), and a new game loads only what the manifest lists.

The Mods screen's buttons and singleplayer worlds come in their own changes.

## Alternatives

- **Removing at once and restarting the server.** The owner wants the admin to pick the moment; players would be cut off mid-game.
- **Removing the mods that need the removed one too.** It removes more than the admin asked for, without telling them first.
- **Keeping every backup.** Worlds weigh hundreds of megabytes; three backups cover a few restarts and bound the folder.
- **Backing up with the server running.** The bootstrap runs between two runs of the server, so the world files are closed and consistent.

## Consequences

- Checking what needs a mod reads Modrinth: a removal fails, with the reason, when Modrinth cannot be reached.
- A backup takes disk space and time at start, once per start that applies removals; a large world makes that start slower.
- Only the dependencies Modrinth records are checked; a mod that needs another without saying so on Modrinth can lose it.

## Enforcement

- `RemovalsTest` (marking by id or slug, refusals, keep, nothing changed without an audit line) and `PendingRemovalsTest` (backup then removal, three backups kept, mods kept when the backup fails, nothing done when nothing is marked) in `check`.
- `AdminAddGameTest` (CI job `game-test`): `rloaded remove`, `rloaded keep` and an unknown project from the console, with the lockfile and the audit trail they leave.
