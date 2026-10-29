# ADR 0008: Server bootstrap

- Status: Accepted
- Date: 2026-10-29

## Context

An admin should run one jar, on a Mac for tests or at a hosting provider, and get a Fabric server on the pinned JetBrains Runtime with the Rloaded agent. Hosting providers offer a Java 21 or newer, not necessarily the JetBrains Runtime.

## Decision

`rloaded-server.jar` (module `rloaded-server-bootstrap`) is a single jar with its dependencies inside, compiled to Java 21 bytecode.

- **First start**: it writes `rloaded-server.toml` with its defaults and `eula.txt` with `eula=false`, prints the next steps and exits. It never accepts the Minecraft EULA for the admin.
- **Settings**: `rloaded-server.toml` is read with ktoml 0.7.1 (MIT, built on kotlinx.serialization). Unknown keys are refused, and values are checked against their bounds. For now the file holds `server.name` and `server.maxMemoryMiB`; later settings join it when the code that uses them exists.
- **Later starts**: it checks the EULA, installs the pinned runtime, the server jar, Fabric Loader and the base set into `rloaded/`, writes the agent jar it carries to `rloaded/agent/`, then starts `KnotServer` with `-XX:+AllowEnhancedClassRedefinition` and the agent. The server shares the bootstrap's console.
- **Restarts**: exit code 75 means "start again". At most 10 restarts in a row, then the bootstrap stops.
- **`--dev`**: sets `online-mode=false` for offline development accounts. The bind address must be loopback or private; an empty one becomes `127.0.0.1`, a public one is refused.
- Ktor's SLF4J logging goes to `slf4j-nop`; the bootstrap reports failures itself.

Checked on 2026-10-29 on macOS ARM64: the jar runs on Java 21.0.1. With the real downloads, the server reports `[Rloaded agent] attached; enhanced class redefinition: active`, Fabric Loader 0.20.1 reads Mojang's bundler jar and loads 56 mods, and Minecraft stops at its EULA check.

## Alternatives

- **Jackson's TOML module.** Mature, but brings Jackson databind and reflection for one small file.
- **tomlj.** No kotlinx.serialization support, so the strict schema would be written by hand.
- **A Gradle shadow plugin** for the single jar. The built-in `Jar` task is enough, since nothing needs relocating.

## Consequences

- The bootstrap downloads about 150 MB on first install: the runtime, the server jar and the mods.
- ktoml is a 0.x library. Its use is limited to `ServerConfig`, so replacing it touches one file.

## Enforcement

- `BootstrapTest`: first start without EULA acceptance, unknown arguments, refused EULA, unknown settings, the server command, restarts and their limit, dev mode on local, private and public addresses, and failed installs.
- `ServerConfigTest`: defaults, values, unknown keys and bounds.
