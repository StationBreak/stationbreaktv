# ADR-0004: Kotlin Multiplatform shared core (JVM + linuxX64 canary)

- **Status:** Accepted
- **Date:** 2026-10-04
- **Decided by:** Phil Critchfield. Approved with conditions by Granny (Architecture).

## Context

The scheduler, engine, rules format, and domain model must stay reusable by a future Apple TV client (Swift)
and a companion server (JVM). The options were: plain JVM + a detekt guard (A); full KMP with tvOS targets
now (B); KMP with a JVM target only (C). With a **single** target, Kotlin compiles `commonMain` as that
platform, so `java.*` would still resolve. C alone doesn't enforce portability.

## Decision

1. `core:model`, `core:rules`, `core:engine`, `core:scheduler`, and `core:testing` use the **Kotlin
   Multiplatform plugin**, with code in `commonMain`.
2. Targets: **`jvm()`** (consumed by the app and the server) plus a **non-published `linuxX64()` canary**
   that forces `commonMain` to compile as truly common code. It's Kotlin/Native, like the future tvOS
   targets, and needs no Mac. **tvOS targets are added in Phase 5.**
3. **Time:** stdlib `kotlin.time.Instant`/`Clock` everywhere. `kotlinx-datetime` only for
   `TimeZone`/`LocalDateTime`. There's one Instant type, and both are pinned in the version catalog.
   `kotlinx.serialization` in common.
4. **Tests:** `kotlin.test` in `commonTest`, plus `kotest-property` (`checkAll`) only, no Kotest spec
   styles. Golden-vector assertions live in `commonTest`, with vector data as Kotlin source (or behind a
   tiny expect/actual loader).
5. **`core:testing` splits:** fixtures and golden vectors go in KMP `core:testing`. Port fakes go in
   `core:domain` `testFixtures`.
6. **`core:domain` stays plain JVM** (it holds `javax.inject`). Revisit in Phase 5.
7. **Tooling:** the `stationbreak.kmp-lib` convention plugin wires detekt into `check`: `detektMainJvm` and
   `detektTestJvm` (type resolution over the jvm compilation), plus the source-set tasks
   (`detektCommonMainSourceSet`, `detektJvmMainSourceSet`, `detektLinuxX64MainSourceSet` and their `Test`
   counterparts). Kover is deferred: it isn't configured yet. When it is, verify it covers `commonMain`.

## Verification (scaffold acceptance)

- Adding `import java.io.File` to a `commonMain` file **fails the build**.
- `app` (Android) consumes `core:model` via a plain `implementation(project(...))`, uses its type in its main
  source set, and Gradle selects the jvm variant (`dependencyInsight`). No feature module exists on day one.
- The detekt forbidden-import rule stays until the first check passes in CI. After that it's reduced to
  `javax.inject` only, or removed. Done in F0.2: the canary failed the build, and the rule now forbids only
  `javax.inject`.

## Consequences

- CI is slower (Kotlin/Native compile for the canary). This is accepted.
- Rollback: switch the plugin back to `kotlin.jvm` and move `commonMain` to `main`.
