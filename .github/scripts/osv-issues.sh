#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Opens one GitHub issue per OSV-Scanner finding, skipping findings that already have an open issue.
# A finding is one vulnerability (an alias group, e.g. a GHSA and its CVE) in one package version, titled
# "OSV: <first id in the group> in <package>@<version>". Run by .github/workflows/osv-weekly.yml.
#
# Usage: osv-issues.sh RESULTS.json
#          Live: reads open issue titles with gh and opens issues (needs GH_TOKEN with issues: write).
#        osv-issues.sh --dry-run OPEN_TITLES.txt RESULTS.json
#          Reads open issue titles from a file, one per line, and prints what it would open.
set -euo pipefail

dry_run=false
open_titles_file=""
if [ "${1:-}" = --dry-run ]; then
  dry_run=true
  open_titles_file="${2:?--dry-run needs a file of open issue titles}"
  shift 2
fi
results="${1:?usage: osv-issues.sh [--dry-run OPEN_TITLES.txt] RESULTS.json}"

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
    | {
        title: "OSV: \($g.ids[0]) in \($p.package.name)@\($p.package.version)",
        id: $g.ids[0],
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
  open_titles="$(cat "$open_titles_file")"
else
  open_titles="$(gh issue list --state open --search 'in:title "OSV:"' --limit 1000 --json title --jq '.[].title')"
fi

planned=0
while IFS= read -r finding; do
  [ -n "$finding" ] || continue
  title="$(jq -r '.title' <<<"$finding")"
  if grep -qxF -- "$title" <<<"$open_titles"; then
    echo "already open: $title"
    continue
  fi
  planned=$((planned + 1))
  if [ "$dry_run" = true ]; then
    echo "would open: $title"
    continue
  fi
  body="$(jq -r '
    "OSV-Scanner found **\(.id)** in `\(.title | sub("^OSV: [^ ]+ in "; ""))`"
      + (if .ecosystem != "" then " (\(.ecosystem))" else "" end) + ".\n\n"
      + (if .summary != "" then "- Summary: \(.summary)\n" else "" end)
      + "- Aliases: \(.aliases | join(", "))\n"
      + (if .severity != "" then "- Max CVSS score: \(.severity)\n" else "" end)
      + "- Found in: \(.paths | map("`\(.)`") | join(", "))\n"
      + "- Details: https://osv.dev/vulnerability/\(.id)\n\n"
      + "Opened by the weekly OSV-Scanner workflow (`.github/workflows/osv-weekly.yml`). Close it once the "
      + "dependency is updated, or record why the finding does not apply."
  ' <<<"$finding")"
  gh issue create --title "$title" --body "$body"
  echo "opened: $title"
done <<<"$findings"

echo "OSV: $planned new issue(s)"
