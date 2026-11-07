#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Tests scripts/adb-server.sh (#643): an adb server started through start_adb_server_unlocked does not hold the emulator
# lock's descriptor, so it cannot keep the lock after the run. A stand-in adb forks a long-lived "daemon" as the real
# one does, and the lock file is checked with the daemon still alive. A control run without the helper shows the
# stand-in does inherit the lock, so the test can fail.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
cleanup() {
  if [ -f "$work/daemon.pid" ]; then kill "$(cat "$work/daemon.pid")" 2>/dev/null || true; fi
  rm -rf "$work"
}
trap cleanup EXIT
failures=0

check() { # <description> <0 if it holds>
  if [ "$2" -eq 0 ]; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}

# shellcheck source=scripts/adb-server.sh
source "$here/adb-server.sh"

# A stand-in adb: `start-server` forks a daemon that outlives it, and records its pid.
cat >"$work/adb" <<'STUB'
#!/usr/bin/env bash
if [ "$1" = start-server ]; then
  sleep 60 &
  echo $! >"$(dirname "$0")/daemon.pid"
fi
STUB
chmod +x "$work/adb"

# holders <lock file>: the pids with it open, read from /proc (no dependency on fuser).
holders() {
  local fd target
  for fd in /proc/[0-9]*/fd/*; do
    target="$(readlink "$fd" 2>/dev/null || true)"
    if [ "$target" = "$1" ]; then basename "$(dirname "$(dirname "$fd")")"; fi
  done | sort -u
}

run_case() { # <label> <helper|plain>: starts the stand-in adb with the lock held, then reports who holds the lock
  local mode="$1" daemon
  rm -f "$work/daemon.pid"
  emulator_lock_fd=""
  exec {emulator_lock_fd}>"$work/emulator.lock"
  flock -n "$emulator_lock_fd"
  if [ "$mode" = helper ]; then
    start_adb_server_unlocked "$work/adb"
  else
    "$work/adb" start-server
  fi
  daemon="$(cat "$work/daemon.pid")"
  kill -0 "$daemon" 2>/dev/null && alive=0 || alive=1
  holders "$work/emulator.lock" | grep -qx "$daemon" && held=0 || held=1
  exec {emulator_lock_fd}>&-
  kill "$daemon" 2>/dev/null || true
}

run_case control plain
check "control: a daemon started without the helper inherits the lock" "$held"
run_case helper helper
check "the daemon is still running after the helper returns" "$alive"
check "a daemon started with the helper does not hold the lock" "$((1 - held))"
# The lock is free again once this script's own descriptor is closed.
flock -n "$work/emulator.lock" true && ok=0 || ok=1
check "the lock can be taken once the script lets go of it" "$ok"

if [ "$failures" -ne 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
