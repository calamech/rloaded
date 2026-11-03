# ADR 0067: A mod added while the game runs is told the lifecycle events that already fired

- Status: Accepted
- Date: 2026-11-03

## Context

Many mods set up their state in Fabric API lifecycle events rather than in their entrypoint. Read on 2026-11-02 in Xaero's Minimap 26.6.2 for Fabric (`xaero.common.events.CommonEventsFabric`): it registers `ServerLifecycleEvents.SERVER_STARTED` and creates its per-server data there. Added to a running local server, its entrypoints ran but that event had already fired, so its data stayed null; at the player's next tick its handler threw a `NullPointerException` and the server disconnected the player with "Internal server error", and did again at each join until it restarted (#125). The staging dry run has no player, so it passed.

Fabric API keeps an event's listeners in the `handlers` array of `ArrayBackedEvent` (fabric-api-base 2.0.6), in calling order.

## Decision

- `HotAdd.load` takes the events that already fired in this game (`StartedEvents`), each with how to call one listener the way the event does:
  - on a running server, `ServerLifecycleEvents.SERVER_STARTING`, then `ServerLevelEvents.LOAD` for each level, then `ServerLifecycleEvents.SERVER_STARTED`;
  - on a client, `ClientLifecycleEvents.CLIENT_STARTED`;
  - in a singleplayer world, the client's then the world server's;
  - before a singleplayer world opens, only the client's: the world's server fires the others itself.
- Before and after each entrypoint of an added mod, it takes the listeners of those events by identity: the new ones belong to that mod. Once every entrypoint ran, it calls them, event by event in the order the events fired, before the game goes on, so no tick sees a mod without its state. Mods that were there from the start are not called again.
- A listener that throws is a failure of its mod as it started (`HotAddFailure`), with the event named, and is handled like a failing entrypoint (ADR 0041, ADR 0043).
- The listeners are read through an accessor mixin on `ArrayBackedEvent`; if Fabric API renames the field, the game fails at start instead of skipping the replay.

## Alternatives

- **Calling every listener of those events again.** Measured on 2026-11-03: a listener that the game test server registered at start threw when told again that the server was starting. Mods expect each event once.
- **Restarting the server after such an add.** It would cut every player's session, which Rloaded exists to avoid, and the client side needs the same replay.
- **Refusing mods that listen to these events.** Most large mods do; the add would refuse them for a reason Rloaded can handle.

## Consequences

- Events that fire repeatedly (ticks, player joins) are not replayed: the mod gets the next one. Other one-time events that a mod might set up in, such as registry or resource events, are not covered until a mod needs them.
- A mod that registers its listener outside its entrypoints, later, is not seen as late and gets no call.
- Relies on a Fabric API internal field; the accessor makes a change loud.

## Enforcement

`StartedEventsGameTest` (the order and count of the calls, and that a mod from the start is not told again), and `addTest`, which adds late-lifecycle, a test mod that sets up its state when the server and the game started and uses it at each player and client tick, while two players are connected. Without the replay, `addTest` fails with "late-lifecycle was never told the server started".
