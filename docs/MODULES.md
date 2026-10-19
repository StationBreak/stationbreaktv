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
   `kotlinx-datetime` (`TimeZone`/`LocalDateTime` only) and `kotlinx.serialization`; the hash functions (`splitmix64`, `fnv1a64`) are hand-written. There is no PRNG: every order is a hash key. Time comes from the
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
├── build-logic/convention/      Gradle convention plugins (quality, android-app, android-lib, compose, kmp-lib, jvm-lib;
│                                licensee, jvm-inject, contract-test, hilt)
│
├── core/
│   ├── model/          [KMP]    Domain entities: Server, MediaItem, Channel, ScheduleBlock, ids, value types,
│   │                            and scheduler inputs (OrderingMode, Seed, BlockLength, FillerSpec), `CanonicalId`,
│   │                            and the hash primitives `splitmix64` and `fnv1a64` (`ordering` package)
│   ├── rules/          [KMP]    Portable channel-rules JSON format: schema, versioning, migrations, (de)serialization
│   ├── engine/         [KMP]    Channel engine: evaluate source rules against a library snapshot (`ChannelRules` +
│   │                            `RuleSnapshot` → the channel's pool); the scheduler orders it
│   ├── scheduler/      [KMP]    Deterministic scheduler: ordering modes, epochs, filler packing,
│   │                            "what's on at time T, at what offset". Input is the scheduler settings and an item list (any order), not rules.
│   ├── domain/         [JVM]    Ports (MediaServer, LibraryRepository, ChannelRepository, ScheduleSource,
│   │                            SecretStore, ProEntitlement), use cases, and the built-in
│   │                            ScheduleSource (engine + scheduler)
│   ├── testing/        [KMP]    Fixture libraries (incl. a synthetic 10k-item library), golden vectors for the
│   │                            scheduler's lineups. (The vectors for the rules format, the primitives and the lineups are
│   │                            canonical in `docs/rules-format/vectors/`; see "Golden vectors" below. `core:testing` keeps
│   │                            `SyntheticLibrary`.)
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

`feature:onboarding` exists since F0.5 (task 1.0). It holds the server screen so far, then sign-in, trust and the library picker.
It depends on `core:domain`, `core:model` and `core:ui` only, and applies the `hilt` convention plugin for `@HiltViewModel` (see
"Hilt in pure modules"). `app` hosts its navigation graph and wires the adapters it consumes.

`data:settings` exists since F0.4 and holds `KeystoreSecretStore` (the `SecretStore` implementation) and, since F0.5
task 3.0, `FileServerSettings` (the `ServerSettings` implementation: the configured address and its certificate pin as one
atomically replaced file in `noBackupFilesDir`). `app` binds both in `MediaServerModule`. The DataStore settings arrive with
the stories that need them.

`data:library` exists since F0.6 task 2.0. It holds the saved copy of the user's chosen libraries in Room (`RoomLibraryRepository`,
the `LibraryRepository` implementation) and, from tasks 3.0 and 4.0, the sync engine, the worker and the trigger policy. It
depends on `core:domain` and `core:model` only. Room and WorkManager belong to this module: `checkArchitecture` fails a `core:*`
module that declares `androidx.room*` or `androidx.work*`. `app` binds the repository in `di/LibraryModule`, and passes it to
`SessionRepository` as the `AccountCache`, so sign-in and sign-out keep the saved copy to one account.

- **Room 2.8.5, KSP only, platform SQLite.** The database is a cache: a schema change drops and rebuilds it, and the schema is
  exported to `data/library/schemas/` so a change shows in review. The SQL stays inside what SQLite 3.9 (API 25) has: no UPSERT,
  no window functions, no row values, at most 999 variables per statement.
