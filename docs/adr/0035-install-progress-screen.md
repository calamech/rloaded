# ADR 0035: A joining player watches the install on a screen of its own

- Status: Accepted
- Date: 2026-10-30

## Context

While a joining player's game downloads and loads the server's mods, the player saw the game's "Joining world" screen with no sign of progress, for as long as the install took (ADR 0030). The product owner chose on 2026-10-30 what the player sees instead: one screen with the step, how much is downloaded, a bar, each mod with its state, and a "Leave" button.

## Decision

- `InstallScreen` opens when the install starts, after the trust screen or at once for a server already trusted, and closes when the game reports `loaded`. It shows "Installing <server>'s mods", a step line ("Downloaded 1 of 7 mods · 12.4 of 40.0 MB", in KB when the whole download is under a megabyte, then "Loading mods" and "Reloading resources"), a bar, and each mod with its title, or its file name when the game did not read titles from Modrinth, and "Waiting", "Downloaded" or "Loaded". Every string is in the language file.
- The state lives in `InstallProgress` (core), which download threads update and the screen reads each tick. To know which mod a finished file belongs to, `Downloads.obtainAll` now reports the index of each download that ends, and `DownloadProgress` the entry.
- "Leave" sends `refused_by_user`, like "Leave" on the trust screen; the server's message for both is now "You chose not to install this server's mods."
- Once the player left or the configuration connection ended, what the install still has in flight is dropped: no mod is loaded into a game that left the server.
- Like the trust screen, the screen ticks the connection screen it was opened from, so the connection stays alive, and Escape does not close it.

Checked on 2026-10-30 with `:rloaded-game-tests:joinTest`: both players see the screen after accepting the trust screen; the player whose downloads are slow joins once they end, and a screenshot of the screen is taken. With the screen never opened, the check failed.

## Alternatives

- **The step line alone, without bar or button**: the product owner preferred the list of mods and a way to leave.
- **Byte-level progress inside each file.** It needs a counter inside the download of each file; the per-file count is enough while mods are a few megabytes, and a single large file still shows no movement until it ends.

## Consequences

- Progress moves once per file, and the server's timeout resets once per file too (ADR 0033).
- A join with nothing to install opens no screen.

## Enforcement

`InstallProgressTest`, `DownloadsTest` and `ModInstallerTest` for the state and the reported entries, and `:rloaded-game-tests:joinTest` in the `client-game-test` CI job, which waits for the screen.
