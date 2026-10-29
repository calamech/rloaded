#!/usr/bin/env bash
# Runs every script test and fails if any of them fails.
set -euo pipefail

status=0
for test in "$(dirname "${BASH_SOURCE[0]}")"/*.test.sh; do
  printf '== %s\n' "$(basename "$test")"
  bash "$test" || status=1
done
exit "$status"
