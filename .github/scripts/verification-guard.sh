#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Fails if the Gradle dependency verification setup has been loosened (ADR-0013). Run on a checkout:
#   bash .github/scripts/verification-guard.sh [dir]     (dir defaults to the current directory)
# Rules, each printed as "FAIL <rule>: <where>":
#   baseline-missing  gradle/verification-metadata.xml does not exist
#   verify-metadata   the file does not set <verify-metadata>true</verify-metadata>, or also sets it to false
#   trust             any <trust> element other than the one sources/javadoc regex below
#   also-trust        any <also-trust> element
#   ignored-keys      any <ignored-keys> or <ignored-key> element
#   properties        a gradle.properties anywhere in the tree mentions org.gradle.dependency.verification
#   workflow-flag     anything under .github/ turns verification down: --dependency-verification, -F or
#                     org.gradle.dependency.verification
#   no-ci-rewrite     anything under .github/ rewrites the baseline (--write-verification-metadata, -M).
#                     Angua's veto: no CI job may regenerate the file and push it.
# This script and its fixtures are skipped by the .github/ scan: they have to name the patterns they look for.
# Exit: 0 clean, 1 a rule failed, 2 usage error.
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

metadata=gradle/verification-metadata.xml
# The only <trust> the baseline may carry, with whitespace squeezed. Gradle may write the sources and javadoc
# patterns as two elements; this is the one-element form the spec allows.
allowed_trust='<trust file=".*-(sources|javadoc)[.]jar" regex="true"/>'
failures=0

# fail <rule> <where>
fail() {
  echo "FAIL $1: $2"
  failures=$((failures + 1))
}

if [ ! -f "$metadata" ]; then
  fail baseline-missing "$metadata does not exist"
else
  # Newlines folded to spaces, so an element split over several lines is still one match.
  flat="$(tr '\n\r\t' '   ' <"$metadata" | tr -s ' ')"

  if ! grep -qE '<verify-metadata>[[:space:]]*true[[:space:]]*</verify-metadata>' <<<"$flat" \
    || grep -qE '<verify-metadata>[[:space:]]*[^t[:space:]<]' <<<"$flat"; then
    fail verify-metadata "$metadata must set <verify-metadata>true</verify-metadata> and nothing else"
  fi

  # An optional namespace prefix is allowed for, so <x:trust .../> doesn't slip past. The name must end at a
  # space, '/' or '>', which keeps <trusted-artifacts> and <trusted-key> out.
  while IFS= read -r element; do
    [ -n "$element" ] || continue
    squeezed="$(tr -s ' ' <<<"$element")"
    if [ "$squeezed" != "$allowed_trust" ]; then
      fail trust "$metadata: $squeezed"
    fi
  done < <(grep -oE '<([A-Za-z0-9_.-]+:)?trust([[:space:]/][^>]*)?>' <<<"$flat" || true)

  while IFS= read -r element; do
    [ -n "$element" ] || continue
    fail also-trust "$metadata: $element"
  done < <(grep -oE '<([A-Za-z0-9_.-]+:)?also-trust([[:space:]/][^>]*)?>' <<<"$flat" || true)

  while IFS= read -r element; do
    [ -n "$element" ] || continue
    fail ignored-keys "$metadata: $element"
  done < <(grep -oE '<([A-Za-z0-9_.-]+:)?ignored-keys?([[:space:]/][^>]*)?>' <<<"$flat" || true)
fi

# Any gradle.properties, not just the root's: the build-logic included build has its own.
while IFS= read -r props; do
  [ -n "$props" ] || continue
  if hits="$(grep -nE 'org\.gradle\.dependency\.verification' "$props")"; then
    fail properties "$props:$(head -n1 <<<"$hits")"
  fi
done < <(find . \( -name build -o -name .gradle -o -name .git -o -path ./.github/scripts/fixtures \) -prune \
  -o -name gradle.properties -print | sort)

# Everything under .github/ that could run in CI: workflows, composite actions and the scripts they call.
if [ -d .github ]; then
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    if hits="$(grep -nE 'dependency-verification|org\.gradle\.dependency\.verification' "$file")"; then
      fail workflow-flag "$file:$(head -n1 <<<"$hits")"
    fi
    if hits="$(grep -nE 'write-verification-metadata' "$file")"; then
      fail no-ci-rewrite "$file:$(head -n1 <<<"$hits")"
    fi
    # The short forms: -F is --dependency-verification and -M is --write-verification-metadata. Matched with their
    # only valid values, so grep -F and curl -F don't trip them.
    if hits="$(grep -nE "(^|[[:space:]'\"])-F[[:space:]=]*(off|lenient|strict)([[:space:]'\"]|\$)" "$file")"; then
      fail workflow-flag "$file:$(head -n1 <<<"$hits") (-F is --dependency-verification)"
    fi
    if hits="$(grep -nE "(^|[[:space:]'\"])-M[[:space:]=]*(sha256|sha512)([[:space:]'\"]|\$)" "$file")"; then
      fail no-ci-rewrite "$file:$(head -n1 <<<"$hits") (-M is --write-verification-metadata)"
    fi
  done < <(find .github -type f \
    ! -path '.github/scripts/fixtures/*' ! -name 'verification-guard.sh' ! -name 'verification-guard.test.sh' | sort)
fi

if [ "$failures" -ne 0 ]; then
  echo "$failures dependency verification rule(s) failed (see docs/adr/0013-dependency-verification.md)"
  exit 1
fi
echo "dependency verification guard: ok"
