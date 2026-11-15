# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
"""Tests for the audit exporter: the acceptance rules, the fixture, the fake server and the safety rules.

Run: python3 -I -m unittest discover -s tools/audit-export -p '*_test.py'

Nothing here contacts a real server: the exporter runs against a fake one on 127.0.0.1 that serves invented titles.
"""

import contextlib
import copy
import datetime
import io
import json
import os
import stat
import tempfile
import threading
import time
import unittest
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

import acceptance
import export

FIXTURES = Path(__file__).resolve().parent / "fixtures"
KEY = "fake-test-key-not-real"
CUTOFF = 1_800_000_000_000


def load(name):
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


class VectorTest(unittest.TestCase):
    """The same vectors as the app's own tests (data:jellyfin PremiereDateTest and InputMappingPropertyTest)."""

    def test_premiereDateIsTheUtcCalendarDate(self):
        d = acceptance.premiere_date
        self.assertEqual("2001-03-05", d("2001-03-04T23:30:00-05:00"))
        self.assertEqual("2001-03-03", d("2001-03-04T00:30:00+05:30"))
        self.assertEqual("2020-01-01", d("2020-01-01T00:00:00Z"))
        self.assertEqual("2019-12-31", d("2019-12-31T23:59:59.9999999Z"))
        self.assertEqual("1999-12-31", d("1999-12-31T00:00:00.0000000Z"))
        self.assertEqual("1969-07-20", d("1969-07-20T20:17:00Z"))
        self.assertEqual("1923-01-01", d("1923-01-01T00:00:00.0000000Z"))
        self.assertEqual("2001-03-04", d("2001-03-04T00:00:00"))
        self.assertEqual("2001-03-04", d("2001-03-04T23:59:59.1234567"))

    def test_aMissingOrMalformedPremiereDateIsNone(self):
        d = acceptance.premiere_date
        for text in (None, "not a date", "", "2001-13-45T00:00:00Z", "2001-03-04", 5):
            self.assertIsNone(d(text), repr(text))

    def test_aYearOutsideOneToNineNineNineNineIsNone(self):
        d = acceptance.premiere_date
        for text in (
            "+999999999-12-31T23:59:59-18:00",
            "+10000-01-01T00:00:00Z",
            "9999-12-31T23:59:59-05:00",
            "-0001-06-01T00:00:00Z",
            "0000-06-01T00:00:00Z",
            "0001-01-01T00:00:00+01:00",
        ):
            self.assertIsNone(d(text), text)
        self.assertEqual("0001-01-01", d("0001-01-01T00:00:00Z"))
        self.assertEqual("9999-12-31", d("9999-12-31T23:59:59Z"))

    def test_createdAtIsTheTicksFlooredToMilliseconds(self):
        c = acceptance.created_at_ms
        self.assertEqual(1767323045123, c("2026-01-02T03:04:05.1239999Z"))
        self.assertEqual(1767323045123, c("2026-01-02T03:04:05.1239999"))
        self.assertEqual(1767323045123, c("2026-01-02T04:04:05.1239999+01:00"))
        self.assertEqual(-1_000, c("1969-12-31T23:59:59Z"))
        # Before 1970 it is a floor, not a truncation: a quarter of a second before the epoch is -250 ms.
        self.assertEqual(-250, c("1969-12-31T23:59:59.75Z"))

    def test_aDateBeyondWhatMillisecondsHoldIsNoneAndNeverThrows(self):
        for text in ("+999999999-12-31T23:59:59Z", "-999999999-01-01T00:00:00Z", "+999999999-12-31T23:59:59-18:00"):
            self.assertIsNone(acceptance.created_at_ms(text), text)
        for text in (None, "", "yesterday", "2026-02-30T00:00:00Z", "2026-01-01T24:00:00Z", "2026-01-01T00:00:00+19:00"):
            self.assertIsNone(acceptance.created_at_ms(text), repr(text))

    def test_theRuntimeIsFloorDivOfTheTicksOrAbsent(self):
        r = acceptance.runtime_ms
        self.assertEqual(0, r(9_999))
        self.assertEqual(1, r(10_000))
        self.assertEqual(3_600_000, r(36_000_000_005))
        for ticks in (None, 0, -5, True, 1.5):
            self.assertIsNone(r(ticks), repr(ticks))

    def test_canonicalIdsAreNormalised(self):
        c = acceptance.canonical_id
        self.assertEqual("a" * 32, c("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"))
        self.assertEqual("a" * 32, c("{" + "a" * 32 + "}"))
        for raw in (None, "", "xyz", "a" * 31, "a" * 33, "g" * 32, 5):
            self.assertIsNone(c(raw), repr(raw))

    def test_studioNamesAreCleanedAsTheAppCleansThem(self):
        names = acceptance.studios_of(
            [{"Name": " A​ B C\t "}, {"Name": None}, {"Name": ""}, None, {"Name": "​"}, {"Name": "x" * 257}],
            acceptance.Counts(),
        )
        self.assertEqual(["A B C"], names)
        many = acceptance.studios_of([{"Name": f"S{i}"} for i in range(20)], acceptance.Counts())
        self.assertEqual(16, len(many))


