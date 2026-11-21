#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Tests scripts/release-notes.sh on a throwaway repository: every group, breaking changes by `!` and by footer, scope
# and no scope, a first release with no previous tag, a non-conforming subject, a subject starting with `#`, Markdown
# and control characters in subjects, forged record separators in a body, tags that are not releases, the end-commit
# argument and a shallow clone.
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
notes() { bash "$here/release-notes.sh" "$@"; }

# First release: no previous tag, so no commit list.
commit "feat(guide): first feature (#1)"
commit "docs: write the readme"
git tag v1.0.0
check "first release: one line, no commit list" "First release." "$(notes v1.0.0)"

# Second release: every group. Newest first inside a group. Parentheses are escaped, so `(#4)` reads `\(#4\)`.
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
commit "## Heading attempt <!-- hidden -->"
c7="$(sha)"
commit "revert: put it back (#6)"
c8="$(sha)"
commit "fix: #12 crash keeps its issue number"
c11="$(sha)"
commit "fix: BREAKING CHANGE: in the subject is not a footer"
c9="$(sha)"
commit "Feat(Menu): capitalised type is still a feature"
c10="$(sha)"
git tag v1.1.0

check "second release: every group, breaking by ! and by footer" '### Breaking changes

- **data:** split the repository ('"$c4"')
- **guide:** faster paint \(#4\) ('"$c3"')

### Features

- **Menu:** capitalised type is still a feature ('"$c10"')
- plain feature without scope \(#2\) ('"$c1"')

### Fixes

- BREAKING CHANGE: in the subject is not a footer ('"$c9"')
- #12 crash keeps its issue number ('"$c11"')
- **player:** stop the crash \(#3\) ('"$c2"')

### Performance

- **guide:** faster paint \(#4\) ('"$c3"')

### Other changes

- put it back \(#6\) ('"$c8"')
- Heading attempt \<\!-- hidden --\> ('"$c7"')
- Update stuff without a type ('"$c6"')
- pin the runner \(#5\) ('"$c5"')
- **data:** split the repository ('"$c4"')' "$(notes v1.1.0)"

# Third release: the range starts at the highest earlier version (1.10.0 is above 1.2.0), not the newest by date.
git tag v1.2.0 "$(git rev-parse v1.1.0)"
commit "fix: only this one (#7)"
d="$(sha)"
git tag v1.10.0
check "range starts at the previous tag by version order" '### Fixes

- only this one \(#7\) ('"$d"')' "$(notes v1.10.0)"

# Nothing between two tags.
git tag v1.10.1
check "no commits: says so" "No changes since v1.10.0." "$(notes v1.10.1)"

# Untrusted text: nothing a subject says may render as a link, image, fence, tag or heading, or carry control or
# invisible characters. The body forges the old record separators (\x1f, \x1e) and a fake commit.
commit "fix: [click](http://evil.test/x) here"
u1="$(sha)"
commit "fix: ![pic](http://evil.test/x.png)"
u2="$(sha)"
commit 'fix: ```js'
u3="$(sha)"
commit "fix: ~~~"
u4="$(sha)"
commit "fix: <script>alert(1)</script> <b>bold</b>"
u5="$(sha)"
commit $'fix: carriage\rreturn ::add-mask::secret\ttab'
u6="$(sha)"
commit $'fix: zero\xe2\x80\x8bwidth bidi\xe2\x80\xaeflip bom\xef\xbb\xbfhere'
u7="$(sha)"
commit 'fix: back\slash and *stars* and _under_ and a|pipe'
u8="$(sha)"
commit "fix(a*b): scope with a star"
u9="$(sha)"
commit "fix: forged records" $'text\x1f0000000\x1fBREAKING CHANGE: no\x1efake\x1f'
u10="$(sha)"
commit 'fix: entities &#x202E; and &lt;b&gt; stay text'
v1="$(sha)"
commit "fix: thanks @octo-cat and @team_x for the fix"
v2="$(sha)"
commit "fix: - not a list"
v3="$(sha)"
commit "fix: 1. not a numbered list"
v4="$(sha)"
commit "fix: === not a rule"
v5="$(sha)"
commit $'fix: more invisible\xe2\x81\xa0word\xd8\x9cx\xc2\xadsoft\xe1\xa0\x8eY\xe2\x80\xa8Z\xc2\x85C1'
v6="$(sha)"
# Tags that are not releases must never be the previous tag, even though they are reachable and sort high.
git tag v1.99.0-rc1
git tag vendor-x
git tag v2.0.0-rc1
commit "fix: last"
u11="$(sha)"
git tag v1.10.2
# shellcheck disable=SC2016 # the backticks are literal
check "untrusted text is inert; pre-release and non-release tags are ignored" '### Fixes

- last ('"$u11"')
- more invisiblewordxsoftYZC1 ('"$v6"')
- \=== not a rule ('"$v5"')
- 1\. not a numbered list ('"$v4"')
- \- not a list ('"$v3"')
- thanks `@octo-cat` and `@team_x` for the fix ('"$v2"')
- entities \&#x202E; and \&lt;b\&gt; stay text ('"$v1"')
- forged records ('"$u10"')
- **a\*b:** scope with a star ('"$u9"')
- back\\slash and \*stars\* and \_under\_ and a\|pipe ('"$u8"')
- zerowidth bidiflip bomhere ('"$u7"')
- carriagereturn ::add-mask::secrettab ('"$u6"')
- \<script\>alert\(1\)\</script\> \<b\>bold\</b\> ('"$u5"')
- \~\~\~ ('"$u4"')
- \`\`\`js ('"$u3"')
- \!\[pic\]\(http://evil.test/x.png\) ('"$u2"')
- \[click\]\(http://evil.test/x\) here ('"$u1"')' "$(notes v1.10.2)"

# The end commit argument ends the range: a full SHA, not the tag.
before_last="$(git rev-parse HEAD~1)"
check "an end commit stops the range there" "$(notes v1.10.2 "$before_last" | head -n 3)" '### Fixes

- more invisiblewordxsoftYZC1 ('"$v6"')'
check "an end commit of the tag's own commit matches the default" "$(notes v1.10.2)" "$(notes v1.10.2 "$(git rev-parse HEAD)")"

# Bad input.
if notes v9.9.9 2>/dev/null; then rc=0; else rc=$?; fi
check "an unknown tag fails" "1" "$rc"
if notes main 2>/dev/null; then rc=0; else rc=$?; fi
check "a non-release tag is refused" "2" "$rc"
if notes v1.2.0-rc1 2>/dev/null; then rc=0; else rc=$?; fi
check "a pre-release tag without the dot is refused" "2" "$rc"
if notes v1.2.0-dev.1 2>/dev/null; then rc=0; else rc=$?; fi
check "a pre-release label other than alpha, beta and rc is refused" "2" "$rc"
if notes v1.2.0-beta.30 2>/dev/null; then rc=0; else rc=$?; fi
check "a pre-release number above 29 is refused" "2" "$rc"
if notes v1.10.2 --help 2>/dev/null; then rc=0; else rc=$?; fi
check "an end commit that is not a full SHA is refused" "2" "$rc"
if notes v1.10.2 "$(git rev-parse --short HEAD)" 2>/dev/null; then rc=0; else rc=$?; fi
check "a short SHA is refused" "2" "$rc"

# A beta tag, and a tag with MAJOR 0, are release tags (release.yml's tag check): the range runs from the last final.
commit "fix: only in the beta"
beta="$(sha)"
git tag v1.10.3-beta.1
check "a beta tag gets the notes since the last final release" '### Fixes

- only in the beta ('"$beta"')' "$(notes v1.10.3-beta.1)"
git tag v0.9.0-rc.2
if notes v0.9.0-rc.2 >/dev/null 2>&1; then rc=0; else rc=$?; fi
check "a tag with MAJOR 0 is accepted" "0" "$rc"

# A shallow clone can't give the right range.
git clone -q --no-local --depth 1 "file://$repo" "$work/shallow"
cd "$work/shallow"
if bash "$here/release-notes.sh" v1.10.2 2>/dev/null; then rc=0; else rc=$?; fi
check "a shallow clone fails" "1" "$rc"

if [ "$failures" -ne 0 ]; then
  echo "$failures failed"
  exit 1
fi
echo "all passed"
