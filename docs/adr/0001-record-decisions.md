# ADR 0001: Record decisions as ADRs

- Status: Accepted
- Date: 2026-10-29

## Context

Rloaded hooks into Fabric Loader, Mixin and Minecraft internals. Many choices depend on how those behave in a given version, and the reason behind a choice is easy to lose once the code works.

## Decision

Each significant decision gets an Architecture Decision Record in `docs/adr/`.

- One decision per file, named `NNNN-short-title.md`, numbered in order and never renumbered.
- Sections: Context, Decision, Alternatives, Consequences, Enforcement.
- Alternatives cite dated facts: a release page, a source file, a measurement.
- Enforcement names the check that keeps the decision true: a test, a lint, a CI job, or "review" when nothing can check it automatically.
- An accepted ADR is not rewritten. A later ADR supersedes it, and the old one gets a dated "Superseded by" line under Status.

## Alternatives

- Pull request descriptions only. Rejected: hard to find later, and not reviewed as documents.
- A single design document edited in place. Rejected: it keeps the current answer but loses why it changed.

## Consequences

- A decision costs a short document in the same pull request.
- Reading `docs/adr/` in order explains the current design.

## Enforcement

Review: a pull request that makes a decision without an ADR is not merged.
