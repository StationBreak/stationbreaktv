#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Tests for release-scope.sh: which changed files make a PR "risky" for the release build.
# Usage: bash .github/scripts/release-scope.test.sh
# No -e: a failing case must be reported and counted, not end the run.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$here/release-scope.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
failures=0

# expect <true|false> <case> <files (newline separated)> [patch text]
expect() {
  local want="$1" name="$2" files="$3" patch_text="${4:-}" got patch_file=""
  if [ -n "$patch_text" ]; then
    patch_file="$tmp/patch"
    printf '%s\n' "$patch_text" >"$patch_file"
  fi
  got="$(printf '%s\n' "$files" | bash "$script" ${patch_file:+"$patch_file"} 2>/dev/null)"
  if [ "$got" = "$want" ]; then
    echo "ok   $name ($got)"
  else
    echo "FAIL $name: got '$got', want '$want'"
    failures=$((failures + 1))
  fi
}

# Debug-only changes: no release build.
expect false "kotlin source" $'feature/onboarding/src/main/kotlin/A.kt\ncore/ui/src/main/kotlin/B.kt'
expect false "tests and resources" $'app/src/test/kotlin/ATest.kt\napp/src/main/res/values/strings.xml'
expect false "docs only" $'docs/adr/0001.md\nREADME.md'
expect false "other workflow" $'.github/workflows/codeql.yml'
expect false "other script" $'.github/scripts/dco-check.sh'
expect false "kotlin without annotations" $'core/rules/src/commonMain/kotlin/R.kt' $'+++ b/R.kt\n+val x = 1'
expect false "a file merely named like a manifest" $'docs/AndroidManifest.xml.md'

# Build and dependency surface.
expect true "root build script" $'build.gradle.kts'
expect true "module build script" $'feature/onboarding/build.gradle.kts'
expect true "settings" $'settings.gradle.kts'
expect true "gradle properties" $'gradle.properties'
expect true "version catalog" $'gradle/libs.versions.toml'
expect true "dependency verification" $'gradle/verification-metadata.xml'
expect true "wrapper" $'gradle/wrapper/gradle-wrapper.properties'
expect true "convention plugin" $'build-logic/convention/src/main/kotlin/X.kt'

# Shrinker, manifest, signing, packaging.
expect true "proguard rules" $'app/proguard-rules.pro'
expect true "consumer rules" $'core/ui/consumer-rules.pro'
expect true "app manifest" $'app/src/main/AndroidManifest.xml'
expect true "flavor manifest" $'app/src/amazon/AndroidManifest.xml'
expect true "release source set" $'app/src/githubRelease/kotlin/X.kt'
expect true "plain release source set" $'app/src/release/kotlin/X.kt'
expect true "keystore" $'keys/upload.jks'
expect true "network security config" $'app/src/main/res/xml/network_security_config.xml'
expect true "trust anchor pem" $'app/src/main/res/raw/isrg_root_x2.pem'
expect true "trust anchors readme" $'app/trust-anchors/README.md'
expect true "moved out of a release source set" $'app/src/githubRelease/kotlin/X.kt\napp/src/debug/kotlin/X.kt'
expect true "quoted file name" $'"app/src/main/kotlin/caf\\303\\251.kt"'

# Gates and this decision.
expect true "lint config" $'config/lint/lint.xml'
expect true "detekt config" $'config/detekt/detekt.yml'
expect true "ci.yml" $'.github/workflows/ci.yml'
expect true "this script" $'.github/scripts/release-scope.sh'

# Content rule.
expect true "serializable added" $'core/model/src/commonMain/kotlin/Ids.kt' $'+++ b/Ids.kt\n+@Serializable\n+class Id'
expect true "serializable removed" $'x/Y.kt' $'-@Serializable'
expect true "serial name" $'x/Y.kt' $'+    @SerialName("a")'
expect true "keep annotation" $'x/Y.kt' $'+@Keep'
expect true "serialization import" $'x/Y.kt' $'+import kotlinx.serialization.json.Json'

# Combined: one risky file among safe ones.
expect true "mixed diff" $'docs/a.md\nfeature/x/src/main/kotlin/A.kt\ngradle/libs.versions.toml'
expect false "empty list" ""

if [ "$failures" -ne 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all release-scope cases passed"
