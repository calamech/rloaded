# ADR 0051: The launcher no longer links to the game it starts

- Status: Accepted
- Date: 2026-10-31

## Context

The launcher and the game it started talked over a loopback link (ADR 0048) so the launcher could ask the game to add mods to the open singleplayer world. The Mods screen is now in the game (ADR 0050): the game asks its own server, and nothing else uses the link.

## Decision

The link is removed: `GameLink`, `LauncherLink` and their messages, the environment variables `RLOADED_LINK_PORT` and `RLOADED_LINK_TOKEN`, and the game's connection at start. The singleplayer add keeps everything else of ADR 0049; its requests come from the world's owner over `rloaded:admin`.

## Alternatives

- **Keeping the link for later uses, such as showing install progress in the launcher or asking it to start the game again.** No feature needs it today; restarting goes through the exit code (ADR 0043). It can come back from ADR 0048 and the history if one does.

## Consequences

- A game started without the launcher still cannot add mods to a singleplayer world: it has no store for the mods it downloads (`rloaded.mod-store`).

## Enforcement

Review: nothing in the repository opens a loopback socket for the launcher.
