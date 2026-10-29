# Rloaded

Rloaded lets friends play on a Minecraft server where the admin adds Fabric mods on the fly. Players install nothing by hand, never restart their game, and see the new content right away.

> **Status:** early development. Nothing is usable yet.

Design decisions are recorded in [docs/adr](docs/adr/).

## Development

You need a JDK 17 or newer to start Gradle. Gradle then downloads the JetBrains Runtime versions the build uses.

```sh
./gradlew check
```

Before the first push, set up the Git identity and the pre-push hook as described in [ADR 0003](docs/adr/0003-git-identity.md).

## License

[MIT](LICENSE)

Rloaded is not an official Minecraft product. It is not approved by or associated with Mojang or Microsoft.
