#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Checks that each APK carries every bundled channel mark, byte for byte (#748). The source check
# (core/ui's manifest test) proves the pack in the repository; this proves the packaged APK, so a packaging or
# shrinker change that drops or alters a mark fails CI instead of the guide quietly falling back to initials tiles.
#
# The expected set is core/ui/channelmarks.sha256: lines "<sha256>  src/main/assets/channelmarks/<file>", which land
# in the APK as assets/channelmarks/<file>. An APK fails on a missing mark, a mark whose bytes differ, or a file under
# assets/channelmarks/ that the manifest doesn't list.
#
# Usage: bash apk-channelmarks.sh <channelmarks.sha256> <apk>...
set -euo pipefail
# comm and join need the same collation as sort.
export LC_ALL=C

if [ "$#" -lt 2 ]; then
  echo "::error::usage: apk-channelmarks.sh <channelmarks.sha256> <apk>..."
  exit 1
fi
manifest="$1"
shift

# Every line must be a hash and a channel mark path; anything else means the manifest changed shape.
line_re='^[0-9a-f]{64}  src/main/assets/channelmarks/[^/]+$'
if [ ! -s "$manifest" ]; then
  echo "::error::channel marks: the manifest $manifest is missing or empty"
  exit 1
fi
if bad="$(grep -nEv "$line_re" "$manifest")"; then
  echo "::error::channel marks: unexpected line(s) in $manifest:"
  printf '%s\n' "$bad"
  exit 1
fi
want="$(sed -E 's|  src/main/|  |' "$manifest" | sort -k2)"
count="$(wc -l <<<"$want")"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failed=0
n=0
for apk in "$@"; do
  n=$((n + 1))
  dir="$work/$n"
  mkdir -p "$dir"
  if [ ! -f "$apk" ]; then
    echo "::error::channel marks: no such APK: $apk"
    failed=1
    continue
  fi
  # unzip exits 11 when nothing matches the pattern: an APK with no marks at all, reported below as all missing.
  unzip -qq -o "$apk" 'assets/channelmarks/*' -d "$dir" || [ "$?" -eq 11 ]
  got=""
  if [ -d "$dir/assets/channelmarks" ]; then
    got="$(cd "$dir" && find assets/channelmarks -type f -print0 | sort -z | xargs -0 -r sha256sum | sort -k2)"
  fi
  missing="$(comm -23 <(awk '{print $2}' <<<"$want" | sort) <(awk 'NF {print $2}' <<<"$got" | sort))"
  extra="$(comm -13 <(awk '{print $2}' <<<"$want" | sort) <(awk 'NF {print $2}' <<<"$got" | sort))"
  changed="$(join -j 2 <(sort -k2 <<<"$want") <(awk 'NF' <<<"$got" | sort -k2) | awk '$2 != $3 {print $1}')"
  if [ -n "$missing$extra$changed" ]; then
    failed=1
    if [ -n "$missing" ]; then
      while read -r f; do echo "::error::channel marks: $apk is missing $f"; done <<<"$missing"
    fi
    if [ -n "$changed" ]; then
      while read -r f; do echo "::error::channel marks: $apk has a different $f than the manifest"; done <<<"$changed"
    fi
    if [ -n "$extra" ]; then
      while read -r f; do echo "::error::channel marks: $apk has $f, which the manifest doesn't list"; done <<<"$extra"
    fi
  else
    echo "channel marks: $apk carries all $count marks, byte for byte"
  fi
done
exit "$failed"
