#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Runs CI's checks locally, so CI passes the first time. Run it from anywhere in the repo before marking a PR ready:
#   scripts/preflight.sh [--emulator] [--contract]
# It mirrors .github/workflows/ci.yml: the `checks` job (script tests, dependency verification guard, actionlint,
# zizmor, gitleaks, DCO) and the Gradle tasks of the `verify` and `assemble` jobs. Keep the task lists and tool versions in step with it.
# Every step runs even if an earlier one fails, then a PASS/FAIL summary lists them.
#   --emulator  also run the instrumented tests on a local API 25 Android TV emulator (headless), as CI's `emulator`
#               job does. If the SDK lacks the emulator or the system image, it prints the sdkmanager command to
#               install them and exits 1; it installs nothing itself.
#   --contract  also run the container contract suite (`:data:jellyfin:contractTest`) once per Jellyfin image in
#               jellyfin-contract.yml's matrix, as that workflow does. Needs docker; Testcontainers starts its own
#               containers on random ports and removes them. The first run pulls the images. Not part of the default
#               run, because it takes a few minutes per image.
# Needs: JDK 17, the Android SDK, docker, uvx (uv), jq, git. A missing tool fails its step with an install hint.
# Toolchain: JAVA_HOME and ANDROID_HOME are used if set. Otherwise $HOME/.local/share/stationbreak-toolchain/jdk-17
# and /android-sdk are used if they exist.
# Instrumented tests are bounded: PREFLIGHT_INSTRUMENTED_TIMEOUT seconds (default 1200; a normal run takes 3-5
# minutes). On a timeout it saves logcat, the foreground activity, the device's processes and packages, the host's adb
# and Java processes, and thread dumps of the Gradle client and the Android test engine, and fails the step. The logcat
# is kept for every --emulator run, in a per-run directory under
# ${XDG_CACHE_HOME:-$HOME/.cache}/stationbreak/preflight-runs/ (the last 10 are kept); the path is printed at the end.
# Each APK install is bounded too: PREFLIGHT_INSTALL_TIMEOUT seconds (default 120; one normally takes under 10). A
# stalled install is stopped and its evidence saved in an install-stall-* directory of the run (#482). A module with
# tests that wrote no test results (a stopped install, or any other failure to start its tests, which Gradle reports as
# a pass) is run once more. The step fails on a failure in a module that wrote results, or on the re-run's failure, or
# if a re-run module still has no results (scripts/connected-results.sh).
# Exit: 0 all passed, 1 a step failed, 2 usage error.
set -euo pipefail

# The tool versions CI uses (ci.yml, "Install actionlint" and "Install gitleaks"; zizmor's is the one the pinned
# zizmor-action runs). Docker tags here, not the release checksums CI pins.
ACTIONLINT_VERSION=1.7.12
GITLEAKS_VERSION=8.30.1
ZIZMOR_VERSION=1.30.1

emulator=false
contract=false
for arg in "$@"; do
  case "$arg" in
    --emulator) emulator=true ;;
    --contract) contract=true ;;
    *)
      echo "usage: $0 [--emulator] [--contract]" >&2
      exit 2
      ;;
  esac
done

cd "$(git rev-parse --show-toplevel)"

toolchain="$HOME/.local/share/stationbreak-toolchain"
if [ -z "${JAVA_HOME:-}" ] && [ -d "$toolchain/jdk-17" ]; then
  export JAVA_HOME="$toolchain/jdk-17"
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -d "$toolchain/android-sdk" ]; then
  export ANDROID_HOME="$toolchain/android-sdk"
fi
if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

results=()
failed=0

# step <title> <function>: runs the function under a header, records PASS or FAIL, and carries on either way.
step() {
  local title="$1" fn="$2" start status
  printf '\n==== %s ====\n' "$title"
  start=$SECONDS
  # `if` stops errexit from ending the whole script inside the function.
  if "$fn"; then
    status=PASS
  else
    status=FAIL
    failed=1
  fi
  results+=("$(printf '%-4s  %-52s %4ss' "$status" "$title" "$((SECONDS - start))")")
}

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "missing tool: $1. $2" >&2
    return 1
  fi
}

