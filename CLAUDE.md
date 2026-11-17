# StationBreak agent guide

Fire TV and Android TV app (minSdk 25). These instructions add to [CONTRIBUTING.md](CONTRIBUTING.md),
[docs/MODULES.md](docs/MODULES.md) and the ADRs in [docs/adr](docs/adr). Those stay the source of truth.

## Stack

- Kotlin 2.4, JDK 17, Android Gradle Plugin 9, Gradle wrapper (`./gradlew`).
- Compose for TV (`androidx.tv:tv-material`) and AndroidX (Navigation, Lifecycle, Room, WorkManager).
- Media3 (ExoPlayer) in `core:playback` only.
- Jellyfin: `org.jellyfin.sdk` is a dependency of `data:jellyfin`. The transport is the hand-written OkHttp
  client required by [ADR-0005](docs/adr/0005-jellyfin-client.md). Do not start a second HTTP stack.
- kotlinx-coroutines and kotlinx.serialization. Hilt (KSP) wires `app` only.
- Shared core modules are Kotlin Multiplatform (`commonMain`, `jvm`, `linuxX64` canary). They must not
  reference `java.*` or Android.

## Commands

JDK 17 is required (`JAVA_HOME`). The Android SDK is required for Android tasks and not for the KMP
command below. CI runs these from [`.github/workflows/ci.yml`](.github/workflows/ci.yml); details and
how to fix a red check are in [CONTRIBUTING.md](CONTRIBUTING.md).

```bash
# ktlint (via Spotless) and the SPDX header. Non-destructive. The Claude Code Stop hook runs this.
./gradlew spotlessCheck

# Rewrite formatting and headers. Only when you intend to change files.
./gradlew spotlessApply

# Everything CI's verify job runs: spotlessCheck, detekt, Android lint (Security issues are fatal),
# unit tests, checkArchitecture, checkTrustSurface, checkOverrideLibrary, licensee.
# Needs the Android SDK. A cold run is many minutes; do not put it on a hook.
./gradlew check

# The CI step that must pass with no Android SDK on the path.
./gradlew :core:model:jvmTest :core:scheduler:jvmTest

# KMP tests on both targets (no plain `test` task on those modules).
./gradlew allTests

# Local mirror of CI, when docker and uv are installed as well.
scripts/preflight.sh
```

`detekt` and Android lint are not separate hooks: they run inside `./gradlew check` (debug variants on
a pull request that does not need a release build; see CONTRIBUTING.md).

## Layout and lanes

Dependency direction is `app` → `feature:*` → `core:*`, and `data:*` → `core:*`. Features never depend
on each other or on `data:*`. `checkArchitecture` fails the build when an edge breaks
[docs/MODULES.md](docs/MODULES.md).

| Lane | What it owns |
| --- | --- |
| `app` | Hilt root, navigation, product flavors, binding data implementations to ports |
| `build-logic` | Convention plugins, dependency rules, trust and manifest guards |
| `core:model`, `core:rules`, `core:engine`, `core:scheduler`, `core:testing` | Pure KMP. No Android |
| `core:domain` | JVM ports and use cases |
| `core:ui` | Compose for TV theme, shared program UI |
| `core:playback` | The one Media3 player |
| `data:jellyfin` | The only Jellyfin adapter |
| `data:library` | Room cache and the WorkManager sync |
| `data:settings` | On-device files and the Keystore secret store |
| `feature:*` | Screens for that feature only |
| `docs/`, `.github/` | Spec, ADRs, CI. Extend them; do not replace them |

## Rules

- Use the platform library or API before writing your own: AndroidX, Media3, Compose for TV
  (`androidx.tv:tv-material`), the Jellyfin Kotlin SDK, WorkManager, DataStore, Room,
  kotlinx.serialization, and coroutines. An exception has to be an existing ADR (the Jellyfin
  transport in ADR-0005, the `splitmix64` / `fnv1a64` primitives in
  [ADR-0004](docs/adr/0004-kmp-shared-core.md)). Call those functions; do not paste a second copy.
- JSON bodies and files go through kotlinx.serialization. The wipeable password body in
  `data/jellyfin/.../Wire.kt` (`PasswordBody`) is the exception, because the buffer has to be zeroed.
- Viewer preferences go through Jetpack DataStore (`androidx.datastore`). Do not add another
  `SharedPreferences` file.
- Durable files in `data:settings` use the staged `.new`, `fd.sync()`, rename, directory-fsync writer
  (`DiskLineupFiles` / `DiskRecordFile`). Do not use `android.util.AtomicFile` (its `.bak` scheme on
  API 25 can leave the only copy truncated).
- DRY. If the same logic exists, call it. No dead code: delete an unused function instead of leaving it.
- Small pull requests, one change each, as CONTRIBUTING.md requires. Sign off every commit
  (`git commit -s`).
- Risky logic needs tests: parsing untrusted input, crypto, schedule math, auth, playback offset, and
  on-disk durability. A comment that a write is atomic is not a test.
