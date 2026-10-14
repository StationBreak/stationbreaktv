#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Fixture tests for device-proof-guard.sh. Each fixture is a small repo-shaped directory. The guard must exit 1 for
# every way of handing CI the on-device probe's server address, and 0 for the clean fixtures.
# Usage: bash .github/scripts/device-proof-guard.test.sh
# No -e: a failing case must be reported and counted, not end the run.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$here/device-proof-guard.sh"
fixtures="$here/fixtures/device-proof"
failures=0

# fail <case> <reason> <output...>
fail() {
  local name="$1" reason="$2"
  shift 2
  echo "FAIL $name: $reason"
  printf '%s\n' "$@" | sed 's/^/    /'
  failures=$((failures + 1))
}

# expect <fixture> <exit code>
expect() {
  local fixture="$1" want="$2" out got
  out="$(bash "$script" "$fixtures/$fixture" 2>&1)"
  got=$?
  if [ "$got" -ne "$want" ]; then
    fail "$fixture" "exit $got, want $want" "$out"
  elif [ "$want" -eq 1 ] && ! grep -q '^FAIL address-argument: ' <<<"$out"; then
    fail "$fixture" "failed without naming the rule" "$out"
  else
    echo "ok   $fixture (exit $got)"
  fi
}

# Clean: workflows that run the instrumented tests, and a Gradle file with an unrelated runner argument.
expect clean 0
expect gradle-kts-other-argument 0

# The address argument, however it is passed.
expect workflow-e-flag 1
expect workflow-gradle-property 1
expect workflow-lowercase 1
expect workflow-quoted-name 1
expect script-names-argument 1
expect gradle-kts-names-argument 1
expect gradle-properties-names-argument 1

# Anywhere else a build or script could set it: build-logic plugins, Groovy Gradle files, a Taskfile, a script.
expect build-logic-names-argument 1
expect groovy-names-argument 1
expect taskfile-names-argument 1
expect script-outside-github-names-argument 1

# Where the argument is allowed to be named: the docs, CONTRIBUTING and the probe test itself.
expect exempt-paths 0

# Only the guard, its test and its own fixtures are exempt, by exact path. A same-named script elsewhere, or another
# fixtures directory, is not.
expect script-named-like-guard 1
expect fixtures-dir-hides-script 1

# A file the guard cannot read must not read as clean. (root can read anything, so the case needs another user.)
if [ "$(id -u)" -ne 0 ]; then
  scratch="$(mktemp -d)"
  cp -R "$fixtures/clean/." "$scratch/"
  echo 'serverAddress' >"$scratch/unreadable.txt"
  chmod 000 "$scratch/unreadable.txt"
  out="$(bash "$script" "$scratch" 2>&1)"
  got=$?
  chmod 600 "$scratch/unreadable.txt"
  rm -rf "$scratch"
  if [ "$got" -eq 2 ]; then
    echo "ok   unreadable file (exit $got)"
  else
    fail "unreadable file" "exit $got, want 2" "$out"
  fi
fi

# No argument: usage error, not a pass on the current directory.
out="$(bash "$script" 2>&1)"
got=$?
if [ "$got" -eq 2 ]; then
  echo "ok   no argument (exit $got)"
else
  fail "no argument" "exit $got, want 2" "$out"
fi

# A directory that doesn't exist is a usage error too.
out="$(bash "$script" "$fixtures/does-not-exist" 2>&1)"
got=$?
if [ "$got" -eq 2 ]; then
  echo "ok   missing directory (exit $got)"
else
  fail "missing directory" "exit $got, want 2" "$out"
fi

if [ "$failures" -ne 0 ]; then
  echo "$failures test(s) failed"
  exit 1
fi
echo "all tests passed"
