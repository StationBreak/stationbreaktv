#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Tests for store-secrets-guard.sh. Each case builds a small repo-shaped directory in a temp dir, so no secret-shaped
# text is committed here. The guard must exit 1 and name the rule for every way a store secret, a printed secret or a
# store submission can get in, and exit 0 for clean trees.
# Usage: bash .github/scripts/store-secrets-guard.test.sh
# No -e: a failing case must be reported and counted, not end the run.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$here/store-secrets-guard.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0
n=0

# case_dir <name> <path> <content>: a tree holding one file.
case_dir() {
  local dir="$work/$1"
  mkdir -p "$dir/$(dirname "$2")"
  printf '%s\n' "$3" >"$dir/$2"
  echo "$dir"
}

# expect <name> <path> <content> <exit> [<rule>]
expect() {
  local name="$1" path="$2" content="$3" want="$4" rule="${5:-}" dir out got
  dir="$(case_dir "$name" "$path" "$content")"
  out="$(bash "$script" "$dir" 2>&1)"
  got=$?
  n=$((n + 1))
  if [ "$got" -ne "$want" ]; then
    echo "FAIL $name: exit $got, want $want"
    printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
  elif [ -n "$rule" ] && ! grep -qE "^FAIL $rule: \./$path:[0-9]+$" <<<"$out"; then
    echo "FAIL $name: no 'FAIL $rule: ./$path:<line>' line"
    printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
  elif grep -q "$content" <<<"$out" 2>/dev/null && [ "$want" -eq 1 ]; then
    echo "FAIL $name: the output repeats the matched text"
    failures=$((failures + 1))
  else
    echo "ok   $name (exit $got)"
  fi
}

# Clean.
expect clean-workflow .github/workflows/ci.yml $'env:\n  TOKEN: ${{ secrets.RELEASE_KEY_PASSWORD }}\nrun: ./gradlew check' 0
expect keystore-secret-store app/Store.kt 'class KeystoreSecretStore' 0
expect plain-code app/Main.kt 'val sku = "pro"' 0
expect docs-may-name-them docs/iap.md 'The IAP shared key never goes in the app' 0

# iap-secret.
expect amazon-shared-key app/Pro.kt 'const val AMAZON_SHARED_KEY = "x"' 1 iap-secret
expect iap-secret-env .github/workflows/ci.yml 'IAP_SECRET: ${{ secrets.IAP_SECRET }}' 1 iap-secret
expect rvs-secret gradle.properties 'rvsSecret=abc' 1 iap-secret
expect shared-secret-prose app/Pro.kt '// the shared secret for receipts' 1 iap-secret
expect appstore-api-key scripts/x.sh 'APPSTORE_API_KEY=1' 1 iap-secret
expect amazon-private-key app/b.gradle.kts 'val amazonPrivateKey = 1' 1 iap-secret

# secret-printed.
expect echo-secret .github/workflows/x.yml 'run: echo "${{ secrets.RELEASE_KEY_PASSWORD }}"' 1 secret-printed
expect secret-redirect .github/workflows/x.yml 'run: ${{ secrets.RELEASE_KEY_PASSWORD }} >> out.txt' 1 secret-printed
expect secret-printf .github/workflows/x.yml 'run: printf %s ${{ secrets.A_TOKEN }}' 1 secret-printed

# store-submission.
expect amazon-api .github/workflows/x.yml 'run: curl https://developer.amazon.com/api/appstore/v1/applications' 1 store-submission
expect play-upload .github/workflows/x.yml 'uses: r0adkll/upload-google-play@v1' 1 store-submission
expect fastlane-supply scripts/deploy.sh 'fastlane supply --aab app.aab' 1 store-submission
expect gradle-publisher app/build.gradle.kts 'implementation("androidpublisher")' 1 store-submission
expect appstore-in-app-code app/Pro.kt 'val url = "developer.amazon.com/api/appstore"' 0

if [ "$failures" -ne 0 ]; then
  echo "$failures of $n failed"
  exit 1
fi
echo "all $n passed"
