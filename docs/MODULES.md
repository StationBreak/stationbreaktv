# StationBreak — Gradle Module Layout

Status: **Accepted** (2026-10-11, Phil Critchfield).
Source of truth: [`SPEC.md`](SPEC.md).

## Principles

1. **Domain logic is pure Kotlin.** The channel engine, scheduler, rules format, and domain model use the
   **KMP plugin, with code in `commonMain`, a `jvm()` target, and a non-published `linuxX64()` canary target**
   ([ADR-0004](adr/0004-kmp-shared-core.md)). They have **no Android dependencies**, and their tests run on
   the JVM with no emulator. KMP modules have no plain `test` task: their tests are `jvmTest` and `linuxX64Test`, both
   run by `allTests`, which `./gradlew check` runs (so `commonTest` runs on both targets). Scaffolding verifies that these tests run with no Android SDK on the path, even
   though Android modules share the build. If they can't, the pure modules move to a Gradle included build.
2. **KMP now; tvOS targets are added in Phase 5.** The canary target makes the compiler reject `java.*` in
   `commonMain`, so portability is enforced rather than hoped for. Common code uses `kotlin.time`,
   `kotlinx-datetime` (`TimeZone`/`LocalDateTime` only), `kotlinx.serialization`, and a hand-written PRNG. Time comes from the
   standard library's `kotlin.time.Clock`, injected where needed, not from a custom `Clock` port
   ([ADR-0004](adr/0004-kmp-shared-core.md)). `app` provides `Clock.System` with `@Provides`. If the pinned Kotlin
   version still marks `kotlin.time` APIs experimental, the `kmp-lib` convention plugin opts in to
   `ExperimentalTime`.
3. **Ports and adapters.** `core:*` defines interfaces (`MediaServer`, `LibraryRepository`,
   `ScheduleSource`). `data:*` implements them. Feature modules depend on interfaces, never on
   Jellyfin types. This is what makes "Emby and Plex behind the same interface" and the
   "XMLTV schedule source" cheap later.
4. **Dependencies point inward.** `app` → `feature:*` → `core:*`. `data:*` → `core:*`. Feature
   modules never depend on each other or on `data:*` directly. The app module wires bindings through Hilt.
5. **Convention plugins over copy-paste.** `build-logic/` holds the shared Gradle config. Versions live in
   `gradle/libs.versions.toml`.

## Module tree

```
stationbreaktv/
├── app/                         Android application. Hilt root, navigation, flavors.
├── build-logic/convention/      Gradle convention plugins (android-lib, jvm-lib, jvm-inject, kmp-lib, compose, hilt, ...)
│
├── core/
│   ├── model/          [KMP]    Domain entities: Server, MediaItem, Channel, ScheduleBlock, ids, value types,
│   │                            and scheduler inputs (OrderingMode, Seed, BlockLength, FillerSpec)
│   ├── rules/          [KMP]    Portable channel-rules JSON format: schema, versioning, migrations, (de)serialization
│   ├── engine/         [KMP]    Channel engine: evaluate source rules against a library snapshot, then produce
│   │                            a canonical base order; default-channel generation (per library / genre / collection)
│   ├── scheduler/      [KMP]    Deterministic scheduler: PRNG, ordering modes, epochs, filler packing,
│   │                            "what's on at time T, at what offset". Input is an ordered item list, not rules.
│   ├── domain/         [JVM]    Ports (MediaServer, LibraryRepository, ChannelRepository, ScheduleSource,
│   │                            SecretStore, ProEntitlement), use cases, and the built-in
│   │                            ScheduleSource (engine + scheduler)
│   ├── testing/        [KMP]    Fixture libraries (incl. a synthetic 10k-item library), golden vectors.
│   │                            (Port fakes live in core:domain testFixtures.)
│   ├── ui/             [Android] Compose for TV theme + design tokens (incl. retro), shared composables, D-pad helpers
│   └── playback/       [Android] Media3 wrapper: tune-at-offset, direct-play vs transcode decision,
│                                codec capability probe, player reuse/pre-warm
│
├── data/
│   ├── jellyfin/       [JVM]    Jellyfin adapter (jellyfin-sdk-kotlin sealed inside, per ADR-0005):
│   │                            auth, URL normalisation, library paging, stream URLs, playback reporting.
│   │                            Implements MediaServer.
│   ├── library/        [Android] Room DB: library cache (with tombstones) + cached ScheduleBlocks. Sync worker.
│   │                            Implements LibraryRepository.
│   └── settings/       [Android] DataStore: servers, channels (as rules JSON), prefs, last channel; Keystore-backed
│                                SecretStore implementation
│
└── feature/
    ├── onboarding/     [Android] Server setup, connection test, login, library selection
    ├── live/           [Android] Full-screen player, up/down flipping, channel banner, info overlay, last-channel recall
    ├── guide/          [Android] Grid guide (>=6h), now/next
    ├── channels/       [Android] Channel list + custom channel builder
    └── settings/       [Android] Settings, diagnostics, about/licenses

Deferred: `benchmark/` (Macrobenchmark + Baseline Profiles) is added in F2.5. `core:common` is not created
until something genuinely needs sharing.
```