# The base for "what this branch adds", as CI's PR base is.
base=origin/main
have_base() {
  if ! git rev-parse --verify --quiet "$base^{commit}" >/dev/null; then
    echo "no $base. Run 'git fetch origin' first." >&2
    return 1
  fi
}

gradle_env() {
  need java "Install JDK 17 and set JAVA_HOME." || return 1
  if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "$ANDROID_HOME" ]; then
    echo "ANDROID_HOME is not set to an Android SDK. Install the SDK and set ANDROID_HOME." >&2
    return 1
  fi
}

# --- the `checks` job ---

script_tests() {
  bash .github/scripts/dco-check.test.sh
  bash .github/scripts/osv-issues.test.sh
  bash .github/scripts/verification-guard.test.sh
  bash .github/scripts/device-proof-guard.test.sh
  bash .github/scripts/instrumented-infra.test.sh
  bash .github/scripts/release-scope.test.sh
  bash scripts/guard-tree.test.sh
  bash scripts/run-bounded.test.sh
  bash scripts/install-watchdog.test.sh
  bash scripts/connected-results.test.sh
  bash scripts/playback-matrix/playback-matrix.test.sh
}

# The guards walk the tree they are given. On the repo root that includes agent worktrees under .claude/, each a full
# checkout of its own, so they run on a copy of the repo's files without them (scripts/guard-tree.sh).
verification_guard() {
  local tree status=0
  tree="$(mktemp -d)"
  bash scripts/guard-tree.sh "$tree" || status=1
  if [ "$status" -eq 0 ]; then
    bash .github/scripts/verification-guard.sh "$tree" || status=1
    bash .github/scripts/device-proof-guard.sh "$tree" || status=1
  fi
  rm -rf "$tree"
  return "$status"
}

actionlint() {
  need docker "Install docker." || return 1
  docker run --rm -v "$PWD":/repo -w /repo "rhysd/actionlint:$ACTIONLINT_VERSION" -color
}

zizmor() {
  need uvx "Install uv (https://docs.astral.sh/uv/)." || return 1
  # CI fails on high-severity findings only.
  uvx "zizmor@$ZIZMOR_VERSION" --quiet --min-severity high .
}

gitleaks() {
  need docker "Install docker." || return 1
  have_base || return 1
  local config
  config="$(mktemp -d)"
  # The base's config, as in CI, so a branch can't loosen the scan of its own commits.
  if ! git show "$base:.gitleaks.toml" >"$config/gitleaks.toml" 2>/dev/null; then
    printf '[extend]\nuseDefault = true\n' >"$config/gitleaks.toml"
  fi
  # The container's user doesn't own the mounted repo; git refuses it unless told it's safe.
  # shellcheck disable=SC2016
  docker run --rm --entrypoint sh -v "$PWD":/repo:ro -v "$config":/cfg:ro -w /repo \
    "ghcr.io/gitleaks/gitleaks:v$GITLEAKS_VERSION" -c '
      git config --global --add safe.directory /repo
      gitleaks git --config /cfg/gitleaks.toml --log-opts="'"$base"'..HEAD" \
        --ignore-gitleaks-allow --gitleaks-ignore-path /cfg \
        --redact --verbose --no-banner --exit-code 1'
  local rc=$?
  rm -rf "$config"
  return "$rc"
}

dco() {
  need jq "Install jq." || return 1
  have_base || return 1
  local shas
  shas="$(git rev-list "$base..HEAD")"
  if [ -z "$shas" ]; then
    echo "No commits ahead of $base: nothing to check."
    return 0
  fi
  # The shape of the API's commit list, which dco-check.sh reads.
  local sha
  while read -r sha; do
    jq -n --arg sha "$sha" --arg message "$(git log -1 --format=%B "$sha")" '{sha: $sha, commit: {message: $message}}'
  done <<<"$shas" | jq -s . | bash .github/scripts/dco-check.sh
}

# --- the `verify` job (module_rules, kmp_tests, check) and the `assemble` job (same tasks, same order) ---

module_rules() {
  gradle_env || return 1
  ./gradlew checkArchitecture checkOverrideLibrary
}

kmp_tests() {
  gradle_env || return 1
  env -u ANDROID_HOME -u ANDROID_SDK_ROOT ./gradlew :core:model:jvmTest :core:scheduler:jvmTest
}

