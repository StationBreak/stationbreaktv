# StationBreak — Features & User Stories

Each feature belongs to an epic in [EPICS.md](EPICS.md). Stories use **"As a … I want … so that …"**,
and their acceptance criteria (AC) are written to be **testable**. Each AC says whether it's checked by an
automated test (🧪), on a device (📺), or by inspection (👁).

IDs: `F<phase>.<n>` = feature, `S<phase>.<n>.<m>` = story. Priority: **M** must, **S** should, **C** could.
These IDs become GitHub issue titles once creating issues is approved.

---

# E0 — Foundations

## F0.1 Repo & CI (M)

**S0.1.1** As a contributor, I want CI on every PR so that broken code can't merge.
- 🧪 A GitHub Actions workflow runs on PRs and on `main`: `assemble` (all flavors), `test`, `lint`, `detekt`, `ktlintCheck`, plus an architecture test that enforces the [MODULES.md](MODULES.md) dependency rules.
- 🧪 Actions are pinned by commit SHA. Gradle wrapper validation runs.
- 🧪 Gradle and dependency caches are used; a warm PR build finishes in < 10 min.
- 👁 Branch protection on `main` requires the workflow and the DCO check to pass, and is enabled **before the repo is made public**.

**S0.1.2** As the owner, I want every source file GPL-3.0 headed and every dependency license-checked so that the project stays license-clean.
- 🧪 A CI check fails if a `.kt`/`.kts` file lacks the SPDX header `GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore` ([ADR-0003](adr/0003-licensing-and-contributions.md)).
- 🧪 A DCO check fails any PR with a commit missing `Signed-off-by`.
- 🧪 A dependency-license report is generated. CI fails on a denylisted license.
- 👁 `LICENSE` (GPLv3 text + the app-store additional permission), `README.md`, `CONTRIBUTING.md` (explains DCO and the exception), and `CODE_OF_CONDUCT.md` exist.
- 👁 The exception text is reviewed by a qualified party (e.g. Software Freedom Conservancy) **before the first outside contribution is merged**.

**S0.1.3** As a tester, I want CI to publish a debug APK artifact so that I can sideload a build without a dev machine.
- 🧪 Every `main` build uploads `stationbreak-github-debug.apk` as a workflow artifact.

## F0.2 Module skeleton (M)

**S0.2.1** As a developer, I want the module layout from [MODULES.md](MODULES.md) in place with convention plugins so that new code has an obvious home.
- 🧪 All modules exist and build. `./gradlew :core:scheduler:test` runs with no Android SDK on the path.
- 🧪 A build check fails if a `[KMP]`/`[JVM]` module declares an Android dependency, or if `feature:*` depends on `data:*`.
- 🧪 KMP acceptance (ADR-0004): adding `import java.io.File` to a `commonMain` file fails the build, and an Android module consumes `core:model` via a plain project dependency.

**S0.2.2** As a developer, I want a hello-world Compose for TV screen that's D-pad navigable so that the UI stack is proven on the target device.
- 📺 The app launches on the Insignia, shows up in the Fire TV "Your Apps" row with a banner, and focus moves with the D-pad.

## F0.3 Jellyfin adapter (M)

**S0.3.1** As a user, I want to enter my server as a URL or IP:port in any reasonable form so that reverse-proxied and LAN servers both work.
- 🧪 URL normalisation accepts, with table-driven tests: `host:8096`, `192.168.1.10:8096`, `http://…`, `https://…`, `https://host/jellyfin`, `https://host/jellyfin/`, IPv6 literal `[fd00::1]:8096`, trailing paths like `/web/index.html` (stripped).
- 🧪 With no scheme, `https` is tried first. Fallback to `http` happens **only** on connection refused or unreachable, **never** on a TLS error (TLS errors go to S0.5.2). The probe sends no credentials. The chosen scheme is persisted and never re-probed downward.
- 🧪 **Cleartext policy:** the network security config permits cleartext (hosts are arbitrary), and the app gates it. `http` to private addresses (RFC1918, link-local, `fd00::/8`, `.local`) is allowed without friction. `http` to a public host requires an explicit "insecure connection" confirm before any password is sent.
- 🧪 Subpaths are preserved on every request (verified against a mock engine).

