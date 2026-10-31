# ADR 0036: `rloaded add` admits a mod and writes it to the lockfile

- Status: Accepted
- Date: 2026-10-31

## Context

An admin adds mods from Modrinth. Before a mod reaches anyone, the server has to resolve it and its dependencies, refuse what a running game cannot take (ADR 0034), and record it in the lockfile that players install from (ADR 0028, ADR 0030) and the server loads at start (ADR 0032). Adding a mod to a game while it runs comes later. The product owner decided on 2026-10-31 that until then, `rloaded add` admits the mod and writes it to the lockfile, so it takes effect at the next restart, rather than only reporting a verdict.

Read in Modrinth's OpenAPI description v2.7.0 and checked against `api.modrinth.com` on 2026-10-31: `GET /v2/project/{id|slug}/version?loaders=["fabric"]&game_versions=["26.3"]&include_changelog=false` lists a project's versions, each with `version_type` (`release`, `beta`, `alpha`), `date_published`, its files with a `primary` flag, and dependencies of type `required`, `optional`, `incompatible` or `embedded`, each naming a project, a version, or only a file. An unknown project answers 404. Mod Menu 21.0.0 needs Text Placeholder API and Fabric API without pinning versions.

## Decision

- A new module, `rloaded-admin`, depends on `rloaded-core` and `rloaded-modrinth` and holds the admission flow; the runtime mod nests it.
- `Resolver` takes a project id or slug and an optional version number or id. It picks the version asked for, or the newest release by publication date, for Fabric and the running game version, and refuses betas and alphas. It adds every `required` dependency that is not installed, recursively, at the version the dependency pins or at its newest release, dependencies first. It refuses a project already installed, a dependency pinned to another version than the installed one, a dependency outside Modrinth, an `incompatible` project that is installed or added, and more than 64 versions. Optional and embedded dependencies are left out. Installed means the lockfile's base set and mods.
- `AdmissionService` downloads each version's primary file into `rloaded/cache/mods`, classifies each jar, asks the server whether it can load the jars that are not client-only (the game answers with `HotAdd`'s checks, without loading anything), then writes every entry to the lockfile in one atomic write. Each entry's side comes from the jar's `environment`. It is the only writer of the lockfile, and runs one add at a time. A server without a lockfile starts from one listing the pinned base set.
- `rloaded add <project> [version]` is a console command: the server console and RCON may run it, players may not, whatever their level, nor command blocks. It answers at once that it is adding, then with the files added or the reason for the refusal. Messages are in the language file.
- The Modrinth client reads the new version fields strictly: a value outside the documented enums makes the record malformed.

Checked on 2026-10-31 with `AdminAddGameTest` on the game test server: against a fake Modrinth on the loopback interface, `rloaded add` from a console source wrote a plain mod to the lockfile with side `BOTH` and `addedBy` `console`, and refused a mod with biome entries with the classifier's reason, leaving the lockfile with the first mod only. With the classifier given no registries, the biome mod was added and the test failed.

## Alternatives

- **Reporting the verdict only until mods can be added while the game runs.** The product owner chose writing the lockfile, which makes the command useful now; adding during play will replace "at the next restart" with "now".
- **The resolver in `rloaded-modrinth`.** That module reads Modrinth's records; deciding what to add, with the jar rules and the lockfile, is another responsibility.
- **Side from Modrinth's `environment` field.** Fabric Loader decides from the jar's `fabric.mod.json`, so that is what a server or a client will actually load.

## Consequences

- An added mod is loaded at the next server start and installed by players when they next join; players already connected keep playing without it. The server's join manifest still lists the old lockfile until the restart.
- Admission does not yet include the reputation guard, the compatibility database, or the staging dry-run. Those come next.
- Dependency versions are not checked against `fabric.mod.json` version ranges at resolution; `HotAdd`'s check does it for the server's mods, with Fabric's rules, before anything is written.
- `rloaded.test.modrinth` is now read on servers too, for checks only, with a warning in the log.

## Enforcement

`ModrinthProjectVersionsTest` (recorded answers of 2026-10-31), `ResolverTest`, `AdmissionServiceTest`, `JarClassifierTest`, `LockfileTest`, and `AdminAddGameTest` in the `game-test` CI job.

Amended 2026-10-31: the staging dry-run is described in ADR 0039; the reputation guard and the compatibility database in ADR 0037 and ADR 0038.
