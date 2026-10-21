# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
"""Tests for run.py, the playback matrix driver.

Run: python3 -I -m unittest discover -s scripts/playback-matrix -p '*_test.py'
"""

import json
import unittest

import run

HEX = "0123456789abcdef0123456789abcdef"


def real(runtime_ms, **extra):
    return {"id": HEX, "runtimeMs": runtime_ms, **extra}


class PlanTest(unittest.TestCase):
    def test_startPointsAreWithinTheWindow(self):
        runtime = 3_600_000
        items = {"real": [real(runtime) for _ in range(200)], "synthetic": []}
        plan = run.make_plan(items, seed=7)
        starts = [p["startMs"] for p in plan["real"]]
        self.assertTrue(all(runtime * 0.10 <= s <= runtime - 30_000 for s in starts), starts)
        self.assertGreater(len(set(starts)), 100)

    def test_shortItemsStartAtZero(self):
        items = {"real": [real(119_999), real(None), real(0), real(120_000)], "synthetic": []}
        starts = [p["startMs"] for p in run.make_plan(items, seed=1)["real"]]
        self.assertEqual(starts[:3], [0, 0, 0])
        self.assertGreaterEqual(starts[3], 12_000)
        self.assertLessEqual(starts[3], 90_000)

    def test_theSameSeedGivesTheSamePlan(self):
        items = {"real": [real(1_000_000 + n) for n in range(10)], "synthetic": []}
        self.assertEqual(run.make_plan(items, seed=5), run.make_plan(items, seed=5))
        self.assertNotEqual(run.make_plan(items, seed=5), run.make_plan(items, seed=6))

    def test_syntheticItemsStartAtThirtySeconds(self):
        items = {
            "synthetic": [{"id": HEX, "runtimeMs": None}, {"id": HEX, "runtimeMs": 60_000}, {"id": HEX}],
            "real": [],
        }
        for seed in (1, 2):
            starts = [p["startMs"] for p in run.make_plan(items, seed=seed)["synthetic"]]
            self.assertEqual(starts, [30_000, 30_000, 30_000])

    def test_trackChoicesAreCarriedAndNothingElse(self):
        items = {"real": [real(600_000, audio=1, subtitle=-1)], "synthetic": []}
        entry = run.make_plan(items, seed=1)["real"][0]
        self.assertEqual(entry["item"], HEX)
        self.assertEqual((entry["audio"], entry["subtitle"]), (1, -1))
        self.assertNotIn("runtimeMs", entry)

    def test_aBadItemsFileIsRefused(self):
        for bad in ({"real": [{"runtimeMs": 5}]}, {"real": "x"}, {"real": [real(-1)]}):
            with self.assertRaises(ValueError):
                run.make_plan(bad, seed=1)
        with self.assertRaises(ValueError):
            run.make_plan({"real": [real(1_000_000)] * 201}, seed=1)


class CommandTest(unittest.TestCase):
    def test_theStartCommandUsesTheExtrasTheActivityReads(self):
        batch = [
            {"item": "aaa", "startMs": 5, "audio": None, "subtitle": None},
            {"item": "bbb", "startMs": 0, "audio": None, "subtitle": None},
        ]
        cmd = run.start_command("SER", batch, section="real", run_id="r1", override=False)
        self.assertEqual(cmd[:3], ["adb", "-s", "SER"])
        self.assertIn("tv.stationbreak.app.benchmark/tv.stationbreak.app.bench.PlaybackTestActivity", cmd)
        text = " ".join(cmd)
        self.assertIn("--esa items aaa,bbb", text)
        self.assertIn("--ela starts_ms 5,0", text)
        self.assertIn("--ez override false", text)
        self.assertIn("--es section real", text)
        self.assertIn("--es run_id r1", text)

    def test_anOverrideBatchSendsTrackIndexes(self):
        batch = [{"item": "aaa", "startMs": 5, "audio": 2, "subtitle": None}]
        text = " ".join(run.start_command("S", batch, "real", "r", override=True))
        self.assertIn("--eia audio 2", text)
        self.assertIn("--eia subtitles -1", text)
        self.assertIn("--ez override true", text)

    def test_batchesSplitOnOverrideAndKeepOrder(self):
        plain = {"item": "a", "startMs": 0, "audio": None, "subtitle": None}
        over = {"item": "b", "startMs": 0, "audio": 1, "subtitle": None}
        batches = run.split_batches([plain, plain, over, plain])
        self.assertEqual([(o, len(b)) for o, b in batches], [(False, 2), (True, 1), (False, 1)])

    def test_aTooLongLabelIsRefused(self):
        item = [{"item": "a", "startMs": 0}]
        with self.assertRaises(ValueError):
            run.start_command("S", item, "x" * 65, "r", override=False)
        with self.assertRaises(ValueError):
            run.start_command("S", item, "real", "r" * 65, override=False)

    def test_timeoutIsSixtySecondsAnItemPlusSixty(self):
        self.assertEqual(run.timeout_seconds(10), 660)


class LogTest(unittest.TestCase):
    def test_linesAreCollectedUntilDone(self):
        lines = [
            'META {"device":"AFTMM"}',
            "noise",
            'RESULT {"pass":true}',
            'RESULT {"pass":false}',
            "DONE r1",
            "RESULT {}",
        ]
        got = run.collect(iter(lines))
        self.assertEqual(got.meta, 'META {"device":"AFTMM"}')
        self.assertEqual(len(got.results), 2)
        self.assertTrue(got.done)
        self.assertEqual(got.errors, [])

    def test_errorsAreKept(self):
        got = run.collect(iter(["ERROR bad-args", "DONE none"]))
        self.assertEqual(got.errors, ["ERROR bad-args"])
        self.assertEqual(got.results, [])

    def test_aStreamThatEndsBeforeDoneIsNotDone(self):
        self.assertFalse(run.collect(iter(["RESULT {}"])).done)

    def test_theMessageAfterTheLogcatPrefixIsUsed(self):
        got = run.collect(iter(['I/SBPLAY( 123): RESULT {"a":1}', "I/SBPLAY( 123): DONE r"]))
        self.assertEqual(got.results, ['RESULT {"a":1}'])
        self.assertTrue(got.done)

    def test_theResultsFileNameUsesDateModelAndSection(self):
        meta = "META " + json.dumps({"device": "AFT MM/../x"})
        name = run.results_name("2026-10-21", meta, "real")
        self.assertNotIn("/", name)
        self.assertTrue(name.startswith("2026-10-21-") and name.endswith("-real.jsonl"))


class ShortfallTest(unittest.TestCase):
    def test_aMissingDoneReportsHowManyResultsArrived(self):
        message = run.shortfall_message(done=False, arrived=3, expected=5, errors=[])
        self.assertIn("3 of 5", message)
        self.assertIn("RESULT", message)

    def test_aCompleteRunHasNoShortfall(self):
        self.assertIsNone(run.shortfall_message(done=True, arrived=5, expected=5, errors=[]))


if __name__ == "__main__":
    unittest.main()