**S0.3.2** As a user, I want to sign in with username/password or Quick Connect so that the TV holds only a scoped, revocable token for a non-admin user ([ADR-0001](adr/0001-client-authentication.md)).
- 🧪 `AuthenticateByName` returns a token + userId and is persisted via `SecretStore`.
- 🧪 The client offers **no** dashboard-API-key login. Those keys are admin-level and not user-scoped (ADR-0001).
- 🧪 Every request sends a stable `Authorization: MediaBrowser Client=…, Device=…, DeviceId=…, Version=…` header. DeviceId is generated once per install.
- 🧪 Quick Connect login: the TV shows a code, which is approved from a device already signed in as the target user. If Quick Connect is disabled on the server, the UI says so and offers password login instead.
- 🧪 Logout calls `POST /Sessions/Logout` so the token is revoked server-side, then wipes local secrets.

**S0.3.3** As a user, I want library listing so that I can choose which libraries feed my channels.
- 🧪 `GET /UserViews` maps to domain `Library` objects. Only video-capable collection types are offered (movies, tvshows, mixed, homevideos, musicvideos).

**S0.3.4** As a developer, I want typed, actionable connection errors so that the UI can explain failures.
- 🧪 Errors are distinguished: DNS failure, connection refused, timeout, TLS untrusted cert, TLS hostname mismatch, HTTP 401/403, not a Jellyfin server (unexpected `/System/Info/Public` payload), and unsupported server version (below 12.0, ADR-0006; the cause chain is inspected, since the SDK wraps refused/DNS/timeout in one exception type).

**S0.3.5** As a user on an old Fire TV, I want the app to trust current Let's Encrypt roots so that my server keeps working after the X2←X1 cross-sign is retired.
- 🧪 `network_security_config` adds bundled **ISRG Root X2** and **ISRG Root YE** as extra trust anchors (alongside system CAs). This works on API 24+ with no custom TLS stack.
- 📺 A server using the current Let's Encrypt chain (`YE2 → Root YE → ISRG Root X2 → ISRG Root X1`) connects on the Fire OS 6.7.0.8 reference device.
- 🧪 An instrumented test validates a chain that terminates at Root X2 **without** the X1 cross-sign.

**S0.3.6** As the maintainer, I want the adapter tested against every supported Jellyfin major so that server upgrades don't break users.
- 🧪 A CI job starts Jellyfin **oldest supported 12.x** and **latest 12.x** containers (12.0+ only, ADR-0006), completes the startup wizard via API, seeds a small fixture library, and runs the adapter contract tests (auth, Quick Connect, libraries, items paging, PlaybackInfo, playback reporting) against both.
- 🧪 A version matrix in the workflow makes adding or dropping a major a one-line change.

## F0.4 Secure credential storage (M)

**S0.4.1** As a user, I want my access token stored securely so that another app or a backup can't lift it.
- 🧪 `SecretStore` implementation: a Keystore AES/GCM/NoPadding key (`setUserAuthenticationRequired(false)`, since TVs have no lock screen), with a random IV stored alongside the ciphertext in DataStore. No token appears in plain SharedPreferences (an instrumented test greps the prefs dir).
- 🧪 `android:allowBackup="false"` **and** `dataExtractionRules` excluding DataStore and Room (Android 12+ device-to-device transfer ignores `allowBackup`). Checked by a manifest lint test.
- 🧪 Tokens never appear in logs. A case-insensitive redaction layer covers `api_key`, `ApiKey`, `Token=`, `X-Emby-Token`, `X-Emby-Authorization`, and `Authorization`.
- 🧪 `KeyPermanentlyInvalidatedException`, `UnrecoverableKeyException`, or `AEADBadTagException` wipe the key and the blob, then route to "please sign in again". There is never a plaintext fallback.
- 👁 Documented limitation: budget Fire OS may only have a software-backed Keystore. This protects against backup extraction and other apps, not against root.

## F0.5 Server setup & connection test (M)

**S0.5.1** As a user, I want a setup screen with a connection test so that I know it works before continuing.
- 📺 D-pad-friendly form: server address → **Test** → shows server name + version on success.
- 📺 Every error from S0.3.4 shows a plain-language message plus a next step (e.g. "Looks like a self-signed certificate → Trust this certificate?").
- 📺 The on-screen keyboard works on Fire TV. Paste and voice input are not required.

