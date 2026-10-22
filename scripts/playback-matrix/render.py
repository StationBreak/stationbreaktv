#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
"""Turns playback result files into the public playback matrix (spec 13). Standard library only.

  python3 -I scripts/playback-matrix/render.py results/*.jsonl > docs/compatibility/playback-matrix.md

Each file holds the META and RESULT lines the benchmark build's PlaybackTestActivity printed. The output is a public
document, so nothing that could name a home network or a library may reach it: if any value holds a "/", a "://",
"api_key", a 32-hex-character run (an item ID), an IPv4 or IPv6 address or a host:port, nothing is printed and the exit
status is 1. The error names the field and the rule, never the value.
"""

import json
import re
import string
import sys

SECTION_ORDER = ("synthetic", "real")

def _hex_run_of_32(text):
    """A run of hex digits split by dashes or spaces, in any grouping, holding 32 or more hex digits (a GUID or ID)."""
    runs = re.findall(r"[0-9a-fA-F][0-9a-fA-F\s-]*", text)
    return any(sum(c in string.hexdigits for c in run) >= 32 for run in runs)


# (rule name, test): a value or key for which any test is true is refused. Fail closed: no lookarounds, so an address
# glued to other characters is still caught. The hostname rule also rejects a dotted name such as "movies.lan"; the
# legitimate values (codecs, containers, routes, reasons, error codes, a model such as AFTMM, versions like 0.3.0)
# have no letters after a dot, so none of them trips it.
LEAK_RULES = (
    ("a slash", re.compile(r"/").search),
    ("a URL", re.compile(r"://").search),
    ("an api_key", re.compile(r"api_key", re.IGNORECASE).search),
    ("percent-encoding", re.compile(r"%[0-9A-Fa-f]{2}").search),
    ("an ID-shaped run of 32 hex characters", _hex_run_of_32),
    ("an IPv4 address", re.compile(r"\d{1,3}(?:\.\d{1,3}){3}").search),
    ("an IPv6 address", re.compile(r"(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{1,4}").search),
    ("a host:port", re.compile(r"[A-Za-z0-9.\]-]:\d{1,5}").search),
    ("a host name", re.compile(r"[A-Za-z0-9-]+\.[A-Za-z]{2,}").search),
)

COLUMNS = (
    ("Container", "container"),
    ("Video", "video"),
    ("Audio", "audio"),
    ("Subtitle", "subtitle"),
    ("Route", "route"),
    ("Expected", "expectedRoute"),
    ("Transcode reasons", "transcodeReasons"),
    ("Burn-in", "burnIn"),
    ("Fallback", "fallback"),
    ("Start (ms)", "requestedMs"),
    ("Position error (ms)", "positionErrorMs"),
    ("First frame (ms)", "firstFrameMs"),
    ("Playing (ms)", "playingMs"),
    ("Stalls", "stalls"),
    ("Audio heard", "audioOk"),
    ("Subtitle shown", "subtitleOk"),
    ("Result", "pass"),
    ("Error", "error"),
)

# A start point can hint at the runtime of an item, so only the sections listed here, which play generated clips,
# show it. Fail closed: any other section name, however spelled, plays someone's own files and hides it.
START_SHOWN_SECTIONS = frozenset({"synthetic"})
START_FIELD = "requestedMs"

# Only generated clips have a known, fixed seek behaviour, so only this section is held to it: a seek-miss fallback
# there passes only for a clip the run marked as expected to miss (an MKV without Cues). Anywhere else it passes
# and is counted.
STRICT_FALLBACK_SECTIONS = frozenset({"synthetic"})

INTRO = """\
# Playback matrix

What the StationBreak player did with a spread of files on real devices: which route the server chose (direct play,
direct stream or transcode), whether playback started where it was asked to, and whether the sound and subtitles came
through. Each section is one set of files on one device; each row is one file. The synthetic section plays generated
test clips made for this purpose; the real section plays a few files from the owner's own library, named here only by
their format.

How to read it: Expected is the route the player's own device model predicts and the server's first answer should
match; Route is the route that finally played. They differ when Fallback is not "no". Fallback "seek-miss" means a
direct play or direct stream landed away from the requested start and was replaced once by a transcode, which is by
design (ADR-0016) and passes; in the synthetic section it passes only for the one clip expected to do so, an MKV
without a Cues index. Fallback "load-failed" means the first attempt did not load or play (the Error column says why)
and always fails the row. A difference between Expected and Route with Fallback "no" is a finding. Start (synthetic
section only) is where playback was asked to begin. Position error is how far the first frame was from that point
(empty when it landed within tolerance). Audio heard means the media clock advanced during a 15 s watch; Subtitle
shown means text cues arrived or the server burned the subtitle in. A dash means the column does not apply or nothing
was reported.

Rows for audio the device cannot play (Expected: direct stream) assume an account with default audio-track playback
turned on: the server offers a direct stream for such audio only then. The device runs use such an account.

Timings (first frame, playing) are measured on a non-debuggable build, on the device as it is, and are not graded: a
slow device is slow, and the number is there to be read, not to pass or fail. Only the Result column grades a row.
"""


class Leak(Exception):
    """A value that must not reach the public document. The message never holds the value."""


