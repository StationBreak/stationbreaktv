# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
"""Tests for run.py, the playback matrix driver.

Run: python3 -I -m unittest discover -s scripts/playback-matrix -p '*_test.py'
"""

import json
import os
import re
import tempfile
import time
import unittest
from unittest import mock

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

    def test_onlyTheNamedSyntheticClipIsExpectedToMissItsSeek(self):
        items = {
            "synthetic": [
                {"id": HEX, "name": "h264-aac-cues"},
                {"id": HEX, "name": "h264-aac-nocues"},
                {"id": HEX},
            ],
            "real": [real(600_000, name="h264-aac-nocues")],
        }
        plan = run.make_plan(items, seed=1)
        self.assertEqual([p["seekMissExpected"] for p in plan["synthetic"]], [False, True, False])
        self.assertEqual([p["seekMissExpected"] for p in plan["real"]], [False])

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


class PlanValidationTest(unittest.TestCase):
    """A plan file is read back from disk, so it is checked again with the rules make_plan applies."""

    def entry(self, **change):
        return {"item": "aaa", "startMs": 5, "audio": None, "subtitle": None, **change}

    def test_aTamperedTrackIndexIsRefusedBeforeAdb(self):
        for field, value in (("audio", "1;reboot"), ("subtitle", "$(id)"), ("audio", -2), ("subtitle", True)):
            with self.assertRaises(ValueError, msg=(field, value)):
                run.start_command("S", [self.entry(**{field: value})], "real", "r", override=True)

    def test_aTamperedStartOrItemIsRefused(self):
        for change in ({"startMs": -1}, {"startMs": "5;reboot"}, {"startMs": 1.5}, {"startMs": True}, {"item": "a b"}):
            with self.assertRaises(ValueError, msg=change):
                run.validate_entry(self.entry(**change))

    def test_validEntriesPass(self):
        run.validate_entry(self.entry())
        run.validate_entry(self.entry(audio=-1, subtitle=3, startMs=0))

    def test_commandRunRefusesATamperedPlanWithoutCallingAdb(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "plan.json")
            plan = {"real": [self.entry(), self.entry(audio="1;reboot")]}
            with open(path, "w", encoding="utf-8") as handle:
                json.dump(plan, handle)
            args = mock.Mock(plan=path, section="real", serial="S", results_dir=tmp)
            with mock.patch.object(run.subprocess, "run") as adb, mock.patch.object(run.subprocess, "Popen") as popen:
                with self.assertRaises(ValueError):
                    run.command_run(args)
            adb.assert_not_called()
            popen.assert_not_called()


class ForgeryTest(unittest.TestCase):
    def test_everyRunIdCarriesAFreshNonce(self):
        first = run.new_run_id("real", "2026-10-21", 1)
        second = run.new_run_id("real", "2026-10-21", 1)
        self.assertNotEqual(first, second)
        self.assertTrue(first.startswith("real-2026-10-21-1-"))
        self.assertRegex(first, run.SAFE)
        self.assertLessEqual(len(first), run.MAX_LABEL)

    def test_logcatIsFilteredToTheAppsProcess(self):
        cmd = run.logcat_command("SER", 4242)
        self.assertEqual(cmd[:4], ["adb", "-s", "SER", "logcat"])
        self.assertIn("--pid=4242", cmd)
        self.assertEqual(cmd[-2:], ["-s", "SBPLAY:I"])

    def test_aPidThatIsNotAnIntegerIsRefused(self):
        for bad in ("1;reboot", None, True, 0, -3):
            with self.assertRaises(ValueError, msg=bad):
                run.logcat_command("S", bad)

    def test_pidofOutputIsParsed(self):
        self.assertEqual(run.parse_pid("4242\r\n"), 4242)
        self.assertEqual(run.parse_pid("4242 77\n"), 4242)
        self.assertIsNone(run.parse_pid(""))
        self.assertIsNone(run.parse_pid("not a pid"))

    def test_theCommandThatAsksForThePidNamesTheBenchmarkApp(self):
        self.assertEqual(run.pid_command("S"), ["adb", "-s", "S", "shell", "pidof", "tv.stationbreak.app.benchmark"])


