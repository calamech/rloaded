# ADR 0033: The server ends a join that reports no progress for two minutes

- Status: Accepted
- Date: 2026-10-30
- Amended: 2026-11-01, deadlines are checked each time the server ticks its connections, also while it pauses with no player in its list, and a timeout is logged by Rloaded (ADR 0056)

## Context

A joining player's game installs the server's mods during the configuration phase, and the server's `rloaded:install` task waits for its result (ADR 0030). It waited without a limit: a game stuck on a download, or one that never answers, kept its slot until the connection dropped. The game's own keep-alive does not help, because the game keeps answering it while the install is stuck.

## Decision

- The server gives each join a deadline: `rollout.clientTimeoutSeconds` after the install task starts, then again after each `rloaded:progress` message. When it passes, the player is disconnected with "Your game stopped answering while it installed this server's mods." The result message, or the player leaving, ends the wait. The rule lives in `JoinDeadlines` (core); the runtime checks it at the end of each server tick.
- `rollout.clientTimeoutSeconds` is set in `rloaded-server.toml`, 120 by default, between 10 and 3600; adds during play will use the same limit. The bootstrap passes it to the game as `rloaded.client-timeout-seconds`; the runtime mod refuses to start a dedicated server without a valid value.
- The game sends progress after each downloaded file, before loading the mods, before reloading its resources, and every 5 seconds while the player reads the trust screen, so reading it is never mistaken for a stuck game.
- The server now reads each progress message; one that cannot be read disconnects the player, like any malformed message.

Checked on 2026-10-30 with `:rloaded-game-tests:joinTest`, the server's timeout set to 20 seconds. A player who reads the trust screen for 25 seconds, then gets its two files after 12 and 24 seconds, joins and uses the mods. A second player whose files take 28 seconds is disconnected with the message above. With the server ignoring progress messages, the first player was disconnected too and the check failed; with the server never ending a join, the check failed as well.

## Alternatives

- **Counting the game's keep-alive answers as progress.** The game answers them on its own while the install is stuck, which is the case to catch.
- **A fixed limit for the whole install.** A large set of mods on a slow connection takes long while making progress; resetting the deadline on progress lets it finish and still catches a stuck game.

## Consequences

- Progress comes once per downloaded file: a single file that takes longer than the timeout to download ends the join, even if bytes keep arriving. Byte-level progress belongs with the progress screen (#58).
- The game keeps installing after the server disconnected it; the mods it trusted end up in its store and, if the download finishes, loaded in the game.
- Every launch of a dedicated server with the runtime mod sets the property, the test harnesses included.

## Enforcement

`JoinDeadlinesTest` (deadline, progress, end, clock wrap, setting bounds), `ServerConfigTest`, `BootstrapTest`, `JoinTest` (progress decoding), and `:rloaded-game-tests:joinTest` in the `client-game-test` CI job.
