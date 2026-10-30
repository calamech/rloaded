# Rloaded

Rloaded lets friends play on a Minecraft server where the admin adds Fabric mods on the fly. Players install nothing by hand, never restart their game, and see the new content right away.

> **Status:** early development. The launcher starts Minecraft 26.3 with Fabric on the JetBrains Runtime, and the server jar starts a Fabric server the same way. Adding mods during play is not there yet.

Design decisions are recorded in [docs/adr](docs/adr/).

## What is in the repository

| Module | What it does |
| --- | --- |
| `rloaded-protocol` | The messages between server, game and launcher, and types shared by every module such as `Outcome` and `Checksum` |
| `rloaded-core` | Verified downloads, Mojang metadata, the pinned runtime, the JetBrains Runtime installer |
| `rloaded-agent` | The Java agent started with the game |
| `rloaded-runtime` | The Fabric mod loaded in every game and server (a separate Loom build) |
| `rloaded-launcher` | `launcher-core`, which installs and starts the game, and `launcher-app`, the desktop window |
| `rloaded-server-bootstrap` | `rloaded-server.jar`, which sets up and starts a server |
| `rloaded-game-tests` | Game tests on a real game test server, and checks with real clients |

## Development

You need a JDK 17 or newer to start Gradle. Gradle then downloads the JetBrains Runtime versions the build uses.

```sh
./gradlew check                                  # build and unit tests
./gradlew :rloaded-game-tests:gameTest           # game tests, downloads the pinned server
./gradlew :rloaded-game-tests:registrySyncTest   # a server and two clients agree on raw ids after an add
./gradlew :rloaded-launcher:launcher-app:run     # the launcher window
./gradlew :rloaded-server-bootstrap:serverJar    # rloaded-server-bootstrap/build/libs/rloaded-server.jar
```

The launcher keeps its data in `~/Library/Application Support/Rloaded` on macOS, `%APPDATA%\Rloaded` on Windows and `$XDG_DATA_HOME/rloaded` on Linux.

To try a server, start `rloaded-server.jar` in an empty folder with Java 21 or newer. The first start sets the folder up and asks you to accept the Minecraft EULA in `eula.txt`. Start it again with `--dev` to accept the launcher's offline profiles on this machine.

Before the first push, set up the Git identity and the pre-push hook as described in [ADR 0003](docs/adr/0003-git-identity.md).

## License

[MIT](LICENSE)

Rloaded is not an official Minecraft product. It is not approved by or associated with Mojang or Microsoft.
