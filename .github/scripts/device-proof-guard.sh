#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Fails if CI could be handed the owner's media server address (spec 03, Unit 4, R15). Run on a checkout:
#   bash .github/scripts/device-proof-guard.sh <dir>
# The on-device probe (OnDeviceServerProbeTest) reads its address from the `serverAddress` instrumentation argument.
# The owner supplies it by hand in a bridge session; no workflow, script or Gradle file may ever set it, because a
# value that comes from a CI secret or variable would land in a log.
# Rule, printed as "FAIL address-argument: <file>:<line>":
#   address-argument  any file under .github/, and any *.gradle.kts or gradle.properties elsewhere, names the
#                     `serverAddress` argument in any case (-e serverAddress, -P...serverAddress=,
#                     a variable that holds the name)
# Exempt from the scan, by exact path: this script, its test and .github/scripts/fixtures/device-proof/. They have
# to name the argument. A copy elsewhere is not exempt.
# A tripwire for mistakes, not a defence against a deliberate evasion.
# Exit: 0 clean, 1 the rule failed, 2 usage error.
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

# scan <file>: fail address-argument on the first line of <file> that names the argument.
scan() {
  local hits
  if hits="$(grep -niE 'serveraddress' "$1")"; then
    echo "FAIL address-argument: $1:$(head -n1 <<<"$hits")"
    failures=$((failures + 1))
  fi
}

while IFS= read -r file; do
  [ -n "$file" ] || continue
  scan "$file"
done < <(
  {
    if [ -d .github ]; then
      find .github -type f \
        ! -path '.github/scripts/fixtures/device-proof/*' ! -path .github/scripts/device-proof-guard.sh \
        ! -path .github/scripts/device-proof-guard.test.sh
    fi
    find . \( -name build -o -name .gradle -o -name .git -o -path ./.github \) -prune \
      -o \( -name '*.gradle.kts' -o -name gradle.properties \) -print
  } | sort
)

if [ "$failures" -ne 0 ]; then
  echo "$failures file(s) name the device-proof server address argument (spec 03, Unit 4)"
  exit 1
fi
echo "device-proof guard: ok"
