# ADR 0050: The game's Mods screen adds mods to the server the player is on, over `rloaded:admin`

- Status: Accepted
- Date: 2026-10-31

## Context

Players add mods to a singleplayer world while it is open (ADR 0049), and admins add mods to a dedicated server from its console (ADR 0041). The product owner decided on 2026-10-31 that adding mods happens in the game itself, on one screen, for the owner of a singleplayer world and for operators of a server, and that the launcher has no Mods tab. Modrinth's search endpoint (`GET /v2/search`, with `facets` for the loader, the game version and the project type) answers each hit with `project_id`, `slug`, `title`, `author` and `downloads`, read on 2026-10-31 in a recorded answer.

## Decision

- **Screen.** A "Mods" button in the pause menu, and a key that is unbound by default, open the Mods screen; both exist only when the server said the player may add mods. The screen has a Modrinth search field, the results with their title, author and downloads and an Install button, then the mods the server has ("Installed in this world"). Each mod's progress and outcome show on its row: "Installing…", "Installed", or the reason the server gave, with an Install button to try again. No dialog opens. An add sends the player through the configuration phase, which closes the screen; once the player is back in the world, the screen opens again on the outcome, with the same search.
- **Channel.** The client searches Modrinth itself (`Modrinth.search`: Fabric mods of the game version, 20 hits at most, no empty query nor one longer than 100 characters). It sends `AdminRequest.Add` and `AdminRequest.ListMods` to the server on the play-phase channel `rloaded:admin_request`; the server answers on `rloaded:admin_event` with `AdminEvent.Access` (sent each time the player enters the world), `Mods`, `Added` (once every player has the mods) and `Refused` with its `AddProblem` key. After an add the screen asks for the mods again rather than adding to its list.
- **Who decides.** The server decides who may add, in one place, and says it with `Access`: in a singleplayer world, its owner, unless the world is open to the local network (`SHARED_WORLD`). Anyone else is refused with `NOT_ALLOWED`. A dedicated server says no to everyone until operators get the screen (#92).
- **Logic.** `ModsBrowser` in `rloaded-admin` holds what the screen shows, built from what the server and Modrinth said; the Minecraft screen only renders it. What Modrinth did not say about a mod stays empty rather than guessed.

Checked on 2026-10-31 with `singleplayerTest` (CI job `client-game-test`), whose client game test plays the player with the mouse and the keyboard: it opens the pause menu, clicks Mods, types "content", clicks Install on content-basic while the world is open to the local network (refused), on a project a search lists but Modrinth removed (refused), then on content-basic with the game paused (installed); it searches "mixin" and installs mixin-field-init. The block then shows with its model, every client entity has mixin-field-init's field, the world's lockfile lists both, and no log line is an error from the first add on. Green 6 times out of 6 in local, about 31 seconds each.

## Alternatives

- **A Mods tab in the launcher.** Built and checked on 2026-10-31, then dropped by the product owner the same day: a player in the game would leave it to add a mod, and an operator of a server could not use it without a link from the launcher to the server.
- **Checking access on the client.** The client knows its own permission level but not the server's settings; two places deciding could disagree.
- **Sending the server's list after each change without being asked.** The screen would rebuild the list from messages and drift after a missed one; asking costs one message.

## Consequences

- The screen itself is checked by the client game test; the state behind it by `ModsBrowserTest`.
- On a dedicated server, mods are still added from the console only (#92).
- Players who join a world through the local network see no Mods button.

## Enforcement

`ModsBrowserTest`, `ModrinthSearchTest`, `AdminMessagesTest` in `check`; `singleplayerTest` in the `client-game-test` CI job.
