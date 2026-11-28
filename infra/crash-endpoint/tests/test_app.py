import base64
import json
import os
import sys
import unittest
import uuid
from datetime import datetime, timezone

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))
os.environ.setdefault("MAX_REPORT_BYTES", "32768")
os.environ.setdefault("REPORT_BUCKET", "test-bucket")

import app  # noqa: E402

LIMIT = 32768
NOW = datetime(2026, 11, 27, 12, 0, tzinfo=timezone.utc)
SAMPLE = {
    "REPORT_ID": "00000000-0000-4000-8000-000000000000",
    "USER_CRASH_DATE": "2026-11-27T11:59:00Z",
    "PACKAGE_NAME": "tv.stationbreak.app",
    "APP_VERSION_NAME": "0.9.0-beta.4",
    "APP_VERSION_CODE": "90034",
    "ANDROID_VERSION": "7.1.1",
    "PHONE_MODEL": "AFTMM",
    "BRAND": "Amazon",
    "PRODUCT": "sheldon",
    "TOTAL_MEM_SIZE": "1073741824",
    "AVAILABLE_MEM_SIZE": "314572800",
    "STACK_TRACE": "java.lang.IllegalStateException: boom\n\tat tv.stationbreak.app.Foo.bar(Foo.kt:1)",
}


class FakeS3:
    def __init__(self, fail=False):
        self.puts = []
        self.fail = fail

    def put_object(self, **kwargs):
        if self.fail:
            raise RuntimeError("secret bucket name in the message")
        self.puts.append(kwargs)


def event(body=None, method="POST", content_type="application/json", base64_body=False, extra=None):
    headers = {"Content-Type": content_type} if content_type else {}
    headers.update(extra or {})
    return {
        "requestContext": {"http": {"method": method}},
        "headers": headers,
        "body": body,
        "isBase64Encoded": base64_body,
    }


def call(ev, s3=None):
    s3 = s3 or FakeS3()
    out = app.handler(ev, None, s3=s3, now=NOW, new_id=lambda: uuid.UUID(int=1))
    return out["statusCode"], s3


class HandlerTest(unittest.TestCase):
    def test_a_sample_report_is_written_once_with_a_key_the_function_made(self):
        status, s3 = call(event(json.dumps(SAMPLE)))
        self.assertEqual(204, status)
        self.assertEqual(1, len(s3.puts))
        put = s3.puts[0]
        self.assertEqual("test-bucket", put["Bucket"])
        self.assertEqual("reports/2026/11/27/00000000-0000-0000-0000-000000000001.json", put["Key"])
        self.assertEqual("AES256", put["ServerSideEncryption"])
        stored = json.loads(put["Body"])
        self.assertEqual(SAMPLE, stored["report"])
        self.assertEqual(NOW.isoformat(), stored["receivedAt"])

    def test_the_report_id_is_never_the_key(self):
        _, s3 = call(event(json.dumps({**SAMPLE, "REPORT_ID": "../../evil"})))
        self.assertNotIn("evil", s3.puts[0]["Key"])

    def test_only_the_apps_fields_are_kept(self):
        extra = {**SAMPLE, "LOGCAT": "everything", "DEVICE_ID": "abc", "nested": {"a": 1}}
        _, s3 = call(event(json.dumps(extra)))
        self.assertEqual(set(SAMPLE), set(json.loads(s3.puts[0]["Body"])["report"]))

    def test_a_field_that_is_not_a_short_string_is_dropped(self):
        report = {**SAMPLE, "BRAND": 7, "PRODUCT": "x" * (app.SHORT_FIELD_LIMIT + 1), "PHONE_MODEL": ""}
        _, s3 = call(event(json.dumps(report)))
        kept = json.loads(s3.puts[0]["Body"])["report"]
        for name in ("BRAND", "PRODUCT", "PHONE_MODEL"):
            self.assertNotIn(name, kept)

    def test_only_post_is_taken(self):
        for method in ("GET", "PUT", "DELETE", "OPTIONS", "HEAD"):
            status, s3 = call(event(json.dumps(SAMPLE), method=method))
            self.assertEqual(405, status, method)
            self.assertEqual([], s3.puts)

    def test_only_json_is_taken(self):
        for content_type in ("text/plain", "application/x-www-form-urlencoded", None):
            status, s3 = call(event(json.dumps(SAMPLE), content_type=content_type))
            self.assertEqual(415, status, str(content_type))
            self.assertEqual([], s3.puts)

    def test_the_charset_form_of_json_is_taken(self):
        status, _ = call(event(json.dumps(SAMPLE), content_type="application/json; charset=utf-8"))
        self.assertEqual(204, status)

    def test_a_body_over_the_cap_is_refused_by_its_size_not_its_header(self):
        big = json.dumps({**SAMPLE, "STACK_TRACE": "x" * LIMIT})
        status, s3 = call(event(big))
        self.assertEqual(413, status)
        status, _ = call(event(big, extra={"Content-Length": "10"}))
        self.assertEqual(413, status, "a header that understates the size does not get it through")
        self.assertEqual([], s3.puts)

    def test_a_declared_size_over_the_cap_is_refused_at_once(self):
        status, s3 = call(event(json.dumps(SAMPLE), extra={"content-length": str(LIMIT + 1)}))
        self.assertEqual(413, status)
        self.assertEqual([], s3.puts)

    def test_a_body_at_the_cap_is_taken(self):
        body = json.dumps({"STACK_TRACE": "x" * 100})
        body += " " * (LIMIT - len(body))
        status, _ = call(event(body))
        self.assertEqual(204, status)

    def test_a_base64_body_is_decoded_and_a_bad_one_refused(self):
        encoded = base64.b64encode(json.dumps(SAMPLE).encode()).decode()
        self.assertEqual(204, call(event(encoded, base64_body=True))[0])
        self.assertEqual(400, call(event("not base64!!", base64_body=True))[0])

    def test_empty_garbage_and_non_objects_are_refused(self):
        for body in (None, "", "not json", "[]", "7", "null", '"text"', "{}", '{"unknown": "x"}'):
            status, s3 = call(event(body))
            self.assertEqual(400, status, repr(body))
            self.assertEqual([], s3.puts)

    def test_deeply_nested_json_is_refused_not_crashed_on(self):
        self.assertEqual(400, call(event("[" * 10000 + "]" * 10000))[0])

    def test_a_storage_failure_is_a_500_that_says_nothing(self):
        status, _ = call(event(json.dumps(SAMPLE)), FakeS3(fail=True))
        self.assertEqual(500, status)

    def test_the_answer_has_no_body(self):
        out = app.handler(event(json.dumps(SAMPLE)), None, s3=FakeS3(), now=NOW)
        self.assertEqual({"statusCode": 204}, out)


if __name__ == "__main__":
    unittest.main()