- **Room 3 was checked and not adopted** (spec 06 task 2.2, 2026-10-15). `androidx.room3:room3-runtime-android:3.0.3` is stable
  and its merged manifest says `minSdkVersion 23`, so it would not break R-PLAT-1. It stays on Room 2.8.5 because the spec and
  the research notes design for 2.x, Room 3 drops the `SupportSQLite` API for a driver-only one, and the cache is rebuildable, so
  moving later is mechanical. Revisit when Room 2 stops receiving fixes. The AARs the cache pulls in are all minSdk 23 or 24
  (Room 2.8.5: 23, androidx.sqlite 2.7.1: 23, WorkManager 2.12.0, added by task 3.0: 24).
- **The first sync (task 3.0).** `FirstSyncEngine` copies each ticked library one page at a time, writing the page and its
  checkpoint in one transaction, so a killed run resumes from the last saved page. `FirstSyncWorker` (a `CoroutineWorker`, one
  unique piece of work, network-connected, exponential backoff, no input data) runs `FirstSyncRunner`, which reads the server
  record, the choice and the session from the app's own stores. WorkManager builds the worker by class name, so the worker
  asks the `Application` for its runner (`FirstSyncRunnerProvider`, implemented by `StationBreakApp` through a Hilt entry
  point). The `FirstSync` port in `core:domain` is how the progress screen and launch routing reach all of it without seeing
  WorkManager or Room. Every sync write checks inside its own transaction that the account is still the signed-in one
  (`active_account`, schema 2), and sign-in and sign-out cancel the unique work first.
- **Unit tests run Room on the JVM** over `androidx.sqlite:sqlite-bundled-jvm`, a test-only dependency that carries a native
  SQLite for the build machine. It is never in an APK. The instrumented test on the API 25 emulator runs the same schema on the
  platform SQLite.

Per-server certificate trust (F0.5 task 3.0, S0.5.2) is in `data:jellyfin`'s `trust` package: `PinnedHttpClients` builds the
one base client the adapter derives every client from, and rebuilds its `SSLContext`, client and connection pool whenever a
pin changes. Its trust manager asks the platform first (`SystemTrust`, supplied by `app` as the Network Security Config's trust
through `X509TrustManagerExtensions`) and accepts a refused chain only on an exact scheme, host and port SPKI pin.

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

- `core:engine` answers *"which items belong to this channel, and with what fields?"* The scheduler decides the order. The input is `ChannelRules` plus a
  `RuleSnapshot` (the library snapshot), and the output is the channel's pool.
- `core:scheduler` answers *"what airs at time T, and at what offset?"* The input is the settings (from the rules), the
  item list, the day number, and filler.

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
- `data:library`'s tests → `core:scheduler`, **test source sets only**, so the edit-cutoff property test can build real days from
  the library's as-of read (spec 09). Its main code reaches the scheduler only through `core:domain`.
- Any module → `core:testing`, **test source sets only** (`commonTest`, `jvmTest`, `test`, `androidTest`).
- Any module's tests → `core:domain`'s `testFixtures`, for the port fakes.

`core:scheduler` does **not** depend on `core:engine` or `core:rules`. It needs only `core:model`.
The built-in `ScheduleSource` in `core:domain` composes engine → scheduler. The Phase 5 XMLTV
`ScheduleSource` (in its own `data:xmltv`) depends on neither (Granny ruling, see ADR when scaffolded).

Enforcement: the `checkArchitecture` task, which every module's convention plugin adds to `check`, fails the build
if a module's declared dependencies break these rules. Its allowlist is `ArchitectureRules.kt` in
`build-logic/convention` (package `tv.stationbreak.buildlogic.architecture`) and must match this section; a module
not listed in this file's module tree fails until it's added to both. Android modules also run
`checkOverrideLibrary`. Every module also runs `checkTrustSurface` (`TrustSurfaceRules.kt`), which fails the build if the TLS trust API
(`X509TrustManager`, `TrustManager`, `TrustManagerFactory`, `HostnameVerifier`, `SSLContext`,
`SSLCertificateSocketFactory`, `.hostnameVerifier(`, `.sslSocketFactory(`) is named outside the `trust` package of
`data:jellyfin` (a few files are exempt by exact path), or a trust-all pattern or `CertificatePinner` is named anywhere.
Together they fail the build if
- a `[KMP]` or `[JVM]` module gains an Android dependency,
- a `java.*` import appears in `commonMain` (enforced by the compiler via the canary target; scaffold acceptance test),
- `feature:*` depends on `data:*` or on another `feature:*`,
- `core:scheduler` depends on `core:engine`/`core:rules`,
- `core:testing` is used outside a test source set,
- `core:domain`'s `testFixtures` is used outside a test source set,
- the Jellyfin SDK (`org.jellyfin.sdk:*`) is declared outside `data:jellyfin`, or OkHttp (`com.squareup.okhttp3:*`) in a
  `[KMP]` or `[JVM]` module other than `data:jellyfin` (ADR-0005: no SDK or OkHttp type may appear in a port
  signature; `PortSignatureTest` in `core:domain` is the second guard, over the compiled public API of `core:model` and
  `core:domain`),
