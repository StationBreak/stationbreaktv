#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Sourced by scripts/preflight.sh and .github/scripts/instrumented-infra.sh (and the tests); defines the two rules they
# use to judge a run of the connected (instrumented) test tasks. It lives under .github/ so the CI gate's logic stays
# inside the reviewed path (ADR-0012). Runs nothing itself. Paths are relative to the repo root (the current directory).
#
# Why (#482): when a module's APK does not install, AGP 9.4.1's Android test engine prints "AndroidTestRunner failed"
# and the task still passes. Its test-result-exit-code.txt says 0, it writes no JUnit XML, and Gradle exits 0. Both a
# stopped install (preflight's install watchdog) and a lost one showed this (2026-10-29). A missing result file is the
# only sign, so preflight looks for one per module, re-runs the modules that have none once, and judges the run with
# connected_status.

# connected_module <task>: the module directory a connected task's results are written under. The app's task has no
# project path and writes under app/. :a:b's writes under a/b/.
connected_module() {
  local task="$1" module
  case "$task" in
    :*:connected*)
      module="${task%:*}"
      module="${module#:}"
      echo "${module//://}"
      ;;
    *) echo app ;;
  esac
}

# tasks_without_results <since-file> <task...>: prints each task whose module has at least one real test under a
# src/androidTest* source set but wrote no fresh result: a TEST-*.xml under build/outputs/androidTest-results/connected/
# made after <since-file> that holds at least one <testcase> (an empty suite counts as no result). A module with no
# tests is never expected to write any, so it is never listed. A name that is not a Gradle task path is skipped with a
# note on stderr, as instrumented-infra.sh classify does.
tasks_without_results() {
  local since="$1" task module dir has_tests
  shift
  for task in "$@"; do
    if ! [[ "$task" =~ ^:?[A-Za-z0-9_-]+(:[A-Za-z0-9_-]+)*$ ]]; then
      echo "skipping task name that is not a task path: $task" >&2
      continue
    fi
    module="$(connected_module "$task")"
    # The @Test annotation itself (or @org.junit.Test), not @TestRule or @TestInstallIn.
    has_tests=1
    for dir in "$module"/src/androidTest*; do
      if [ -d "$dir" ] && grep -rqsE '@(org\.junit\.)?Test([^A-Za-z0-9_.]|$)' "$dir"; then
        has_tests=0
        break
      fi
    done
    if [ "$has_tests" -ne 0 ]; then
      continue
    fi
    if ! find "$module/build/outputs/androidTest-results/connected" -name 'TEST-*.xml' -newer "$since" \
      -exec grep -l '<testcase' {} + 2>/dev/null | grep -q .; then
      echo "$task"
    fi
  done
}

# connected_status <first-status> <first-log> <rerun-status> [<rerun-task>...]: prints the run's final status.
#   <first-status>, <first-log>: the first attempt's exit status and its Gradle output.
#   <rerun-status>: the re-run's status (pass 1 if a re-run module still wrote no results), ignored if no task re-ran.
#   <rerun-task>...: the tasks that were re-run, the ones the first attempt left with no results.
# The rule:
#   - 124 (a timeout) in the first attempt is final; nothing re-ran.
#   - A failed task in the first attempt that wrote results is kept: a re-run of other modules never hides it. A failed
#     task that was re-run is judged by the re-run instead. A failing first attempt that names no failed task (a build
#     or configuration error, say) is kept too.
#   - If anything re-ran, its status counts as well.
connected_status() {
  local first="$1" log="$2" rerun_status="$3" failed task rerun_task kept=0
  shift 3
  if [ "$first" -eq 124 ]; then
    echo 124
    return
  fi
  if [ "$first" -ne 0 ]; then
    kept=1
    while IFS= read -r failed; do
      kept=0
      [ -n "$failed" ] || continue
      for rerun_task in "$@"; do
        # The app's task is listed unqualified but fails as :app:<task>.
        case "$rerun_task" in :*) task="$rerun_task" ;; *) task=":app:$rerun_task" ;; esac
        if [ "$failed" = "$task" ]; then continue 2; fi
      done
      echo "$first"
      return
    done < <(sed -n "s/.*Execution failed for task '\\([^']*\\)'.*/\\1/p" "$log" 2>/dev/null)
    if [ "$kept" -eq 1 ]; then
      echo "$first"
      return
    fi
  fi
  if [ "$#" -gt 0 ] && [ "$rerun_status" -ne 0 ]; then
    echo "$rerun_status"
    return
  fi
  echo 0
}
