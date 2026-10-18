#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Tests for instrumented-infra.sh's classify and cleanup modes. Each case builds a results tree and the files `run`
# would have saved, in a temp dir.
# Usage: bash .github/scripts/instrumented-infra.test.sh
# No -e: a failing case must be reported and counted, not end the run.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$here/instrumented-infra.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0
n=0

fail() {
  echo "FAIL $1: $2"
  shift 2
  printf '%s\n' "$@" | sed 's/^/    /'
  failures=$((failures + 1))
}

# A fresh case dir: $case/root holds the module trees, $case/diag the files `run` saves.
new_case() {
  n=$((n + 1))
  case_dir="$work/case$n"
  mkdir -p "$case_dir/diag" "$case_dir/root/app/build/outputs/androidTest-results/connected/debug"
  results="$case_dir/root/app/build/outputs/androidTest-results/connected/debug/TEST-device.xml"
}

# xml <testcase body>: a result file with one failing test case.
xml() {
  printf '<?xml version="1.0"?>\n<testsuite name="s" tests="2" failures="1" errors="0">\n<testcase name="ok" classname="c"/>\n<testcase name="bad" classname="c">\n%s\n</testcase>\n</testsuite>\n' "$1" >"$results"
}

# expect <name> <want exit> [text the output must contain]
expect() {
  local name="$1" want="$2" text="${3:-}" out got
  out="$(bash "$script" classify "$case_dir/diag" "$case_dir/root" 2>&1)"
  got=$?
  if [ "$got" -ne "$want" ]; then
    fail "$name" "exit $got, want $want" "$out"
  elif [ -n "$text" ] && ! grep -qF -- "$text" <<<"$out"; then
    fail "$name" "output lacks '$text'" "$out"
  else
    echo "ok   $name (exit $got)"
  fi
}

# A real assertion failure is never retried.
new_case
xml '<failure message="java.lang.AssertionError: expected:&lt;1&gt; but was:&lt;2&gt;" type="java.lang.AssertionError">at c.bad(C.kt:1)</failure>'
expect "assertion failure" 1 "genuine"

new_case
xml '<failure message="Failed to perform text input.">stack</failure>'
expect "a failure with a message, even about text input" 1 "genuine"

new_case
xml '<failure message="">java.lang.AssertionError: boom
at c.bad(C.kt:1)</failure>'
expect "empty message but a stack in the body" 1 "genuine"

new_case
xml '<error message="java.lang.IllegalStateException">trace</error>'
expect "an <error> with a message" 1 "genuine"

# A real failure wins over every infrastructure sign.
new_case
xml '<failure message="expected true">trace</failure>'
echo 'F libc    : Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0 in tid 1 (surfaceflinger)' >"$case_dir/diag/logcat.txt"
echo gone >"$case_dir/diag/emulator-state.txt"
expect "assertion failure plus a crashed emulator" 1 "genuine"

# One real failure among empty ones is still a real failure.
new_case
xml '<failure message=""/><failure message="expected true">trace</failure>'
expect "one empty and one real failure" 1 "genuine"

# Empty failures: the process died under the test.
new_case
xml '<failure message="" type=""/>'
expect "self-closed empty failure" 0 "empty <failure>"

new_case
xml '<failure/>'
expect "bare self-closed failure" 0 "empty <failure>"

new_case
xml '<failure message="">
</failure>'
expect "empty failure with a blank body" 0 "empty <failure>"

new_case
xml '<error message=""/>'
expect "empty error" 0 "empty <failure>"

# Logcat: the system crashed.
new_case
xml '<testcase name="x"/>'
echo 'F libc    : Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0 in tid 120 (surfaceflinger), pid 120 (surfaceflinger)' >"$case_dir/diag/logcat.txt"
echo alive >"$case_dir/diag/emulator-state.txt"
echo device >"$case_dir/diag/adb-state.txt"
expect "surfaceflinger SIGSEGV in the logcat" 0 "surfaceflinger or system_server"

new_case
xml '<testcase name="x"/>'
echo 'E AndroidRuntime: *** FATAL EXCEPTION IN SYSTEM PROCESS: android.fg' >"$case_dir/diag/logcat.txt"
expect "system process fatal exception" 0 "surfaceflinger or system_server"

new_case
xml '<testcase name="x"/>'
echo 'I Zygote  : System server process 1234 has died. Restarting Zygote!' >"$case_dir/diag/logcat.txt"
expect "system server died" 0 "surfaceflinger or system_server"

# A crash in the app under test is not infrastructure.
new_case
xml '<testcase name="x"/>'
echo 'F libc    : Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0 in tid 4 (tv.stationbreak.app)' >"$case_dir/diag/logcat.txt"
echo alive >"$case_dir/diag/emulator-state.txt"
echo device >"$case_dir/diag/adb-state.txt"
expect "the app's own native crash" 1 "genuine"

# The emulator or adb went away.
new_case
xml '<testcase name="x"/>'
echo gone >"$case_dir/diag/emulator-state.txt"
echo device >"$case_dir/diag/adb-state.txt"
expect "emulator process gone" 0 "emulator process died"

new_case
xml '<testcase name="x"/>'
echo alive >"$case_dir/diag/emulator-state.txt"
echo offline >"$case_dir/diag/adb-state.txt"
expect "adb sees the device offline" 0 "adb no longer sees a device"

