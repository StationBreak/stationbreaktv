#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Tests for instrumented-infra.sh's classify, prepare and cleanup modes. Each case builds a results tree and the files `run`
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

# Known emulator signatures (#444, #462), from fixtures/instrumented-infra/.
fx="$here/fixtures/instrumented-infra"

new_case
xml "$(cat "$fx/inject-events.failure.xml")"
expect "INJECT_EVENTS refused (a system dialog took focus)" 0 "INJECT_EVENTS"

new_case
xml "$(cat "$fx/inject-events.failure.xml")$(cat "$fx/inject-events.failure.xml")"
expect "two INJECT_EVENTS failures" 0 "2 test failure(s) refused"

new_case
xml "$(cat "$fx/inject-events.failure.xml")$(cat "$fx/compose-timeout.failure.xml")"
expect "INJECT_EVENTS plus a real timeout" 1 "genuine"

new_case
xml "$(cat "$fx/no-compose-hierarchies.failure.xml")"
expect "No compose hierarchies found, the only failure" 0 "sole signature"

new_case
xml "$(cat "$fx/no-compose-hierarchies.failure.xml")$(cat "$fx/no-compose-hierarchies.failure.xml")"
expect "No compose hierarchies found twice, nothing else" 0 "all 'No compose hierarchies found'"

new_case
xml "$(cat "$fx/no-compose-hierarchies.failure.xml")<failure message=\"java.lang.AssertionError: expected:&lt;1&gt;\">trace</failure>"
expect "No compose hierarchies found plus an assertion" 1 "genuine"

new_case
xml "$(cat "$fx/no-compose-hierarchies.failure.xml")$(cat "$fx/inject-events.failure.xml")"
expect "No compose hierarchies found plus INJECT_EVENTS (not the sole signature)" 1 "genuine"

new_case
xml "$(cat "$fx/no-compose-hierarchies.failure.xml")<failure message=\"\"/>"
expect "No compose hierarchies found plus an empty failure" 1 "genuine"

# The strictness: a test's own timeout, or an assertion that merely quotes the words, stays genuine.
new_case
xml "$(cat "$fx/compose-timeout.failure.xml")"
expect "ComposeTimeoutException from a test's own waitUntil" 1 "genuine"

new_case
xml "$(cat "$fx/assertion-mentions-hierarchies.failure.xml")"
expect "an assertion that quotes the hierarchies message" 1 "genuine"

# Real AGP connected-test XML has no message attribute: the body starts with the exception class.
new_case
xml "$(cat "$fx/body-inject-events.failure.xml")"
expect "body-only INJECT_EVENTS" 0 "INJECT_EVENTS"

new_case
xml "$(cat "$fx/body-no-compose-hierarchies.failure.xml")"
expect "body-only No compose hierarchies found, alone" 0 "sole signature"

new_case
xml "$(cat "$fx/body-compose-timeout.failure.xml")"
expect "body-only ComposeTimeoutException" 1 "genuine"

new_case
xml "$(cat "$fx/body-assertion-quotes-hierarchies.failure.xml")"
expect "body-only assertion quoting the hierarchies message" 1 "genuine"

new_case
xml "$(cat "$fx/body-assertion-caused-by-hierarchies.failure.xml")"
expect "body-only assertion with the hierarchies message in a Caused by" 1 "genuine"

# A type="" attribute that names another class is not the signature.
new_case
xml "$(cat "$fx/inject-events-wrong-type.failure.xml")"
expect "INJECT_EVENTS message but another type" 1 "genuine"

new_case
xml '<failure message="java.lang.AssertionError: boom">java.lang.SecurityException: Injecting to another application requires INJECT_EVENTS permission</failure>'
expect "INJECT_EVENTS only deep in an assertion's body" 1 "genuine"

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

# (b) Marker but no CONNECTED_TASKS: the cut-off rule cannot tell what is missing, so the old behaviour holds.
cut_off_case
unset CONNECTED_TASKS
xml '<testcase name="x"/>'
expect "marker, CONNECTED_TASKS unset, results present" 1 "genuine"

