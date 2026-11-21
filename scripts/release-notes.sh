#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Prints Markdown release notes for a tag, from Conventional Commits (#98, ADR-0011). Used by release.yml; tests are in
# release-notes.test.sh.
#
# Usage: scripts/release-notes.sh <tag> [<end-commit>]
#
# <tag> is a release tag, vMAJOR.MINOR.PATCH with an optional -alpha.N, -beta.N or -rc.N (release.yml's tag check).
# <end-commit> (a full 40-character SHA, default: the commit <tag> points at) ends the range: release.yml passes
# $GITHUB_SHA, the commit that was built. The range is the commits after the previous release tag reachable from the
# end commit (highest version first, <tag> itself excluded; only final vMAJOR.MINOR.PATCH tags count, so
# `v1.2.0-beta.1`, `v1.2.0-rc1` and `vendor-x` are ignored) up to the end commit. With no previous
# tag it prints just `First release.` (the whole history is no use as a draft). A shallow clone is refused: the range
# would be wrong. Merge commits are skipped. Groups, in order: Breaking changes (a `!` after the type or scope, or a
# `BREAKING CHANGE:` / `BREAKING-CHANGE:` footer), Features (feat), Fixes (fix), Performance (perf), Other changes
# (everything else, including subjects that are not Conventional Commits). A breaking commit is listed in Breaking
# changes and again in its own group. Empty groups are left out. Newest commit first within a group. One bullet per
# commit: `- **scope:** summary (short-sha)`; the summary keeps its trailing `(#NNN)` (shown as typed, the
# parentheses escaped).
#
# Commit text is untrusted. It is only ever printed as Markdown text, never evaluated or used as a command or option.
# `clean` removes control characters (CR included) and bidi and zero-width characters, strips leading Markdown heading
# markers (one to six `#` and a space; `#12` stays) and backslash-escapes Markdown punctuation, so a subject can't make
# a link, image, fence, HTML tag or comment. Bare URLs may still autolink. The script's output is not echoed to a CI log.
set -euo pipefail
# A UTF-8 locale, so [[:cntrl:]] and [[:space:]] know Unicode. If the runner lacks it bash keeps the C locale, and the
# C1 controls and the invisible characters are removed byte by byte below anyway: that part works in either locale.
export LC_ALL=C.UTF-8

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo "usage: release-notes.sh <tag> [<end-commit>]" >&2
  exit 2
