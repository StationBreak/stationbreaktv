#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Fixture tests for osv-issues.sh. Each fixture is OSV-Scanner `--format json` output; issues.json stands in for
# the repo's OSV issues, shaped like `gh issue list --json title,state,stateReason`.
# Usage: bash .github/scripts/osv-issues.test.sh
# No -e: a failing case must be reported and counted, not end the run.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$here/osv-issues.sh"
fixtures="$here/fixtures/osv"
issues="$fixtures/issues.json"
failures=0

# fail <case> <reason> <output...>
fail() {
  local name="$1" reason="$2"
  shift 2
  echo "FAIL $name: $reason"
  printf '%s\n' "$@" | sed 's/^/    /'
  failures=$((failures + 1))
}

# expect <fixture> <exit code> <planned issues> [title that must be planned]
expect() {
  local fixture="$1" want="$2" want_planned="$3" title="${4:-}" out got planned
  out="$(bash "$script" --dry-run "$issues" "$fixtures/$fixture" 2>&1)"
  got=$?
  planned="$(grep -c '^would open: ' <<<"$out")"
  if [ "$got" -ne "$want" ]; then
    fail "$fixture" "exit $got, want $want" "$out"
  elif [ "$planned" -ne "$want_planned" ]; then
    fail "$fixture" "$planned issue(s) planned, want $want_planned" "$out"
  elif [ -n "$title" ] && ! grep -qxF -- "would open: $title" <<<"$out"; then
    fail "$fixture" "output does not plan '$title'" "$out"
  else
    echo "ok   $fixture (exit $got, $planned planned)"
  fi
}

expect clean.json 0 0
# The GHSA and CVE ids are aliases (one group), so they make one issue, titled with the group's first id.
expect one-finding.json 0 1 "OSV: GHSA-aaaa-bbbb-cccc in org.example:vulnerable-lib@1.2.3"
expect already-open.json 0 0
# The same package version found in two lockfiles is one finding.
expect two-sources.json 0 1 "OSV: GHSA-aaaa-bbbb-cccc in org.example:vulnerable-lib@1.2.3"
# Closed as not planned: the owner judged it doesn't apply, so it stays closed.
expect not-planned.json 0 0
# Closed as completed but found again: a regression, so it's filed again.
expect regressed.json 0 1 "OSV: GHSA-jjjj-kkkk-llll in org.example:fixed-lib@3.1.0"
# A failed scan writes no results (or something else); that must fail the job, not look like a clean scan.
expect not-results.json 1 0
expect missing.json 1 0

# Live mode, with a stub gh: existing issues come from gh issue list, and each new finding is one gh issue create.
stub="$(mktemp -d)"
trap 'rm -rf "$stub"' EXIT
cat >"$stub/gh" <<EOF
#!/usr/bin/env bash
case "\$1 \$2" in
  "issue list") cat "$issues" ;;
  "issue create") printf '%s\n' "\$*" >>"$stub/created" ;;
  *) echo "unexpected gh call: \$*" >&2; exit 1 ;;
esac
EOF
chmod +x "$stub/gh"
out="$(PATH="$stub:$PATH" bash "$script" "$fixtures/one-finding.json" 2>&1)"
got=$?
created="$(cat "$stub/created" 2>/dev/null)"
if [ "$got" -eq 0 ] && [ "$(grep -c '^issue create' <<<"$created")" -eq 1 ] \
  && grep -qF -- "--title OSV: GHSA-aaaa-bbbb-cccc in org.example:vulnerable-lib@1.2.3 --body" <<<"$created" \
  && grep -qF -- "in \`org.example:vulnerable-lib@1.2.3\` (Maven)" <<<"$created" \
  && grep -qF -- "https://osv.dev/vulnerability/GHSA-aaaa-bbbb-cccc" <<<"$created"; then
  echo "ok   live one-finding.json (exit $got, 1 created)"
else
  fail "live one-finding.json" "exit $got, want 0 and one gh issue create" "$out" "$created"
fi

if [ "$failures" -ne 0 ]; then
  echo "$failures test(s) failed"
  exit 1
fi
echo "all tests passed"
