# ADR 0022: Client game tests run a real client against a server in its process

- Status: Accepted
- Date: 2026-10-30

## Context

Part of what Rloaded does happens on the client: the game drops its level when the server sends it back to the configuration phase, it receives registries again, and later it installs mods before the world exists. Server game tests cannot see any of that. ADR 0013 relied on the client dropping its level, read in the 26.3 sources, and asked for a client test to prove it.

Fabric API ships a client game test module, `fabric-client-gametest-api-v1`, left out of the Fabric API release on Modrinth like the server one. Read in version 6.0.11, the one Fabric API 0.165.0 depends on, on 2026-10-30: tests implement `FabricClientGameTest` and run when the client starts with `-Dfabric.client.gametest`; `TestWorldBuilder.createServer` starts a dedicated server inside the client's process and connects to it. That server needs an accepted Minecraft EULA in its directory. A failing test ends the client with an exception, so a non-zero exit code; a client that runs no test exits with 0.

## Decision

`:rloaded-game-tests:clientGameTest` installs the pinned client with the launcher's own `GameInstaller` and `ClientCommand`, into a cache under the build directory, and starts it in a fresh run directory with the agent, the runtime mod, the game test mod and Fabric's two game test modules from Fabric's Maven repository. It forces OpenGL with `--graphicsBackend opengl`, which every runner and virtual display offers. It writes `eula.txt` in the run directory: the project owner accepted the EULA for these test directories on 2026-10-30. Each client test appends its name to a report file once it passed; the task fails unless the client exited with 0 and the report lists at least one test.

CI runs it in a `client-game-test` job on Ubuntu, under `xvfb-run` with Mesa's software OpenGL.

The first test, `ReconfigurationClientGameTest`, spawns an armor stand next to the player, sends the player to the configuration phase with `switchToConfig` and back with `returnToWorld`, as the debug `config` command does, and checks that the client has a new level and a new armor stand object for the same entity. Checked on 2026-10-30 on macOS with the pinned JetBrains Runtime, Fabric Loader 0.20.1, Fabric API 0.165.0 and Minecraft 26.3: it passed, and it failed with a timeout when the reconfiguration was left out.

## Alternatives

- **A separate server process** started by the harness. Needed when the client and the server must not share memory, such as checking registry ids (issue #14); the in-process server is enough for what the client does with its own objects, and Fabric manages its lifecycle.
- **Fabric Loom's client game test run.** It runs from the development environment, not from the launcher's install on the pinned runtime with the agent.
- **Skipping the EULA with `fabric-api.gametest`.** That property turns the server into a headless game test server, with no client connection.

## Consequences

- The client and the in-process server share registries, so tests about what the client receives from the server need a separate process.
- The job installs `xvfb` and Mesa from Ubuntu's packages, which are not pinned like the rest.
- The Ubuntu job draws with a software renderer; tests must not depend on rendering speed.

## Enforcement

The `client-game-test` CI job runs `:rloaded-game-tests:clientGameTest`. `ClientTestCommandTest` checks the command the harness builds.
