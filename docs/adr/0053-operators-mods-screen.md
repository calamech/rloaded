# ADR 0053: Operators of a dedicated server add mods from the game's Mods screen

- Status: Accepted
- Date: 2026-11-01

## Context

The game's Mods screen adds mods to a singleplayer world for its owner (ADR 0050); on a dedicated server, mods are added from the console only (ADR 0036, ADR 0041). The product owner decided on 2026-10-31 that operators use the same screen on a server, and that the admin can turn this off. In Minecraft 26.3, a player's permissions come from the server's operator list (`MinecraftServer.getProfilePermissions`); level 4 is `Permissions.COMMANDS_OWNER`, the level of the server's owner.

## Decision

- One server-side handler, `ModsChannel`, serves the Mods screen for both kinds of server; each server gives it a target that decides who may add: `SingleplayerAdmin` (the world's owner, not while the world is shared on the local network) and `DedicatedAdmin` (operators of level 4, when `[admin] inGame` in `rloaded-server.toml` is `true`, its default). The bootstrap passes the setting as `rloaded.admin.in-game`; a server without it does not start.
- An operator's add runs the dedicated server's own pipeline, as the console's: staging, reputation, compatibility database, the server's lockfile, the rollout to every player. The lockfile records the operator's name as who added the mods.
- Whether a player may add is told at each join, as before (ADR 0050). A player made an operator while in the world gets the button at their next join; an add brings one for everyone.
- The game's client clicks only once no resource reload covers the screen: the game ignores the mouse under it. The checks' screen driver waits for it.

Checked on 2026-11-01 with `addTest` (CI job `client-game-test`): the console makes Alice an operator before the first add; after the console's adds of content-basic and mixin-field-init, Alice opens the pause menu, clicks Mods, searches "key" and installs keybind-and-tab, which both players get with its key mapping and creative tab. Bob has no Mods button, and the server refuses the add he sends anyway and logs it. The server's log shows Alice's add and Bob's refusal, four rollouts and no error line. With the server letting every player add, Bob had the button and the check failed on "a player who is not an operator has the Mods button".

## Alternatives

- **A lower level, such as the game masters' 2.** Adding mods makes code run on every player's machine; it is the owner's call, as stopping the server is.
- **Checking the permission on the client only.** The client does not know the server's `[admin] inGame` setting; the server decides in one place.

## Consequences

- Turning `[admin] inGame` off is checked by the settings' tests (`ServerConfigTest`, `InGameAdminTest`); that the server then refuses operators is checked by review.
- Players who are not operators never see the screen; they get new mods as before, with a toast.

## Enforcement

`addTest` in the `client-game-test` CI job; `InGameAdminTest`, `ServerConfigTest` and `ModsBrowserTest` in `check`.
