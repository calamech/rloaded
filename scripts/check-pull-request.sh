#!/usr/bin/env bash
# Checks a pull request against the Git rules in docs/adr/0002-git-workflow.md
# and docs/adr/0003-git-identity.md. Run by CI on every pull request.
#
# Inputs, from the environment:
#   PR_TITLE, PR_BODY, HEAD_REF   pull request title, body and head branch
#   BASE_SHA, HEAD_SHA            commits bounding the pull request
set -euo pipefail

readonly types="feat|fix|docs|test|refactor|perf|build|ci|chore|revert"
readonly title_pattern="^(${types})(\([a-z0-9-]+\))?!?: [^ ]"
readonly branch_pattern="^(${types})/[a-z0-9]+(-[a-z0-9]+)*$"
readonly calamech="Calamech <267289534+calamech@users.noreply.github.com>"
readonly renovate="renovate[bot] <29139614+renovate[bot]@users.noreply.github.com>"
readonly github_web="GitHub <noreply@github.com>"

failures=0

fail() {
  printf 'check-pull-request: %s\n' "$1" >&2
  failures=$((failures + 1))
}

require_env() {
  local name
  for name in PR_TITLE PR_BODY HEAD_REF BASE_SHA HEAD_SHA; do
    if ! printenv "$name" >/dev/null; then
      fail "environment variable ${name} is not set"
    fi
  done
  if [[ $failures -ne 0 ]]; then
    exit 2
  fi
}

check_title() {
  if [[ ! "$PR_TITLE" =~ $title_pattern ]]; then
    fail "title '${PR_TITLE}' is not a Conventional Commits header like 'feat(core): add the resolver'"
  fi
}

check_branch() {
  if [[ ! "$HEAD_REF" =~ $branch_pattern && "$HEAD_REF" != renovate/* ]]; then
    fail "branch '${HEAD_REF}' must be named <type>/<short-name>, like feat/trust-screen"
  fi
}

check_body() {
  if grep -qi '^co-authored-by:' <<<"$PR_BODY"; then
    fail "the body has a Co-authored-by line; it would become a co-author on main"
  fi
}

# Calamech authors every commit; Renovate may author commits on its own branches.
is_allowed_author() {
  [[ "$1" == "$calamech" ]] || [[ "$1" == "$renovate" && "$HEAD_REF" == renovate/* ]]
}

# GitHub may commit on the author's behalf, for example when updating a branch.
check_commits() {
  local commits commit author committer
  if ! commits=$(git rev-list "${BASE_SHA}..${HEAD_SHA}"); then
    fail "cannot list commits between ${BASE_SHA} and ${HEAD_SHA}"
    return
  fi
  if [[ -z "$commits" ]]; then
    fail "the pull request has no commits"
    return
  fi
  for commit in $commits; do
    author=$(git log -1 --format='%an <%ae>' "$commit")
    committer=$(git log -1 --format='%cn <%ce>' "$commit")
    if ! is_allowed_author "$author"; then
      fail "commit ${commit:0:12} is authored by '${author}', expected '${calamech}'"
    fi
    if [[ "$committer" != "$author" && "$committer" != "$github_web" ]]; then
      fail "commit ${commit:0:12} is committed by '${committer}', expected its author or '${github_web}'"
    fi
    if grep -qi '^co-authored-by:' <<<"$(git log -1 --format=%B "$commit")"; then
      fail "commit ${commit:0:12} has a Co-authored-by trailer"
    fi
  done
}

main() {
  require_env
  check_title
  check_branch
  check_body
  check_commits
  if [[ $failures -ne 0 ]]; then
    printf 'check-pull-request: %d problem(s) found.\n' "$failures" >&2
    exit 1
  fi
  printf 'check-pull-request: OK.\n'
}

main "$@"
