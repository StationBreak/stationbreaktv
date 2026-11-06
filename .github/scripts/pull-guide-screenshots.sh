#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Copies GuideScreenshotTest's PNGs off the emulator into ./guide-screenshots.
# The emulator job calls this from the emulator-runner script, while adb still sees the device: the action stops the
# emulator when that script returns, so a later step can only upload files that are already on the runner.
#
# The test writes the same PNGs in two places. The shell on API 25 cannot list another app's external files directory,
# so the copy that is pulled is the test package's private files, via run-as. Exit 1 when none are copied: a passing
# run with no pictures is a failed proof.
# Exit: 0 copied at least one PNG, 1 nothing was copied.
set -uo pipefail

dest="${1:-guide-screenshots}"
pkg="tv.stationbreak.feature.guide.test"
mkdir -p "$dest"

copied=0
copy_private() {
  local name="$1" magic
  if adb exec-out run-as "$pkg" cat "files/guide-screenshots/$name" >"$dest/$name"; then
    magic="$(od -An -t x1 -N 8 "$dest/$name" | tr -d ' \n')"
  else
    magic=""
  fi
  # A failed run-as still writes its error text; only a real PNG counts.
  if [ "$magic" = "89504e470d0a1a0a" ]; then
    echo "Pulled $name"
    copied=$((copied + 1))
  else
    rm -f "$dest/$name"
    echo "could not read files/guide-screenshots/$name via run-as" >&2
  fi
}

names="$(adb shell run-as "$pkg" ls files/guide-screenshots 2>/dev/null | tr -d '\r')"
if [ -n "$names" ]; then
  while IFS= read -r name; do
    case "$name" in
      *.png) copy_private "$name" ;;
    esac
  done <<<"$names"
fi

if [ "$copied" -eq 0 ]; then
  echo "No guide screenshots copied from $pkg." >&2
  echo "run-as files:" >&2
  adb shell run-as "$pkg" ls -la files >&2 || true
  exit 1
fi
exit 0
