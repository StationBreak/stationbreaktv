#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Tells an emulator or runner failure from a real test failure, so CI's `emulator` job can retry the first and never
# the second. Four modes:
#
#   instrumented-infra.sh prepare <dir>
#       Calms the emulator before the tests: closes system dialogs, disables the crashing config updater, and gives
#       logcat a 16M buffer with chatty pruning off, so a failure's window is still in the log (#444, #462).
#       `run` calls it first, so both attempts get it. <dir> is unused. Every step tolerates failure: it never
#       fails the job.
#
#   instrumented-infra.sh run <dir>
#       Runs `prepare`, then `./gradlew $CONNECTED_TASKS` (the action's script step). On failure it saves what classify needs into
#       <dir>: logcat.txt (also copied to app/build/logcat.txt, where the reports upload has always looked),
#       adb-state.txt (`adb get-state`) and emulator-state.txt (alive | gone: is a qemu process still running).
#
#   instrumented-infra.sh classify <dir> [results-root]
#       Exit 0: infrastructure failure, retry. Exit 1: not one, fail the job. Prints the reason either way.
#       results-root (default .) is searched for the JUnit XML, **/build/outputs/androidTest-results/connected/**/TEST-*.xml.
#       Only the first rule that matches applies:
#         1. A <failure> or <error> with a message or a body is a genuine test failure: never retried, even if the
#            logcat also looks bad, because a failed assertion is the result to report. Two known emulator
#            signatures are the exception and count as infrastructure:
#              a. `SecurityException: Injecting to another application requires INJECT_EVENTS permission`: a system
#                 dialog took focus from the app, so the test's input was refused (#444).
#              b. `IllegalStateException: No compose hierarchies found in the app`: the activity was not up when the test looked. A real bug
#                 (the activity crashing on start) looks the same, and the diag logcat cannot tell the two apart
#                 reliably (the default buffers carry no per-test "resumed" marker), so (b) counts only when EVERY
#                 failure in the run is (b). Any other failure alongside it makes the run genuine.
#            Matched on the failure's message (with no message, the start of its body), never deeper in a stack, so
#            an assertion that merely mentions these words is still genuine.
#         2. A <failure> or <error> that is empty (no message, no body): the process died under the test. Retry.
#         3. The logcat shows a surfaceflinger or system_server crash. Retry.
#         4. The emulator process is gone, or adb no longer sees a device (the files `run` saved). Retry.
#         5. The step was cut off (its timeout) with a module still to report: `run` left its `started`
#            marker but was killed before it saved emulator-state.txt, no result file has a failure, and a module in
#            $CONNECTED_TASKS wrote no results (module :a:b writes under a/b/, the app under app/). Retry.
#         6. No result file at all: the emulator never booted, or the step was cut off (a step timeout kills `run`
#            before it can save anything, so <dir> may be empty). Retry.
#         7. Anything else: not infrastructure.
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
  echo "usage: $0 prepare|run|classify|cleanup <dir> [results-root]" >&2
  exit 2
fi

result_files() {
  local root="$1"
  find "$root" -path '*/build/outputs/androidTest-results/connected/*' -name 'TEST-*.xml' -type f 2>/dev/null
}

case "$mode" in
  prepare)
    # Best effort on a throwaway AVD: a missing package or an adb that rejects a size must not fail the job.
    adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
    # The updater crashes on the CI image and its crash dialog steals focus. "Unknown package" is fine.
    adb shell pm disable-user --user 0 com.google.android.configupdater >/dev/null 2>&1 || true
    # 16M first, 8M if the emulator refuses it. -P '' turns off chatty pruning (the "expire N lines" that ate the
    # log in #462).
    adb logcat -G 16M >/dev/null 2>&1 || adb logcat -G 8M >/dev/null 2>&1 || true
    adb logcat -P '' >/dev/null 2>&1 || true
    exit 0
    ;;

  run)
    mkdir -p "$dir"
    bash "${BASH_SOURCE[0]}" prepare "$dir" || true
    # Removed by nothing: if the step is killed, this is all that is left, and the missing emulator-state.txt below
    # tells classify the run never reached its failure handling.
    : >"$dir/started"
    # Split on spaces, never globbed.
    set -f
    if ./gradlew ${CONNECTED_TASKS:?CONNECTED_TASKS is not set}; then
      exit 0
    fi
    set +f
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
    sig_inject=0
    sig_nohier=0
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
          # What the failure says: its message, or with none the start of its body.
          head="$message"
          if [ -z "${head//[[:space:]]/}" ]; then head="${body:0:300}"; fi
          if [[ "$head" == *"SecurityException: Injecting to another application requires INJECT_EVENTS permission"* ]]; then
            sig_inject=$((sig_inject + 1))
          elif [[ "$head" == *"IllegalStateException: No compose hierarchies found in the app"* ]]; then
            sig_nohier=$((sig_nohier + 1))
          else
            genuine=$((genuine + 1))
          fi
        else
          empty=$((empty + 1))
        fi
      done
    done

    # (b) is ambiguous on its own (see the header): it counts only when nothing else failed.
    if [ "$sig_nohier" -gt 0 ] && { [ "$genuine" -gt 0 ] || [ "$sig_inject" -gt 0 ] || [ "$empty" -gt 0 ]; }; then
      genuine=$((genuine + sig_nohier))
      sig_nohier=0
    fi
    if [ "$genuine" -gt 0 ]; then
      echo "genuine: $genuine test failure(s) with a message; not retrying"
      exit 1
    fi
    if [ "$sig_inject" -gt 0 ]; then
      echo "infra: $sig_inject test failure(s) refused input injection (INJECT_EVENTS): a system dialog took focus"
      exit 0
    fi
    if [ "$sig_nohier" -gt 0 ]; then
      echo "infra: $sig_nohier test failure(s), all 'No compose hierarchies found' (the sole signature): the activity was not up"
      exit 0
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
    # Cut off: started, never got to save its state. Needs CONNECTED_TASKS to know which modules should have reported.
    if [ -f "$dir/started" ] && [ ! -f "$dir/emulator-state.txt" ] && [ -n "${CONNECTED_TASKS:-}" ]; then
      missing=()
      read -ra tasks <<<"$CONNECTED_TASKS"
      for task in "${tasks[@]}"; do
        if ! [[ "$task" =~ ^:?[A-Za-z0-9_-]+(:[A-Za-z0-9_-]+)*$ ]]; then
          echo "skipping CONNECTED_TASKS entry that is not a task path: $task" >&2
          continue
        fi
        case "$task" in
          :*:connected*) module="${task%:*}"; module="${module#:}"; module="${module//://}" ;;
          *) module="app" ;;
        esac
        if ! result_files "$root/$module" | grep -q .; then missing+=("$module"); fi
      done
      if [ "${#missing[@]}" -gt 0 ]; then
        echo "infra: the step was cut off (timeout) with no results from: ${missing[*]}"
        exit 0
      fi
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
    echo "usage: $0 prepare|run|classify|cleanup <dir> [results-root]" >&2
    exit 2
    ;;
esac