def _check(field, value):
    text = str(value)
    for rule, matches in LEAK_RULES:
        if matches(text):
            raise Leak(f"{field} holds {rule}")


def parse_lines(lines):
    """(meta dict or None, [result dicts]) from one file's lines. Other lines (DONE, ERROR, noise) are skipped."""
    meta, results = None, []
    for raw in lines:
        line = raw.strip()
        if line.startswith("META "):
            payload, kind = line[5:], "meta"
        elif line.startswith("RESULT "):
            payload, kind = line[7:], "result"
        elif line.startswith("{"):
            payload, kind = line, None
        else:
            continue
        try:
            value = json.loads(payload)
        except ValueError:
            raise ValueError("a result line is not JSON") from None
        if not isinstance(value, dict):
            raise ValueError("a result line is not a JSON object")
        kind = kind or ("meta" if "device" in value and "route" not in value else "result")
        if kind == "meta":
            meta = meta or value
        else:
            results.append(value)
    return meta, results


def _cell(value):
    if value is None or value == "":
        return "—"
    if isinstance(value, str):
        if any(ord(ch) < 0x20 or ord(ch) == 0x7F for ch in value):
            # A newline would break the table row and could start markup of its own.
            raise Leak("a cell holds a control character")
        return value.replace("|", "\\|").replace("<", "&lt;").replace(">", "&gt;")
    if value is True:
        return "yes"
    if value is False:
        return "no"
    return str(value)


def _graded(section, row):
    """A copy of [row] with the checks the renderer adds: a load-failed fallback fails the row, and so does an
    unexpected seek-miss fallback in a strict section. The key that marks an expected one is not shown."""
    row = dict(row)
    expected = row.pop("seekMissExpected", None) is True
    fallback = row.get("fallback")
    reason = None
    if fallback == "load-failed":
        reason = "fallback-load-failed"
    elif fallback == "seek-miss" and section in STRICT_FALLBACK_SECTIONS and not expected:
        reason = "unexpected-fallback"
    if reason and row.get("pass") is True:
        row["pass"] = False
        row["error"] = row.get("error") or reason
    return row


def _result_cell(value):
    return "pass" if value is True else "FAIL"


def _stamp(meta):
    build = "a debuggable build" if meta.get("debuggable") else "a non-debuggable build"
    return [
        f"- Device: {_cell(meta.get('device'))} (API {_cell(meta.get('sdk'))})",
        f"- App: {_cell(meta.get('app'))}, {build}",
        f"- Jellyfin: {_cell(meta.get('jellyfin'))}",
        f"- Audio sink: {_cell(meta.get('sink'))}",
    ]


def render(files):
    """The markdown for [files], each a list of lines. Raises Leak (before any output) if a value must not be public."""
    groups = {}
    for lines in files:
        meta, results = parse_lines(lines)
        if meta is None:
            raise ValueError("a results file has no META line")
        for field, value in meta.items():
            _check("a META key", field)
            _check(f"META {field}", value)
        for result in results:
            for field, value in result.items():
                _check("a RESULT key", field)
                _check(f"RESULT {field}", value)
            key = (str(result.get("section", "unnamed")), str(meta.get("device", "?")))
            entry = groups.setdefault(key, {"meta": meta, "rows": []})
            entry["rows"].append(result)

    def order(key):
        section, device = key
        rank = SECTION_ORDER.index(section) if section in SECTION_ORDER else len(SECTION_ORDER)
        return (rank, section, device)

    out = [INTRO]
    for key in sorted(groups, key=order):
        section, device = key
        entry = groups[key]
        rows = [_graded(section, row) for row in entry["rows"]]
        passing = [row for row in rows if row.get("pass") is True]
        via_fallback = sum(1 for row in passing if row.get("fallback") not in (None, False, "no"))
        out.append(f"\n## {section}: {device}\n")
        out.extend(_stamp(entry["meta"]))
        out.append(f"- Passed {len(passing)} of {len(rows)} ({via_fallback} via fallback)\n")
        shown = section in START_SHOWN_SECTIONS
        columns = [(name, f) for name, f in COLUMNS if shown or f != START_FIELD]
        out.append("| " + " | ".join(name for name, _ in columns) + " |")
        out.append("|" + "|".join(" --- " for _ in columns) + "|")
        for row in rows:
            cells = [_result_cell(row.get(f)) if f == "pass" else _cell(row.get(f)) for _, f in columns]
            out.append("| " + " | ".join(cells) + " |")
    return "\n".join(out) + "\n"


def main(argv=None):
    paths = sys.argv[1:] if argv is None else argv
    if not paths:
        print("usage: render.py results/*.jsonl > docs/compatibility/playback-matrix.md", file=sys.stderr)
        return 2
    files = []
    try:
        for path in paths:
            with open(path, encoding="utf-8") as handle:
                files.append(handle.read().splitlines())
        text = render(files)
    except Leak as leak:
        print(f"render: refused, {leak}. Nothing was written.", file=sys.stderr)
        return 1
    except (ValueError, OSError) as error:
        print(f"render: {type(error).__name__}: {error}", file=sys.stderr)
        return 1
    sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
