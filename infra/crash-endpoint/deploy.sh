#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Deploys the crash-endpoint stack and smoke tests it (#1189). Run by .github/workflows/deploy-crash-endpoint.yml, in the
# deploy job, and nowhere else: it needs GitHub's OIDC token request variables, which only a job with `id-token: write`
# has. It trades that token for the deploy role's temporary credentials with the AWS CLI's own web-identity call, so no
# third-party action and no stored AWS key is involved, and the credentials live in this script's shell and nowhere else.
#
# Inputs (environment): ROLE_ARN, REGION, RESERVED_CONCURRENCY, and the runner's ACTIONS_ID_TOKEN_REQUEST_URL and
# ACTIONS_ID_TOKEN_REQUEST_TOKEN. Nothing here prints the token, the credentials, the account id or the endpoint URL:
# each is masked in the log the moment it is read.
set -euo pipefail

STACK_NAME="stationbreak-crash-endpoint"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

[[ "$RESERVED_CONCURRENCY" =~ ^-?[0-9]+$ ]] || { echo "::error::reserved_concurrency must be a whole number"; exit 1; }
export AWS_REGION="$REGION" AWS_DEFAULT_REGION="$REGION" SAM_CLI_TELEMETRY=0

# GitHub's OIDC token for AWS (audience sts.amazonaws.com), then the role's credentials for half an hour.
token="$(curl -sS --fail --retry 2 --max-time 20 \
  -H "Authorization: Bearer ${ACTIONS_ID_TOKEN_REQUEST_TOKEN}" \
  "${ACTIONS_ID_TOKEN_REQUEST_URL}&audience=sts.amazonaws.com" | jq -r .value)"
echo "::add-mask::$token"
credentials="$(aws sts assume-role-with-web-identity \
  --role-arn "$ROLE_ARN" \
  --role-session-name deploy-crash-endpoint \
  --web-identity-token "$token" \
  --duration-seconds 1800 \
  --query 'Credentials.[AccessKeyId,SecretAccessKey,SessionToken]' --output text)"
read -r key secret session <<< "$credentials"
echo "::add-mask::$key"
echo "::add-mask::$secret"
echo "::add-mask::$session"
export AWS_ACCESS_KEY_ID="$key" AWS_SECRET_ACCESS_KEY="$secret" AWS_SESSION_TOKEN="$session"
unset token credentials key secret session

account="$(aws sts get-caller-identity --query Account --output text)"
echo "::add-mask::$account"

# The boundary the function's role must carry is made once by the owner (docs/beta/crash-endpoint-runbook.md).
sam deploy \
  --template-file "$here/template.yaml" \
  --stack-name "$STACK_NAME" \
  --resolve-s3 \
  --capabilities CAPABILITY_NAMED_IAM \
  --no-confirm-changeset \
  --no-fail-on-empty-changeset \
  --parameter-overrides \
  "PermissionsBoundaryArn=arn:aws:iam::${account}:policy/stationbreak-crash-endpoint-boundary" \
  "ReservedConcurrency=${RESERVED_CONCURRENCY}"

# One sample report must land in the bucket, and the endpoint must refuse what it should.
output() {
  aws cloudformation describe-stacks --stack-name "$STACK_NAME" \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text
}
url="$(output FunctionUrl)"
echo "::add-mask::$url"
bucket="$(output ReportBucketName)"
prefix="reports/$(date -u +%Y/%m/%d)/"
count() {
  aws s3api list-objects-v2 --bucket "$bucket" --prefix "$prefix" --query KeyCount --output text
}
status() {
  curl -sS -o /dev/null -w '%{http_code}' --max-time 20 "$@" "$url"
}

# With the kill switch on, nothing runs and everything is answered 429: that is the expected state, not a failure.
if [ "$RESERVED_CONCURRENCY" = "0" ]; then
  got="$(status -X GET || true)"
  [ "$got" = "429" ] || { echo "::error::with the kill switch on, a GET was answered $got, not 429"; exit 1; }
  echo "Kill switch on: the endpoint answers 429 and stores nothing."
  exit 0
fi

sample='{"REPORT_ID":"00000000-0000-4000-8000-000000000000","PACKAGE_NAME":"tv.stationbreak.app","APP_VERSION_NAME":"smoke-test","STACK_TRACE":"java.lang.IllegalStateException: smoke test"}'
before="$(count)"
got="$(status -X POST -H 'Content-Type: application/json' --data "$sample")"
[ "$got" = "204" ] || { echo "::error::a sample report was answered $got, not 204"; exit 1; }
after="$(count)"
[ "$after" -gt "$before" ] || { echo "::error::the sample report did not land in the bucket"; exit 1; }
got="$(status -X GET)"
[ "$got" = "405" ] || { echo "::error::a GET was answered $got, not 405"; exit 1; }
big="$(head -c 40000 /dev/zero | tr '\0' x)"
got="$(status -X POST -H 'Content-Type: application/json' --data "{\"STACK_TRACE\":\"$big\"}")"
[ "$got" = "413" ] || { echo "::error::an oversize body was answered $got, not 413"; exit 1; }

{
  echo "### Crash endpoint deployed"
  echo "A sample report landed in the bucket; a GET and an oversize body were refused."
  echo "The function URL is not printed here: read it from the stack's Outputs in the CloudFormation console and set it as the CRASH_REPORT_URL secret."
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
