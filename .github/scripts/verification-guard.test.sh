#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Fixture tests for verification-guard.sh. Each fixture is a small repo-shaped directory (gradle/
# verification-metadata.xml, gradle.properties, .github/workflows/*.yml). The guard must exit 1 for every forbidden
# pattern, naming the rule that failed, and 0 for the clean fixtures.
# Usage: bash .github/scripts/verification-guard.test.sh
# No -e: a failing case must be reported and counted, not end the run.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$here/verification-guard.sh"
fixtures="$here/fixtures/verification"
failures=0

# fail <case> <reason> <output...>
fail() {
  local name="$1" reason="$2"
  shift 2
  echo "FAIL $name: $reason"
  printf '%s\n' "$@" | sed 's/^/    /'
  failures=$((failures + 1))
}

# expect <fixture> <exit code> [rule id that must be named in the output]
expect() {
  local fixture="$1" want="$2" rule="${3:-}" out got
  out="$(bash "$script" "$fixtures/$fixture" 2>&1)"
  got=$?
  if [ "$got" -ne "$want" ]; then
    fail "$fixture" "exit $got, want $want" "$out"
  elif [ -n "$rule" ] && ! grep -q -- "^FAIL $rule: " <<<"$out"; then
    fail "$fixture" "output does not name rule $rule" "$out"
  else
    echo "ok   $fixture (exit $got${rule:+, $rule})"
  fi
}

expect clean 0
# The one trust entry the guard allows: source and javadoc jars only.
expect allowed-trust 0

# <trust> other than the allowed one, however it is written.
expect trust-group-wildcard 1 trust
expect trust-plain-group 1 trust
expect trust-file-wildcard 1 trust
expect trust-in-addition-to-allowed 1 trust
expect trust-split-over-lines 1 trust
expect trust-prefixed-element 1 trust

expect verify-metadata-false 1 verify-metadata
# Absent means Gradle's default (on), but the baseline sets it explicitly, so the guard requires it.
expect verify-metadata-missing 1 verify-metadata
expect also-trust 1 also-trust
expect ignored-keys 1 ignored-keys

expect properties-verification-off 1 properties
expect properties-systemprop 1 properties
# A gradle.properties anywhere in the tree, such as the build-logic included build's.
expect nested-properties 1 properties

expect workflow-flag-long 1 workflow-flag
expect workflow-flag-short 1 workflow-flag
expect workflow-system-property 1 workflow-flag

# Veto (a): nothing under .github/ may rewrite the baseline.
expect workflow-rewrites-baseline 1 no-ci-rewrite
expect workflow-rewrites-baseline-short 1 no-ci-rewrite
expect script-rewrites-baseline 1 no-ci-rewrite

# No baseline file is a failure, not a pass.
expect missing-baseline 1 baseline-missing

# No argument: usage error, not a pass on the current directory.
out="$(bash "$script" 2>&1)"
got=$?
if [ "$got" -eq 2 ]; then
  echo "ok   no argument (exit $got)"
else
  fail "no argument" "exit $got, want 2" "$out"
fi

if [ "$failures" -ne 0 ]; then
  echo "$failures test(s) failed"
  exit 1
fi
echo "all tests passed"