class LogTest(unittest.TestCase):
    def test_linesAreCollectedUntilDone(self):
        lines = [
            'META {"device":"AFTMM"}',
            "noise",
            'RESULT {"run_id":"r1","pass":true}',
            'RESULT {"run_id":"r1","pass":false}',
            "DONE r1",
            "RESULT {}",
        ]
        got = run.collect(iter(lines), "r1")
        self.assertEqual(got.meta, 'META {"device":"AFTMM"}')
        self.assertEqual(len(got.results), 2)
        self.assertTrue(got.done)
        self.assertEqual(got.errors, [])

    def test_errorsAreKept(self):
        got = run.collect(iter(["ERROR bad-args", "DONE none"]), "r1")
        self.assertEqual(got.errors, ["ERROR bad-args"])
        self.assertEqual(got.results, [])

    def test_aStreamThatEndsBeforeDoneIsNotDone(self):
        self.assertFalse(run.collect(iter(['RESULT {"run_id":"r1"}']), "r1").done)

    def test_theMessageAfterTheLogcatPrefixIsUsed(self):
        got = run.collect(iter(['I/SBPLAY( 123): RESULT {"run_id":"r1"}', "I/SBPLAY( 123): DONE r1"]), "r1")
        self.assertEqual(got.results, ['RESULT {"run_id":"r1"}'])
        self.assertTrue(got.done)

    def test_theResultsFileNameUsesDateModelAndSection(self):
        meta = "META " + json.dumps({"device": "AFT MM/../x"})
        name = run.results_name("2026-10-21", meta, "real")
        self.assertNotIn("/", name)
        self.assertTrue(name.startswith("2026-10-21-") and name.endswith("-real.jsonl"))


    def test_aResultOfAnotherRunIsLeftOut(self):
        lines = ['RESULT {"run_id":"old","n":0}', 'RESULT {"run_id":"r1","n":0}', "RESULT {}", "DONE r1"]
        self.assertEqual(run.collect(iter(lines), "r1").results, ['RESULT {"run_id":"r1","n":0}'])

    def test_aResultThatIsNotJsonIsKeptForMarkingToReport(self):
        self.assertEqual(run.collect(iter(["RESULT {oops", "DONE r1"]), "r1").results, ["RESULT {oops"])


def line(n, run_id="r1", **fields):
    return "RESULT " + json.dumps({"run_id": run_id, "n": n, "fallback": "no", "pass": True, **fields})


def planned(*expected):
    return [{"seekMissExpected": e} for e in expected]


