# ADR 0021: Protocol messages are CBOR, bounded, and the manifest is signed with Ed25519

- Status: Accepted
- Date: 2026-10-30

## Context

When a player joins, the server tells the game who it is and which mods to install, and the game reports its progress and the result. The launcher will reuse the same messages to talk to the game. Every message comes from a peer that may be hostile or broken, and the list of mods decides what code the game downloads and runs.

## Decision

`rloaded-protocol` defines the messages once: `ServerHello`, `ClientHello`, `Manifest` with its `ModEntry` list, `Progress` and `InstallResult`. `PROTOCOL_VERSION` is 1.

- **Encoding.** `ProtocolCodec` writes CBOR with kotlinx-serialization-cbor 1.11.0, the version Fabric Language Kotlin already ships in the game (ADR 0019). The JDK has no CBOR encoder. The library is Apache-2.0 and 72 KB, released with kotlinx-serialization. Its CBOR format is marked experimental, so the codec opts in once, in that file.
- **Strict decoding.** A field the message does not know is refused. Messages over 1 MiB are refused before decoding. Every bound lives in the message type itself and is checked when a message is built or decoded: Modrinth ids of 1 to 16 letters or digits, a file name ending in `.jar` without a path separator or control character, a file size from 1 byte to 512 MiB, at most 1,024 entries with each project once, a percent from 0 to 100, a report of at most 16 KiB. Decoding returns `Outcome`: `TooLarge`, or `Malformed` with the reason.
- **Typed values.** Hashes are `Checksum` values of the expected algorithm; the server key and the signature are fixed-size byte types. The install result is a sealed type, `Loaded`, `RefusedByUser` or `Failed`, instead of a status with optional fields.
- **Signature.** The server signs the manifest's kind and entries with its Ed25519 key, through the JDK's `Signature` API. The signed bytes are the compact JSON of kind and entries, fields in declaration order, written by the same code on both sides. Signing the kind too stops a delta being passed off as a full list, or the reverse.

Admin requests over the game connection are left for when the admin surfaces need them.

## Alternatives

- **JSON on the wire.** Readable, but larger, and the signature already needs one exact byte form.
- **Protocol Buffers.** A schema compiler and a runtime that the game does not ship.
- **RFC 8785 canonical JSON for the signed bytes.** It matters when independent implementations sign and check; here both sides run this module.

## Consequences

- A manifest carries no date and no session, so an older manifest of the same server can be replayed on an unencrypted connection: issue #36.
- The kotlinx CBOR API may change when it leaves experimental status; the version only moves with Fabric Language Kotlin.
- Storing the server's private key and the keys a player trusts is the trust store's job, not this module's.

## Enforcement

`ProtocolCodecTest`: every message round-trips; an unknown field, a value out of bounds and an oversized message are refused with their error; 2,000 truncated or altered inputs return an error and never throw. `ManifestSigningTest`: a manifest signed by the trusted key passes, and one signed by another key or changed after signing is refused. The codec tests failed when unknown fields were accepted and bound violations were not caught.
