# 01-audit-repo-and-ci.md

Planning audit for [01-tasks-repo-and-ci.md](01-tasks-repo-and-ci.md) against
[01-spec-repo-and-ci.md](01-spec-repo-and-ci.md). Run 1 and run 2 (re-audit after approved remediation), 2026-10-11.

## Executive Summary

- Overall Status: PASS (run 2; all REQUIRED gates pass and no FLAGs remain)
- Required Gate Failures: 0
- Flagged Risks: 0 (run 1 had 1, resolved by the approved remediation)
- Standards confidence: **low**. There's no `AGENTS.md`, `CONTRIBUTING.md`, CI config or lint config yet. Standards
  come from `README.md`, ADR-0003, `MODULES.md` and `BACKLOG.md`, plus the spec's own "Repository Standards"
  section. The missing contribution guide is created by sub-task 3.8, so no separate remediation is needed.

## Gateboard

| Gate | Status | Why it failed (<=10 words) | Exact fix target |
| --- | --- | --- | --- |
| Requirement-to-test traceability (REQUIRED) | PASS | Every Unit 1–4 requirement maps to a sub-task and proof | — |
| Proof artifact verifiability (REQUIRED) | PASS | Each proof names a run, job, command, file or API | — |
| Repository standards consistency (REQUIRED) | PASS | 4 sources read; no conflicts; precedence is the spec | — |
| Open question resolution (REQUIRED) | PASS | OQ1–3 and `targetSdk` carried as explicit assumptions | — |
| Regression-risk blind spots (FLAG) | PASS | 12 of 13 gates have failing-PR proofs; OSV named with follow-up | — |
| Non-goal leakage (FLAG) | PASS | Additions trace to requirements; no flip-day or F0.2 items | — |

Traceability evidence (summary): Gradle build and toolchain → 1.2, 1.3 (green `build` run); app ID, SDK levels,
desugaring, flavors → 1.5, 1.7 (badging output, desugaring instrumented test); activity and tests → 1.5–1.7 (TDD
red/green runs); debug signing and non-debuggable release → 1.4, 1.7, 1.9 ("Inspect APKs" step); `check`
composition and Lint Security → 1.8, 3.1, 3.3 (job log, failing PR 1.10); workflow rules → 1.6, 4.1, 4.9
(actionlint, zizmor, Angua review); APK upload rules → 1.9, 1.12 (PR and `main` artifacts); `sha_pinning_required`
→ 1.11 (API); device install → 1.12; emulator job → 2.1–2.6 (green run, cache hit, failing PR 2.6); SPDX →
3.1, 3.2; Licensee (disallowed and unidentifiable licenses) → 3.3, 3.4; DCO → 3.5–3.7 (fixture tests, failing PR); root files and README → 3.8, 3.9
(grep checks); zizmor/actionlint → 4.1, 4.11; gitleaks → 4.2, 4.11; OSV PR → 4.3; weekly OSV → 4.4–4.6, 4.11;
Renovate → 4.7, 4.8, 4.12; Dependabot alerts and absence of CodeQL/Dependabot updates → 4.10 (API, file checks).

Open question evidence: OQ1 → Owner sub-tasks 3.10 and 4.8, plus approval-gated 1.11 and 4.10; OQ2 → 4.10 and
assumption 2; OQ3 → 2.5 and assumption 3; `targetSdk` → spec Technical Considerations assumption, 1.3, 1.13 and assumption 4;
Renovate commit verification → spec Unit 3, assumption 5, checked by 4.12 (stop and report if not Verified).

## Standards Evidence Table (Required)

