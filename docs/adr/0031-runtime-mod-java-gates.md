# ADR 0031: The runtime mod's Java code passes the agent's gates

- Status: Accepted
- Date: 2026-10-30

## Context

The runtime mod holds most of the engine and the join code, in Java, and its build is separate from the main build (ADR 0011). Until now that build only compiled with `-Xlint:all -Werror`: no google-java-format, no Error Prone, and its build scripts were not formatted either. Its test mods live in source sets that no unit test compiles, so a gate wired to `compileJava` alone would miss them.

## Decision

- The formatting and lint part of `rloaded.java-library` (ADR 0007) moves to a convention of its own, `rloaded.java-quality`: google-java-format and ktlint through Spotless, Error Prone, `-Xlint:all -Werror`, and `check` compiling every source set. `rloaded.java-library` applies it and keeps the Java 21 release, the tests and the JaCoCo floor.
- The runtime build includes `build-logic` in its plugin management and applies `rloaded.java-quality`. It keeps Java 25, which Minecraft 26.3 needs, and has no coverage floor: game tests exercise it.
- The runtime build turns off one javac category, `classfile`. Guava and Log4j, which the game ships, carry annotations whose classes the game leaves out (`com.google.errorprone.annotations.DoNotMock`, `org.osgi.annotation.versioning.Version`). Once Error Prone reads those annotations, javac warns about every one, 100 warnings on the first source set, measured on 2026-10-30 with Error Prone 2.51.0. The warnings are about jars that are not ours.
- Error Prone findings in our code are fixed, or suppressed on the narrowest declaration with the reason next to it. The first run found 25, in 12 files: three ignored futures in the client's join (a failed download or resource reload would have left the server waiting; the join now ends with the error), identity comparisons in tests, arrays in the join payload records, a loopback address resolved by name.

## Alternatives

- **Adding the missing annotation jars to the compile class path.** Each library brings its own set (Error Prone annotations for Guava, OSGi annotations for Log4j), so the list grows with the game's libraries, and every jar adds checksums to record. Turning off a category that only reports on class files of dependencies costs less.
- **Spotless and Error Prone configured again in the runtime build.** Two copies of the same versions and settings would drift.

## Consequences

- The runtime's Java code and build scripts follow the same format as the agent; the first run reformatted 31 Java files and the build script.
- A `classfile` warning about our own classes would go unseen in the runtime build. Our classes are compiled from source, where such problems surface as compile errors.
- The runtime has no Kotlin code. Kotlin there would need the Kotlin plugin and the gates of `rloaded.kotlin-library`.

## Enforcement

`JavaQualityConventionTest` (build-logic) fails the build on a formatting error, an Error Prone error and an Error Prone warning in a source set that no test uses; `./gradlew check` runs the runtime build's `check`, which applies the convention.
