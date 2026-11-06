#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Copies GuideScreenshotTest's PNGs off the emulator into ./guide-screenshots.
# The emulator job calls this from the emulator-runner script, while adb still sees the device: the action stops the
# emulator when that script returns, so a later step can only upload files that are already on the runner.
#
# The pictures live in /sdcard/guide-screenshots. connectedAndroidTest uninstalls the test package before this runs,
# which removes the package's own external files; shared storage stays. Exit 1 when none are copied: a passing run
# with no pictures is a failed proof.
# Exit: 0 copied at least one PNG, 1 nothing was copied.
set -uo pipefail

dest="${1:-guide-screenshots}"
remote="/sdcard/guide-screenshots"
mkdir -p "$dest"

names="$(adb shell "ls $remote" 2>/dev/null | tr -d '\r')"
if [ -z "$names" ]; then
  echo "No $remote on the device." >&2
  adb shell 'ls -d /sdcard /sdcard/guide-screenshots /storage/emulated/0/guide-screenshots' >&2 || true
  exit 1
fi

copied=0
while IFS= read -r name; do
  case "$name" in
    *.png)
      if adb pull "$remote/$name" "$dest/$name" && [ -s "$dest/$name" ]; then
        magic="$(od -An -t x1 -N 8 "$dest/$name" | tr -d ' \n')"
        if [ "$magic" = "89504e470d0a1a0a" ]; then
          echo "Pulled $name"
          copied=$((copied + 1))
        else
          rm -f "$dest/$name"
          echo "$name is not a PNG" >&2
        fi
      else
        echo "could not pull $remote/$name" >&2
      fi
      ;;
  esac
done <<<"$names"

if [ "$copied" -eq 0 ]; then
  echo "No guide screenshots copied from $remote." >&2
  exit 1
fi
exit 0
