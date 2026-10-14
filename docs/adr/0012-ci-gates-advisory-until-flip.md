# ADR-0012: CI gates on a private Free repo are advisory until the visibility flip

- **Status:** Accepted
- **Date:** 2026-10-12
- **Decided by:** the owner, with a security ruling from Angua (Security)
- **Revisit:** at the visibility flip, tracked in [#156](https://github.com/StationBreak/stationbreaktv/issues/156)
- **Informs:** F0.1 (S0.1.1, S0.1.2, S0.1.4)

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
