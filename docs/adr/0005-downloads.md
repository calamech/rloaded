# ADR 0005: Downloads

- Status: Accepted
- Date: 2026-10-29

## Context

Rloaded downloads the game, its libraries and assets, Fabric Loader, mods and a Java runtime, on the player's machine and on the server. A file that is not exactly the expected one must never run, and a slow or broken source must not hang the launcher.

## Decision

- **One HTTP port**, `Http`, with a single implementation, `KtorHttp`, on the Ktor 3.6 CIO engine. It is the only file that touches Ktor's client API.
- **Bounded requests.** Connect, idle-read and whole-request timeouts come from `HttpSettings`. Bodies have a size limit: the published size when known, 512 MiB otherwise. Ktor never retries and never follows redirects on its own.
- **Allowlisted origins.** Every URL, and every redirect target, must match an HTTPS origin of `UrlPolicy.PRODUCTION`: Mojang, Fabric, Modrinth and JetBrains hosts. Anything else is refused before a request is sent.
- **Content-addressed store.** `ArtifactStore` keeps each file at `root/ab/abcdef…`, named by its hash. A download goes to a temporary file, is checked for size and hash, then moved into place atomically. A file under its final name is therefore already verified.
- **One retry policy**, in `ArtifactStore`: network failures, 5xx, 408 and 429 are tried again, up to 3 attempts with a backoff doubling from 500 ms to at most 5 s. A wrong hash, a wrong size or a refused URL fails at once.
- **Errors as values.** Every failure is a `DownloadError`, which says whether it is retryable.

## Alternatives

- **The JDK `HttpClient`.** No dependency, but its timeout stops at the response headers, so a body that stalls can hang. Ktor's socket timeout covers the body.
- **Ktor's own redirect following.** Simpler, but redirect targets would skip the allowlist.
- **Trusting HTTPS alone.** Rejected: the hash is what proves the file is the expected one, whoever serves it.

## Consequences

- A file present in the store is trusted without hashing it again, so a launch with a warm cache stays fast. A file damaged on disk after the move is not detected.
- A source that moves to a new host fails with `Refused` until the allowlist is updated.

## Enforcement

- Tests against a real HTTP server on the loopback interface, with request counters: allowed and refused redirects, size limits, a stalled server, a refused connection, hash and size mismatches, and retries that stop on fatal errors.
- A test checks that the production policy refuses plain HTTP and unknown hosts.
