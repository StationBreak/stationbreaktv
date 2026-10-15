#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Tests scripts/guard-tree.sh: tracked and untracked files are copied, .claude/ and ignored files are not.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

check() { # <description> <condition exit status>
  if [ "$2" -eq 0 ]; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}

repo="$work/repo"
mkdir -p "$repo"
cd "$repo"
git init -q .
git config user.email t@example.test
git config user.name t
mkdir -p gradle .github/scripts .claude/worktrees/w/gradle
echo tracked >gradle/verification-metadata.xml
echo tracked >.github/scripts/x.sh
echo '*.log' >.gitignore
git add -A
git commit -q -m init
echo untracked >new-file.txt
echo ignored >ignored.log
echo agent >.claude/worktrees/w/gradle.properties
echo gone >deleted.txt
git add deleted.txt
git commit -q -m add-deleted
rm deleted.txt

dest="$work/out"
bash "$here/guard-tree.sh" "$dest"

[ -f "$dest/gradle/verification-metadata.xml" ]
check "a tracked file is copied" $?
[ -f "$dest/.github/scripts/x.sh" ]
check "a tracked file under .github is copied" $?
[ -f "$dest/new-file.txt" ]
check "an untracked file is copied" $?
[ ! -e "$dest/ignored.log" ]
check "an ignored file is not copied" $?
[ ! -e "$dest/.claude" ]
check "nothing under .claude is copied" $?
[ ! -e "$dest/deleted.txt" ]
check "a tracked file deleted from the tree is skipped" $?

if [ "$failures" -ne 0 ]; then
  echo "$failures check(s) failed" >&2
  exit 1
fi
