# ADR 0046: Packets written while a connection switches its outbound protocol wait for the new one

- Status: Accepted
- Date: 2026-10-31

## Context

On 2026-10-31 the `soakTest` run of the `build` workflow on `main` failed at its 15th add: one client was disconnected with `EncoderException: Pipeline has no outbound protocol configured, can't process packet ServerboundCustomPayloadPacket[payload=RegistrationPayload[... minecraft:register ...]]`. The same test had passed on the pull request a few minutes earlier, and 20 adds in a row had passed many times before.

Read in the Minecraft 26.3 and Fabric API 0.165.0+26.3 (`fabric-networking-api-v1` 6.4.0) sources on 2026-10-31:

- When the server sends a player to the configuration phase, the client thread handles the switch (`ClientPacketListener.handleConfigurationStart`): it sets the inbound protocol to configuration, sends the acknowledgement, then sets the outbound protocol. Between the last two, the connection has no outbound protocol (`UnconfiguredPipelineHandler.Outbound`), and any packet written then is refused with that exception, which closes the connection.
- The server starts the configuration as soon as the acknowledgement arrives (ADR 0024, ADR 0041). Fabric API then sends its channel registration, and the client's Fabric API answers with its own from the network thread, without waiting for the client thread.

On a busy machine the client thread can still be between the two calls when the server's registration arrives. Holding the client thread 200 ms at that point made the failure happen at every add: both players of `soakTest` were disconnected at the first one.

## Decision

- A mixin on `UnconfiguredPipelineHandler.Outbound` holds, in order, the packets written while a connection has no outbound protocol, up to 64, and writes them through the new encoder as soon as the next protocol is set. Each sender's promise reports its own packet. Past 64, the game refuses as before.
- A packet written after the one that ends a phase belongs to the next phase, so it is encoded with that phase's protocol. If it does not exist there, the new encoder refuses it, as the game would have.
- The mixin applies on clients and servers, to every connection.
- The add checks hold the client thread 200 ms between the acknowledgement and the outbound switch, with a mixin in their test mod (`SlowConfigurationSwitch`), so every add of `addTest` and `soakTest` goes through the window.

Checked on 2026-10-31: `soakTest` with the client held, 20 adds and two players, no error line and no disconnection; without the mixin, both players were disconnected at the first add. `gameTest`, `clientGameTest`, `registrySyncTest`, `joinTest`, `addTest` and `recoveryTest` pass with it.

## Alternatives

- **Sending Fabric API's registration from the client thread.** The client thread handles packets through its own queue, separate from its task queue: a registration sent from a task went out after Fabric's ping answer, and the server then believed the client had no channels and refused the join.
- **Waiting a few ticks on the server before starting the configuration.** It narrows the window without closing it.
- **Leaving it.** About one reconfiguration in thirty failed on the CI runner, each time dropping a player.

## Consequences

- Fabric API and the game have the same window at a first join, between the login acknowledgement and the configuration protocol; the mixin covers it too.
- A connection that closes while packets are held drops them with it, as a closing connection drops what it has not sent.

## Enforcement

`addTest` and `soakTest` in the `client-game-test` CI job, with the client held in the window at every add.
