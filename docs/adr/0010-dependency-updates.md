# ADR 0010: Dependency updates

- Status: Accepted
- Date: 2026-10-29

## Context

Every version is pinned: in `gradle/libs.versions.toml`, in the Gradle wrapper, and as commit SHAs for GitHub Actions. Pins only stay safe if updates arrive regularly, through the same checks as any other change.

## Decision

The Renovate GitHub app opens update pull requests, configured in `.github/renovate.json`:

- Commit and pull request titles follow Conventional Commits (`chore(deps): …`), so they pass the `hygiene` check.
- A release must be at least 3 days old before Renovate proposes it.
- Stable releases only: alpha, beta, release candidate, milestone, EAP, dev and snapshot versions are ignored.
- GitHub Actions stay pinned to commit SHAs, with the version in a comment.
- Kotlin and the libraries released with it are updated together.
- At most 5 update pull requests are open at a time; the dependency dashboard issue lists the rest.

Renovate's commits are authored by `renovate[bot]`, which the `hygiene` check accepts on `renovate/` branches only.

## Alternatives

- **Dependabot.** It cannot group updates across ecosystems as finely, and has no minimum release age for Gradle.
- **Self-hosted Renovate** in a workflow, committing as the maintainer. Rejected by the maintainer in favor of the app.

## Consequences

- Renovate does not regenerate `gradle/verification-metadata.xml` unless it may run the Gradle wrapper. Until then, a Gradle update pull request fails `check`, and the checksums are regenerated as ADR 0004 describes, in the same pull request.
- `renovate[bot]` appears as a contributor once its pull requests are merged.

## Enforcement

- `renovate-config-validator --strict` accepted the configuration on 2026-10-29.
- Update pull requests go through the same required checks as any other change.
