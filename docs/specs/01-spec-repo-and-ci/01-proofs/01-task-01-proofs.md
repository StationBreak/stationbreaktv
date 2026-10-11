# Task 01 Proofs - Placeholder app and the core CI build

> **Status: in progress.** Sub-tasks 1.1–1.10 and 1.11's SHA-pinning step are done and proven below. Items
> marked **PENDING** need the laptop bridge session (1.12 device check), a `main` run
> after merge (1.11 warm-cache timing, 1.12 `main` artifact) or Angua (Security)'s review in 4.9 (fork-gate
> confirmation).

## Task Summary

This task gives CI something real to build: a Gradle 9.8.0 / AGP 9.4.1 / Kotlin 2.4.20 project with one `:app`
module (`tv.stationbreak.app`, `minSdk 25`, `targetSdk 34`, desugaring, `amazon` and `github` flavors), a
public debug keystore (ADR-0011), and a `build` CI job that runs `check`, assembles all four variants, inspects
the APKs and publishes a sideloadable debug APK for same-repo pull requests.

## What This Task Proves

- The build, unit tests, detekt and Lint (with every Security-category issue fatal) run on every PR, and each
  blocks a bad change.
- Each debug build is signed with the same committed key, so it installs over the previous one, and uses the
  `.debug` application ID suffix. The release build isn't debuggable.
- PR APKs are named `stationbreak-github-debug-pr<N>-<shortsha>.apk`, kept 7 days, and uploaded only for
  same-repo PRs.
- The build job takes about 4m15s even with an empty Gradle cache, well under the 10-minute target.

## Evidence Summary

