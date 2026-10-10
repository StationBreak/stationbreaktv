# StationBreak — Gradle Module Layout (Proposal)

Status: **Proposed**. Needs owner approval before Phase 0 scaffolding.
Source of truth: [`SPEC.md`](SPEC.md).

## Principles

1. **Domain logic is pure Kotlin.** The channel engine, scheduler, rules format, and domain model use the
   **KMP plugin, with code in `commonMain`, a `jvm()` target, and a non-published `linuxX64()` canary target**
   ([ADR-0004](adr/0004-kmp-shared-core.md)). They have **no Android dependencies** and run under plain
   `./gradlew test` with no emulator.
2. **KMP now; tvOS targets are added in Phase 5.** The canary target makes the compiler reject `java.*` in
   `commonMain`, so portability is enforced rather than hoped for. Common code uses `kotlin.time`,
   `kotlinx-datetime` (timezones only), `kotlinx.serialization`, and a hand-written PRNG.
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
├── build-logic/convention/      Gradle convention plugins (android-lib, jvm-lib, compose, hilt, ...)
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
│   │                            SecretStore, Clock, ProEntitlement), use cases, and the built-in
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

`core:scheduler` does **not** depend on `core:engine` or `core:rules`. It needs only `core:model`.
The built-in `ScheduleSource` in `core:domain` composes engine → scheduler. The Phase 5 XMLTV
`ScheduleSource` (in its own `data:xmltv`) depends on neither (Granny ruling, see ADR when scaffolded).

Enforcement: an architecture test / convention-plugin check fails the build if
- a `[KMP]` or `[JVM]` module gains an Android dependency,
- a `java.*` import appears in `commonMain` (enforced by the compiler via the canary target; scaffold acceptance test),
- `feature:*` depends on `data:*` or on another `feature:*`,
- `core:scheduler` depends on `core:engine`/`core:rules`.

### Hilt in pure modules

`javax.inject` is JVM-only, so it is **kept out of** `core:model`, `core:rules`, `core:engine`, and
`core:scheduler` to preserve KMP-readiness. These are wired with `@Provides` in `app`. `core:domain` and
`data:jellyfin` may use `@Inject` constructors. Hilt `@Module`s live in `app` (or in thin `di` packages
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

## Toolchain baseline (to confirm at scaffold time)

- Kotlin 2.x, AGP current stable, JDK 17 toolchain, Gradle version catalog
- `minSdk 25`, `targetSdk` = current store requirement, core library desugaring **only if** a
  `java.time` dependency sneaks in (pure modules shouldn't need it)
- Compose BOM + `androidx.tv:tv-material`, Media3 (`exoplayer`, `exoplayer-hls`, `datasource-okhttp`)
- Room with KSP, Hilt with KSP, WorkManager, DataStore, kotlinx.serialization
- Quality: ktlint (or Spotless + ktlint), detekt (incl. the forbidden-import rule), Android Lint, Kover for coverage of the `[KMP]`/`[JVM]` modules (detekt per KMP source set; verify Kover covers `commonMain`)
- License hygiene: GPL-3.0 header check, plus a dependency-license report so no GPL-incompatible deps sneak in
