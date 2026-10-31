# ADR 0048: The launcher and the game it starts talk over a loopback link with length-prefixed CBOR

- Status: Accepted
- Date: 2026-10-31

## Context

A player adds a mod to the singleplayer world they have open from the launcher's window, while the game runs in a process of its own. The launcher has to send the request to that game, and the game has to answer with the outcome, which the launcher shows. Any process on the machine can open a loopback connection, so the launcher must know that the process on the other end is the game it started. The messages Rloaded exchanges between server and client are already defined once in `rloaded-protocol` and read by `ProtocolCodec`: CBOR, strict (an unknown key is refused) and bounded (ADR 0020, ADR 0021).

## Decision

- The launcher listens on the loopback address, on a port the system picks, and accepts one connection. It generates a token of 32 random bytes (`SecureRandom`, written as 64 hexadecimal digits) for each launch, and gives the port and the token to the game in the environment variables `RLOADED_LINK_PORT` and `RLOADED_LINK_TOKEN`.
- The game connects at start. Its first message is `LinkHello`: the link's protocol version and the token. The launcher compares the token in constant time (`MessageDigest.isEqual`) and closes a connection with another token or another version. A game started without the variables has no link and runs as before.
- Each message is a 4-byte big-endian length, then the message encoded by `ProtocolCodec`; a length above 64 KiB is refused before anything is read. The launcher sends `LinkRequest.Add` (a request id, a Modrinth project id or slug, an optional version id); the game answers each request once with `LinkEvent.Added` (the file names of the mods added) or `LinkEvent.Refused`, which carries an `AddProblem` key the launcher has a message for, and a detail for its log. Every field has bounds checked when the message is built or decoded.
- The launcher waits up to 5 minutes for the game to connect, then 10 seconds for its hello (`LinkSettings.DEFAULT`). The game closes the link on a message it cannot read.
- `GameLink` (launcher side) and `LauncherLink` (game side) in `rloaded-core` are the only code that touches the sockets.

## Alternatives

- **Newline-delimited JSON messages.** It needs a second serializer configuration next to `ProtocolCodec`, with its own strictness and bounds to keep in line, for messages that only Rloaded reads. The length prefix lets the reader refuse an oversized message before it reads it, where a line has to be read to its end first.
- **A Unix domain socket.** Java supports them on Windows 10 and later since Java 16, but the socket file needs a folder only the player can write to on each platform; the loopback port with a token gives the same guarantee without files.
- **The token on the command line.** Other users of the machine can read a process's arguments (`ps`), while its environment is readable by its owner only on Linux and macOS.
- **The game listening and the launcher connecting.** The game would have to tell the launcher its port, through its log or a file, before the launcher could talk to it.

## Consequences

- The game trusts what comes on the link as it trusts the launcher; the launcher trusts the game's answers as it trusts the mods the game runs.
- Processes the game starts inherit the token, like the rest of its environment; the launcher accepts a single connection per launch, which the game opens at start.
- Answers can come in another order than the requests, as an add that is refused at once overtakes one that runs; the launcher matches them by request id.

## Enforcement

`LinkTest` (round trip, loopback only, wrong token, other version, oversized message, timeouts, no launcher) and `LinkMessagesTest` (round trips and bounds) in `check`, and `singleplayerTest` in the `client-game-test` CI job, where the check plays the launcher's end against a real game.
