# ADR 0006: Pinned runtime

- Status: Accepted
- Date: 2026-10-29

## Context

The game, the server and every client must run the same Fabric Loader, the same base mods and a JetBrains Runtime that supports `-XX:+AllowEnhancedClassRedefinition`. Picking "the latest" at launch time would let two players end up with different code, and would let a compromised release reach players silently.

## Decision

Each Rloaded release pins its runtime in `rloaded-core/src/main/resources/io/github/calamech/rloaded/core/runtime/runtime-pins.json`, read by `RuntimePins`:

- **Minecraft 26.3** and **Fabric Loader 0.20.1**, with Fabric's launcher profile libraries (ASM 9.10.1 and sponge-mixin 0.17.5) and their SHA-512 from Fabric's Maven repository. The profile is not fetched at launch.
- **Base set**: Fabric API 0.165.0+26.3 and Fabric Language Kotlin 1.14.2+kotlin.2.4.21, with Modrinth's version ids, file URLs, sizes and SHA-512.
- **JetBrains Runtime 25.0.4.1, build 508.40**, the "vanilla" archive for each supported platform (macOS, Linux and Windows, x86-64 and ARM64), with the SHA-512 JetBrains publishes next to each archive.

Values were recorded on 2026-10-29. The SHA-512 of ASM was checked against Maven Central, and the SHA-1 Fabric lists for sponge-mixin against the downloaded jar.

`JetBrainsRuntimeInstaller` installs the pinned archive with Apache Commons Compress 1.28.0, the only library here that reads tar archives with their symbolic links and file modes. Entries and links that point outside the archive are refused. JetBrains serves the archives through a redirect to `d2xrhe97vsfxuc.cloudfront.net`, which joins the download allowlist.

The pinned runtime was installed from JetBrains on macOS ARM64 on 2026-10-29. `java -XX:+AllowEnhancedClassRedefinition -version` reports `JBR-25.0.4.1+1-508.40-nomod`, and the flag reads `true`.

## Alternatives

- **Fetching Fabric's launcher profile at launch.** It lists SHA-1 for the libraries but not for Fabric Loader itself, and any change on Fabric's side would change what players run.
- **The newest JetBrains Runtime 25 build at launch.** Rejected for the same reason: every player and server must run the same runtime.
- **Pins as Kotlin constants.** A SHA-512 does not fit the 140-character line limit, and a data file is easier to update and review.

## Consequences

- Updating a pinned file is a pull request that changes `runtime-pins.json`, with the new values recorded from their source.
- Builds older than 3 days were preferred for the runtime: 508.41 came out on 2026-10-27, 508.40 on 2026-10-23.
- A source that removes a pinned file breaks installs of that Rloaded release until it is updated.

## Enforcement

- `RuntimePinsTest`: every file is pinned by SHA-512 from an allowed HTTPS origin, every platform has a runtime, and the pins file refuses unknown fields.
- `ArtifactStore` refuses any download whose hash differs from the pin.
- `JetBrainsRuntimeInstallerTest`: macOS, Linux and Windows layouts, symbolic links, archives that climb out, archives without Java, damaged archives and hash mismatches.
