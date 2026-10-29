#!/usr/bin/env bash
# Tests for scripts/check-git-identity.sh against real Git repositories in a temporary directory.
# A fake gh answers `gh api user` according to the token it receives.
set -euo pipefail

# shellcheck source=scripts/tests/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

readonly script="${repo_root}/scripts/check-git-identity.sh"
readonly github_url="https://github.com/calamech/rloaded.git"
readonly calamech_email="267289534+calamech@users.noreply.github.com"
readonly zero_oid="0000000000000000000000000000000000000000"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
isolate_git "$work"

mkdir "${work}/bin"
cat >"${work}/bin/gh" <<'EOF'
#!/usr/bin/env bash
# Fake gh: `gh api user --jq .login` prints the account that owns GH_TOKEN.
if [[ "$*" != "api user --jq .login" ]]; then
  echo "fake gh: unexpected arguments: $*" >&2
  exit 2
fi
case "${GH_TOKEN:-}" in
  token-calamech) echo calamech ;;
  token-other) echo someone-else ;;
  *)
    echo "HTTP 401: Bad credentials" >&2
    exit 1
    ;;
esac
EOF
chmod +x "${work}/bin/gh"
export PATH="${work}/bin:${PATH}"

# Creates a repository set up as in ADR 0003, with one commit already on the remote.
# Its credential helper returns the token stored in "${repo}.token".
new_repo() {
  repo="${work}/$1"
  git init -q --bare "${repo}-remote.git"
  git init -q -b main "$repo"
  git -C "$repo" config user.name Calamech
  git -C "$repo" config user.email "$calamech_email"
  git -C "$repo" config credential.helper "!f() { test \"\$1\" = get || exit 0; echo username=calamech; echo password=\$(cat '${repo}.token'); }; f"
  printf 'token-calamech\n' >"${repo}.token"
  git -C "$repo" remote add origin "${repo}-remote.git"
  git -C "$repo" remote set-url --push origin "$github_url"
  git -C "$repo" commit -q --allow-empty -m "chore: initial commit"
  publish
}

publish() {
  git -C "$repo" push -q "${repo}-remote.git" main
  git -C "$repo" fetch -q origin
}

setup_check() {
  (cd "$repo" && "$script")
}

# pre_push <url> <local oid>: runs the hook mode for a push of main.
pre_push() {
  (cd "$repo" && printf 'refs/heads/main %s refs/heads/main %s\n' "$2" "$zero_oid" | "$script" pre-push origin "$1")
}

head_oid() {
  git -C "$repo" rev-parse HEAD
}

new_repo setup-ok
expect "setup check passes for Calamech" 0 "OK, every commit and push uses calamech" setup_check

new_repo setup-other-account
printf 'token-other\n' >"${repo}.token"
expect "setup check refuses a credential of another account" 1 "belongs to 'someone-else', expected 'calamech'" setup_check

new_repo setup-revoked-token
printf 'token-revoked\n' >"${repo}.token"
expect "setup check refuses a credential GitHub rejects" 1 "GitHub rejected the credential" setup_check

new_repo setup-wrong-email
git -C "$repo" config user.email "someone@example.com"
expect "setup check refuses another user.email" 1 "user.email is 'someone@example.com'" setup_check

new_repo setup-wrong-name
git -C "$repo" config user.name "Someone"
expect "setup check refuses another user.name" 1 "user.name is 'Someone'" setup_check

new_repo push-ok
git -C "$repo" commit -q --allow-empty -m "feat: add a feature"
expect "pre-push accepts commits by Calamech" 0 "OK" pre_push "$github_url" "$(head_oid)"

new_repo push-other-author
git -C "$repo" commit -q --allow-empty --author="Someone <someone@example.com>" -m "feat: add a feature"
expect "pre-push refuses a commit by another author" 1 "'Someone <someone@example.com> | Calamech <${calamech_email}>'" pre_push "$github_url" "$(head_oid)"

new_repo push-other-committer
GIT_COMMITTER_EMAIL="someone@example.com" git -C "$repo" commit -q --allow-empty -m "feat: add a feature"
expect "pre-push refuses a commit by another committer" 1 "| Calamech <someone@example.com>'" pre_push "$github_url" "$(head_oid)"

new_repo push-co-author
git -C "$repo" commit -q --allow-empty -m "feat: add a feature" -m "Co-authored-by: Someone <someone@example.com>"
expect "pre-push refuses a Co-authored-by trailer" 1 "has a Co-authored-by trailer" pre_push "$github_url" "$(head_oid)"

new_repo push-other-account
git -C "$repo" commit -q --allow-empty -m "feat: add a feature"
printf 'token-other\n' >"${repo}.token"
expect "pre-push refuses a credential of another account" 1 "belongs to 'someone-else'" pre_push "$github_url" "$(head_oid)"

new_repo push-ssh
expect "pre-push refuses a non-HTTPS remote" 1 "is not https://github.com/" pre_push "git@github.com:calamech/rloaded.git" "$(head_oid)"

new_repo push-delete
expect "pre-push accepts a branch deletion" 0 "OK" pre_push "$github_url" "$zero_oid"

new_repo push-published-history
git -C "$repo" commit -q --allow-empty --author="Someone <someone@example.com>" -m "chore: already published"
publish
git -C "$repo" commit -q --allow-empty -m "feat: add a feature"
expect "pre-push ignores commits already on the remote" 0 "OK" pre_push "$github_url" "$(head_oid)"

new_repo push-unknown-commit
expect "pre-push refuses a commit it cannot list" 1 "cannot list the commits" pre_push "$github_url" "1111111111111111111111111111111111111111"

new_repo push-bad-usage
expect "pre-push refuses missing arguments" 1 "usage:" bash -c "cd '${repo}' && '${script}' pre-push origin </dev/null"

finish
