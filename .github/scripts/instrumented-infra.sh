#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Tells an emulator or runner failure from a real test failure, so CI's `emulator` job can retry the first and never
# the second. Three modes:
#
#   instrumented-infra.sh run <dir>
#       Runs `./gradlew $CONNECTED_TASKS` (the action's script step). On failure it saves what classify needs into
#       <dir>: logcat.txt (also copied to app/build/logcat.txt, where the reports upload has always looked),
#       adb-state.txt (`adb get-state`) and emulator-state.txt (alive | gone: is a qemu process still running).
#
#   instrumented-infra.sh classify <dir> [results-root]
#       Exit 0: infrastructure failure, retry. Exit 1: not one, fail the job. Prints the reason either way.
#       results-root (default .) is searched for the JUnit XML, **/build/outputs/androidTest-results/connected/**/TEST-*.xml.
#       Only the first rule that matches applies:
#         1. A <failure> or <error> with a message or a body is a genuine test failure: never retried, even if the
#            logcat also looks bad, because a failed assertion is the result to report.
#         2. A <failure> or <error> that is empty (no message, no body): the process died under the test. Retry.
#         3. The logcat shows a surfaceflinger or system_server crash. Retry.
#         4. The emulator process is gone, or adb no longer sees a device (the files `run` saved). Retry.
#         5. No result file at all: the emulator never booted, or the step was cut off (a step timeout kills `run`
#            before it can save anything, so <dir> may be empty). Retry.
#         6. Anything else: not infrastructure.
#
#   instrumented-infra.sh cleanup <dir>
#       Between attempts: moves the attempt's JUnit XML and reports into <dir>/results (so the reports upload still has
#       them and the next attempt starts clean), then kills any emulator and adb left running.
#
# Usage in ci.yml: see the `emulator` job. Tests: instrumented-infra.test.sh.
set -uo pipefail

mode="${1:-}"
dir="${2:-}"
if [ -z "$mode" ] || [ -z "$dir" ]; then
  echo "usage: $0 run|classify|cleanup <dir> [results-root]" >&2
  exit 2
fi

result_files() {
  local root="$1"
  find "$root" -path '*/build/outputs/androidTest-results/connected/*' -name 'TEST-*.xml' -type f 2>/dev/null
}

case "$mode" in
  run)
    mkdir -p "$dir"
    if ./gradlew ${CONNECTED_TASKS:?CONNECTED_TASKS is not set}; then
      exit 0
    fi
    adb logcat -d >"$dir/logcat.txt" 2>&1 || true
    mkdir -p app/build
    cp "$dir/logcat.txt" app/build/logcat.txt 2>/dev/null || true
    adb get-state >"$dir/adb-state.txt" 2>&1 || true
    if pgrep -f 'qemu-system' >/dev/null 2>&1; then
      echo alive >"$dir/emulator-state.txt"
    else
      echo gone >"$dir/emulator-state.txt"
    fi
    exit 1
    ;;

  classify)
    root="${3:-.}"
    genuine=0
    empty=0
    files=()
    while IFS= read -r f; do files+=("$f"); done < <(result_files "$root")

    for f in "${files[@]}"; do
      # One line, so a tag and its body can be read together.
      rest="$(tr '\r\n' '  ' <"$f")"
      while [[ "$rest" =~ \<(failure|error)([^\>]*)\> ]]; do
        tag="${BASH_REMATCH[1]}"
        attrs="${BASH_REMATCH[2]}"
        rest="${rest#*"${BASH_REMATCH[0]}"}"
        message=""
        if [[ "$attrs" =~ message=\"([^\"]*)\" ]]; then message="${BASH_REMATCH[1]}"; fi
        body=""
        if [[ "$attrs" != */ ]]; then body="${rest%%"</$tag>"*}"; fi
        # Blank if only whitespace.
        if [ -n "${message//[[:space:]]/}" ] || [ -n "${body//[[:space:]]/}" ]; then
          genuine=$((genuine + 1))
        else
          empty=$((empty + 1))
        fi
      done
    done

    if [ "$genuine" -gt 0 ]; then
      echo "genuine: $genuine test failure(s) with a message; not retrying"
      exit 1
    fi
    if [ "$empty" -gt 0 ]; then
      echo "infra: $empty test failure(s) with an empty <failure>: the process died under the test"
      exit 0
    fi
    if [ -f "$dir/logcat.txt" ] \
      && grep -qE 'Fatal signal [0-9]+ \(SIG[A-Z]+\).*(surfaceflinger|system_server)|FATAL EXCEPTION IN SYSTEM PROCESS|System server process [0-9]+ has died|Process system_server .*has died' "$dir/logcat.txt"; then
      echo "infra: the logcat shows a surfaceflinger or system_server crash"
      exit 0
    fi
    if [ -f "$dir/emulator-state.txt" ] && grep -qx gone "$dir/emulator-state.txt"; then
      echo "infra: the emulator process died"
      exit 0
    fi
    if [ -f "$dir/adb-state.txt" ] && ! grep -qx device "$dir/adb-state.txt"; then
      echo "infra: adb no longer sees a device ($(tr '\n' ' ' <"$dir/adb-state.txt"))"
      exit 0
    fi
    if [ "${#files[@]}" -eq 0 ]; then
      echo "infra: no test results at all (the emulator did not boot, or the step timed out)"
      exit 0
    fi
    echo "genuine: failed with results and no sign of an infrastructure fault; not retrying"
    exit 1
    ;;

  cleanup)
    mkdir -p "$dir/results"
    while IFS= read -r f; do
      # Keep each module's results apart: the path is flattened into the name.
      cp "$f" "$dir/results/$(tr '/' '_' <<<"${f#./}")" 2>/dev/null || true
    done < <(result_files .)
    find . -path '*/build/outputs/androidTest-results' -prune -exec rm -rf {} + 2>/dev/null || true
    find . -path '*/build/reports/androidTests' -prune -exec rm -rf {} + 2>/dev/null || true
    adb emu kill >/dev/null 2>&1 || true
    adb kill-server >/dev/null 2>&1 || true
    pkill -9 -f 'qemu-system' || true
    pkill -9 -f 'emulator/emulator' || true
    pkill -9 -x adb || true
    exit 0
    ;;

  *)
    echo "usage: $0 run|classify|cleanup <dir> [results-root]" >&2
    exit 2
    ;;
esac