fi
tag="$1"
# The tag argument: release.yml's tag check. The previous tag below is a final release only.
release_tag_re='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$'
if ! [[ "$tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)(-(alpha|beta|rc)\.(0|[1-9]|[12][0-9]))?$ ]]; then
  echo "release-notes.sh: '$tag' is not a vMAJOR.MINOR.PATCH tag, optionally with -alpha.N, -beta.N or -rc.N" >&2
  exit 2
fi
end_arg="${2:-}"
if [ "$#" -eq 2 ] && ! [[ "$end_arg" =~ ^[0-9a-f]{40}$ ]]; then
  echo "release-notes.sh: the end commit must be a full 40-character SHA" >&2
  exit 2
fi

if [ "$(git rev-parse --is-shallow-repository)" = true ]; then
  echo "release-notes.sh: this is a shallow clone; fetch the full history first" >&2
  exit 1
fi
if ! tag_commit="$(git rev-parse --verify --quiet "refs/tags/$tag^{commit}")"; then
  echo "release-notes.sh: no tag '$tag'" >&2
  exit 1
fi
end_commit="$tag_commit"
if [ "$#" -eq 2 ]; then
  if ! end_commit="$(git rev-parse --verify --quiet "$end_arg^{commit}")"; then
    echo "release-notes.sh: no commit '$end_arg'" >&2
    exit 1
  fi
fi

# The previous tag: the highest release version among the tags reachable from the end commit, other than <tag>.
previous=""
tags_raw="$(git tag --merged "$end_commit" --sort=-v:refname)"
while IFS= read -r candidate; do
  if [ "$candidate" != "$tag" ] && [[ "$candidate" =~ $release_tag_re ]]; then
    previous="$candidate"
    break
  fi
done <<<"$tags_raw"

if [ -z "$previous" ]; then
  echo "First release."
  exit 0
fi
previous_commit="$(git rev-parse --verify "refs/tags/$previous^{commit}")"

# Text that is safe to print: no control, bidi or zero-width characters, no heading marker at the start, no live Markdown.
invisible=(
  $'\xe2\x80\x8b' $'\xe2\x80\x8c' $'\xe2\x80\x8d' $'\xe2\x80\x8e' $'\xe2\x80\x8f' # U+200B-U+200F
  $'\xe2\x80\xa8' $'\xe2\x80\xa9'                                                 # U+2028, U+2029
  $'\xe2\x80\xaa' $'\xe2\x80\xab' $'\xe2\x80\xac' $'\xe2\x80\xad' $'\xe2\x80\xae' # U+202A-U+202E
  $'\xe2\x81\xa0' $'\xe2\x81\xa1' $'\xe2\x81\xa2' $'\xe2\x81\xa3' $'\xe2\x81\xa4' # U+2060-U+2064
  $'\xe2\x81\xa6' $'\xe2\x81\xa7' $'\xe2\x81\xa8' $'\xe2\x81\xa9'                 # U+2066-U+2069
  $'\xef\xbb\xbf' $'\xd8\x9c' $'\xc2\xad' $'\xe1\xa0\x8e'                         # U+FEFF, U+061C, U+00AD, U+180E
)
# The C1 controls U+0080-U+009F, listed byte by byte: [[:cntrl:]] only reaches them in a UTF-8 locale.
for byte in 80 81 82 83 84 85 86 87 88 89 8a 8b 8c 8d 8e 8f 90 91 92 93 94 95 96 97 98 99 9a 9b 9c 9d 9e 9f; do
  invisible+=("$(printf '%b' "\\xc2\\x$byte")")
done
markdown_chars=("\\" '&' '`' '*' '_' '[' ']' '(' ')' '<' '>' '!' '|' '~') # the backslash first

# Backslash-escapes the Markdown punctuation.
escape() {
  local text="$1" c
  for c in "${markdown_chars[@]}"; do
    text="${text//"$c"/"\\$c"}"
  done
  printf '%s' "$text"
}

clean() {
  local text="$1" s
  text="${text//[[:cntrl:]]/}"
  for s in "${invisible[@]}"; do
    text="${text//"$s"/}"
  done
  # Only a real heading marker (one to six #, then a space or the end) goes: `#12` is an issue number and stays.
  local heading_re='^[[:space:]]*#{1,6}([[:space:]]+|$)'
  while [[ "$text" =~ $heading_re ]]; do
    text="${text#"${BASH_REMATCH[0]}"}"
  done
  text="${text#"${text%%[![:space:]]*}"}"
  # An `@name` goes in a code span, which notifies nobody when the release is published.
  local mention_re='@[A-Za-z0-9][A-Za-z0-9_-]*' result="" before mention
  while [[ "$text" =~ $mention_re ]]; do
    mention="${BASH_REMATCH[0]}"
    before="${text%%"$mention"*}"
    result+="$(escape "$before")"'`'"$mention"'`'
    text="${text#*"$mention"}"
  done
  result+="$(escape "$text")"
  # A leading `-`, `+`, `=` or `N.` could start a nested list or a rule.
  local list_re='^([0-9]+)\.'
  if [[ "$result" == [-+=]* ]]; then
    result="\\$result"
  elif [[ "$result" =~ $list_re ]]; then
    result="${BASH_REMATCH[1]}\\.${result#"${BASH_REMATCH[0]}"}"
  fi
  printf '%s' "$result"
}

breaking="" features="" fixes="" perf="" other=""
subject_re='^([A-Za-z]+)(\(([^)]*)\))?(!)?:[[:space:]]+(.+)$'
footer_re=$'(^|\n)BREAKING[ -]CHANGE:'

# One git call per field and commit: no separator can be forged by a commit message. rev-list prints hex and nothing
# else; anything that isn't a full SHA stops the script.
shas="$(git rev-list --no-merges "$previous_commit..$end_commit")"
while IFS= read -r full; do
  if [ -z "$full" ]; then continue; fi
  if ! [[ "$full" =~ ^[0-9a-f]{7,40}$ ]]; then
    echo "release-notes.sh: unexpected commit id from git" >&2
    exit 1
  fi
  sha="$(git rev-parse --short=7 "$full")"
  subject="$(git log -1 --format=%s "$full")"
  body="$(git log -1 --format=%b "$full")"

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
done <<<"$shas"

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
