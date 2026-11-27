# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
"""Receives one opt-in crash report from the app (#1189) and stores it as a JSON object in a private S3 bucket.

The app's sender (app/src/main/kotlin/tv/stationbreak/app/crash/CrashReportSender.kt) posts a JSON object of string
fields, the ones in CrashReportBody.FIELDS, already redacted on the TV. This handler accepts that shape and nothing
else. It stores only the body: no source address, no headers, no user agent. It logs nothing from the body.

Status codes follow what the sender does with them: 2xx is delivered; 4xx other than 408 and 429 is refused for good
(the report is dropped on the TV); 408, 429 and 5xx are tried again later.
"""

import base64
import json
import os
import uuid
from datetime import datetime, timezone

# Kept equal to CrashReportBody.FIELDS (handler_test.py checks it against the Kotlin file).
FIELDS = frozenset(
    {
        "REPORT_ID",
        "USER_CRASH_DATE",
        "PACKAGE_NAME",
        "APP_VERSION_NAME",
        "APP_VERSION_CODE",
        "ANDROID_VERSION",
        "PHONE_MODEL",
        "BRAND",
        "PRODUCT",
        "TOTAL_MEM_SIZE",
        "AVAILABLE_MEM_SIZE",
        "STACK_TRACE",
    }
)

# The app's package names (app/build.gradle.kts: applicationId and its debug and benchmark suffixes). A report from
# anything else is refused. Not a security boundary (anyone can type the name), just a filter for stray traffic.
PACKAGES = frozenset({"tv.stationbreak.app", "tv.stationbreak.app.debug", "tv.stationbreak.app.benchmark"})

MAX_BYTES = int(os.environ.get("MAX_REPORT_BYTES", "131072"))


def _answer(code):
    return {"statusCode": code, "headers": {"content-type": "text/plain"}, "body": ""}


def _body(event):
    raw = event.get("body") or ""
    if event.get("isBase64Encoded"):
        try:
            return base64.b64decode(raw, validate=True)
        except ValueError:
            return None
    return raw.encode("utf-8")


def parse(event):
    """The report as a dict of the allowed string fields, or an HTTP status code saying why it was refused."""
    headers = {k.lower(): v for k, v in (event.get("headers") or {}).items()}
    if not headers.get("content-type", "").lower().startswith("application/json"):
        return 415
    body = _body(event)
    if body is None:
        return 400
    if len(body) > MAX_BYTES:
        return 413
    try:
        report = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        return 400
    if not isinstance(report, dict) or not report:
        return 400
    if not set(report) <= FIELDS or not all(isinstance(v, str) for v in report.values()):
        return 400
    if report.get("PACKAGE_NAME") not in PACKAGES or not report.get("STACK_TRACE"):
        return 400
    return report


def key_for(now, name=None):
    """reports/YYYY/MM/DD/<time>-<random>.json: listed in time order, and never one report over another."""
    name = name or uuid.uuid4().hex
    return f"reports/{now:%Y/%m/%d}/{now:%H%M%S}-{name}.json"


def handle(event, s3, bucket, now=None):
    parsed = parse(event)
    if isinstance(parsed, int):
        return _answer(parsed)
    now = now or datetime.now(timezone.utc)
    s3.put_object(
        Bucket=bucket,
        Key=key_for(now),
        Body=json.dumps(parsed, sort_keys=True).encode("utf-8"),
        ContentType="application/json",
    )
    return _answer(204)


_s3 = None


def lambda_handler(event, _context):
    global _s3
    if _s3 is None:
        import boto3

        _s3 = boto3.client("s3")
    try:
        return handle(event, _s3, os.environ["BUCKET"])
    except Exception:  # noqa: BLE001 - one plain 500, so the TV tries again; the body is never logged.
        print("store failed")
        return _answer(500)
