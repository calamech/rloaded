# ADR 0027: The runtime mod nests Rloaded's Kotlin libraries and Ktor

- Status: Accepted
- Date: 2026-10-30

## Context

The runtime mod is about to speak the join protocol: decode and encode messages with `rloaded-protocol`, check entries against Modrinth with `rloaded-modrinth`, and download mods with the verified downloads of `rloaded-core`, over Ktor. Those are Kotlin libraries of the main build. The mod is a separate Loom build (ADR 0011), and players' games load it from a single jar.

Checked on 2026-10-30 with Gradle 9.8.2 and Loom 1.19.3:

- A dependency on `rloaded-protocol` in a Loom configuration fails while configuring: Loom resolves `mainRuntimeDependencyResolve` then, before the main build can substitute its projects ("Could not find").
- A configuration Loom does not know resolves when tasks run, but an included build does not see the projects of the build that includes it ("Could not find rloaded:rloaded-protocol"). With `includeBuild("..")` in the runtime's settings, Gradle substitutes them.
- Loom's `include` configuration, which nests jars in a mod, is resolved while configuring as well.
- The base set already loads Fabric Language Kotlin 1.14.2, which nests the Kotlin standard library 2.4.21, kotlinx-coroutines 1.11.0, kotlinx-serialization 1.11.0 (core, JSON, CBOR), kotlinx-io 0.9.1, atomicfu and kotlinx-datetime. The main build uses the same versions (ADR 0019). Minecraft ships SLF4J.

## Decision

- The runtime's settings include the main build, and the Kotlin library conventions set the group `io.github.calamech.rloaded`, so the mod asks for `io.github.calamech.rloaded:rloaded-modrinth` like any library.
- A `libraries` configuration that Loom does not know holds that dependency. Its resolved files are on the compile class path of the mod and of the game test mod.
- The `nestLibraries` task copies each library jar into `META-INF/jars/`, adds a generated `fabric.mod.json` with the id `<group>_<name>` (dots as underscores) and the library's version, the way Loom wraps the jars it nests, and the mod's `fabric.mod.json` lists them. Rloaded's modules, Ktor and the Apache Commons libraries that `rloaded-core` uses are nested: 21 jars. The Kotlin standard library, kotlinx, JetBrains annotations and SLF4J are left out, except `kotlinx-coroutines-slf4j`, which Fabric Language Kotlin does not ship. The mod declares a dependency on `fabric-language-kotlin`.
- The mod's code stays in Java for now; it calls the libraries' suspending functions through coroutine builders, and the install flow will give Java a `CompletableFuture` API.

Checked on 2026-10-30 with `NestedLibrariesGameTest` on the game test server: Fabric Loader lists the nested protocol library, the protocol codec encodes and decodes a `Progress` message, and `KtorHttp` fetches a body from a server on the loopback interface. The client game tests and the registry sync check, which load the same mod, passed. With Rloaded's modules left out of the nested jars, the server stopped at startup on `NoClassDefFoundError: io/github/calamech/rloaded/core/download/KtorHttp`.

## Alternatives

- **The libraries' classes copied into the mod jar.** Two mods shipping the same library would then define the same classes twice; nested mods let Fabric Loader keep the newest version once.
- **Nesting the Kotlin standard library and kotlinx too.** Fabric Language Kotlin already loads them in the same versions; a second copy only adds size.
- **A JDK `HttpClient` implementation of `Http` in the mod.** It would duplicate the URL policy, limits and redirect checks that `KtorHttp` already enforces and tests.

## Consequences

- The mod jar is now 5.5 MiB, measured on 2026-10-30; the nested jars are 6.1 MiB uncompressed, close to half of it Apache Commons, which only `rloaded-core`'s archive extraction needs. `rloaded-core` also brings its Mojang and runtime installer code.
- The runtime build depends on the main build and the main build on the runtime build. Running the runtime build alone (`./gradlew -p rloaded-runtime`) still works.
- When Fabric Language Kotlin changes the versions it ships, the main build follows (ADR 0019); otherwise the nested libraries run against other kotlinx versions than they were built with. `NestedLibrariesGameTest` exercises them with the pinned base set.
- The runtime build has no formatting or lint step yet, for Java or Kotlin: issue #51. Kotlin code waits for it.

## Enforcement

`NestedLibrariesGameTest`, run by the `game-test` CI job.
