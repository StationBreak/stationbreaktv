# 01-tasks-repo-and-ci.md

Task list for [01-spec-repo-and-ci.md](01-spec-repo-and-ci.md) (F0.1, stories S0.1.1–S0.1.4).

**How verification works here:** the implementing machine has no Android SDK, and the project rule is "CI first"
(nothing is built by hand for a device). All builds and checks are verified by **GitHub Actions runs on the
implementation PR**. Deliberate failing PRs are opened from throwaway branches, recorded, then closed without
merging and their branches deleted.

## Relevant Files

| File | Why It Is Relevant |
| --- | --- |
| `settings.gradle.kts` | Root settings: project name, `:app` include, plugin and dependency repositories. Needs the SPDX header. |
| `build.gradle.kts` | Root build: declares plugins `apply false` (AGP, Kotlin, detekt, Spotless, Licensee) and the root Spotless config. |
| `gradle/libs.versions.toml` | Version catalog: Kotlin 2.4.20, AGP 9.4.1, detekt 2.0.0-alpha.6, Spotless 8.10.4, Licensee 1.14.1, desugar_jdk_libs, AndroidX Test, `minSdk`/`targetSdk`. The single place versions live (MODULES.md). |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 9.8.0 distribution URL and `distributionSha256Sum`. |
| `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat` | Gradle wrapper; validated in CI by `setup-gradle`. |
| `gradle.properties` | JVM args and AndroidX/Kotlin build flags. |
| `.gitignore` | Keeps `build/`, `.gradle/`, `local.properties`, IDE files out of git (none exists today). |
| `.gitattributes` | Marks `*.keystore`/`*.jar` binary and `gradlew` as LF, so the wrapper and keystore aren't corrupted. |
| `debug.keystore` | Committed, public, throwaway debug signing key (ADR-0011). Exact path is allowlisted in gitleaks. |
| `app/build.gradle.kts` | `:app` module: application ID, namespace, SDK levels, desugaring, `amazon`/`github` flavors, debug signing, Lint, detekt, Licensee allowlist. |
| `app/lint.xml` | Only if the Lint DSL won't take the `Security` category name: raises each Security-category issue to fatal (sub-task 1.8). |
| `app/src/main/AndroidManifest.xml` | Declares the single launchable activity (LEANBACK_LAUNCHER and LAUNCHER) and `android:allowBackup="false"`. |
| `app/src/main/kotlin/tv/stationbreak/app/MainActivity.kt` | The empty placeholder activity. |
| `app/src/main/res/values/strings.xml` | `app_name` for the launcher label. |
| `app/src/test/kotlin/tv/stationbreak/app/MainActivityPackageTest.kt` | The one JVM unit test (activity lives in the ADR-0010 package). |
| `app/src/androidTest/kotlin/tv/stationbreak/app/MainActivityLaunchTest.kt` | The instrumented launch smoke test, plus a `java.time` call that proves desugaring works on API 25. |
| `.github/workflows/ci.yml` | The single CI workflow: `build`, `emulator`, `dco`, `zizmor`, `actionlint`, `gitleaks`, `osv-scanner` jobs. |
| `.github/workflows/osv-weekly.yml` | Weekly (and `workflow_dispatch`) OSV-Scanner run on `main` that opens an issue per finding. |
| `.github/scripts/dco-check.sh` | DCO checker: reads the PR's commits as JSON and fails on a commit without `Signed-off-by:`, exempting only verified `renovate[bot]` commits. |
| `.github/scripts/dco-check.test.sh` | Fixture tests for `dco-check.sh`; run in the `dco` job before the real check. |
| `.github/scripts/fixtures/dco/*.json` | DCO fixtures: all signed, one unsigned, verified Renovate bot, spoofed Renovate author. |
| `.github/scripts/osv-issues.sh` | Turns OSV-Scanner JSON output into one de-duplicated GitHub issue per finding (supports `--dry-run`). |
| `.github/scripts/osv-issues.test.sh` | Fixture tests for `osv-issues.sh`. |
| `.github/scripts/fixtures/osv/*.json` | OSV fixtures: no findings, one finding, a finding that already has an open issue. |
| `.gitleaks.toml` | Extends the default gitleaks rules; allowlists `debug.keystore` by exact, anchored path only. |
| `renovate.json` | Hosted Renovate configuration (release age, strict checks, no automerge, OSV alerts, digest pinning, groups). |
| `LICENSE` | GPLv3 text plus the app-store additional permission. |
| `CONTRIBUTING.md` | How to contribute: DCO, `git commit -s`, the app-store exception, CI gates, `spotlessApply`, the public debug key. |
| `CODE_OF_CONDUCT.md` | Contributor Covenant (current version), reports to `conduct@stationbreak.tv`. |
| `SECURITY.md` | Private vulnerability reports to `security@stationbreak.tv`. |
| `README.md` | Status line and links to the new root files; says the project builds in CI. |
| `docs/legal/app-store-exception.md` | Source of the exception text copied into `LICENSE` (read only). |
| `docs/adr/0003-licensing-and-contributions.md`, `docs/adr/0010-application-id.md`, `docs/adr/0011-debug-build-signing.md` | Decisions the implementation must match (read only). |
| `docs/specs/01-spec-repo-and-ci/01-proofs/01-task-01-proofs.md` … `01-task-04-proofs.md` | One proofs file per parent task: run links, job names, sanitized CLI/device output, deliberate-PR records. |

### Notes

