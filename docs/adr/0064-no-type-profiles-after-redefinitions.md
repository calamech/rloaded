# ADR 0064: Games and servers run without JIT type profiles, which outlive enhanced redefinitions

- Status: Accepted
- Date: 2026-11-02

## Context

The `game-test` job of `main` failed once on 2026-11-01 because the server's JVM aborted in the C2 compiler, a few seconds after tests redefined loaded classes (#114):

```
#  Internal Error (ciMethod.cpp:749)
#  guarantee(CURRENT_THREAD_ENV->jvmti_state_changed()) failed: old method not detected
# V  [libjvm.so+0x6a4a73]  ciMethod::find_monomorphic_target(...)
```

Measured on 2026-11-02 with the pinned JetBrains Runtime 25.0.4.1 build 508.40:

- **Cold game test server, Linux x64**: 1 abort in 120 runs of `gameTest` on 20 GitHub runners, with the same report. The redefined classes were `ChestBlockEntity`, `MinecraftServer`, `GameTestServer` and a test class; C2 was compiling `AbstractConsecutiveExecutor.run`.
- **Warm server, macOS ARM64**: a server that generated 900 forced chunks and ticked 300 villagers for 3,000 ticks, then had `MinecraftServer` and `DedicatedServer`, or `ServerLevel`, redefined once through `LateMixins.redefineLoaded`, as an add with a mixin on those classes does. The JVM aborted with the same report in 11 trials out of 11, within the next 1,500 ticks.
- **Standalone program**: five classes, no game, in `RedefinitionProbe.kt`. A single enhanced redefinition of a class aborts the JVM in 3 runs out of 3, and redefining it with its own bytes does too, in 5 runs out of 5. With the standard redefinition of HotSpot (without `-XX:+AllowEnhancedClassRedefinition`), 0 runs out of 5 abort.

Read in the sources of that build (`ciMethod.cpp`, `ciMethodData.cpp`, `jvmtiEnhancedRedefineClasses.cpp`): an enhanced redefinition gives the class, and each subclass, a new `Klass`, and the old one stays loaded. The receiver type profiles of the methods that called it still name the old `Klass`, and nothing cleans them: the `MethodDataCleaner` call is commented out in `jvmtiEnhancedRedefineClasses.cpp`. When C2 later inlines a call for that profiled receiver, it resolves the calls inside the inlined method against the old class, finds a method marked old, and the guarantee aborts the JVM because no redefinition happened during that compilation. The builds 508.41 and 635.79, published since, change none of this code.

Every add whose mods have a mixin or a class tweaker on a loaded class redefines it (ADR 0012, ADR 0061), on the server and on each player's game, so on a server that has been running the abort is close to certain.

## Decision

- Every game and server Rloaded starts gets `-XX:-UseTypeProfile`, next to `-XX:+AllowEnhancedClassRedefinition`. C2 then never inlines a call for a profiled receiver type, so it never meets the old class. Both options live in `RuntimeOptions` (core), which the server launch and the client command use.
- `redefinitionTest` runs the standalone program on the pinned runtime with those options, where it must finish, and without `-XX:-UseTypeProfile`, where it must still abort. When a runtime update stops the abort, the check fails and says to remove the option.

With the option, on 2026-11-02: the standalone program finished 5 runs out of 5, `gameTest` passed 120 runs out of 120 on Linux x64, and the warm server survived 8 redefinitions out of 8.

## Alternatives

- **No option, waiting for a fixed runtime.** Until then, most adds would end the server or the players' games.
- **`-XX:TypeProfileWidth=0`.** It avoids the abort too, but the same server workload took 34 to 44 % longer per tick, against 30 to 36 % with `-XX:-UseTypeProfile`.
- **`-XX:-UseBimorphicInlining`, `-XX:TypeProfileLevel=0`, `-XX:-UseTypeSpeculation`.** The standalone program aborted 5 runs out of 5 with each.
- **A runtime built with the cleaning restored.** Building, signing and hosting a runtime for every platform is a cost we do not take on; the pinned runtime comes from JetBrains (ADR 0006).

## Consequences

- The server spends more time per tick. On the warm server workload above, macOS ARM64, 4 alternating runs each: 9.7 ms per tick while chunks generate and 8.4 ms after, without the option; 12.7 ms and 11.4 ms with it (+30 % and +36 %). The cost on players' frame rate is not measured yet.
- The option goes when a JetBrains Runtime fixes the bug and passes `redefinitionTest` without it (#114).

## Enforcement

`redefinitionTest` in the `game-test` CI job, `ServerLaunchTest`, `GameInstallerTest` and `BootstrapTest` (the options in the launch commands).
