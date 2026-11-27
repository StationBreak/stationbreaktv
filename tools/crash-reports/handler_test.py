# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
"""Tests for the crash-report endpoint's handler. Run: python3 -I -m unittest discover -s tools/crash-reports -p '*_test.py'"""

import base64
import json
import pathlib
import re
import sys
import unittest
from datetime import datetime, timezone

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import handler  # noqa: E402

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent.parent
SAMPLE = json.loads((HERE / "sample-report.json").read_text())


class FakeS3:
    def __init__(self):
        self.puts = []

    def put_object(self, **kwargs):
        self.puts.append(kwargs)


def event(body, content_type="application/json; charset=utf-8", b64=False):
    raw = body if isinstance(body, str) else json.dumps(body)
    if b64:
        raw = base64.b64encode(raw.encode()).decode()
    return {
        "headers": {"Content-Type": content_type, "X-Forwarded-For": "203.0.113.7"},
        "body": raw,
        "isBase64Encoded": b64,
        "requestContext": {"http": {"sourceIp": "203.0.113.7"}},
    }


class HandlerTest(unittest.TestCase):
    def setUp(self):
        self.s3 = FakeS3()

    def call(self, ev):
        return handler.handle(ev, self.s3, "bucket", now=datetime(2026, 11, 27, 9, 5, 1, tzinfo=timezone.utc))

    def test_the_sample_report_is_stored_as_it_came(self):
        answer = self.call(event(SAMPLE))
        self.assertEqual(204, answer["statusCode"])
        self.assertEqual(1, len(self.s3.puts))
        put = self.s3.puts[0]
        self.assertEqual("bucket", put["Bucket"])
        self.assertRegex(put["Key"], r"^reports/2026/11/27/090501-[0-9a-f]{32}\.json$")
        self.assertEqual(SAMPLE, json.loads(put["Body"]))

    def test_nothing_about_the_sender_is_stored(self):
        self.call(event(SAMPLE))
        stored = self.s3.puts[0]["Body"].decode()
        self.assertNotIn("203.0.113.7", stored)
        self.assertEqual(set(SAMPLE), set(json.loads(stored)))

    def test_a_base64_body_is_read(self):
        self.assertEqual(204, self.call(event(SAMPLE, b64=True))["statusCode"])

    def test_two_reports_with_the_same_id_never_overwrite(self):
        self.call(event(SAMPLE))
        self.call(event(SAMPLE))
        self.assertNotEqual(self.s3.puts[0]["Key"], self.s3.puts[1]["Key"])

    def refused(self, ev, code):
        self.assertEqual(code, self.call(ev)["statusCode"])
        self.assertEqual([], self.s3.puts)

    def test_too_big_is_413(self):
        big = dict(SAMPLE, STACK_TRACE="x" * handler.MAX_BYTES)
        self.refused(event(big), 413)

    def test_not_json_content_type_is_415(self):
        self.refused(event(SAMPLE, content_type="text/plain"), 415)

    def test_bad_bodies_are_400(self):
        for body in ["", "not json", "[]", "{}", '"x"', json.dumps(dict(SAMPLE, EXTRA="x")),
                     json.dumps(dict(SAMPLE, APP_VERSION_CODE=90031)),
                     json.dumps(dict(SAMPLE, PACKAGE_NAME="com.example")),
                     json.dumps({k: v for k, v in SAMPLE.items() if k != "STACK_TRACE"})]:
            with self.subTest(body=body[:40]):
                self.refused(event(body), 400)

    def test_bad_base64_is_400(self):
        ev = event(SAMPLE)
        ev["body"], ev["isBase64Encoded"] = "!!!", True
        self.refused(ev, 400)

    def test_a_store_failure_is_a_plain_500(self):
        class Broken:
            def put_object(self, **_):
                raise RuntimeError("secret detail")

        handler._s3 = Broken()
        try:
            import os

            os.environ["BUCKET"] = "bucket"
            answer = handler.lambda_handler(event(SAMPLE), None)
        finally:
            handler._s3 = None
        self.assertEqual(500, answer["statusCode"])
        self.assertEqual("", answer["body"])


class InSyncTest(unittest.TestCase):
    def test_fields_match_the_apps_crash_report_body(self):
        kotlin = (ROOT / "app/src/main/kotlin/tv/stationbreak/app/crash/CrashReportBody.kt").read_text()
        block = re.search(r"val FIELDS: List<ReportField> =\s*listOf\((.*?)\)", kotlin, re.S).group(1)
        self.assertEqual(set(re.findall(r"ReportField\.([A-Z_]+)", block)), set(handler.FIELDS))

    def test_packages_match_the_apps_application_ids(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        base = re.search(r'applicationId = "([^"]+)"', gradle).group(1)
        suffixes = re.findall(r'applicationIdSuffix = "([^"]+)"', gradle)
        self.assertEqual({base} | {base + s for s in suffixes}, set(handler.PACKAGES))

    def test_the_sample_holds_only_the_fields(self):
        self.assertEqual(set(SAMPLE), set(handler.FIELDS))


if __name__ == "__main__":
    unittest.main()
