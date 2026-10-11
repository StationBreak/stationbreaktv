# ADR-0011: Debug build signing and test APKs

- **Status:** Accepted
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
   matches the debug certificate's SHA-256 fingerprint.
3. **Release builds are never debuggable.** CI checks that the release manifest isn't debuggable. The `.debug`
   suffix and the debuggable flag are set only in the debug build type.
4. **The key is single-purpose:** generated fresh for this project, documented in the repo as public and
   throwaway, and used for nothing else.
5. **Test APKs come only from code the owner controls.**
   - CI uploads a debug APK for `main` builds and for pull requests whose branch is in this repository.
   - Pull requests from forks still build and run tests, but get no APK. They run on the `pull_request`
     trigger only, never `pull_request_target`, so they have no access to secrets or write tokens. The upload
     step, not the whole job, is gated on `github.event.pull_request.head.repo.full_name == github.repository`.
   - APKs are named by source: `stationbreak-github-debug-pr<N>-<shortsha>.apk` for pull requests and
     `stationbreak-github-debug-main-<shortsha>.apk` for `main`. The owner checks that the SHA matches the pull
     request's head commit before installing.
   - Pull request APKs are kept for 7 days. `main` APKs use the default retention.
   - Release signing secrets are never available to pull request runs.
6. **Renovate's pull requests get test APKs too.** Installing one runs exactly the code a merge would ship, so
   testing a dependency update on the device before merging is safer than after. The owner installs a
   Renovate APK only once CI is green, and only after reading the diff, including any `verification-metadata.xml`
   changes. A Renovate pull request that touches `.github/` gets extra scrutiny first. Renovate has the app's
   default permissions only: no admin rights, and no bypass of branch protection.
7. **Device hygiene:** ADB debugging is turned off on the TVs when they aren't being used for testing, and
   debug builds sign in with a low-privilege Jellyfin user.

## Consequences

- Each CI debug build installs over the previous one, keeping settings and sign-in, and debug and release
  builds can be installed side by side.
- Once the repository is public, anyone can sign an APK as `tv.stationbreak.app.debug`. Installed over a debug
  build, it would inherit that build's data, including the Keystore key that protects the Jellyfin token
  (ADR-0009). It's accepted because it needs the owner to sideload an untrusted APK deliberately, and anyone
  with ADB access can already read a debuggable build's data. Item 5 limits what the owner installs, and item 7
  limits what a debug token can do.
- **Standing rule:** if anyone else gets push access to the repository (a collaborator, app or bot), item 5 is
  reviewed again, because it relies on only the owner being able to push branches.
