# ADR 0009: Launcher window

- Status: Accepted
- Date: 2026-10-29

## Context

Players start the game from a desktop launcher on Windows, macOS and Linux. The first window only needs a player name, a server address and a Play button; the rest of the launcher grows from there. Logic must stay testable without a display.

## Decision

- **Compose Multiplatform for Desktop 1.12.2**, with Material 3, in `rloaded-launcher/launcher-app`. It runs on the JVM like the rest of Rloaded, and shares `launcher-core` directly.
- **All logic outside the screen.** `launcher-core` installs and starts the game (`Launcher`). `LauncherState` turns typed input into a `Status` and maps every `LaunchFailure` to a `FailureReason` with an exhaustive `when`. The window only renders that state.
- **No hard-coded UI strings.** Text lives in `composeResources/values/strings.xml`, read through Compose's generated `Res.string` accessors: an unknown key does not compile. Technical details of a failure go to the launcher log, not to the window.
- **Coverage.** `LauncherWindow.kt` and `Main.kt` are left out of the 85 % floor and checked by hand. `LauncherState` is fully covered.
- **Repositories.** Compose for Desktop needs a few AndroidX libraries that are only on Google's Maven repository. That repository is declared for the `androidx.*` groups only.
- **Checksums for every platform.** The desktop runtime has one artifact per platform. `resolveAllDesktopRuntimes` resolves all six, so the checksums can be recorded from one machine:
  `GRADLE_USER_HOME=$(mktemp -d) ./gradlew --write-verification-metadata sha256 check :rloaded-launcher:launcher-app:resolveAllDesktopRuntimes`.
- detekt accepts the capitalized names of `@Composable` functions, as Compose requires (`ignoreAnnotated` in `config/detekt/detekt.yml`). ktlint has no such setting that keeps its other defaults, so each composable function carries a `@Suppress("ktlint:standard:function-naming")` with that reason.

## Alternatives

- **Swing or JavaFX.** Older APIs, no shared state model with Kotlin coroutines, and JavaFX ships separately.
- **Compose Multiplatform 1.13.** Only alpha and beta releases on 2026-10-29.

## Consequences

- The window has no automated UI test yet. It was rendered off screen with `ImageComposeScene` on 2026-10-29 to check its layout and texts.
- The launcher version shown to the game is a constant until packaging defines the release version.

## Enforcement

- `LauncherStateTest`: invalid names and addresses, progress, the running game, its exit code, logged details, and a reason for every failure.
- Compose resource accessors: a missing string key fails compilation.