check_tasks() {
  gradle_env || return 1
  ./gradlew check --continue
}

assemble_tasks() {
  gradle_env || return 1
  # CI does not assemble the benchmark build (never published); building it here keeps it compiling.
  ./gradlew assembleAmazonRelease assembleGithubDebug assembleGithubRelease assembleGithubBenchmark --continue
}

# --- the `jellyfin-contract` workflow ---

# One run of the suite per matrix line, with the image the workflow would use. The lines are read from the workflow, so
# adding or dropping a version there changes this too.
contract_tests() {
  gradle_env || return 1
  need docker "Install docker." || return 1
  local workflow=.github/workflows/jellyfin-contract.yml line tag digest ran=0
  while IFS= read -r line; do
    tag="$(sed -n 's/.*tag: "\([^"]*\)".*/\1/p' <<<"$line")"
    digest="$(sed -n 's/.*digest: "\(sha256:[0-9a-f]*\)".*/\1/p' <<<"$line")"
    if [ -z "$tag" ] || [ -z "$digest" ]; then
      echo "can't read a matrix line in $workflow: $line" >&2
      return 1
    fi
    printf '\n-- Jellyfin %s (%s)\n' "$tag" "$digest"
    JELLYFIN_IMAGE="ghcr.io/jellyfin/jellyfin@$digest" ./gradlew :data:jellyfin:contractTest || return 1
    ran=$((ran + 1))
  done < <(grep -E '^[[:space:]]*- \{ tag: ' "$workflow")
  if [ "$ran" -eq 0 ]; then
    echo "no matrix lines found in $workflow" >&2
    return 1
  fi
}

# --- the `emulator` job ---

# shellcheck source=scripts/run-bounded.sh
source scripts/run-bounded.sh
# shellcheck source=scripts/install-watchdog.sh
source scripts/install-watchdog.sh
# shellcheck source=scripts/connected-results.sh
source scripts/connected-results.sh

emulator_image='system-images;android-25;android-tv;x86'
emulator_port=5580
emulator_lock_fd=""
emulator_pid=""
logcat_pid=""
watchdog_pid=""
avd_home=""
# Per run, outside the repo and the AVD directory: cleanup deletes the AVD, and something removed a repo-root build/
# directory after a run (2026-10), taking the logcat with it.
preflight_out=""

emulator_cleanup() {
  if [ -n "$watchdog_pid" ]; then
    kill "$watchdog_pid" 2>/dev/null || true
    wait "$watchdog_pid" 2>/dev/null || true
  fi
  if [ -n "$logcat_pid" ]; then
    kill "$logcat_pid" 2>/dev/null || true
    wait "$logcat_pid" 2>/dev/null || true
  fi
  if [ -n "$emulator_pid" ]; then
    kill "$emulator_pid" 2>/dev/null || true
    wait "$emulator_pid" 2>/dev/null || true
  fi
  if [ -n "$avd_home" ]; then
    rm -rf "$avd_home"
  fi
}

# Checked before any step runs, so a missing piece doesn't cost a whole build first.
emulator_prereqs() {
  local sdk="${ANDROID_HOME:-}"
  local sdkmanager="$sdk/cmdline-tools/latest/bin/sdkmanager"
  if [ ! -x "$sdk/emulator/emulator" ] || [ ! -d "$sdk/system-images/android-25/android-tv/x86" ]; then
    echo "The SDK at ANDROID_HOME has no emulator or no API 25 Android TV image. Install them with:" >&2
    echo "  $sdkmanager --install 'emulator' '$emulator_image'" >&2
    exit 1
  fi
  if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
    echo "/dev/kvm is not usable by this user; the x86 emulator needs it." >&2
    exit 1
  fi
}

# Saves what a hung instrumented run needs to be understood afterwards, into $preflight_out. Called by run_bounded with
# the Gradle client's pid, before anything is killed. Every adb call is itself bounded: adb stalling is one of the
# things this has to tell apart, and a stalled call here must not hang the diagnosis. Best effort throughout.
save_hang_evidence() {
  local client_pid="$1"
  echo "Saving hang evidence to $preflight_out" >&2
  save_device_state "$preflight_out"
  if command -v jstack >/dev/null 2>&1; then
    timeout 30 jstack "$client_pid" >"$preflight_out/gradle-client-threads.txt" 2>&1 || true
  fi
}

# save_device_state <dir>: the device's and the host's side of a stuck run, as save_hang_evidence describes.
save_device_state() {
  local dir="$1" adb="$ANDROID_HOME/platform-tools/adb" pid
  timeout 20 "$adb" shell dumpsys activity activities 2>&1 | head -n 200 >"$dir/activities.txt" || true
  # API 25 has `ps` with no -A; newer images want it.
  { timeout 20 "$adb" shell ps -A 2>/dev/null || timeout 20 "$adb" shell ps 2>&1; } >"$dir/ps.txt" || true
  timeout 20 "$adb" devices -l >"$dir/adb-devices.txt" 2>&1 || true
  timeout 20 "$adb" shell pm list packages 2>&1 | grep -i stationbreak >"$dir/packages.txt" || true
  # The host side: every adb client with its age in seconds (a stuck `adb install` shows here), and the JVMs.
  ps -u "$(id -u)" -o pid=,ppid=,etimes=,args= | awk '$4 ~ /(^|\/)(adb|java)$/' >"$dir/host-processes.txt" || true
  # The Gradle client only waits on its daemon. The connected task's work is done by the Android test engine, a JVM the
  # daemon starts per task, and it runs the adb commands; its threads show which one it is waiting on.
  if command -v jstack >/dev/null 2>&1; then
    local engines=()
    mapfile -t engines < <(pgrep -u "$(id -u)" -f 'android-test\.adb-path' || true)
    for pid in "${engines[@]}"; do
      echo "=== test engine JVM $pid"
      timeout 30 jstack "$pid" 2>&1 || true
    done >"$dir/test-engine-threads.txt"
  fi
}

# The on-stall command for install_watchdog: the evidence of one stalled install, taken while it is still running.
install_stalled() {
  local pid="$1" dir
  dir="$preflight_out/install-stall-$(date +%H%M%S)-$pid"
  mkdir -p "$dir"
  echo "Saving install-stall evidence to $dir" >&2
  ps -o etimes=,args= -p "$pid" >"$dir/install-command.txt" 2>&1 || true
  save_device_state "$dir"
}

instrumented_tests() {
  gradle_env || return 1
  local sdk="$ANDROID_HOME" assemble connected root build_file dir project
  # The task lists, as ci.yml's "List instrumented test tasks" step builds them.
  assemble="assembleGithubDebug assembleGithubDebugAndroidTest"
  connected="connectedGithubDebugAndroidTest"
  local roots=()
  for root in core data feature; do
    if [ -d "$root" ]; then roots+=("$root"); fi
  done
  while IFS= read -r build_file; do
    dir="$(dirname "$build_file")"
    if [ -d "$dir/src/androidTest" ]; then
      project=":${dir//\//:}"
      assemble+=" $project:assembleDebugAndroidTest"
      connected+=" $project:connectedDebugAndroidTest"
    fi
  done < <(grep -rl --include=build.gradle.kts 'id("stationbreak.android-lib")' "${roots[@]}")

  # shellcheck disable=SC2086
  ./gradlew $assemble

  # One emulator at a time on this machine: runs from other worktrees wait here for their turn. The lock is held
  # until this script exits. A local emulator is heavy, and the host may be serving other things.
  local lock_dir="${XDG_CACHE_HOME:-$HOME/.cache}/stationbreak"
  mkdir -p "$lock_dir"
  exec {emulator_lock_fd}>"$lock_dir/emulator.lock"
  if ! flock -n "$emulator_lock_fd"; then
    echo "Another preflight is using the emulator; waiting for it to finish..."
    flock "$emulator_lock_fd"
  fi
  # A run started outside this lock (an older copy of this script) may still hold the port.
  local waited_port=0
  while ss -ltnH "( sport = :$emulator_port or sport = :$((emulator_port + 1)) )" 2>/dev/null | grep -q .; do
    if [ "$waited_port" -ge 1800 ]; then
      echo "Port $emulator_port is still in use after 30 minutes." >&2
      return 1
    fi
    sleep 10
    waited_port=$((waited_port + 10))
  done

  # On disk, not in TMPDIR: /tmp is often RAM-backed, and the AVD's data partition is several GB.
  avd_home="$(mktemp -d "$lock_dir/avd.XXXXXX")"
  export ANDROID_AVD_HOME="$avd_home"
  echo no | "$sdk/cmdline-tools/latest/bin/avdmanager" create avd --force -n preflight -k "$emulator_image" -d tv_1080p
  nice -n 10 "$sdk/emulator/emulator" -avd preflight -port "$emulator_port" -no-snapshot -no-window -no-audio -no-boot-anim \
    -gpu swiftshader_indirect -camera-back none >"$avd_home/emulator.log" 2>&1 &
  emulator_pid=$!

  # Pinned to this emulator, so a device on adb (a Fire TV, say) is never touched.
  export ANDROID_SERIAL="emulator-$emulator_port"
  local adb="$sdk/platform-tools/adb" waited=0
  "$adb" wait-for-device
  until [ "$("$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
    if ! kill -0 "$emulator_pid" 2>/dev/null; then
      echo "The emulator exited during boot:" >&2
      tail -n 20 "$avd_home/emulator.log" >&2
      return 1
    fi
    if [ "$waited" -ge 300 ]; then
      echo "The emulator did not finish booting in 300 s." >&2
      return 1
    fi
    sleep 5
    waited=$((waited + 5))
  done
  for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$adb" shell settings put global "$setting" 0
  done

  # Kept for every run, so a hang leaves evidence of which test stalled. Cleared first so it holds only this run.
  local runs_dir="$lock_dir/preflight-runs" old
  preflight_out="$runs_dir/$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$preflight_out"
  # Keep the last 10 runs (names sort by time), so this doesn't grow without end.
  while IFS= read -r old; do
    rm -rf "${runs_dir:?}/$old"
  done < <(ls -1 "$runs_dir" | sort | head -n -10)
  "$adb" logcat -c
  "$adb" logcat -v threadtime >"$preflight_out/logcat.txt" 2>&1 &
  logcat_pid=$!

  # One module at a time: gradle.properties turns on org.gradle.parallel, and when several modules' connected tasks ran
  # together on the one emulator, one of them never reported and the run hung for its full hour (2026-10).
  # Bounded, because hangs here have run to the outer hour (most recently :feature:onboarding). The limit is well over a
  # normal 3-5 minute run.
  local limit="${PREFLIGHT_INSTRUMENTED_TIMEOUT:-1200}" install_limit="${PREFLIGHT_INSTALL_TIMEOUT:-120}" status=0
  case "$limit" in
    '' | *[!0-9]*)
      echo "PREFLIGHT_INSTRUMENTED_TIMEOUT must be a number of seconds, not '$limit'." >&2
      return 1
      ;;
  esac
  case "$install_limit" in
    '' | *[!0-9]*)
      echo "PREFLIGHT_INSTALL_TIMEOUT must be a number of seconds, not '$install_limit'." >&2
      return 1
      ;;
  esac
  local tasks=() retry=() still=() since first_log retry_status=0 pkgs=() pkg tee_pid tee_fd
  read -ra tasks <<<"$connected"
  timeout 20 "$adb" devices -l >"$preflight_out/adb-devices-start.txt" 2>&1 || true

  # Each APK install bounded (#482): the connected tasks install with `adb -s <serial> install` and wait on it with no
  # limit, and here that install has sometimes never finished. See scripts/install-watchdog.sh. $$ is this script: the
  # watchdog ends with it even if this script is killed before its cleanup runs.
  install_watchdog "$ANDROID_SERIAL" "$install_limit" install_stalled "$$" &
  watchdog_pid=$!

  # --continue: every module runs even if one fails, so the results check below sees them all. The output is kept for
  # connected_status, which reads the failed tasks from it.
  since="$preflight_out/attempt1.start"
  first_log="$preflight_out/attempt1-gradle.log"
  : >"$since"
  # tee on its own descriptor, so its pid is known and the log is complete before it is read.
  exec {tee_fd}> >(tee "$first_log")
  tee_pid=$!
  run_bounded "$limit" save_hang_evidence ./gradlew --no-parallel --continue "${tasks[@]}" >&"$tee_fd" 2>&1 ||
    status=$?
  exec {tee_fd}>&-
  # Bounded: a leftover descendant of Gradle still holding the pipe would keep tee from ending.
  for _ in $(seq 30); do
    kill -0 "$tee_pid" 2>/dev/null || break
    sleep 1
  done
  kill "$tee_pid" 2>/dev/null || true
  wait "$tee_pid" 2>/dev/null || true
  # A module whose install was stopped or lost wrote no results, and Gradle still passed it (scripts/connected-results.sh).
  # Each is run once more; connected_status then combines the two attempts.
  if [ "$status" -ne 124 ]; then
    mapfile -t retry < <(tasks_without_results "$since" "${tasks[@]}")
  fi
  if [ "${#retry[@]}" -gt 0 ]; then
    echo "No test results from: ${retry[*]}. Their tests never ran (a stopped install, or the test runner failed" >&2
    echo "to start, which Gradle reports as a pass). Running them once more." >&2
    # A stopped install can still leave its package on the device, and the next `adb install` (it has no -r) then fails
    # with INSTALL_FAILED_ALREADY_EXISTS. The emulator is this run's own, so every StationBreak package on it is ours.
    mapfile -t pkgs < <(timeout 20 "$adb" -s "$ANDROID_SERIAL" shell pm list packages 2>/dev/null | tr -d '\r' |
      sed -n 's/^package:\(tv\.stationbreak\..*\)$/\1/p')
    for pkg in "${pkgs[@]}"; do
      timeout 60 "$adb" -s "$ANDROID_SERIAL" uninstall "$pkg" >/dev/null 2>&1 || true
    done
    since="$preflight_out/attempt2.start"
    : >"$since"
    run_bounded "$limit" save_hang_evidence ./gradlew --no-parallel --continue "${retry[@]}" || retry_status=$?
    if [ "$retry_status" -ne 124 ]; then
      mapfile -t still < <(tasks_without_results "$since" "${retry[@]}")
      if [ "${#still[@]}" -gt 0 ]; then
        echo "Still no test results from: ${still[*]}." >&2
        if [ "$retry_status" -eq 0 ]; then retry_status=1; fi
      fi
    fi
  fi
  status="$(connected_status "$status" "$first_log" "$retry_status" "${retry[@]}")"
  kill "$watchdog_pid" 2>/dev/null || true
  wait "$watchdog_pid" 2>/dev/null || true
  watchdog_pid=""

  if compgen -G "$preflight_out/install-stall-*" >/dev/null; then
    echo "An install stalled and was stopped; its evidence is in $preflight_out/install-stall-*" >&2
  fi
  if [ "$status" -eq 124 ]; then
    echo "The instrumented tests hung: no result after ${limit}s. Evidence (logcat, device and host state, thread dumps) is in:" >&2
    echo "  $preflight_out" >&2
    echo "The Gradle client was stopped and the emulator is shut down next. Gradle's daemon is asked to cancel the build but" >&2
    echo "may stay busy a while if a task is stuck in adb; it was not stopped, as that would stop other builds too." >&2
  else
    echo "Instrumented run evidence (logcat): $preflight_out"
  fi
  return "$status"
}

trap emulator_cleanup EXIT

if [ "$emulator" = true ]; then
  emulator_prereqs
fi

step "Script tests" script_tests
step "Verification and device-proof guards" verification_guard
step "actionlint $ACTIONLINT_VERSION" actionlint
step "zizmor $ZIZMOR_VERSION" zizmor
step "gitleaks $GITLEAKS_VERSION ($base..HEAD)" gitleaks
step "Sign-offs (DCO, $base..HEAD)" dco
step "Module dependency rules" module_rules
step "KMP tests with no Android SDK" kmp_tests
step "Check (CI: verify)" check_tasks
step "Assemble (CI: assemble)" assemble_tasks
if [ "$contract" = true ]; then
  step "Container contract tests (docker)" contract_tests
fi
if [ "$emulator" = true ]; then
  step "Instrumented tests (API 25 Android TV)" instrumented_tests
fi

printf '\n==== Summary ====\n'
printf '%s\n' "${results[@]}"
if [ "$failed" -eq 0 ]; then
  echo "PASS: ready to mark the PR ready for review."
else
  echo "FAIL: fix the steps above before pushing."
  exit 1
fi
