# ADR 0019: Kotlin libraries follow the versions Fabric Language Kotlin ships

- Status: Accepted
- Date: 2026-10-30

## Context

Kotlin code is going to run inside the game: the network messages shared by the server, the client and the launcher, and the runtime's own logic. In the game, Kotlin and its libraries come from Fabric Language Kotlin, which every Rloaded game and server loads. A mod that needs another version of one of those libraries cannot load next to it, and code compiled against a newer version can call methods the game does not have.

Read on 2026-10-30 from the pinned `fabric-language-kotlin-1.14.2+kotlin.2.4.21.jar`, in its `META-INF/jars`: Kotlin 2.4.21, kotlinx-coroutines 1.11.0, kotlinx-serialization (core, json and cbor) 1.11.0, kotlinx-io 0.9.1, kotlinx-datetime 0.8.0 and atomicfu 0.33.0.

The build used kotlinx-serialization 1.12.0. Ktor 3.6.0, which the Modrinth client will use in the game too, requires kotlinx-serialization 1.11.0, kotlinx-coroutines 1.11.0 and kotlinx-io 0.9.1 in its published metadata, read the same day.

## Decision

The build pins Kotlin and the kotlinx libraries to the versions the pinned Fabric Language Kotlin ships. kotlinx-serialization goes from 1.12.0 to 1.11.0; Kotlin 2.4.21 and kotlinx-coroutines 1.11.0 already match. When the pinned Fabric Language Kotlin changes, these versions change with it.

## Alternatives

- **Keeping the newest releases**, and bundling them in the runtime mod. Two copies of the same library in the game, one of them shadowing the other, fail in ways that depend on load order.
- **Keeping Kotlin out of the game**, in Java only. The message types would then be written twice, once per language, which is what the shared protocol module avoids.

## Consequences

- Library updates wait for a Fabric Language Kotlin release that ships them. A Renovate pull request that bumps one of them fails `BaseSetLibrariesTest` until then.
- Modules that never run in the game, such as the launcher, follow the same versions, as the build has one version of each library.

## Enforcement

`BaseSetLibrariesTest` in `rloaded-core` fails when the pinned Fabric Language Kotlin changes, and when the Kotlin, kotlinx-coroutines or kotlinx-serialization version on the build's classpath differs from what that jar ships.