# (c) The app's results are the missing ones; a library module's are there.
cut_off_case
rm -rf "$case_dir/root/app"
mkdir -p "$case_dir/root/feature/onboarding/build/outputs/androidTest-results/connected/debug"
results="$case_dir/root/feature/onboarding/build/outputs/androidTest-results/connected/debug/TEST-device.xml"
xml '<testcase name="x"/>'
expect "cut off, the app's results missing" 0 "no results from: app"

# (d) Marker and no results at all: the cut-off message, still exit 0.
cut_off_case
rm -rf "$case_dir/root/app"
expect "marker and no results at all" 0 "cut off"

# A glob or odd word in CONNECTED_TASKS is skipped with a note and never expanded.
cut_off_case
xml '<testcase name="x"/>'
export CONNECTED_TASKS='* :feature:onboarding:connectedDebugAndroidTest'
globdir="$case_dir/globs"
mkdir -p "$globdir" && touch "$globdir/a.txt" "$globdir/b.txt"
out="$(cd "$globdir" && bash "$script" classify "$case_dir/diag" "$case_dir/root" 2>&1)"
got=$?
if [ "$got" -eq 0 ] && grep -qF 'skipping' <<<"$out" && grep -qF 'feature/onboarding' <<<"$out" && ! grep -qF 'a.txt' <<<"$out"; then
  echo "ok   a glob in CONNECTED_TASKS is skipped, not expanded (exit $got)"
else
  fail "glob in CONNECTED_TASKS" "exit $got" "$out"
fi
unset CONNECTED_TASKS

# Usage errors.
if bash "$script" >/dev/null 2>&1; then fail "usage" "no arguments exited 0"; else echo "ok   usage (no arguments fail)"; fi
if bash "$script" nonsense "$work" >/dev/null 2>&1; then fail "usage" "unknown mode exited 0"; else echo "ok   usage (unknown mode fails)"; fi

# prepare: every adb call is made, and none failing fails the step. adb is a stub that records its calls and fails.
stub_prep="$work/stub-prep"
mkdir -p "$stub_prep"
printf '#!/usr/bin/env bash\necho "adb $*" >>"%s/calls"\nexit 1\n' "$stub_prep" >"$stub_prep/adb"
chmod +x "$stub_prep/adb"
out="$(PATH="$stub_prep:$PATH" bash "$script" prepare "$work/unused" 2>&1)"
got=$?
calls="$(cat "$stub_prep/calls" 2>/dev/null)"
if [ "$got" -eq 0 ] \
  && grep -qF 'adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS' <<<"$calls" \
  && grep -qF 'adb shell pm disable-user --user 0 com.google.android.configupdater' <<<"$calls" \
  && grep -qF 'adb logcat -G 16M' <<<"$calls" \
  && grep -qF 'adb logcat -G 8M' <<<"$calls" \
  && grep -qF "adb shell logcat -P ''" <<<"$calls" \
  && grep -qF 'adb logcat -g' <<<"$calls" \
  && grep -qF 'adb shell logcat -p' <<<"$calls" \
  && grep -qF 'prepare: logcat buffers:' <<<"$out" \
  && grep -qF 'prepare: logcat prune list:' <<<"$out"; then
  echo "ok   prepare (all adb calls made and reported, exit 0 though each failed)"
else
  fail "prepare" "exit $got" "$calls" "$out"
fi

# prepare cannot hang the job: a stub adb that sleeps is cut off by `timeout`. timeout is a stub that records its
# argument and runs the command with a 1 second limit instead of 20, so the test stays fast.
stub_to="$work/stub-timeout"
mkdir -p "$stub_to"
printf '#!/usr/bin/env bash\necho "timeout $1" >>"%s/calls"\nshift\n' "$stub_to" >"$stub_to/timeout"
printf 'exec /usr/bin/timeout 1 "$@"\n' >>"$stub_to/timeout"
printf '#!/usr/bin/env bash\nsleep 30\n' >"$stub_to/adb"
chmod +x "$stub_to/timeout" "$stub_to/adb"
start=$SECONDS
PATH="$stub_to:$PATH" bash "$script" prepare "$work/unused" >/dev/null 2>&1
got=$?
if [ "$got" -eq 0 ] && [ $((SECONDS - start)) -lt 25 ] && grep -qx 'timeout 20' "$stub_to/calls"; then
  echo "ok   prepare (every adb call bounded by timeout 20; a hung adb does not hang or fail it)"
