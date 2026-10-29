# ADR 0007: Conventions for Java modules

- Status: Accepted
- Date: 2026-10-29

## Context

The Java agent, and later the mixins of the runtime mod, are written in Java: Mixin and early loader hooks are simplest there. Java modules need the same bar as the Kotlin ones from ADR 0004: formatting, static analysis, warnings as errors and a coverage floor.

## Decision

`rloaded.java-library` in `build-logic/conventions` applies to Java modules:

- JetBrains Runtime 25 compiles to Java 21 bytecode (`--release 21`), like the Kotlin libraries.
- `-Xlint:all -Werror`: any javac warning fails the build.
- **Error Prone** 2.51.0 through the `net.ltgt.errorprone` Gradle plugin 5.1.1. Its errors fail the build, and its warnings do too through `-Werror`. The plugin forks the compiler with the `--add-exports` flags Java 16 and later require.
- **google-java-format** 1.36.1 through Spotless, as in ADR 0004.
- **JaCoCo** 0.8.15 fails a module under 85 % line coverage.

## Alternatives

- **Kover**, as for Kotlin. On a module without the Kotlin plugin, Kover 0.9.11 reports "No sources" and its verification passes with nothing measured. Rejected: a check that cannot fail is worse than none.
- **JaCoCo 0.8.16**, released 2026-10-26. 0.8.15 (2026-06-04) is used until 0.8.16 has been out longer.

## Consequences

- Coverage uses two tools: Kover for Kotlin and JaCoCo for Java. Each module uses exactly one.
- Error Prone adds a few seconds to each Java compilation.

## Enforcement

`JavaLibraryConventionTest` builds a fixture Java library and checks that the build passes on clean code, checks the Java 21 class file version, and fails on a formatting violation, an Error Prone error (`DeadException`), a javac warning and coverage under 85 %.
