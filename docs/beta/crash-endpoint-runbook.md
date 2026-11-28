# Crash-report endpoint: setup and reading reports (#1189)

The app's opt-in crash reports (#96, Q-PRIV-1) go to an endpoint in the owner's AWS account: a Lambda function URL that
writes each report as a JSON object to a private S3 bucket. The stack is in `infra/crash-endpoint/` and is deployed by
the `Deploy crash endpoint` workflow with GitHub OIDC, so no AWS key is stored anywhere. The URL is in every APK, so the
endpoint takes writes from anyone, gives nothing back, caps the size, and limits the rate. The URL is never committed.

## One-time setup (the owner)

Nothing here is done by an agent. Use one AWS region throughout. The policies in `infra/crash-endpoint/iam/` name
`<REGION>` and `<ACCOUNT_ID>`, and the trust policy also names `<ORG_ID>` and `<REPO_ID>` (step 3): replace them all
before you create anything from them. **The region in the policies must be the region the workflow deploys to**, which is
the `CRASH_ENDPOINT_REGION` repository variable, or `us-east-1` when that variable is not set (step 5). If they differ, the
role's permissions name resources in a region the deploy never touches, and it fails with AccessDenied.

1. **GitHub OIDC identity provider.** IAM, Identity providers, Add provider: type OpenID Connect, provider URL
   `https://token.actions.githubusercontent.com`, audience `sts.amazonaws.com`. (Skip if the account has it already.)
2. **Permissions boundary.** IAM, Policies, Create policy from `iam/permissions-boundary.json`, named exactly
   `stationbreak-crash-endpoint-boundary`. The function's role must carry it, and the deploy role may not create the role
   without it, so the function can never do more than write reports.
3. **Deploy role.** IAM, Roles, Create role, custom trust policy from `iam/trust-policy.json` (it trusts only this repository's
   `crash-endpoint` environment), then attach `iam/deploy-role-policy.json` as an inline policy. Name it, for example,
   `stationbreak-crash-endpoint-deploy`.

   AWS now wants the `sub` in GitHub's immutable-ID form, `repo:<OWNER>@<ORG_ID>/<REPO>@<REPO_ID>:environment:crash-endpoint`,
   not the name-only form (`repo:StationBreak/stationbreaktv:...`), which it rejects. `<ORG_ID>` and `<REPO_ID>` are the
   numeric ids of the organisation and the repository: `gh api repos/StationBreak/stationbreaktv --jq '.owner.id, .id'`
   prints them, owner first. If AWS still refuses the role (the workflow fails at `AssumeRoleWithWebIdentity`), open the
   failed `AssumeRoleWithWebIdentity` event in CloudTrail and copy the exact `sub` from it into the trust policy.
4. **GitHub environment.** Settings, Environments, New environment `crash-endpoint`: required reviewer yourself,
   deployment branches limited to `main`.
5. **Repository variable.** Settings, Secrets and variables, Actions, Variables: `CRASH_ENDPOINT_DEPLOY_ROLE_ARN` is the
   role's ARN (a variable, not a secret). Add `CRASH_ENDPOINT_REGION` unless the region is `us-east-1`, and make it the same
   region the policies name (see the top of this section).
6. **Run the workflow.** Actions, Deploy crash endpoint, Run workflow, from `main`; approve the environment. It deploys the
   stack, then smoke tests it: a sample report must land in the bucket, a GET must be refused (405) and an oversize
   body must be refused (413). The smoke test leaves one object in the bucket (`APP_VERSION_NAME` `smoke-test`); the
   90-day expiry removes it.
7. **Set the secret.** CloudFormation console, stack `stationbreak-crash-endpoint`, Outputs, `FunctionUrl`: copy it into
   Settings, Secrets and variables, Actions, **Repository secrets**, `CRASH_REPORT_URL`. It is a repository secret (not an
   environment one) because the release build job has no environment. The workflow never prints the URL; GitHub masks it
   in logs.
8. **A budget alert.** Billing, Budgets: a monthly cost budget of about $10 with an email alert. The rate limit bounds what
   a flood can do but does not stop it (see Cost below); this is how you hear about one.

Then tag a release as usual. A tagged build has the Send crash reports row in Settings; with it Off nothing is sent.

## Reading reports

A report is one JSON object: `receivedAt`, and `report` with the fields the app sends (the stack trace, the app and Android
version, the TV's brand, model and memory, the crash time, a random report id). Keys are
`reports/YYYY/MM/DD/<random>.json`. The stack trace is redacted on the TV and is still untrusted text: read it as text.

```bash
BUCKET=stationbreak-crash-reports-<ACCOUNT_ID>-<REGION>
# What came in this month
aws s3 ls "s3://$BUCKET/reports/$(date -u +%Y/%m)/" --recursive
# One report
aws s3 cp "s3://$BUCKET/reports/2026/12/01/<id>.json" - | jq .
# All of them, grouped by version and model
aws s3 sync "s3://$BUCKET/reports/" ./reports --quiet
jq -r '.report | [.APP_VERSION_NAME, .PHONE_MODEL, (.STACK_TRACE | split("\n")[0])] | @tsv' $(find ./reports -name '*.json') | sort | uniq -c | sort -rn
```

Reports expire after 90 days (the bucket's lifecycle rule). Nothing else deletes them: not a stack delete either, because
the bucket is retained.

## Running it

- **Kill switch.** Run the deploy workflow with `reserved_concurrency` 0: every request is answered 429 and nothing is
  stored (the app keeps its report and tries at its next start). Run it again with `2` to turn it back on.
- **A different limit.** Run the workflow with another `reserved_concurrency`. If a deploy fails because the account has no
  unreserved concurrency to give, request a Lambda concurrency quota increase, or deploy with `-1` (no reservation: no rate
  limit, so keep the budget alert).
- **Rotate the URL.** Delete the stack's function (or the whole stack: the bucket stays), run the workflow, copy the new URL
  into `CRASH_REPORT_URL`, and tag a release. Builds with the old URL then get an error and drop their reports.
- **The smoke test answers 502.** The function timed out (Lambda answers a timeout as 502): check its CloudWatch log for
  `Status: timeout`. It has 15 s and 256 MB, and builds its S3 client while it initialises, so a cold start should take a
  fraction of that; if it still times out, look at the log's `Init Duration` and `Max Memory Used`.
- **A deploy fails with AccessDenied.** The policies were written against AWS's documentation and checked by lint, not by a
  deploy, so the first run may find an action missing. The error names it (or CloudTrail does, as an `AccessDenied` event for
  the deploy role). Add that one action to `iam/deploy-role-policy.json` in a pull request, update the role, and run again.
- **Retention, size and the rest** are template parameters (`ReportRetentionDays`, `MaxReportBytes`); change the default in
  `infra/crash-endpoint/template.yaml` in a pull request.
- **Take it all down.** Delete the stack, then empty and delete the bucket by hand. Unset `CRASH_REPORT_URL` and the next
  tagged build has no crash reporting again.

## Cost

At beta scale (a few hundred reports a month) it is under a dollar a month, mostly nothing: Lambda's free tier covers the
requests, S3 holds kilobytes, and function URLs have no charge. Even 100,000 reports a month is about a dollar. A flood is
the risk, not the traffic: the rate limit is concurrency, a handful of requests at a time but tens a second, so a sustained
flood could cost tens to hundreds of dollars a month before anyone noticed. The budget alert and the kill switch are the
guard. A hard limit means API Gateway with a usage plan, or WAF in front of a CloudFront distribution, both with a fixed
monthly cost: not built here.
