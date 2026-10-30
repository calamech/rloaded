# ADR 0029: Players trust a server's key once, and may leave out its client-only mods

- Status: Accepted
- Date: 2026-10-30

## Context

A server causes code to run on its players' machines. The first time a player joins, they see the mods the server runs and decide whether to install them. That decision has to be bound to something an attacker cannot copy: an address can be taken over, a server name can be copied. Manifests are already signed with the server's Ed25519 key (ADR 0021); the key has to exist, stay the same across restarts, and stay private.

The product owner decided on 2026-10-30 that:

- a player trusts a server once; mods the server adds later install without asking again, and the player sees a notice listing them;
- the player may leave out each client-only mod (`side: CLIENT`) on the trust screen; mods that both sides need stay required.

## Decision

- `ServerIdentity.loadOrCreate` reads `rloaded/identity.key` or creates it at the first start: a new Ed25519 key pair, written as two Base64 lines (PKCS#8 private key, X.509 public key). A file that is not a pair, or whose keys do not sign and verify together, stops the server with an `IdentityError` naming the cause. The private key never leaves the class; `toString` shows the public key only.
- `TrustStore` keeps, per player, the servers they trust, keyed by public key: the server name shown at the time and the client-only mods they left out, by Modrinth project id. A missing file is an empty store. Each decision is written at once. A file of another schema, with an unknown field or with a key that is not 32 bytes is refused.
- Both files are written to a temporary file, flushed, then renamed over the old one, and are readable by their owner only on file systems with POSIX permissions.

## Alternatives

- **Trust per address**, as browsers once did for self-signed certificates. Whoever controls the address next would inherit the trust.
- **Asking again for each new mod.** It interrupts players in the middle of a game, and a refusal disconnects them; the owner chose a notice instead.
- **No opt-out**: one list to accept or refuse. Simpler, but the owner wants players to be able to leave out client-only mods such as minimaps.

## Consequences

- A server whose key changes, because the file was lost or replaced, is unknown to its players: they see the trust screen again. A distinct warning for a changed key comes with the security work.
- A client that left out a mod has another set of mods loaded than the server's lockfile lists; the comparison at join has to leave opted-out client-only mods aside.
- A client-only mod added after the player trusted the server installs like any other add; leaving it out later needs a way to edit the decision, not built yet.
- Losing `identity.key` without a backup makes every player trust the server again.

## Enforcement

`ServerIdentityTest` (same key across restarts, owner-only file, private key never printed, mismatched pair refused) and `TrustStoreTest` (decisions kept across openings, replaced, invalid files refused), run by `check`.