new_case
xml '<testcase name="x"/>'
echo alive >"$case_dir/diag/emulator-state.txt"
echo 'error: no devices/emulators found' >"$case_dir/diag/adb-state.txt"
expect "adb finds no device" 0 "adb no longer sees a device"

# No results: never booted, or the step timed out (nothing saved at all).
new_case
rm -rf "$case_dir/root/app"
expect "no results and nothing saved (timeout or boot failure)" 0 "no test results"

# Failed, healthy emulator, results with no failure in them: leave it to a human.
new_case
xml '<testcase name="x"/>'
echo alive >"$case_dir/diag/emulator-state.txt"
echo device >"$case_dir/diag/adb-state.txt"
echo 'I ActivityManager: nothing wrong' >"$case_dir/diag/logcat.txt"
expect "failed with results, healthy emulator, no failure element" 1 "genuine"

# Results in a module other than app are found too.
new_case
mkdir -p "$case_dir/root/feature/onboarding/build/outputs/androidTest-results/connected/debug"
results="$case_dir/root/feature/onboarding/build/outputs/androidTest-results/connected/debug/TEST-device.xml"
xml '<failure message="expected:&lt;a&gt;">trace</failure>'
rm -rf "$case_dir/root/app"
expect "a module's results" 1 "genuine"

# Cut off by the step timeout (issue #263): `run` started (marker) but was killed before it saved emulator-state.txt.
# Only the modules in CONNECTED_TASKS that wrote no results count as missing.
cut_off_case() {
  new_case
  mkdir -p "$case_dir/root/feature/onboarding"
  : >"$case_dir/diag/started"
  export CONNECTED_TASKS="connectedGithubDebugAndroidTest :feature:onboarding:connectedDebugAndroidTest"
}

cut_off_case
xml '<testcase name="x"/>'
expect "timeout, no failures, a module has no results" 0 "cut off"

cut_off_case
xml '<failure message="expected true">trace</failure>'
expect "timeout with a real failure" 1 "genuine"

cut_off_case
xml '<failure message=""/>'
expect "timeout with an empty failure" 0 "empty <failure>"

cut_off_case
xml '<testcase name="x"/>'
mkdir -p "$case_dir/root/feature/onboarding/build/outputs/androidTest-results/connected/debug"
cp "$results" "$case_dir/root/feature/onboarding/build/outputs/androidTest-results/connected/debug/TEST-device.xml"
expect "timeout but every module has results" 1 "genuine"

# Complete results and a failure: the run finished, saved its state, and a test failed.
cut_off_case
rm -f "$case_dir/diag/started"
echo alive >"$case_dir/diag/emulator-state.txt"
echo device >"$case_dir/diag/adb-state.txt"
xml '<failure message="expected true">trace</failure>'
mkdir -p "$case_dir/root/feature/onboarding/build/outputs/androidTest-results/connected/debug"
cp "$results" "$case_dir/root/feature/onboarding/build/outputs/androidTest-results/connected/debug/TEST-device.xml"
expect "complete results plus a failure" 1 "genuine"

# A run that finished (state saved) with a module missing is not a timeout.
cut_off_case
echo alive >"$case_dir/diag/emulator-state.txt"
echo device >"$case_dir/diag/adb-state.txt"
xml '<testcase name="x"/>'
expect "finished run, a module missing, no timeout marker" 1 "genuine"
unset CONNECTED_TASKS

# Usage errors.
if bash "$script" >/dev/null 2>&1; then fail "usage" "no arguments exited 0"; else echo "ok   usage (no arguments fail)"; fi
if bash "$script" nonsense "$work" >/dev/null 2>&1; then fail "usage" "unknown mode exited 0"; else echo "ok   usage (unknown mode fails)"; fi

# cleanup: the attempt's results move into <dir>/results, and the emulator and adb are killed. pkill and adb are
# stubs that record the call; the real ones would kill this machine's processes.
new_case
xml '<failure message="">x</failure>'
stub="$work/stub"
mkdir -p "$stub"
for tool in pkill adb; do
  printf '#!/usr/bin/env bash\necho "%s $*" >>"%s/calls"\n' "$tool" "$stub" >"$stub/$tool"
  chmod +x "$stub/$tool"
done
(cd "$case_dir/root" && PATH="$stub:$PATH" bash "$script" cleanup "$case_dir/diag")
got=$?
saved="$(find "$case_dir/diag/results" -name '*.xml' | wc -l)"
left="$(find "$case_dir/root" -name 'TEST-*.xml' | wc -l)"
if [ "$got" -eq 0 ] && [ "$saved" -eq 1 ] && [ "$left" -eq 0 ] \
  && grep -q '^pkill -9 -f qemu-system' "$stub/calls" && grep -q '^adb kill-server' "$stub/calls"; then
  echo "ok   cleanup (results saved, emulator and adb killed)"
else
  fail "cleanup" "exit $got, saved $saved, left $left" "$(cat "$stub/calls" 2>/dev/null)"
fi

if [ "$failures" -ne 0 ]; then
  echo "$failures test(s) failed"
  exit 1
fi
echo "all tests passed"
