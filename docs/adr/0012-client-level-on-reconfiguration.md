# ADR 0012: The client drops its level during reconfiguration

- Status: Accepted
- Date: 2026-10-29

## Context

When the server adds a mod while players are connected, it sends each client back to the configuration phase to install it. Objects that live on the client, such as entities in the client level, were built from the old classes. If they survived reconfiguration, a field added by a new mixin would stay null or zero on them.

## Decision

Rloaded relies on the vanilla client discarding its level when configuration starts, and does not cycle client entities itself.

Read in the Minecraft 26.3 client, decompiled from the client jar with Vineflower 1.12.0 on 2026-10-29:

- `ClientPacketListener.handleConfigurationStart` calls `Minecraft.clearClientLevel(new ServerReconfigScreen(...))` before switching the connection to the configuration protocol.
- `Minecraft.clearClientLevel` clears the connection's level, resets the game renderer, drops the game mode, and sets `level` and `player` to null.
- When configuration ends, `ClientConfigurationPacketListenerImpl.handleConfigurationFinished` creates a new `ClientPacketListener` with the registries received during configuration. The level comes back with the next login packet of the play phase.

So no client-side entity or level object survives a reconfiguration: everything is rebuilt from what the server sends afterwards.

## Alternatives

- **Cycling the client level after an add**, by forcing a dimension reload. Not needed while the behavior above holds.

## Consequences

- A later Minecraft version could keep the level across reconfiguration. The decision then has to be revisited.
- The registries a client uses after reconfiguration are the ones received during it, which is where Rloaded's registry sync has to run.

## Enforcement

- Source reading above, for 26.3.
- A client game test that adds a mod during play and checks that entities near the player are new instances: issue #17.
