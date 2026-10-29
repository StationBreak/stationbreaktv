#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Tests scripts/connected-results.sh on fake module trees: which tasks count as having no results, and how the two
# attempts' statuses combine.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

check() { # <description> <0 if it holds>
  if [ "$2" -eq 0 ]; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}
expect() { # <description> <expected> <actual>
  if [ "$2" = "$3" ]; then check "$1" 0; else check "$1 (expected '$2', got '$3')" 1; fi
}

# shellcheck source=scripts/connected-results.sh
source "$here/connected-results.sh"

cd "$work"
module() { # <dir> <has-tests: yes|no>
  mkdir -p "$1/src/androidTest/kotlin"
  if [ "$2" = yes ]; then
    printf 'class T {\n  @Test fun t() {}\n}\n' >"$1/src/androidTest/kotlin/T.kt"
  else
    printf 'class Helper\n' >"$1/src/androidTest/kotlin/Helper.kt"
  fi
}
result() { # <dir> [old]: a JUnit XML in the module's connected results, made before the run started if "old"
  local dir="$1/build/outputs/androidTest-results/connected/debug"
  mkdir -p "$dir"
  : >"$dir/TEST-dev.xml"
  if [ "${2:-}" = old ]; then touch -d '2000-01-01' "$dir/TEST-dev.xml"; fi
}

module app yes
module core/ui yes
module feature/guide yes
module data/settings yes
module core/empty no
: >since
touch -d '2001-01-01' since
result app
result core/ui old
result data/settings
# feature/guide: no results at all. core/empty: none, and no tests.

all=(connectedGithubDebugAndroidTest :core:ui:connectedDebugAndroidTest :feature:guide:connectedDebugAndroidTest
  :data:settings:connectedDebugAndroidTest :core:empty:connectedDebugAndroidTest)
expect "a module with old results, or none, is listed; one with new results or no tests is not" \
  ":core:ui:connectedDebugAndroidTest :feature:guide:connectedDebugAndroidTest" \
  "$(tasks_without_results since "${all[@]}" | tr '\n' ' ' | sed 's/ $//')"

rm -rf app/build
expect "the app's unqualified task is checked under app/" "connectedGithubDebugAndroidTest" \
  "$(tasks_without_results since connectedGithubDebugAndroidTest)"

expect "connected_module maps :a:b to a/b" "feature/guide" "$(connected_module :feature:guide:connectedDebugAndroidTest)"

# connected_status. Logs as Gradle writes them with --continue.
printf "* What went wrong:\nExecution failed for task ':data:settings:connectedDebugAndroidTest'.\n" >log-settings
printf "* What went wrong:\nExecution failed for task ':app:connectedGithubDebugAndroidTest'.\n" >log-app
printf "* What went wrong:\nA problem occurred configuring project ':app'.\n" >log-config
: >log-pass

expect "all passed first time" 0 "$(connected_status 0 log-pass 0)"
expect "a module re-run after a stopped install, passing the second time, passes" 0 \
  "$(connected_status 0 log-pass 0 :core:ui:connectedDebugAndroidTest)"
expect "a re-run module that fails again fails the run" 1 \
  "$(connected_status 0 log-pass 1 :core:ui:connectedDebugAndroidTest)"
expect "a real failure in a module that wrote results is kept, though the re-run passed" 1 \
  "$(connected_status 1 log-settings 0 :core:ui:connectedDebugAndroidTest)"
expect "a failure in a module that was re-run is judged by the re-run" 0 \
  "$(connected_status 1 log-app 0 connectedGithubDebugAndroidTest)"
expect "a failure that names no task is kept" 1 \
  "$(connected_status 1 log-config 0 connectedGithubDebugAndroidTest)"
expect "a first-attempt timeout is final" 124 "$(connected_status 124 log-pass 0)"
expect "a re-run timeout is reported" 124 \
  "$(connected_status 0 log-pass 124 :core:ui:connectedDebugAndroidTest)"
expect "with nothing re-run, the first status stands" 1 "$(connected_status 1 log-settings 0)"

if [ "$failures" -ne 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
