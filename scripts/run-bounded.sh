#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Sourced by scripts/preflight.sh (and its test); defines run_bounded and runs nothing itself.
#
# run_bounded <seconds> <on-timeout-command> <command...>: runs the command, and if it is still going after <seconds>
# runs `<on-timeout-command> <pid>` while the command is STILL ALIVE, then stops it. Returns the command's status, or
# 124 on a timeout (as timeout(1) does).
#
# Why not timeout(1): its signal is the first thing the command sees, so there is no chance to look at the live
# process first (a thread dump of a hung Gradle client needs it running). The on-timeout command gets that chance.
#
# Stopping is SIGTERM, then SIGKILL after RUN_BOUNDED_GRACE seconds (default 30). ./gradlew execs the Gradle client
# JVM, so the pid is the client. Losing its client asks the daemon to cancel that build, but cancellation is
# cooperative: a task blocked in adb may not notice, so the daemon can stay busy for a while after this returns.
# Nothing here waits for it. Never `gradlew --stop`: that stops every daemon on the machine, including other
# worktrees' builds.
run_bounded() {
  local limit="$1" on_timeout="$2" pid status=0 start grace
  shift 2
  # A plain `"$@" &` in a script starts the command with SIGINT and SIGQUIT ignored, and the Gradle JVM would inherit
  # that: Ctrl-C would stop only this script while Gradle ran on. Restoring the defaults lets Ctrl-C reach the command.
  ( trap - INT QUIT; exec "$@" ) &
  pid=$!
  start=$SECONDS
  while kill -0 "$pid" 2>/dev/null; do
    if [ $((SECONDS - start)) -ge "$limit" ]; then
      echo "Timed out after ${limit}s: $*" >&2
      # A failing diagnostic must not stop the kill below.
      "$on_timeout" "$pid" || true
      kill -TERM "$pid" 2>/dev/null || true
      for grace in $(seq "${RUN_BOUNDED_GRACE:-30}"); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 1
      done
      kill -KILL "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
      return 124
    fi
    sleep 1
  done
  wait "$pid" || status=$?
  return "$status"
}