class FixtureTest(unittest.TestCase):
    def test_theFixtureRowsAreWhatTheAnswersBecome(self):
        server, rows = load("server.json"), load("rows.json")
        counts = acceptance.Counts()
        made = []
        for library in sorted(server["items"], key=acceptance.canonical_id):
            made += [acceptance.held_row(d, library, counts) for d in server["items"][library]]
        self.assertEqual(rows["rows"], made)

    def test_theFixtureSnapshotIsWhatTheRowsBecome(self):
        rows = load("rows.json")
        snapshot, dropped = acceptance.build_snapshot(
            rows["rows"], rows["series"], rows["collections"], rows["libraries"], rows["cutoffMs"], rows["collectionsSynced"]
        )
        self.assertEqual(load("expected-snapshot.json"), snapshot)
        # Each reason of the as-of read that the fixture holds, and the app's own read counts the same (data:library).
        self.assertEqual(load("expected-dropped.json"), dict(dropped))

    def test_theWrittenFileIsTheFixtureFileByteForByte(self):
        self.assertEqual((FIXTURES / "expected-snapshot.json").read_text(encoding="utf-8"), export.layout(load("expected-snapshot.json")))

    def test_aSeasonThatIsNotCanonicalReadsAsNoSeasonAndAnUnheldSeriesHasNoEntry(self):
        snapshot = load("expected-snapshot.json")
        second = next(i for i in snapshot["items"] if i["name"] == "Second")
        self.assertIsNone(second["seasonId"])
        series_ids = [s["id"] for s in snapshot["series"]]
        self.assertEqual(["5" * 28 + "0001"], series_ids)  # not the unwatched series, not the missing one

    def test_theCollectionKeepsOnlyTheFourKindsOfMemberItsCanonicalIdsAndNoDuplicates(self):
        collection = load("expected-snapshot.json")["collections"][0]
        # A movie (listed twice, one spelling), an episode, a series and a season; a BoxSet, a photo and a member whose
        # ID is not canonical are left out.
        for kind in ("movies", "episodes", "series", "seasons"):
            self.assertEqual(1, len(collection[kind]), kind)


