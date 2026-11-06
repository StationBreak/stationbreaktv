#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Copies GuideScreenshotTest's PNGs off the emulator into ./guide-screenshots.
# The emulator job calls this from the emulator-runner script, while adb still sees the device: the action stops the
# emulator when that script returns, so a later step can only upload files that are already on the runner.
# A missing directory is not a failure (a run that never reached the test); the upload step warns if no PNG is there.
# Exit: 0 copied or nothing to copy, 1 adb could not read a directory it found.
set -uo pipefail

dest="${1:-guide-screenshots}"
mkdir -p "$dest"

# The test writes under the instrumentation package's external files (API 25: /sdcard/Android/data/<pkg>/files).
remote="$(
  adb shell 'ls -d /sdcard/Android/data/*/files/guide-screenshots /storage/emulated/0/Android/data/*/files/guide-screenshots 2>/dev/null' |
    tr -d '\r' |
    head -n 1
)"
if [ -z "$remote" ]; then
  echo "No guide-screenshots directory on the device."
  exit 0
fi

echo "Pulling $remote"
names="$(adb shell "ls \"$remote\"" | tr -d '\r')"
if [ -z "$names" ]; then
  echo "guide-screenshots on the device is empty."
  exit 0
fi
status=0
while IFS= read -r name; do
  [ -n "$name" ] || continue
  if ! adb pull "$remote/$name" "$dest/$name"; then
    echo "could not pull $remote/$name" >&2
    status=1
  fi
done <<<"$names"
exit "$status"
