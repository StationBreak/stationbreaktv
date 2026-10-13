#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Fails if the Gradle dependency verification setup has been loosened (ADR-0013). Run on a checkout:
#   bash .github/scripts/verification-guard.sh <dir>
# Rules, each printed as "FAIL <rule>: <where>":
#   baseline-missing  gradle/verification-metadata.xml does not exist
#   xml-construct     the file has a DOCTYPE, ENTITY, CDATA section or comment (text and Gradle's reading could differ)
#   verify-metadata   the file is not exactly one <verify-metadata>true</verify-metadata>
#   verify-signatures the file is not exactly one <verify-signatures>false</verify-signatures>
#   trusted-keys      any <trusted-keys> or <trusted-key> element
#   trust             any <trust> element other than the one sources/javadoc regex below
#   also-trust        any <also-trust> element
#   ignored-keys      any <ignored-keys> or <ignored-key> element
#   properties        a gradle.properties anywhere in the tree mentions org.gradle.dependency.verification
#   workflow-flag     anything under .github/ turns verification down: --dependency-verification, -F or
#                     org.gradle.dependency.verification
#   no-ci-rewrite     anything under .github/ rewrites the baseline (--write-verification-metadata, -M); Angua's veto
#   fixture-reference a file under .github/ refers to the verification fixtures, which are exempt from the scan
# Exempt from the .github/ scan, by exact path: this script, its test and .github/scripts/fixtures/verification/.
# They have to name the patterns they look for. A copy elsewhere is not exempt.
# A tripwire for mistakes, not a defence against a deliberate evasion (ADR-0013, Accepted risks).
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

  # No DOCTYPE or entities (an entity can expand to an element no text match sees), no CDATA or comments (they can
  # hide an element from Gradle that looks present here, or the reverse).
  hits="$( (grep -oE '<!(DOCTYPE|ENTITY|\[CDATA\[|--)' <<<"$flat" || true) | sort -u | tr '\n' ' ')"
  if [ -n "$hits" ]; then
    fail xml-construct "$metadata: ${hits% }"
  fi

  # exactly <setting> <name>/<value>: every tag whose name ends in the setting (prefixed, with attributes,
  # self-closing, closing) must be one of the two exact tags, and the exact element must be there.
  require_setting() {
    local name="$1" value="$2" tags
    tags="$( (grep -oE "</?([A-Za-z0-9_.-]+:)?${name}[^<>]*>" <<<"$flat" || true) | tr '\n' ' ')"
    if [ "$tags" != "<${name}> </${name}> " ] || ! grep -qF "<${name}>${value}</${name}>" <<<"$flat"; then
      fail "$name" "$metadata must contain exactly one <${name}>${value}</${name}>"
    fi
  }
  require_setting verify-metadata true
  require_setting verify-signatures false

  # scan_elements <rule> <element name regex>
  # An optional namespace prefix is allowed for, so <x:trust .../> doesn't slip past. The name must end at a
  # space, '/' or '>', which keeps <trusted-artifacts> and <trusted-key> out of the trust scan.
  scan_elements() {
    local rule="$1" name="$2" element squeezed
    while IFS= read -r element; do
      [ -n "$element" ] || continue
      squeezed="$(tr -s ' ' <<<"$element")"
      if [ "$rule" = trust ] && [ "$squeezed" = "$allowed_trust" ]; then
        continue
      fi
      fail "$rule" "$metadata: $squeezed"
    done < <(grep -oE "<([A-Za-z0-9_.-]+:)?${name}([[:space:]/][^>]*)?>" <<<"$flat" || true)
  }
  scan_elements trusted-keys 'trusted-keys?'
  scan_elements trust 'trust'
  scan_elements also-trust 'also-trust'
  scan_elements ignored-keys 'ignored-keys?'
fi

# scan_file <rule> <grep flags> <pattern> <file> [suffix]: fail <rule> on the first line of <file> that matches.
scan_file() {
  local hits
  if hits="$(grep "$2" "$3" "$4")"; then
    fail "$1" "$4:$(head -n1 <<<"$hits")${5:-}"
  fi
}

# Any gradle.properties, not just the root's: the build-logic included build has its own.
while IFS= read -r props; do
  [ -n "$props" ] || continue
  scan_file properties -nE 'org\.gradle\.dependency\.verification' "$props"
done < <(find . \( -name build -o -name .gradle -o -name .git -o -path ./.github/scripts/fixtures \) -prune \
  -o -name gradle.properties -print | sort)

# Everything under .github/ that could run in CI: workflows, composite actions and the scripts they call.
if [ -d .github ]; then
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    scan_file workflow-flag -niE 'dependency-verification|org\.gradle\.dependency\.verification' "$file"
    scan_file no-ci-rewrite -nE 'write-verification-metadata' "$file"
    # The short forms: -F is --dependency-verification and -M is --write-verification-metadata. Matched with their
    # only valid values (any case, quoted or not, joined with = or a space; -M also takes a comma list such as
    # sha256,pgp), so grep -F and curl -F don't trip them.
    scan_file workflow-flag -niE "(^|[[:space:]'\"=])-F[[:space:]=]*['\"]?(off|lenient|strict)" "$file" \
      ' (-F is --dependency-verification)'
    scan_file no-ci-rewrite -niE "(^|[[:space:]'\"=])-M[[:space:]=]*['\"]?(md5|sha1|sha256|sha512|pgp)" "$file" \
      ' (-M is --write-verification-metadata)'
    scan_file fixture-reference -nE 'fixtures/verification' "$file"
  done < <(find .github -type f \
    ! -path '.github/scripts/fixtures/verification/*' ! -path .github/scripts/verification-guard.sh \
    ! -path .github/scripts/verification-guard.test.sh | sort)
fi

if [ "$failures" -ne 0 ]; then
  echo "$failures dependency verification rule(s) failed (see docs/adr/0013-dependency-verification.md)"
  exit 1
fi
echo "dependency verification guard: ok"
