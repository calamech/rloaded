#!/usr/bin/env bash
# Checks that commits and pushes in this repository use the GitHub account Calamech.
# See docs/adr/0003-git-identity.md.
#
# Usage:
#   scripts/check-git-identity.sh                    check the repository setup
#   scripts/check-git-identity.sh pre-push <remote> <url>
#                                                    Git pre-push hook mode, refs on stdin
set -euo pipefail

readonly expected_login="calamech"
readonly expected_name="Calamech"
readonly expected_email="267289534+calamech@users.noreply.github.com"
readonly zero_oid="0000000000000000000000000000000000000000"

failures=0

fail() {
  printf 'check-git-identity: %s\n' "$1" >&2
  failures=$((failures + 1))
}

check_config() {
  local name email
  name=$(git config --local --get user.name || true)
  email=$(git config --local --get user.email || true)
  if [[ "$name" != "$expected_name" ]]; then
    fail "user.name is '${name}', expected '${expected_name}'. Fix: git config --local user.name ${expected_name}"
  fi
  if [[ "$email" != "$expected_email" ]]; then
    fail "user.email is '${email}', expected '${expected_email}'. Fix: git config --local user.email ${expected_email}"
  fi
}

# Asks Git for the credential it would send to the given URL, then asks GitHub who owns it.
check_push_account() {
  local url="$1" token login
  if [[ "$url" != https://github.com/* ]]; then
    fail "remote URL '${url}' is not https://github.com/...; only HTTPS pushes to GitHub can be checked"
    return
  fi
  token=$(printf 'url=%s\n\n' "$url" | GIT_TERMINAL_PROMPT=0 git credential fill 2>/dev/null | sed -n 's/^password=//p') || true
  if [[ -z "$token" ]]; then
    fail "Git has no credential for ${url}. See docs/adr/0003-git-identity.md for the credential helper setup"
    return
  fi
  if ! login=$(GH_TOKEN="$token" gh api user --jq .login 2>/dev/null); then
    fail "GitHub rejected the credential Git would use for ${url} (gh api user failed)"
    return
  fi
  if [[ "$login" != "$expected_login" ]]; then
    fail "the credential Git would use for ${url} belongs to '${login}', expected '${expected_login}'. See docs/adr/0003-git-identity.md"
  fi
}

check_commit() {
  local commit="$1" identity message
  identity=$(git log -1 --format='%an <%ae> | %cn <%ce>' "$commit")
  if [[ "$identity" != "${expected_name} <${expected_email}> | ${expected_name} <${expected_email}>" ]]; then
    fail "commit ${commit:0:12} has author and committer '${identity}', expected '${expected_name} <${expected_email}>' for both"
  fi
  message=$(git log -1 --format=%B "$commit")
  if grep -qi '^co-authored-by:' <<<"$message"; then
    fail "commit ${commit:0:12} has a Co-authored-by trailer; only ${expected_name} may appear as a contributor"
  fi
}

# Checks every commit the push would publish, read from the hook's stdin.
check_pushed_commits() {
  local remote="$1" local_ref local_oid remote_ref remote_oid commits commit
  # shellcheck disable=SC2034 # remote_ref and remote_oid are part of the hook's input format.
  while read -r local_ref local_oid remote_ref remote_oid; do
    # Deletions push nothing, and a blank line carries no ref at all.
    if [[ -z "$local_oid" || "$local_oid" == "$zero_oid" ]]; then
      continue
    fi
    if ! commits=$(git rev-list "$local_oid" --not --remotes="$remote"); then
      fail "cannot list the commits pushed from ${local_ref}"
      continue
    fi
    for commit in $commits; do
      check_commit "$commit"
    done
  done
}

main() {
  case "${1:-}" in
    "")
      check_config
      check_push_account "$(git remote get-url --push origin)"
      ;;
    pre-push)
      if [[ $# -ne 3 ]]; then
        fail "usage: $0 pre-push <remote> <url>"
      else
        check_config
        check_push_account "$3"
        check_pushed_commits "$2"
      fi
      ;;
    *)
      fail "unknown mode '$1'. Usage: $0 [pre-push <remote> <url>]"
      ;;
  esac
  if [[ $failures -ne 0 ]]; then
    printf 'check-git-identity: %d problem(s) found.\n' "$failures" >&2
    exit 1
  fi
  printf 'check-git-identity: OK, every commit and push uses %s.\n' "$expected_login"
}

main "$@"
