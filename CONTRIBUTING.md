# Contributing to StationBreak

Thanks for helping. This page covers how to open a pull request, what you agree to when you contribute, and the
checks CI runs on every pull request.

## Opening a pull request

1. Fork the repository and create a branch from `main`.
2. Make your change. Keep each pull request to one change, so it's easy to review.
3. Sign off every commit (see [Sign off your commits](#sign-off-your-commits-dco)).
4. Run `./gradlew spotlessApply` to fix formatting and license headers before you push.
5. Open a pull request against `main` and say what the change does and why.

Every check in [CI checks](#ci-checks) must pass before a pull request can merge. Pull requests are squash-merged,
so the pull request's title and description become the commit on `main`.

## Before you push

CI is metered, so iterate locally and let CI run once.

- Run `scripts/preflight.sh` before pushing. It runs CI's checks on your machine: the script tests, `actionlint`,
  `zizmor`, `gitleaks`, the sign-off check and the Gradle build. It needs JDK 17, the Android SDK, docker and `uv`;
  a missing tool fails its step with an install hint. Add `--emulator` to also run the instrumented tests on a local
  API 25 emulator (it tells you the `sdkmanager` command if the SDK lacks one).
- Open the pull request as a draft. CI doesn't run on drafts.
- When preflight passes, mark the pull request ready for review. That runs CI once.
- Batch review fixes into one push. Every push to a ready pull request runs CI again.
- CI on the final head must be green before merge.
- `osv-scanner` runs on a pull request only when it changes `gradle/`, a `*.gradle.kts` file, `gradle.properties` or
  `build-logic/`. It also runs weekly on `main`.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/): a type, an optional scope, and a short summary.
For example:

```text
ci: cache the Gradle wrapper
docs: fix a broken link in the README
```

Common types are `feat`, `fix`, `docs`, `test`, `build`, `ci` and `chore`.

## Sign off your commits (DCO)

StationBreak uses the [Developer Certificate of Origin](https://developercertificate.org/) (DCO) instead of a
contributor license agreement. By signing off a commit, you certify that you wrote it or otherwise have the right
to submit it under the project's license.

Sign off with `git commit -s`. Git adds a trailer with the name and email from your Git config:

```text
docs: fix a broken link in the README

Signed-off-by: Your Name <you@example.com>
```

To sign off commits you've already made on your branch, run `git fetch origin` and `git rebase --signoff origin/main`, then `git push --force-with-lease`.

The `checks` job fails a pull request (at its "Check sign-offs" step) if any of its commits has no `Signed-off-by:` trailer. The only exception is a
commit from the Renovate dependency bot, on a pull request Renovate opened, that GitHub itself created and signed
for the `renovate[bot]` account (a bot can't certify the DCO). Don't click "Update branch" on a Renovate pull
request: the merge commit it makes has no sign-off, so the sign-off check fails. Ask Renovate to rebase instead.

## License and the app-store permission

StationBreak is licensed under the GNU General Public License, version 3 or later, with an additional permission
for app stores ([LICENSE](LICENSE), [ADR-0003](docs/adr/0003-licensing-and-contributions.md)). Its SPDX
identifier is:

```text
GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
```

Some app stores (Apple's, for example) have terms that conflict with the GPL. The additional permission, under
section 7 of the GPL, lets anyone distribute StationBreak through such a store, as long as they also make the
source available outside the store under the same license, and add no restrictions of their own beyond what the
store requires. The full text is at the end of [LICENSE](LICENSE).

**When you contribute, you agree that your contribution is licensed under these same terms, including the
app-store permission.** You keep your copyright. Because the permission is part of the license from the start,
the project never needs to ask contributors for it later.

Every Kotlin source file and Gradle build script starts with this header, which `./gradlew spotlessApply` adds
for you:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
```

## CI checks

All checks run in GitHub Actions on every pull request that is ready for review (not on drafts). Here's what each one does and how to fix a failure.

| Check | What it checks | How to fix a failure |
| --- | --- | --- |
| `build` | Skipped when the PR changes only `docs/`, `LICENSE` or `*.md` files. `./gradlew check` and assembling every flavor (below) | See the rows below for the task that failed |
| ↳ `spotlessCheck` | Kotlin style (ktlint) and the SPDX header on every `.kt` and `.gradle.kts` file | Run `./gradlew spotlessApply` and commit the result. ktlint can't fix some rules automatically (wildcard imports, for example); fix those by hand |
| ↳ `detekt` | Static analysis of Kotlin code | Fix the reported issue. The report names the rule and the line |
| ↳ `lint` | Android Lint. Issues in the Security category are errors | Fix the reported issue |
| ↳ `test` | JVM unit tests | Fix the code or the test |
| ↳ `allTests` | Each Kotlin Multiplatform module's `commonTest`, on the JVM and on Linux (`linuxX64`), whose compiler rejects `java.*` in `commonMain` | Fix the code or the test. For an unresolved `java.*` reference in `commonMain`, use a Kotlin common API instead |
| ↳ KMP tests with no Android SDK | `:core:model:jvmTest` and `:core:scheduler:jvmTest` with no Android SDK in the environment ([ADR-0004](docs/adr/0004-kmp-shared-core.md)) | Keep Android dependencies out of the `[KMP]` modules ([MODULES.md](docs/MODULES.md)) |
| ↳ `checkArchitecture` | Each module's declared dependencies follow the allowed edges in [MODULES.md](docs/MODULES.md#dependency-graph-allowed-edges), and the Jellyfin SDK stays inside `data:jellyfin`, and OkHttp stays out of the other `[KMP]` and `[JVM]` modules. The error names the module, the dependency, the configuration and the rule broken. It also runs on its own, first, in the "Module dependency rules" step, because a broken rule can make the full build fail earlier with a less helpful Gradle error | Remove the dependency, or depend on what MODULES.md allows instead (for example a `core:domain` port rather than a `data:*` module). Changing the rules means changing MODULES.md and `ArchitectureRules.kt` in `build-logic` together, in a pull request that says why |
| ↳ `checkOverrideLibrary` | No `AndroidManifest.xml` uses `tools:overrideLibrary`, so a library that needs a newer Android version than API 25 can't slip in | Use a version of the library that supports API 25 (minSdk 25 or lower), or a different library |
| ↳ `licensee` | Every dependency of `app` and of `data:jellyfin` has a license on the allowlist of GPL-compatible licenses, and can be identified (the allowlist is `LicenseePlugin` in `build-logic`) | Use a dependency with a compatible license, or raise it in the pull request so it can be discussed |
| ↳ trust-anchor tests | The bundled Let's Encrypt roots match their pinned SHA-256, are self-signed CAs and are valid for another year; the network security config trusts only the system store plus those roots, permits cleartext in exactly one place and has no debug overrides | See [Bundled Let's Encrypt roots](#bundled-lets-encrypt-roots). Never add a user-CA source or a second cleartext permission to make a test pass |
| `emulator` | Skipped unless the PR changes `app/`, `core/`, `data/`, `feature/`, `build-logic/`, `gradle/`, a `*.gradle.kts` file, `gradle.properties` or `ci.yml`. Instrumented tests on an Android 7.1 (API 25) Android TV emulator, the oldest Android version StationBreak supports | Don't call APIs newer than API 25 without a version check |
| ↳ dependency verification | Every file Gradle downloads matches its SHA-256 in `gradle/verification-metadata.xml` ([ADR-0013](docs/adr/0013-dependency-verification.md)). It runs inside `build` and `emulator`. A failure names the artifact | New or updated dependency: rebuild the file ([Dependency verification](#dependency-verification)). A checksum that changed for a version you didn't touch is a real alarm: don't regenerate it, report it |
| `checks`: dependency verification guard | The verification file or its settings were loosened, or a file under `.github/` turns verification off or rewrites it | Run `bash .github/scripts/verification-guard.sh .` to see which rule. Remove the setting it names. Don't work around it |
| `checks`: device-proof guard | Any text file in the tree (workflow, script, Gradle or build-logic file, Taskfile) names the `serverAddress` argument of the on-device probe, which would hand CI the owner's server address | Run `bash .github/scripts/device-proof-guard.sh .` to see the file. Remove the argument: the owner supplies it by hand, never CI |
| `checks`: sign-offs (DCO) | Every commit is signed off | See [Sign off your commits](#sign-off-your-commits-dco) |

To run the Gradle checks locally (with an Android SDK installed):

- The build needs JDK 17. Gradle doesn't download one for you, so with only another JDK installed the build fails
  with "Cannot find a Java installation on your machine matching this tasks requirements: {languageVersion=17 …}".
- The first build also downloads the Kotlin/Native toolchain (for the `linuxX64` target), so it takes longer.

```bash
./gradlew check
```

## Bundled Let's Encrypt roots

Fire OS 6 (API 25) trusts ISRG Root X1 but not ISRG Root X2 or Root YE, so the app bundles those two as extra trust
anchors in `app/src/main/res/raw/`. The source, fingerprints and the reasons Root YR is left out are in
[app/trust-anchors/README.md](app/trust-anchors/README.md).

`TrustAnchorsTest` fails the build when a bundled root has less than a year of validity left. To refresh a root (or
add one):

1. Download it from <https://letsencrypt.org/certificates/> and compare it with the README's source and checks.
2. Put the PEM in `app/src/main/res/raw/` (lower-case name, underscores), and add its `<certificates src="@raw/...">`
   line to `app/src/main/res/xml/network_security_config.xml`.
3. Update the fingerprint constants in `TrustAnchorsTest` and the table in the README together, and have the owner
   compare the new fingerprints with an independent source in the pull request.

`NetworkSecurityConfigChainTest` exercises the X2 anchor with a frozen Let's Encrypt intermediate that Root X2 signed
(E8, valid until 2027-03-12); the cross-signed Root YE test says nothing about X2, because a certificate that shares
its subject and key with a bundled anchor is that anchor. After that date the test ends early and no chain test
exercises the X2 anchor any more: only the fingerprint pin and the key and signature checks in `TrustAnchorsTest`
remain. Before it expires, refresh the frozen chain from a certificate X2 still signs, or decide to drop the X2 anchor.

A bundled root also comes out, not only gets refreshed, when it is distrusted: if a root program (Mozilla, Chrome,
Apple, Microsoft) or the CA announces a distrusted or compromised root, a mis-issuance involving it, or a key
compromise, remove its PEM, its config line and its pins in the same pull request, and say why in the description. An
anchor the platform would no longer trust must not stay trusted here just because it is bundled.

Don't add `src="user"` (a user-installed CA), a second `cleartextTrafficPermitted`, debug overrides outside a debug
source set, or a custom `TrustManager`: `NetworkSecurityConfigTest` and Lint's Security category reject them.

## Dependency verification

Gradle checks every file it downloads (dependencies, plugins and the `build-logic` build) against a SHA-256 in
`gradle/verification-metadata.xml`. After adding or updating a dependency or plugin, rebuild the file. Renovate's
PRs fail `build` with a dependency verification error until the baseline is rebuilt. Push the rebuilt file as a
signed-off commit to the Renovate branch. Renovate then treats the branch as edited and stops rebasing it. The owner
asks an agent session on their development machine to do the rebuild.

Rebuild from a fresh Gradle user home, so nothing already cached is skipped, with a JDK 17 and the Android SDK. The
rebuild runs plugins and tools at versions that have no checksum yet, so run it with no credentials in the
environment (no cloud or registry tokens, no SSH agent):

```bash
GRADLE_USER_HOME="$(mktemp -d)" ./gradlew --write-verification-metadata sha256 \
  help buildEnvironment check lint detekt \
  assembleAmazonDebug assembleAmazonRelease assembleGithubDebug assembleGithubRelease \
  assembleGithubDebugAndroidTest linuxX64Test
```

Keep the `<configuration>` block as it is. Then run `bash .github/scripts/verification-guard.sh .`, which must pass.

Reviewing the diff is the point: a checksum in the file is only as good as the one it was copied from. For each
added or changed entry, compare the `sha256` with the `.sha256` file next to the artifact in the repository that
published it. Google's repository lists one for every file
(`https://dl.google.com/dl/android/maven2/<group path>/<name>/<version>/<file>.sha256`); Maven Central lists
one for many, and for the rest only a `.sha1`, which can confirm a mismatch but is weak evidence of a match.
A new entry for a dependency nobody added, or any change to an unchanged version, is a reason to stop and ask.

## Debug signing key

`debug.keystore` at the root of the repository is **public, throwaway and for debug builds only**
([ADR-0011](docs/adr/0011-debug-build-signing.md)). It's committed so every CI debug build is signed with the same
key and installs over the previous one. It never signs a release build, and it must not be used for anything
else. Its password is `android`.

Its certificate's SHA-256 fingerprint is:

```text
57:4B:E8:75:93:09:FF:2E:AE:16:57:9B:89:55:4D:84:E8:7B:2B:4E:96:D7:FF:82:D1:51:CC:59:19:BA:80:B1
```

Anyone can sign an APK with this key, so only install debug APKs built by this repository's CI.

## Code of conduct and security

Everyone who takes part is expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md). To report a security
vulnerability, follow [SECURITY.md](SECURITY.md). Please don't open a public issue for it.
