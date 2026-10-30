# ADR 0028: The lockfile is strict JSON, hashed over its base set and mods

- Status: Accepted
- Date: 2026-10-30

## Context

A server tells joining players which mods to install. That list has to survive restarts, be readable by an admin, and let a client tell cheaply whether it changed since its last visit, before any manifest is sent. Rloaded writes the file itself; admins add and remove mods through Rloaded, not by editing it.

## Decision

Each server, and each singleplayer world, keeps `rloaded/lockfile.json`:

```json
{
  "schema": 1,
  "minecraft": "26.3",
  "rloaded": "0.1.0",
  "baseSet": [{ "projectId": "P7dR8mSH", "versionId": "EQKgpeKu", "sha512": "…" }],
  "mods": [{
    "projectId": "…", "versionId": "…", "fileName": "example-1.2.0.jar", "sha512": "…",
    "size": 123456, "side": "BOTH", "addedAt": "2026-10-29T18:00:00Z", "addedBy": "console"
  }],
  "pendingRemovals": []
}
```

- `Lockfile.parse` refuses the whole file on an unknown field, a missing field, a schema other than 1, an invalid SHA-512, a mod entry the protocol would refuse (such as a file name with a path), a date that is not an ISO-8601 instant, or a project listed twice. The errors are values (`LockfileError`) naming the cause. Files over 1 MiB are refused before reading.
- `Lockfile.hash` is the SHA-256 of the base set and the mods written as compact JSON, every field in declaration order. Layout and pending removals do not change it; any field of a mod does. Servers send it in `ServerHello`.
- Mods become manifest entries in lockfile order.

Writing the lockfile, with an atomic rename, comes with the first feature that changes it: adding a mod.

## Alternatives

- **Ignoring unknown fields**, as Rloaded does for Modrinth's answers (ADR 0025). The lockfile is Rloaded's own file; a field it does not know means another version wrote it, or a hand edit, and either should stop the server rather than be dropped.
- **Hashing the file's bytes.** Reformatting or reordering pending removals would then make every client think the mods changed.

## Consequences

- A lockfile written by a later schema stops an older server with `UnsupportedSchema`. Migrations are added when the schema changes.
- An admin who edits the file by hand gets a refusal with the reason instead of a silent fix.

## Enforcement

`LockfileTest`, which also recomputes the hash from a hand-written canonical string, run by `check`.
