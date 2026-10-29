# ADR 0011: Building the runtime mod

- Status: Accepted
- Date: 2026-10-29

## Context

The runtime mod is a Fabric mod for Minecraft 26.3, built with Fabric Loom. The main build refuses project repositories (`FAIL_ON_PROJECT_REPOS`) and verifies every dependency checksum. Loom, checked with version 1.19.3 on Gradle 9.8.2, adds project repositories of its own, some of them local file repositories holding game jars it builds.

## Decision

- `rloaded-runtime` is a separate Gradle build, included by the main build with `includeBuild`. Its settings let Loom add its repositories; the main build keeps refusing them. It reads the same version catalog.
- The main build uses the mod jar by its coordinates, `io.github.calamech.rloaded:rloaded-runtime`, which the included build substitutes. The launcher and the bootstrap embed it next to the agent jar.
- The mod does not compile against the agent. Loom resolves the mod's dependencies while configuring, before the main build's projects can be substituted, so the mod calls the agent's two public methods by name, `AgentState.instrumentation()` and `ClassRedefinition.current()`, in `AgentBridge`. A missing method is a packaging defect and throws.
- Dependency verification covers the included build. Loom builds `net.minecraft:minecraft-*` jars locally from Mojang's game jars, which it checks against Mojang's SHA-1; their checksum differs between machines (a checksum recorded on macOS failed on the Linux runner). They are trusted by name in `gradle/verification-metadata.xml`, with that reason. Every other artifact stays verified.
- `RuntimePinsTest` keeps the Minecraft, Fabric Loader and Fabric API versions of the catalog equal to the pinned runtime, since the mod is built against the first and runs with the second.

## Alternatives

- **The mod as a project of the main build.** Loom fails the build there: "repository 'LoomLocalMinecraft' was added by plugin class 'net.fabricmc.loom.LoomRepositoryPlugin'".
- **Moving the agent into the runtime build.** The Java conventions of `build-logic` would then share a build with Loom, and the main build would depend on the runtime build for the agent too. Reaching two methods by name is the smaller change.

## Consequences

- Configuring the build downloads the game jars once per machine, for Loom.
- Renaming or changing the signature of `AgentState.instrumentation()` or `ClassRedefinition.current()` breaks the mod at runtime, not at compile time. The client and server checks below catch it.

## Enforcement

- The root `check` task runs the runtime build's `check`.
- Checked by hand on 2026-10-29 on macOS ARM64: the client started with the mod logs `Runtime started with the agent; enhanced class redefinition: ACTIVE`, and the server loads it among 57 mods.