**Day-one set (decided 2026-10-11):** F0.2 creates only `app`, `build-logic`, `core:model`, `core:rules`,
`core:engine`, `core:scheduler`, `core:testing`, `core:domain`, `data:jellyfin`, and `core:ui` (theme only).
Every other module is created by the first story that needs it, for example `data:settings` in F0.4 and
`data:library` in F0.6. The architecture test enforces the rules for each module as it appears.

`[KMP]` means Kotlin Multiplatform (`commonMain`; `jvm()` + `linuxX64()` canary). `[JVM]` means a pure Kotlin/JVM module with no Android plugin. `[Android]` means an Android library module.

### Why `data:jellyfin` is pure JVM

The spec leaves "Ktor or Retrofit + OkHttp" open. **Decision (ADR-0005, ADR-0006):** the official
`jellyfin-sdk-kotlin` on its `jellyfin-api-okhttp` client, sealed inside `data:jellyfin` so no SDK types
leak past the `MediaServer` port. Spike SP-4 verified the injected OkHttp client (pinning), subpath base
URLs, and distinguishable errors against Jellyfin 12.x.

OkHttp and the SDK are plain JVM libraries, which keeps the adapter free of Android, so:
- the companion server (Phase 5) can reuse the Jellyfin adapter verbatim, and
- adapter tests run on the JVM against OkHttp `MockWebServer` fixtures or real containers (S0.3.6).

The Android side still gets OkHttp's TLS stack. That's where the self-signed certificate opt-in is configured.
`app` provides the configured, pinned `OkHttpClient` through Hilt.

### Why the engine and the scheduler are separate modules

- `core:engine` answers *"which items belong to this channel, and in what base order?"* The input is rules plus a
  library snapshot.
- `core:scheduler` answers *"what airs at time T, and at what offset?"* The input is the ordered item
  list, seed, epoch, and filler.

The Phase 5 XMLTV adapter replaces the scheduler as a `ScheduleSource` but keeps no engine. Keeping them
separate stops the XMLTV path from dragging in rule evaluation, and it keeps each module's golden
tests focused.

### Dependency graph (allowed edges)

```
app ──► feature:* ──► core:domain, core:ui, core:playback
 │
 ├──► data:* ──► core:domain
 │
 core:domain ──► core:engine ──► core:rules ──► core:model
      │                                          ▲
      └────────► core:scheduler ─────────────────┘
```

Also allowed (not drawn above):
- `app` → any module. It's the composition root: it wires `core:rules`, `core:engine` and `core:scheduler`
  with `@Provides` (they hold no `javax.inject`; see "Hilt in pure modules"), and implements the flavor-specific
  `ProEntitlement` from `core:domain`.
- `feature:*` → `core:model`, because screens show domain entities.
- `data:*` → `core:model`. `data:settings` → `core:rules` too, because it stores channels as rules JSON.
- `core:domain` and `core:engine` → `core:model` directly.
- `core:testing` → `core:model` and `core:rules`, to build fixtures and golden vectors.
- Any module → `core:testing`, **test source sets only** (`commonTest`, `jvmTest`, `test`, `androidTest`).
- Any module's tests → `core:domain`'s `testFixtures`, for the port fakes.

