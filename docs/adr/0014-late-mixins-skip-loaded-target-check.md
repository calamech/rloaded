# ADR 0014: Late mixin configurations skip Mixin's loaded target check

- Status: Accepted
- Date: 2026-10-30

## Context

Mixin refuses a mixin whose target class is already loaded, because Mixin alone could not apply it any more. `LateMixins` (ADR 0012) applies a configuration after startup and redefines its loaded targets itself, so the refusal is wrong for it. With a `required` configuration, as most mods ship, Mixin throws `MixinTargetAlreadyLoadedException` and nothing applies. Without `required`, it logs `Critical problem: … target … was loaded too early.` at error level, then applies the mixin.

Read in sponge-mixin 0.17.5+mixin.0.8.7, decompiled with Vineflower 1.12.0 on 2026-10-30:

- The check is in `MixinInfo.readDeclaredTargets`, called from the `MixinInfo` constructor when `MixinProcessor.select` prepares the configuration. It asks `tracker.isClassLoaded(target)`, where `tracker` is the class tracker of the configuration's own service (`MixinConfig.service`, set when the configuration is created).
- No other class of Mixin calls `IClassTracker.isClassLoaded`; the two others that name it implement it for other launchers.
- On Fabric Loader 0.20.1, the service is `MixinServiceKnot`, which is its own class tracker and asks Knot whether the class is loaded.

## Decision

Right after registering a configuration, and before selecting it, `LateMixins` replaces that configuration's `service` field with a proxy of the same service. The proxy forwards every call, except `getClassTracker`, which returns a tracker that reports every class as not loaded and forwards its two other methods. Only the late configuration sees this tracker; configurations loaded at startup keep the check.

Checked on 2026-10-30 with `LateMixinGameTest` on the production stack (JetBrains Runtime 25.0.4.1 build 508.40, the agent, Fabric Loader 0.20.1, Fabric API 0.165.0, Minecraft 26.3 game test server). The test configuration is now `required`, and the test records every error-level line written to the game log while `LateMixins.apply` runs. Before the change, the test failed on `MixinTargetAlreadyLoadedException`; with the configuration not required, it failed on the `Critical problem` line. After the change, it passed in 20 server runs out of 20 in a row (193 s in total), each on a fresh server, with no `loaded too early` line in any server log.

## Alternatives

- **Clearing `required` on the configuration during selection.** Mixin still logs the `Critical problem` line at error level (same method, read on 2026-10-30), and every other error of a required configuration would become a log line instead of a failure.
- **Mixin's global `IGNORE_REQUIRED` option.** Same log line, and it applies to every configuration of the game, not only late ones.
- **Replacing the global Mixin service while the configuration is created.** It reaches the same field with a wider scope: any configuration created on another thread at that moment would get the tracker too.
- **Redefining `MixinInfo` with the agent to drop the check.** It patches Mixin's bytecode for every configuration, and a change of that method in a later Mixin breaks the patch without a clear error.
- **Filtering the log line.** It hides the message but not the exception of a required configuration.

## Consequences

- Mixin no longer warns about a late mixin whose target is loaded. `LateMixins` takes over that duty: it redefines every loaded target of the configuration, and a redefinition error propagates out of `apply`.
- The engine now also depends on the private field `MixinConfig.service`, and on `isClassLoaded` being used only by this check. A renamed field fails the game test with `NoSuchFieldException`; a new use of `isClassLoaded` would need a review of the Mixin update, which must run the game tests anyway (ADR 0012).

## Enforcement

`LateMixinGameTest`, in the runtime mod's `gametest` source set, run by the `game-test` CI job: it applies a `required` configuration to a loaded class and fails on any error-level log line written meanwhile.
