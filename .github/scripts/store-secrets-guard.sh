#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Fails if a store's IAP secret or shared key could reach this repo or a CI log, or if CI could start a store
# submission (ADR-0023). Run on a checkout:  bash .github/scripts/store-secrets-guard.sh <dir>
# The app does no server-side receipt checks, so no IAP secret, shared key or store API credential belongs anywhere in
# the tree, and no workflow step may hold one. Rules, printed as "FAIL <rule>: <file>:<line>" (never the text, which
# could be a secret):
#   iap-secret       a name for a store secret or shared key (AMAZON_SHARED_KEY, IAP_SECRET, RVS_SHARED_SECRET,
#                    APPSTORE_API_KEY, "shared secret", "shared key", ...) in any text file
#   secret-printed   a workflow line that prints a `secrets.` expression (echo, printf, cat, tee, a redirect)
#   store-submission a workflow, script or Gradle file that calls a store's submission API or uploader (Amazon's
#                    developer.amazon.com/api/appstore, Google Play's androidpublisher, upload-google-play, fastlane
#                    supply/deliver): CI never submits to a store or starts Amazon's 14-day review clock
# Exempt by exact path: this script, its test, .github/scripts/fixtures/store-secrets/, docs/, CONTRIBUTING.md and
# .gitleaks.toml. .git, .gradle and each module's build/ output are not scanned, and neither are binary files. An
# unreadable file is an error (exit 2), never a pass. A tripwire for mistakes, not a defence against deliberate evasion;
# gitleaks (the `checks` job) still scans every commit for real key shapes.
# Exit: 0 clean, 1 a rule failed, 2 usage error or an unreadable file.
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <repo-dir>" >&2
  exit 2
fi
root="$1"
if [ ! -d "$root" ]; then
  echo "not a directory: $root" >&2
  exit 2
fi
cd "$root"

failures=0

# first_line <file> <pattern>...: the first matching line number, return 1 for no match, exit 2 if unreadable.
first_line() {
  local file="$1" hits status=0 args=()
  shift
  for pattern in "$@"; do args+=(-e "$pattern"); done
  hits="$(grep -nIiE "${args[@]}" "$file" 2>/dev/null)" || status=$?
  if [ "$status" -eq 0 ]; then
    head -n1 <<<"$hits" | cut -d: -f1
    return 0
  elif [ "$status" -ne 1 ]; then
    echo "cannot read: $file" >&2
    exit 2
  fi
  return 1
}

report() { # <rule> <file> <line>
  echo "FAIL $1: $2:$3"
  failures=$((failures + 1))
}

scan() {
  local file="$1" line
  if line="$(first_line "$file" \
    '(^|[^a-z])(amazon|appstore|app_store|iap|rvs)[_-]?(shared|secret)' \
    '(^|[^a-z])(iap|rvs|appstore|amazon)[_A-Za-z]*(secret|shared[_-]?key|private[_-]?key|api[_-]?key)' \
    'shared[ _-]?(secret|key)')"; then
    report iap-secret "$file" "$line"
  fi
  case "$file" in
    ./.github/workflows/*)
      if line="$(first_line "$file" \
        '(echo|printf|cat|tee)[^#]*\$\{\{[[:space:]]*secrets\.' \
        '\$\{\{[[:space:]]*secrets\.[^}]*\}\}[^#]*(>>?|\|[[:space:]]*tee)')"; then
        report secret-printed "$file" "$line"
      fi
      ;;
  esac
  case "$file" in
    ./.github/*|./scripts/*|./build-logic/*|*.gradle|*.gradle.kts|./Taskfile*|./Makefile)
      if line="$(first_line "$file" \
        'developer\.amazon\.com/api/appstore' 'api/appstore' 'androidpublisher' \
        'upload-google-play' 'fastlane[[:space:]]+(supply|deliver)' 'appstore-submit')"; then
        report store-submission "$file" "$line"
      fi
      ;;
  esac
}

prune=()
while IFS= read -r module_build_file; do
  [ -n "$module_build_file" ] || continue
  prune+=(-path "$(dirname "$module_build_file")/build" -o)
done < <(find . \( -name .gradle -o -name .git -o -name node_modules \) -prune \
  -o \( -name build.gradle -o -name build.gradle.kts \) -type f -print)

files="$(find . \( "${prune[@]}" -name .gradle -o -name .git -o -name node_modules \) -prune \
  -o -type f \
  ! -path ./.github/scripts/store-secrets-guard.sh ! -path ./.github/scripts/store-secrets-guard.test.sh \
  ! -path './.github/scripts/fixtures/store-secrets/*' ! -path './docs/*' ! -path ./CONTRIBUTING.md \
  ! -path ./.gitleaks.toml -print | sort)" || {
  echo "cannot list the tree" >&2
  exit 2
}

while IFS= read -r file; do
  [ -n "$file" ] || continue
  scan "$file"
done <<<"$files"

if [ "$failures" -ne 0 ]; then
  echo "$failures finding(s): a store secret, a printed secret or a store submission has no place in this repo (ADR-0023)"
  exit 1
fi
echo "store secrets guard: ok"
