# ADR-0012: CI gates on a private Free repo are advisory until the visibility flip

- **Status:** Accepted, amended 2026-11-06
- **Date:** 2026-10-12
- **Decided by:** the owner, with a security ruling from Angua (Security)
- **Revisit:** done 2026-11-06. The repository is public. The amendment below is the current rule. The original revisit was [#156](https://github.com/StationBreak/stationbreaktv/issues/156).
- **Informs:** F0.1 (S0.1.1, S0.1.2, S0.1.4)

The decision below is the record of the private-repository period. For what blocks a merge now, read the amendment.

## Context

F0.1 adds CI gates that are each meant to block a merge: the build and its quality checks, the emulator tests,
DCO, zizmor, actionlint, gitleaks and OSV-Scanner. Two facts about the repository as it is today mean none of
them can actually enforce anything:

- **No branch protection or rulesets.** The repository is private on GitHub Free, where both are unavailable.
  GitHub can't require a check to pass before a merge, so a red check is a signal, not a lock.
- **A pull request checks itself.** A `pull_request` run uses the pull request's own copy of `.github/workflows/`
  and `.github/scripts/`. A pull request from a branch in this repository can change a gate, for example make
  `dco-check.sh` always pass or delete the `gitleaks` job, and its own run will use the changed version.
  `.gitleaks.toml` is read from the base commit, so a pull request can't loosen its own secret scan. But it can
  still change the job that reads that file.

Branch protection, rulesets and code-owner review all become available when the repository goes public.

## Decision

1. **Until the visibility flip, the owner's review is the control.** The CI gates support that review but don't
   replace it. Before merging, the owner reads every change to:
   - `.github/**` (workflows, scripts and their test fixtures);
   - `.gitleaks.toml`;
   - `renovate.json`;
   - `gradle/osv-scanner.toml` (its ignores can silence a vulnerability finding; ADR-0013).

   A pull request that changes any of these gets the same scrutiny as a change to the gates themselves, whoever
   or whatever authored it. That includes Renovate (ADR-0011, item 6).
2. **Accepted risk: every check is advisory** (Angua's review of spec 01 task 4.0, finding 3). Someone with
   write access can change a gate in the same pull request it checks, and nothing stops a merge on a red check.
   This is accepted because only the owner and the Renovate app (ADR-0011 item 6) push branches, and decision 1
   says what review must catch, including in Renovate's pull requests.
3. **Accepted risk: the OSV-Scanner reusable workflow keeps its checkout token** (finding 4). The upstream
   `osv-scanner-reusable-pr.yml` (v2.6.0) checks out with persisted credentials, so its token sits in
   `.git/config` while the scanner's container runs. That token is limited to what the `osv-scanner` job grants:
   `actions: read`, `contents: read` and `security-events: write`, the minimum the workflow will start with.
   This is accepted **on condition** that fork pull requests get no workflow runs, write tokens or secrets. The
   repository's settings were read on 2026-10-12 (read-only):
   - `actions/permissions/fork-pr-workflows-private-repos`: `run_workflows_from_fork_pull_requests: false`,
     `send_write_tokens_to_workflows: false`, `send_secrets_and_variables: false`;
   - `actions/permissions/workflow`: `default_workflow_permissions: read`,
     `can_approve_pull_request_reviews: false`;
   - repository: `allow_forking: false`.

   The organisation-level equivalents need `admin:org` and weren't read. If any of these values changes, this
   risk is reviewed again.

## Consequences

- A green CI run on this repository means "the checks found nothing", not "the change can't have weakened the
  checks". The owner treats a diff under `.github/` as the first thing to read in any pull request.
- **Revisit trigger:** the visibility flip ([#156](https://github.com/StationBreak/stationbreaktv/issues/156)).
  In order:
  1. Turn on branch protection or rulesets for `main`, requiring every CI job and the DCO check.
  2. Add a `CODEOWNERS` entry for `.github/` (and `.gitleaks.toml`, `renovate.json` and
     `gradle/osv-scanner.toml`), with code-owner review required.

  After that, this ADR is superseded for the gates those rules cover.
- **Standing rule (as in ADR-0011):** if anyone else gets push access to the repository (a collaborator, app or
  bot), this ADR is reviewed again before the flip, because decision 2 relies on only the owner and the Renovate
  app (ADR-0011 item 6) pushing branches.

## Amendment (2026-11-06): the existing checks are the merge gate

The repository is public. Read on 2026-11-06: `visibility` is `public`, and rulesets apply to `main`. This amendment is what the revisit at the top asked for. It does not add scanners. Renovate automerge stays off.

### What is already enforced

One repository ruleset, id `25361384`, name `main`, enforcement `active`, includes `~DEFAULT_BRANCH`. Created 2026-10-18. The ruleset list has no second ruleset. `GET /repos/StationBreak/stationbreaktv/rules/branches/main` returns only this ruleset's rules.

Classic branch protection is not the gate. `GET /branches/main` reports `protected: true` because a ruleset applies, and `protection.enabled: false`, with an empty required-check list and `enforcement_level: off`. A non-admin token receives 403 from `GET /branches/main/protection`, so that call alone cannot prove the absence of a hidden classic rule. The branch summary and the rules evaluation are the evidence: no classic contexts, and the four contexts below come from the ruleset.

The ruleset also requires a pull request, allows only squash merges, and blocks deletion and force-pushes. Required approving reviews: 0. Code-owner review: off. Approval of the last push: off. `require_extra_approval_for_unattributed_changes` is on. It adds one review only for an unattributed Copilot pull request, and only when the approval count is already at least 1, so with a count of 0 it changes nothing. `strict_required_status_checks_policy` is off: a pull request can merge without being brought up to date with `main` first.

The readable ruleset object has no `bypass_actors` field. The token used for the read reports `current_user_can_bypass: never`. The update below sets `bypass_actors` to an empty list. An administrators bypass stays off: it would let an admin merge a red pull request on an ordinary day. An emergency is a deliberate edit of the ruleset, not a standing bypass. Renovate, the Cursor app, and any other bot are not bypass actors.

Required status checks today, GitHub Actions integration id `15368`. The context is the job id, with no workflow prefix:

| Context | Already required | What a failure means |
| --- | --- | --- |
| `checks` | yes | DCO (the "Check sign-offs" step; it is not its own check), actionlint, zizmor, gitleaks, the dependency-verification guard, the device-proof guard, and the script tests |
| `verify` | yes | `check`: detekt, Android Lint (Security is an error), unit tests, licensee, architecture, spotless |
| `assemble` | yes | Debug assemble, and the release variants when the release scope says so |
| `emulator` | yes | API 25 instrumented tests |

A skipped job reports its job id and satisfies a required check. Docs pull request #595 merged with `verify`, `assemble` and `emulator` skipped. A workflow that never starts leaves the context pending and blocks the merge. That is why `ci.yml` skips with `if` and the Jellyfin workflow's path filters stay off this list.

### Contexts to add

The token that read the ruleset cannot update it (`PUT` returned 403, `permissions.admin` false). These two contexts are part of this decision and are not on the ruleset until an admin applies the payload at the end.

| Context | Why this name | When the job is skipped |
| --- | --- | --- |
| `sign-debug` | The job id already reported on every CI run, as a pass, a failure, or a skip. Re-signs the debug APK with the CI key. | Docs-only pull requests, forks, and Renovate, which must not receive the key (ADR-0011). A failed `assemble` skips it too; `assemble` is already required. |
| `osv-scanner-result` | The reusable workflow reports `osv-scanner` when it is skipped and `osv-scanner / osv-scan` when it runs (seen on pull requests #603 and #528). Requiring either name blocks the other kind of pull request. The new job in `ci.yml` runs only when `checks` sets `deps=true`, and passes only when the scan's result is `success`. | Every pull request that does not change dependency files or build logic. The skip is the success the ruleset needs. |

Add `osv-scanner-result` only after this change is on `main` and open pull requests have been updated from `main`. Until a branch contains the job, the context stays pending and the pull request cannot merge. Open at the time of the audit: #528 (Renovate), #564 and #580 (spikes, marked do not merge). `sign-debug` is already reported on those branches, so it can be required on its own before they update.

### What stays off the ruleset

- **Jellyfin contract.** `jellyfin-contract.yml` uses workflow path filters, so on any other pull request the check is absent, not skipped. The matrix check name embeds the image digest (`jellyfin-contract (12.1, sha256:…)`), so a required context would go stale on every pin bump. `jellyfin-contract-latest` runs on the weekly schedule and on `workflow_dispatch`, not on pull requests. The contract still fails its own run when the paths match. It is not a merge gate. Making it one means either blocking unrelated pull requests or starting a job on every pull request. Neither is in this change.
- **OSV weekly scan.** It opens issues. It does not run on pull requests. The pull request gate is `osv-scanner-result`.
- **`renovate/artifacts` and `renovate/stability-days`.** Only Renovate pull requests report them. Requiring them would block every other pull request.
- **CodeQL, secret scanning with push protection, the dependency review action, Semgrep, screenshot tests.** Not added. S0.1.4's public-repo trio stays future work; the repository being public was the condition, not the decision to turn them on in this change.
- **Signed commits.** Not required. Every identity that commits, including an agent session, still needs a signing key first (S0.1.1).
- **A required review count, code-owner review, and approval of the last push.** Off, for the reason in the next section. Turning any of them on deadlocks the only maintainer.

A pull request can still weaken a gate that the ruleset requires. Setting `osv-scanner-result` (or `checks`) to `if: false` makes GitHub report a skip, and a skip passes. The ruleset cannot see that the job declined to run. Review of the paths below is what catches that.

### CODEOWNERS, without a deadlock

`@PCritchfield` is the only person whose pull requests merge. Organisation membership is private. Recent merged pull requests are his, plus Renovate. GitHub does not let the author approve their own pull request. Requiring one review, or a code-owner review, would stop his own gate changes and every Renovate metadata pull request. There is no second maintainer to name, and a bot approver would put the bypass back.

`.github/CODEOWNERS` requests his review, and the ruleset does not require it:

- `/.github/` (workflows, scripts, fixtures, and this file)
- `/.gitleaks.toml`
- `/renovate.json`
- `/gradle/verification-metadata.xml`
- `/gradle/osv-scanner.toml`

`gradle.properties` is not listed. The verification guard fails if any `gradle.properties` mentions `org.gradle.dependency.verification`, and the file otherwise changes for ordinary build settings. The owner still reads a diff under `.github/` before merging, whoever authored it, including Renovate and an agent session. That is the same duty as decision 1, now with the file GitHub uses to mark the diff.

### Applying the two new contexts

An admin does this once, after `osv-scanner-result` exists on `main` and on any open pull request that should still be mergeable. Confirm the job on the pull request that adds it first. That pull request does not touch dependency files, so `osv-scanner-result` is skipped, which is the expected report.

In the GitHub UI: Settings → Rules → Rulesets → `main`. Under required status checks, add `sign-debug` and `osv-scanner-result`. Leave "Require branches to be up to date before merging" off. Leave the bypass list empty. Do not turn on code-owner review or a required approval count.

Or replace the ruleset with this body. It keeps every current rule and adds the two contexts. `integration_id` `15368` is GitHub Actions, the same id the current contexts use.

Before sending `"bypass_actors": []`, GET the ruleset with an admin token. The non-admin read had no `bypass_actors` field. If an admin GET shows a non-empty list, do not keep an actor who can merge without these checks.

```bash
gh api --method PUT repos/StationBreak/stationbreaktv/rulesets/25361384 \
  --input - <<'EOF'
{
  "name": "main",
  "target": "branch",
  "enforcement": "active",
  "bypass_actors": [],
  "conditions": {
    "ref_name": {
      "exclude": [],
      "include": ["~DEFAULT_BRANCH"]
    }
  },
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    {
      "type": "pull_request",
      "parameters": {
        "required_approving_review_count": 0,
        "dismiss_stale_reviews_on_push": false,
        "required_reviewers": [],
        "require_code_owner_review": false,
        "dismissal_restriction": { "enabled": false, "allowed_actors": [] },
        "require_last_push_approval": false,
        "required_review_thread_resolution": false,
        "require_extra_approval_for_unattributed_changes": true,
        "allowed_merge_methods": ["squash"]
      }
    },
    {
      "type": "required_status_checks",
      "parameters": {
        "strict_required_status_checks_policy": false,
        "do_not_enforce_on_create": false,
        "required_status_checks": [
          { "context": "checks", "integration_id": 15368 },
          { "context": "verify", "integration_id": 15368 },
          { "context": "assemble", "integration_id": 15368 },
          { "context": "emulator", "integration_id": 15368 },
          { "context": "sign-debug", "integration_id": 15368 },
          { "context": "osv-scanner-result", "integration_id": 15368 }
        ]
      }
    }
  ]
}
EOF
```

After it succeeds, `GET /repos/StationBreak/stationbreaktv/rules/branches/main` lists those six contexts and still no bypass. This amendment's "Contexts to add" section is then historical: they are required.