- **Testing approach.** There is no local Android SDK, so `./gradlew` tasks run only in CI. TDD (red, green,
  refactor) for code tasks means: push the failing test first, record the red CI run (link and failing job), then
  push the implementation and record the green run. Shell scripts under `.github/scripts/` are tested with their
  `*.test.sh` fixture runners, which work locally (`bash`, `jq`) and in CI. Config-only sub-tasks (YAML, TOML,
  JSON, Markdown) are exempt from TDD, but each still has a proof.
- **Branches and PRs.** Work on one branch, `feat/f0.1-repo-and-ci`, with one implementation PR into `main`,
  merged only when every job is green. **Deliberate failing PRs** are opened from throwaway branches cut from
  `feat/f0.1-repo-and-ci` **with that branch as their base**, so the PR diff is only the bad change and the gates
  are proven before the implementation PR merges. Each is recorded in the parent task's proofs file, closed
  unmerged, and its branch deleted (`git push origin --delete <branch>`). Note: GitHub keeps a closed PR's commits
  reachable via `refs/pull/<N>/head` even after the branch is deleted, so throwaway commits must never contain a
  real secret or personal detail.
- **Commits.** Conventional Commits (`build:`, `ci:`, `feat:`, `test:`, `docs:`, `chore:`), every commit made
  with `git commit -s`, author `Phil Critchfield <7206106+PCritchfield@users.noreply.github.com>`. Commit at
  least once per sub-task group so progress is reviewable.
- **Pinning.** Every `uses:` (actions and reusable workflows) is pinned by full 40-character commit SHA with the
  version in a trailing comment, e.g. `uses: actions/checkout@<sha> # v5.0.0`. Resolve SHAs with
  `gh api repos/<owner>/<repo>/git/ref/tags/<tag>` (dereference annotated tags). Downloaded CLIs (gitleaks,
  actionlint) are pinned by version and verified with `sha256sum -c` against the checksum published with that
  release.
- **No private details.** Never put device IPs, hostnames, serial numbers or home-network details in any file,
  including proofs. Device output from the bridge session is sanitized before it's recorded.
- **Who does what.** Sub-tasks marked **Owner:** are done by Phil, not the implementing session. Sub-tasks that
  change a repository setting say so and need the owner's explicit approval in the session before running.
- **Security review.** Angua (Security) reviews the workflows, the scripts they run, `renovate.json` and
  `.gitleaks.toml` before the implementation PR merges (sub-task 4.9).
- **Post-merge proofs.** A few proofs can only exist after merge: the `main` APK artifact (1.12), the weekly
  OSV `workflow_dispatch` run (4.11), the first Renovate PR (4.12). The parent task is complete only when they
  are recorded.

#### Assumptions carried from the spec's open questions

1. **Owner setup (OQ1):** the owner installs the hosted Renovate app on the StationBreak org (4.8) and creates
   the `conduct@` and `security@stationbreak.tv` forwarding aliases (3.10). The implementing session turns on
   `sha_pinning_required` (1.11) and Dependabot alerts (4.10) only after the owner approves each in the session.
