#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Fails if CI could be handed the owner's media server address (spec 03, Unit 4, R15). Run on a checkout:
#   bash .github/scripts/device-proof-guard.sh <dir>
# The on-device probe (OnDeviceServerProbeTest) reads its address from the `serverAddress` instrumentation argument.
# The owner supplies it by hand in a bridge session; no workflow, script or Gradle file may ever set it, because a
# value that comes from a CI secret or variable would land in a log.
# Rule, printed as "FAIL address-argument: <file>:<line>":
#   address-argument  any text file in the tree names the `serverAddress` argument in any case: workflows, scripts,
#                     Gradle files (Kotlin or Groovy) and build-logic plugins, a Taskfile or Makefile, anything
# Exempt from the scan, by exact path: this script, its test, .github/scripts/fixtures/device-proof/, the probe's own
# sources under app/src/androidTest/, docs/ and CONTRIBUTING.md. They have to name the argument. A copy elsewhere is
# not exempt. .git, .gradle and each Gradle module's build/ output are not scanned, and neither are binary files.
# A file or directory that cannot be read is an error (exit 2), never a pass.
# A tripwire for mistakes, not a defence against a deliberate evasion.
# Exit: 0 clean, 1 the rule failed, 2 usage error or an unreadable file.
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <repo-dir>" >&2
  exit 2
fi
root="$1"
if [ ! -d "$root" ]; then
  echo "not a directory: $root" >&2
  exit 2
fi
cd "$root"

failures=0

# scan <file>: fail address-argument on the first line of <file> that names the argument. grep exits 1 for "no
# match" and 2 or more for "could not read", which stops the guard instead of reading as clean.
scan() {
  local hits status=0
  hits="$(grep -nIiE 'serveraddress' "$1" 2>/dev/null)" || status=$?
  if [ "$status" -eq 0 ]; then
    # File and line number only: the matched text could be a real address, and this goes to a CI log.
    echo "FAIL address-argument: $1:$(head -n1 <<<"$hits" | cut -d: -f1)"
    failures=$((failures + 1))
  elif [ "$status" -ne 1 ]; then
    echo "cannot read: $1" >&2
    exit 2
  fi
}

# Gradle's own output: <module>/build next to a build.gradle(.kts). A directory merely named build is scanned.
prune=()
while IFS= read -r module_build_file; do
  [ -n "$module_build_file" ] || continue
  prune+=(-path "$(dirname "$module_build_file")/build" -o)
done < <(find . \( -name .gradle -o -name .git -o -name node_modules \) -prune \
  -o \( -name build.gradle -o -name build.gradle.kts \) -type f -print)

files="$(find . \( "${prune[@]}" -name .gradle -o -name .git -o -name node_modules \) -prune \
  -o -type f \
  ! -path ./.github/scripts/device-proof-guard.sh ! -path ./.github/scripts/device-proof-guard.test.sh \
  ! -path './.github/scripts/fixtures/device-proof/*' ! -path './app/src/androidTest/*' \
  ! -path './docs/*' ! -path ./CONTRIBUTING.md -print | sort)" || {
  echo "cannot list the tree" >&2
  exit 2
}

while IFS= read -r file; do
  [ -n "$file" ] || continue
  scan "$file"
done <<<"$files"

if [ "$failures" -ne 0 ]; then
  echo "$failures file(s) name the device-proof server address argument (spec 03, Unit 4)"
  exit 1
fi
echo "device-proof guard: ok"
