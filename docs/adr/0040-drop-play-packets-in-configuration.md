# ADR 0040: The server drops play packets sent to a player in the configuration phase

- Status: Accepted
- Date: 2026-10-31

## Context

An add sends every connected player back to the configuration phase (ADR 0023, ADR 0024). Mods often keep references to players and send them packets from their own ticks. A play packet sent to a player who is in the configuration phase stalls that player's reconfiguration (issue #40, measured for ADR 0023), so a single such mod would break adds for everyone.

Read in the Minecraft 26.3 sources on 2026-10-31: `ServerGamePacketListenerImpl.switchToConfig` sends `ClientboundStartConfigurationPacket` in the play phase, then calls `Connection.setupOutboundProtocol` with the configuration protocol; every later packet goes through `Connection.send(Packet, ChannelFutureListener, boolean)`. `ConfigurationProtocols.CLIENTBOUND_TEMPLATE.details().listPackets` lists the packets the configuration phase can carry, custom payloads, keep-alives and disconnects included.

## Decision

- A mixin on `Connection` remembers the outbound protocol each time it is set up, and at the start of `send` drops any clientbound packet whose type the configuration protocol does not list, while the connection sends in that phase. Packets of the play phase sent before the switch, custom payloads and every packet of the configuration phase pass.
- `ConfigurationPackets` counts the dropped packets for the life of the game and logs one `info` line the first time it drops each packet type. Nothing else is done with them: the player receives the current state when it goes back to the world.

Checked on 2026-10-31 with the client game tests: the game test mod now also behaves like a careless mod, keeping every player it saw and sending each `ClientboundSetExperiencePacket` every tick. `ReconfigurationStabilityClientGameTest` ran its 100 reconfigurations and found dropped packets counted. With the drop disabled, the tests timed out at the first reconfiguration and the client was disconnected.

## Alternatives

- **Queueing the packets until the player is back in the world.** The world state they describe is stale by then and the game sends the current one on return; a queue only adds memory a careless mod can fill.
- **Dropping only in `ServerGamePacketListenerImpl`.** A mod can also send through the connection itself; the connection is the one place every packet goes through.

## Consequences

- A mod relying on a play packet reaching a player during an add loses it. The packets dropped are those the game could not have sent anyway.
- Every packet sent costs one more comparison; a set lookup is added only for connections in the configuration phase.

## Enforcement

`ReconfigurationStabilityClientGameTest` (asserts dropped packets were counted, with the careless traffic of `PingTraffic`), run by the `client-game-test` CI job.
