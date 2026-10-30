# ADR 0037: An add refuses versions younger than 72 hours and projects under 1,000 downloads

- Status: Accepted
- Date: 2026-10-31

## Context

A mod added to a server runs on every player's computer. A malicious upload, or a broken release pulled within hours, is most likely in a new version or an unknown project. Modrinth records when each version was published and how often each project was downloaded (ADR 0036), so an add can wait for both before it trusts a mod.

## Decision

- `Reputation` (in `rloaded-admin`) refuses, after resolution and before any download, every version published less than `reputation.minVersionAgeHours` ago (72 by default, 0 to 8760) and every project downloaded fewer than `reputation.minProjectDownloads` times (1,000 by default, 0 to one billion). Dependencies are checked like the mod asked for. All failures are listed in one refusal. Zero turns a rule off; download counts are not read then.
- The settings live in `rloaded-server.toml`, `[reputation]`. `ServerConfig.gameProperties()` is the one place that turns the file's settings into the system properties the game reads; the bootstrap and the test harnesses both use it, so a new setting reaches every server. The runtime mod refuses to start a dedicated server whose properties lack the rules.
- The age is measured against the server's clock at the time of the add.

Checked on 2026-10-31 with `AdminAddGameTest`: a version published a moment ago was refused with "published on …, less than 72 hours ago", and with the age rule disabled in the code it was added and the test failed. `AdmissionServiceTest` checks that a dependency failing both rules is reported twice and nothing is written.

## Alternatives

- **Thresholds hard-coded.** An admin who knows a mod's author, or tests their own mod, needs to lower them; a setting with an explicit zero keeps that a deliberate choice.
- **Checking on the players' side too.** Players only install from servers they trust, and the server's own settings decide what it adds; a client-side guard with other thresholds would refuse mods its server admitted. Left for later, if a server's thresholds come to be sent to players.

## Consequences

- A mod updated minutes ago cannot be added before three days pass, unless the admin lowers the threshold.
- Download counts are Modrinth's and can be inflated; the rule filters out unknown projects, it does not prove a mod safe.
- One more request per add, for the project records.

## Enforcement

`ReputationRulesTest`, `AdmissionServiceTest`, `ServerConfigTest`, `BootstrapTest`, and `AdminAddGameTest` in the `game-test` CI job.
