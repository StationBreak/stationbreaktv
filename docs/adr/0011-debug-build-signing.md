# ADR-0011: Debug build signing and test APKs

- **Status:** Accepted; amended 2026-10-18 (see Amendment: the debug key moves to CI secrets)
- **Date:** 2026-10-11
- **Decided by:** Phil Critchfield, with a security ruling from Angua (Security)
- **Resolves:** Q-8 from the Phase 0 decision packet
- **Informs:** F0.1 (S0.1.1, S0.1.3), F4.1 (S4.1.1)

## Context

Every APK that goes on a device comes from CI (ROADMAP, "CI first"). A CI runner generates a fresh random debug
keystore on each run, so each debug APK would be signed with a different key and couldn't install over the
previous one. The owner would have to uninstall every build and lose settings and sign-in each time.

The owner also needs to check changes on the reference device **before** they merge, because the 📺
acceptance criteria are checked during PR review.

## Decision

1. **Debug builds are signed with a committed, debug-only keystore** (`debug.keystore`). Debug builds use the
   application ID suffix `.debug` (`tv.stationbreak.app.debug`), so a debug build and a release build can never
   overwrite each other.
2. **The debug key never signs a release.** Release signing comes only from secrets in a protected
   environment. CI runs `apksigner verify --print-certs` on every release APK and fails if its certificate
   matches the debug certificate's SHA-256 fingerprint. *(Pending S4.1.1: release APKs are unsigned today, so the
   fingerprint check lands with release signing. It must reject both the CI debug key and the retired key.)*
3. **Release builds are never debuggable.** CI checks that the release manifest isn't debuggable. The `.debug`
   suffix and the debuggable flag are set only in the debug build type.
4. **The key is single-purpose:** generated fresh for this project, documented in the repo as public and
   throwaway, and used for nothing else. *(Superseded in part by the Amendment: the CI debug key is single-purpose
   but not public.)*
5. **Test APKs come only from code the owner controls.**
   - CI uploads a debug APK for `main` builds and for pull requests whose branch is in this repository.
   - Pull requests from forks still build and run tests, but get no APK. They run on the `pull_request`
     trigger only, never `pull_request_target`, so they have no access to secrets or write tokens. The upload
     step, not the whole job, is gated on `github.event.pull_request.head.repo.full_name == github.repository`.
   - APKs are named by source: `stationbreak-github-debug-pr<N>-<shortsha>.apk` for pull requests and
     `stationbreak-github-debug-main-<shortsha>.apk` for `main`. The owner checks that the SHA matches the pull
     request's head commit before installing.
   - Pull request APKs are kept for 7 days. `main` APKs are kept for 30 days, set explicitly in the workflow (owner, 2026-10-11).
   - Release signing secrets are never available to pull request runs.
6. *(Superseded by the Amendment: Renovate's pull requests no longer get test APKs.)*
   **Renovate's pull requests get test APKs too.** Installing one runs exactly the code a merge would ship, so
   testing a dependency update on the device before merging is safer than after. The owner installs a
   Renovate APK only once CI is green, and only after reading the diff, including any `verification-metadata.xml`
   changes. A Renovate pull request that touches `.github/` gets extra scrutiny first. Renovate has the app's
   default permissions only: no admin rights, and no bypass of branch protection.
7. **Device hygiene:** ADB debugging is turned off on the TVs when they aren't being used for testing, and
   debug builds sign in with a low-privilege Jellyfin user.

## Consequences

- Each CI debug build installs over the previous one, keeping settings and sign-in, and debug and release
  builds can be installed side by side.
- *(Superseded by the Amendment: the key is no longer public. Kept for the record.)* Once the repository is public,
  anyone can sign an APK as `tv.stationbreak.app.debug`. Installed over a debug
  build, it would inherit that build's data, including the Keystore key that protects the Jellyfin token
  (ADR-0009). It's accepted because it needs the owner to sideload an untrusted APK deliberately, and anyone
  with ADB access can already read a debuggable build's data. Item 5 limits what the owner installs, and item 7
  limits what a debug token can do.
- **Standing rule:** if anyone else gets push access to the repository (a collaborator, app or bot), item 5 is
  reviewed again, because it relies on only the owner being able to push branches.

## Amendment, 2026-10-18: the debug key moves to CI secrets

- **Decided by:** Phil Critchfield, on a security ruling from Angua (Security) in the pre-public review.

**Why.** The Consequences above accepted a public debug key on the condition in item 7: debug builds sign in with a
low-privilege Jellyfin user. On the owner's TVs, debug builds are signed in with an administrator account, the one
the owner streams with. A public key would let anyone build an APK that installs over that build and inherits an
administrator token, so Angua rated the risk HIGH before the repository goes public.

**What changes.**
1. `debug.keystore` is removed from the repository. A new debug key, the **CI debug key**, was generated off the
   repository and is held in the Actions secrets `DEBUG_KEYSTORE_B64` and `DEBUG_KEYSTORE_PASSWORD`. Item 1 now reads:
   debug builds from CI are signed with the CI debug key.
2. **No repository code runs where the key is.** Gradle signs the debug APK with the runner's own per-machine key,
   and `assemble` hands that APK to a separate job, `sign-debug`, as a one-day artifact. `sign-debug` runs on a fresh
   runner with no checkout and no Gradle: it downloads the APK, decodes the CI debug key into the runner's temporary
   directory, re-signs the APK with the SDK's `apksigner`, and deletes the key file when the step ends. The password
   reaches `keytool` and `apksigner` through the step's environment only. Only this job reads the secrets. A separate
   job is needed because a build step can shape every later step in its own job (through `$GITHUB_ENV`,
   `$GITHUB_PATH` or a background process), so no step in `assemble` could hold the key safely.
3. `sign-debug` checks that the debug APK has exactly one signer, the CI debug key, before it uploads it.
4. A push to `main` without the secrets fails. A pull request from a fork gets no secrets (`pull_request`, never
   `pull_request_target`), never reaches `sign-debug`, and uploads no APK, as before (item 5).
5. Local builds and the `emulator` job use Android's per-machine debug key. They can't update a CI install.
6. **Renovate's pull requests get no APK** (owner, 2026-10-18, on Angua's pre-public review). They still build and
   run every test. A compromised dependency in a Renovate bump would otherwise get an APK signed with the key the TVs
   trust, downloadable by anyone once the repository is public. Item 5 says test APKs come only from code the owner
   controls, and a bot's dependency bump isn't that. A dependency update reaches the TVs in the first `main` APK after
   it merges. `sign-debug` and the upload that feeds it skip pull requests opened by `renovate[bot]`.

**The retired key** stays in the history and stays allowlisted in `.gitleaks.toml` by its exact path. Once a TV runs
a build signed with the CI debug key, Android refuses an update signed with the retired key. The one-time cost is an
uninstall and a fresh set-up on each TV. Only CI APKs signed with the CI debug key are installed from then on.
**Before the repository goes public, both TVs are reinstalled from a CI build signed with the CI debug key**, and the
installed signature is checked (`apksigner verify --print-certs` on the pulled APK shows `0047…6522`).

**What stays.** Items 2, 3, 5 and 7 stand, and item 4 except that the key is no longer public. Item 7 (a
low-privilege Jellyfin user for debug builds) is still the right practice; this amendment removes the dependency on
it, not the advice. The CI debug key is single-purpose (item 4) and is never used for a release. If it leaks, it is replaced the same way: a new key, new secrets, and one uninstall per TV.
