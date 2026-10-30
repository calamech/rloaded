# ADR 0030: Joining players install the server's mods in the configuration phase

- Status: Accepted
- Date: 2026-10-30

## Context

A player starts the game from the launcher with the base set only, and joins a server that runs more mods. The mods have to be on the client, loaded and registered, before Fabric syncs raw ids during the configuration phase (ADR 0024), and before any world exists, so no live object is affected (ADR 0026). The player decides once whether to trust the server (ADR 0029). The product owner decided on 2026-10-30 that the trust screen shows at the first join even when the server has no mod for the client, and approved its content: the server name and the number of mods, a line saying the mods come from Modrinth and run on the player's computer, each mod with its name, authors and download count, client-only mods with a box ticked by default, a line saying the trust covers future mods, and the buttons "Install and join" and "Leave".

## Decision

- Four configuration-phase channels carry the messages of `rloaded-protocol`, as bytes: `rloaded:hello` both ways, `rloaded:manifest` from the server, `rloaded:progress` and `rloaded:result` from the client.
- On a dedicated server, every join gets the `rloaded:install` configuration task, registered in a phase of `ServerConfigurationConnectionEvents.BEFORE_CONFIGURE` ordered before Fabric's default phase, where Fabric queues its registry sync. The task sends the server's hello and the manifest of its lockfile, signed with its identity, then waits for the result: `loaded` completes the task, any other result or an unreadable message disconnects the player with the reason. A client without the channels is told to use the Rloaded launcher. The identity and the lockfile are read when the server starts; a missing lockfile means no mods, a broken one stops the server.
- The client answers the hello, checks the manifest's signature against the key in the hello, and looks the key up in its trust store. The first time, it reads the mods' details from Modrinth and shows the trust screen; "Leave" sends `refused_by_user`. Then it picks the entries to install, downloads them from the URLs in Modrinth's records into its store, loads them with `HotAdd`, reloads its resources and sends `loaded`. Each failure is sent as `failed` with a reason, which the server shows the player.
- The rules live in `rloaded-core` (`ServerJoin`, `ClientJoin`), unit tested without the game; the runtime mod moves bytes, shows the screen and calls `HotAdd`.
- The launcher passes the mod store and the trust store to the game as `rloaded.mod-store` and `rloaded.trust-store`; a game started without them refuses to install mods. For checks only, `rloaded.test.modrinth` points the game at another Modrinth API and allows only that origin; the game logs a warning when it is set, and a server cannot set it.
- Authors are the first two members of the project's team in Modrinth's order, then "and N more": Modrinth has no owner flag and some projects belong to organizations.

Checked on 2026-10-30 with `:rloaded-game-tests:joinTest`. It starts the pinned server in its own process with `content-basic` and `mixin-loaded-class` in its lockfile and among its mods, a fake Modrinth on the loopback interface that serves both jars, and a client with the base set only. The client game test connects, waits for the trust screen, clicks "Install and join", and once in the world finds the new block registered and the stone item answering the mixin's method. The trust store then holds the server's key and the store holds both jars. No error was logged. The server game tests, the client game tests and the registry sync check, whose clients now trust their server beforehand, passed.

## Alternatives

- **Installing in the play phase**, after the world loads. Live objects would then miss the mods' fields and the registry sync would already have failed on unknown blocks.
- **A second connection** to download before joining. It doubles the authentication and gives the server no way to tie the install to the player who joins.
- **The client reading mod URLs from the manifest.** The server would then choose where code comes from; the URL comes from Modrinth's record of the version (ADR 0025).

## Consequences

- The server waits without a limit for a client's result: issue #57.
- The player sees the game's joining screen while mods download, without progress: issue #58.
- The server does not load its lockfile's mods yet; the check starts it with them: issue #59.
- Singleplayer worlds do not go through this path; they come with the launcher's in-game requests.
- A mod the game already loaded in another version cannot be replaced; the join fails and asks for a restart.
- `rloaded.test.modrinth` is a switch in the shipped game. It only affects the player who sets it on their own game.

## Enforcement

`JoinTest` (core: trust asked once, opted-out mods left out, bad signature, protocol version, conflicts), `ModInstallerTest`, and `:rloaded-game-tests:joinTest` in the `client-game-test` CI job.

Amended 2026-10-30: the server now obtains and loads its lockfile's mods itself (ADR 0032, #59).

Amended 2026-10-30: the server ends a join whose client reports no progress for `rollout.clientTimeoutSeconds` (ADR 0033, #57).

Amended 2026-10-30: the player watches the install on its own screen (ADR 0035, #58).
