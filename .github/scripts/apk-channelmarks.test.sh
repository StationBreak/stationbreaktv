#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Fixture tests for apk-channelmarks.sh. Each case builds a small zip shaped like an APK, so no binary is committed.
# Usage: bash .github/scripts/apk-channelmarks.test.sh
# No -e: a failing case must be reported and counted, not end the run.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$here/apk-channelmarks.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

# Three marks and their manifest, as core/ui/channelmarks.sha256 lists them.
src="$work/src/assets/channelmarks"
mkdir -p "$src"
printf 'red' >"$src/a_48dp.png"
printf 'green' >"$src/b_48dp.png"
printf 'blue' >"$src/c_64dp.png"
manifest="$work/channelmarks.sha256"
(cd "$work/src" && sha256sum assets/channelmarks/* | sed 's|  assets/|  src/main/assets/|') >"$manifest"

# apk <name> <file in the APK>=<source file or literal:text>...
apk() {
  local name="$1" out="$work/$1.apk"
  shift
  python3 -I - "$out" "$@" <<'PY'
import sys, zipfile
out, entries = sys.argv[1], sys.argv[2:]
with zipfile.ZipFile(out, "w") as z:
    z.writestr("AndroidManifest.xml", b"<manifest/>")
    for e in entries:
        path, src = e.split("=", 1)
        data = src[len("literal:"):].encode() if src.startswith("literal:") else open(src, "rb").read()
        z.writestr(path, data)
PY
  echo "$out"
}
all=("assets/channelmarks/a_48dp.png=$src/a_48dp.png" "assets/channelmarks/b_48dp.png=$src/b_48dp.png"
  "assets/channelmarks/c_64dp.png=$src/c_64dp.png")

# expect <description> <exit code> <text the output must contain> -- <script args>...
expect() {
  local desc="$1" want="$2" needle="$3" out got
  shift 4
  out="$(bash "$script" "$@" 2>&1)"
  got=$?
  if [ "$got" -ne "$want" ]; then
    echo "FAIL $desc: exit $got, want $want"
    printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
  elif [ -n "$needle" ] && ! grep -qF -- "$needle" <<<"$out"; then
    echo "FAIL $desc: output does not contain '$needle'"
    printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
  else
    echo "ok   $desc (exit $got)"
  fi
}

good="$(apk good "${all[@]}" "assets/licenses/OFL.txt=literal:other assets don't count")"
expect "every mark, byte for byte" 0 "carries all 3 marks" -- "$manifest" "$good"

missing="$(apk missing "${all[0]}" "${all[2]}")"
expect "a missing mark" 1 "is missing assets/channelmarks/b_48dp.png" -- "$manifest" "$missing"

changed="$(apk changed "${all[0]}" "assets/channelmarks/b_48dp.png=literal:recompressed" "${all[2]}")"
expect "a mark whose bytes differ" 1 "has a different assets/channelmarks/b_48dp.png" -- "$manifest" "$changed"

extra="$(apk extra "${all[@]}" "assets/channelmarks/d_48dp.png=literal:new")"
expect "a mark the manifest doesn't list" 1 "d_48dp.png, which the manifest doesn't list" -- "$manifest" "$extra"

none="$(apk none "assets/licenses/OFL.txt=literal:no marks at all")"
expect "an APK with no marks" 1 "is missing assets/channelmarks/a_48dp.png" -- "$manifest" "$none"

expect "two APKs, one bad: fails, names the bad one" 1 "$missing is missing" -- "$manifest" "$good" "$missing"
expect "two APKs, one bad: still reports the good one" 1 "$good carries all 3 marks" -- "$manifest" "$good" "$missing"
expect "an APK path that doesn't exist" 1 "no such APK" -- "$manifest" "$work/nope.apk"

printf 'not a manifest line\n' >"$work/bad.sha256"
expect "a manifest line of the wrong shape" 1 "unexpected line" -- "$work/bad.sha256" "$good"
: >"$work/empty.sha256"
expect "an empty manifest" 1 "missing or empty" -- "$work/empty.sha256" "$good"
expect "no APK given" 1 "usage" -- "$manifest"

if [ "$failures" -ne 0 ]; then
  echo "$failures test(s) failed"
  exit 1
fi
echo "all tests passed"
