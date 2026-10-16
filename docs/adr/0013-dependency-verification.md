# ADR-0013: Dependency verification is checksum-only

- **Status:** Accepted
- **Date:** 2026-10-13
- **Decided by:** the owner (2026-10-12), with vetoes from Angua (Security)
- **Revisit:** if Google starts signing its Maven artifacts, or if the baseline is ever found to hold a bad checksum
- **Informs:** F0.2 (S0.2.1)

## Context

Without verification, the build trusts whatever a repository serves on the day. A dependency swapped after release
(or a compromised mirror or connection) would build and ship. Gradle can check every downloaded file, plugins and
the `build-logic` included build included, against `gradle/verification-metadata.xml`. The root file governs the
whole composite build.

Gradle can check SHA-256 checksums, PGP signatures, or both. Google's Maven repository (AndroidX, AGP, Compose)
doesn't publish signatures, so signature checking would mean trusting every Google key by hand or exempting the
largest part of the dependency set.

## Decision

1. **SHA-256 checksums only.** `verify-metadata` is on and `verify-signatures` is off, each written exactly once. There's
   no lenient mode, and no `<trusted-key>`: with signatures on, a trusted key would replace the checksum.
2. **The only trust entry** is one regex for source and javadoc jars, which are never built into the app:
   `<trust file=".*-(sources|javadoc)[.]jar" regex="true"/>`. Nothing else is trusted by group, name or wildcard,
   and the file has no `<also-trust>` or `<ignored-keys>`.
3. **A guard script enforces that**, in CI: `.github/scripts/verification-guard.sh` (tests in
   `verification-guard.test.sh`). The rule list lives in the script's header, so it can't drift from the code. The
   baseline doesn't merge until the guard passes (Angua's veto c).
4. **No CI job may regenerate the file and push it** (Angua's veto a). A job that rebuilds its own reference
   from whatever it just downloaded turns verification into a copy of the attack. The guard fails any file under
   `.github/` that rewrites the baseline.
5. **Extra repositories only through `exclusiveContent { includeGroup(...) }`**, and `FAIL_ON_PROJECT_REPOS` stays
   (Angua's veto b). A repository that can serve any group can serve a substitute for any dependency.
6. **One source per artifact.** The file holds one checksum per artifact. When Gradle sees two different
   checksums for one artifact it writes an `<also-trust>`, which the guard forbids. The `build-logic` build
   therefore uses the same plugin repositories, in the same order, as the root build: the Spotless plugin marker
   differs between Maven Central and the Plugin Portal, so the two builds disagreed about it.

## Accepted risks

- **A poisoned release's checksum gets recorded.** The baseline is generated from what the repositories serve
  today. If a release was already malicious when first fetched, its hash goes in the file as if it were good.
  Checksums catch a change after the baseline, not a bad baseline.
- **Kotlin/Native downloads its own toolchain outside Gradle**, and Gradle's verification doesn't see it. Recorded
  by running the `linuxX64Test` build with an empty Kotlin/Native home (2.4.21). Four toolchain items come from
  `download.jetbrains.com/kotlin/native` over HTTPS and are **unchecked** by this ADR's mechanism:
  - `x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2` (sysroot);
  - `llvm-21-x86_64-linux-essentials-116`;
  - `lldb-4-linux`;
  - `libffi-3.2.1-2-linux-x86-64`.

  The compiler bundle itself, `kotlin-native-prebuilt-2.4.21-linux-x86_64.tar.gz`, is fetched by Gradle as a
  dependency and **is** verified (it has a checksum in the baseline). It is the toolchain items it then downloads
  that aren't.

  CI keeps `~/.konan` in a cache keyed on the Kotlin version, so a cache hit skips even the unchecked download, and
  skips verifying the bundle there. Only pushes to `main` write that cache, so a pull request can't plant content
  in it (the same rule as the Gradle cache). These items affect only the `linuxX64` test target of the shared core
  (a canary that `commonMain` has no `java.*`). They are not part of any APK.
- **Rebuilding the baseline runs new, unverified code on the owner's machine.** The rebuild resolves new plugin and
  tool versions for the first time, so a plugin that was malicious from its first release runs at that moment,
  before any checksum exists to catch it. The rebuild is therefore run in an environment with no credentials: no
  cloud or registry tokens, no SSH agent, and a fresh Gradle user home (CONTRIBUTING.md).
- **The guard is a tripwire, not a wall.** It scans `.github/` and the Gradle properties files for the patterns
  above, so it catches the mistakes and shortcuts that loosen verification. It can't stop a pull request that
  changes the guard itself, or one that turns verification off in a way it doesn't look for (an obfuscated
  command, a setting in a file outside its scope). The owner's review of every diff under `.github/` (ADR-0012, CI gates
  advisory until the visibility flip, decision 1) is the real control; the guard makes the common ways to get it
  wrong fail loudly.

## Mitigations

- Renovate waits 3 days after a release before proposing it (`minimumReleaseAge`), which gives the community time
  to spot a bad one.
- The owner reviews every change to `gradle/verification-metadata.xml` before merging, including in Renovate's
  pull requests (amended 2026-10-16, see below) (ADR-0011, debug build signing, item 6; ADR-0012, CI gates advisory until the visibility
  flip). The review compares each new checksum with the `.sha256` file the
  publishing repository lists (see CONTRIBUTING.md).
- OSV-Scanner reads the file, so known-vulnerable versions are reported. Findings that existed at the baseline
  are ignored one by one in `gradle/osv-scanner.toml`, each with a reason and an `ignoreUntil` review date; the file
  sits next to the verification file because the scanner reads a config only from the directory of the file it
  scans.

## Renovate rebuild workflow

A dependency update changes what the build downloads, so CI fails on the Renovate pull request with a verification
error until the file is rebuilt. That's intended: nothing unreviewed passes. When the owner asks, an agent
session on the owner's development machine runs the command in CONTRIBUTING.md from a fresh Gradle user home and
commits the result with a sign-off. The owner reads the diff and merges. CI never does this (decision 4).

## Consequences

- Every Renovate dependency update takes a manual step before it can merge.
- A dependency's checksum changing for the same version fails the build, naming the artifact.
- A change to `gradle/osv-scanner.toml` can silence a finding, as a change to `.github/` can disable a gate, so
  the owner reviews it before merge, like `.github/**` (ADR-0012, CI gates advisory until the visibility flip,
  decision 1).
- The file is large and machine-generated. Its diff is reviewed by checksum, not read for meaning.

## Amendment (2026-10-16): who reviews the metadata diff

Owner decision, made on PR #253.

- **Our own pull requests.** A change to `gradle/verification-metadata.xml` is no longer held for the owner. A
  security review (Angua) replaces it. The review compares every new checksum with the `.sha256` file the
  publishing repository lists, or records why it can't (for example, Maven Central publishes none for a file and a
  second download was compared instead). It also confirms that the change adds no `trusted-artifacts`, keys or
  wildcard trust. The pull request body lists the new entries.
- **Renovate's dependency pull requests.** These still go to the owner, who reads the rebuilt diff and merges.

