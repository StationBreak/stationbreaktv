#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Fixture tests for dco-check.sh. Each fixture is shaped like GET /repos/{owner}/{repo}/pulls/{n}/commits.
# Usage: bash .github/scripts/dco-check.test.sh
# No -e: a failing case must be reported and counted, not end the run.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$here/dco-check.sh"
fixtures="$here/fixtures/dco"
failures=0

# expect <fixture> <exit code> [text the output must contain]
# The PR's author comes from $pr_author, as the dco job passes it in PR_AUTHOR.
pr_author=alice
expect() {
  local fixture="$1" want="$2" needle="${3:-}" out got
  out="$(PR_AUTHOR="$pr_author" bash "$script" <"$fixtures/$fixture" 2>&1)"
  got=$?
  if [ "$got" -ne "$want" ]; then
    echo "FAIL $fixture: exit $got, want $want"
    printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
  elif [ -n "$needle" ] && ! grep -qF -- "$needle" <<<"$out"; then
    echo "FAIL $fixture: output does not contain '$needle'"
    printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
  else
    echo "ok   $fixture (exit $got)"
  fi
}

expect all-signed.json 0
expect one-unsigned.json 1 3333333333333333333333333333333333333333
expect wrong-case.json 1 9999999999999999999999999999999999999999
expect malformed-trailer.json 1 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
expect null-author.json 1 7777777777777777777777777777777777777777
# A PR always has at least one commit, so an empty list or a non-list means the API call went wrong.
expect empty.json 1
expect not-an-array.json 1

# Exempt only on a PR opened by renovate[bot], for a commit authored by the renovate[bot] Bot account,
# committed by web-flow (GitHub signs the commits Renovate makes through the API) with a verified signature.
pr_author='renovate[bot]'
expect renovate-verified.json 0
expect renovate-spoofed.json 1 5555555555555555555555555555555555555555
expect renovate-name-only.json 1 6666666666666666666666666666666666666666
expect renovate-human-committer.json 1 8888888888888888888888888888888888888888
pr_author=alice
expect renovate-verified.json 1 4444444444444444444444444444444444444444
# The PR commits API returns at most 250 commits, so the rest can't be checked: fail closed.
expect 250-commits.json 1 250

if [ "$failures" -ne 0 ]; then
  echo "$failures test(s) failed"
  exit 1
fi
echo "all tests passed"
