#!/usr/bin/env bash
# Tests for scripts/release-version.sh against catalogs in a temporary directory.
set -euo pipefail

# shellcheck source=scripts/tests/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

readonly script="${repo_root}/scripts/release-version.sh"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
printf '[versions]\nminecraft = "26.3"\nrloaded = "0.4.2"\n' >"${work}/catalog.toml"
printf '[versions]\nminecraft = "26.3"\n' >"${work}/no-version.toml"

expect "prints the version" 0 "0.4.2" env CATALOG="${work}/catalog.toml" "$script"
expect "accepts the matching tag" 0 "0.4.2" env CATALOG="${work}/catalog.toml" "$script" v0.4.2
expect "refuses another tag" 1 "tag v0.4.3 does not match version 0.4.2; tag v0.4.2 instead" \
  env CATALOG="${work}/catalog.toml" "$script" v0.4.3
expect "refuses a tag without the v" 1 "tag 0.4.2 does not match" env CATALOG="${work}/catalog.toml" "$script" 0.4.2
expect "refuses a catalog without the version" 1 "no rloaded version in" env CATALOG="${work}/no-version.toml" "$script"
expect "reads the repository's catalog" 0 "" "$script"

finish
