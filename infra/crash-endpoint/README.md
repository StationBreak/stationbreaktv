# Crash-report endpoint (#1189)

The owner's endpoint for the app's opt-in crash reports (#96, Q-PRIV-1): an AWS SAM stack. A Lambda function URL takes
one POST, checks it, and writes one JSON object to a private S3 bucket. Nothing is returned, no credential is in the
URL, and the URL itself is never in this repository (it goes in the `CRASH_REPORT_URL` Actions secret).

| Path | What |
| --- | --- |
| `template.yaml` | The stack: private bucket (block public access, SSE, TLS only, 90-day expiry), the function, its role |
| `src/app.py` | The function: method, content type and size checks, allowed fields only, a key it makes itself |
| `tests/` | The function's unit tests (`python3 -m unittest discover -s infra/crash-endpoint/tests`) |
| `iam/` | The exact policies the one-time AWS setup uses: trust, permissions boundary, deploy role (placeholders for the account id and region) |

Deployed by `.github/workflows/deploy-crash-endpoint.yml` with GitHub OIDC; the one-time steps and how to read the
reports are in [docs/beta/crash-endpoint-runbook.md](../../docs/beta/crash-endpoint-runbook.md).
