"""The crash-report endpoint (#1189): takes one redacted report from the app and writes it to a private S3 bucket.

The URL is in every APK, so the caller is anyone: the endpoint is write-only, gives nothing back, and trusts nothing it
is sent. It checks the method, the content type and the size, keeps only the fields the app is built to send, and
writes them under a key it makes itself. Throttling is the function's reserved concurrency (a request over it is
answered 429 by Lambda, before this code runs; the app tries again at its next start).

Nothing in a report is logged, and no request header is: only the status class of each call.
"""

import base64
import binascii
import json
import os
import uuid
from datetime import datetime, timezone

# The fields the app's CrashReportBody sends (app/crash/CrashReportBody.kt), and nothing else is kept.
ALLOWED_FIELDS = frozenset(
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

# Every field but the stack trace is a short build or device value.
SHORT_FIELD_LIMIT = 256

try:
    import boto3
except ImportError:  # The unit tests run without the AWS library and pass their own client.
    boto3 = None

# Made while the function initialises, not inside the first request: the import and the client are the slow part of a
# cold start (a 5 s timeout was hit on 128 MB), and init has its own, longer allowance.
_client = boto3.client("s3") if boto3 else None


def _s3():
    return _client


def _answer(status):
    print(json.dumps({"status": status}))
    return {"statusCode": status}


def _body_bytes(event):
    body = event.get("body")
    if not body:
        return b""
    if event.get("isBase64Encoded"):
        try:
            return base64.b64decode(body, validate=True)
        except (binascii.Error, ValueError):
            return None
    return body.encode("utf-8")


def _kept(report, max_bytes):
    """The allowed fields of [report] with a string value no longer than its limit, or None if there are none."""
    kept = {}
    for name in ALLOWED_FIELDS:
        value = report.get(name)
        limit = max_bytes if name == "STACK_TRACE" else SHORT_FIELD_LIMIT
        if isinstance(value, str) and 0 < len(value) <= limit:
            kept[name] = value
    return kept or None


def handler(event, context=None, *, s3=None, now=None, new_id=None):
    max_bytes = int(os.environ["MAX_REPORT_BYTES"])
    bucket = os.environ["REPORT_BUCKET"]
    request = (event.get("requestContext") or {}).get("http") or {}
    if request.get("method") != "POST":
        return _answer(405)
    headers = {str(k).lower(): str(v) for k, v in (event.get("headers") or {}).items()}
    if not headers.get("content-type", "").lower().startswith("application/json"):
        return _answer(415)
    declared = headers.get("content-length", "")
    if declared.isdigit() and int(declared) > max_bytes:
        return _answer(413)
    raw = _body_bytes(event)
    if raw is None or not raw:
        return _answer(400)
    if len(raw) > max_bytes:
        return _answer(413)
    try:
        parsed = json.loads(raw)
    except (ValueError, RecursionError):
        return _answer(400)
    kept = _kept(parsed, max_bytes) if isinstance(parsed, dict) else None
    if kept is None:
        return _answer(400)
    received = (now or datetime.now(timezone.utc))
    # The key is made here, never taken from the report: a caller cannot choose where or what to overwrite.
    key = f"reports/{received:%Y/%m/%d}/{(new_id or uuid.uuid4)()}.json"
    record = {"receivedAt": received.isoformat(), "report": kept}
    try:
        (s3 or _s3()).put_object(
            Bucket=bucket,
            Key=key,
            Body=json.dumps(record).encode("utf-8"),
            ContentType="application/json",
            ServerSideEncryption="AES256",
        )
    except Exception:  # noqa: BLE001 - Any failure is a 500 the app tries again; its text could carry a name.
        return _answer(500)
    return _answer(204)