else
  fail "prepare timeout" "exit $got after $((SECONDS - start))s" "$(cat "$stub_to/calls" 2>/dev/null)"
fi

# run calls prepare before gradle. gradlew and adb are stubs that record their order.
new_case
stub_run="$work/stub-run"
mkdir -p "$stub_run/bin"
printf '#!/usr/bin/env bash\necho "adb $*" >>"%s/order"\n' "$stub_run" >"$stub_run/bin/adb"
printf '#!/usr/bin/env bash\necho "gradlew $*" >>"%s/order"\n' "$stub_run" >"$stub_run/gradlew"
chmod +x "$stub_run/bin/adb" "$stub_run/gradlew"
(cd "$stub_run" && PATH="$stub_run/bin:$PATH" CONNECTED_TASKS="connectedDebugAndroidTest" \
  bash "$script" run "$case_dir/diag" >/dev/null 2>&1)
got=$?
first_gradle="$(grep -n '^gradlew' "$stub_run/order" | head -1 | cut -d: -f1)"
last_adb="$(grep -n '^adb' "$stub_run/order" | tail -1 | cut -d: -f1)"
if [ "$got" -eq 0 ] && [ -n "$first_gradle" ] && [ -n "$last_adb" ] && [ "$last_adb" -lt "$first_gradle" ]; then
  echo "ok   run calls prepare (all adb setup) before gradle"
else
  fail "run order" "exit $got" "$(cat "$stub_run/order" 2>/dev/null)"
fi

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

# verify and the missing-results rule (#489): Gradle passed, but a module's APK never installed, so it wrote no XML.
# A repo tree in a temp dir: app, :core:ui and :data:lib have androidTest sources with a real @Test; :feature:none has
# only helpers. gradlew is a stub that copies a passing result file into each module dir listed in $STUB_WRITES.
new_repo() {
  n=$((n + 1))
  repo="$work/repo$n"
  diag="$repo/diag"
  mkdir -p "$diag" "$repo/bin"
  for m in app core/ui data/lib feature/none; do mkdir -p "$repo/$m/src/androidTest/kotlin"; done
  for m in app core/ui data/lib; do cp "$fx/androidtest-with-test.kt.txt" "$repo/$m/src/androidTest/kotlin/T.kt"; done
  cp "$fx/androidtest-without-test.kt.txt" "$repo/feature/none/src/androidTest/kotlin/H.kt"
  printf '#!/usr/bin/env bash\nfor m in $STUB_WRITES; do d="$m/build/outputs/androidTest-results/connected/debug"; mkdir -p "$d"; cp "%s" "$d/TEST-device.xml"; done\n' \
    "$fx/connected-pass.result.xml" >"$repo/gradlew"
  chmod +x "$repo/gradlew"
  tasks="connectedGithubDebugAndroidTest :core:ui:connectedDebugAndroidTest :data:lib:connectedDebugAndroidTest :feature:none:connectedDebugAndroidTest"
  # Stubs, so `run` never touches this machine's emulator.
  for tool in adb pgrep timeout; do
    printf '#!/usr/bin/env bash\nexit 0\n' >"$repo/bin/$tool"
    chmod +x "$repo/bin/$tool"
  done
  # A live device, so classify reaches the missing-results rule rather than the adb-state ones.
  printf '#!/usr/bin/env bash\n[ "$1" = get-state ] && echo device\nexit 0\n' >"$repo/bin/adb"
}

