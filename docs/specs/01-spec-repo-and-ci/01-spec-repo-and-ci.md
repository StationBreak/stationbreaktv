# 01-spec-repo-and-ci.md

## Introduction/Overview

StationBreak has a plan and a set of accepted decisions, but no code and no CI. This spec builds the
**continuous-integration foundation** (feature F0.1: stories S0.1.1–S0.1.4, issues #46, #47, #48 and #135)
around a deliberately tiny placeholder Android app. When it's done, every pull request is built, tested on an
API 25 Android TV emulator, style-checked, license-checked, DCO-checked and security-scanned automatically,
and produces an installable test APK. The goal is that agent-written code can be trusted to be checked by
machines, because the owner can't hand-test every PR ("CI first", [ROADMAP.md](../../ROADMAP.md)).

## Goals

- Every pull request and every push to `main` runs a single CI workflow that ends green on the placeholder
  app, with a warm PR run finishing in **under 10 minutes**.
- Each blocking gate (build, tests, lint, style, SPDX header, license allowlist, DCO, workflow security,
  secrets, known vulnerabilities, API 25 emulator) is **shown to block** a deliberately bad PR, or, where that
  isn't possible yet, is verified by inspection with the follow-up named. The inspection-verified gates are:
  - **OSV-Scanner (known vulnerabilities):** it has no Gradle dependency list to scan until F0.2, so it can't
    fail yet. Follow-up: F0.2 adds `gradle/verification-metadata.xml` and, with it, a deliberate failing-PR proof
    that OSV-Scanner blocks a newly introduced vulnerable dependency.
  - **The fork-PR no-APK gate:** verified by inspecting the upload step's
    `github.event.pull_request.head.repo.full_name == github.repository` condition, plus Angua (Security)'s
    review, because a fork PR needs a second GitHub account. The workflow's "never `pull_request_target`" rule
    is also enforced by zizmor, which flags that trigger.
- A sideloadable debug APK is published for `main` and for pull requests from this repository, signed with a
  stable debug key, so each build installs over the previous one ([ADR-0011](../../adr/0011-debug-build-signing.md)).
- The repository has the legal and community files a GPL project needs before outside contributions:
  `LICENSE`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`.
- Dependency updates arrive automatically as reviewable Renovate PRs.

## User Stories

- **As a contributor**, I want CI on every PR so that broken code can't merge (S0.1.1).
- **As the owner**, I want every source file GPL-3.0 headed and every dependency license-checked so that the
  project stays license-clean (S0.1.2).
- **As a tester**, I want CI to publish a debug APK artifact so that I can sideload a build without a dev
  machine (S0.1.3).
- **As the owner**, I want every PR security-scanned automatically so that agent-written code can't ship known
  weaknesses unnoticed (S0.1.4).

## Demoable Units of Work

### Unit 1: Placeholder app and the core CI build

**Purpose:** Gives CI something real to build, and proves the main pipeline: build, check, and a test APK the
owner can install. Serves contributors (S0.1.1) and testers (S0.1.3).

**Functional Requirements:**
- The system shall contain a Gradle build (wrapper, `settings.gradle.kts`, `gradle/libs.versions.toml`) using
  Kotlin 2.4.20, AGP 9.4.1, Gradle ≥ 9.6 and a JDK 17 toolchain, with a single `:app` module.
- The `:app` module shall have application ID `tv.stationbreak.app` and namespace `tv.stationbreak.app`
  ([ADR-0010](../../adr/0010-application-id.md)), `minSdk 25`, `targetSdk` at the current store requirement,
  core library desugaring enabled ([MODULES.md](../../MODULES.md)), and exactly two product flavors, `amazon`
  and `github`.
- The `:app` module shall contain one empty launchable activity, plus one unit test and one instrumented test
  that launches it.
- The debug build type shall use `applicationIdSuffix ".debug"`, be debuggable, and be signed with a committed
  keystore `debug.keystore`, generated fresh for this project and documented in the repo as public and
  throwaway. The release build type shall not be debuggable and shall not reference the debug keystore.
- `./gradlew check` shall run unit tests, Android Lint, detekt (2.0.0-alpha.6, pinned) and Spotless (ktlint plus
  the SPDX header check from Unit 3). Android Lint's `Security` category shall be set to fatal, with
  `abortOnError = true`.
- A GitHub Actions workflow shall run on `pull_request` and on `push` to `main`. It shall never use
  `pull_request_target`. It shall:
  - set top-level `permissions: contents: read`, granting more only to the jobs that need it;
  - pin every action by full commit SHA, with the version in a trailing comment;
  - use `gradle/actions/setup-gradle` (which validates the Gradle wrapper) with `cache-provider: basic`;
  - run `./gradlew check` and assemble the debug and release variants of both flavors;
  - cancel superseded runs on the same branch.
- The workflow shall upload the `github` flavor's debug APK:
  - as `stationbreak-github-debug-main-<shortsha>.apk` on `main`, kept 30 days (set explicitly; owner decision 2026-10-11, changed from the default during /polish);
  - as `stationbreak-github-debug-pr<N>-<shortsha>.apk`, kept 7 days, on pull requests whose head repository is
    this repository (`github.event.pull_request.head.repo.full_name == github.repository`);
  - never for fork pull requests. The gate is on the upload step, not the whole job.
- The repository setting that requires actions to be pinned to a full-length commit SHA
  (`sha_pinning_required`) shall be turned on.
- The owner shall be able to install the PR's debug APK on the reference Insignia and launch it.

**Proof Artifacts:**
- CI run: a green workflow run on the F0.1 pull request demonstrates the build, `check` and both flavors pass.
- CI artifact: `stationbreak-github-debug-pr<N>-<shortsha>.apk` listed on that run demonstrates PR artifacts and
  their naming.
- CLI: `apksigner verify --print-certs` on two APKs from different runs shows the same certificate SHA-256,
  demonstrating the stable debug key; `aapt2 dump badging` shows package `tv.stationbreak.app.debug`.
- Device check: the owner (or the laptop bridge session) installs two successive PR APKs on the Insignia, the
  second over the first without uninstalling, and the activity launches. This demonstrates the
  install-over-previous behaviour from ADR-0011.
- CI run: a timed warm re-run of the PR workflow under 10 minutes demonstrates the build-time target.
- API: `gh api repos/StationBreak/stationbreaktv/actions/permissions` shows `"sha_pinning_required": true`.

### Unit 2: API 25 Android TV emulator job

**Purpose:** Catches crashes from APIs missing on the Insignia's Android version before anyone installs the
APK, so automation, not manual testing, is the main line of defence (S0.1.1).

**Functional Requirements:**
- The workflow shall have a separate emulator job, running in parallel with Unit 1's build job on every PR and
  on `main`.
- The job shall boot an **API 25 Android TV (`android-tv`, x86)** system image on an `ubuntu-latest` runner with
  KVM enabled, using `ReactiveCircus/android-emulator-runner` (pinned by SHA). The emulator snapshot (AVD) shall
  be cached between runs.
- The job shall run all instrumented tests (`connectedCheck` or the per-flavor equivalent) for the `github`
  debug variant. That includes the launch smoke test from Unit 1.
- A failure in any instrumented test, or an emulator that fails to boot, shall fail the job.

**Proof Artifacts:**
- CI run: a green emulator job on the F0.1 PR, with its log showing API 25 and the TV image, demonstrates the
  job works.
- Deliberate failing PR: a throwaway branch whose activity calls an API added in API 26 at launch, for example
  constructing `android.app.NotificationChannel`, with Lint's `NewApi` check suppressed on that line. Lint
  then lets it through, and the emulator job fails. This demonstrates the job catches the class of crash it
  exists for. The PR is closed without merging.

### Unit 3: License and contribution hygiene

**Purpose:** Keeps the project license-clean and contribution-ready before outside contributions or the
visibility flip (S0.1.2).

**Functional Requirements:**
- Spotless shall fail `check` when any `.kt` or `.kts` file lacks the header
  `SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore`
  ([ADR-0003](../../adr/0003-licensing-and-contributions.md)), and `./gradlew spotlessApply` shall insert it.
  `.kts` files shall use an explicit header delimiter so the header goes above `plugins {` / `import` lines.
- The Licensee Gradle plugin (`app.cash.licensee`) shall fail the build on any dependency whose license isn't on
  an allowlist of GPL-3.0-compatible licenses (initially Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause and
  LGPL-2.1-or-later/LGPL-3.0, extended only as real dependencies need it). It shall also fail on licenses it
  can't identify. Per-dependency overrides shall be listed in the build file with a comment giving the
  reason.
- A DCO job shall fail any pull request containing a commit without a `Signed-off-by:` trailer. It shall read
  every commit in the PR range. A commit shall be exempt only if **both** its author is the `renovate[bot]`
  GitHub account (login `renovate[bot]` with `type: Bot`) **and** the commit has a GitHub-verified signature
  (`verification.verified == true` from the commits API). The name or email text alone never exempts a commit,
  because GitHub links a commit's author to an account by email, which anyone can set.
- The repository root shall contain:
  - `LICENSE`: the GPLv3 text plus the app-store additional permission from
    `docs/legal/app-store-exception.md`;
  - `CONTRIBUTING.md`: how to contribute, the DCO and `git commit -s`, the app-store exception, and how CI
    gates PRs;
  - `CODE_OF_CONDUCT.md`: the current Contributor Covenant, with reports to `conduct@stationbreak.tv`;
  - `SECURITY.md`: report vulnerabilities privately to `security@stationbreak.tv`.
- `README.md` shall link to the new root files and say the project builds in CI.

**Proof Artifacts:**
- Deliberate failing PRs, each closed without merging:
  - a `.kt` file without the SPDX header fails `spotlessCheck`;
  - a commit without `Signed-off-by` fails the DCO job, and so does an unsigned commit whose author email is
    `renovate[bot]`'s but which has no verified signature;
  - a dependency with a license that isn't allowlisted fails Licensee, and so does a dependency whose license
    Licensee can't identify.
- CI run: the F0.1 PR passes all three, demonstrating the gates don't block correct work.
- File listing: the four root files exist, and `LICENSE` contains both the GPLv3 text and the exception.

### Unit 4: Security scanning and dependency updates

**Purpose:** Agent-written code and workflow changes are scanned on every PR, and dependency updates arrive on
their own, reviewably (S0.1.4, plus S0.1.1's Renovate criterion).

**Functional Requirements:**
- Every PR shall run, each blocking merge:
  - **zizmor** (via `zizmorcore/zizmor-action`, `advanced-security: false`, failing on high severity) and
    **actionlint** on `.github/workflows/`;
  - **gitleaks**, the CLI run directly (not `gitleaks-action`), over the PR's full commit range. A
    `.gitleaks.toml` allowlists `debug.keystore` by exact path only;
  - **OSV-Scanner** (pinned v2 reusable PR workflow, `upload-sarif: false`), failing only on vulnerabilities
    the PR introduces.
- OSV-Scanner shall also run weekly on `main`, opening an issue for each finding. It won't block anything.
- A `renovate.json` shall configure the hosted Renovate app with:
  - `minimumReleaseAge: "3 days"`, held back strictly (`internalChecksFilter: "strict"`);
  - no automerge;
  - `osvVulnerabilityAlerts: true`;
  - GitHub Actions pinned to digests (`helpers:pinGitHubActionDigests`);
  - grouping of related updates, for example AndroidX/Compose, Kotlin with KSP, and AGP.
- Dependabot alerts shall be enabled on the repository, and Dependabot version-update PRs shall stay off.
- The flip-day checks (CodeQL, secret scanning with push protection, dependency review) shall **not** be added in
  this spec. CodeQL shall not be run while the repository is private.

**Proof Artifacts:**
- Deliberate failing PRs, each closed without merging:
  - a workflow using an unpinned action, or `${{ github.event.pull_request.title }}` inside `run:`, fails
    zizmor;
  - a commit adding a fake credential in a known test format (no real secret) fails gitleaks.
- CI run: the F0.1 PR passes zizmor, actionlint, gitleaks and OSV-Scanner.
- Renovate: an onboarding or first update PR opened by `renovate[bot]` demonstrates the app is installed and
  reads `renovate.json`.
- API: `gh api repos/StationBreak/stationbreaktv/vulnerability-alerts` returning 204 demonstrates Dependabot
  alerts are on.

## Non-Goals (Out of Scope)

1. **The module skeleton, architecture test and dependency verification:** `build-logic`, the day-one modules,
   the module-graph test, the `tools:overrideLibrary` ban and `verification-metadata.xml` all belong to F0.2.
2. **Any real UI:** the placeholder activity has no Compose for TV screen, leanback banner or D-pad focus;
   that's S0.2.2.
3. **Release signing and release guards:** the release key, tag-triggered releases, and the CI checks that a
   release isn't debuggable or signed with the debug certificate are S4.1.1.
4. **Flip-day items:** branch protection, required checks, signed-commit enforcement, CodeQL, secret scanning
   and dependency review are turned on when the repository goes public, not here.
5. **The `play` flavor:** added at F4.3.
6. **Legal review of the app-store exception:** the Software Freedom Conservancy-style review is tracked in
   S0.1.2 for before the first outside contribution, and isn't done by this spec.
7. **MobSF, Snyk, Sonar, Trivy, Semgrep and Dependabot update PRs:** rejected in S0.1.4.

## Design Considerations

No specific design requirements identified. The placeholder app has no user interface beyond an empty
activity.

## Repository Standards

- **Commits:** Conventional Commits style (`docs:`, `ci:`, `build:`, `feat:` …), every commit signed off
  (`git commit -s`) as Phil Critchfield with the GitHub no-reply address.
- **Process:** code changes go through a pull request merged only when CI is green, per the
  branch-protection-by-convention rule in S0.1.1. This spec's own implementation is the first such PR.
- **Docs:** decisions go in ADRs (`docs/adr/`), backlog acceptance criteria stay in sync with their GitHub
  issues, and no personal server or home-network details appear anywhere in the repo.
- **Testing:** test-driven development (red, green, refactor) for code. Each CI gate is proven by a deliberate
  failing PR as well as a passing one.
- **Architecture and versions:** follow [MODULES.md](../../MODULES.md) (Accepted) and the toolchain baseline
  there. Versions live in `gradle/libs.versions.toml`.

## Technical Considerations

- **Toolchain:** Kotlin 2.4.20 is required by jellyfin-sdk-kotlin 1.9.2 (used from F0.3). Choose the AGP and
  Gradle versions to match: AGP 9.4.1 and Gradle 9.6 or later (9.8.0 is current).
- **detekt 2.0.0-alpha.6:** the plugin ID and group changed to `dev.detekt`. It's the only release built for
  Kotlin 2.4. Pin it exactly and let Renovate propose updates. Move to 2.0 stable when it ships.
- **Spotless:** runs ktlint, so the CI task is `spotlessCheck`, not `ktlintCheck`. S0.1.1's text is updated to
  match.
- **Gradle cache:** `setup-gradle` v6's default "enhanced" cache is a proprietary component, offered only as a
  free preview for private repos. Use `cache-provider: basic` (MIT-licensed, wraps `actions/cache`). The cache
  is written only from `main`, and PRs read it.
- **Emulator:**
  - The API 25 TV image exists only for x86, so use `arch: x86`.
  - KVM needs a udev rule step on `ubuntu-latest`.
  - Boot time is unmeasured (estimated 2–5 minutes cold), so the AVD snapshot cache matters.
  - The emulator job runs in parallel, so it doesn't count toward the 10-minute build target. If the TV image
    proves unbootable on hosted runners, fall back to the API 25 `google_apis` x86 image and record why.
- **Licensee and AGP 9:** confirmed working with application modules. A known open issue affects the
  `com.android.kotlin.multiplatform.library` plugin, which matters in F0.2, not here.
- **OSV-Scanner coverage:** OSV-Scanner reads `gradle/verification-metadata.xml` or Gradle lockfiles. Neither
  exists until F0.2, so in F0.1 the job runs and passes but has no Gradle dependency list to scan. It becomes
  effective when F0.2 adds `verification-metadata.xml`. This is expected, not a gap to fix here.
- **minSdk guard:** in F0.1 the manifest merger already fails on any Android library needing minSdk above 25.
  The `tools:overrideLibrary` ban that makes this unbypassable lands with F0.2's architecture checks.
- **Debug keystore:** generate it with `keytool` (RSA 2048 or larger, long validity) with the conventional
  `android` passwords, since it's public by design.
- **`targetSdk` (assumption):** `targetSdk` = the current Google Play target-API requirement at implementation
  time (Amazon follows Play). The implementer confirms it from the official Play policy page and records the
  value and source in the 1.0 proofs.

## Security Considerations

- **No secrets in F0.1.** Nothing in this spec needs a repository secret. The release key is F4.1. CI uses only
  the automatic `GITHUB_TOKEN`, read-only by default.
- **The debug keystore is public on purpose** ([ADR-0011](../../adr/0011-debug-build-signing.md)). It must
  never sign a release build, and it must be allowlisted in gitleaks by exact path only.
- **Fork pull requests** run on `pull_request` only, get no secrets or write tokens, and produce no APK.
- **Supply chain:**
  - every action is pinned by SHA, enforced by the repository setting;
  - zizmor and actionlint check the workflows;
  - Renovate waits 3 days before proposing a new release, except for vulnerability fixes.
- **Proof artifacts must not contain real credentials.** The gitleaks test uses a well-known fake token format
  from gitleaks' own test patterns. The deliberate failing PRs and their branches are deleted after they're
  closed.
- **Personal details:** the contact addresses are `conduct@` and `security@stationbreak.tv` aliases, not the
  owner's personal address. Nothing reveals home-network or server details.
- **Angua (Security)** reviews the workflows, `renovate.json` and `.gitleaks.toml` during implementation.

## Success Metrics

1. **All gates green on the F0.1 PR**, with a warm PR build job finishing in under 10 minutes.
2. **Every blocking gate proven to block, or verified by inspection with its follow-up named.** There are 13
   blocking checks: build, unit tests, Android Lint (Security fatal), detekt, ktlint style, SPDX header,
   Licensee, DCO, zizmor, actionlint, gitleaks, OSV-Scanner and the API 25 emulator. Twelve are proven by a
   deliberate failing PR (the build by the TDD red run on the implementation PR). One, **OSV-Scanner**, is
   verified by inspection until F0.2. Separately, two workflow controls are verified by inspection: the
   **fork-PR no-APK gate** and **never `pull_request_target`** (also flagged by zizmor).
3. **Zero manual build steps:** the owner installs a CI-built PR APK on the Insignia over a previous one,
   without a local build and without uninstalling.
4. **Renovate active:** at least one Renovate PR opened by the end of the spec.

## Open Questions

1. **Owner setup tasks:** the owner needs to install the hosted Renovate app on the StationBreak org, and
   create the `conduct@` and `security@stationbreak.tv` forwarding aliases. Turning on `sha_pinning_required`
   and Dependabot alerts can be done by the implementing session with the owner's approval. Who does each, and
   when?
2. **Dependency graph for Gradle on a private Free repo:** Dependabot alerts for Gradle need the dependency
   graph, which for Gradle means the dependency-submission action. Whether that works on a private Free-plan
   repo should be checked during implementation. If it doesn't, OSV-Scanner remains the CVE gate and this is
   revisited at the visibility flip.
3. **Emulator job time:** if a cold or cached API 25 TV boot pushes the emulator job well past the build job,
   should it get its own time budget, for example 15 minutes? This is non-blocking, and will be measured in
   Unit 2.