class Handler(BaseHTTPRequestHandler):
    data = None
    mode = ""

    def log_message(self, *args):  # the fake server says nothing
        pass

    def _send(self, status, body, headers=()):
        self.send_response(status)
        for k, v in headers:
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if f'Token="{KEY}"' not in self.headers.get("Authorization", ""):
            return self._send(401, b"{}")
        if self.mode == "garbage":
            self.wfile.write(b"NOT HTTP AT ALL\r\n\r\n")
            return
        if self.mode == "truncated":
            self.wfile.write(b"HTTP/1.1 200 OK\r\nContent-Length: 500\r\n\r\n{")
            return
        if self.mode == "redirect":
            return self._send(302, b"", [("Location", "http://127.0.0.1:1/elsewhere")])
        if self.mode == "huge":
            return self._send(200, b" " * (export.MAX_BODY_BYTES + 10))
        if self.mode == "duplicate-keys":
            return self._send(200, b'{"Items": [], "Items": [], "TotalRecordCount": 0}')
        url = urllib.parse.urlsplit(self.path)
        query = {k: v[0] for k, v in urllib.parse.parse_qs(url.query).items()}
        data = self.data
        if url.path.endswith("/Library/VirtualFolders"):
            return self._json(data["virtualFolders"])
        if not url.path.endswith("/Items"):
            return self._send(404, b"{}")
        return self._json(self._items(data, query))

    def _items(self, data, q):
        parent, kinds = q.get("parentId"), q.get("includeItemTypes")
        if kinds == "BoxSet":
            rows = data["boxsets"]
        elif parent in data["members"]:
            rows = data["members"][parent]
        elif kinds == "Series":
            rows = data["series"].get(parent, [])
        else:
            rows = data["items"].get(parent, [])
        start, limit = int(q["startIndex"]), int(q["limit"])
        page = {"Items": rows[start : start + limit], "StartIndex": start}
        if start == 0 and q.get("enableTotalRecordCount") == "true":
            page["TotalRecordCount"] = len(rows)
        if self.mode == "wrong-type":
            for item in page["Items"]:
                item["ProductionYear"] = "1999"
        return page

    def _json(self, value):
        self._send(200, json.dumps(value).encode("utf-8"), [("Content-Type", "application/json")])


class FakeServer:
    def __init__(self, data, mode=""):
        handler = type("H", (Handler,), {"data": data, "mode": mode})
        self._http = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.address = f"http://127.0.0.1:{self._http.server_address[1]}"
        self._thread = threading.Thread(target=self._http.serve_forever, daemon=True)

    def __enter__(self):
        self._thread.start()
        return self

    def __exit__(self, *exc):
        self._http.shutdown()
        self._http.server_close()
        self._thread.join()


class ExportAgainstAFakeServerTest(unittest.TestCase):
    def run_export(self, data, mode="", only=()):
        with FakeServer(data, mode) as fake:
            return export.export(export.Server(fake.address, KEY), list(only), CUTOFF)

    def test_theExporterMakesTheFixtureSnapshotFromTheFixtureServer(self):
        snapshot, counts = self.run_export(load("server.json"))
        self.assertEqual(load("expected-snapshot.json"), snapshot)
        self.assertEqual(len(snapshot["items"]), counts["items"])
        self.assertEqual(2, counts["dropped_DuplicateId"])
        self.assertEqual(1, counts["dropped_SeasonNegative"])

    def test_aLibraryCanBeChosenAndAMusicLibraryIsNeverRead(self):
        snapshot, _ = self.run_export(load("server.json"), only=["a0000000-0000-0000-0000-0000000000a2"])
        self.assertEqual(["a" + "0" * 29 + "a2"], snapshot["libraries"])
        self.assertTrue(all(i["libraryId"] == "a" + "0" * 29 + "a2" for i in snapshot["items"]))
        everything, _ = self.run_export(load("server.json"))
        self.assertNotIn("a" + "0" * 29 + "a3", everything["libraries"])

    def test_manyPagesAreRead(self):
        data = copy.deepcopy(load("server.json"))
        template = data["items"]["A0000000-0000-0000-0000-0000000000A1"][0]
        many = []
        for n in range(450):
            item = dict(template, Id=f"9999999999999999999999999999{n:04d}", Name=f"Item {n}")
            many.append(item)
        data["items"]["A0000000-0000-0000-0000-0000000000A1"] = many
        snapshot, _ = self.run_export(data, only=["A0000000-0000-0000-0000-0000000000A1"])
        self.assertEqual(450, len(snapshot["items"]))

    def test_aRedirectIsRefusedAndTheKeyGoesNowhere(self):
        with self.assertRaises(export.ExportError) as caught:
            self.run_export(load("server.json"), "redirect")
        self.assertNotIn(KEY, str(caught.exception))

    def test_anAnswerOverTheSizeLimitIsRefused(self):
        with self.assertRaises(export.ExportError):
            self.run_export(load("server.json"), "huge")

    def test_aDuplicateKeyInAnAnswerIsRefused(self):
        with self.assertRaises(export.ExportError):
            self.run_export(load("server.json"), "duplicate-keys")

    def test_aFieldOfTheWrongTypeFailsTheExportAsItFailsAPageInTheApp(self):
        with self.assertRaises(acceptance.BadAnswer):
            self.run_export(load("server.json"), "wrong-type")

    def test_aWrongKeyIsRefusedWithAStatusAndNeverItsText(self):
        with FakeServer(load("server.json")) as fake:
            wrong = "f" * 32
            with self.assertRaises(export.ExportError) as caught:
                export.export(export.Server(fake.address, wrong), [], CUTOFF)
        self.assertIn("401", str(caught.exception))
        self.assertNotIn(wrong, str(caught.exception))


