# ADR 0020: rloaded-protocol is the lowest module and holds Outcome

- Status: Accepted
- Date: 2026-10-30
- Amended: 2026-10-30, `Checksum`, `HashAlgorithm` and `InvalidChecksum` move to the same package, as messages carry SHA-512 hashes of mod files

## Context

The server, the client and the launcher are going to exchange messages: the server's identity, the list of mods to install, install progress and results. The message types have to be defined once and used by all three. Decoding a message can fail in expected ways, and Rloaded returns expected failures as values with `Outcome`, which lived in `rloaded-core`. `rloaded-core` is going to use the message types for the lockfile, so the module that holds them cannot depend on `rloaded-core`.

## Decision

A new module, `rloaded-protocol`, sits under every other Kotlin module and depends on none of them. `Outcome` and its functions move there, to the package `io.github.calamech.rloaded.protocol`, unchanged. `rloaded-core` exposes it to its users with an `api` dependency, so modules that use `rloaded-core` see it without declaring anything. The message types come next, in the same module.

## Alternatives

- **Message types in `rloaded-core`.** The runtime mod and the launcher would pull the download and install code into every place that only needs a message.
- **A second result type in `rloaded-protocol`.** Two ways to express the same thing, converted back and forth at every boundary.
- **A module for `Outcome` alone.** One more module for one file, with nothing else planned in it.

## Consequences

- Every file that used `Outcome` imports it from its new package; the change is mechanical.
- `rloaded-protocol` runs in the game too, so it follows the library versions of ADR 0019 and stays free of Minecraft and Fabric classes.

## Enforcement

Review, for the dependency direction: Gradle refuses a cycle between `rloaded-core` and `rloaded-protocol` if one is ever added. `OutcomeTest` covers `Outcome`'s functions, with Kover's 85 % line coverage on the module.
