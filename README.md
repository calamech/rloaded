# Rloaded

Rloaded lets friends play on a Minecraft server where the admin adds Fabric mods on the fly. Players install nothing by hand, never restart their game, and see the new content right away.

> **Status:** early development. The launcher starts Minecraft 26.3 with Fabric on the JetBrains Runtime, and the server jar starts a Fabric server the same way. Mods can be added from the server console while players are connected.

Design decisions are recorded in [docs/adr](docs/adr/).

## What is in the repository

| Module | What it does |
| --- | --- |
| `rloaded-protocol` | The messages between server, game and launcher, and types shared by every module such as `Outcome` and `Checksum` |
| `rloaded-core` | Verified downloads, Mojang metadata, the pinned runtime, the JetBrains Runtime installer |
| `rloaded-modrinth` | Reads Modrinth's records of mod versions and checks manifest entries against them |
| `rloaded-admin` | Admission of a mod an admin adds: resolves it and its dependencies on Modrinth, checks the jars, tries them on a staging server, writes the lockfile |
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
./gradlew :rloaded-game-tests:stagingTest        # test mods pass a staging server
./gradlew :rloaded-game-tests:restartTest        # a server restarts without a mod that failed as it loaded it
./gradlew :rloaded-game-tests:registrySyncTest   # a server and two clients agree on raw ids after an add
./gradlew :rloaded-game-tests:joinTest           # a client joins a server and installs its mods
./gradlew :rloaded-game-tests:addTest            # mods added from the console and an operator's Mods screen reach two connected players
./gradlew :rloaded-game-tests:soakTest           # twenty adds in a row with two connected players
./gradlew :rloaded-game-tests:recoveryTest       # a game that fails to load an added mod starts again and rejoins
./gradlew :rloaded-game-tests:singleplayerTest   # mods installed from the game's Mods screen join an open singleplayer world
./gradlew :rloaded-launcher:launcher-app:run     # the launcher window
./gradlew :rloaded-server-bootstrap:serverJar    # rloaded-server-bootstrap/build/libs/rloaded-server.jar
```

In a singleplayer world started from the launcher, the pause menu has a Mods button: it searches Modrinth and installs a mod in the open world while you play. Each world lists its mods in `<world>/rloaded/lockfile.json`; opening the world later installs them first, and a game that holds other mods starts again to open it.

The launcher keeps its data in `~/Library/Application Support/Rloaded` on macOS, `%APPDATA%\Rloaded` on Windows and `$XDG_DATA_HOME/rloaded` on Linux.

To try a server, start `rloaded-server.jar` in an empty folder with Java 21 or newer. The first start sets the folder up and asks you to accept the Minecraft EULA in `eula.txt`. Start it again with `--dev` to accept the launcher's offline profiles on this machine. At each start, the mods listed in `rloaded/lockfile.json` are checked against Modrinth, downloaded once and loaded; if one cannot be obtained, the server does not start. In the server console, `rloaded add <project> [version]` adds a mod from Modrinth with its dependencies, or says why it cannot; operators of level 4 can do the same from the Mods button of the game's pause menu (`[admin] inGame` in `rloaded-server.toml`): the running server loads it, writes it to the lockfile, and connected players install it without leaving the world. By default a mod version must have been public for 72 hours and its project downloaded 1,000 times (`[reputation]` in `rloaded-server.toml`). Mods known not to work in a running game, such as optimization mods, are refused from the list in `compat/compat.json`. If a player's game fails to load a mod, the launcher starts the game again and it joins the server again; the server keeps the mod, logs an error, and lists the version in `rloaded/compat-local.json` so it is not added again. Before writing the lockfile, the server tries the mods on a staging server that nobody plays on, in a new flat world under `rloaded/staging`, and refuses them if they fail there; its log stays in `rloaded/staging/logs/latest.log` until the next add (`[staging]` in `rloaded-server.toml`). The host needs memory for this second server while an add runs. Every add, from the console or the Mods screen, and every refusal, including a player who may not add, is appended to `rloaded/audit.log` (`<world>/rloaded/audit.log` in singleplayer), one JSON object per line. The server's identity is the key in `rloaded/identity.key`, created at the first start: keep a copy of it, since players who joined before are warned that the server's identity changed if it does.

Before the first push, set up the Git identity and the pre-push hook as described in [ADR 0003](docs/adr/0003-git-identity.md).

## License

[MIT](LICENSE)

Rloaded is not an official Minecraft product. It is not approved by or associated with Mojang or Microsoft.
