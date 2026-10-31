# ADR 0039: An add is tried on a throwaway server before it is accepted

- Status: Accepted
- Date: 2026-10-31

## Context

The static rules (ADR 0034), the reputation guard (ADR 0037) and the compatibility database (ADR 0038) refuse what can be judged without running a mod. A mod that throws in its initializer, logs errors once its blocks exist, or stalls the server passes all three. The only way to see that before players do is to run it on a server nobody plays on, with the mods the real server runs.

A running server cannot build a server command line by itself: the pinned Java runtime, the agent, the classpath and the base mods are chosen by the bootstrap (`ServerCommand`, ADR 0032), which starts the game and only waits for it to exit. Copying the running JVM's own arguments (`RuntimeMXBean.getInputArguments()`) would also copy whatever a harness or an admin added, such as a debugger port or Fabric's game test switch.

Read in the Minecraft 26.3 sources on 2026-10-31: `DedicatedServer.initServer` binds the game port with `ServerConnectionListener.startTcpServerListener` and stops when the port is taken; `MinecraftServer` pauses ticking after `pause-when-empty-seconds` (60 by default) without players, and never when it is 0; `/reload` reports a failure as a `warn` line with the exception. An item's data components are bound by `ReloadableServerResources.updateComponentsAndStaticRegistryTags`, that is at start and at each data pack reload: until then, `new ItemStack(item)` for an item registered later throws `NullPointerException: Components not bound yet`. Fabric API's `ServerLifecycleEvents.END_DATA_PACK_RELOAD` says whether a reload succeeded.

## Decision

- **Launch description.** The bootstrap describes how it starts the server, as `ServerLaunch` (`rloaded-admin`): Java, memory, agent, mods (the base set, the runtime mod and the lockfile's mods), classpath and game properties. It writes it to `rloaded/server-launch.json` at each start and passes its path as `rloaded.server-launch`. `ServerLaunch.command()` is the only builder of a server command line; the bootstrap, the staging run and the test harnesses all use it.
- **Staging run.** After the jar rules and before the lockfile is written, `AdmissionService` hands the jars the server would load (not those for the client only) to `StagingServer`. It empties `rloaded/staging`, copies the admin's `eula.txt`, writes a `server.properties` for a flat world without structures, offline, on the loopback address, never pausing, and starts the launch with three more properties: the candidate jars, the report file, and `rloaded.staging.enabled=false`, so a staging server never stages. Its console goes to `rloaded/staging/console.log`.
- **Inside the staging server** (runtime mod, `staging` package): the game port is never opened (a mixin skips `startTcpServerListener`), so a staging server runs next to the real one. Once started, it loads the candidates with `HotAdd`, the way mods will be added while players are connected, and reloads the data packs, which binds the new items' components and loads the new mods' data. It then ticks 200 times, places every new block, spawns every new summonable entity type, drops every new item as an item entity (there is no player to give it to), runs `/reload`, ticks 200 more times, writes its report and stops. New content is what the registries hold after the add and did not before. It fails on any exception, any `error` line, any line carrying an exception, a failed reload, or a tick longer than 2 seconds, from the add on. It stops itself when the server that started it exits.
- **Verdict.** The add is refused when the staging server fails, stops without a report, or runs longer than `staging.timeoutSeconds` (180 by default, 30 to 3600; it is then killed with its children). The refusal lists the failures, at most 20, each at most 500 characters, and names `rloaded/staging/logs/latest.log`, which stays until the next add.
- **Settings.** `[staging]` in `rloaded-server.toml`: `enabled` (true) and `timeoutSeconds`. A dedicated server with staging enabled and no launch description refuses to start. With staging off, the server says in its log at start that adds are not tried first.

Checked on 2026-10-31. `stagingTest` (CI job `game-test`) tries content-basic and mixin-loaded-class on a staging server while port 25565 is taken: both loaded, 1 block placed, 1 item dropped, 401 ticks, no failure, about 20 seconds from start to stop. With the mixin left out of the configuration, the staging server logged "FAILED TO BIND TO PORT", stopped without a report, and the test failed. Without the reload after the add, dropping content-basic's item failed with "Components not bound yet". `AdminAddGameTest` adds a mod through a staging server and has throws-on-init refused with "loading the mods threw java.lang.IllegalStateException: throws-on-init fails as it starts, on purpose" and the path of the log; with that failure ignored by the staging server, the mod was added and the test failed.

## Alternatives

- **Dry run in the bootstrap.** The bootstrap only waits for the game to exit; asking it to stage would need a channel between the two processes, both ways, for no gain over handing the server its own launch.
- **Starting the staging server with the candidates in `fabric.addMods`.** That tests a cold start, not the add while the game runs that adds become next; `HotAdd` is the path they will take.
- **A Fabric game test server for staging.** Modrinth's Fabric API does not ship `fabric-gametest-api-v1` (seen 2026-10-30), so production servers cannot start one.
- **Binding the staging server to a free port on the loopback address.** It still opens a port, and the vanilla default 25565 is often the real server's.

## Consequences

- An add takes about a minute longer, and the host needs memory for a second server while it runs; the staging server gets the same `-Xmx` as the real one, though a fresh flat world uses far less.
- Until adds take effect while the game runs, the staging server starts from the mods the real server runs, not from the lockfile: a mod added since the last restart is not on the staging server.
- Staging covers server code. Client code is left to the jar rules, the compatibility database and the client tests.
- An `error` line from a mod already installed fails an add during the run, even if the new mod did not cause it. The admin reads the log; refusing is the safe side.
- No player joins the staging server: code that runs only with a player (inventories, menus, joining) is not exercised.
- Adding mods while the game runs must reload the data packs after `HotAdd` too, or the new items cannot be used.

## Enforcement

`ServerLaunchTest`, `StagingServerTest` (a real child process for the report, the missing report and the timeout), `StagingSettingsTest`, `StagingReportTest`, `AdmissionServiceTest`, `ServerConfigTest`, `BootstrapTest`; `stagingTest` and `AdminAddGameTest` in the `game-test` CI job.
