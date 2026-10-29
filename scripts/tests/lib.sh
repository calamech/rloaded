# shellcheck shell=bash
# Shared helpers for the script tests. Sourced by each *.test.sh file.

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
readonly repo_root
tests_run=0
tests_failed=0

# Isolates Git from the developer's own configuration.
isolate_git() {
  local dir="$1"
  : >"${dir}/gitconfig"
  export GIT_CONFIG_GLOBAL="${dir}/gitconfig"
  export GIT_CONFIG_NOSYSTEM=1
  export GIT_TERMINAL_PROMPT=0
}

# expect <test name> <exit code> <text the output must contain, or ""> <command...>
expect() {
  local name="$1" want_code="$2" want_text="$3" output code=0
  shift 3
  output=$("$@" 2>&1) || code=$?
  tests_run=$((tests_run + 1))
  if [[ $code -ne $want_code ]] || [[ -n "$want_text" && "$output" != *"$want_text"* ]]; then
    tests_failed=$((tests_failed + 1))
    printf 'FAIL %s\n  expected exit %s with output containing: %s\n  got exit %s with output:\n%s\n' \
      "$name" "$want_code" "$want_text" "$code" "$output"
  else
    printf 'ok   %s\n' "$name"
  fi
}

finish() {
  printf '%d run, %d failed\n' "$tests_run" "$tests_failed"
  [[ $tests_failed -eq 0 ]]
}