**S0.5.2** As a user with a self-signed certificate, I want to trust it explicitly so that I can connect without disabling security globally.
- 🧪 Trust is **per server, pinned to the leaf certificate's SPKI SHA-256** (trust-on-first-use with an explicit confirm screen that also shows the cert fingerprint for comparison).
- 🧪 It's implemented as a per-server `X509TrustManager` (system trust first, otherwise accept only when the pin matches), **not** OkHttp `CertificatePinner`, which runs after system trust and so never sees a self-signed cert. A hostname mismatch is tolerated only when the pin matches. There's no `HostnameVerifier { true }` and no global `HttpsURLConnection` defaults.
- 🧪 The **same pinned OkHttp client** serves the API, Media3 (`OkHttpDataSource.Factory`, never `DefaultHttpDataSource`), HLS segments, subtitles, and image loading.
- 🧪 Redirects to a different host don't inherit the pin.
- 🧪 If the pinned certificate changes, the connection fails with a "certificate changed" error that requires re-confirmation.
- 🧪 Instrumented tests: pin match, pin mismatch, cross-host redirect, cert rotation.

**S0.5.3** As a user, I want onboarding to recommend a dedicated non-admin Jellyfin user so that a stolen TV can't administer my server.
- 📺 The login step shows the recommendation. If the authenticated user is an administrator (`Policy.IsAdministrator`), a dismissible warning appears.

**S0.5.4** As a user, I want to pick which libraries StationBreak uses so that irrelevant ones (e.g. home videos) stay out.
- 📺 A multi-select of the S0.3.3 libraries. The selection persists. **The Phase 0 gate is checked on this screen.**

## F0.6 Library sync & cache (M)

**S0.6.1** As a user with a 10k+ item library, I want the initial sync to complete reliably so that channels have complete data.
- 🧪 Paged `GET /Users/{id}/Items` (`Recursive=true`, `IncludeItemTypes=Movie,Episode,…`, explicit `Fields=` list, `StartIndex/Limit` pages of ~500) writes into Room in transactions.
- 🧪 The fixture test syncs a synthetic 15k-item library with peak heap < 64 MB growth. The sync runs in a WorkManager worker and survives process death (it resumes from the last committed page).
- 📺 Initial sync of the owner's real library on the Insignia completes; duration is recorded in the PR.

**S0.6.2** As a user, I want incremental sync so that new episodes show up without a full re-sync.
- 🧪 Incremental pass uses `MinDateLastSaved` (or equivalent) since the last successful sync. Deletions are detected by a periodic ID reconciliation (see Q-JF-4).
- 🧪 Sync runs on app start (if stale > N hours) and on a periodic WorkManager schedule.

**S0.6.3** As the scheduler, I need trustworthy runtimes so that offsets are right.
- 🧪 Items with null/zero `RunTimeTicks` are excluded from scheduling and counted in a diagnostics stat.
- 🧪 Each item stores `dateCreated` (used for deterministic snapshots, see F1.1).

## F0.7 Channel rules format v1 (M, document + types only)

**S0.7.1** As a future Roku/Apple TV developer, I want the channel rules JSON format specified so that every client produces the same lineup.
- 👁 `docs/rules-format.md` defines the schema with a `formatVersion` field. It covers sources (library, genre, collection, tag, year range, series), a **`maxRating` ceiling** with a documented rating-order table (US TV + MPAA in v1; Q-RULES-1), ordering mode, block length, filler, seed, and the PRNG and hash-keyed ordering algorithms **by exact specification**.
- 🧪 Under a `maxRating` ceiling, unrated items and items rated in an unsupported system are excluded (fail closed). Without a ceiling, every item is eligible.
- 🧪 `core:rules` round-trips the documented examples. Unknown fields are preserved (forward compatibility).
- 🧪 A client given a `formatVersion` newer than it supports does **not** schedule that channel. It shows "update required" instead.

---

# E1 — Live core

## F1.1 Deterministic scheduler (M)

