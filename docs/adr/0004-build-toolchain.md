# ADR 0004: Build toolchain and quality gates

- Status: Accepted
- Date: 2026-10-29

## Context

Rloaded is a Gradle multi-module build in Kotlin and Java. The game runs on JetBrains Runtime 25. The server bootstrap must start on any Java 21 or newer, and it reuses the pure libraries. The quality gates must fail the build, not print warnings.

Versions below are the latest stable releases on 2026-10-29, checked on Maven Central, the Gradle Plugin Portal and services.gradle.org.

## Decision

- **Gradle 9.8.2** through the wrapper, with the distribution SHA-256 pinned in `gradle-wrapper.properties`.
- **JetBrains Runtime 25** runs the Gradle daemon (`gradle/gradle-daemon-jvm.properties`) and compiles the code (Java toolchains). The foojay resolver downloads it when missing.
- **Kotlin 2.4.21**. Every version lives in `gradle/libs.versions.toml`.
- **Convention plugins** in `build-logic/conventions`. `rloaded.kotlin-library` applies to pure Kotlin modules: Java 21 bytecode (`-Xjdk-release=21`), `explicitApi()`, warnings as errors, JUnit, Spotless, detekt and Kover.
- **Spotless** 8.10.4 with ktlint 1.8.0 for Kotlin and google-java-format 1.36.1 for Java.
- **detekt** 1.23.8, from its command line tool on JetBrains Runtime 21, with the default rules and zero findings allowed.
- **NoNotNullAssertion**, a detekt rule in `build-logic/detekt-rules`, refuses `!!` outside tests.
- **Kover** 0.9.11 fails a module under 85 % line coverage.
- **Dependency verification** with SHA-256 checksums in `gradle/verification-metadata.xml`.
- **CI** runs `./gradlew check` on Linux, macOS and Windows.

## Alternatives

- **detekt 2.0** supports Java 25, but only alpha releases exist (2.0.0-alpha.6, 2026-08-04). Rejected: stable releases only.
- **The detekt 1.23.8 Gradle plugin.** It runs detekt inside the Gradle daemon. Its embedded Kotlin 2.0.21 compiler fails with `IllegalArgumentException: 25.0.5` while parsing the Java version. Rejected; the command line tool on Java 21 does not have this problem.
- **detekt's `UnsafeCallOnNullableType`** needs type resolution. The Kotlin 2.0 compiler in detekt cannot read classes compiled by Kotlin 2.4, so type resolution is not an option. A syntax-only rule does the job.
- **google-java-format 1.37.0.** Spotless 8.10.4 fails on it with `NoSuchMethodError: JavaFormatterOptions$Style.valueOf`. 1.36.1 works.

## Consequences

- Builds download two runtimes: JetBrains Runtime 25 and 21.
- google-java-format runs in the daemon, which needs `--add-exports` for the `jdk.compiler` packages (`gradle.properties`).
- Adding or updating a dependency means regenerating the checksums from an empty Gradle cache, because a warm cache can skip metadata files that a fresh CI runner downloads: `GRADLE_USER_HOME=$(mktemp -d) ./gradlew --write-verification-metadata sha256 check`. A Renovate pull request that does not do it fails CI until someone does.
- Revisit when detekt 2.0 is stable: it can then run in the daemon with type resolution, and `NoNotNullAssertion` can go.

## Enforcement

- `./gradlew check` in the CI job `check` on three operating systems.
- `build-logic/conventions` functional tests (Gradle TestKit) build a fixture library and check that each gate passes on clean code and fails on its defect: formatting, `!!`, a default detekt rule, coverage, explicit API and compiler warnings. They also check the Java 21 class file version.
- Dependency verification fails the build on any unknown or changed artifact.
