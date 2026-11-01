#!/usr/bin/env bash
# The release version, Automagik's way: every release is 0.N.0 and the next one is 0.(highest N + 1).0.
# version.properties holds it; the app's versionName reads it. Usage:
#   scripts/version.sh current      print version.properties
#   scripts/version.sh next         the next release from the v0.N.0 tags
#   scripts/version.sh set <x.y.z>  write version.properties
set -euo pipefail
cd "$(dirname "$0")/.."
MAJOR=0
case "${1:-}" in
  current) sed -n 's/^version=//p' version.properties ;;
  next)
    n=$(git tag --list "v${MAJOR}.*.0" | sed -nE "s/^v${MAJOR}\.(0|[1-9][0-9]{0,3})\.0$/\1/p" | sort -n | tail -1)
    echo "${MAJOR}.$(( ${n:-0} + 1 )).0" ;;
  set)
    [[ "${2:-}" =~ ^${MAJOR}\.(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,3})$ ]] || { echo "not a version: ${2:-} (want ${MAJOR}.N.P)" >&2; exit 1; }
    printf 'version=%s\n' "$2" > version.properties ;;
  *) echo "usage: scripts/version.sh current | next | set <version>" >&2; exit 2 ;;
esac
