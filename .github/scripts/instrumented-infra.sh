#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Tells an emulator or runner failure from a real test failure, so CI's `emulator` job can retry the first and never
# the second. Five modes:
#
#   instrumented-infra.sh prepare <dir>
#       Calms the emulator before the tests: closes system dialogs, disables the crashing config updater, and gives
#       logcat a 16M buffer with chatty pruning off, so a failure's window is still in the log (#444, #462). It
#       prints the buffer sizes and prune list it ended up with. `run` calls it first, so both attempts get it.
#       <dir> is unused. Every adb call is bounded by `timeout 20` and tolerates failure: it never fails the job.
#
#   instrumented-infra.sh run <dir> [final]
#       Runs `prepare`, then `./gradlew $CONNECTED_TASKS` (the action's script step). If Gradle passes it runs `verify`,
#       and its exit status is the step's. `final` (the retry) makes a missing result an ::error::, not a ::warning::. On failure it saves what classify needs into <dir>: logcat.txt (also copied to app/build/logcat.txt, where the reports upload has
#       always looked), adb-state.txt (`adb get-state`) and emulator-state.txt (alive | gone: is a qemu process
#       still running).
#
#   instrumented-infra.sh verify <dir> [final]
#       Run from the repo root after a Gradle run that passed (`run` calls it). AGP 9.4.1's test engine counts a failed or
#       killed APK install as a pass: Gradle succeeds, the module writes no JUnit XML (#489, #482). So for every task in
#       $CONNECTED_TASKS whose module has a real @Test under src/androidTest, it requires a TEST-*.xml newer than
#       <dir>/started (the marker `run` makes just before Gradle, so a file left from an earlier attempt does not count).
#       The rule is tasks_without_results in connected-results.sh next to this script, sourced so preflight.sh and CI
#       cannot drift. If any module has none it writes their task names to <dir>/missing-results.txt, prints a
#       `::warning::` naming them (an `::error::` if the second argument is `final`) and exits 1; else exit 0. It exits 2
#       if connected-results.sh or its functions cannot be loaded, or <dir>/started is missing. A module with no real tests is never expected to write any.
#
#   instrumented-infra.sh classify <dir> [results-root]
#       Exit 0: infrastructure failure, retry. Exit 1: not one, fail the job. Prints the reason either way.
#       results-root (default .) is searched for the JUnit XML, **/build/outputs/androidTest-results/connected/**/TEST-*.xml.
#       Only the first rule that matches applies:
#         1. A <failure> or <error> with a message or a body is a genuine test failure: never retried, even if the
#            logcat also looks bad, because a failed assertion is the result to report. Two known emulator
#            signatures are the exception and count as infrastructure:
#              a. `java.lang.SecurityException: Injecting to another application requires INJECT_EVENTS permission`:
#                 a system dialog took focus from the app, so the test's input was refused (#444).
#              b. `java.lang.IllegalStateException: No compose hierarchies found in the app`: the activity was not up
#                 when the test looked. A real bug (the activity crashing on start) looks the same, and the diag
#                 logcat cannot tell the two apart reliably (the default buffers carry no per-test "resumed" marker),
#                 so (b) counts only when EVERY failure in the run is (b). Any other failure alongside it, even an
#                 empty one, makes the run genuine.
#            Anchored match: the failure's head is its message attribute if it has one, else its body (AGP's
#            connected-test XML usually has no message; the body starts with the exception class). After optional
#            leading whitespace the head must BEGIN with the exact text above, and a type="..." attribute, if
#            present, must name the same class. A failure that merely quotes the phrase later on its first line, or
#            in a `Caused by:` line, stays genuine, as does any ComposeTimeoutException or AssertionError.
#         2. A <failure> or <error> that is empty (no message, no body): the process died under the test. Retry.
#         3. The logcat shows a surfaceflinger or system_server crash. Retry.
#         4. The emulator process is gone, or adb no longer sees a device (the files `run` saved). Retry.
#         5. The step was cut off (its timeout) with a module still to report: `run` left its `started`
#            marker but was killed before it saved emulator-state.txt, no result file has a failure, and a module in
#            $CONNECTED_TASKS wrote no results (module :a:b writes under a/b/, the app under app/). Retry.
#         5b. <dir>/missing-results.txt exists (written by `verify`: Gradle passed but a module wrote no results, a
#            lost APK install). Retry. After the rules above, so a real failure is never reclassified by it.
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
  echo "usage: $0 prepare|run|verify|classify|cleanup <dir> [results-root]" >&2
  exit 2
fi

result_files() {
  local root="$1"
  find "$root" -path '*/build/outputs/androidTest-results/connected/*' -name 'TEST-*.xml' -type f 2>/dev/null
}