**S1.1.1** As a user signed in on several TVs (home, Grandma's), I want each to show the same thing on the same channel at the same time so that it feels like real broadcast.
- 🧪 Schedule = f(rules, library snapshot visible to the account, seed, epoch). No device-local state, randomness, or locale enters it. Scope: same account → same lineup ([ADR-0002](adr/0002-determinism-scope.md)).
- 🧪 The PRNG is specified exactly and implemented in `core:scheduler`, using unsigned 64-bit arithmetic. Kotlin's `Random(seed)` and `java.util.Random` are **not** used, so that other platforms can re-implement it bit-for-bit.
  - Per-epoch seed: `seed_n = splitmix64(seed + n · 0x9E3779B97F4A7C15)`. Never `seed XOR n`, which correlates neighbouring epochs.
  - Bounded integers use rejection sampling, not modulo.
- 🧪 Base order before any shuffle: a canonical sort by item ID as bytes. Never sort by title, because collation depends on locale.
- 🧪 **Golden vectors**: checked-in fixtures `(formatVersion, rules, snapshot, seed, T) → (itemId, offsetMs)` for ≥ 50 cases. CI fails on any drift. These vectors are the cross-platform contract.
- 🧪 Every input that affects the schedule is a versioned, serialised document that travels with the account and is covered by golden vectors. New inputs (e.g. a future house-level config for station breaks) are added the same way, never as device settings.

**S1.1.2** As a user, I want adding one new episode to my library not to reshuffle every channel's current program so that what I'm watching doesn't jump.
- 🧪 Time is divided into **epochs** (default 24 h) anchored to a **fixed UTC origin plus an optional fixed offset stored in the channel rules** (e.g. "broadcast day starts 06:00 at UTC−5"). The device timezone and DST are **never** used (see Q-SCH-1).
- 🧪 Epoch *n* uses `seed_n` and the snapshot of items with `dateCreated < epochStart(n) − G`, where G is a grace window (e.g. 6 h) that absorbs sync lag. Cross-device agreement is **eventual**: guaranteed once both devices have synced past `epochStart − G`.
- 🧪 Epochs are independent. An epoch boundary is a **hard cut** (or filler/slate once F3.1 exists); it never means "start after the current item", which would chain epochs together.
- 🧪 Adding, removing, or re-timing items affects only future epochs. The current epoch's lineup is unchanged (property tests for adds, deletes, and `RunTimeTicks` edits).
- 🧪 The library cache **tombstones** deleted items and freezes their runtime until the epoch that referenced them ends, so prefix sums don't shift. Deleted items are skipped at playback time (that slot shows a slate, see F1.3).

**S1.1.3** As a user, I want ordering modes so that channels feel intentional.
- 🧪 `SHUFFLE`: **hash-keyed order** per epoch: sort by `splitmix64(seed_n + fnv1a64(itemIdBytes))`, ties broken by ID bytes (ADR-0002). No repeats within an epoch until the pool is exhausted. Property test: removing any item leaves the relative order of the others unchanged (start times of later items shift by that item's runtime).
- 🧪 `CHRONOLOGICAL`: by air date / series-season-episode, continuing across epochs. The starting cursor for epoch *n* is **closed-form** from epoch *n*'s snapshot (not replayed from the origin), so it's O(1) to compute and stable.
- 🧪 `RANDOM_IN_ORDER`: picks series in hash-keyed order and plays each series' next episode in order. Per-series cursors are closed-form from `(seed_n, snapshot_n)`.
- 🧪 **Ordering primitives (exact spec):** item ID canonical form = 32 lowercase hex ASCII chars with no hyphens, normalised on ingest (some Jellyfin endpoints return hyphenated GUIDs) and used everywhere (base sort, hash, tie-break). `fnv1a64` uses offset `0xcbf29ce484222325` and prime `0x100000001b3`. All arithmetic wraps mod 2^64 with logical shifts. Keys compare **unsigned**, and ties compare canonical ID bytes unsigned. `RANDOM_IN_ORDER` keys on series ID. When the pool runs out within an epoch, the same order repeats.
- 🧪 Golden vectors cover `splitmix64`, `fnv1a64`, and key ordering **separately** (so ports built from 32-bit halves, e.g. BrightScript, can verify each primitive), including at least one key with the high bit set to catch signed sorting. Property tests cover item removal and insertion.
- 🧪 `blockLength` (e.g. play 2 episodes of a series per block) is honoured. Blocks start on the :00/:30 grid when `alignToGrid=true`, **the default** (Q-SCH-2). Before F3.1 filler exists, the gap before the next grid slot is a station ID slate (a non-item `ScheduleBlock`). Golden vectors cover aligned and unaligned channels.

**S1.1.4** As the player, I need `whatsOn(channel, T)` to be fast so that tune-in fits the 2 s budget.
- 🧪 `whatsOn` returns (item, offset, nextItems[n]) in < 5 ms on a JVM benchmark with a 10k-item channel (it uses the cached ScheduleBlocks or prefix-sum binary search, not a linear scan).
- 🧪 A `ScheduleBlock` has `wallStart`, `wallEnd`, and `mediaStartOffsetMs`. It references either an item or a non-item slot (slate or filler). One item may span several blocks, so a program can pause and resume. The offset that `whatsOn` returns is the position in the media, not the time since the block started.
- 🧪 ScheduleBlocks for [now − 1h, now + 24h] are materialised to Room per channel and regenerated when an epoch rolls or the rules change.

## F1.2 Channel engine & default channels (M)

**S1.2.1** As a new user, I want channels generated automatically so that I can start watching without configuring anything.
- 🧪 Defaults: one channel per selected library, one per genre with ≥ N items (default 10), and one per collection (BoxSet) with ≥ N items.
- 🧪 Generation is deterministic: the same library gives the same channels with the same numbers and seeds. The default seed = hash(serverId, channel rules).
- 🧪 Channels with zero schedulable items are not created.
- 📺 More than 50 auto channels is capped/grouped (see Q-UX-1).

**S1.2.2** As the engine, I need rule evaluation against the cache so that channel membership is correct.
- 🧪 Union/intersection of sources and exclusion rules are covered by table tests over the fixture library.

## F1.3 Offset playback (M)

**S1.3.1** As a viewer, I want tuning to a channel to start the current program at the right point so that it feels live.
- 🧪 The player requests the Jellyfin static stream URL for the item and seeks to `offset`. On the transcode path, `StartTimeTicks` is passed so the server starts encoding at the offset (no client-side seek).
- 📺 Visual offset error is ≤ ±2 s versus the guide's expected position.
- 🧪 After a seek, the player compares the actual position with the requested offset. If they differ by more than 2 s (for example an MKV without Cues, which ExoPlayer silently plays from 0; [ADR-0007](adr/0007-tune-latency-spike.md)), it falls back to the transcode path with `StartTimeTicks`.

**S1.3.2** As a viewer, I want playback to continue seamlessly into the next scheduled item so that the channel never stops.
- 🧪 The next item is queued in the Media3 playlist ahead of time. When an item ends early or late (runtime metadata wrong), playback re-syncs to the schedule at the next boundary instead of drifting (see Q-SCH-3).

**S1.3.3** As a viewer, I want a dead item (deleted, unplayable) to be skipped so that the channel doesn't hang.
- 🧪 On a source error, the player skips to the next scheduled item with offset 0 and shows a brief "signal lost" slate. It retries the original item at most once.

## F1.4 Channel flipping & last-channel recall (M)

**S1.4.1** As a viewer, I want Up/Down (and CH+/CH− where the remote has them) to change channel so that I can surf.
- 📺 Up = next channel number, Down = previous, wrapping around. Fire TV remotes without CH keys work via D-pad. Media keys are mapped where present.

**S1.4.2** As a viewer, I want the app to open on the last channel I watched so that it's "just on".
- 🧪 The last channel is persisted on every tune. Cold start goes directly to the live player on that channel (skipping menus) once setup is complete.

**S1.4.3** As a viewer, I want a "previous channel" toggle so that I can jump between two channels.
- 📺 The now/next banner (opened with Select) offers a **"Last: <channel>"** action that tunes the previous channel; pressing it again toggles back.
- 📺 The **Menu (☰)** key is a one-press shortcut for the same toggle. Back is **not** used for it (Q-UX-2).
- 📺 Back follows the platform convention: it closes an open overlay or the guide first, then leaves live TV.

**S1.4.4** As the owner, I want tune latency measured so that the Phase 1 gate is objective.
- 🧪 For each tune, the app records two times: `keypress → first rendered frame` (Media3 `onRenderedFirstFrame`) and `keypress → playing`, the first point at which playback is actually advancing (`onIsPlayingChanged(true)`, so a frozen first frame doesn't count). They're kept in a local ring buffer, shown on a diagnostics screen and exportable to logcat. Nothing is sent anywhere. Spike SP-1 found the first frame can appear 1–2 s before playback moves.

**S1.4.5** As a viewer, I want flipping to be fast even on a budget stick so that surfing feels like cable.
- 📺 **Gate measurement:** keypress → playing < 2.0 s, with at most 1 of 30 flips over (3 runs of 10 on the Insignia, as in the Phase 1 gate). First frame is reported but not gated.
- 🧪 Investigate and close the first-frame-to-playing gap SP-1 found on some items, where the frame shows at about 0.9 s but playback starts at 1.9–2.9 s. The suspected cause is audio renderer readiness ([ADR-0007](adr/0007-tune-latency-spike.md)).
- Techniques to evaluate (spike first; see R-PERF-1): reusing one ExoPlayer instance, tuned `LoadControl` buffers for fast start, pre-resolving stream URLs for adjacent channels, and optionally pre-warming the adjacent channel's first segment.

## F1.5 Playback reporting (M)

**S1.5.1** As a Jellyfin user, I want my viewing reported so that server watch state and "Continue Watching" are accurate.
- 🧪 `POST /Sessions/Playing`, `/Sessions/Playing/Progress` (every ~10 s and on pause), and `/Sessions/Playing/Stopped` are sent with correct `PositionTicks`.
- 🧪 Behaviour for marking an item **played** when the viewer tuned in mid-way is configurable (default from Q-JF-5).

## F1.6 Direct-play / transcode fallback (M)

**S1.6.1** As a viewer with a weak device, I want unsupported codecs transcoded automatically so that everything plays.
- 🧪 A device capability probe (MediaCodecList: codecs, profiles, levels, max resolution, HDR) builds a Jellyfin `DeviceProfile`. `POST /Items/{id}/PlaybackInfo` decides between direct play, direct stream, and transcode.
- 🧪 The `DeviceProfile` declares subtitle formats, both embedded and external, even with subtitles off. Without them Jellyfin refuses direct play for items carrying subtitle streams. Video the device can't decode, such as MPEG-4 Part 2 (seen in AVI), routes to transcode ([ADR-0007](adr/0007-tune-latency-spike.md)).
- 🧪 Transcode uses HLS (`master.m3u8`) with `StartTimeTicks` = offset.
- 🧪 **Stream auth rule:** the token travels only in the `Authorization` header, via an OkHttp interceptor scoped to the pinned server origin. `ApiKey`/`api_key` is stripped from the server's `TranscodingUrl`, and no media URL carries the token.
- 📺 A test matrix on the Insignia: H.264 MP4, HEVC MKV, AV1 (expect transcode), DTS audio (expect audio transcode), and PGS subtitles (expect burn-in or off; see Q-PLY-1).

---

# E2 — Guide and builder

## F2.1 Grid guide (M)

**S2.1.1** As a viewer, I want a grid guide showing ≥ 6 hours ahead so that I can see what's coming.
- 📺 Rows are channels, columns are time in 30-minute slots, with a "now" line. Program cells are proportional to duration.
- 📺 D-pad navigation: Up/Down moves between channels, Left/Right moves through programs in time. Focus never gets lost or jumps to an off-screen cell.
- 📺 Select on a current program tunes to that channel. Select on a future program shows its info (reminders: see Q-UX-3).
- 🧪 Guide data comes from materialised ScheduleBlocks (S1.1.4), never computed on the UI thread.

**S2.1.2** As a viewer, I want to open the guide from live TV without losing the picture so that it feels like cable.
- 📺 The guide opens over the live video (picture continues, either as a PIP corner or behind a translucent overlay, see Q-UX-4). Back returns to full screen on the same channel without re-tuning.

## F2.2 Now/next banner & info overlay (M)

**S2.2.1** As a viewer, I want a banner on every tune showing channel, now, and next so that I know what I'm watching.
- 📺 The banner appears for ~4 s on tune or on an info key press. It shows channel number/name/logo, the current title (with S/E for episodes), a progress bar, and the next title + start time.

**S2.2.2** As a viewer, I want an info overlay with the description so that I can decide whether to stay.
- 📺 Select (or the Info key) during playback opens the overview, year, genres, and runtime.

## F2.3 Custom channel builder (M)

**S2.3.1** As a user, I want to build a channel by picking sources so that I can make "Saturday Morning Cartoons".
- 📺 A step flow: name → sources (libraries, genres, collections, tags, specific series, year range) → exclusions → ordering mode → block length → preview → save. Every step is fully D-pad operable.
- 🧪 The builder produces rules JSON valid against the `core:rules` schema.

**S2.3.2** As a user, I want a live preview so that I can see the channel before saving.
- 📺 The preview shows the item count, total runtime, and the next ~6 hours of lineup from the real scheduler.

**S2.3.3** As a user, I want to edit and delete custom channels and reseed shuffle so that I can fix a lineup I don't like.
- 🧪 Editing the rules or seed regenerates ScheduleBlocks from the current epoch onward.

## F2.4 Channel management (M)

**S2.4.1** As a user, I want to renumber, hide, and favourite channels so that the lineup is mine.
- 🧪 Channel numbers are unique. Hidden channels are skipped by flipping and by the guide.
- 📺 Auto-generated channels can be hidden but are regenerated (not resurrected) on library change (see Q-UX-1).

## F2.5 Large-library performance (M)

**S2.5.1** As a user with 10k+ items and 50+ channels, I want the guide to stay smooth so that the app feels premium on a budget stick.
- 🧪 A macrobenchmark on the Insignia (or the closest emulator profile) runs: guide open → first frame < 1.0 s, and a vertical scroll over 50 channels with jank < 5% of frames.
- 🧪 A Baseline Profile is generated in CI and shipped in the release build.
- 🧪 Room queries for the guide window are indexed (`channelId, startTime`) and verified with `EXPLAIN QUERY PLAN` in a test.

---

# E3 — Polish and personality

## F3.1 Filler & bumpers (S)

**S3.1.1** As a nostalgia fan, I want bumpers, commercials, or shorts between programs from a chosen library so that it feels like a real station.
- 🧪 A per-channel filler source (library/collection/tag) and mode: `none`, `pad-to-grid` (fill to the next :00/:30), and `between-every-N`.
- 🧪 Filler packing is deterministic (it uses the scheduler PRNG) and covered by golden vectors.
- 🧪 When filler can't exactly fill a gap, the remainder is shown as a station ID slate (bundled asset) instead of drifting off the grid.

## F3.2 Retro mode (S)

**S3.2.1** As a nostalgia fan, I want 4:3 framing and a CRT overlay so that old shows look period-accurate.
- 📺 A per-channel toggle: 4:3 pillarbox/zoom, a scanline + vignette shader overlay, and optional era-styled guide theme.
- 📺 The overlay costs < 2 ms per frame on the Insignia (via `RenderEffect`/AGSL where available. API 25 needs a GL/Bitmap fallback or the feature is disabled; see R-PLAT-2).

## F3.3 Themes (S)

**S3.3.1** As a user, I want a few guide themes (modern, '80s cable, '90s satellite) so that the app has personality.
- 📺 The theme is switchable in settings, and all themes pass a 10-foot legibility check (min text size and contrast on a 1080p TV at 3 m).

## F3.4 Guided onboarding (M for E3 gate)

**S3.4.1** As a first-time user who isn't technical, I want onboarding that explains each step so that I succeed without help.
- 📺 Welcome → server (with examples of URL formats) → sign in (non-admin advice) → libraries → "Here are your channels" → first tune.
- 📺 A companion "set up from your phone" path is a candidate (QR code + local web form) to avoid typing URLs with a remote; see Q-UX-5.

## F3.5 Watch-state options (S)

**S3.5.1** As a user, I want to skip or include watched items per channel so that I see fresh content.
- 🧪 The per-channel `watchedPolicy` is `include` / `skip` / `prefer-unwatched`.
- 👁 Per ADR-0002, the lineup stays identical across one account's devices but changes as that user watches. The settings UI says so.

## F3.6 Diagnostics & opt-in crash reporting (S)

**S3.6.1** As a beta tester, I want to send a diagnostic bundle when something breaks so that the developer can fix it.
- 🧪 Crash reporting is **off by default**. Turning it on requires explicit consent in settings. The tool is self-hostable or privacy-respecting (see Q-PRIV-1).
- 🧪 Diagnostic export (redacted logs, device info, tune-latency stats) stays local, shown as a QR or copied to a URL only when the user acts.
- 🧪 Redaction of the bundle is tested. Tokens and **server hostnames/URLs** count as sensitive.

## F3.7 Beta program (M for E3 gate)

**S3.7.1** As the owner, I want a structured beta so that the E3 gate is measurable.
- 👁 A recruitment post template, an install guide, a feedback form or GitHub issue template, and a tracker of tester/device/outcome.

---

# E4 — Public release

## F4.1 Release pipeline & signing (M)
**S4.1.1** As the owner, I want tagged releases built and signed by CI so that releases are reproducible.
- 🧪 A `v*` tag triggers release builds for all flavors. Signing keys live in GitHub encrypted secrets (or Play App Signing for `play`).
- 🧪 `versionCode` is derived from the tag. A changelog is generated from Conventional Commits.
- 🧪 Supply chain: Gradle `verification-metadata.xml` + wrapper validation, GitHub Actions pinned by commit SHA, Renovate for updates, and the signing secret in a protected environment with required reviewers.
- 👁 The release publishes the APK SHA-256, the signing-cert fingerprint, and a build-provenance attestation. An offline backup of the `github` signing key exists, because losing it breaks upgrades.

## F4.2 Amazon Appstore listing (M)
- 👁 Listing assets (icon, banner 1280×720, screenshots), privacy policy URL, and content rating. 📺 The app passes Amazon's Fire TV certification (D-pad only, no touch requirement). 👁 Submitted with **no DRM** (ADR-0003).

## F4.3 Google Play listing (M)
- 👁 TV-specific requirements: leanback banner, `android.software.leanback` declared, `touchscreen required=false`, TV screenshots, and the Data Safety form (no data collected by default).

## F4.4 GitHub Releases (M)
- 🧪 The `github` flavor APK + SHA-256 checksums are attached to the release. 👁 Downloader-app instructions (Fire TV "Downloader" short code) are in the docs.

## F4.5 Pro unlock (M)
**S4.5.1** As a supporter, I want a one-time Pro purchase so that I can support the project.
- 🧪 The `ProEntitlement` port has implementations for Amazon IAP and Play Billing. Purchase, restore, and refund revocation are covered by fakes.
- 👁 The Pro feature set is defined (Q-BIZ-2). The `github` flavor behaviour is decided (Q-BIZ-1).

## F4.6 Docs site (M)
- 👁 StationBreak.tv is a static site (e.g. Astro/MkDocs, deployed from this repo or `stationbreak/site`) covering install, server setup, reverse-proxy notes, self-signed certs, channel building, FAQ, and privacy policy.

---

# E5 — Expansion (outline; re-plan after E4)

- **F5.1 Channel import/export / sharing between accounts**: **deferred.** Every option explored (short links, codes, server-side storage) needs static data hosted somewhere, which is a bigger problem than the core product. Re-plan after the companion service or a plugin approach is decided. Until then, custom channels live on the device that built them. The portable rules format keeps this cheap to add later. Context: sharing is between accounts on the *same* media server, as a copy with a fresh seed (same recipe, not same lineup).
- **F5.2 Companion sync server**: reuses `core:*` and `data:jellyfin` (JVM). It syncs channels/settings, not schedules.
- **F5.3 XMLTV/M3U `ScheduleSource`**: reads Tunarr/ErsatzTV output. The guide and player stay unchanged.
- **F5.4 Roku** (separate repo): consumes the rules format plus the golden vectors.
- **F5.5 Apple TV**: either Swift re-implementation against the golden vectors, or the `core:*` modules converted to KMP (see Q-ARCH-1).
- **F5.6 Emby adapter → F5.7 Plex adapter**: new `MediaServer` implementations.
- **F5.8 Profiles & parental controls**: per-profile channel sets, a rating ceiling, and a PIN.
