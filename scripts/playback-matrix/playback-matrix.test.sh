#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Tests the playback matrix tooling (spec 13): the Python unit tests of run.py and render.py, and temp-server.sh's
# argument handling, --dry-run and ownership checks. temp-server.sh runs against a stub `docker` that only records its
# arguments, so nothing here starts a container, contacts a host or needs a device.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

check() { # <description> <condition exit status>
  if [ "$2" -eq 0 ]; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}

# The unit tests. -I keeps the current directory and the user's site out of the interpreter's path; discover adds the
# start directory itself.
python3 -I -m unittest discover -s "$here" -p '*_test.py'
check "python unit tests (run.py, render.py)" $?

# A stub docker: logs every call, answers `inspect` with $STUB_LABEL and `ps -a` with $STUB_PS.
mkdir -p "$work/bin"
cat >"$work/bin/docker" <<'STUB'
#!/usr/bin/env bash
echo "$*" >>"$STUB_LOG"
case "$1 $2" in
  "ps -a") printf '%s' "${STUB_PS:-}" ;;
  "container inspect" | "volume inspect") printf '%s' "${STUB_LABEL:-}" ;;
esac
exit 0
STUB
chmod +x "$work/bin/docker"
export STUB_LOG="$work/docker.log"
server="$here/temp-server.sh"

run_server() { # <args...>: output in $work/out, status in $status
  : >"$STUB_LOG"
  status=0
  PATH="$work/bin:$PATH" bash "$server" "$@" >"$work/out" 2>&1 || status=$?
}

run_server --dry-run up 10.1.2.3
check "dry-run up succeeds" "$status"
grep -q 'ghcr.io/jellyfin/jellyfin@sha256:c3efa69a22f2691fc88d6bb035e87a8560e395a6047f062fb381c61c3185e8f5' "$work/out"
check "dry-run up names the pinned image digest" $?
grep -q 'stationbreak-playback-matrix' "$work/out"
check "dry-run up names the unique container" $?
grep -q '10.1.2.3' "$work/out"
check "dry-run up binds to the address it was given" $?
grep -qi 'synthetic-media.sh' "$work/out" && grep -q 'Formats' "$work/out" && grep -q 'items.local.json' "$work/out"
check "dry-run up lists the media, library and items-file steps" $?
[ ! -s "$STUB_LOG" ]
check "dry-run up calls docker never" $?

PLAYBACK_MATRIX_BIND=10.9.8.7 run_server --dry-run up
check "the bind address can come from PLAYBACK_MATRIX_BIND" "$status"
grep -q '10.9.8.7' "$work/out"
check "the environment address is the one used" $?

run_server up
[ "$status" -eq 2 ]
check "up without a bind address is a usage error" $?
run_server --dry-run up 0.0.0.0
[ "$status" -eq 2 ]
check "up refuses every interface (0.0.0.0)" $?
run_server --dry-run up 127.0.0.1
[ "$status" -eq 2 ]
check "up refuses loopback, which the device cannot reach" $?
run_server --dry-run up 'not-an-address'
[ "$status" -eq 2 ]
check "up refuses a bind that is not an IPv4 address" $?

STUB_PS='stationbreak-playback-matrix' run_server up 10.1.2.3
[ "$status" -ne 0 ] && ! grep -q '^run' "$STUB_LOG"
check "up refuses to start over an existing container" $?

run_server --dry-run down
check "dry-run down succeeds" "$status"
grep -q 'rm -f -v stationbreak-playback-matrix' "$work/out" && grep -q 'volume rm' "$work/out"
check "dry-run down removes the container and its volume" $?

STUB_PS=stationbreak-playback-matrix STUB_LABEL='' run_server down
[ "$status" -ne 0 ] && ! grep -q ' rm ' "$STUB_LOG"
check "down refuses a container it did not create" $?
STUB_PS=stationbreak-playback-matrix STUB_LABEL=1 run_server down
[ "$status" -eq 0 ] && grep -q 'rm -f -v stationbreak-playback-matrix' "$STUB_LOG" && grep -q 'volume rm' "$STUB_LOG"
check "down removes a container it created, with its volume" $?

! grep -nE '192\.168\.|(^|[^0-9])10\.[0-9]+\.[0-9]+\.[0-9]+|172\.(1[6-9]|2[0-9]|3[01])\.' "$server" >/dev/null
check "temp-server.sh hard-codes no private address" $?

# The ignore file keeps IDs and raw results off git.
ignored=0
for name in items.local.json plan.json results/x.jsonl; do
  (cd "$here" && git check-ignore -q "$name") || ignored=1
done
check "items, plans and results are gitignored" "$ignored"

# (That this tooling never passes the device-proof instrumentation argument is checked by preflight's device-proof guard.)

if command -v shellcheck >/dev/null 2>&1; then
  shellcheck "$server" "$here/playback-matrix.test.sh" "$here/synthetic-media.sh"
  check "shellcheck is clean" $?
else
  echo "skip shellcheck (not installed)"
fi

if [ "$failures" -ne 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "all checks passed"
