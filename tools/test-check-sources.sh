#!/usr/bin/env bash
# Exercise the complete byte gate against source files without a final newline.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/litemask-source-gate-test.XXXXXX")"
trap 'rm -rf -- "$fixture_dir"' EXIT
mkdir -p "$fixture_dir/tools" "$fixture_dir/src"
cp "$ROOT/tools/check-sources.sh" "$fixture_dir/tools/"
: > "$fixture_dir/tools/byte-gate-exemptions.txt"

# No newline: read returns failure while still assigning the final line.
printf 'damaged \357\277\275' > "$fixture_dir/src/sample.txt"
if bash "$fixture_dir/tools/check-sources.sh" > "$fixture_dir/rejected.log" 2>&1; then
  printf '%s\n' 'FAIL: an unregistered replacement character was accepted'
  exit 1
fi
grep -q 'src/sample.txt:1:.*U+FFFD' "$fixture_dir/rejected.log"

# The same edge case must still allow an explicitly registered semantic use.
printf 'allowed_marker \357\277\275' > "$fixture_dir/src/sample.txt"
printf '%s' 'src/sample.txt | allowed_marker | synthetic regression fixture' \
  > "$fixture_dir/tools/byte-gate-exemptions.txt"
if ! bash "$fixture_dir/tools/check-sources.sh" > "$fixture_dir/allowed.log" 2>&1; then
  cat "$fixture_dir/allowed.log"
  printf '%s\n' 'FAIL: a registered replacement character was rejected'
  exit 1
fi
printf '%s\n' 'PASS: no-final-newline source is rejected unless explicitly exempted (2 cases)'
