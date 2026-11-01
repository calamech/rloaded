# ADR 0054: The web panel is optional and comes after the first release

- Status: Accepted
- Date: 2026-11-01

## Context

Admins were to add mods from three surfaces that share one service: the server console, a web panel served by the server, and a Mods screen. The console works (ADR 0036, ADR 0041), and the game's Mods screen now serves both the owner of a singleplayer world and the operators of a server (ADR 0050, ADR 0053). The product owner decided on 2026-10-31 that the web panel is optional and not urgent.

## Decision

The first release ships the console and the game's Mods screen. The web panel, its settings in `rloaded-server.toml` and its embedded HTTP server are left out until after that release (#96). The admin surfaces are complete when the same add passes through the console and the Mods screen.

## Alternatives

- **Building the panel now.** It adds an HTTP server with sessions, tokens and CSRF protection to every Rloaded server, for admins who already have a console and, as operators, the game itself.

## Consequences

- An admin who manages a server only from a hosting provider's web console uses the console commands there.
- No HTTP port is opened by Rloaded on a server.

## Enforcement

Review: no web server dependency in `rloaded-runtime` nor in `rloaded-server-bootstrap`.