class MarkTest(unittest.TestCase):
    def mark(self, batch, lines, section="synthetic"):
        marked, problems = run.mark_expected(batch, lines, "r1", section)
        return [json.loads(m[len("RESULT "):]) for m in marked], problems

    def test_resultsArePairedByOrdinalWhateverTheOrder(self):
        marked, problems = self.mark(planned(False, True), [line(1), line(0)])
        self.assertEqual([(m["n"], m["seekMissExpected"]) for m in marked], [(1, True), (0, False)])
        self.assertEqual(problems, [])

    def test_aMalformedLineLeavesTheBatchUnmarkedAndIsReported(self):
        lines = [line(0), "RESULT {oops"]
        marked, problems = run.mark_expected(planned(False, False), lines, "r1", "synthetic")
        self.assertEqual(marked, lines)
        self.assertEqual(len(problems), 1)

    def test_aNonObjectLineLeavesTheBatchUnmarked(self):
        marked, problems = run.mark_expected(planned(False), ["RESULT [1]"], "r1", "synthetic")
        self.assertEqual(marked, ["RESULT [1]"])
        self.assertTrue(problems)

    def test_aMissingOrRepeatedOrdinalLeavesTheBatchUnmarked(self):
        for lines in (
            [line(0), line(0)],
            [line(0), line(2)],
            [line(0)],
            [line(0), "RESULT " + json.dumps({"run_id": "r1", "fallback": "no"})],
            [line(0), line(True)],
        ):
            marked, problems = run.mark_expected(planned(True, True), lines, "r1", "synthetic")
            self.assertEqual(marked, lines, msg=lines)
            self.assertTrue(problems, msg=lines)
            self.assertNotIn("seekMissExpected", marked[0])

    def test_aLineFromAnotherRunLeavesTheBatchUnmarked(self):
        lines = [line(0), line(1, run_id="old")]
        marked, problems = run.mark_expected(planned(False, False), lines, "r1", "synthetic")
        self.assertEqual(marked, lines)
        self.assertTrue(problems)

    def test_anUnexpectedSeekMissInTheSyntheticSectionIsRewrittenAsAFailure(self):
        misses = [line(0, fallback="seek-miss"), line(1, fallback="seek-miss")]
        marked, problems = self.mark(planned(False, True), misses)
        self.assertEqual((marked[0]["pass"], marked[0]["error"]), (False, "unexpected-fallback"))
        self.assertTrue(marked[1]["pass"])
        self.assertEqual(len(problems), 1)

    def test_aSeekMissInTheRealSectionIsLeftAlone(self):
        marked, problems = self.mark(planned(False), [line(0, fallback="seek-miss")], section="real")
        self.assertTrue(marked[0]["pass"])
        self.assertEqual(problems, [])


class SyntheticNamesTest(unittest.TestCase):
    def test_everyExpectedSeekMissClipIsAClipTheScriptMakes(self):
        script = os.path.join(os.path.dirname(os.path.abspath(run.__file__)), "synthetic-media.sh")
        with open(script, encoding="utf-8") as handle:
            slots = set(re.findall(r"\$\(slot ([A-Za-z0-9._-]+) ", handle.read()))
        for name in run.SEEK_MISS_CLIPS:
            self.assertIn(name, slots)


class ReaderTest(unittest.TestCase):
    def test_everyLineOfOneWriteArrivesWithoutWaitingForTheTimeout(self):
        read_end, write_end = os.pipe()
        os.write(write_end, b'META {}\nRESULT {"run_id":"r1"}\nRESULT {"run_id":"r1"}\nDONE r1\n')
        started = time.monotonic()
        got = run.collect(run.read_lines(read_end, time.monotonic() + 30), "r1")
        self.assertLess(time.monotonic() - started, 5)
        self.assertTrue(got.done)
        self.assertEqual(len(got.results), 2)
        os.close(read_end)
        os.close(write_end)

    def test_aLastLineWithoutANewlineIsKeptAtEndOfInput(self):
        read_end, write_end = os.pipe()
        os.write(write_end, b"RESULT {}\nDONE r1")
        os.close(write_end)
        self.assertEqual(list(run.read_lines(read_end, time.monotonic() + 30)), ["RESULT {}", "DONE r1"])
        os.close(read_end)

    def test_aLineSplitAcrossWritesIsJoined(self):
        read_end, write_end = os.pipe()
        os.write(write_end, b'RESULT {"run_id"')
        os.write(write_end, b':"r1"}\nDONE r1\n')
        got = run.collect(run.read_lines(read_end, time.monotonic() + 30), "r1")
        self.assertEqual(got.results, ['RESULT {"run_id":"r1"}'])
        os.close(read_end)
        os.close(write_end)

    def test_noDataEndsAtTheDeadline(self):
        read_end, write_end = os.pipe()
        started = time.monotonic()
        got = run.collect(run.read_lines(read_end, time.monotonic() + 1.2), "r1")
        self.assertFalse(got.done)
        self.assertLess(time.monotonic() - started, 5)
        os.close(read_end)
        os.close(write_end)

    def test_aStaleDoneFromAnotherRunIsIgnored(self):
        got = run.collect(iter(["DONE old", "RESULT {}", "DONE r1"]), "r1")
        self.assertEqual(got.results, [])
        self.assertTrue(got.done)
        self.assertFalse(run.collect(iter(["DONE old"]), "r1").done)