`core:scheduler` does **not** depend on `core:engine` or `core:rules`. It needs only `core:model`.
The built-in `ScheduleSource` in `core:domain` composes engine → scheduler. The Phase 5 XMLTV
`ScheduleSource` (in its own `data:xmltv`) depends on neither (Granny ruling, see ADR when scaffolded).

Enforcement: an architecture test / convention-plugin check fails the build if
- a `[KMP]` or `[JVM]` module gains an Android dependency,
- a `java.*` import appears in `commonMain` (enforced by the compiler via the canary target; scaffold acceptance test),
- `feature:*` depends on `data:*` or on another `feature:*`,
- `core:scheduler` depends on `core:engine`/`core:rules`,
- `core:testing` is used outside a test source set,
- any manifest uses `tools:overrideLibrary`.

### Hilt in pure modules

`javax.inject` is JVM-only, so it is **kept out of** `core:model`, `core:rules`, `core:engine`, and
`core:scheduler` to preserve KMP-readiness. These are wired with `@Provides` in `app`. `core:domain` and
`data:jellyfin` may use `@Inject` constructors, through a `jvm-inject` convention plugin that adds only
`javax.inject`. Hilt `@Module`s live in `app` (or in thin `di` packages
inside the Android `data:*` modules).

**Portability guard:** the `linuxX64()` canary makes the compiler reject `java.*` in `commonMain`. A detekt
forbidden-import rule stays as a backstop until the "`import java.io.File` fails the build" acceptance test
passes in CI. After that it's reduced to `javax.inject` only, or removed (ADR-0004).

## Build flavors

| Flavor | Store | Billing | Notes |
| --- | --- | --- | --- |
| `amazon` | Amazon Appstore | Amazon IAP (Appstore SDK) | Primary target. No Google Play Services. |
| `play` | Google Play | Play Billing | Google TV / Shield / Sony / TCL etc. |
| `github` | GitHub Releases (sideload) | None (see Q-BIZ-1) | Fully FOSS dependency set. Potential F-Droid candidate. |

Billing sits behind the `ProEntitlement` port in `core:domain`, with a flavor-specific implementation in
`app/src/<flavor>/`. The code stays public for every flavor, as the spec requires.

**Phasing (decided 2026-10-10):** F0.2 creates only the `amazon` and `github` flavors. `play` is added at
F4.3, along with Play Billing, so CI doesn't build an empty flavor until then. How Play Billing fits the "no
Google Play Services" rule (R-PLAT-3) is settled at that point.

## Toolchain baseline

Exact library versions are picked at scaffold time. Everything else in this section is settled.

- Kotlin 2.x, AGP current stable, JDK 17 toolchain, Gradle version catalog
- `minSdk 25`, `targetSdk` = current store requirement
- **Core library desugaring on from day one.** `kotlinx-datetime` on the JVM uses `java.time`, which API 25
  lacks, so the app needs desugaring even though the `[KMP]` modules never import `java.*` themselves. SP-1 ran
  with it on the Insignia ([ADR-0007](adr/0007-tune-latency-spike.md)). The `android-lib` convention plugin turns it
  on too, so library modules' instrumented tests work on the API 25 emulator.
- **minSdk guard:** `tools:overrideLibrary` is banned in every manifest (a build check fails on it). Because the
  override is banned, the manifest merger fails the build when any Android library (AAR) needs a minSdk above
  25, so no custom scanner is needed (R-PLAT-1). Plain JARs carry no minSdk, so the merger can't see them; the
  API 25 emulator job (S0.1.1) is the backstop for those.
- Compose BOM + `androidx.tv:tv-material`, Media3 (`exoplayer`, `exoplayer-hls`, `datasource-okhttp`)
- Room with KSP, Hilt with KSP, WorkManager, DataStore, kotlinx.serialization
- Quality: ktlint (or Spotless + ktlint), detekt (incl. the forbidden-import rule), Android Lint, Kover for coverage of the `[KMP]`/`[JVM]` modules (detekt per KMP source set; verify Kover covers `commonMain`)
- License hygiene: GPL-3.0 header check, plus a dependency-license report so no GPL-incompatible deps sneak in
