# ADR 0025: rloaded-modrinth reads version records over the core HTTP port

- Status: Accepted
- Date: 2026-10-30
- Amended: 2026-10-30, the installer and project details (see the end of Decision)

## Context

A server sends its players identifiers and hashes, never jars. Before a client downloads a mod, it checks the manifest entry against Modrinth's own record of that version, and takes the file's URL from that record. The resolver will later read Modrinth too. Both need a Modrinth client that respects the rules every download already follows: allowed hosts, timeouts, size limits, no hidden retries.

Read in Modrinth's OpenAPI description v2.7.0 (`docs.modrinth.com/openapi.yaml`) and checked against `api.modrinth.com` on 2026-10-30:

- Every request must carry a User-Agent that identifies the application; the limit is 300 requests a minute per IP address, and answers carry `X-Ratelimit-Limit`, `X-Ratelimit-Remaining` and `X-Ratelimit-Reset`.
- `GET /v2/versions?ids=[...]` returns several versions in one request. Asked for `EQKgpeKu`, `kyy7dbrZ` and `zzzzzzzz`, it answered 200 with the two known versions, in another order, and left the unknown id out. An id with a space was refused with 400.
- Each file carries its URL, name, size and `hashes.sha512`. The SHA-512 of Fabric API 0.165.0 in that answer is the one `runtime-pins.json` recorded from the per-project listing on an earlier day.

## Decision

A new module, `rloaded-modrinth`, holds the client. `Modrinth.versions(ids)` asks `/v2/versions` for up to 100 ids per request and returns each version's id, project and files. It refuses an id that is not 1 to 16 letters or digits before any request, and fails with `UnknownVersions` when Modrinth leaves a requested id out, since the endpoint does not refuse them itself. An answer that lists a version twice, or one that was not asked for, is malformed. A 429 answer becomes `RateLimited`, apart from other HTTP failures; nothing is retried in the module. `fileFor(entry)` returns the file a manifest entry names once the record agrees with it on version, project, file name, size and SHA-512.

The module goes through the `Http` port of `rloaded-core`, so it gets the URL policy, the timeouts, the size limits and the User-Agent (`calamech/rloaded (github.com/calamech/rloaded)`) every download already has. It therefore depends on `rloaded-core`. When core logic such as the resolver needs Modrinth, core declares a port for what it needs and `rloaded-modrinth` implements it.

Answers are decoded with unknown fields ignored: Modrinth adds fields to its records over time, and refusing them would break every client the day it does. The fields Rloaded checks are required and validated; a missing one, or a SHA-512 that is not 128 hexadecimal digits, makes the answer malformed.

Tests run against a real HTTP server on the loopback interface that answers from versions recorded on `api.modrinth.com` on 2026-10-30, leaving unknown ids out as Modrinth does. A request with no recorded answer fails the test.

Amendment of 2026-10-30, the installer: `ModInstaller.install` takes manifest entries, reads their records in one go, refuses every entry that disagrees with its record before downloading anything, then downloads each file from the URL in the record into the content-addressed `ArtifactStore`, which verifies size and SHA-512, four at a time. `installAsync` returns a `CompletableFuture` for the runtime mod's Java code. `InstallPlan.entriesToInstall`, in `rloaded-core`, picks the entries a client installs: never server-only mods, not the client-only mods the player left out (ADR 0029), not mods the game already loaded; a mod loaded in another version is reported as a conflict, since a running game cannot replace it.

Amendment of 2026-10-30, project details: `Modrinth.projects(ids)` returns the title, download count and team members of each project, in two requests (`/v2/projects`, then `/v2/teams`), for the trust screen. Checked against `api.modrinth.com` on 2026-10-30: team roles are free text ("Owner", "Original creator", "Maintainer"), there is no ownership flag, and Mod Menu belongs to an organization; the members are kept in Modrinth's order and the screen decides how many to show.

## Alternatives

- **A Modrinth client in `rloaded-core`.** Core would mix Mojang, runtime and Modrinth code; the module boundary keeps what talks to Modrinth in one place.
- **Moving `Http` to a module of its own** so that core could depend on `rloaded-modrinth`. It splits a working package for a direction that a port in core gives as well, once core needs Modrinth.
- **One request per version** with `GET /v2/version/{id}`. A manifest of a hundred mods would take a hundred requests, a third of the minute's allowance.
- **Pacing requests to stay under 300 a minute.** Joining a server takes a request per 100 mods. This is reopened when a feature sends more than about 100 requests in a minute, such as checking the most downloaded mods for compatibility.

## Consequences

- The rate limit headers are not read: the `Http` port returns bodies only. A caller that hits `RateLimited` knows the window is one minute, not when it resets.
- The User-Agent has no version number, which Modrinth recommends. It is added with the first release version.
- The client reads only the fields the file check needs. The resolver adds the ones it needs (release channel, publication date, dependencies) with their own tests.

## Enforcement

`ModrinthTest` (recorded answers, unknown ids, invalid ids, rate limit, malformed answers) and `FileCheckTest` (each disagreement refused), run by `check`.
