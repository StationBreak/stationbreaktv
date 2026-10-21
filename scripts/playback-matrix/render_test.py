# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
"""Tests for render.py, the playback matrix renderer.

Run: python3 -I -m unittest discover -s scripts/playback-matrix -p '*_test.py'
"""

import json
import unittest

import render


def meta(**over):
    fields = {
        "app": "0.3.0",
        "device": "AFTMM",
        "sdk": 25,
        "jellyfin": "12.1.0",
        "sink": "passthrough=ac3;channels=6",
        "debuggable": False,
        "run_id": "r1",
    }
    fields.update(over)
    return "META " + json.dumps(fields)


def result(**over):
    fields = {
        "section": "synthetic",
        "container": "mkv",
        "video": "h264 320x240",
        "audio": "aac 2ch",
        "subtitle": "none",
        "route": "direct-play",
        "expectedRoute": "direct-play",
        "transcodeReasons": "—",
        "burnIn": False,
        "fallback": False,
        "requestedMs": 30000,
        "positionErrorMs": 120,
        "firstFrameMs": 900,
        "playingMs": 1400,
        "stalls": 0,
        "audioOk": True,
        "subtitleOk": None,
        "pass": True,
        "error": None,
    }
    fields.update(over)
    return "RESULT " + json.dumps(fields)


class RenderTest(unittest.TestCase):
    def test_aRowPerResult(self):
        text = render.render([[meta(), result(), result(container="mp4"), result(container="avi")]])
        rows = [line for line in text.splitlines() if line.startswith("| ") and "direct-play" in line]
        self.assertEqual(len(rows), 3)

    def test_aRunIsStampedFromMeta(self):
        text = render.render([[meta(), result()]])
        self.assertIn("AFTMM", text)
        self.assertIn("API 25", text)
        self.assertIn("12.1.0", text)
        self.assertIn("0.3.0", text)
        self.assertIn("passthrough=ac3;channels=6", text)
        self.assertIn("non-debuggable", text)

    def test_aSectionPerSectionAndDevice(self):
        files = [
            [meta(), result(section="synthetic")],
            [meta(), result(section="real")],
            [meta(device="Other"), result(section="synthetic")],
        ]
        headings = [line for line in render.render(files).splitlines() if line.startswith("## ")]
        self.assertEqual(len(headings), 3)

    def test_theIntroSaysTimingsAreMeasuredNotGraded(self):
        text = render.render([[meta(), result()]])
        self.assertIn("not graded", text)

    def test_anIdShapedValueFailsTheRender(self):
        with self.assertRaises(render.Leak):
            render.render([[meta(), result(video="0123456789abcdef0123456789abcdef")]])

    def test_aUrlFailsTheRender(self):
        with self.assertRaises(render.Leak):
            render.render([[meta(), result(error="failed:http://host")]])

    def test_aSlashApiKeyFailsTheRender(self):
        for bad in ("a/b", "api_key=x", "xx api_key"):
            with self.assertRaises(render.Leak, msg=bad):
                render.render([[meta(), result(error=bad)]])

    def test_anIpAddressFailsTheRender(self):
        for bad in ("192.168.1.20", "fe80::1", "2001:db8:0:0:0:0:0:1", "::1"):
            with self.assertRaises(render.Leak, msg=bad):
                render.render([[meta(), result(error=bad)]])
        with self.assertRaises(render.Leak):
            render.render([[meta(jellyfin="10.0.0.5"), result()]])

    def test_aHostPortFailsTheRender(self):
        for bad in ("media.lan:8096", "tv:80", "failed:box:8920"):
            with self.assertRaises(render.Leak, msg=bad):
                render.render([[meta(), result(error=bad)]])

    def test_ordinaryDiagnosticsAreNotMistakenForLeaks(self):
        text = render.render([[meta(), result(error="failed:player:ERROR_CODE_IO_BAD_HTTP_STATUS (HTTP 404)")]])
        self.assertIn("ERROR_CODE_IO_BAD_HTTP_STATUS", text)

    def test_theCommandLineExitsOneOnALeakAndPrintsNothing(self):
        import io
        import os
        import tempfile
        from contextlib import redirect_stderr, redirect_stdout

        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "a.jsonl")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write("\n".join([meta(), result(error="a/b")]) + "\n")
            out, err = io.StringIO(), io.StringIO()
            with redirect_stdout(out), redirect_stderr(err):
                code = render.main([path])
            self.assertEqual(code, 1)
            self.assertEqual(out.getvalue(), "")
            self.assertNotIn("a/b", err.getvalue())


if __name__ == "__main__":
    unittest.main()
