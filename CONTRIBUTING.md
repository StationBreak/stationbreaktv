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

The `dco` check fails a pull request if any of its commits has no `Signed-off-by:` trailer. The only exception is a
commit from the Renovate dependency bot, on a pull request Renovate opened, that GitHub itself created and signed
for the `renovate[bot]` account (a bot can't certify the DCO). Don't click "Update branch" on a Renovate pull
request: the merge commit it makes has no sign-off, so `dco` fails. Ask Renovate to rebase instead.

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

All checks run in GitHub Actions on every pull request. Here's what each one does and how to fix a failure.

| Check | What it checks | How to fix a failure |
| --- | --- | --- |
| `build` | `./gradlew check` and assembling every flavor (below) | See the rows below for the task that failed |
| ↳ `spotlessCheck` | Kotlin style (ktlint) and the SPDX header on every `.kt` and `.gradle.kts` file | Run `./gradlew spotlessApply` and commit the result. ktlint can't fix some rules automatically (wildcard imports, for example); fix those by hand |
| ↳ `detekt` | Static analysis of Kotlin code | Fix the reported issue. The report names the rule and the line |
| ↳ `lint` | Android Lint. Issues in the Security category are errors | Fix the reported issue |
| ↳ `test` | JVM unit tests | Fix the code or the test |
| ↳ `allTests` | Each Kotlin Multiplatform module's `commonTest`, on the JVM and on Linux (`linuxX64`), whose compiler rejects `java.*` in `commonMain` | Fix the code or the test. For an unresolved `java.*` reference in `commonMain`, use a Kotlin common API instead |
| ↳ KMP tests with no Android SDK | `:core:model:jvmTest` and `:core:scheduler:jvmTest` with no Android SDK in the environment ([ADR-0004](docs/adr/0004-kmp-shared-core.md)) | Keep Android dependencies out of the `[KMP]` modules ([MODULES.md](docs/MODULES.md)) |
| ↳ `checkArchitecture` | Each module's declared dependencies follow the allowed edges in [MODULES.md](docs/MODULES.md#dependency-graph-allowed-edges). The error names the module, the dependency, the configuration and the rule broken. It also runs on its own, first, in the "Module dependency rules" step, because a broken rule can make the full build fail earlier with a less helpful Gradle error | Remove the dependency, or depend on what MODULES.md allows instead (for example a `core:domain` port rather than a `data:*` module). Changing the rules means changing MODULES.md and `ArchitectureRules.kt` in `build-logic` together, in a pull request that says why |
| ↳ `checkOverrideLibrary` | No `AndroidManifest.xml` uses `tools:overrideLibrary`, so a library that needs a newer Android version than API 25 can't slip in | Use a version of the library that supports API 25 (minSdk 25 or lower), or a different library |
| ↳ `licensee` | Every dependency's license is on the allowlist of GPL-compatible licenses, and can be identified | Use a dependency with a compatible license, or raise it in the pull request so it can be discussed |
| `emulator` | Instrumented tests on an Android 7.1 (API 25) Android TV emulator, the oldest Android version StationBreak supports | Don't call APIs newer than API 25 without a version check |
| `dco` | Every commit is signed off | See [Sign off your commits](#sign-off-your-commits-dco) |

To run the Gradle checks locally (with an Android SDK installed):

- The build needs JDK 17. Gradle doesn't download one for you, so with only another JDK installed the build fails
  with "Cannot find a Java installation on your machine matching this tasks requirements: {languageVersion=17 …}".
- The first build also downloads the Kotlin/Native toolchain (for the `linuxX64` target), so it takes longer.

```bash
./gradlew check
```

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
