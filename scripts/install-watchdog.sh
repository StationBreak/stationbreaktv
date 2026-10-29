#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Sourced by scripts/preflight.sh (and its test); defines install_watchdog and runs nothing itself.
#
# install_watchdog <serial> <seconds> <on-stall-command> <parent-pid>: runs until it is killed or <parent-pid> is gone.
# Every INSTALL_WATCHDOG_POLL seconds (default 5) it looks for this user's `adb -s <serial> install ...` processes that
# have run for <seconds> or more. For each one it runs `<on-stall-command> <pid>` while the process is STILL ALIVE, then
# stops it: SIGTERM, and SIGKILL if it is still there on the next look. Ending with its parent matters: a parent killed
# with SIGKILL never runs its cleanup, and a watchdog left behind would stop the next run's installs on the same serial.
#
# Why (#482): Gradle's connected test tasks (AGP 9.4's Android test engine) install each APK by running
# `adb -s <serial> install ...` and wait for it with no time limit. AGP's installApkTimeout option does not reach the
# engine: the engine reads a key AGP never sets, so even a 1 s value lets a slow install run on. On this host that
# install sometimes never finishes. The device's `cmd package install` waits for APK bytes that never arrive. The run
# then hung to its outer limit with no test started. Stopping the adb client ends the install with an error. adbd
# closes the device side too, and the module's task fails at once, so the caller can retry that module.
#
# Only an adb client whose first arguments are exactly `-s <serial> install` (or install-multiple) is matched, so a
# run on another device or emulator, or any other adb command on this one, is never touched.
install_watchdog() {
  local serial="$1" limit="$2" on_stall="$3" parent="$4" pid age stopped=" "
  while kill -0 "$parent" 2>/dev/null; do
    while read -r pid age; do
      if [[ "$stopped" == *" $pid "* ]]; then
        # Still there after SIGTERM: the next look is the grace period.
        kill -KILL "$pid" 2>/dev/null || true
        continue
      fi
      echo "adb install on $serial has run ${age}s (limit ${limit}s); stopping it (pid $pid)." >&2
      # A failing diagnostic must not stop the kill below.
      "$on_stall" "$pid" || true
      kill -TERM "$pid" 2>/dev/null || true
      stopped+="$pid "
    done < <(ps -u "$(id -u)" -o pid=,etimes=,args= |
      awk -v s="$serial" -v l="$limit" \
        '$3 ~ /(^|\/)adb$/ && $4 == "-s" && $5 == s && $6 ~ /^install(-multiple)?$/ && $2 >= l { print $1, $2 }')
    sleep "${INSTALL_WATCHDOG_POLL:-5}"
  done
}
