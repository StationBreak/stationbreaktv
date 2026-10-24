#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Tests scripts/run-bounded.sh: a command that finishes passes its status through; one that outlives the limit gets the
# on-timeout command while still alive, is then stopped, and returns 124.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

check() { # <description> <condition exit status>
  if [ "$2" -eq 0 ]; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}

# shellcheck source=scripts/run-bounded.sh
source "$here/run-bounded.sh"

# The grace between SIGTERM and SIGKILL, short so this test stays quick.
export RUN_BOUNDED_GRACE=1

noop() { :; }
record() { # records whether the command was still alive when the on-timeout command ran
  if kill -0 "$1" 2>/dev/null; then echo alive >"$work/state"; else echo dead >"$work/state"; fi
  echo "$1" >"$work/pid"
}

status=0
run_bounded 5 noop true || status=$?
check "a command that succeeds returns 0" "$status"

status=0
run_bounded 5 noop bash -c 'exit 7' || status=$?
[ "$status" -eq 7 ]
check "a command that fails keeps its status" $?

status=0
run_bounded 2 record sleep 300 || status=$?
[ "$status" -eq 124 ]
check "a command over the limit returns 124" $?

[ "$(cat "$work/state")" = alive ]
check "the on-timeout command ran while the command was still alive" $?

! kill -0 "$(cat "$work/pid")" 2>/dev/null
check "the command was stopped" $?

# A command that ignores SIGTERM is still stopped (by SIGKILL after the grace period).
status=0
start=$SECONDS
run_bounded 1 noop bash -c 'trap "" TERM; while :; do sleep 1; done' || status=$?
[ "$status" -eq 124 ] && [ $((SECONDS - start)) -lt 20 ]
check "a command that ignores SIGTERM is killed" $?

# A failing on-timeout command does not stop the kill.
fail_hook() { return 1; }
status=0
run_bounded 1 fail_hook sleep 300 || status=$?
[ "$status" -eq 124 ]
check "a failing on-timeout command still lets the command be stopped" $?

# Ctrl-C: SIGINT sent to the script's process group must reach the command. A background job in a script starts with
# SIGINT ignored unless run_bounded restores it. The script is started from an interactive-less subshell with job control
# on (set -m), so it has its own process group to signal.
cat >"$work/ctrlc.sh" <<EOS
source "$here/run-bounded.sh"
noop() { :; }
run_bounded 60 noop sleep 4242
EOS
set -m
bash "$work/ctrlc.sh" &
script_pid=$!
set +m
sleep 2
kill -INT -- "-$script_pid" 2>/dev/null || true
for _ in 1 2 3 4 5 6 7 8 9 10; do
  pgrep -f "sleep 4242" >/dev/null || break
  sleep 1
done
! pgrep -f "sleep 4242" >/dev/null
check "SIGINT reaches the command" $?
pkill -f "sleep 4242" 2>/dev/null || true
wait "$script_pid" 2>/dev/null || true

if [ "$failures" -ne 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
