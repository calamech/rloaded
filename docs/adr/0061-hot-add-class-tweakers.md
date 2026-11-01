# ADR 0061: A mod's access widener applies to classes the game already loaded

- Status: Accepted
- Date: 2026-11-01

## Context

`HotAdd` refused any mod declaring an access widener or class tweaker (ADR 0026, #48). Many mods on Modrinth declare one, to reach private members of game classes. Fabric applies them to each class as it loads, so the game classes loaded before an add would keep their old access, and the mod's code would fail on its first access.

Read in Fabric Loader 0.20.1 on 2026-11-01:

- `FabricLoaderImpl.loadClassTweakers` reads each mod's file (`accessWidener` in `fabric.mod.json`, `classTweaker` or `accessWidener` header) into one `ClassTweaker`, in the runtime namespace.
- `FabricTransformer.transform`, part of the bytes `KnotClassDelegate.getPreMixinClassBytes` returns, applies that `ClassTweaker` to every class it targets.
- A class tweaker can widen access (`accessible`, `extendable`, `mutable`), inject interfaces, and, from `classTweaker v2`, add constants to enums (`extend-enum`).

## Decision

- Before anything is registered, `HotAdd` reads the mod's class tweaker with Fabric's reader into a separate `ClassTweaker`: a missing or unreadable file refuses the add with its name, and so does an enum extension ("it adds constants to the enum net.minecraft.world.Difficulty, which cannot be done in a running game").
- After registering the mods, `LoaderBridge.applyClassTweaker` gives each file to the game's `ClassTweaker`, so classes that load later get it from Fabric itself.
- `LateMixins.redefineLoaded` then redefines every loaded class it targets from its pre-Mixin bytes, which now include the class tweaker, through the Mixin transformer, under the transformer's lock (ADR 0055), before the mods' own mixins and entrypoints.

Checked on 2026-11-01 on the game test server (JetBrains Runtime 25.0.4.1 build 508.40 with `-XX:+AllowEnhancedClassRedefinition`) by `ClassTweakerGameTest`: a mod widens the private field `MinecraftServer.LOGGER`, a class loaded at startup with mixins of its own, and its initializer reads it. After the add the field is public and the initializer saw it. With the redefinition skipped, the initializer fails and so does the test. `HotAddRefusalGameTest` checks the refusals of a missing file and of an enum extension.

## Alternatives

- **Applying class tweakers only to classes that load after the add.** Mods widen the game's core classes, nearly all loaded at startup; their code would fail at once.
- **Restarting the game for such mods.** It works, but it is what adds to a running game exist to avoid.
- **Supporting enum extensions.** An enum's constants live in its static state and in every switch compiled against it; adding one to a loaded enum would leave both out of date.

## Consequences

- Changing a loaded class's access needs enhanced class redefinition, which every game Rloaded starts already has (ADR 0012).
- Injected interfaces go through the same redefinition, like interfaces that mixins add; no test covers them yet.
- Once given to the game, a class tweaker stays even if a later step of the add fails; that failure already asks for a restart.

## Enforcement

`ClassTweakerGameTest` and `HotAddRefusalGameTest`, in the runtime mod's `gametest` source set, run by the `game-test` CI job.
