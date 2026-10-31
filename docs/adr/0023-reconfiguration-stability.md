# ADR 0023: Players go through the configuration phase again for every in-game add

- Status: Accepted
- Date: 2026-10-30

## Context

To install a mod while players are connected, the server sends each of them back to the configuration phase, installs the mod there, and lets them back into the world. The alternative is to disconnect them, load the mod at the title screen, and reconnect them automatically. Reconfiguration is smoother, but only if it holds up when it happens often and while other mods talk to their clients.

## Decision

Rloaded keeps reconfiguration for in-game adds.

Checked on 2026-10-30 with `ReconfigurationStabilityClientGameTest` (client game tests, ADR 0022) on macOS with the pinned JetBrains Runtime, Fabric Loader 0.20.1, Fabric API 0.165.0 and Minecraft 26.3. The game test mod sends a custom packet every tick in each direction, the way content mods keep their clients in sync: the server to every player it can send to, the client to the server while it is in the world. The test then sends the player through the configuration phase and back 100 times in a row, with `switchToConfig` and `returnToWorld`, and after each round waits until packets flow again both ways. All 100 rounds completed with the client on its original connection and no error in the log. The two client game tests together took 50 s, client start included, and passed 4 runs out of 4.

The test catches a stalled or dropped connection: with a server mod that keeps a reference to the player and sends it a play-only packet every tick, the first round never completed and the test failed on a timeout.

## Alternatives

- **Disconnect, load at the title screen, reconnect.** It works with any mod, but players see the title screen and a new connection for every add, and the server has to tell the launcher to reconnect them.

## Consequences

- A mod that sends play-only packets to a player who is in the configuration phase stalls every reconfiguration. Custom payload packets do not, as they exist in both phases. The server has to drop such packets: issue #40.
- The test runs with Rloaded's own test mod only; mods from the catalog are covered by the compatibility checks that admit them.

## Enforcement

`ReconfigurationStabilityClientGameTest`, run by the `client-game-test` CI job.

Amended 2026-10-31: the server drops play packets sent to a player in the configuration phase (ADR 0040).
