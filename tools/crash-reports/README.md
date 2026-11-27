# Crash-report endpoint and runbook

The app's opt-in crash reports (#96, Q-PRIV-1) go to the owner's own endpoint (#1189). This directory is that endpoint
as code: an AWS SAM stack with one HTTPS route, `POST /report`, a small Lambda function that checks each report and
writes it as one JSON object to a private S3 bucket. Nothing in the repository holds the endpoint's address or a
credential. The address exists only as the deployed stack's output and in the `CRASH_REPORT_URL` Actions secret.

| Piece | What it does |
| --- | --- |
| `template.yaml` | HTTP API (HTTPS only, throttled), the function, the bucket (private, encrypted, TLS only, reports expire), an optional budget alert |
| `handler.py` | Accepts only the app's report shape: JSON, the fields in `CrashReportBody.FIELDS`, all strings, the app's package name, at most `MaxReportBytes` |
| `handler_test.py` | Unit tests, plus checks that the fields and package names still match the app |
| `sample-report.json` | A made-up, redacted report for the test POST |

What it keeps: the report body as the TV sent it (already redacted on the TV), under
`reports/YYYY/MM/DD/<time>-<random>.json`. What it does not keep: the sender's network address, headers or user agent.
There is no API access log, and the function never logs a body. Reports are deleted by S3 after `RetentionDays` (90 by
default).

How the app reacts to each answer (`CrashReportSender`): 204 is delivered. 400, 413 and 415 are refused for good, so the
TV drops the report. 429 (over the rate limit) and 500 make the TV try again later.

## Limits and cost

- Rate limit: `RateLimit` requests per second, `BurstLimit` at once, for the whole endpoint (defaults 1 and 5). Anything
  above that answers 429 before the function runs.
- Size cap: `MaxReportBytes` (128 KiB by default). A larger body answers 413 and is not stored.
- `ReservedConcurrency` (2) caps how many copies of the function run at once.
- Expected cost at beta scale (up to about 1,000 reports a month): under $0.05 a month, mostly S3 requests. The free
  tier covers the Lambda part. Worst case, with someone flooding the endpoint at the full rate limit for a whole month:
  about $17 (2.6 million requests: about $2.60 API Gateway, $0.75 Lambda, $13 S3 writes if every request were a
  valid-shaped report). Lower `RateLimit` to lower that ceiling. Figures are us-east-1 list prices; check the AWS
  pricing pages before relying on them.

## Deploy (Phil)

Needs the AWS CLI and the SAM CLI, signed in to the StationBreak AWS account. This creates resources that cost money.

```bash
cd tools/crash-reports
python3 -I -m unittest discover -s . -p '*_test.py'
sam deploy --guided --stack-name stationbreak-crash-reports --capabilities CAPABILITY_IAM
```

`--guided` asks for the region and each parameter. It can save the answers to `samconfig.toml`; that file is
gitignored here, so keep it local. Set `AlertEmail` to get an email when the account's monthly spend passes 80% of
`MonthlyBudgetUsd`. The budget covers the whole account, not only this stack. When the deploy ends, it prints
`ReportUrl` and `BucketName`.

The address is baked into every APK built with it. Deleting and recreating the stack gives a new address, and builds
already installed would then send to nowhere. A custom domain on Route 53 (for example a `crash` subdomain, with an ACM
certificate and an API Gateway custom domain mapping) keeps the address stable. It is not in this template yet; add it
before the address goes into a store build if stability matters.

## Test the endpoint

Read the address from the stack instead of typing it, so it stays out of files:

```bash
url="$(aws cloudformation describe-stacks --stack-name stationbreak-crash-reports \
  --query "Stacks[0].Outputs[?OutputKey=='ReportUrl'].OutputValue" --output text)"
curl -sS -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/json' \
  --data-binary @sample-report.json "$url"
```

A 204 means the sample was stored. Check that it landed (next section), then delete it.

## Give the address to the release workflow

```bash
gh secret set CRASH_REPORT_URL -R StationBreak/stationbreaktv
```

It prompts for the value, so the address never reaches the shell history. Paste the `ReportUrl` output. It is a
repository secret because the release workflow's `build` job, which has no environment, reads it. Builds made without it
have no Send crash reports row and send nothing.

## Read reports

```bash
bucket="$(aws cloudformation describe-stacks --stack-name stationbreak-crash-reports \
  --query "Stacks[0].Outputs[?OutputKey=='BucketName'].OutputValue" --output text)"
aws s3 ls "s3://$bucket/reports/" --recursive          # newest last, by date and time
aws s3 cp "s3://$bucket/reports/2026/12/06/101500-abc.json" - | python3 -m json.tool
aws s3 sync "s3://$bucket/reports/2026/12/" ./reports-2026-12/   # a month, to read locally
```

`STACK_TRACE` is the crash. `APP_VERSION_NAME`, `PHONE_MODEL` and `ANDROID_VERSION` say which build and which TV. Keep
downloaded copies out of the repository: like library titles, a stack trace can name something from a viewer's server.

## Delete a report on request

The privacy page tells viewers to email support about a report already sent. Ask for the report's time and TV model.
The app shows no report ID, so search by those fields:

```bash
aws s3 ls "s3://$bucket/reports/2026/12/06/"
aws s3 cp "s3://$bucket/reports/2026/12/06/101500-abc.json" -     # check it is the one
aws s3 rm "s3://$bucket/reports/2026/12/06/101500-abc.json"
```

The bucket has no versioning, so `rm` removes the report completely.

## When something goes wrong

- Flooded or abused: lower `RateLimit` with `sam deploy` (seconds to take effect). To stop accepting reports entirely,
  set `ReservedConcurrency` to 0 or delete the stack. The TVs keep their reports and try again later, up to ACRA's limit.
- Function errors: `aws logs tail /aws/lambda/<function name> --since 1h`. A line `store failed` means S3 refused the
  write.
- The deploy fails on `ReservedConcurrentExecutions`: the account's concurrency quota is too low to reserve any. Deploy
  with `ReservedConcurrency=-1` and ask AWS for a higher quota.
- Tear down: `sam delete --stack-name stationbreak-crash-reports`. The bucket is kept (`DeletionPolicy: Retain`): empty
  it and delete it by hand if the reports should go too.