2. **Gradle dependency graph on a private Free repo (OQ2):** Dependabot alerts are enabled regardless; whether
   they can see Gradle dependencies is checked and recorded (4.10). Gradle dependency submission is **not**
   added here (it's a flip-day item in S0.1.4). OSV-Scanner remains the CVE gate.
3. **Emulator time (OQ3):** cold and warm emulator-job durations are measured and recorded (2.5). The job gets a
   generous `timeout-minutes` so it can't hang; whether it needs its own budget is put to the owner with the
   numbers.
4. **`targetSdk` (spec Technical Considerations):** `targetSdk` = the current Google Play target-API
   requirement at implementation time (Amazon follows Play). The implementer confirms it from the official Play
   policy page and records the value and source in the 1.0 proofs (1.3, 1.13).
5. **Renovate commit verification (spec Unit 3):** a commit is DCO-exempt only if its author is the
   `renovate[bot]` account (`type: Bot`) **and** it has a GitHub-verified signature
   (`verification.verified == true`), because GitHub links commit authors to accounts by email alone, which
   anyone can set. This assumes the hosted Renovate app makes API commits that GitHub marks Verified. The first
   Renovate PR (4.12) checks it; if its commits aren't Verified, the implementer stops and reports to the owner
   rather than weakening the check.

## Tasks

### [x] 1.0 Placeholder app and the core CI build

Covers spec Unit 1 (S0.1.1, S0.1.3; ADR-0010, ADR-0011).

#### 1.0 Proof Artifact(s)

- CI run: the implementation PR's `build` job is green, running `./gradlew check --continue` and assembling debug and release variants of `amazon` and `github`. The job log lists the `assembleAmazonDebug`, `assembleAmazonRelease`, `assembleGithubDebug` and `assembleGithubRelease` tasks and the `detekt`, `spotlessCheck`, `lint` and `test` tasks run by `check`. This demonstrates the build and the quality gates pass on the placeholder.
- CI run (TDD red): the run where only the tests were pushed fails, linked in the proofs file, demonstrating test failures block the build.
- CI artifact: `stationbreak-github-debug-pr<N>-<shortsha>.apk` is listed on the PR run with 7-day retention, demonstrating same-repo PR artifacts and their naming.
- CI artifact (post-merge): `stationbreak-github-debug-main-<shortsha>.apk` is listed on the first `main` run after merge, with 30-day retention (owner decision 2026-10-11; was default).
- CLI (in CI, since there's no local SDK): the `build` job's "Inspect APKs" step prints `apksigner verify --print-certs` for the `github` debug APK, and the certificate SHA-256 is identical on two runs from different commits. It also prints `aapt2 dump badging` showing `package: name='tv.stationbreak.app.debug'`, `sdkVersion:'25'` and the chosen `targetSdkVersion`, and, for the `github` release APK, no `application-debuggable` line. This demonstrates the stable debug key, the `.debug` suffix, the SDK levels and a non-debuggable release.
- Device check: the bridge session installs two successive PR APKs on the reference Insignia with `adb install -r` (no uninstall), and `am start` launches the activity. The output is recorded in the proofs file with device identifiers removed. This demonstrates installing over the previous build.
- Deliberate failing PR: a throwaway branch adds a 7-parameter function (detekt `LongParameterList`), sets `android:allowBackup="true"` (Lint `AllowBackup`, Security category) and adds a unit test assertion that fails. The `build` job fails, and with `--continue` its log shows the detekt finding, the Lint error raised to fatal and the failing unit test. This demonstrates detekt, the Lint Security gate and unit tests block.
- Inspection: the PR upload step's `if:` contains `github.event.pull_request.head.repo.full_name == github.repository`, the workflow has no `pull_request_target` trigger, and Angua (Security)'s review (4.9) confirms both. This is the inspection proof for the fork-PR no-APK gate, which can't be exercised without a second GitHub account.
- Record: the chosen `targetSdk` value and the Google Play policy page it came from, demonstrating the spec's `targetSdk` assumption was confirmed.
- CI run: the timing of a warm re-run of the PR's `build` job shows under 10 minutes, demonstrating the build-time target.
- API: `gh api repos/StationBreak/stationbreaktv/actions/permissions` returns `"sha_pinning_required": true`, demonstrating SHA pinning is enforced.

#### 1.0 Tasks

- [x] 1.1 Create branch `feat/f0.1-repo-and-ci` from `main`. Add `.gitignore` (Gradle/Android: `build/`, `.gradle/`, `local.properties`, `.idea/`, `*.iml`, `captures/`, `.kotlin/`) and `.gitattributes` (`*.jar binary`, `*.keystore binary`, `gradlew text eol=lf`, `*.bat text eol=crlf`). Commit as `chore:`.
- [x] 1.2 Add the Gradle wrapper for Gradle 9.8.0: download the `-bin` distribution, verify it against the published `.sha256`, and run its `gradle wrapper --gradle-version 9.8.0 --distribution-type bin --gradle-distribution-sha256-sum <sum>`. If Gradle won't run on the local JDK 27, use a temporary JDK 17 or 21 unpacked in the scratchpad (not installed system-wide). Commit `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` and `gradle-wrapper.properties` (with `distributionSha256Sum`).
- [x] 1.3 Create `gradle/libs.versions.toml` with the versions in the spec (Kotlin 2.4.20, AGP 9.4.1, detekt 2.0.0-alpha.6 with plugin id `dev.detekt`, Spotless 8.10.4, Licensee 1.14.1, `desugar_jdk_libs`, AndroidX Test core/runner/ext-junit, JUnit), plus `minSdk = "25"` and `targetSdk` set to the current Google Play target-API requirement (assumption 4): confirm it on the official Play policy page ("Target API level requirements") on the day, and add a comment with the URL and date. Create `settings.gradle.kts` (project name `stationbreaktv`, `include(":app")`, `google()`/`mavenCentral()`/`gradlePluginPortal()`), root `build.gradle.kts` (all plugins `apply false`) and `gradle.properties` (`org.gradle.jvmargs`, `android.useAndroidX=true`, `kotlin.code.style=official`). Every `.kts` file starts with the SPDX header line from 3.1.
- [x] 1.4 Generate `debug.keystore` at the repo root with `keytool -genkeypair -v -keystore debug.keystore -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=StationBreak Debug (public, throwaway), O=StationBreak"`. Record its SHA-256 fingerprint from `keytool -list -v` for 1.6 and 3.8. Don't reuse any existing key.
- [x] 1.5 TDD red (tests): create `app/build.gradle.kts` with the `com.android.application` and Kotlin plugins only (namespace and `applicationId` `tv.stationbreak.app`, `compileSdk`/`minSdk 25`/`targetSdk` from the catalog, Java and Kotlin JVM toolchain 17, `isCoreLibraryDesugaringEnabled = true` plus the `coreLibraryDesugaring` dependency, `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`, `flavorDimensions += "distribution"` with exactly the `amazon` and `github` flavors). Write `MainActivityPackageTest.kt` (asserts `MainActivity::class.java.packageName == "tv.stationbreak.app"`) and `MainActivityLaunchTest.kt` (`ActivityScenario.launch(MainActivity::class.java)` reaches `RESUMED`, and a second test asserts `java.time.LocalDate.of(2026, 1, 1).dayOfWeek == DayOfWeek.THURSDAY`, which only works on API 25 with desugaring). Don't create the activity yet. Commit, but don't push until 1.6 is committed.
- [x] 1.6 Create `.github/workflows/ci.yml` with the `build` job: `on: pull_request` and `on: push: branches: [main]` (never `pull_request_target`); top-level `permissions: contents: read`; `concurrency: { group: "${{ github.workflow }}-${{ github.head_ref || github.ref }}", cancel-in-progress: true }`; `runs-on: ubuntu-latest`, `timeout-minutes: 20`. Steps: `actions/checkout` (`persist-credentials: false`), `actions/setup-java` (temurin 17), `gradle/actions/setup-gradle` v6.4.0 (`cache-provider: basic`, `cache-read-only: ${{ github.ref != 'refs/heads/main' }}`), then `./gradlew check assembleAmazonDebug assembleAmazonRelease assembleGithubDebug assembleGithubRelease --continue`. Pin every action by SHA (see Notes). Push the branch, open the implementation PR (draft) and record the red run: compilation of the tests fails because `MainActivity` doesn't exist.
- [x] 1.7 TDD green (activity and signing): add `MainActivity.kt` (extends `android.app.Activity`, empty `onCreate` calling `super`), `strings.xml`, and `AndroidManifest.xml` (activity `exported="true"` with `MAIN` + `LAUNCHER` + `LEANBACK_LAUNCHER`, `<uses-feature android:name="android.software.leanback" android:required="false"/>`, `<uses-feature android:name="android.hardware.touchscreen" android:required="false"/>`, `android:allowBackup="false"`). In `app/build.gradle.kts` add a `debug` signing config pointing at `rootProject.file("debug.keystore")` (passwords `android`, alias `androiddebugkey`) with a comment saying it's public, throwaway and debug-only (ADR-0011). The `debug` build type sets `applicationIdSuffix = ".debug"`, `isDebuggable = true` and that signing config; the `release` build type sets `isDebuggable = false` and **no** signing config. Push and record the green `build` run.
- [x] 1.8 Wire the quality gates into `check`: apply detekt (`dev.detekt`, default config, `buildUponDefaultConfig = true`, no baseline) and configure `lint { abortOnError = true; checkReleaseBuilds = true; fatal += "Security" }`. If the DSL doesn't accept a category name, add `app/lint.xml` raising each Security-category issue ID to `severity="fatal"` and record why. Confirm from the CI log which Lint variant(s) `check` runs and that `lintVital*` runs during each `assemble*Release`; if `check` lints only one flavor, add the `github` and `amazon` debug lint tasks to `check` explicitly. Spotless and Licensee are added in 3.1 and 3.3. Push and keep `build` green.
- [x] 1.9 Add the APK steps to `build`: compute `shortsha` from `github.event.pull_request.head.sha || github.sha` (first 7 chars) in an `env:` value, never by inlining event fields into `run:`; copy the `github` debug APK to `stationbreak-github-debug-pr<N>-<shortsha>.apk` or `stationbreak-github-debug-main-<shortsha>.apk`. Upload with `actions/upload-artifact`: the PR step has `if: github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name == github.repository` and `retention-days: 7`; the `main` step has `if: github.event_name == 'push' && github.ref == 'refs/heads/main'` and `retention-days: 30` (owner decision 2026-10-11; was default retention). Use `archive: false` if the pinned version supports it. Add an informational "Inspect APKs" step that runs `$ANDROID_HOME/build-tools/<ver>/apksigner verify --print-certs` on the debug APK and `aapt2 dump badging` on the debug and release APKs.
- [x] 1.10 Deliberate failing PR (detekt + Lint Security + unit tests): from a throwaway branch based on `feat/f0.1-repo-and-ci`, add a 7-parameter function to `MainActivity.kt`, set `android:allowBackup="true"`, and add `assertEquals(1, 2)` to a new test in `MainActivityPackageTest.kt`. Open the PR against `feat/f0.1-repo-and-ci`, confirm `build` fails with all three findings in the log, record the PR link and failing job, close it unmerged and delete the branch.
- [x] 1.11 Measure and enforce: re-run the green PR `build` job once the cache is warm and record its duration (target under 10 minutes; if over, record the slow steps and raise it with the owner before merge). With the owner's explicit approval, read `gh api repos/StationBreak/stationbreaktv/actions/permissions`, then `PUT` it back with the same `enabled`/`allowed_actions` values and `sha_pinning_required=true`. Re-run CI to show it still goes green.
- [x] 1.12 Device and post-merge proofs: give the bridge session the run IDs of two successive green PR runs (different commits). It downloads each APK with `gh run download`, checks the SHA in the file name matches the PR head commit, installs the first, then the second with `adb install -r`, and launches with `adb shell am start -n tv.stationbreak.app.debug/tv.stationbreak.app.MainActivity`. After the implementation PR merges, confirm the `main` run lists `stationbreak-github-debug-main-<shortsha>.apk`.
- [x] 1.13 Record all 1.0 proofs in `docs/specs/01-spec-repo-and-ci/01-proofs/01-task-01-proofs.md`: red and green run links, job log excerpts (task list, `check` tasks), artifact names and retention, the two certificate SHA-256 values and badging output, the release APK's missing `application-debuggable`, the `targetSdk` value with its Play policy source and date, the fork-PR gate inspection (the `if:` line and Angua's confirmation), sanitized device output, the detekt/Lint/unit-test failing PR, the warm build time, and the `sha_pinning_required` API output.

### [ ] 2.0 API 25 Android TV emulator job

Covers spec Unit 2 (S0.1.1).

#### 2.0 Proof Artifact(s)

- CI run: the PR's `emulator` job is green, and its log shows `system-images;android-25;android-tv;x86` booting and the launch and desugaring instrumented tests passing (`connectedGithubDebugAndroidTest`). This demonstrates the job runs the instrumented tests on API 25.
- CI run: the job has no `needs:` on `build`, and the run's timeline shows both jobs starting together. A second run's log shows an AVD cache hit. This demonstrates the job runs in parallel and the snapshot is cached.
- Deliberate failing PR: a throwaway branch whose activity constructs `android.app.NotificationChannel` (API 26) at launch, with `@SuppressLint("NewApi")`. The `build` job is green and the `emulator` job fails with `NoClassDefFoundError`/`NoSuchMethodError`. This demonstrates the job catches crashes from APIs missing on API 25. The PR link and failing job are recorded.
- Timing: cold and warm (cache-hit) `emulator` job durations recorded, answering the spec's open question 3.

#### 2.0 Tasks

- [ ] 2.1 Add an `emulator` job to `ci.yml` with no `needs:`, `runs-on: ubuntu-latest`, `timeout-minutes: 45`, and job-level `permissions: contents: read`. Steps: checkout (`persist-credentials: false`), setup-java 17, setup-gradle (same settings as `build`), then the KVM udev step: `echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules` followed by `sudo udevadm control --reload-rules && sudo udevadm trigger --name-match=kvm`.
- [ ] 2.2 Add the AVD cache: `actions/cache/restore` keyed on `avd-api25-android-tv-x86-v1` for `~/.android/avd/*` and `~/.android/adb*`; on a miss, run `ReactiveCircus/android-emulator-runner` v2.38.0 once with `force-avd-creation: false` and `script: echo "Generated AVD snapshot"` to create the snapshot. Save it with `actions/cache/save` only when `github.ref == 'refs/heads/main'` (PRs read, never write, like the Gradle cache).
- [ ] 2.3 Add the test step: `ReactiveCircus/android-emulator-runner` with `api-level: 25`, `target: android-tv`, `arch: x86`, `force-avd-creation: false`, `emulator-options: -no-snapshot-save -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -camera-back none`, `disable-animations: true`, and `script: ./gradlew connectedGithubDebugAndroidTest`. A boot timeout or any failed test fails the step and the job (the action's default behaviour; don't add `continue-on-error`).
- [ ] 2.4 Push and get the job green. If the `android-tv` image won't boot on hosted runners after two attempts with different emulator options, switch to `target: google_apis` (API 25, x86) and record why in the proofs file and as a comment in `ci.yml`, per the spec's fallback.
- [ ] 2.5 Measure: record the cold (cache miss) and warm (cache hit) durations of the `emulator` job, and the `build` job's duration on the same run. Put the numbers to the owner with a recommended budget (spec open question 3) and set `timeout-minutes` to match.
- [ ] 2.6 Deliberate failing PR: from a throwaway branch based on `feat/f0.1-repo-and-ci`, change `MainActivity.onCreate` to construct `android.app.NotificationChannel("x", "x", NotificationManager.IMPORTANCE_LOW)`, annotated `@SuppressLint("NewApi")`. Open it against `feat/f0.1-repo-and-ci`. Confirm `build` is green and `emulator` fails in `MainActivityLaunchTest` with `NoClassDefFoundError` (or `NoSuchMethodError`/`VerifyError`). Record the PR link and the failing log excerpt, close it unmerged and delete the branch.
- [ ] 2.7 Record all 2.0 proofs in `docs/specs/01-spec-repo-and-ci/01-proofs/01-task-02-proofs.md`: green run link, log excerpt showing the system image and test results, the parallel-start evidence, the AVD cache-hit line, the timings and recommended budget, and the failing PR record (plus the `google_apis` fallback reason, if used).

### [ ] 3.0 License and contribution hygiene

Covers spec Unit 3 (S0.1.2; ADR-0003).

#### 3.0 Proof Artifact(s)

- Deliberate failing PRs, each recorded and then closed:
  - a `.kt` file without the SPDX header and with a wildcard import fails `spotlessCheck`, naming both the missing header and ktlint's `no-wildcard-imports`. On the same throwaway branch, a temporary CI step running `./gradlew spotlessApply` followed by `git diff` shows the header inserted above `package`, and for a `.kts` file above `plugins {`;
  - a commit without `Signed-off-by:` fails the `dco` job, and a second unsigned commit with its author set to `renovate[bot]`'s no-reply address (so the API reports the `renovate[bot]` Bot account) but no verified signature also fails it, demonstrating the exemption needs both the bot account and a verified signature;
  - a dependency whose license isn't on the allowlist (`junit:junit:4.13.2`, EPL-1.0, added to `implementation`) fails Licensee (`licensee` task);
  - a dependency whose license Licensee can't identify (a fixture artifact whose POM has no `<licenses>` block) fails Licensee.
- Test: `bash .github/scripts/dco-check.test.sh` passes locally and in the `dco` job, covering signed, unsigned, verified-Renovate and spoofed-Renovate fixtures.
- CI run: the implementation PR passes `spotlessCheck`, `dco` and `licensee`, demonstrating the gates don't block correct work.
- File listing: `LICENSE`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md` and `SECURITY.md` exist at the repo root, and `grep -c "AdditionRef-StationBreak-AppStore\|GNU GENERAL PUBLIC LICENSE" LICENSE` finds both the GPLv3 text and the exception. `grep -l "conduct@stationbreak.tv" CODE_OF_CONDUCT.md`, `grep -l "security@stationbreak.tv" SECURITY.md` and `grep -c "git commit -s\|Signed-off-by" CONTRIBUTING.md` each match, and `README.md` links all four files.

#### 3.0 Tasks

- [ ] 3.1 Add Spotless 8.10.4 to every project via the root `build.gradle.kts`: a `kotlin` target (`src/**/*.kt`) and a `kotlinGradle` target (`*.gradle.kts`, `app/*.gradle.kts`), each running ktlint and `licenseHeader("// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore", <delimiter>)`. For `.kts` use an explicit delimiter that matches the first non-comment line (for example `^(?!//).*$` or `^(plugins|import|pluginManagement|rootProject)`), and confirm `check` depends on `spotlessCheck`. Prove the delimiter is idempotent: in 3.2's run, a second `spotlessApply` yields no further diff.
- [ ] 3.2 Deliberate failing PR (SPDX): from a throwaway branch based on `feat/f0.1-repo-and-ci`, add `app/src/main/kotlin/tv/stationbreak/app/NoHeader.kt` without the header and with `import java.util.*` used by one declaration, and remove the header from `app/build.gradle.kts`. Confirm `spotlessCheck` fails, naming both files and ktlint's `no-wildcard-imports` rule. Then add a temporary step to the throwaway branch's `build` job running `./gradlew spotlessApply && ./gradlew spotlessApply && git diff` and record the diff showing the header inserted above `package` and above `plugins {`, with nothing added on the second apply. Close unmerged and delete the branch.
- [ ] 3.3 Apply `app.cash.licensee` 1.14.1 in `app/build.gradle.kts` with `allow("Apache-2.0")`, `allow("MIT")`, `allow("BSD-2-Clause")`, `allow("BSD-3-Clause")`, `allow("LGPL-2.1-or-later")`, `allow("LGPL-3.0-only")`, `allow("LGPL-3.0-or-later")`. Make sure unused allowlist entries don't fail the build (Licensee's unused-entry setting) and unknown licenses do (the default). Confirm `check` runs the `licensee` tasks for both flavors. If a real dependency needs an override (for example `desugar_jdk_libs`, GPL-2.0 with Classpath Exception, if Licensee checks it), add `allowDependency(...)` with a `// reason:` comment and raise it with the owner before merge.
- [ ] 3.4 Deliberate failing PRs (Licensee), each from its own throwaway branch based on `feat/f0.1-repo-and-ci`, opened against it, recorded, closed unmerged and the branch deleted:
  - (a) Disallowed license: add `implementation("junit:junit:4.13.2")` (EPL-1.0) to `app/build.gradle.kts`. Confirm the `build` job fails in a `licensee*` task naming `junit:junit` and EPL-1.0.
  - (b) Unidentifiable license: add a file-based Maven repository fixture at `licensee-fixture/repo/tv/stationbreak/fixture/no-license/1.0/` containing `no-license-1.0.pom` (with `groupId`, `artifactId`, `version`, `packaging jar` and **no** `<licenses>` block) and an empty `no-license-1.0.jar`; register it with `maven { url = uri("licensee-fixture/repo") }` in `settings.gradle.kts`, and add `implementation("tv.stationbreak.fixture:no-license:1.0")`. Confirm the `build` job fails in a `licensee*` task reporting no license or an unknown license for `tv.stationbreak.fixture:no-license`. If Licensee passes, stop: the unknown-license requirement isn't met, and the fix (Licensee configuration) is made on the implementation branch and this PR re-run.
- [ ] 3.5 TDD red: write `.github/scripts/dco-check.test.sh` and fixtures in `.github/scripts/fixtures/dco/`, each a JSON array shaped like `GET /repos/{owner}/{repo}/pulls/{n}/commits`: `all-signed.json` (expect exit 0), `one-unsigned.json` (expect exit 1 naming the SHA), `renovate-verified.json` (unsigned, `author.login == "renovate[bot]"`, `author.type == "Bot"`, `commit.verification.verified == true`; expect 0), `renovate-spoofed.json` (same login and type but `verified == false`; expect 1), `renovate-name-only.json` (`commit.author.name == "renovate[bot]"` but `author.type == "User"`; expect 1), `null-author.json` (unsigned, `author: null`; expect 1), and `250-commits.json` (expect 1: the API caps at 250, so fail closed). Run it locally and see it fail because the script doesn't exist.
- [ ] 3.6 TDD green: write `.github/scripts/dco-check.sh` reading the JSON on stdin with `jq`. A commit passes if its message has a line matching `^Signed-off-by: .+ <.+>$`, or if it's exempt (all three of `author.login == "renovate[bot]"`, `author.type == "Bot"` and `commit.verification.verified == true`). Print each failing SHA and exit 1; exit 1 on 250 or more commits. Run the test script until green; refactor.
- [ ] 3.7 Add the `dco` job to `ci.yml`: `if: github.event_name == 'pull_request'`, `permissions: { contents: read, pull-requests: read }`, checkout (`persist-credentials: false`), run `bash .github/scripts/dco-check.test.sh`, then `gh api --paginate --slurp "repos/${GITHUB_REPOSITORY}/pulls/${PR_NUMBER}/commits" | jq 'add' | bash .github/scripts/dco-check.sh` with `PR_NUMBER: ${{ github.event.pull_request.number }}` and `GH_TOKEN: ${{ github.token }}` passed through `env:`. Then the deliberate failing PR: from a throwaway branch based on `feat/f0.1-repo-and-ci`, add one commit without `-s`, and one commit with `--author "renovate[bot] <29139614+renovate[bot]@users.noreply.github.com>"` and no sign-off. Confirm `dco` fails naming both SHAs. Record, close unmerged and delete the branch.
- [ ] 3.8 Write `LICENSE`: the unmodified GPLv3 text from `https://www.gnu.org/licenses/gpl-3.0.txt`, followed by the "Additional permission under GNU GPL version 3 section 7" block copied verbatim from `docs/legal/app-store-exception.md`, introduced by the SPDX expression. Write `CONTRIBUTING.md`: how to open a PR, the DCO and `git commit -s` (with an example trailer), the app-store exception and what contributors agree to, the CI gates and how to fix them (`./gradlew spotlessApply`), Conventional Commits, and a "Debug signing key" section saying `debug.keystore` is public, throwaway and debug-only, with its SHA-256 fingerprint from 1.4.
- [ ] 3.9 Write `CODE_OF_CONDUCT.md` from the current Contributor Covenant text (check contributor-covenant.org for the latest version, and keep its attribution), with reports to `conduct@stationbreak.tv`. Write `SECURITY.md`: report vulnerabilities privately to `security@stationbreak.tv`, not in public issues; supported versions are "the latest `main` build" until there's a release. Update `README.md`: change the status line to say the project builds in CI, and link `LICENSE`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md` and `SECURITY.md`. Run the grep checks from the proof artifacts.
- [ ] 3.10 **Owner:** create the `conduct@stationbreak.tv` and `security@stationbreak.tv` forwarding aliases, and send a test mail to each. The implementing session records only "aliases confirmed working on <date>" in the proofs file, never the forwarding destination. This doesn't block the PR merging, but must be done before the visibility flip.
- [ ] 3.11 Record all 3.0 proofs in `docs/specs/01-spec-repo-and-ci/01-proofs/01-task-03-proofs.md`: the four deliberate failing PRs (SPDX + ktlint, DCO, Licensee disallowed, Licensee unidentifiable: links, failing jobs, log excerpts), the `spotlessApply` diff, the DCO fixture test output (red and green), the passing run on the implementation PR, the grep outputs, and the owner's alias confirmation.

### [ ] 4.0 Security scanning and dependency updates

Covers spec Unit 4 (S0.1.4; S0.1.1's Renovate criterion).

#### 4.0 Proof Artifact(s)

- Deliberate failing PRs, each recorded and then closed:
  - a workflow with `${{ github.event.pull_request.title }}` inside `run:` fails `zizmor` (template injection, high), and the same workflow's reference to an undefined job (`needs.missing`) fails `actionlint`; whether actionlint also flags the title as untrusted input is recorded;
  - a commit adding a fake credential in a gitleaks test format (a random value matching the `generic-api-key` rule, not a real provider's token format) fails `gitleaks`.
- CI run: the implementation PR passes `zizmor`, `actionlint`, `gitleaks` and `osv-scanner`.
- Test: `bash .github/scripts/osv-issues.test.sh` passes, showing one issue planned for a one-finding fixture, none for a clean fixture, and none for a finding that already has an open issue.
- Workflow run: a manual (`workflow_dispatch`) run of the weekly OSV-Scanner workflow on `main` completes, demonstrating the scheduled scan is wired.
- Renovate: an onboarding or update PR authored by `renovate[bot]`, demonstrating the app is installed and reads `renovate.json`. `gh api repos/StationBreak/stationbreaktv/pulls/<N>/commits --jq '.[] | [.author.login, .author.type, .commit.verification.verified]'` shows `renovate[bot]`, `Bot`, `true` for every commit, and its `dco` job is green, demonstrating the bot exemption works on a real Renovate commit. This depends on the owner installing the app.
- CLI: `npx --yes --package renovate -- renovate-config-validator --strict renovate.json` reports the config valid.
- API: `gh api repos/StationBreak/stationbreaktv/vulnerability-alerts` returns HTTP 204, demonstrating Dependabot alerts are on. `ls .github/dependabot.yml` and `grep -rl codeql .github/` find nothing, demonstrating Dependabot version updates and CodeQL are not added.
- Review: Angua (Security)'s ruling on the workflows, scripts, `renovate.json` and `.gitleaks.toml` is recorded, with every finding resolved or explicitly accepted by the owner.

#### 4.0 Tasks

- [ ] 4.1 Add the `zizmor` job to `ci.yml`: `zizmorcore/zizmor-action` v0.6.4 with `advanced-security: false` and `min-severity: high`, `permissions: contents: read`, checkout with `persist-credentials: false`. Add the `actionlint` job: download `actionlint_1.7.12_linux_amd64.tar.gz` from the rhysd/actionlint release, verify it against that release's `checksums.txt` with `sha256sum -c`, and run `./actionlint -color` (shellcheck is on the runner, so `run:` scripts are linted too). Fix anything either tool reports on the existing workflows, at every severity zizmor shows, even though only high fails.
- [ ] 4.2 Write `.gitleaks.toml`: `[extend] useDefault = true`, and an `[[allowlists]]` entry with `paths = ['''^debug\.keystore$''']` (anchored, exact path, nothing else). Add the `gitleaks` job: `if: github.event_name == 'pull_request'`, checkout with `fetch-depth: 0` and `persist-credentials: false`, download `gitleaks_8.30.1_linux_x64.tar.gz`, verify against the release's checksums file, then run `gitleaks git --config .gitleaks.toml --log-opts="${BASE_SHA}..${HEAD_SHA}" --redact --no-banner --exit-code 1` with `BASE_SHA`/`HEAD_SHA` from `github.event.pull_request.base.sha`/`head.sha` passed via `env:`. Confirm the implementation PR (which contains `debug.keystore`) passes.
- [ ] 4.3 Add the `osv-scanner` job to `ci.yml`: `uses: google/osv-scanner-action/.github/workflows/osv-scanner-reusable-pr.yml@<sha> # v2.6.0` with `with: upload-sarif: false`. Read that reusable workflow at the pinned SHA and grant only the `permissions:` it needs. If it fails because there are no lockfiles to scan (expected in F0.1, see the spec), add the scanner's allow-no-lockfiles option to its scan args and record why; it must still fail on new vulnerabilities once F0.2 adds `verification-metadata.xml`. **F0.2 follow-up:** OSV-Scanner is verified by inspection only in F0.1 (spec Goals and Success Metric 2). F0.2's task list must include a deliberate failing PR that adds a dependency with a known vulnerability once `gradle/verification-metadata.xml` exists, and shows `osv-scanner` failing. Record this follow-up in the 4.0 proofs file.
- [ ] 4.4 TDD red: write `.github/scripts/osv-issues.test.sh` and fixtures in `.github/scripts/fixtures/osv/` (`clean.json`, `one-finding.json`, `already-open.json`, in OSV-Scanner `--format json` shape, plus a stub list of open issue titles). The test runs `osv-issues.sh --dry-run` and expects 0, 1 and 0 planned issues respectively, with titles like `OSV: <vuln id> in <package>@<version>`. Run it and see it fail.
- [ ] 4.5 TDD green: write `.github/scripts/osv-issues.sh`: parse the JSON with `jq`, build one title per (vulnerability, package, version), skip titles that already have an open issue (`gh issue list --state open --search` in live mode, the stub list in `--dry-run`), and `gh issue create --title ... --body ...` for the rest. Run the tests until green; refactor.
- [ ] 4.6 Write `.github/workflows/osv-weekly.yml`: `on: schedule` (weekly cron) and `workflow_dispatch`; top-level `permissions: contents: read`; one job with `permissions: { contents: read, issues: write }` that checks out `main`, runs the pinned `google/osv-scanner-action/osv-scanner-action` (v2.6.0) with `--format json --output osv.json` (non-blocking: a finding doesn't fail the job), runs `bash .github/scripts/osv-issues.test.sh`, then `bash .github/scripts/osv-issues.sh osv.json` with `GH_TOKEN: ${{ github.token }}`. It's linted by the PR's `actionlint` and `zizmor` jobs.
- [ ] 4.7 Write `renovate.json`: `"extends": ["config:recommended", "helpers:pinGitHubActionDigests"]`, `"minimumReleaseAge": "3 days"`, `"internalChecksFilter": "strict"`, `"automerge": false`, `"osvVulnerabilityAlerts": true`, `"dependencyDashboard": true`, and `packageRules` grouping AndroidX/Compose (`matchPackageNames` `androidx.**`), Kotlin with KSP (`org.jetbrains.kotlin**` and `com.google.devtools.ksp**`), and AGP (`com.android.tools.build**` and `com.android.application`). Validate with `npx --yes --package renovate -- renovate-config-validator --strict renovate.json`; if Node isn't available locally, run it once in a throwaway CI step and record the output.
- [ ] 4.8 **Owner:** install the hosted Renovate GitHub App on the StationBreak org with access to `stationbreaktv` only, and tell the implementing session when it's done.
- [ ] 4.9 Security review: ask Angua (Security) to review `ci.yml`, `osv-weekly.yml`, `.github/scripts/*.sh`, `renovate.json` and `.gitleaks.toml`. Specifically ask her to confirm no `pull_request_target`; least-privilege `permissions:` per job; every `uses:` SHA-pinned; no event fields inlined into `run:`; `persist-credentials: false`; caches written only from `main`; the fork-PR APK gate on the upload step; checksum-verified CLI downloads; and the DCO bot-exemption logic. Resolve every finding before merge, or record the owner's explicit acceptance.
- [ ] 4.10 Dependabot: with the owner's explicit approval in the session, run `gh api -X PUT repos/StationBreak/stationbreaktv/vulnerability-alerts` and confirm the `GET` returns 204. Confirm no `.github/dependabot.yml` exists and version-update PRs stay off. Check whether alerts can see Gradle dependencies on this private Free repo (`gh api repos/StationBreak/stationbreaktv/dependency-graph/sbom` and the repo's Dependency graph page) and record the answer (spec open question 2). Don't add Gradle dependency submission; that's a flip-day item.
- [ ] 4.11 Deliberate failing PRs and post-merge runs: (a) from a throwaway branch based on `feat/f0.1-repo-and-ci`, add `.github/workflows/zizmor-canary.yml` (`on: pull_request`, `permissions: {}`, one job with `if: ${{ needs.missing.result == 'success' }}` and one step `run: echo "${{ github.event.pull_request.title }}"`). Confirm `zizmor` fails on template injection and `actionlint` fails on the undefined `needs.missing` job, and record whether actionlint also flags the title as untrusted input. (b) From another throwaway branch, add `app/src/main/res/raw/canary.properties` containing `api_key = <random 32-char alphanumeric value>`, and confirm `gitleaks` fails on `generic-api-key` with the value redacted in the log. Record, close both unmerged and delete both branches. After merge, run `gh workflow run osv-weekly.yml --ref main` and record the completed run.
- [ ] 4.12 After merge and 4.8, wait for the first `renovate[bot]` PR (or the Dependency Dashboard issue followed by an update PR). Record its link. Check that every commit in it shows Verified: `gh api repos/StationBreak/stationbreaktv/pulls/<N>/commits --jq '.[] | [.sha, .author.login, .author.type, .commit.verification.verified]'` must show `renovate[bot]`, `Bot` and `true` for each commit (assumption 5). If any commit isn't Verified, **stop and report to the owner**; don't weaken the DCO check. Otherwise confirm its `dco` job is green and that its CI runs all gates. Leave it for the owner to review; no automerge.
- [ ] 4.13 Record all 4.0 proofs in `docs/specs/01-spec-repo-and-ci/01-proofs/01-task-04-proofs.md`: the deliberate failing PRs, the passing scanner jobs on the implementation PR, the OSV fixture test output (red and green), the `workflow_dispatch` run, the Renovate validator output and first Renovate PR, the Dependabot API output and the open question 2 answer, the absence checks, and Angua's ruling with how each finding was resolved.