class DefaultsTest(unittest.TestCase):
    def test_defaultFilesLiveInTheIgnoredDirectory(self):
        here = os.path.dirname(os.path.abspath(run.__file__))
        for path in (run.DEFAULT_ITEMS, run.DEFAULT_PLAN):
            self.assertEqual(os.path.dirname(path), here)

    def test_anExistingFileIsMadePrivateWhenWritten(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "plan.json")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write("old")
            os.chmod(path, 0o644)
            run.write_private(path, "new")
            self.assertEqual(os.stat(path).st_mode & 0o777, 0o600)
            with open(path, encoding="utf-8") as handle:
                self.assertEqual(handle.read(), "new")


class SetupCommandTest(unittest.TestCase):
    def test_theSetupCommandUsesTheExtrasTheActivityReads(self):
        cmd = run.setup_command("SER", "setup", "https://media.example.invalid:8920/jf")
        self.assertEqual(cmd[:6], ["adb", "-s", "SER", "shell", "am", "start"])
        self.assertIn("tv.stationbreak.app.benchmark/tv.stationbreak.app.bench.BenchSetupActivity", cmd)
        text = " ".join(cmd)
        self.assertIn("--es action setup", text)
        self.assertIn("--es server https://media.example.invalid:8920/jf", text)

    def test_theSignoutCommandSendsNoServer(self):
        text = " ".join(run.setup_command("SER", "signout"))
        self.assertIn("--es action signout", text)
        self.assertNotIn("server", text)

    def test_anAddressThatCouldReachAShellIsRefused(self):
        for bad in (None, "", "a b", "host;reboot", "host$(id)", "host`id`", "h'x", 'h"x', "a|b", "a&b", "x" * 513):
            with self.assertRaises(ValueError, msg=repr(bad)):
                run.setup_command("SER", "setup", bad)

    def test_anUnknownActionIsRefused(self):
        with self.assertRaises(ValueError):
            run.setup_command("SER", "wipe")


class SetupLineTest(unittest.TestCase):
    def test_theLinesTheAppPrintsAreRecognised(self):
        parse = run.parse_setup_line
        self.assertEqual(parse("QC 123456", "setup"), run.SetupLine("qc", "123456"))
        self.assertEqual(parse("I/SBPLAY  ( 123): QC 123456", "setup"), run.SetupLine("qc", "123456"))
        self.assertEqual(parse("SETUP OK", "setup"), run.SetupLine("ok", ""))
        self.assertEqual(parse("SETUP ERROR qc-denied", "setup"), run.SetupLine("error", "qc-denied"))
        self.assertEqual(parse("SIGNOUT OK revoked", "signout"), run.SetupLine("ok", "revoked"))
        self.assertEqual(parse("SIGNOUT OK not-revoked", "signout"), run.SetupLine("ok", "not-revoked"))
        self.assertEqual(parse("SIGNOUT ERROR no-session", "signout"), run.SetupLine("error", "no-session"))
        self.assertEqual(parse("ERROR adb-disabled", "setup"), run.SetupLine("error", "adb-disabled"))

    def test_otherLinesAreIgnored(self):
        parse = run.parse_setup_line
        for line in ("", "META {}", "RESULT {}", "DONE r1", "QC", "QC a b", "QC 12 34", "SETUP", "SETUP MAYBE",
                     "SETUP ERROR", "SETUP ERROR Bad Reason", "SIGNOUT OK maybe", "SETUP OK extra", "SETUP ERROR a b"):
            self.assertIsNone(parse(line, "setup"), line)

    def test_aLineForTheOtherActionIsNotTakenAsTheAnswer(self):
        self.assertIsNone(run.parse_setup_line("SIGNOUT OK revoked", "setup"))
        self.assertIsNone(run.parse_setup_line("SETUP OK", "signout"))
        self.assertIsNone(run.parse_setup_line("QC 123456", "signout"))


