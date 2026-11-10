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
│   │                            `RuleSnapshot` → the channel's pool); the scheduler orders it. Its `lineup` package holds
│   │                            the themed starter catalog and the planner that chooses a household's channels (spec 12)
│   ├── scheduler/      [KMP]    Deterministic scheduler: ordering modes, epochs, filler packing,
│   │                            "what's on at time T, at what offset". Input is the scheduler settings and an item list (any order), not rules.
│   ├── domain/         [JVM]    Ports (MediaServer, LibraryRepository, ChannelRepository, ScheduleSource,
│   │                            SecretStore, ProEntitlement), use cases, and the built-in
│   │                            ScheduleSource (engine + scheduler). Its `lineup` package owns the lineup in memory
│   │                            (spec 12): the stored-form codec, `LineupService`, `LineupRefresher`. Its `selection`
│   │                            package is the one writer of the chosen libraries (spec 26)
│   ├── testing/        [KMP]    Fixture libraries (incl. a synthetic 10k-item library), golden vectors for the
│   │                            scheduler's lineups. (The vectors for the rules format, the primitives and the lineups are
│   │                            canonical in `docs/rules-format/vectors/`; see "Golden vectors" below. `core:testing` keeps
│   │                            `SyntheticLibrary`.)
│   │                            (Port fakes live in core:domain testFixtures.)
│   ├── ui/             [Android] Compose for TV theme + design tokens (incl. retro), shared composables, D-pad helpers;
│   │                            the program text and info overlay live TV and the guide share, the guide's geometry and
│   │                            the one safe area everything lays out in (spec 17); the guide's colour tokens (`GuideColors`), type helpers (`guideText`,
│   │                            `guideFigures`) and channel marks (`ChannelMark`, `MarkCache`, and the bundled logos in `assets/channelmarks/`),
│   │                            public so live TV draws with them too (#686)
│   └── playback/       [Android] Media3 wrapper: tune-at-offset, direct-play vs transcode decision,
│                                codec capability probe, one reused player (spec 13; see "Playback")
│
├── data/
│   ├── jellyfin/       [JVM]    Jellyfin adapter (jellyfin-sdk-kotlin sealed inside, per ADR-0005):
│   │                            auth, URL normalisation, library paging, stream URLs, playback reporting.
│   │                            Implements MediaServer.
│   ├── library/        [Android] Room DB: library cache (with tombstones and item history). Sync worker.
│   │                            Implements LibraryRepository.
│   └── settings/       [Android] DataStore: servers, prefs, last channel; the channel lineup files; Keystore-backed
│                                SecretStore implementation
│
└── feature/
    ├── onboarding/     [Android] Server setup, connection test, login, library selection
    ├── live/           [Android] Full-screen player, up/down flipping, channel banner, info overlay, last-channel recall
    ├── guide/          [Android] Grid guide (>=6h) drawn over live TV: details panel, clock strip, model-driven focus
    ├── channels/       [Android] Channel list + custom channel builder
    └── settings/       [Android] Settings: Libraries (spec 26); later diagnostics, about/licenses

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

`feature:live` exists since spec 14 (offset playback, F1.3). It owns the live channel state machine (`LiveChannel`, the
sitting that follows a channel's blocks, dead items and stand-ins) and its screens (the snow, the cable banner, the test
cards). It depends on `core:domain`, `core:model`, `core:ui` and `core:playback`, which differs from `feature:onboarding`'s
set: the screen creates its player through `VideoPlayerFactory` and tunes through `TuneController`, both in `core:playback`,
and never names a Media3 type. It applies the `hilt` convention plugin for `@HiltViewModel`. `app` hosts its route and binds
what it needs (ADR-0017).

`feature:guide` exists since spec 17. It holds the program guide: `GuideViewModel` (the schedule window, the focus and the
visible range, the details of the focused program, the clock) and its screen. It depends on `core:domain`, `core:model` and
`core:ui`, and applies the `hilt` convention plugin. It is not a route of its own: live TV owns the screen, the surface and
the remote's keys, and takes the guide as a slot (`LiveDestination(onSignedOut, guide)`) that it draws over its picture while
the guide is open. A feature module may not depend on another, so `app` fills the slot with `GuideDestination`. Whether the
guide is open is live's to decide (Back from the full picture and the Guide key open it; Back with it open is the guide's, which walks the focus outwards and, from its menu row, leaves live TV);
what it shows is the guide's. Where the picture is while the guide is open is live's too, and live is the one source of
it: live moves its one surface to the top-right corner by its modifier alone (the surface is never made again), and the
slot carries that hole (a `DpRect`, never null). The guide does not work out where the picture is: it leaves the hole
clear and lays its details panel and grid out around it, left to right whatever the device's layout direction (the hole
is top-right in screen coordinates; live places its picture absolutely). A channel the guide picks (Select on a program
on now) goes back through the slot; live closes the guide and tunes it through the flip's commit (`LiveCommand.Tune`),
so Menu, the look-ahead and the tune line follow as for a flip (the line ends `via=guide`). The guide also hosts
channel management (spec 21) as a second slot, `ChannelsSlot`, behind the Channels entry of its menu row (Channels and Exit, reached by Back); `app` fills it with
`feature:channels`' `ChannelsDestination`. Settings is another entry of the menu row, a panel of three rows (Appearance,
Libraries and Sync now): Up and Down move the row, Left and Right pick the theme on Appearance, Select on Libraries opens
a third slot, `LibrariesSlot`, which `app` fills with `feature:settings`' `LibrariesDestination`, and Select on Sync now asks
`SyncNow` for a sync (a press while one runs does nothing) and shows its state on the row. The guide and live both read
`LibraryChoices.selection`. When the stored selection is gone (no library of this TV's is available), the guide shows a
message in place of the grid and keeps its menu row reachable, and live shows `LiveState.NoLibraries` and tunes nothing.
The guide's ViewModel reads `ChannelRepository.activeChannels()` (and
`onGuide()`: `onLineup()` plus each channel's `PlayKey`, the days-input digest) to see the lineup change under it
while that screen shows and just after. It needs the key because it shows the days: a new play order or a reshuffle
moves no channel or number, yet must reload the grid. Live TV and flipping follow `onLineup()` only (which channels
exist, and where); a channel's order reaches them through the schedule they read at the next block. When the channel
playing is gone from the lineup, the guide tunes the neighbour by `neighbourOf` (the next higher number, else the next
lower). The channel plate carries a 32 dp channel mark (spec 27): the logo bundled in `core:ui`'s `assets/channelmarks/` (`ChannelMark`, `MarkCache`),
found by the channel's recipe key (`ChannelAiring.recipeKey`) and decoded off the main thread into `MarkCache`, else an
initials tile; the channel column is 168 dp wide to hold it. Everything in
`feature:guide` is internal except `GuideDestination`, its `ChannelsSlot` and `LibrariesSlot` types, the `GuideMeasured` qualifier `app`
provides, and `GuideTags` for `app`'s tests.

`feature:channels` exists since spec 21 (channel management, F2.4). It holds `ChannelsViewModel` (the lineup as rows in three
groups, the focus as a value, the panel over the list, and the changes the viewer makes) and its D-pad-only screen. It depends
on `core:domain`, `core:model` and `core:ui`, and applies the `hilt` convention plugin. It reaches the lineup only through the
`core:domain` ports `ChannelRepository` (the rows), `ChannelEditor` (every change and the reset) and `ScheduleSource` (why a
channel has nothing to air today); it never sees an engine type. The channel builder (spec 22, F2.3) lives here too, in the
`builder` package: `BuilderViewModel` (the draft, the focus as a value, the picker in view, one-shot effects through a
`Channel<BuilderEvent>`) and its screens, the three steps for a new channel or a copy and one sheet for an edit. The Channels
screen draws it over its list, so it is not a route either. It adds the ports `ChannelBuilder`, `CatalogPicker` and
`ChannelPreviews` to the ones above, and reads the signed-in account from `LibraryRepository.activeAccount()`. What it
offers to choose from (`DraftChoices`: the rating ceilings and their tiers) and the channel numbers it shows
(`ChannelNumbers`: the whole range, the kids band, the custom band, where a number is first offered) are in `core:domain`,
taken from the engine, so the screen keeps no copy of the bands or the rules. Like the guide it is not a route of its own: the guide hosts it as a slot that `app` fills with
`ChannelsDestination` (a feature may not depend on another), over the picture and around the same hole. It tells its host when
it is finished with one `onDone(tune)` call. Everything in `feature:channels` is internal except `ChannelsDestination` and
`ChannelsTags` for `app`'s tests.

`feature:settings` exists since spec 26 (library settings). It holds the Libraries screen: `LibrariesSettingsViewModel` (the
server's libraries as rows, the focus as a value, and the viewer's presses) and its D-pad-only screen (Up and Down move,
Select ticks or unticks at once, Back leaves). A press flips its row at once and shows "Saving…" (a tick) or "Removing…"
(an untick) until it is stored; one change is out at a time, and the presses made meanwhile go out together as the next,
the last press of a row winning. A reopened screen shows the last listing's rows until its own listing answers. It depends on `core:domain`, `core:model` and
`core:ui` only, with a narrower allowlist than other features (no `core:playback`), and applies the `hilt` convention plugin.
It reaches the selection only through `LibraryChoices`: one listing per visit, the stored choice as `ChoiceView` (an add the
add pass gave up shows as stalled, not as being added) and `change`. When the server cannot be asked it shows the stored choice
read-only. Like the channel screen it is not a route: its host draws `LibrariesDestination` as a slot that `app` fills, over
the picture and around the same hole, and it tells its host it is finished with one `onDone()` call. Everything in
`feature:settings` is internal except `LibrariesDestination` and `SettingsTags` for `app`'s tests.

The geometry both sides must agree on is in `core:ui`, not in either feature: `ScreenLayout.safeArea` (the 5% overscan
inset the banner, the info overlay and the guide all use) and `ScreenLayout.pictureRect` (where live puts the corner
picture; the guide is handed the result). `core:ui` has grown from the theme into the shared program UI of spec 17: the
program text rules (`ProgramText`: slots, times, episode lines, and the minutes until a time as the clock and the slot
read them, with the wait to the clock's next minute that the banner and the guide both tick on), the program info
overlay, and that geometry.

`core:domain` also holds `logName()`, the name a log line prints for a class (`state.javaClass.logName()`): taken from
the binary name, because R8 drops the attribute `simpleName` needs from nested classes. It relies on the release build's
`-dontobfuscate` (`app/proguard-rules.pro`); with obfuscation it would print invented names.

The guide departs from ADR-0008's grid, which moved focus with Compose focus search over lazy lists: its focus is a value in
the ViewModel (`GuideFocus`, by channel and start), moved by pure functions over the model, and published with the grid as
one snapshot. The screen holds a single focusable node that turns D-pad keys into moves, and each cell reads only whether it
is the focused one, so a move recomposes the two cells it touched and the details panel. A grid rebuilt by the clock or by a
longer window can therefore never leave the focus on a cell that is gone, and nothing depends on what Compose would pick as
the next focus target.

It keeps ADR-0008's horizontal structure: every row's cells are laid out by time from the grid's origin, never from the
viewport, and all rows move by one shared horizontal offset (the viewport's distance from the origin) that is read in the
layout phase, when each row is placed, and clipped to the time columns. A Left/Right page therefore recomposes no cell;
the sticky titles and the "continues" chevrons follow the viewport in the layout and draw phases too. Vertically the
grid is a window of four rows that the model chooses (`firstVisibleRow`) in a plain column, in place of a lazy list
scrolled by focus. This departs from ADR-0008 decision 1, which chose standard Compose lists (a `LazyColumn` of
`LazyRow`s): the guide uses neither, and the model moves the focus rather than Compose focus search. The ADR-0008
addendum records the departure and the device results.

`data:settings` exists since F0.4 and holds `KeystoreSecretStore` (the `SecretStore` implementation) and, since F0.5
task 3.0, `FileServerSettings` (the `ServerSettings` implementation: the configured address and its certificate pin as one
atomically replaced file in `noBackupFilesDir`). `app` binds both in `MediaServerModule`. The DataStore settings arrive with
the stories that need them.

Since spec 12, `data:settings` also holds `FileLineupStore` (the `LineupStore` implementation in `core:domain`): one file per
account, `lineup/<server>/<user>.json` under `filesDir`, replaced atomically. It keeps opaque bytes and knows nothing of
their format; the codec is in `core:domain`. A path is built only from canonical IDs, a file over 1 MiB reads as a fault, and
an unreadable file is moved to `<name>.unreadable.<timestamp>`, of which the newest three are kept.

Since spec 14, `data:settings` also holds `FileLastChannelStore` (the `LastChannelStore` implementation in `core:domain`):
one small file per account under `noBackupFilesDir/lastchannel`, named by a hash of the account so no server or user ID
appears in a path, holding only the last channel's ID and replaced atomically. Sign-out leaves it. `app` binds it in
`LiveModule`.

**Privacy and removal of the lineup** ([#308](https://github.com/StationBreak/stationbreaktv/issues/308)). By design,
sign-out, session expiry and an account switch never delete a household's lineup; only an explicit user action does. The
lineup is the per-account file plus up to three set-aside copies. It holds channel names and rules, with no credentials and no
media titles, and it is app-private and excluded from backup. A user removes it today by clearing app data. A "forget this account's channels" action
is planned: `LineupService.forget` exists (it deletes the file and its set-aside copies), with no UI yet. The privacy policy
must cover this (F4.6).

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
  (`active_account`, schema 2), and sign-in and sign-out cancel the unique work first. Since spec 26 its library set is
  the selection's (`FirstSyncLibraries.firstSyncLibraries`, written under the selection's lock), and an empty set (no
  choice of this account's) ends the run with nothing written. The runner waits for the selection's resume first.
- **Item history and the as-of read (specs 09 and 10).** The cache keeps the superseded versions of an item in `item_version`
  (series and collections have matching version tables), so a day can be built from the fields the client held before its
  cut-off. `scheduleItems(account, libraries, cutoff)` and `ruleSnapshot(account, libraries, cutoff)` are that as-of read,
  and `scheduleItemsOf(account, ids, cutoffMs)` (spec 26) is the same rule read by id, in any library (a repaired day's
  tail pool and a repair's recorded tail items).
  Their **safe range is `cutoff >= now - 72 h`** (the longest epoch plus the largest grace window); an older cut-off is a
  documented contract, not a `require`, and degrades to the newest copy. The scheduler uses these, never `snapshot`, which
  hides deleted items and uses current fields.
- **History purge.** After a completed reconcile, the sync run calls `purgeHistory` for the account. It deletes tombstones
  and superseded versions older than the retention (30 days by default, `HistoryRetention`), for items, series and
  collections, and never touches the current version of a live row. The **clock guard** skips the purge, deleting nothing,
  when now is before the last successful sync or more than the retention minus 72 h after it.
- **Collection names and the pass signal (spec 12).** `collectionNames(account)` gives the current names of the account's
  collections that are not deleted, by ID; it is a planning read, never used to build a day. `collectionPassCompleted(account)`
  emits the start time of the latest whole collection pass (null until one completes, and a pass that stops part-way does
  not signal), so the lineup refresher waits for the collections and not for `lastSuccess`.
- **Cache schema version 11** (`LibraryDatabase.VERSION`). 4 added the item history, 5 added series, 6 added collections;
  7 added `sync_meta` (spec 12), 8 added studios and `backfillPending` (spec 18, migrated), 9 added a name to the
  item, series and collection version tables (spec 23, migrated with a backfill from the current rows), and 10 added
  `providerIds` to `item` and `series` and `heldUntilMs` and `addStamp` to `library` (spec 26, migrated). The migration
  to 10 sets `backfillPending` on every account, so the studios re-read writes the provider ids; a row tombstoned before
  the upgrade is never read again and keeps `''` (no ids) until it is revived or purged. 11 (spec 26, migrated) added
  `walkedStamp`, `rewalkPending` and `coveredFromMs` to `library`, the `item_also_listed` table and an index on
  `item(serverId, userId, seriesId)`; its migration sets no flag and inserts nothing (the first ID reconcile finds the
  items two libraries already shared). From version 7 on the cache migrates. A file of version 6 or older, or one newer
  than the code, is dropped and rebuilt, and that also drops the signed-in account. The schema is exported to
  `data/library/schemas/`.
- **The library rows follow the selection (spec 26).** `applySelection(account, keep, addStamps, holds, nowMs)` keeps
  `keep`'s libraries with no hold (a new one pending, with its add stamp), keeps any other library whose hold lies after
  now (setting `holds`), and deletes every other library's rows. Only the selection calls it (and `landAdd` and
  `revertUnproven`); `SelectionWriterScanTest` fails any other caller. A library given a stamp it did not have is set
  pending and its checkpoint deleted, so it is walked again; a new stamp for a chosen library that has landed is refused.
  - **Guarded writes.** Every page, catch-up, series and hidden-series write names the library and a stamp (the add's,
    or none) and is checked inside its transaction: a library that was dropped, held or stamped since the walk began
    takes nothing (`PageResult.StampMismatch`). A rewalk's write also needs the rewalk mark, and stops while a first
    sync runs (`PageResult.FirstSyncRunning`).
  - **The add lifecycle.** A tick stamps the library: it is pending, and its pages move no generation. The add walk's
    last write records the proof that it ended (`walkedStamp`, with `coveredFromMs`, the walk's start). `landAdd`
    lands it only while it still has that stamp and that proof: the stamp is cleared, the library is fetched and the
    generation moves once. A chosen row whose stamp was never proven is set back (`revertUnproven`): no stamp, pending,
    marked for a rewalk (`rewalkPending`). An untick holds the library while a programme of it is on air (a held
    library loses its stamp and proof, so it never lands) or deletes it.
  - **Fetched.** `markWalked` is the first sync's walk end (it clears a rewalk mark), `markUnlisted` marks a chosen
    library the server no longer lists (never one being added, held or marked), and `finishRewalk` ends a rewalk (it
    clears the mark and writes the coverage, and does nothing while a first sync runs).
  - **Shared items.** An item two libraries list has one owner; every other library that lists it has an
    `item_also_listed` row. A chosen writer takes an item from an owner being added, and any writer takes a tombstone,
    each as a versioned move. Before a library's rows go, each item it owns that a staying library lists moves there (a
    chosen one first, then one being added, then a held one), and a series row a live episode of a staying library
    names moves too.
  - **Coverage.** A library's `coveredFromMs` is the earliest moment its next incremental pass must read from (an add
    or a rewalk that landed, a hold placed); the next incremental pass that walks the library clears it.
  - **The generation** moves when a library's rows are deleted (an untick, an expired hold, or an add dropped after
    its walk wrote rows; a library that holds no row goes without a bump), and for a page only for a field the scheduler or the rules read: an item's in a library that
    is not being added, and a series' in any library (episodes of other libraries read a series row by its id). A
    provider-id-only change, a tick and an add's item pages never move it. When the clock is further past the
    last successful sync than a hold lasts (the pause hold, 60 minutes by default), no hold expires, so a forward jump
    cannot end one early. A page rewrites an item's genre, tag or studio rows only when that list
    changed. Provider ids are stored as `key=value;key=value`, keys sorted, and are never logged.
- **The add pass and the rewalk (spec 26).** `LibraryPass` is the one walk of a library (pages from the checkpoint,
  the catch-up of what changed since the walk began, the series and the hidden series), with three uses: the first
  sync's (no stamp), the add walk's (the add's stamp) and the rewalk's (no stamp, marked). `AddedLibrariesWorker`
  (unique work `stationbreak.add-libraries`, network-connected, exponential backoff, no input data) runs
  `AddedLibrariesRunner`. The runner waits for the selection's resume, takes its work from the selection
  (`AddLandings.pendingAdds`), walks each add under its stamp and hands it to the selection to land
  (`AddLandings.passLanded`); it never lands anything itself. A landing that may pass is tried again a few times per
  stamp with a doubling wait; one that cannot is given up until the next start, and the runner tells the selection
  (`AddLandings.addGivenUp`), which publishes it as stalled (`ChoiceView.stalled`). The selection's `LibraryPasses.restart`
  enqueues it with `REPLACE`. It stays out of the sync signal the lineup refresher holds its triggers on.
- **After stay current's passes (spec 26).** A run whose passes completed, or whose incremental pass completed and
  which was not cancelled, asks the selection to expire the holds that have ended (`HoldExpiry`, the cache write of the
  stored selection with no new hold), then lists the server's libraries once and hands that listing to the selection
  (`ServerListings`, the two-read rule). When the whole run completed, the rewalk runs on that same listing: each
  chosen, marked library it lists is walked from its checkpoint, and a failure keeps the mark for the next run. The
  rewalk reads what plays again first and is skipped when the listing left the selection gone. While the selection is
  gone, a run makes no pass, no hold expiry and no rewalk, and still lists and follows, so a chosen library that comes
  back ends the gone state. None of these steps changes how the run ended.
  - **When holds really expire.** The cache trusts the clock for hold expiry only within the pause hold (60 minutes)
    of the account's last successful sync, which only the incremental pass records. So holds expire in the hook of a
    run whose incremental pass completed, and the resume's expiry acts only within 60 minutes of one; after a longer
    gap an ended hold waits for the next incremental pass (at most about 6 hours). A call the guard froze logs
    `HoldExpiryFrozen sinceSuccessMin=<n>`. A monotonic clock pair is the follow-up that would tell a jump from a gap. The passes leave out a chosen
  library that is marked, still stamped (saved as chosen a moment before it lands) or held.
- **Sync now (spec 26).** `SyncNow` (`core:domain`) is the port: a `state` (`Idle`, `Running`, `Done(atMs)`,
  `Failed(failure)`) and `start()`, which returns false while a sync runs. `RunnerSyncNow` implements it over
  `LibraryChoices` and `StayCurrentRunner`, on a scope of its own that `app` binds in `di/StayCurrentModule`. A press
  lists the server once, through `LibraryChoices`: a listing that fails, a sign-in that has expired or an empty answer ends
  as `Failed` with nothing run and nothing written. Otherwise it waits until the selection has followed that listing
  (`LibraryChoices.followed`), then calls `StayCurrentRunner.runWaiting(SyncPlan.Both,
  followServer = false, listed)`. `runWaiting` takes the run's gate by waiting where a background run skips as busy, so a
  press queues behind a background run and two never run at once. With `followServer = false` the run expires holds
  after its passes, walks the rewalk on the press's own listing, and skips the runner's own follow of the listing. A
  completed run is `Done` (the time is the clock's), a failed one `Failed` with the run's reason, a cancelled one `Idle`,
  and an unexpected throw `Failed(Server)`. The guide's Settings panel shows the state on the Sync now row. One line
  per press is logged with numbers only.
- **Hidden views.** The library listing asks `UserViews` with `includeHidden=true`: a library the user hid from the
  server's home screen is still listed, so it stays choosable and never reads as going missing.
- **Re-id matching (spec 26).** `successors(account, libraries, gone)` finds where a tombstoned item came back under a new
  id, among the live rows of the playable libraries. The rows are read in one read transaction (narrowed in SQL to the
  gone items' series, titles and identity ids), and `ReIdMatch` runs after it, outside the transaction, over the live
  rows indexed once. It is conservative: a shared identity id (`imdb`, `tmdb` or `tvdb` only) with no identity key that
  disagrees; else an episode of the same series, season and episode; else a film of the same title and year. A
  disagreeing identity id rules a candidate out of the name fallbacks. A match needs a runtime within 2 minutes or 2%,
  exactly one candidate, and no other gone item claiming or seeing it.
- **Unit tests run Room on the JVM** over `androidx.sqlite:sqlite-bundled-jvm`, a test-only dependency that carries a native
  SQLite for the build machine. It is never in an APK. The instrumented test on the API 25 emulator runs the same schema on the
  platform SQLite.

Per-server certificate trust (F0.5 task 3.0, S0.5.2) is in `data:jellyfin`'s `trust` package: `PinnedHttpClients` builds the
one base client the adapter derives every client from, and rebuilds its `SSLContext`, client and connection pool whenever a
pin changes. Its trust manager asks the platform first (`SystemTrust`, supplied by `app` as the Network Security Config's trust
through `X509TrustManagerExtensions`) and accepts a refused chain only on an exact scheme, host and port SPKI pin.

### The channel lineup (spec 12)

- **`core:engine` `lineup` package** holds product policy as pure code: `StarterCatalog` (95 recipes and two leftovers
  channels, versioned), `LineupDefaults` (the 12-hour fill minimum, the cap of 50, the finalise threshold of 5, the 06:00 day
  start) and `LineupPlanner`, a pure function from the current lineup, the library snapshot and the collection names to the
  next lineup. It never touches storage.
- **`core:engine` `lineup.curation` package (spec 23, [ADR-0023](adr/0023-lineup-curation.md))** is the cross-channel step
  that makes the lineup feel programmed. It is pure, integer-only code, with no floating point and no random numbers.
  - `Titles`: the series or film that the curation homes, with its tone (made for kids, adult animation, and a format).
  - `FitScore`: the integer score of a title on a channel (fixed point, 16 fraction bits).
  - `Curation`: one home per title, guests, the weights (3, 2, 1) and the keeps that feed the scheduler's trim window.
    The planner (for selection) and `LineupDays` (for each day) run the same code. The planner reads the plan's snapshot
    without rating ceilings, and a day reads the snapshot at its cut-off under its ceilings.
  - `Selection`: chooses the starters to switch on by identity, and gives every other one its shelf reason.
  - `CurationDefaults`: the tunables in one place (12 usable hours, 6 titles (4 for kids), the 25% share cap, the 30% guest limit, the
    score caps).
  The tables it reads (anchors, theme studios, anime studios) are in `CurationTables`, beside the catalog-3 recipes.
- **`core:engine` `LineupDay`** (the `lineup` package) is one broadcast day, with the day-boundary predicates the planner,
  the edits and the day build all read: `startsAfter`, `leavesBy`, `isOnAirOn` and `isCuratedOn`. A change waits for the next
  day start, and "starting" and "leaving" are decided by the day, never by the moment asked.
- **`core:domain` `lineup` package** owns the lineup in memory. `LineupCodec` is the stored form (compact JSON, `"version": 3` since spec 23;
  a newer file is left alone and an unreadable one is set aside, never overwritten). `LineupService` is the one place that
  loads, plans and saves an account's lineup, under one lock per account, and implements `ChannelRepository` (the lineup as
  features see it). `LineupRefresher` calls `LineupService.refresh` at start when the lineup is absent, unfinalised or behind
  the catalog, after each completed collection pass, and after each successful sync. `BuiltInScheduleSource` implements
  `ScheduleSource.airings` by building each switched-on channel's day with one `ruleSnapshot` per distinct cut-off and no
  `scheduleItems` call (#275), and keeps the built days (in memory, and on disk across processes) under what they depend
  on rather than the lineup revision, so a renumber or a swap that changes no channel's pool builds nothing.
- **`core:domain` `lineup.repair` package (spec 26, [ADR-0025](adr/0025-repairing-today.md))** repairs the rest of
  today. `TodayRepairer` is its one engine, a single instance per process whose calls take turns on one lock:
  `selectionChanged` (a change of the selection), `repairLost` (blocks whose item left the library) and `dropSelection`
  (a change a crash left recorded but not saved). It writes the per-TV repair record the day build follows; the tune path
  only reads that record. `repairLost` reads the saved selection, its seq and its playable libraries in its own turn, and
  matches a gone block to the item's successor (`LibraryRepository.successors`, the re-id matching above) before it
  fills. `LostRepairHook` is how the lineup refresher runs it: after each sync run's writing ends, and once after the
  start check (the lost-block check is held in memory only, so each process checks once).
- **`core:domain` `selection` package (spec 26)** is the one writer of the chosen libraries. `LibrarySelection` runs
  every change of the stored selection: a change after setup, the setup itself, the landing of an add, the following of
  the server's listings, the first sync's library set and the resume at start. Callers see it only through narrow
  ports: features through `LibraryChoices` and `LibrarySetup`, the add pass through `AddLandings`, the first sync
  through `FirstSyncLibraries`, stay current through `ServerListings` and `HoldExpiry`, and the app's start through
  `SelectionResume`.
  It asks for the add pass through `LibraryPasses`. The app binds it under `@AppStart` only, so nothing else can hold it.
  Its work is split across internal collaborators in the same package (`SelectionOwnership`, `SelectionAllocation`,
  `SelectionCache`, `SelectionReconciler`, `SelectionFollower`), built by it and run only under its lock.
  - **Order.** Locks are taken in one order: the selection's lock, then the repairer's turn, then the lineup's entry
    lock, then the settings' locks; Room transactions are leaves. Stores are written in one order: the repair record,
    then the settings (the truth), then the library cache. A cache write that fails after its save is put right by the
    next operation, before anything else. Every seq is floored one above the highest add stamp in the cache.
  - **Listings.** `LibraryChoices.listing` answers as soon as the server does, never waiting on the lock (a change on
    a slow TV holds it for tens of seconds). It is then followed under the lock on the selection's own scope, queued
    on the fair lock before the listing returns, so an operation asked for after it runs after it. A listing that
    failed or listed nothing has nothing to follow. `followed` waits for every follow that may still run. A follow that
    fails is reported (`ListingFollowFailed`). Each change's time is logged by step: `SelectionChangeTook wait= turnWait=
    record= save= cache= total=`, numbers only.
  - **The start.** `StationBreakApp` runs `SelectionResume.resumeAtStart` before the lineup refresher starts. It
    raises the seq, branches every stamped chosen row (proven: landed; unproven: set back), writes the cache from the
    stored selection, and asks for the add pass and the first sync when there is work. Another account's choice found
    there (a sign-in's clear that failed) is cleared. However it ends, it opens `ResumeGate`, which everything else waits
    on as `SelectionReady`: every selection operation and the first-sync, stay-current and add workers.
  - **Setup** stores the new choice under a reset seq, writes the cache from it and asks for the first sync with a
    replacing request, so a run still reading the old choice gives way.
- **`core:domain` `lineup.builder` package (spec 22)** is how a viewer makes and edits custom channels. `ChannelDraft` is
  the builder's value: what the viewer chose, plus the settings of a copied built-in channel that the builder has no control
  for, which it keeps exactly as they were until the viewer replaces them. `DraftRules` turns a draft into `ChannelRules`
  and back, and writes the lowest format version its features need. `ChannelBuilder` opens a draft (blank, an edit, or a
  copy), creates, saves, reshuffles and deletes; each is one write under the lineup's lock, through the same edit engine as
  `ChannelEditor`, so the kids hold and the number rules cannot be weakened from here. `CatalogPicker` lists libraries,
  collections, tags and genres and searches shows and films, all from the saved library; a read that fails is `Unavailable`,
  never a throw. `ChannelPreviews` shows what a draft would air with `RuledDay` (`ruledDayOf`), the one path that builds a
  channel's pool, day and film map, shared with `LineupDays`, so the preview is the schedule's own answer and not an
  approximation. It evaluates through a plain evaluator call, never the lineup's pool cache, and reads snapshots through the
  schedule source's cache. The preview is cancellable and the screen debounces it.
- `app` wires these in `di/LineupModule`; `feature:*` modules see only `ChannelRepository` (the rows),
  `ChannelEditor` (every change a viewer makes, and the reset), `ChannelBuilder`, `CatalogPicker`, `ChannelPreviews`,
  `ScheduleSource`, `ChannelNumbers` (the bands, taken from the engine), `DraftChoices` (what the builder offers) and, for
  the account the builder works for, `LibraryRepository.activeAccount()`.
- Performance: the double refresh on every launch was fixed in [#309](https://github.com/StationBreak/stationbreaktv/issues/309)
  (closed 2026-10-21, before F1.3). Later work is in PRs #406, #481 and #484.

### Playback (spec 13)

The routes, the fallback rules and the auth rule are in [ADR-0016](adr/0016-playback-routes-and-the-media-client.md). This
section says where each piece lives.

- **`core:playback` holds:**
  - The capability probe, in the `probe` package: `CapabilityProbe` turns a `CodecInventory` into `DeviceCapabilities`
    (`AndroidCodecInventory` reads the platform). `DeviceCapabilitiesCache` probes once, warmed off the main thread at app
    start, and keeps the result. It refreshes only the audio sink part when an HDMI sink changes.
  - `PlaybackResolver`: asks the server for the item's playback info and follows its answer, except where the device
    can't play it (undecodable audio, a burned-in subtitle), and builds the URL for that route.
  - `ExpectedRoute`: a test oracle and a diagnostic. It predicts the route from the capabilities. Playback never follows it.
  - `SeekCheck`: judges whether a start or seek landed within the tolerance.
  - `VideoPlayer`, the interface, and `Media3VideoPlayer`, its one Media3 implementation. The host creates one from the
    `VideoPlayerFactory` that `app` binds (so it names no Media3 type), reuses it for every play and releases it.
  - `TuneController`: resolves, plays, runs the seek check and makes the one fallback. It returns a `TuneResult`, and the
    app branches on the case. A host calls `close(session)` when it is done: it stops the player and the encode even
    from a cancelled scope, and no tune runs after it. Close, then release the player. `preResolve` hands back a
    `Prepared` that a later `tune` plays with no server round trip (`discard` or `close` ends its encode), and a
    `Played` result carries `end`, a flow that says how the play ends (`PlaybackEnd.Ended` or `Failed`).
  - The warm standby ([ADR-0024](adr/0024-warm-next-decoder.md), off by default): `StandbySlots` holds a second, hidden
    ExoPlayer and swaps it with the front at a flip, `WarmStandby` makes the decisions (build, ready, breaker, memory trim),
    and `WarmConfig` and `WarmSwitches` hold the tunables and the debug and benchmark build switches.
- **The media client.** `JellyfinMediaServer.mediaCallFactory` (in `data:jellyfin`) is an OkHttp `Call.Factory` for the
  player's requests. `app` wraps it in Media3's `OkHttpDataSource` in `di/PlaybackModule`, under the `@MediaDataSource`
  qualifier, and `core:playback` receives it as a plain `DataSource.Factory`. It shares the one `JellyfinMediaServer`
  instance (a singleton) that is also bound as `MediaServer`, so it sees the same token bindings as every API call.
- **The benchmark build type** is release code with a `.benchmark` application ID suffix. It is not debuggable, it is signed
  with the per-machine debug key, and CI and the stores never build it. Only it contains `PlaybackTestActivity`
  (`app/src/benchmark`), which plays a list of items on a real device and reports result lines. It is exported, and it does
  nothing unless ADB debugging is on. A manifest test checks that the debug and release manifests do not contain it.

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

Rotation and capped Shuffle (rules format version 2) need to know which films share a collection, and the scheduler may not
read rules or the library. So the engine works it out (`GroupCollections.of`, from the rules, the snapshot and the day's
items) and hands the scheduler a side map beside the item list: `FilmCollections` in `core:model`, built only by `of`. It is
taken from the same snapshot as the pool, and it is kept with the built day, on disk too, so a restored day uses the stored
map. Only Rotation and capped Shuffle read it; the other orders ignore it.

Rules format version 3 adds two more side inputs of the same kind, both in `core:model` and both built only by `of`:
`TitleWeights` (a title ID to a weight of 1 to 3, read by `curated`) and `TitleKeeps` (a series ID to a cap in
milliseconds, the trim window). The franchise map of `curated` is a `FilmCollections` too, built from the collections of
the snapshot whose names are box set names. The trim window (`EpochPool.dayPool`) is applied once, when a fresh day is made
in `core:domain`; the day pool that is kept on disk is the pool after it and is restored with no keeps. The curation
step (above, `lineup.curation`) is what works out the weights and the keeps.

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
- `core:playback` → `core:model` and `core:domain`: it speaks the playback vocabulary (`PlaybackInfo`, `DeviceCapabilities`)
  and the `MediaServer` port. It never reaches `data:jellyfin`; the app gives it the adapter's media client wrapped as
  a Media3 `DataSource.Factory`, inside the `VideoPlayerFactory` it binds; a host gets the player from that factory and
  needs no Media3 dependency (ADR-0016).
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
- **Rules format version 2 (spec 22, [ADR-0022](adr/0022-rules-format-v2.md))** is final. `core:rules` reads versions 1 and 2
  and writes the lowest version a file's features need, so a channel that uses nothing new stays a version 1 file. Version 2
  adds the `rotation` and `inOrder` modes, caps, filters, and an `item` source. Its vectors (`key-order-v2.json`,
  `lineup-v2.json`, `pool-v2.json`) were computed by a reference written apart from the Kotlin and are canonical in
  `docs/rules-format/vectors/` like the others.

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
