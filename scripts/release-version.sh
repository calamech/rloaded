#!/usr/bin/env bash
# Prints the version of Rloaded, read from the version catalog. With a tag, checks that the tag is
# that version with a leading "v", so a release is never published under another number.
# Usage: release-version.sh [tag]
set -euo pipefail

catalog="${CATALOG:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/gradle/libs.versions.toml}"
version=$(sed -n 's/^rloaded = "\([^"]*\)"$/\1/p' "$catalog")
if [[ -z "$version" ]]; then
  echo "release-version: no rloaded version in $catalog" >&2
  exit 1
fi
if [[ $# -gt 0 && "$1" != "v$version" ]]; then
  echo "release-version: tag $1 does not match version $version; tag v$version instead" >&2
  exit 1
fi
echo "$version"
