#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Opens one GitHub issue per OSV-Scanner finding. A finding is one vulnerability (an alias group, e.g. a GHSA and
# its CVE) in one package version, titled "OSV: <first id in the group> in <package>@<version>".
# A finding is skipped if an issue with its title is open, or was closed as not planned (the owner judged it
# doesn't apply). One closed as completed is filed again: the fix has regressed. Run by osv-weekly.yml.
#
# Usage: osv-issues.sh RESULTS.json
#          Live: reads the OSV issues with gh and opens new ones (needs GH_TOKEN with issues: write).
#        osv-issues.sh --dry-run ISSUES.json RESULTS.json
#          Reads the issues from a file shaped like `gh issue list --json title,state,stateReason`, and prints
#          what it would open.
set -euo pipefail

dry_run=false
issues_file=""
if [ "${1:-}" = --dry-run ]; then
  dry_run=true
  issues_file="${2:?--dry-run needs a file of issues}"
  shift 2
fi
results="${1:?usage: osv-issues.sh [--dry-run ISSUES.json] RESULTS.json}"

# A scan that failed writes no results file, or not a results object. Fail rather than report no findings.
if ! jq -e '.results | type == "array"' "$results" >/dev/null 2>&1; then
  echo "::error::OSV: $results is missing or isn't OSV-Scanner JSON output; did the scan fail?"
  exit 1
fi

# One compact JSON object per finding, de-duplicated by title (the same package version can appear in several
# lockfiles), with every path it was found in.
findings="$(jq -c '
  [
    .results[] as $r
    | $r.packages[] as $p
    | ($p.groups // [$p.vulnerabilities[] | {ids: [.id], aliases: (.aliases // [])}])[] as $g
    | "\($p.package.name)@\($p.package.version)" as $package
    | {
        title: "OSV: \($g.ids[0]) in \($package)",
        id: $g.ids[0],
        package: $package,
        aliases: (($g.ids + ($g.aliases // [])) | unique),
        severity: ($g.max_severity // ""),
        ecosystem: ($p.package.ecosystem // ""),
        summary: ([$p.vulnerabilities[] | select(.id as $id | $g.ids | index($id)) | .summary // empty] | first // ""),
        path: ($r.source.path // "" | sub("^/github/workspace/"; ""))
      }
  ]
  | group_by(.title)
  | map(.[0] + {paths: (map(.path) | unique)})
  | .[]
' "$results")"

if [ "$dry_run" = true ]; then
  issues="$(cat "$issues_file")"
else
  issues="$(gh issue list --state all --search 'in:title "OSV:"' --limit 1000 --json title,state,stateReason)"
fi
# Titles that suppress a finding: open, or closed as not planned.
skip_titles="$(jq -r '.[] | select(.state == "OPEN" or .stateReason == "NOT_PLANNED") | .title' <<<"$issues")"

planned=0
while IFS= read -r finding; do
  [ -n "$finding" ] || continue
  title="$(jq -r '.title' <<<"$finding")"
  if grep -qxF -- "$title" <<<"$skip_titles"; then
    echo "already filed: $title"
    continue
  fi
  planned=$((planned + 1))
  if [ "$dry_run" = true ]; then
    echo "would open: $title"
    continue
  fi
  body="$(jq -r '
    "OSV-Scanner found **\(.id)** in `\(.package)`"
      + (if .ecosystem != "" then " (\(.ecosystem))" else "" end) + ".\n\n"
      + (if .summary != "" then "- Summary: \(.summary)\n" else "" end)
      + "- Aliases: \(.aliases | join(", "))\n"
      + (if .severity != "" then "- Max CVSS score: \(.severity)\n" else "" end)
      + "- Found in: \(.paths | map("`\(.)`") | join(", "))\n"
      + "- Details: https://osv.dev/vulnerability/\(.id)\n\n"
      + "Opened by the weekly OSV-Scanner workflow (`.github/workflows/osv-weekly.yml`). Close it as completed "
      + "once the dependency is updated; if the finding comes back, a new issue is opened. If it does not apply, "
      + "say why and close it as not planned; it will not be opened again."
  ' <<<"$finding")"
  gh issue create --title "$title" --body "$body"
  echo "opened: $title"
done <<<"$findings"

echo "OSV: $planned new issue(s)"
