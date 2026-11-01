# ADR 0059: A changed server key shows a warning before the trust screen

- Status: Accepted
- Date: 2026-11-01

## Context

Trust is bound to a server's key, not to its address (ADR 0029). The store kept no address, so when another key answered at an address where the player had trusted a server, the game could not tell: the player saw the trust screen of a new server, which looks the same whether the server was reinstalled or someone took over its address.

The product owner decided on 2026-11-01 that the player sees a warning screen of its own first: "The identity of <server> changed", a short explanation, a "Leave" button that has the focus, and "Trust the new identity", which goes on to the usual trust screen. The earlier trust is replaced only if the player accepts.

## Decision

- `TrustedServer` keeps the addresses the player joined it at, oldest first, at most 16, written by `TrustedServer.addressOf` as the host in lowercase and the port. The store's schema goes to 2; a store of schema 1 is read with no addresses.
- An address belongs to the last key trusted at it: `TrustStore.trust` takes the server's addresses away from any other server, which keeps its trust otherwise.
- The game takes the address from the server entry the player joined (`ServerData.ip`, through an accessor on `ClientCommonPacketListenerImpl`), parsed as the game parses it.
- `ClientJoin` answers a manifest from an unknown key with `ManifestStep.KeyChanged` when a trusted server is known at that address, with the name it was trusted under, and `AskTrust` otherwise. A trusted key joined at a new address gets that address added. Accepting the new identity records its key with the address.
- `KeyChangedScreen` shows the warning; while the player reads it, the game keeps telling the server it is alive, like the trust screen.

Checked on 2026-11-01 by `TrustStoreTest`, `JoinTest`, and by `joinTest`: the slow player's store holds another key at the server's address, it must see the warning, trust the new identity, then install from the trust screen, and its store must end with the server's key at that address. With the lookup by address removed, `joinTest` fails.

## Alternatives

- **A banner on the trust screen.** Lighter, but easy to accept without reading; the owner chose the screen of its own.
- **Refusing the join until the player edits the store.** It protects best, but a reinstalled server, the common case, would need a tool players do not have.
- **Keeping the address the game connected to (IP address).** A host name that moves to another IP address would look new, and an attacker who redirects the name would not be noticed; the address the player typed is what they trust.

## Consequences

- An admin who loses `identity.key` makes every player see the warning once. Backing up the file avoids it.
- A server reached under two names shares its trust between them only after the player joined it under each.
- Stores written before this change know no address: their servers get one at the next join, and a key change before then shows the plain trust screen.

## Enforcement

`TrustStoreTest` and `JoinTest` in `check`; `joinTest` in the `client-game-test` CI job.
