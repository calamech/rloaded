# ADR 0003: Commits and pushes use the Calamech account

- Status: Accepted
- Date: 2026-10-29

## Context

Rloaded is maintained from the GitHub account Calamech, on a machine where other GitHub accounts are signed in to `gh`. Git falls back to the global identity and the global credential helper, so a commit or a push can silently go out under the wrong account.

`gh auth git-credential` only serves the active `gh` account. It ignores the username Git asks for (checked with gh 2.102.1 on 2026-10-29). `gh auth switch` would fix this repository but change the account for every other repository on the machine.

## Decision

The identity is set in the repository config, never in the global config:

```sh
git config --local user.name Calamech
git config --local user.email 267289534+calamech@users.noreply.github.com
git config --local --replace-all credential.https://github.com.helper ''
git config --local --add credential.https://github.com.helper \
  '!f() { test "$1" = get || exit 0; echo username=calamech; echo "password=$(gh auth token --hostname github.com --user calamech)"; }; f'
git config --local core.hooksPath .githooks
```

The empty helper entry clears the helpers inherited from the global config. The second one hands Git the token of the Calamech account, whichever `gh` account is active.

Two checks enforce it:

- `scripts/check-git-identity.sh` runs as the pre-push hook. It refuses the push if the repository identity is wrong, if the credential Git would send belongs to another account, or if a pushed commit has another author or committer, or a `Co-authored-by` trailer. Run it without arguments to check a fresh clone.
- `scripts/check-pull-request.sh` runs in CI on every pull request. It checks the title, the branch name, the body, and the author and committer of every commit. Renovate may author commits on `renovate/` branches.

## Alternatives

- `gh auth switch` before each push. Rejected: it changes the account for every repository on the machine, and nothing checks that it was done.
- A separate SSH key for Calamech with a host alias. Workable, but it needs a new key registered on the account and a non-standard remote URL. Reconsider if HTTPS pushes stop being possible.

## Consequences

- A fresh clone needs the commands above once. Until then the hook refuses to push, and says which setting is wrong.
- Outside contributions would fail the pull request check. Opening the project to contributors means superseding this ADR and relaxing the author rule.

## Enforcement

- Pre-push hook: `scripts/check-git-identity.sh`.
- CI job `hygiene` on every pull request: `scripts/check-pull-request.sh`.
- Both scripts are tested by `scripts/tests/run.sh` in the CI job `scripts`, with a passing and a refused case for each rule.
