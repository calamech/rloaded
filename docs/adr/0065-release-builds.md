# ADR 0065: Releases are built by CI from a version tag, and numbering starts at 1.0.0

- Status: Accepted
- Date: 2026-11-02

## Context

Players need an installer for the launcher, and admins need `rloaded-server.jar`. Building them by hand on one machine would give unverifiable files, and the macOS installer can only be built on macOS. The launcher's Compose build already describes a `.dmg`, an `.msi` and a `.deb`.

Seen on 2026-11-02: `jpackage` from JetBrains Runtime 25.0.4.1 refuses to build the macOS app with version 0.1.0 ("The first number in an app-version cannot be zero or negative"). With 1.0.0 the `.dmg` builds, the app's `Info.plist` reads 1.0.0, and the packaged launcher starts.

## Decision

- The version of Rloaded has one owner, `rloaded` in `gradle/libs.versions.toml`; the launcher package, the launcher itself and the runtime mod read it. Numbering starts at **1.0.0**, the same number everywhere, the macOS app included.
- `.github/workflows/release.yml` builds, on its own runner for each: the `.dmg` for macOS on Apple silicon (`macos-15`) and on Intel (`macos-15-intel`), the `.msi` for Windows x64, the `.deb` for Linux x64, and `rloaded-server.jar`. Each installer is named `Rloaded-<version>-<platform>.<format>`.
- On a tag `v<version>`, it publishes them as a GitHub release with `SHA256SUMS` and the notes in `docs/releases/<version>.md`. `scripts/release-version.sh` refuses a tag that is not the catalog's version, and the job fails if the notes are missing.
- A pull request that changes how they are built runs the same builds, without publishing.
- The builds are not signed: there is no Apple Developer ID or Windows code-signing certificate yet.

## Alternatives

- **Starting at 0.1.0 with 1.0.0 only in the macOS app.** The Finder would show another number than the release, and the update check would need a mapping between the two.
- **Building the installers on a developer's machine.** Nothing would show which sources a file came from, and one machine cannot build the three formats.
- **Installers for Windows and Linux on ARM.** No user asks for them yet; the matrix takes one line each when they do.

## Consequences

- macOS and Windows warn before they open an unsigned app; the install instructions have to say how to get past that. Signing needs certificates only the project owner can get.
- `SHA256SUMS` comes from the same release as the files: it detects a damaged download, not a compromised account.
- A tag publishes for everyone at once; publishing is the project owner's call.

## Enforcement

`release.yml` on pull requests that touch the build of the installers, `scripts/tests/release-version.test.sh` in the `scripts` job, `LauncherVersionTest`.
