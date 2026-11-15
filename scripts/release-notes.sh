#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Prints Markdown release notes for a tag, from Conventional Commits (#98, ADR-0011). Used by release.yml; tests are in
# release-notes.test.sh.
#
# Usage: scripts/release-notes.sh <tag>
#
# The range is the commits after the previous `v*` tag reachable from <tag> (highest version first, <tag> itself
# excluded) up to <tag>; with no previous tag it prints just `First release.` (the whole history is no use as a draft). Merge commits are skipped. Groups, in order:
# Breaking changes (a `!` after the type or scope, or a `BREAKING CHANGE:` / `BREAKING-CHANGE:` footer), Features
# (feat), Fixes (fix), Performance (perf), Other changes (everything else, including subjects that are not Conventional
# Commits). A breaking commit is listed in Breaking changes and again in its own group. Empty groups are left out.
# Newest commit first within a group. One bullet per commit: `- **scope:** summary (short-sha)`; the summary keeps a
# trailing `(#NNN)`.
#
# Commit text is untrusted. It is only ever printed as Markdown text, never evaluated or used as a command or option.
# Leading Markdown heading markers (one to six `#` and a space) are stripped, `#12` is kept, and `<!--` is escaped so a subject can't open an HTML comment.
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: release-notes.sh <tag>" >&2
  exit 2
fi
tag="$1"
case "$tag" in
  v*) ;;
  *)
    echo "release-notes.sh: '$tag' is not a v* tag" >&2
    exit 2
    ;;
esac
if ! git rev-parse --verify --quiet "refs/tags/$tag^{commit}" >/dev/null; then
  echo "release-notes.sh: no tag '$tag'" >&2
  exit 1
fi

# The previous tag: highest version among the v* tags reachable from <tag>, other than <tag>.
previous=""
mapfile -t reachable < <(git tag --merged "refs/tags/$tag^{commit}" --list 'v*' --sort=-v:refname)
for candidate in "${reachable[@]}"; do
  if [ "$candidate" != "$tag" ]; then
    previous="$candidate"
    break
  fi
done

if [ -z "$previous" ]; then
  echo "First release."
  exit 0
fi
range="refs/tags/$previous..refs/tags/$tag"

# Text that is safe to print: no heading marker at the start, no HTML comment opener anywhere.
clean() {
  local text="$1" escaped='&lt;!--'
  # Quoted so bash 5.2 doesn't read the & as "the matched text".
  text="${text//<!--/"$escaped"}"
  # Only a real heading marker (one to six #, then a space or the end) goes: `#12` is an issue number and stays.
  local heading_re='^[[:space:]]*#{1,6}([[:space:]]+|$)'
  while [[ "$text" =~ $heading_re ]]; do
    text="${text#"${BASH_REMATCH[0]}"}"
  done
  text="${text#"${text%%[![:space:]]*}"}"
  printf '%s' "$text"
}

breaking="" features="" fixes="" perf="" other=""
subject_re='^([A-Za-z]+)(\(([^)]*)\))?(!)?:[[:space:]]+(.+)$'
footer_re=$'(^|\n)BREAKING[ -]CHANGE:'

# One record per commit: short sha, subject and body split by \x1f, the record ended by \x1e.
while IFS= read -r -d $'\x1e' record; do
  record="${record#$'\n'}"
  sha="${record%%$'\x1f'*}"
  rest="${record#*$'\x1f'}"
  subject="${rest%%$'\x1f'*}"
  body="${rest#*$'\x1f'}"

  type="" scope="" bang="" summary="$subject"
  if [[ "$subject" =~ $subject_re ]]; then
    type="${BASH_REMATCH[1],,}"
    scope="${BASH_REMATCH[3]}"
    bang="${BASH_REMATCH[4]}"
    summary="${BASH_REMATCH[5]}"
  fi

  entry="- "
  scope="$(clean "$scope")"
  if [ -n "$scope" ]; then
    entry+="**$scope:** "
  fi
  entry+="$(clean "$summary") ($sha)"$'\n'

  if [ -n "$bang" ] || [[ "$body" =~ $footer_re ]]; then
    breaking+="$entry"
  fi
  case "$type" in
    feat) features+="$entry" ;;
    fix) fixes+="$entry" ;;
    perf) perf+="$entry" ;;
    *) other+="$entry" ;;
  esac
done < <(git log --no-merges --abbrev=7 --format='%h%x1f%s%x1f%b%x1e' "$range")

emit() { # <heading> <entries>
  if [ -n "$2" ]; then
    printf '### %s\n\n%s\n' "$1" "$2"
  fi
}

out="$(
  emit "Breaking changes" "$breaking"
  emit "Features" "$features"
  emit "Fixes" "$fixes"
  emit "Performance" "$perf"
  emit "Other changes" "$other"
)"
if [ -z "$out" ]; then
  echo "No changes since $previous."
else
  printf '%s\n' "$out"
fi