case "$mode" in
  prepare)
    # Best effort on a throwaway AVD: a missing package, a refused size or a hung adb must not fail the job, so each
    # call is bounded by `timeout 20` and ends in `|| true`.
    timeout 20 adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
    # The updater crashes on the CI image and its crash dialog steals focus. "Unknown package" is fine.
    timeout 20 adb shell pm disable-user --user 0 com.google.android.configupdater >/dev/null 2>&1 || true
    # 16M first, 8M if the emulator refuses it.
    timeout 20 adb logcat -G 16M >/dev/null 2>&1 || timeout 20 adb logcat -G 8M >/dev/null 2>&1 || true
    # Chatty pruning off (the "expire N lines" that ate the log in #462). The empty argument is quoted inside ONE
    # string for the device shell: `adb logcat -P ''` can lose the empty argument on the way (adb joins its arguments
    # into a command line), but `adb shell "logcat -P ''"` reaches the device's sh with the quotes intact.
    timeout 20 adb shell "logcat -P ''" >/dev/null 2>&1 || true
    # Say what took effect, so the job log shows whether -G and -P work on API 25.
    echo "prepare: logcat buffers: $(timeout 20 adb logcat -g 2>&1 | tr '\n' ' ')"
    echo "prepare: logcat prune list: $(timeout 20 adb shell logcat -p 2>&1 | tr '\n' ' ')"
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
      set +f
      # A pass proves nothing if a module's APK never installed (#489): look for the results.
      # If a module has none, fall through to the failure handling below (logcat and state saved, exit 1).
      if bash "${BASH_SOURCE[0]}" verify "$dir" "${3:-}"; then exit 0; fi
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

  verify)
    # Fail closed: a verify that cannot load its rule must not pass.
    # shellcheck source=connected-results.sh
    . "$(dirname "${BASH_SOURCE[0]}")/connected-results.sh" || { echo "::error::cannot load connected-results.sh"; exit 2; }
    declare -F tasks_without_results >/dev/null || { echo "::error::connected-results.sh lacks tasks_without_results"; exit 2; }
    declare -F connected_module >/dev/null || { echo "::error::connected-results.sh lacks connected_module"; exit 2; }
    since="$dir/started"
    if [ ! -f "$since" ]; then
      echo "verify: $since is missing (run makes it); cannot tell fresh results from stale" >&2
      exit 2
    fi
    set -f
    read -ra tasks <<<"${CONNECTED_TASKS:?CONNECTED_TASKS is not set}"
    set +f
    rm -f "$dir/missing-results.txt"
    missing=()
    while IFS= read -r task; do
      [ -n "$task" ] && missing+=("$task")
    done < <(tasks_without_results "$since" "${tasks[@]}")
    if [ "${#missing[@]}" -gt 0 ]; then
      printf '%s\n' "${missing[@]}" >"$dir/missing-results.txt"
      modules=()
      for task in "${missing[@]}"; do modules+=("$(connected_module "$task")"); done
      # Attempt 1's miss is retried, so it is a warning; the final attempt's miss fails the job, so it is an error.
      level=warning
      if [ "${3:-}" = final ]; then level=error; fi
      echo "::${level}::Gradle passed but these modules' instrumented tests wrote no results (the APK did not install?): ${modules[*]} (tasks: ${missing[*]})"
      exit 1
    fi
    echo "verify: every module with instrumented tests wrote results"
    exit 0
    ;;

  classify)
    root="${3:-.}"
    genuine=0
    empty=0
    sig_inject=0
    sig_nohier=0
    sig_inject_class="java.lang.SecurityException"
    sig_inject_text="$sig_inject_class: Injecting to another application requires INJECT_EVENTS"
    sig_nohier_class="java.lang.IllegalStateException"
    sig_nohier_text="$sig_nohier_class: No compose hierarchies found in the app"
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
          # Anchored: the head must BEGIN with the exception (after whitespace), and a type="" attribute, if there is
          # one, must name the same class. A phrase quoted later on the line or in a Caused by is not a match.
          type=""
          if [[ "$attrs" =~ type=\"([^\"]*)\" ]]; then type="${BASH_REMATCH[1]}"; fi
          head="${head#"${head%%[![:space:]]*}"}"
          if [[ "$head" == "$sig_inject_text"* ]] && [[ -z "$type" || "$type" == "$sig_inject_class" ]]; then
            sig_inject=$((sig_inject + 1))
          elif [[ "$head" == "$sig_nohier_text"* ]] && [[ -z "$type" || "$type" == "$sig_nohier_class" ]]; then
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
    # Gradle passed but a module wrote no results (verify): its APK did not install.
    if [ -s "$dir/missing-results.txt" ]; then
      echo "infra: missing results for $(paste -sd' ' "$dir/missing-results.txt") (Gradle passed; the APK install failed or was killed)"
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
    echo "usage: $0 prepare|run|verify|classify|cleanup <dir> [results-root]" >&2
    exit 2
    ;;
esac