| Source File | Read | Standards Extracted | Conflicts |
| --- | --- | --- | --- |
| `AGENTS.md` | not found | — | — |
| `CLAUDE.md` (repo) | not found | — | — |
| `CONTRIBUTING.md` | not found | — (created by sub-task 3.8) | — |
| `.github/pull_request_template.md`, `.github/workflows/*` | not found | — (no `.github/` directory yet) | — |
| `.pre-commit-config.yaml`, `.editorconfig` | not found | — | — |
| `README.md` | yes | GPL-3.0-or-later with the app-store permission; DCO sign-off (`git commit -s`) on every contribution | none |
| `docs/adr/0003-licensing-and-contributions.md` | yes | SPDX header `GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore`; DCO, no CLA; exception from the first commit | none |
| `docs/MODULES.md` (Accepted) | yes | Versions live in `gradle/libs.versions.toml`; `minSdk 25`, JDK 17 toolchain, desugaring on from day one; ktlint (or Spotless + ktlint), detekt, Android Lint | none (exact versions "picked at scaffold time", which the spec does) |
| `docs/BACKLOG.md` (F0.1) | yes | PRs merged only on green CI (protection by convention until the flip); `spotlessCheck` inside `./gradlew check`; Renovate with 3-day age and no automerge in Phase 0 | none (S0.1.1 already says `spotlessCheck`) |

Precedence: where these sources and the spec differ in detail, the approved spec governs, as it's the latest
decision and cites each source.

## Findings (run 1, resolved)

### FLAG Findings

1. **Some blocking paths were never shown to block** (OSV-Scanner PR gate, fork-PR no-APK gate, Licensee on an
   unidentifiable license), and spec Goals and Success Metric 2 disagreed on what is proven. **Resolved** by the
   approved remediation below.

## User-Approved Remediation Plan

- Completed (approved by the owner 2026-10-11, applied to the spec and tasks files only):
  - Spec `## Goals`, second bullet: a gate is shown to block by a failing PR or verified by inspection with its
    follow-up named. It names OSV-Scanner (follow-up: F0.2 adds `verification-metadata.xml` and a failing-PR
    proof) and the fork-PR no-APK gate (upload-step condition and Angua's review; zizmor also flags
    `pull_request_target`).
  - Spec `## Success Metrics` item 2: lists 13 blocking checks, 12 proven by a failing PR (or the TDD red run),
    with OSV-Scanner by inspection, plus two inspection-verified workflow controls.
  - Spec Unit 3 FR and proofs, tasks 3.0 proofs, 3.5–3.7, 4.0 proofs, 4.12 and assumption 5: the DCO exemption
    needs the `renovate[bot]` Bot account **and** `verification.verified == true`. The first Renovate PR's
    commits must show Verified, or the implementer stops and reports to the owner.
  - Spec `## Technical Considerations`: `targetSdk` assumption (current Google Play requirement, Amazon follows
    Play, value and source recorded in the 1.0 proofs). Reflected in tasks 1.3, 1.13 and assumption 4.
  - Tasks 3.4(b) and the 3.0 proofs: an unidentifiable-license failing PR. Task 4.3: the F0.2 OSV follow-up note.
  - Also added so Success Metric 2's count is true: a failing unit test in 1.10, a ktlint wildcard-import
    violation in 3.2, an undefined `needs` job in the 4.11 canary so actionlint fails deterministically, and an
    explicit fork-gate inspection proof in 1.0.

## Re-Audit Delta (Runs 2+ only)

- Changed gate statuses since previous run: Regression-risk blind spots FLAG → PASS.
- Still-failing REQUIRED gates: none.
- Newly introduced findings: none. Chain-of-verification: each of the 12 failing-PR proofs in spec Success
  Metric 2 was matched to a task (build 1.6 red run; unit tests, Lint Security, detekt 1.10; ktlint, SPDX 3.2;
  Licensee 3.4; DCO 3.7; zizmor, actionlint, gitleaks 4.11; emulator 2.6), and OSV-Scanner's inspection status
  and F0.2 follow-up appear in both the spec and 4.3.
- Not changed here (outside this file set, handled by the coordinator): `docs/BACKLOG.md` S0.1.2's DCO wording
  ("verified bot identity") and any matching GitHub issue text.
