#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Tests scripts/install-watchdog.sh: an `adb -s <serial> install` over the limit gets the on-stall command while still
# alive and is then stopped; an install on another serial, another adb command on the same serial, and an install still
# under the limit are left alone.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
pids=()
watchdog=""
cleanup() {
  if [ -n "$watchdog" ]; then kill "$watchdog" 2>/dev/null || true; fi
  # TERM first: a stand-in's trap then ends its sleep too, which SIGKILL would leave running.
  for pid in "${pids[@]}"; do kill -TERM "$pid" 2>/dev/null || true; done
  sleep 0.5
  for pid in "${pids[@]}"; do kill -KILL "$pid" 2>/dev/null || true; done
  rm -rf "$work"
}
trap cleanup EXIT
failures=0

check() { # <description> <0 if it holds>
  # (Each condition is run as `cond && ok=0 || ok=1`: a bare failing test would end the script under set -e.)
  if [ "$2" -eq 0 ]; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}

# shellcheck source=scripts/install-watchdog.sh
source "$here/install-watchdog.sh"

# A stand-in adb: a process whose command line reads `<dir>/adb <args...>` and that just waits. The args start with -s,
# which is also bash's "commands from stdin", so the rest are only shown, never run. TERM ends it and its sleep.
fake_adb() { # <args...>: starts one in the background and adds its pid to pids
  (exec -a "$work/adb" bash "$@" <<<'trap "kill \$! 2>/dev/null; exit 143" TERM; sleep 60 & wait') &
  pids+=("$!")
}

record() { # the on-stall command: was the process still alive when it ran?
  if kill -0 "$1" 2>/dev/null; then echo "$1 alive" >>"$work/stalls"; else echo "$1 dead" >>"$work/stalls"; fi
}

fake_adb -s emulator-5999 install -t --user 0 /x/feature/guide/build/outputs/apk/androidTest/debug/guide.apk
stalled="${pids[0]}"
fake_adb -s emulator-5998 install -t /x/app.apk
other_serial="${pids[1]}"
fake_adb -s emulator-5999 shell am instrument -w x/y
other_command="${pids[2]}"
sleep 1

export INSTALL_WATCHDOG_POLL=1
install_watchdog emulator-5999 2 record 2>"$work/watchdog.log" &
watchdog=$!

# Started after the watchdog, and it stops looking before this one is 2 s old.
fake_adb -s emulator-5999 install -t /x/young.apk
young="${pids[3]}"

for _ in 1 2 3 4 5 6 7 8 9 10; do
  kill -0 "$stalled" 2>/dev/null || break
  sleep 0.5
done
kill "$watchdog" 2>/dev/null || true
wait "$watchdog" 2>/dev/null || true
watchdog=""

kill -0 "$stalled" 2>/dev/null && ok=1 || ok=0
check "an install over the limit is stopped" "$ok"

grep -qx "$stalled alive" "$work/stalls" 2>/dev/null && ok=0 || ok=1
check "the on-stall command ran while the install was still alive" "$ok"

[ "$(wc -l <"$work/stalls")" -eq 1 ] && ok=0 || ok=1
check "the on-stall command ran once" "$ok"

grep -q "adb install on emulator-5999 has run" "$work/watchdog.log" && ok=0 || ok=1
check "the stop is reported" "$ok"

kill -0 "$other_serial" 2>/dev/null && ok=0 || ok=1
check "an install on another serial is left alone" "$ok"

kill -0 "$other_command" 2>/dev/null && ok=0 || ok=1
check "another adb command on the same serial is left alone" "$ok"

kill -0 "$young" 2>/dev/null && ok=0 || ok=1
check "an install under the limit is left alone" "$ok"

# A process that ignores SIGTERM is killed on the next look.
stubborn_log="$work/stubborn.log"
(exec -a "$work/adb" bash -s emulator-5997 install /x/s.apk <<<'trap "" TERM; while :; do sleep 1; done') &
stubborn=$!
pids+=("$stubborn")
noop() { :; }
install_watchdog emulator-5997 1 noop 2>"$stubborn_log" &
watchdog=$!
# Quietly: bash reports a job killed by a signal on its stderr.
{
  for _ in $(seq 20); do
    kill -0 "$stubborn" || break
    sleep 0.5
  done
  kill -0 "$stubborn" && ok=1 || ok=0
  if [ "$ok" -eq 0 ]; then wait "$stubborn" || true; fi
} 2>/dev/null
kill "$watchdog" 2>/dev/null || true
wait "$watchdog" 2>/dev/null || true
watchdog=""
check "an install that ignores SIGTERM is killed" "$ok"

if [ "$failures" -ne 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