class HostileServerTest(unittest.TestCase):
    def test_proxyVariablesAreIgnoredSoTheKeyNeverGoesToAProxy(self):
        dead = {"http_proxy": "http://127.0.0.1:9", "HTTP_PROXY": "http://127.0.0.1:9", "all_proxy": "http://127.0.0.1:9"}
        with mock.patch.dict(os.environ, dead):
            with FakeServer(load("server.json")) as fake:
                snapshot, _ = export.export(export.Server(fake.address, KEY), [], CUTOFF)
        self.assertEqual(load("expected-snapshot.json"), snapshot)

    def test_aBrokenHttpAnswerIsAFixedMessageWithNoServerContent(self):
        for mode in ("garbage", "truncated"):
            with FakeServer(load("server.json"), mode) as fake:
                with self.assertRaises(export.ExportError, msg=mode) as caught:
                    export.export(export.Server(fake.address, KEY), [], CUTOFF)
            self.assertNotIn("NOT HTTP", str(caught.exception))
            self.assertNotIn(fake.address, str(caught.exception))

    def test_anUnexpectedErrorPrintsOnlyItsTypeName(self):
        err = io.StringIO()
        with tempfile.TemporaryDirectory() as tmp:
            env = {"SB_AUDIT_SERVER": "http://127.0.0.1:9", "SB_AUDIT_API_KEY": KEY, "SB_AUDIT_OUT": tmp}
            with mock.patch.object(export, "export", side_effect=RuntimeError("secret-from-server")):
                self.assertEqual(1, export.main([], env, io.StringIO(), err))
        self.assertIn("RuntimeError", err.getvalue())
        self.assertNotIn("secret-from-server", err.getvalue())

    def test_aHugeNameIsCutInOneStepAndQuickly(self):
        started = time.monotonic()
        row = acceptance.held_row({"Id": "x", "Type": "Movie", "Name": "\U0001f600" * 3_000_000}, "l", acceptance.Counts())
        self.assertLess(time.monotonic() - started, 5)
        self.assertLessEqual(acceptance.u16len(row["name"]), acceptance.MAX_TEXT)
        self.assertEqual(acceptance.MAX_TEXT // 2, len(row["name"]))

    def test_aNameIsNeverCutThroughASurrogatePair(self):
        name = "a" * (acceptance.MAX_TEXT - 1) + "\U0001f600"
        row = acceptance.held_row({"Id": "x", "Type": "Movie", "Name": name}, "l", acceptance.Counts())
        self.assertEqual("a" * (acceptance.MAX_TEXT - 1), row["name"])


class EnvironmentTest(unittest.TestCase):
    def test_theServerAndTheKeyComeFromTheEnvironmentOnly(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                export.main(["--key", KEY], {}, io.StringIO(), io.StringIO())
            with self.assertRaises(SystemExit):
                export.main(["--server", "https://example.invalid"], {}, io.StringIO(), io.StringIO())

    def test_missingVariablesAreSaidWithoutAnyValue(self):
        err = io.StringIO()
        self.assertEqual(1, export.main([], {}, io.StringIO(), err))
        self.assertIn("SB_AUDIT_SERVER", err.getvalue())
        err = io.StringIO()
        self.assertEqual(1, export.main([], {"SB_AUDIT_SERVER": "https://example.invalid"}, io.StringIO(), err))
        self.assertIn("SB_AUDIT_API_KEY", err.getvalue())

    def test_theAddressIsChecked(self):
        read = export.read_server
        self.assertEqual("https://example.invalid/jf", read({"SB_AUDIT_SERVER": "https://example.invalid/jf/"}))
        for bad in ("ftp://example.invalid", "https://user:pass@example.invalid", "https://example.invalid?x=1", "https://", "example.invalid", "https://example.invalid:99999"):
            with self.assertRaises(export.ExportError, msg=bad):
                read({"SB_AUDIT_SERVER": bad})
        with self.assertRaises(export.ExportError):
            read({"SB_AUDIT_SERVER": "http://example.invalid"})
        self.assertEqual("http://192.168.1.5:8096", read({"SB_AUDIT_SERVER": "http://192.168.1.5:8096"}))
        self.assertEqual("http://example.invalid", read({"SB_AUDIT_SERVER": "http://example.invalid", "SB_AUDIT_ALLOW_CLEARTEXT": "1"}))

    def test_theKeyCannotBreakOutOfAHeader(self):
        self.assertEqual(KEY, export.read_key({"SB_AUDIT_API_KEY": KEY}))
        for bad in ('abc"def12345', "abc\r\nX: y12345", "short", "a b c d e f g h", ""):
            with self.assertRaises(export.ExportError, msg=repr(bad)):
                export.read_key({"SB_AUDIT_API_KEY": bad})

    def test_theOutputFolderIsOutsideTheRepositoryAndOutsideAnyWorkTree(self):
        root = export.repo_root()
        for inside in (root, root / "docs", root / "tools" / "audit-export" / "out"):
            with self.assertRaises(export.ExportError, msg=str(inside)):
                export.output_folder({"SB_AUDIT_OUT": str(inside)})
        with tempfile.TemporaryDirectory() as tmp:
            work_tree = Path(tmp) / "elsewhere"
            (work_tree / ".git").mkdir(parents=True)
            with self.assertRaises(export.ExportError):
                export.output_folder({"SB_AUDIT_OUT": str(work_tree / "snapshots")})
            fine = export.output_folder({"SB_AUDIT_OUT": str(Path(tmp) / "snapshots")})
            self.assertEqual(Path(tmp).resolve() / "snapshots", fine)

    def test_aFolderOthersCanWriteToOrWeDoNotOwnIsRefused(self):
        with tempfile.TemporaryDirectory() as tmp:
            open_folder = Path(tmp) / "open"
            open_folder.mkdir()
            open_folder.chmod(0o777)
            with self.assertRaises(export.ExportError):
                export.write_snapshot(open_folder, load("expected-snapshot.json"), datetime.datetime.now(datetime.timezone.utc))
            self.assertEqual([], list(open_folder.iterdir()))
            with mock.patch.object(os, "geteuid", return_value=os.geteuid() + 1):
                with self.assertRaises(export.ExportError):
                    export.write_snapshot(Path(tmp) / "mine", load("expected-snapshot.json"), datetime.datetime.now(datetime.timezone.utc))

    def test_aFailedWriteLeavesNoFileAndASuccessLeavesNoTemporaryOne(self):
        now = datetime.datetime.now(datetime.timezone.utc)
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.object(export, "layout", side_effect=ValueError("x")):
                with self.assertRaises(ValueError):
                    export.write_snapshot(Path(tmp) / "out", {}, now)
            self.assertFalse((Path(tmp) / "out").exists())
            path = export.write_snapshot(Path(tmp) / "out", load("expected-snapshot.json"), now)
            self.assertEqual([path], list((Path(tmp) / "out").iterdir()))

    def test_onlyLiteralLocalAddressesNeedNoOptInForPlainHttp(self):
        self.assertTrue(export._on_this_network("localhost"))
        self.assertTrue(export._on_this_network("nas.local"))
        self.assertTrue(export._on_this_network("10.0.0.2"))
        self.assertTrue(export._on_this_network("::1"))
        self.assertFalse(export._on_this_network("nas"))
        self.assertFalse(export._on_this_network("example.invalid"))

    def test_theDefaultFolderIsInTheUsersDataFolder(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(Path(tmp).resolve() / "stationbreak-audit", export.output_folder({"XDG_DATA_HOME": tmp}))
            self.assertEqual(Path(tmp).resolve() / ".local" / "share" / "stationbreak-audit", export.output_folder({}, home=Path(tmp)))


class CommandLineTest(unittest.TestCase):
    def test_aRunWritesOneFileOutsideTheRepositoryReadableByTheUserOnlyAndPrintsOnlyCounts(self):
        with FakeServer(load("server.json")) as fake, tempfile.TemporaryDirectory() as tmp:
            out, err = io.StringIO(), io.StringIO()
            env = {"SB_AUDIT_SERVER": fake.address, "SB_AUDIT_API_KEY": KEY, "SB_AUDIT_OUT": tmp}
            self.assertEqual(0, export.main([], env, out, err), err.getvalue())
            files = list(Path(tmp).glob("audit-snapshot-*.json"))
            self.assertEqual(1, len(files))
            self.assertEqual(0o600, stat.S_IMODE(files[0].stat().st_mode))
            written = json.loads(files[0].read_text(encoding="ascii"))
            written["cutoffMs"] = CUTOFF
            self.assertEqual(load("expected-snapshot.json"), written)
            shown = out.getvalue() + err.getvalue()
            self.assertNotIn(KEY, shown)
            self.assertNotIn(fake.address, shown)
            self.assertNotIn("Velmiren", shown)

    def test_aFailureNamesNeitherTheKeyNorTheAddress(self):
        with FakeServer(load("server.json"), "redirect") as fake, tempfile.TemporaryDirectory() as tmp:
            out, err = io.StringIO(), io.StringIO()
            env = {"SB_AUDIT_SERVER": fake.address, "SB_AUDIT_API_KEY": KEY, "SB_AUDIT_OUT": tmp}
            self.assertEqual(1, export.main([], env, out, err))
            self.assertNotIn(KEY, err.getvalue())
            self.assertNotIn(fake.address, err.getvalue())
            self.assertEqual([], list(Path(tmp).iterdir()))

    def test_aServerThatIsNotThereIsAnErrorWithNoAddress(self):
        with tempfile.TemporaryDirectory() as tmp:
            err = io.StringIO()
            env = {"SB_AUDIT_SERVER": "http://127.0.0.1:9", "SB_AUDIT_API_KEY": KEY, "SB_AUDIT_OUT": tmp}
            self.assertEqual(1, export.main([], env, io.StringIO(), err))
            self.assertNotIn("127.0.0.1", err.getvalue())

    def test_noFileInTheToolHoldsAServerAddressOrAKey(self):
        for path in Path(__file__).resolve().parent.rglob("*"):
            if path.is_file() and path.suffix in (".py", ".json", ".md", ".sh") and path != Path(__file__).resolve():
                text = path.read_text(encoding="utf-8")
                self.assertNotRegex(text, r"https?://(?!127\.0\.0\.1|example\.invalid|ADDRESS|[a-z]+\.invalid)[a-z0-9.-]+", str(path))


if __name__ == "__main__":
    unittest.main()
