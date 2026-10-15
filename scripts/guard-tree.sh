#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Copies the files the repo's guard scripts should scan into <dest>: every tracked file and every untracked, un-ignored
# one, minus anything under .claude/ (agent worktrees, each a full checkout of its own). Run from inside the repo:
#   scripts/guard-tree.sh <dest>
# The guards (.github/scripts/*-guard.sh) walk the tree with find, so run on the repo root they also read those
# worktrees and fail on a checkout that isn't the one being checked.
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <dest-dir>" >&2
  exit 2
fi
dest="$1"
cd "$(git rev-parse --show-toplevel)"
mkdir -p "$dest"

git ls-files -z --cached --others --exclude-standard |
  while IFS= read -r -d '' path; do
    case "$path" in
      .claude/*) continue ;;
    esac
    # A tracked file deleted in the working tree is not there to copy.
    if [ -f "$path" ] || [ -L "$path" ]; then
      mkdir -p "$dest/$(dirname "$path")"
      cp -P "$path" "$dest/$path"
    fi
  done
