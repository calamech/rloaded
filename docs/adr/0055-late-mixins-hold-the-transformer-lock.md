# ADR 0055: Late mixins hold the Mixin transformer's lock

- Status: Accepted
- Date: 2026-11-01

## Context

`LateMixins.apply` (ADR 0012, ADR 0014) registers a configuration, selects it through the processor's private `select`, then transforms its loaded targets. It ran that from the add's thread while the game kept loading classes on others: the network, the download coroutines, the game threads. Every class Knot loads goes through Mixin's transformer.

Read in sponge-mixin 0.17.5+mixin.0.8.7 (the jar the game test server loads), decompiled with Vineflower on 2026-11-01:

- Every entry point of `MixinTransformer` that transforms or generates a class (`transformClass`, `couldTransformClass`, `generateClass`) is `synchronized` on the transformer, then calls `MixinProcessor.applyMixins` or `couldTransformClass`, which are `synchronized` on the processor. Only the transformer calls them.
- Both iterate the processor's `configs` list, which `select` changes (`addAll`, then `sort`).
- `checkSelect`, run before each transformation, selects pending configurations itself when `Mixins.getUnvisitedCount()` is not zero and no class was transformed since the last selection. `select` resets that count, so after one late configuration the next one is selectable by any thread.

CI saw it once on 2026-10-31: an add failed on `Mixin transformation of …ArtifactStore$downloadOnce$2$1$1 failed`, caused by a `ConcurrentModificationException` (#95).

## Decision

`LateMixins.apply` registers, selects and transforms inside `synchronized (transformer)`, the lock every class transformation takes first. The redefinition of the loaded targets stays outside: it changes no Mixin state.

`LateMixinRaceGameTest` reproduces the race on the production stack (JetBrains Runtime 25.0.4.1 build 508.40, the agent, Fabric Loader 0.20.1, Fabric API 0.165.0+26.3, Minecraft 26.3 game test server). Four threads write new classes into a directory added to Knot's class path and load them through the game's class loader, while the test thread applies 300 empty configurations one after the other. Without the lock, it failed in 3 server runs out of 3 on 2026-11-01: twice because another thread had selected the configuration first (`Mixin did not select rloaded-gametest.race-4.mixins.json`), once on the `ConcurrentModificationException` CI saw. With the lock, it passed in 10 server runs out of 10 in a row, each applying the 300 configurations in about 2.2 s while the other threads loaded between 859 and 1,695 classes.

## Alternatives

- **Locking the processor only.** It deadlocked on the first run on 2026-11-01: the add's thread held the processor and loaded a class, which waited for the transformer, held by a coroutine thread waiting for the processor. Mixin always takes the transformer first.
- **Pausing the other threads during an add.** The network and download threads are not the game's to pause, and the game's own threads keep loading classes when they resume anyway.
- **Applying late configurations only on a game with no other thread running.** No such moment exists in a running server or client.

## Consequences

- Classes loaded on other threads wait while the add selects a configuration and transforms its loaded targets. In the test, that is about 7 ms per empty configuration.
- While it holds the transformer, the add's thread may load a class of the added mod, such as its Mixin plugin, and wait for Knot's lock on that class name. Only a thread loading the same class at that moment holds that lock, and nothing else knows a mod that is being added.
- A target class that another thread transformed just before the lock, and defines just after the add looked at the loaded classes, keeps the code it had without the new mixins (#98).

## Enforcement

`LateMixinRaceGameTest`, in the runtime mod's `gametest` source set, run by the `game-test` CI job.
