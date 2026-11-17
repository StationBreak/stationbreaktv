#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Decides whether a pull request is "risky" for the release build: whether it can change what R8, the manifest merger,
# signing or the release dependency set produce, so that the release variants must be compiled, assembled and
# inspected. Debug-only PRs skip that (see `verify` and `assemble` in ci.yml); pushes to main always run it, and so
# does a diff that can't be read.
#
# Usage: release-scope.sh [PATCH_FILE] < changed-file-names
#   stdin: the changed file names, one per line (git diff --name-only).
#   PATCH_FILE (optional): the unified diff (git diff -U0), searched for serialization and keep annotations.
# Prints `true` or `false`, and on stderr the first reason for `true`.
#
# Limit: R8 is not enabled today (no isMinifyEnabled). Once it is, an edit inside an existing @Serializable class or a
# new reflective lookup (Class.forName and the like) won't trigger this; revisit the content rule then.
set -uo pipefail

patch="${1:-}"
files="$(cat)"

# Path rules: one extended regex per reason.
rules=(
  '(^|/)(build|settings)\.gradle\.kts$|build-logic/|^gradle/|^gradle\.properties$|^buildSrc/|build: build scripts, convention plugins, version catalog, dependency verification, wrapper (AGP, R8, signing and flavor config all live here)'
  '\.pro$|proguard|consumer-rules|shrinker|R8 or ProGuard keep rules and the shrinker config'
  '(^|/)AndroidManifest\.xml$|manifest: the merged manifest differs per build type'
  '(^|/)src/[A-Za-z]*[Rr]elease/|release source sets: only the release variants compile them'
  '\.(jks|keystore|p12|pfx)$|(^|/)signing|signing: key stores and signing configuration'
  '^app/trust-anchors/|(^|/)res/(xml|raw)/|trust anchors (res/raw) and the network security config (res/xml), packaged into the release APK'
  '^config/(lint|detekt)/|gate configuration: a changed rule set gets the full gate'
  '^\.github/workflows/ci\.yml$|^\.github/scripts/release-scope|this decision itself'
  '^"|a file name git had to quote: the anchored rules above cannot be trusted on it'
)

reason=""
for rule in "${rules[@]}"; do
  pattern="${rule%|*}"
  why="${rule##*|}"
  if grep -qE "$pattern" <<<"$files"; then
    reason="$why"
    break
  fi
done

# Content rule: kotlinx.serialization and keep annotations are the classic R8 casualties (a renamed or removed
# serializer fails at run time, not at compile time), and they can appear in any module. Searched in the diff's
# added and removed lines, so a module that starts using them later is caught without extending a path list.
# Lines in docs and agent config (markdown, docs/, .claude/) are skipped: they mention the tokens without compiling
# them. A hunk whose file can't be told from the patch is searched, so an unreadable header still counts.
not_source='(\.md$|^docs/|^\.claude/)'
if [ -z "$reason" ] && [ -n "$patch" ] && [ -f "$patch" ] &&
  awk -v skip="$not_source" '
    /^--- / { old = substr($0, 5); sub(/^a\//, "", old); next }
    /^\+\+\+ / {
      path = substr($0, 5); sub(/^b\//, "", path)
      if (path == "/dev/null") path = old
      next
    }
    !(path != "" && path ~ skip) { print }
  ' "$patch" | grep -qE '^[+-].*(@Serializable|@SerialName|@Keep\b|kotlinx\.serialization)'; then
  reason="serialization or keep annotations changed (R8 can break them)"
fi

if [ -n "$reason" ]; then
  echo "release build needed: $reason" >&2
  echo true
else
  echo false
fi
