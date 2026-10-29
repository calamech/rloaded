#!/usr/bin/env bash
# Tests for scripts/check-pull-request.sh against a real Git repository in a temporary directory.
set -euo pipefail

# shellcheck source=scripts/tests/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

readonly script="${repo_root}/scripts/check-pull-request.sh"
readonly calamech_name="Calamech"
readonly calamech_email="267289534+calamech@users.noreply.github.com"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
isolate_git "$work"

repo="${work}/repo"
git init -q -b main "$repo"
git -C "$repo" config user.name "$calamech_name"
git -C "$repo" config user.email "$calamech_email"
git -C "$repo" commit -q --allow-empty -m "chore: initial commit"
base=$(git -C "$repo" rev-parse HEAD)

# commit <git commit options...>: adds an empty commit on top of base, prints its id.
commit() {
  git -C "$repo" checkout -q --detach "$base"
  git -C "$repo" commit -q --allow-empty "$@"
  git -C "$repo" rev-parse HEAD
}

calamech_commit=$(commit -m "feat: add a feature")

# check <title> <branch> <head> [body]: runs the check for a pull request.
check() {
  (cd "$repo" && PR_TITLE="$1" HEAD_REF="$2" HEAD_SHA="$3" PR_BODY="${4:-}" BASE_SHA="$base" "$script")
}

expect "accepts a pull request by Calamech" 0 "OK" \
  check "feat: add a feature" "feat/add-feature" "$calamech_commit" "Adds a feature."
expect "accepts a scoped breaking title" 0 "OK" \
  check "feat(core)!: change the lockfile format" "feat/lockfile-v2" "$calamech_commit"
expect "accepts an empty body" 0 "OK" \
  check "fix: handle a timeout" "fix/timeout" "$calamech_commit" ""

expect "refuses a title without a type" 1 "title 'Add a feature' is not a Conventional Commits header" \
  check "Add a feature" "feat/add-feature" "$calamech_commit"
expect "refuses an unknown type" 1 "is not a Conventional Commits header" \
  check "feature: add a feature" "feat/add-feature" "$calamech_commit"
expect "refuses a title without a description" 1 "is not a Conventional Commits header" \
  check "feat: " "feat/add-feature" "$calamech_commit"

expect "refuses a branch without a type prefix" 1 "branch 'add-feature' must be named <type>/<short-name>" \
  check "feat: add a feature" "add-feature" "$calamech_commit"
expect "refuses an uppercase branch name" 1 "must be named <type>/<short-name>" \
  check "feat: add a feature" "feat/Add-Feature" "$calamech_commit"

expect "refuses a Co-authored-by line in the body" 1 "the body has a Co-authored-by line" \
  check "feat: add a feature" "feat/add-feature" "$calamech_commit" "$(printf 'Adds a feature.\n\nCo-authored-by: Someone <someone@example.com>')"

other_author=$(commit --author="Someone <someone@example.com>" -m "feat: add a feature")
expect "refuses a commit by another author" 1 "is authored by 'Someone <someone@example.com>'" \
  check "feat: add a feature" "feat/add-feature" "$other_author"

other_committer=$(GIT_COMMITTER_NAME="Someone" GIT_COMMITTER_EMAIL="someone@example.com" commit -m "feat: add a feature")
expect "refuses a commit by another committer" 1 "is committed by 'Someone <someone@example.com>'" \
  check "feat: add a feature" "feat/add-feature" "$other_committer"

github_committer=$(GIT_COMMITTER_NAME="GitHub" GIT_COMMITTER_EMAIL="noreply@github.com" commit -m "feat: add a feature")
expect "accepts a commit made by GitHub on Calamech's behalf" 0 "OK" \
  check "feat: add a feature" "feat/add-feature" "$github_committer"

co_author=$(commit -m "feat: add a feature" -m "Co-authored-by: Someone <someone@example.com>")
expect "refuses a Co-authored-by trailer in a commit" 1 "has a Co-authored-by trailer" \
  check "feat: add a feature" "feat/add-feature" "$co_author"

renovate=$(GIT_COMMITTER_NAME="renovate[bot]" GIT_COMMITTER_EMAIL="29139614+renovate[bot]@users.noreply.github.com" \
  commit --author="renovate[bot] <29139614+renovate[bot]@users.noreply.github.com>" -m "chore(deps): update kotlin to v2.4.22")
expect "accepts Renovate on a Renovate branch" 0 "OK" \
  check "chore(deps): update kotlin to v2.4.22" "renovate/kotlin-monorepo" "$renovate"
expect "refuses Renovate on another branch" 1 "is authored by 'renovate[bot]" \
  check "chore(deps): update kotlin to v2.4.22" "chore/kotlin" "$renovate"

expect "refuses a pull request without commits" 1 "the pull request has no commits" \
  check "feat: add a feature" "feat/add-feature" "$base"
expect "refuses an unknown head commit" 1 "cannot list commits" \
  check "feat: add a feature" "feat/add-feature" "1111111111111111111111111111111111111111"
expect "refuses a missing input" 2 "environment variable HEAD_REF is not set" \
  env -u HEAD_REF PR_TITLE="feat: x" PR_BODY="" BASE_SHA="$base" HEAD_SHA="$calamech_commit" "$script"

finish