- any module declares `com.squareup.okhttp3:logging-interceptor`, in any configuration (F0.3 R18; a detekt
  `ForbiddenImport` on `okhttp3.logging.HttpLoggingInterceptor` is the second guard),
- any manifest uses `tools:overrideLibrary`.

### Hilt in pure modules

`javax.inject` is JVM-only, so it is **kept out of** `core:model`, `core:rules`, `core:engine`, and
`core:scheduler` to preserve KMP-readiness. These are wired with `@Provides` in `app`. `core:domain` and
`data:jellyfin` may use `@Inject` constructors, through a `jvm-inject` convention plugin that adds only
`javax.inject`. Hilt `@Module`s live in `app` (or in thin `di` packages
inside the Android `data:*` modules).

An `[Android]` `feature:*` module may apply the `hilt` convention plugin, for `@HiltViewModel` and `hiltViewModel()` only
(decided F0.5, 2026-10-14). It still never binds an adapter and never depends on `data:*`: `app` provides the ports the
ViewModels inject. `[KMP]` and `[JVM]` modules still may not apply it (`checkArchitecture` fails a pure module that declares
`hilt-android`; `HiltPluginTest` and `ArchitectureRulesTest` pin both halves).

**Portability guard:** the `linuxX64()` canary makes the compiler reject `java.*` in `commonMain`. A detekt
forbidden-import rule was the backstop until the "`import java.io.File` fails the build" acceptance test passed in
CI (F0.2). It now forbids only `javax.inject` in the `[KMP]` modules (`config/detekt/kmp.yml`, ADR-0004).

### Golden vectors and the rules-format primitives

- **Golden vectors for the rules format and its primitives are canonical in `docs/rules-format/vectors/`** (JSON, with decimal,
  hex and 32-bit halves), not in `core:testing`. They are public so that other clients can check themselves against them.
  Each test module keeps a Kotlin copy in `commonTest` (the `linuxX64` tests cannot read files), and a `jvmTest` fails if a
  copy differs from the file. The scheduler's lineup vectors (`lineup.json`) are canonical there too. `core:testing` keeps
  `SyntheticLibrary`, the synthetic 10,000-item library for the scheduler's step-count test and benchmark.
- `splitmix64`, `fnv1a64`, `CanonicalId` and `Seed` live in **`core:model`**, because `core:scheduler` may not depend on
  `core:rules`. `core:rules` keeps the types, the reader and writer, the rating ladder and the automatic-channel seed
  derivation. The per-epoch seed, the key orders, run formation and the day's layout are implemented by the scheduler
  (F1.1). **There is no PRNG stream and there are no bounded integers**: every order is a hash key and a total sort, and the
  end of the day is a deterministic scan (see "Building the schedule" in the rules format). Their vectors (`epoch-seed.json`,
  `key-order.json`, `lineup.json`) are in `docs/rules-format/vectors/`.

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
- Quality: ktlint (or Spotless + ktlint), detekt (incl. the forbidden-import rule), Android Lint, Kover for coverage of the `[KMP]`/`[JVM]` modules (deferred: no Kover and no coverage thresholds on day one; detekt per KMP source set)
- License hygiene: GPL-3.0 header check, plus a dependency-license report so no GPL-incompatible deps sneak in
