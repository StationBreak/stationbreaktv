#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Tests scripts/release-notes.sh on a throwaway repository: every group, breaking changes by `!` and by footer, scope
# and no scope, a first release with no previous tag, a non-conforming subject and a subject starting with `#`.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

check() { # <description> <expected> <actual>
  if [ "$2" = "$3" ]; then
    echo "ok   $1"
  else
    echo "FAIL $1"
    diff <(printf '%s\n' "$2") <(printf '%s\n' "$3") || true
    failures=$((failures + 1))
  fi
}

repo="$work/repo"
mkdir -p "$repo"
cd "$repo"
git init -q .
git config user.email t@example.test
git config user.name t
git config commit.gpgsign false
git config tag.gpgsign false

n=0
commit() { # <subject> [body]
  n=$((n + 1))
  echo "$n" >"file-$n"
  git add "file-$n"
  if [ "$#" -ge 2 ]; then
    git commit -q -m "$1" -m "$2"
  else
    git commit -q -m "$1"
  fi
}
sha() { git rev-parse --short=7 HEAD; }
notes() { bash "$here/release-notes.sh" "$1"; }

# First release: no previous tag, so every commit up to it.
commit "feat(guide): first feature (#1)"
a="$(sha)"
commit "docs: write the readme"
b="$(sha)"
git tag v1.0.0
check "first release: all commits, no previous tag" "### Features

- **guide:** first feature (#1) ($a)

### Other changes

- write the readme ($b)" "$(notes v1.0.0)"

# Second release: every group. Newest first inside a group.
commit "feat: plain feature without scope (#2)"
c1="$(sha)"
commit "fix(player): stop the crash (#3)"
c2="$(sha)"
commit "perf(guide)!: faster paint (#4)"
c3="$(sha)"
commit "refactor(data): split the repository" $'Explain it.\n\nBREAKING CHANGE: the repository API moved.'
c4="$(sha)"
commit "ci: pin the runner (#5)"
c5="$(sha)"
commit "Update stuff without a type"
c6="$(sha)"
commit "# Heading attempt <!-- hidden -->"
c7="$(sha)"
commit "revert: put it back (#6)"
c8="$(sha)"
commit "fix: BREAKING CHANGE: in the subject is not a footer"
c9="$(sha)"
commit "Feat(Menu): capitalised type is still a feature"
c10="$(sha)"
git tag v1.1.0

check "second release: every group, breaking by ! and by footer" "### Breaking changes

- **data:** split the repository ($c4)
- **guide:** faster paint (#4) ($c3)

### Features

- **Menu:** capitalised type is still a feature ($c10)
- plain feature without scope (#2) ($c1)

### Fixes

- BREAKING CHANGE: in the subject is not a footer ($c9)
- **player:** stop the crash (#3) ($c2)

### Performance

- **guide:** faster paint (#4) ($c3)

### Other changes

- put it back (#6) ($c8)
- Heading attempt &lt;!-- hidden --> ($c7)
- Update stuff without a type ($c6)
- pin the runner (#5) ($c5)
- **data:** split the repository ($c4)" "$(notes v1.1.0)"

# Third release: the range starts at the highest earlier version (1.10.0 is above 1.2.0), not the newest by date.
git tag v1.2.0 "$(git rev-parse v1.1.0)"
commit "fix: only this one (#7)"
d="$(sha)"
git tag v1.10.0
check "range starts at the previous tag by version order" "### Fixes

- only this one (#7) ($d)" "$(notes v1.10.0)"

# Nothing between two tags.
git tag v1.10.1
check "no commits: says so" "No changes since v1.10.0." "$(notes v1.10.1)"

# Bad input.
if bash "$here/release-notes.sh" v9.9.9 2>/dev/null; then rc=0; else rc=$?; fi
check "an unknown tag fails" "1" "$rc"
if bash "$here/release-notes.sh" main 2>/dev/null; then rc=0; else rc=$?; fi
check "a non-v tag is refused" "2" "$rc"

if [ "$failures" -ne 0 ]; then
  echo "$failures failed"
  exit 1
fi
echo "all passed"
