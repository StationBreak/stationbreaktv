#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Tests the lineup audit's exporter (tools/audit-export/README.md): the Python unit tests of the acceptance rules, the
# fixture, the safety rules and the exporter against a fake server on 127.0.0.1. Nothing here contacts a real server.
# -I keeps the current directory and the user's site out of the interpreter's path; discover adds the start directory.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
python3 -I -m unittest discover -s "$here" -p '*_test.py'
status=$?
if [ "$status" -eq 0 ]; then echo "ok   python unit tests (audit exporter)"; else echo "FAIL python unit tests (audit exporter)"; fi
exit "$status"