class FollowSetupTest(unittest.TestCase):
    def test_theCodeIsShownThenOkExitsZero(self):
        codes = []
        status, message = run.follow_setup(iter(["noise", "QC 123456", "SETUP OK"]), "setup", codes.append)
        self.assertEqual((status, codes), (0, ["123456"]))
        self.assertIn("ok", message)

    def test_anErrorExitsNonZeroWithItsReason(self):
        status, message = run.follow_setup(iter(["QC 123456", "SETUP ERROR qc-expired"]), "setup", lambda code: None)
        self.assertEqual(status, 1)
        self.assertIn("qc-expired", message)

    def test_linesThatEndWithoutAnAnswerAreATimeout(self):
        status, message = run.follow_setup(iter(["QC 123456"]), "setup", lambda code: None)
        self.assertEqual(status, 1)
        self.assertIn("no answer", message)

    def test_signoutReportsWhetherTheTokenWasRevoked(self):
        self.assertEqual(run.follow_setup(iter(["SIGNOUT OK revoked"]), "signout")[0], 0)
        self.assertIn("not-revoked", run.follow_setup(iter(["SIGNOUT OK not-revoked"]), "signout")[1])
        self.assertEqual(run.follow_setup(iter(["SIGNOUT ERROR no-session"]), "signout")[0], 1)

    def test_theLinesAfterTheAnswerAreNotRead(self):
        lines = iter(["SETUP OK", "SETUP ERROR late"])
        self.assertEqual(run.follow_setup(lines, "setup")[0], 0)


class SetupMainTest(unittest.TestCase):
    def test_setupWithoutAServerExitsTwoBeforeAnyAdbCall(self):
        with mock.patch.dict(os.environ, {}, clear=False), mock.patch("subprocess.run") as adb:
            os.environ.pop(run.SERVER_ENV, None)
            self.assertEqual(run.main(["setup", "-s", "SER"]), 2)
            adb.assert_not_called()

    def test_theServerCanComeFromTheEnvironment(self):
        env = {run.SERVER_ENV: "media.example.invalid"}
        with mock.patch.dict(os.environ, env), mock.patch("subprocess.run") as adb, mock.patch(
            "subprocess.Popen"
        ), mock.patch.object(run, "_wait_for_pid", return_value=42), mock.patch.object(
            run, "read_lines", return_value=iter(["SETUP OK"])
        ):
            adb.return_value.stdout = ""
            self.assertEqual(run.main(["setup", "-s", "SER"]), 0)
        started = [call.args[0] for call in adb.call_args_list if "BenchSetupActivity" in " ".join(call.args[0])]
        self.assertIn("media.example.invalid", started[0])

    def test_anErrorLineMakesTheCommandExitNonZero(self):
        with mock.patch("subprocess.run") as adb, mock.patch("subprocess.Popen"), mock.patch.object(
            run, "_wait_for_pid", return_value=42
        ), mock.patch.object(run, "read_lines", return_value=iter(["SIGNOUT ERROR no-session"])):
            adb.return_value.stdout = ""
            self.assertEqual(run.main(["signout", "-s", "SER"]), 1)

    def test_aBadServerExitsTwo(self):
        with mock.patch("subprocess.run") as adb:
            self.assertEqual(run.main(["setup", "-s", "SER", "--server", "a;b"]), 2)
            adb.assert_not_called()


class ShortfallTest(unittest.TestCase):
    def test_aMissingDoneReportsHowManyResultsArrived(self):
        message = run.shortfall_message(done=False, arrived=3, expected=5, errors=[])
        self.assertIn("3 of 5", message)
        self.assertIn("RESULT", message)

    def test_aCompleteRunHasNoShortfall(self):
        self.assertIsNone(run.shortfall_message(done=True, arrived=5, expected=5, errors=[]))


if __name__ == "__main__":
    unittest.main()