| Proof | Result |
| --- | --- |
| TDD red | [run 38167186619](https://github.com/StationBreak/stationbreaktv/actions/runs/38167186619): `Unresolved reference 'MainActivity'` |
| TDD green | [run 38167722830](https://github.com/StationBreak/stationbreaktv/actions/runs/38167722830), commit `f902825` |
| Latest green `build` | [run 38170364485](https://github.com/StationBreak/stationbreaktv/actions/runs/38170364485), commit `68f0c75` |
| PR artifact | `stationbreak-github-debug-pr136-68f0c75.apk`, expires 7 days after upload |
| Debug certificate SHA-256 | `574be875…ba80b1` on runs from commits `5112b4e`, `69dd39b`, `2886f91` and `68f0c75` |
| Badging | debug `tv.stationbreak.app.debug`, `minSdkVersion:'25'`, `targetSdkVersion:'34'`; release has no `application-debuggable` |
| Failing PR (1.10) | [PR #137](https://github.com/StationBreak/stationbreaktv/pull/137), [run 38170691012](https://github.com/StationBreak/stationbreaktv/actions/runs/38170691012): detekt, Lint Security and unit test all fail |
| `targetSdk` | 34, from Google Play's Android TV target API requirement (checked 2026-10-11) |
| Build time | 4m19s cold-cache re-run (attempt 2 of run 38170364485); warm-cache timing **PENDING** first `main` run |
| `sha_pinning_required` | `true` (owner, 2026-10-11); CI green under enforcement (run 38176295040 attempt 2) |
| Device install-over | **PENDING** bridge session; run IDs handed over below |

## Artifact: TDD red run

**What it proves:** test failures block the build; the tests were pushed before the activity existed.

**Why it matters:** the `build` gate is shown to block (spec Success Metric 2, "the build by the TDD red run").

**Result summary:** the first push ([run 38167012283](https://github.com/StationBreak/stationbreaktv/actions/runs/38167012283),
commit `9e1ad9b`) failed earlier than intended, because the module had no `AndroidManifest.xml` yet. Commit
`e0f6e89` added a manifest with no activity, and [run 38167186619](https://github.com/StationBreak/stationbreaktv/actions/runs/38167186619)
then failed exactly as task 1.6 expects: the unit test can't compile without `MainActivity`.

```text
e: file://app/src/test/kotlin/tv/stationbreak/app/MainActivityPackageTest.kt:11:45 Unresolved reference 'MainActivity'.
> Task :app:compileAmazonDebugUnitTestKotlin FAILED
> Task :app:compileGithubDebugUnitTestKotlin FAILED
BUILD FAILED in 3m 1s
```

## Artifact: green `build` job and the tasks `check` runs

**What it proves:** the placeholder builds, and `check` runs detekt (including type resolution), both flavors'
unit tests and both flavors' Lint before all four variants are assembled.

**Why it matters:** this is the main pipeline every PR goes through.

**Command (CI):** `./gradlew check assembleAmazonDebug assembleAmazonRelease assembleGithubDebug assembleGithubRelease --continue`

**Result summary:** [run 38170364485](https://github.com/StationBreak/stationbreaktv/actions/runs/38170364485)
(job 114563459981) is green. Task excerpt:

```text
> Task :app:assembleAmazonDebug
> Task :app:assembleAmazonRelease
> Task :app:assembleGithubDebug
> Task :app:assembleGithubRelease
> Task :app:check
> Task :app:detekt
> Task :app:detektMain
> Task :app:detektTest
> Task :app:lint
> Task :app:lintAmazonDebug
> Task :app:lintGithubDebug
> Task :app:lintVitalAmazonRelease SKIPPED
> Task :app:lintVitalGithubRelease SKIPPED
> Task :app:test
> Task :app:testAmazonDebugUnitTest
> Task :app:testGithubDebugUnitTest
BUILD SUCCESSFUL in 3m 57s
```

`spotlessCheck` isn't listed yet; Spotless is added in 3.1.

**Lint variants (1.8):** `lint` on its own checks only the default variant, `amazonDebug`. `check` now also
depends on `lintAmazonDebug` and `lintGithubDebug`. The `lintVital*Release` report tasks show `SKIPPED` when a full
`lint` task is in the same build: AGP skips lintVital then, and runs only `lintVitalAnalyze*Release`. So the
release variants get no separate fatal-only Lint report in this CI invocation. The debug variants' full Lint
covers the same sources and manifest, apart from build-type differences.

## Artifact: APK inspection (stable key, `.debug` suffix, SDK levels, non-debuggable release)

**What it proves:** the debug key is stable across commits, debug builds are `tv.stationbreak.app.debug`, the
SDK levels are right, and the release APK isn't debuggable.

**Why it matters:** ADR-0011's install-over-previous behaviour depends on the same certificate every time.

**Command (CI "Inspect APKs" step):** `apksigner verify --print-certs` on the `github` debug APK, `aapt2 dump badging`
on the `github` debug and release APKs (build-tools 36.0.0).

**Result summary:** the certificate SHA-256 is identical on runs from four different commits, and it matches the
keystore fingerprint recorded at generation (1.4):
`57:4B:E8:75:93:09:FF:2E:AE:16:57:9B:89:55:4D:84:E8:7B:2B:4E:96:D7:FF:82:D1:51:CC:59:19:BA:80:B1`.

| Run | Commit | Certificate SHA-256 |
| --- | --- | --- |
| 38168263025 | `69dd39b` | `574be8759309ff2eae16579b89554d84e87b2b4e96d7ff82d151cc5919ba80b1` |
| 38168546762 | `2886f91` | `574be8759309ff2eae16579b89554d84e87b2b4e96d7ff82d151cc5919ba80b1` |
| 38169217853 | `5112b4e` | `574be8759309ff2eae16579b89554d84e87b2b4e96d7ff82d151cc5919ba80b1` |
| 38170364485 | `68f0c75` | `574be8759309ff2eae16579b89554d84e87b2b4e96d7ff82d151cc5919ba80b1` |

Excerpt from run 38170364485:

```text
Signer #1 certificate DN: CN="StationBreak Debug (public, throwaway)", O=StationBreak
Signer #1 certificate SHA-256 digest: 574be8759309ff2eae16579b89554d84e87b2b4e96d7ff82d151cc5919ba80b1
package: name='tv.stationbreak.app.debug' versionCode='1' versionName='0.0.1' platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36' compileSdkVersionCodename='16'
minSdkVersion:'25'
targetSdkVersion:'34'
application-debuggable
package: name='tv.stationbreak.app' versionCode='1' versionName='0.0.1' platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36' compileSdkVersionCodename='16'
minSdkVersion:'25'
targetSdkVersion:'34'
release APK: no application-debuggable line
```

aapt2 prints `minSdkVersion:` where the older aapt printed `sdkVersion:`; the step's filter matches both.

## Artifact: PR debug APK and its naming

**What it proves:** same-repo PRs publish a sideloadable APK named by PR number and head commit, kept 7 days.

**Why it matters:** this is how the owner gets a test build without a dev machine (S0.1.3).

**Command:** `gh api repos/StationBreak/stationbreaktv/actions/runs/<id>/artifacts`

**Result summary:** each green PR run lists exactly one artifact. With `archive: false`, it's the APK itself,
not a zip.

```text
38169217853  stationbreak-github-debug-pr136-5112b4e.apk  expires 2026-10-18T19:46:09Z
38170364485  stationbreak-github-debug-pr136-68f0c75.apk  expires 2026-10-18T20:13:49Z
```

**`main` artifact (`stationbreak-github-debug-main-<shortsha>.apk`, default retention): PENDING** the first `main`
run after merge (1.12).

## Artifact: deliberate failing PR (detekt, Lint Security, unit test)

**What it proves:** detekt, the Lint Security gate and unit tests each block a PR.

**Why it matters:** three of the 13 blocking checks in spec Success Metric 2.

**Artifact:** [PR #137](https://github.com/StationBreak/stationbreaktv/pull/137) from
`throwaway/f0.1-1.10-detekt-lint-test` into `feat/f0.1-repo-and-ci`, closed unmerged, branch deleted. Failing
run: [38170691012](https://github.com/StationBreak/stationbreaktv/actions/runs/38170691012) (job 114562490112).

**Result summary:** with `--continue`, the one `build` job reports all three findings.

```text
e: app/src/main/kotlin/tv/stationbreak/app/MainActivity.kt:14:19 The function sumOfSeven(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int) has too many parameters. The current maximum allowed parameters are 5. [LongParameterList]
> Task :app:detektAmazonDebug FAILED
> Task :app:detektGithubDebug FAILED
app/src/main/kotlin/tv/stationbreak/app/MainActivity.kt:16: Error: Using getString to get device identifiers is not recommended [HardwareIds]
> Task :app:lintAmazonDebug FAILED
> Task :app:lintGithubDebug FAILED
MainActivityPackageTest > deliberatelyFails FAILED
2 tests completed, 1 failed
> Task :app:testAmazonDebugUnitTest FAILED
> Task :app:testGithubDebugUnitTest FAILED
BUILD FAILED in 4m 1s
```

**How the gates were corrected on the way.** The PR's first two runs passed gates they should have failed. Each
problem was fixed on the implementation branch, and the PR was rebased and re-run:

1. [Run 38168779524](https://github.com/StationBreak/stationbreaktv/actions/runs/38168779524): only the unit test
   failed.
   - **detekt:** in detekt 2.0.0-alpha.6, `LongParameterList` implements `RequiresAnalysisApi`. The plain
     `detekt` task, the one the plugin wires into `check`, skips those rules. This was reproduced locally:
     `detekt` reports 0 findings and `detektMain` reports `LongParameterList`. Fix (`5112b4e`): `check` also
     depends on `detektMain` and `detektTest`. **Owner decision (2026-10-11):** accepted.
   - **Lint:** the task's trigger, `android:allowBackup="true"` (`AllowBackup`), can't test the Security gate.
     `AllowBackup` isn't in Lint 32.4.1's built-in issue registry, which is the Lint version AGP 9.4.1 uses.
     The PR now reads `Settings.Secure.ANDROID_ID` instead. That trips `HardwareIds`, which is Security
     category with a default severity of warning.
2. [Run 38169443098](https://github.com/StationBreak/stationbreaktv/actions/runs/38169443098): detekt and the
   unit test failed, but `HardwareIds` stayed a warning. So the Lint DSL's `fatal += "Security"` doesn't raise a
   whole category. Fix (`bc63baf`): task 1.8's fallback, `app/lint.xml`, which lists all 63 Security-category
   issue IDs from `BuiltinIssueRegistry` (Lint 32.4.1) as `fatal`.
   **Owner decision (2026-10-11):** the `HardwareIds` trigger and the explicit ID list are both accepted. A CI
   check that the list covers every Security issue in the current Lint is tracked in
   [#138](https://github.com/StationBreak/stationbreaktv/issues/138).
3. The now-fatal Security gate then caught a real issue on the placeholder: `DataExtractionRules`
   (`allowBackup="false"` is ignored from Android 12). Fix (`5e16457`, `68f0c75`): `data_extraction_rules.xml`
   and `backup_rules.xml`, which exclude every domain, so no app data is backed up or transferred at any API
   level. **Owner decision (2026-10-11):** accepted; no app data is backed up until a later story decides
   otherwise. The access token in particular must never reach a cloud backup.

## Artifact: fork-PR no-APK gate (inspection)

**What it proves:** fork PRs build and test but get no APK, and the workflow never uses `pull_request_target`.

**Why it matters:** this needs a second GitHub account to exercise, so it's verified by inspection (spec Goals).

**Artifact path:** `.github/workflows/ci.yml`

```yaml
on:
  pull_request:
  push:
    branches: [main]
...
      - name: Upload PR debug APK
        if: github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name == github.repository
```

**Result summary:** the gate is on the upload step, not the job. `grep -rn pull_request_target .github/` matches
only the explanatory comment on line 3.

**Angua (Security)'s confirmation (sub-task 4.9, 2026-10-11): PASS.** The upload `if:` gates fork PRs at the
step; the only triggers are `pull_request` and `push` to `main` (no `pull_request_target` or `workflow_run`);
`permissions: contents: read` with no job widening it; `persist-credentials: false`; every `github.event.*` value
reaches `run:` through `env:`; every `uses:` is SHA-pinned; PR runs never write the Gradle cache. Her findings
outside 4.9 are recorded in the PR.

## Artifact: non-debuggable release gate (ADR-0011 condition 3)

**What it proves:** CI fails when either flavor's release APK is debuggable. Angua (Security) found that the
"Inspect APKs" step originally only printed the result. The owner approved the fix on 2026-10-11 (`b994cf7`),
together with her two low-severity findings: device-protected storage is now excluded from backup too, and the
concurrency group includes the head repo, so a fork branch with the same name can't cancel our runs.

**Deliberate failing PR:** [#139](https://github.com/StationBreak/stationbreaktv/pull/139) set `isDebuggable = true`
on `release`. [Run 38183635107](https://github.com/StationBreak/stationbreaktv/actions/runs/38183635107) failed at
"Inspect APKs":

```
##[error]release APK is debuggable (ADR-0011): app/build/outputs/apk/github/release/app-github-release-unsigned.apk
##[error]Process completed with exit code 1.
```

The PR was closed unmerged and its branch deleted. On the fix branch,
[run 38183623944](https://github.com/StationBreak/stationbreaktv/actions/runs/38183623944) is green:

```
release APK not debuggable: app/build/outputs/apk/github/release/app-github-release-unsigned.apk
release APK not debuggable: app/build/outputs/apk/amazon/release/app-amazon-release-unsigned.apk
```

ADR-0011 condition 2's release-certificate check lands with S4.1.1, when release builds are first signed.

## Record: `targetSdk`

**What it proves:** the spec's `targetSdk` assumption was confirmed against the official policy.

**Source:** [Google Play, "Target API level requirements for Google Play apps"](https://support.google.com/googleplay/android-developer/answer/11926878),
checked 2026-10-11.

**Result summary:** **`targetSdk = 34`.** The page says new apps and updates must target Android 16 (API 36) "except for
Wear OS, and Android Automotive OS apps, which must target Android 15 (API level 35) or higher, and **Android TV
and Android XR apps, which must target Android 14 (API level 34) or higher**." StationBreak is an Android TV
app, so its store requirement is API 34. The value and source are in a comment in `gradle/libs.versions.toml`.
`compileSdk` is 36 (AGP 9.4 supports up to 37).

**Owner decision (2026-10-11):** `targetSdk 34` confirmed. Raise it when Play's Android TV requirement rises, at
the latest when the `play` flavor ships at F4.3.

## Artifact: build time

**What it proves:** the build job finishes well under the 10-minute target.

**Command:** `gh run rerun 38170364485`, then the job and step timings from the jobs API.

**Result summary:** the re-run (attempt 2) took **4m19s**. The `Check and assemble` step took 4m03s, and every
other step took 2s or less. The first attempt took 4m12s. Both are **cold-cache** numbers: the Gradle cache is
written only from `main`, nothing has run on `main` yet, and `gh api .../actions/caches` reports 0 entries. So
4m19s is an upper bound. **Warm-cache re-run: PENDING** the first `main` run after merge.

```text
Set up job              20:09:36 -> 20:09:40
checkout / setup-java / setup-gradle   20:09:40 -> 20:09:44
Check and assemble      20:09:44 -> 20:13:47
Name / Inspect / Upload 20:13:47 -> 20:13:50
Complete job            20:13:51
```

## Artifact: `sha_pinning_required`

**What it proves:** the repository requires every action to be pinned to a full commit SHA.

**Command:** `gh api repos/StationBreak/stationbreaktv/actions/permissions` (read-only GET, 2026-10-11)

```json
{"enabled":true,"allowed_actions":"all","sha_pinning_required":false}
```

Before (GET above). The owner approved the change on 2026-10-11 and enabled it themselves in the web UI
(Settings → Actions → General → "Require actions to be pinned to a full-length commit SHA"). After:

```json
{"enabled":true,"allowed_actions":"all","sha_pinning_required":true}
```

**Result summary:** SHA pinning is enforced, with the other settings unchanged. CI still goes green under
enforcement: [run 38176295040, attempt 2](https://github.com/StationBreak/stationbreaktv/actions/runs/38176295040)
(re-run started 2026-10-11T21:32:50Z, after the setting was saved) succeeded.

## Device check (bridge session): PENDING

Two successive green PR runs from different commits, for `gh run download` and `adb install -r`:

| Order | Run ID | Head commit | Artifact |
| --- | --- | --- | --- |
| 1st | 38169217853 | `5112b4e` | `stationbreak-github-debug-pr136-5112b4e.apk` |
| 2nd | 38170364485 | `68f0c75` | `stationbreak-github-debug-pr136-68f0c75.apk` |

Launch: `adb shell am start -n tv.stationbreak.app.debug/tv.stationbreak.app.MainActivity`. Sanitized output
goes here.

## Implementation notes

- **AGP 9 built-in Kotlin:** AGP 9 compiles Kotlin itself and depends on KGP 2.2.10. Applying
  `org.jetbrains.kotlin.android` to the module is an error under AGP 9, so the module applies only
  `com.android.application`. The root build declares the Kotlin plugin at 2.4.20 with `apply false`, which
  raises the KGP that AGP uses.
- **Gradle wrapper:** generated with Gradle 9.8.0 on the local JDK 27; no temporary JDK was needed. The
  distribution matched its published SHA-256, and `gradle-wrapper.jar` matches the published wrapper-jar
  checksum (`238e777f…21abd5`).
- **Debug keystore DN:** keytool rejects an unescaped comma inside the CN, so the command used
  `CN=StationBreak Debug (public\, throwaway)`. The resulting DN is exactly as specified.
- **`MissingTvBanner`:** suppressed on `<application>` with a comment. The leanback banner belongs to the real TV
  UI (spec non-goal 2, S0.2.2).

## Reviewer Conclusion

The core pipeline works end to end: tests were shown red and then green, and the build, unit tests, detekt and
the Lint Security gate each block a deliberately bad PR. The debug APK has a stable certificate, the right
application ID and SDK levels, and a non-debuggable release. Same-repo PRs get a correctly named 7-day APK, and
the build job takes about 4 minutes with an empty cache. Still to come: the owner's approval for
`sha_pinning_required`, the device install-over check, the post-merge `main` artifact and warm-cache timing, and
Angua's fork-gate confirmation.
