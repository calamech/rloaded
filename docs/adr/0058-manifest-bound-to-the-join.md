# ADR 0058: The manifest is signed for the join that asked for it

- Status: Accepted
- Date: 2026-11-01

## Context

The server signs the manifest's kind and entries with its identity key (ADR 0021), and the client checks the signature against the key it trusts (ADR 0029). The signed bytes carried no date and nothing of the connection, so a manifest the server signed once stayed valid forever. On a connection without encryption (offline mode), someone who can inject packets could replay an older manifest of the same server, for example one with an older version of a mod (#36). The server sent its hello and the manifest together, without waiting for the client.

## Decision

- The client's `ClientHello` carries a `JoinChallenge`: 32 bytes from `SecureRandom`, new for each join (`ClientJoin`).
- The server sends the manifest in answer to that hello, signed over the challenge, the kind and the entries (`ManifestSigning`, `ServerJoin.manifest(hello)`). `JoinServer` sends it from the hello's receiver instead of the install task's start.
- The client checks the signature with its own challenge. A manifest signed for another join, even by the right key, is refused as `JoinFailure.BadSignature`.
- `PROTOCOL_VERSION` goes to 2: a game with protocol 1 is refused by the server's existing version check.

Checked on 2026-11-01 by `ManifestSigningTest` and `JoinTest` (a manifest signed for another join is refused), and on the production stack by `joinTest` and `addTest`, which join, install and reconfigure with the new order.

## Alternatives

- **A server timestamp in the manifest, checked against the client's clock.** It needs no extra message, but a player's clock can be wrong by hours, and a replay inside the accepted window still works.
- **A random value from the server in its hello.** Whoever replays the manifest replays that hello with it; only a value the client picks binds the answer to the join.
- **Relying on encryption.** Online-mode servers encrypt the connection, but offline-mode and proxied setups exist, and the manifest decides what code runs.

## Consequences

- The join takes one more round trip between the hello and the manifest: the client's hello was already sent at once, so the cost is the server's answer time.
- The server signs one manifest per join instead of signing the lockfile once.
- Clients and servers of protocol 1 cannot join each other; no release has shipped with protocol 1.

## Enforcement

`ManifestSigningTest` and `JoinTest` in `check`; `joinTest` and `addTest` in the `client-game-test` CI job.
