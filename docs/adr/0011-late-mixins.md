# ADR 0011: Mixins applied after startup

- Status: Accepted
- Date: 2026-10-29

## Context

Most Fabric mods use mixins. A mod added while the game runs brings mixins whose target classes are often loaded already. Mixin itself only prepares its configurations before the first class is transformed, and the JVM can only redefine a loaded class without changing its shape, unless it runs on the JetBrains Runtime with `-XX:+AllowEnhancedClassRedefinition`.

## Decision

`LateMixins` in the runtime mod applies a Mixin configuration after startup:

1. Register the configuration with `Mixins.addConfiguration`.
2. Call the private `MixinProcessor.select(MixinEnvironment)` through reflection. `MixinProcessor.checkSelect` only selects pending configurations while `transformedCount == 0`, that is before the first class is transformed, so a late configuration is never selected on its own.
3. Read the configuration's targets from the processor's `configs` list, where selected configurations move.
4. For each target that is already loaded, take its pre-Mixin bytes from Knot (`getPreMixinClassBytes`: the jar bytes with Fabric's own transformations), run them through the Mixin transformer so every mixin that targets the class applies, old and new, and redefine the class with the agent's `Instrumentation`.

Targets that are not loaded yet are transformed normally when they load.

Checked on 2026-10-29 with a game test on the production stack (JetBrains Runtime 25.0.4.1 build 508.40, the agent, Fabric Loader 0.20.1, Fabric API 0.165.0, Minecraft 26.3 game test server): a mixin registered during a test adds an interface, a method and a field to a class loaded at startup. An instance created before the change implements the interface and answers the new method. The new field reads 0 on that instance, as constructors are not replayed. The game test passed in 50 server runs out of 50 in a row (439 s in total), each run on a fresh server; repeated adds within one session are not measured yet.

## Alternatives

- **Restarting the game** to apply new mixins. It is what Rloaded exists to avoid.
- **Refusing mods whose mixins target loaded classes.** Kept as the fallback if this approach breaks on a later Mixin or Fabric Loader version.
- **Patching Mixin's processor with a mixin of our own** instead of reflection. Both reach the same private members; reflection keeps the change in one class and fails loudly if a member is renamed.

## Consequences

- Mixin logs `Critical problem: … target … was loaded too early.` for every late mixin whose target is loaded, and refuses outright when the configuration is `required`. The engine has to clear that check before late mixins can come from real mods, whose configurations are often required: issue #12.
- New fields hold their default value on objects that already exist, which is why the server cycles chunks after an add: issue #13.
- The engine depends on private members of Mixin 0.8.7 (`MixinTransformer.processor`, `MixinProcessor.select`, `MixinProcessor.configs`) and of Fabric Loader 0.20.1 (`Knot.classLoader`). Each update of either must run the game tests.

## Enforcement

`LateMixinGameTest`, in the runtime mod's `gametest` source set, run on the game test server with the agent.
