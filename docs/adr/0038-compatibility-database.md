# ADR 0038: A compatibility database in the repository refuses categories, projects and versions

- Status: Accepted
- Date: 2026-10-31

## Context

Some mods cannot work when added to a running game, whatever their jar looks like: optimization mods replace the renderer, chunk loading or block state storage, all built when the game starts. Others may be fine in principle but fail with Rloaded in practice. The jar classifier (ADR 0034) cannot see either; a list that people maintain can.

Read on `api.modrinth.com` on 2026-10-31: Sodium (`AANobbMI`), Iris (`YL57xq9U`), Lithium (`gvQqBUqZ`), C2ME (`VSNURh3q`), ModernFix (`nmDcB62a`), FerriteCore (`uXXizFIs`), ImmediatelyFast (`5ZwdcRci`) and Entity Culling (`NNAgCjsB`) list the `optimization` category among their `categories`; Indium (`Orvt0mRa`) lists only `utility`. Project records carry `categories` and `additional_categories`.

## Decision

- `compat/compat.json`, in the repository, lists refused categories with a reason (`optimization`), refused projects by Modrinth project id with a slug for readers and a reason (the nine above), and tested versions by Modrinth version id with a status, `verified` or `broken`, the Rloaded version that tested them and a note. It is read strictly: an unknown field, another schema, an unknown status or a duplicate refuses the file.
- The runtime mod carries the file of its release, and a server reads it when it starts; an unreadable file stops the server, since it is a defect of the build.
- `RecordChecks` (in `rloaded-admin`, replacing `Reputation`) checks every version an add would bring, before any download, against the reputation rules (ADR 0037) and the database: a refused project, a refused category among the project's categories, a version marked `broken`. Project records are read once for both. `verified` changes nothing; it informs admins.
- The Modrinth client now reads each project's categories, featured and additional.

Checked on 2026-10-31 with `AdminAddGameTest`: a project in the `optimization` category was refused with the database's reason. With categories ignored in the code, it was added and the test failed. `AdmissionServiceTest` refuses a denied category, a denied project and a broken version without downloading their files.

## Alternatives

- **Fabric mod ids instead of Modrinth project ids.** A mod id is known only once the jar is downloaded; a project id is in Modrinth's records, so the refusal comes before any download, and Modrinth ids do not change when a mod renames itself.
- **Fetching the database from the tagged release at startup.** No release is published yet; the file in the mod jar is the one of the release it belongs to. Fetching newer data, with a pinned URL and a cache, becomes worth its cost once releases exist and the list changes between them.
- **A local overlay written from players' failures.** Nothing reports a player's failure to the server yet; the overlay comes with that report.

## Consequences

- Updating the list needs a new release of the mod.
- A mod that is optimization-only in spirit but filed under another category passes this rule; the list of projects covers known ones.
- The database refuses; it never lets through what another rule refuses.

## Enforcement

`CompatDatabaseTest` (reads the shipped file), `AdmissionServiceTest`, `ModrinthProjectsTest`, and `AdminAddGameTest` in the `game-test` CI job.

Amended 2026-10-31: servers now keep the versions that failed on players' games in `rloaded/compat-local.json`, in the format of this file, and admission reads it on top of the shipped database (ADR 0043).
