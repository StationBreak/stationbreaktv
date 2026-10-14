#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Fails if CI could be handed the owner's media server address (spec 03, Unit 4, R15). Run on a checkout:
#   bash .github/scripts/device-proof-guard.sh <dir>
# The on-device probe (OnDeviceServerProbeTest) reads its address from the `serverAddress` instrumentation argument.
# The owner supplies it by hand in a bridge session; no workflow, script or Gradle file may ever set it, because a
# value that comes from a CI secret or variable would land in a log.
# Rule, printed as "FAIL address-argument: <file>:<line>". Every text file in the tree is scanned (workflows, scripts,
# Gradle files in Kotlin or Groovy, build-logic plugins, a Taskfile or Makefile, anything), but only for the forms that
# hand the argument to the instrumentation, so an ordinary `serverAddress` parameter or property in app code passes:
#   - `-e serverAddress` or `--es serverAddress`, with any spacing or quoting
#   - the name as a quoted string literal, "serverAddress" or 'serverAddress' (a map key, a property name)
#   - `serverAddress` anywhere in a file that also says `am instrument` or `instrumentationRunnerArgument(s)`, which
#     covers `-Pandroid.testInstrumentationRunnerArguments.serverAddress`, gradle.properties and the Groovy form
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

# first_line <file> <pattern>...: print the first line number of <file> matching any pattern, return 1 for no match.
# grep exits 1 for "no match" and 2 or more for "could not read", which stops the guard instead of reading as clean.
first_line() {
  local file="$1" hits status=0 args=()
  shift
  for pattern in "$@"; do args+=(-e "$pattern"); done
  hits="$(grep -nIiE "${args[@]}" "$file" 2>/dev/null)" || status=$?
  if [ "$status" -eq 0 ]; then
    head -n1 <<<"$hits" | cut -d: -f1
    return 0
  elif [ "$status" -ne 1 ]; then
    echo "cannot read: $file" >&2
    exit 2
  fi
  return 1
}

# scan <file>: fail address-argument when <file> hands the argument to the instrumentation.
scan() {
  local line
  if ! line="$(first_line "$1" \
    '(^|[[:space:]"'\''])(-e|--es)[[:space:]]+["'\'']?serveraddress' \
    '["'\'']serveraddress["'\'']')"; then
    if first_line "$1" 'am[[:space:]]+instrument' 'instrumentationRunnerArgument' >/dev/null; then
      line="$(first_line "$1" 'serveraddress')" || return 0
    else
      return 0
    fi
  fi
  # File and line number only: the matched text could be a real address, and this goes to a CI log.
  echo "FAIL address-argument: $1:$line"
  failures=$((failures + 1))
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
