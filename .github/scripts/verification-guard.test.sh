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

# Signature checking must be explicitly off, and no key may be trusted: with signatures on, a self-trusted key
# would stand in for the checksums.
expect verify-signatures-true 1 verify-signatures
expect verify-signatures-missing 1 verify-signatures
expect trusted-key 1 trusted-keys
expect trusted-keys-self-closed 1 trusted-keys

# XML the guard's text matching can't be sure to read as Gradle does: entities that expand to a <trust>, comments
# and CDATA that hide or fake an element.
expect doctype-entity 1 xml-construct
expect doctype-plain 1 xml-construct
expect comment 1 xml-construct
expect cdata 1 xml-construct

# Exactly one <verify-metadata>, spelled exactly, with the value true.
expect verify-metadata-space-false 1 verify-metadata
expect verify-metadata-attribute 1 verify-metadata
expect verify-metadata-prefixed 1 verify-metadata
expect verify-metadata-true-and-false 1 verify-metadata
expect verify-metadata-uppercase 1 verify-metadata

# Flag spellings: quoted, any case, with = , and the baseline-rewrite values (-M sha1, pgp, sha256,pgp).
expect workflow-flag-quoted 1 workflow-flag
expect workflow-flag-uppercase 1 workflow-flag
expect workflow-flag-lenient-equals 1 workflow-flag
expect workflow-flag-long-uppercase 1 workflow-flag
expect workflow-flag-sha1 1 no-ci-rewrite
expect workflow-flag-pgp 1 no-ci-rewrite
expect workflow-flag-sha256-pgp 1 no-ci-rewrite

# Only the guard, its test and its own fixtures are exempt from the .github/ scan, by exact path. A same-named
# script elsewhere, another fixtures directory, or a workflow that runs something out of the fixtures is not.
expect script-named-like-guard 1 no-ci-rewrite
expect fixtures-dir-hides-script 1 no-ci-rewrite
expect workflow-runs-fixture-script 1 fixture-reference

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
