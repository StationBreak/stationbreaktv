#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Sourced by scripts/preflight.sh (and its test); defines start_adb_server_unlocked and runs nothing itself.
#
# start_adb_server_unlocked <adb>: starts the adb server with the emulator lock's descriptor (named by the variable
# emulator_lock_fd) closed for it. The first adb client to find no server forks the daemon, and the daemon outlives the
# run; if it inherited the lock, the lock would stay held after the run and the next preflight would wait on it forever
# (#643). Bounded like the script's other adb calls. Returns the status of `adb start-server`.
start_adb_server_unlocked() {
  timeout 30 "$1" start-server {emulator_lock_fd}>&-
}
