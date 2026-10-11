# 01 Questions Round 1 - Repo & CI

Please answer each question below (select one or more options, or add your own notes). Feel free to add additional context under any question.

Everything already decided (ADR-0010, ADR-0011, CI first, the placeholder `:app`, the license allowlist, Renovate,
the DCO check, the emulator job, the S0.1.4 scanners, the flavors, branch protection by convention) is **not**
re-asked here. These are the only gaps the research turned up.

## 1. detekt and Kotlin 2.4

The project must use Kotlin 2.4.20, because jellyfin-sdk-kotlin 1.9.2 requires it. detekt's latest **stable**
release (1.23.8, February 2025) is built on Kotlin 2.0 and is expected to fail when run alongside Kotlin 2.4.
The only release built for Kotlin 2.4 is **detekt 2.0.0-alpha.6** (August 2026), which also renames the plugin
(`dev.detekt`). S0.1.1 lists `detekt` as a required check, and MODULES.md uses a detekt rule as a backstop
against `java.*` imports in shared code.

- [x] (A) Use detekt **2.0.0-alpha.6**, pinned to that exact version, with Renovate proposing updates. Move to 2.0 stable when it ships.
- [ ] (B) Leave detekt out of F0.1 and add it when 2.0 stable ships. Until then the checks are ktlint and Android Lint; the `linuxX64` canary compiler (ADR-0004) still blocks `java.*` in shared code.
- [ ] (C) Try detekt 1.23.8 anyway and fall back to (A) or (B) if it fails.
- [ ] (D) Other (describe)

**Current best-practice context:** detekt's maintainers publish 2.0 alphas specifically for current Kotlin and
Gradle versions. 1.23.x is in maintenance and pinned to an older Kotlin compiler.

**Recommended answer(s):** (A)

**Why these are recommended:**

- (A) keeps the check you asked for and runs it from day one. detekt is a build-time linter; it never ships
  in the app, so being on an alpha can't break users. At worst it gives a false positive that blocks a PR, and
  that can be fixed or suppressed.
- (B) is safe, but it removes an automated check from CI. That goes against "trust automation", and the
  backstop gap stays open for an unknown time.
- (C) spends implementation time on a version that is very likely to fail.

## 2. Code of Conduct and contact address

S0.1.2 requires a `CODE_OF_CONDUCT.md`. A code of conduct must name a way to report problems privately, and
it's public once the repo flips. The same address could serve a `SECURITY.md` for reporting vulnerabilities
privately, which Angua's (Security) scanning plan implies but no story requires yet.

- [x] (A) Contributor Covenant (current version), with reports sent to a project address on your domain, such as `conduct@stationbreak.tv`. Add a short `SECURITY.md` pointing to `security@stationbreak.tv`. You'd set these up as forwarding aliases.
- [ ] (B) Contributor Covenant, with reports sent to your personal email address.
- [ ] (C) Contributor Covenant, with a placeholder contact to fill in before the visibility flip. No `SECURITY.md` yet.
- [ ] (D) Other (describe)

**Recommended answer(s):** (A)

**Why these are recommended:**

- (A) keeps your personal address out of a public repo, which matches the rule to keep personal details out of
  the docs, and the domain is yours for the life of the app (ADR-0010).
- (B) works, but it publishes your personal address permanently in git history.
- (C) defers the decision, but it risks a placeholder still being there on the day the repo goes public.

## 3. Style checker: ktlint plugin or Spotless

S0.1.1 names a `ktlintCheck` task, which comes from the ktlint Gradle plugin. Separately, S0.1.2 needs an SPDX
header check. **Spotless** runs ktlint *and* checks or inserts license headers in one plugin, with one command
to fix everything (`spotlessApply`). It's the more common choice for this today.

- [x] (A) Spotless, running both ktlint and the SPDX header check. The CI task becomes `spotlessCheck` instead of `ktlintCheck`.
- [ ] (B) The ktlint Gradle plugin (`ktlintCheck`) for style, plus a separate script for the SPDX header check.
- [ ] (C) Other (describe)

**Recommended answer(s):** (A)

**Why these are recommended:**

- (A) is one tool and one fix command instead of two, and agents fixing style or a missing header just run
  `spotlessApply`.
- (B) matches the backlog's wording exactly, but it means maintaining a custom header script.
- With (A), I'd update S0.1.1's text from `ktlintCheck` to `spotlessCheck` so the backlog matches.

---

**Answered 2026-10-11 by Phil Critchfield:** 1 (A), 2 (A), 3 (A).