# run_in <writes>: `run` with the stub gradle, which writes results for the listed module dirs.
run_in() {
  out="$(cd "$repo" && STUB_WRITES="$1" PATH="$repo/bin:$PATH" CONNECTED_TASKS="$tasks" bash "$script" run "$diag" 2>&1)"
  got=$?
}

# expect_run <name> <want exit> <writes> [text]
expect_run() {
  run_in "$3"
  if [ "$got" -ne "$2" ]; then
    fail "$1" "exit $got, want $2" "$out"
  elif [ -n "${4:-}" ] && ! grep -qF -- "$4" <<<"$out"; then
    fail "$1" "output lacks '$4'" "$out"
  else
    echo "ok   $1 (exit $got)"
  fi
}

new_repo
expect_run "verify: every module with tests wrote fresh results" 0 "app core/ui data/lib" "every module with instrumented tests wrote results"

new_repo
expect_run "verify: a module with zero real tests is ignored" 0 "app core/ui data/lib"
if grep -q 'feature' <<<"$out"; then fail "zero-test module" "named in the output" "$out"; fi

new_repo
expect_run "verify: one module missing fails the run and names it" 1 "app data/lib" "::error::"
if [ "$(cat "$diag/missing-results.txt")" = ":core:ui:connectedDebugAndroidTest" ] && ! grep -qF 'data/lib (' <<<"$out"; then
  echo "ok   verify lists only the missing module"
else
  fail "verify lists" "missing-results.txt: $(cat "$diag/missing-results.txt" 2>/dev/null)" "$out"
fi

new_repo
expect_run "verify: the app missing is named as app" 1 "core/ui data/lib" "wrote no results (the APK did not install?): app "

# XML from before this attempt counts as missing.
new_repo
stale="$repo/core/ui/build/outputs/androidTest-results/connected/debug"
mkdir -p "$stale"
cp "$fx/connected-pass.result.xml" "$stale/TEST-old.xml"
touch -d '2 hours ago' "$stale/TEST-old.xml"
expect_run "verify: stale XML from before the attempt counts as missing" 1 "app data/lib" "core/ui"

# A verify failure is infrastructure (retry); a retry that fills the hole passes; one that does not fails the job
# with the error. Each attempt has its own diag dir, as in ci.yml.
new_repo
run_in "app data/lib"
c_out="$(bash "$script" classify "$diag" "$repo" 2>&1)"
c_got=$?
if [ "$c_got" -eq 0 ] && grep -qF "missing results for :core:ui:connectedDebugAndroidTest" <<<"$c_out"; then
  echo "ok   classify: missing results are infrastructure (retry)"
else
  fail "classify missing" "exit $c_got" "$c_out"
fi
(cd "$repo" && PATH="$repo/bin:$PATH" bash "$script" cleanup "$diag" >/dev/null 2>&1)
diag="$repo/diag2"
expect_run "retry fills the hole: passes" 0 "app core/ui data/lib"
new_repo
run_in "app data/lib"
(cd "$repo" && PATH="$repo/bin:$PATH" bash "$script" cleanup "$diag" >/dev/null 2>&1)
diag="$repo/diag2"
expect_run "retry still missing: fails, ::error:: names the module" 1 "app data/lib" "wrote no results (the APK did not install?): core/ui "

# A genuine failure is never reclassified by a missing-results file.
new_case
xml '<failure message="expected true">trace</failure>'
echo ':core:ui:connectedDebugAndroidTest' >"$case_dir/diag/missing-results.txt"
expect "genuine failure beats missing-results.txt" 1 "genuine"

new_case
xml '<failure message=""/>'
echo ':core:ui:connectedDebugAndroidTest' >"$case_dir/diag/missing-results.txt"
expect "empty failure plus missing-results.txt is infra" 0 "infra"

new_case
rm -f "$results"
expect "no results and no missing-results.txt keeps the old message" 0 "no test results at all"

if [ "$failures" -ne 0 ]; then
  echo "$failures test(s) failed"
  exit 1
fi
echo "all tests passed"
