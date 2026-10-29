# ADR 0002: Git workflow

- Status: Accepted
- Date: 2026-10-29

## Context

The repository is public, so its history is part of what people read. It should be easy to follow and easy to revert.

## Decision

- Every change reaches `main` through a pull request.
- Branches are named `<type>/<short-name>`, where `type` is a Conventional Commits type: `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `build`, `ci`, `chore`, `revert`. `short-name` is lowercase kebab-case. Renovate branches keep their `renovate/` prefix.
- Pull request titles are Conventional Commits headers, because the title becomes the commit message on `main`.
- Pull requests are squash-merged once CI is green, and the branch is deleted.

Repository settings (2026-10-29):

- Squash merge is the only merge method. The squash commit takes the pull request title and body.
- Head branches are deleted automatically after merge.
- A ruleset on `main` blocks deletion and force pushes, requires linear history, and requires a pull request with every review thread resolved. Nobody can bypass it.

## Alternatives

- Merge commits. Rejected: one commit per pull request on a linear `main` is easier to read and to revert.
- Rebase merges. Rejected: they keep every work-in-progress commit on `main`.

## Consequences

- `main` reads as a list of Conventional Commits headers, one per pull request.
- Branch commits can be small; the pull request title and body are what lands on `main`.

## Enforcement

- Repository settings and the `main` ruleset listed above.
- Branch names and pull request titles: review until a CI check exists.
