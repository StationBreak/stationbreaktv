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
- 🧪 A GitHub Actions workflow runs on PRs and on `main`: `assemble` (all flavors), `test`, `lint`, `detekt`, `spotlessCheck` (ktlint and the SPDX header), via `./gradlew check`, so checks added later (such as F0.2's architecture test) run automatically.
- 🧪 Actions are pinned by commit SHA. Gradle wrapper validation runs.
- 🧪 An API 25 emulator job runs on every PR, in parallel with the main build: a launch smoke test plus all instrumented tests. It catches crashes from APIs missing on the Insignia's Android version before anyone installs the APK. Automation is the main line of defence; manual checks on the Insignia supplement it rather than replace it.
- 🧪 F0.1 includes a minimal `:app` placeholder: one module, an empty activity, the SPDX header and the real application ID ([ADR-0010](adr/0010-application-id.md)). Every check goes green on it before F0.2 starts, and F0.2 grows it into the real skeleton.
- 🧪 Gradle and dependency caches are used; a warm PR build finishes in < 10 min.
- 👁 Dependency updates come from the hosted Renovate GitHub App, configured in `renovate.json`: a 3-day minimum release age, no automerge in Phase 0, related updates grouped (for example all of Compose), and updates that would raise minSdk above 25 caught (R-PLAT-1). Angua (Security) reviews the config.
- 👁 Branch protection on `main` requires the workflow and the DCO check to pass, and is enabled **before the repo is made public**. GitHub Free doesn't offer protection on private repos, so until then it's by convention: from F0.1 on, code changes go through PRs merged only when CI is green. Docs-only changes may go straight to `main` until the flip.
- 👁 **Signed commits** (GPG or SSH, shown as Verified) are required on `main`. Set up later, by the visibility flip at the latest. Every identity that commits, including agent sessions committing for the owner, needs a signing key first.

**S0.1.2** As the owner, I want every source file GPL-3.0 headed and every dependency license-checked so that the project stays license-clean.
- 🧪 A CI check fails if a `.kt`/`.kts` file lacks the SPDX header `GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore` ([ADR-0003](adr/0003-licensing-and-contributions.md)).
- 🧪 A DCO check fails any PR with a commit missing `Signed-off-by`. It's an in-repo GitHub Actions step that reads every commit in the PR, not a third-party app. Commits from `renovate[bot]` are exempt, since a bot can't certify the DCO, but only when the author is the `renovate[bot]` account (type Bot) **and** the commit has a GitHub-verified signature. GitHub links commits to accounts by email alone, so the account match on its own could be spoofed.
- 🧪 A dependency-license report is generated. CI fails on any license that isn't on an allowlist of GPL-3.0-compatible licenses (for example Apache-2.0, MIT, BSD, LGPL), including licenses the checker can't identify. Adding a license to the allowlist, or a per-dependency override, needs a reviewed PR.
- 👁 `LICENSE` (GPLv3 text + the app-store additional permission), `README.md`, `CONTRIBUTING.md` (explains DCO and the exception), and `CODE_OF_CONDUCT.md` exist.
- 👁 The exception text is reviewed by a qualified party (e.g. Software Freedom Conservancy) **before the first outside contribution is merged**.

**S0.1.3** As a tester, I want CI to publish a debug APK artifact so that I can sideload a build without a dev machine.
- 🧪 Every `main` build uploads `stationbreak-github-debug-main-<shortsha>.apk` as a workflow artifact. So does every PR whose branch is in this repo (`stationbreak-github-debug-pr<N>-<shortsha>.apk`, kept 7 days), so changes can be checked on the Insignia before they merge. Fork PRs build and test but upload no APK, and run on `pull_request` only, never `pull_request_target` ([ADR-0011](adr/0011-debug-build-signing.md)).
- 🧪 CI debug builds are signed with the CI debug key, held in secrets, and use the application ID `tv.stationbreak.app.debug`, so each CI build installs over the last one. Fork PRs and local builds use a per-machine key ([ADR-0011](adr/0011-debug-build-signing.md), amended 2026-10-18; the committed `debug.keystore` was retired).

**S0.1.4** As the owner, I want every PR security-scanned automatically so that agent-written code can't ship known weaknesses unnoticed.
- 🧪 Every PR runs these checks, and each is meant to block merge (advisory until the visibility flip, [ADR-0012](adr/0012-ci-gates-advisory-until-flip.md)): zizmor and actionlint on the workflows; gitleaks (the CLI, run directly) over the PR's full commit range, with the retired `debug.keystore` allowlisted by exact path only ([ADR-0011](adr/0011-debug-build-signing.md)); OSV-Scanner on dependencies, failing on new findings; and Android Lint's Security category raised to error inside `check`.
- 🧪 OSV-Scanner also runs weekly on `main` and opens an issue for any finding.
- 👁 Dependabot alerts and Dependabot update PRs are off: Renovate is the one tool for dependency updates and alerts (owner decision, 2026-10-12; may be revisited). Renovate's `osvVulnerabilityAlerts` is on, so fixes for vulnerable dependencies skip the 3-day wait.
- 👁 **On the day of the visibility flip**, three checks that need a public repo are turned on and made required: CodeQL (`java-kotlin`) on every PR and weekly, blocking on high severity; GitHub secret scanning with push protection; and the dependency review action, with Gradle dependency submission. CodeQL is **not** run while the repo is private, because its licence doesn't cover private code; Android Lint and detekt cover code analysis until then.
- 👁 Rejected as noise or duplication: MobSF in CI (at most one manual scan before the first store release), Snyk, Sonar, Trivy, Semgrep default rules, and Dependabot update PRs.

## F0.2 Module skeleton (M)

**S0.2.1** As a developer, I want the module layout from [MODULES.md](MODULES.md) in place with convention plugins so that new code has an obvious home.
- 🧪 The day-one modules ([MODULES.md](MODULES.md), "Day-one set") exist and build; the rest are created by the first story that needs them. `./gradlew :core:scheduler:jvmTest` runs with no Android SDK on the path ([MODULES.md](MODULES.md), principle 1).
- 🧪 `app` defines two flavors, `amazon` and `github`. `play` comes later, at F4.3 ([MODULES.md](MODULES.md#build-flavors)).
- 🧪 An architecture test enforces the [MODULES.md](MODULES.md) dependency rules, and runs in CI as part of `check`. It fails if a `[KMP]`/`[JVM]` module declares an Android dependency, or if `feature:*` depends on `data:*`. Its first PR shows it catching a deliberately bad edge and passing an allowed one.
- 🧪 Gradle dependency verification (`verification-metadata.xml`, SHA-256) is turned on here, with a baseline generated against the real dependency set. Every later dependency change updates it in the same PR. Angua (Security) reviews the baseline.
- 🧪 KMP acceptance (ADR-0004): adding `import java.io.File` to a `commonMain` file fails the build, and an Android module consumes `core:model` via a plain project dependency.
- 🧪 Build hygiene (Granny (Architecture), PR #136 review): `./gradlew check` passes from a clean Gradle home with JDK 17 and the Android SDK, no other JDK; the `linuxX64` canary resolves under `FAIL_ON_PROJECT_REPOS` (any toolchain repository is declared centrally, never by loosening the mode); detekt runs on the KMP source sets in `check`; and `app`'s flavors, desugaring and toolchain move into an `android-app` convention plugin.

**S0.2.2** As a developer, I want a hello-world Compose for TV screen that's D-pad navigable so that the UI stack is proven on the target device.
- 📺 The APK on the Insignia is the CI debug artifact (S0.1.3), never a local build.
- 📺 The app launches on the Insignia, shows up in the Fire TV "Your Apps" row with a banner, and focus moves with the D-pad.
- 👁 The application ID is `tv.stationbreak.app`, and the package root and Gradle `namespace` use `tv.stationbreak` ([ADR-0010](adr/0010-application-id.md)).

## F0.3 Jellyfin adapter (M)

**S0.3.1** As a user, I want to enter my server as a URL or IP:port in any reasonable form so that reverse-proxied and LAN servers both work.
- 🧪 URL normalisation accepts, with table-driven tests: `host:8096`, `192.168.1.10:8096`, `http://…`, `https://…`, `https://host/jellyfin`, `https://host/jellyfin/`, IPv6 literal `[fd00::1]:8096`, trailing paths like `/web/index.html` (stripped).
- 🧪 With no scheme, `https` is tried first. Fallback to `http` happens **only** on connection refused, network unreachable, or a positively identified plaintext answer to the TLS hello (a plain-HTTP LAN server answering the TLS hello is the common case), **never** on any other TLS error, a certificate problem, a timeout or a DNS failure (TLS errors go to S0.5.2). The probe sends no credentials. `discover` is setup-only and reports whether it fell back; `connect` probes the given scheme only and never downgrades. Persisting the chosen scheme belongs to F0.4/F0.5 ([ADR-0014](adr/0014-scheme-fallback-and-cleartext-policy.md)).
- 🧪 **Cleartext policy:** the network security config permits cleartext (hosts are arbitrary), and the adapter gates it. `http` to private addresses (RFC 1918, link-local, loopback, `fc00::/7` (covers `fd00::/8`), `fe80::/10`, `.local`, `.home.arpa`, single-label names; decided from the address text only, carrier-grade NAT `100.64.0.0/10` is not private) is allowed without friction. `http` to a public host requires an explicit "insecure connection" confirmation, bound to that one address, before any password, Quick Connect secret or token is sent ([ADR-0014](adr/0014-scheme-fallback-and-cleartext-policy.md)).
- 🧪 Numeric hosts are strict dotted-decimal (`127.1`, `0x7f.1` and `2130706433` are rejected); user-info in the address is rejected.
- 🧪 Subpaths are preserved on every request (verified against a mock engine).

**S0.3.2** As a user, I want to sign in with username/password or Quick Connect so that the TV holds only a revocable, Keystore-encrypted token for the signed-in account (admin or not, decision 9) ([ADR-0001](adr/0001-client-authentication.md)).
- 🧪 `AuthenticateByName` returns a token + userId and is persisted via `SecretStore`.
- 🧪 The client offers **no** dashboard-API-key login. Those keys are admin-level and not user-scoped (ADR-0001).
- 🧪 Every request after the probe sends exactly one `Authorization: MediaBrowser Client=…, Device=…, DeviceId=…, Version=…` header (values percent-encoded; plus `Token=…` once signed in), and no credential is placed in a URL. DeviceId is generated once per install and kept across sign-out.
- 🧪 The session records `isAdministrator` (from `Policy.IsAdministrator`) (recorded only: S0.5.3 was dropped, decision 9).
- 🧪 The token is bound to the server that issued it (server ID plus host, port and subpath): a stored token is probed against its server before first use, and a different server clears the session instead of receiving the token.
- 🧪 Quick Connect login: the TV shows a code, which is approved from a device already signed in as the target user. If Quick Connect is disabled on the server, the UI says so and offers password login instead.
- 🧪 Logout clears the stored session first, then calls `POST /Sessions/Logout` with a short timeout (still clearing if the server is unreachable or the caller is cancelled), and reports whether the token was revoked. The device ID is kept.

**S0.3.3** As a user, I want library listing so that I can choose which libraries feed my channels.
- 🧪 `GET /UserViews` maps to domain `Library` objects. Only video-capable collection types are offered (movies, tvshows, mixed, homevideos, musicvideos).

**S0.3.4** As a developer, I want typed, actionable connection errors so that the UI can explain failures.
- 🧪 Errors are distinguished: DNS failure, connection refused, timeout, TLS untrusted cert, TLS hostname mismatch, HTTP 401/403, not a Jellyfin server (unexpected `/System/Info/Public` payload), and unsupported server version (below 12.0, ADR-0006; the cause chain is inspected, since the SDK wraps refused/DNS/timeout in one exception type).

**S0.3.5** As a user on an old Fire TV, I want the app to trust current Let's Encrypt roots so that my server keeps working after the X2←X1 cross-sign is retired.
- 🧪 `network_security_config` adds bundled **ISRG Root X2** and **ISRG Root YE** as extra trust anchors (alongside system CAs). This works on API 24+ with no custom TLS stack.
- 📺 A server using the current Let's Encrypt chain (`YE2 → Root YE → ISRG Root X2 → ISRG Root X1`) connects on the Fire OS 6.7.0.8 reference device.
- 🧪 An instrumented test validates a chain that terminates at Root X2 **without** the X1 cross-sign.

**S0.3.6** As the maintainer, I want the adapter tested against every supported Jellyfin major so that server upgrades don't break users.
- 🧪 A CI job starts Jellyfin **oldest supported 12.x** and **latest 12.x** containers (12.0+ only, ADR-0006; since 2026-10-14 the two newest 12.x minors, never dropping the owner's), completes the startup wizard via API, seeds a small fixture library, and runs the adapter contract tests (auth, Quick Connect, libraries, items paging, PlaybackInfo, playback reporting) against both.
- 🧪 A version matrix in the workflow makes adding or dropping a major a one-line change.

## F0.4 Secure credential storage (M)

✅ **Built 2026-10-14** (specs 04, [ADR-0009 amendment](adr/0009-keystore-spike.md)). The Keystore-backed `SecretStore`, restart survival of the session and device ID, the backup rules test, and token redaction are in; the reference-device run is recorded on the PR.

**S0.4.1** As a user, I want my access token stored securely so that another app or a backup can't lift it.
- 🧪 `SecretStore` implementation: a Keystore AES/GCM/NoPadding key (`setUserAuthenticationRequired(false)`, since TVs have no lock screen), with a Keystore-generated IV, stored with the ciphertext as a versioned blob ([ADR-0009](adr/0009-keystore-spike.md)). Where the blob lives (DataStore or a private file) is decided in the implementation. The version and IV-length header is passed to GCM as AAD (`updateAAD`), so it can't be altered undetected. The key has one fixed, namespaced alias, and key creation and saving run under a single mutex so a race on first save can't create two keys. An instrumented test greps the whole data dir for the token, not just prefs.
- 🧪 `android:allowBackup="false"` **and** `dataExtractionRules` excluding DataStore and Room (Android 12+ device-to-device transfer ignores `allowBackup`). Checked by a manifest lint test.
- 🧪 Tokens never appear in logs. A case-insensitive redaction layer (`Redactor`) covers `api_key`, `ApiKey`, `Token`, `X-Emby-Token`, `X-Emby-Authorization`, `Authorization`, `AccessToken`, `Secret`, `Pw` and `Password`, in query parameters, header lines, quoted pairs and JSON pairs.
- 🧪 Loading fails closed: **any** failure routes to "please sign in again", and there is never a plaintext fallback ([ADR-0009](adr/0009-keystore-spike.md)).
  - **Definitive failures wipe the key and the blob:** a missing key (`getKey` returns null with no exception), `KeyPermanentlyInvalidatedException`, `UnrecoverableKeyException`, `AEADBadTagException`, `IllegalBlockSizeException`, or a blob with an invalid version or length.
  - **Transient failures don't wipe:** `KeyStoreException`, `ProviderException` and `IOException` route to sign-in but keep the key and the blob. For example, the Keystore may not be ready just after boot.
  - `CancellationException` is rethrown and never wipes.
  - Each case has a test.
- 👁 Documented limitation: some budget Fire OS devices may only have a software-backed Keystore. That protects against backup extraction and other apps, not against root. On the reference Insignia, the key reports itself as hardware-backed (TEE, not attested) ([ADR-0009](adr/0009-keystore-spike.md)).

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

**S0.5.3** ~~As a user, I want onboarding to recommend a dedicated non-admin Jellyfin user so that a stolen TV can't administer my server.~~ **Dropped by owner decision 9 (2026-10-15).**
- There is no admin warning and no "use a non-admin account" tip. There is one sign-in, and the account that signs in is the app's user, admin or not. Reasons: people who run Jellyfin already understand the risk, consumers already have non-admin accounts, and a tip would make people anxious for no good reason on a read-only app.
- `Session.isAdministrator` is still recorded (S0.3.2) and nothing acts on it. The residual risk the owner accepted: Jellyfin tokens aren't scoped, so a token taken from a rooted or debug-enabled TV carries the account's full rights. It can be revoked from Dashboard → Devices, and a factory reset destroys the Keystore key.
- 🧪 The contract suite signs in as an admin and as a non-admin; both succeed and `isAdministrator` is recorded correctly (spec 05, task 2.5).

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
- 👁 `docs/rules-format.md` defines the schema with a `formatVersion` field. It covers sources (library, genre, collection, tag, year range, series), a **`maxRating` ceiling** with a documented rating-order table (US TV + MPAA in v1; Q-RULES-1), ordering mode, block length, filler, seed, and the hash functions and hash-keyed ordering algorithms **by exact specification** (there is no PRNG).
- 🧪 Under a `maxRating` ceiling, unrated items and items rated in an unsupported system are excluded (fail closed). Without a ceiling, every item is eligible.
- 🧪 `core:rules` round-trips the documented examples. Unknown fields are preserved (forward compatibility).
- 🧪 A client given a `formatVersion` newer than it supports does **not** schedule that channel. It shows "update required" instead.

---

# E1 — Live core

## F1.1 Deterministic scheduler (M)

**S1.1.1** As a user signed in on several TVs (home, Grandma's), I want each to show the same thing on the same channel at the same time so that it feels like real broadcast.
- 🧪 Schedule = f(rules, library snapshot visible to the account, seed, epoch). No device-local state, randomness, or locale enters it. Scope: same account → same lineup ([ADR-0002](adr/0002-determinism-scope.md)).
- 🧪 The seed is a **required** field of the channel rules (a decimal string of an unsigned 64-bit number; a file without one is invalid), so it travels with the rules and is never worked out again from other fields ([rules format](rules-format.md#seed)).
- 🧪 The hashing is specified exactly, using unsigned 64-bit arithmetic. `splitmix64` and `fnv1a64` are implemented in `core:model` (so that `core:scheduler`, which may not depend on `core:rules`, can use them); the per-epoch seed and key ordering are implemented in `core:scheduler`. There is **no random-number stream and no bounded integer**: every order is a hash key and a total sort, so no rejection sampling is needed. Kotlin's `Random(seed)` and `java.util.Random` are **not** used, so that other platforms can re-implement it bit-for-bit.
  - Per-epoch seed: `seed_n = splitmix64(seed + n · 0x9E3779B97F4A7C15)`. Never `seed XOR n`, which correlates neighbouring epochs.
- 🧪 Every order is a total key ending in the canonical item ID as bytes, so the result does not depend on the order items are listed in. Never sort by title, because collation depends on locale.
- 🧪 **Golden vectors**: checked-in fixtures `(formatVersion, rules, snapshot, seed, T) → (itemId, offsetMs)` for ≥ 50 cases. CI fails on any drift. These vectors are the cross-platform contract.
- 🧪 Every input that affects the schedule is a versioned, serialised document that travels with the account and is covered by golden vectors. New inputs (e.g. a future house-level config for station breaks) are added the same way, never as device settings.

**S1.1.2** As a user, I want adding one new episode to my library not to reshuffle every channel's current program so that what I'm watching doesn't jump.
- 🧪 Time is divided into **epochs** (default 24 h) anchored to a **fixed UTC origin plus an optional fixed offset stored in the channel rules** (e.g. "broadcast day starts 06:00 at UTC−5"). The device timezone and DST are **never** used (see Q-SCH-1).
- 🧪 Epoch *n* uses `seed_n` and the snapshot of items with `dateCreated < epochStart(n) − G`, where G is a grace window (e.g. 6 h) that absorbs sync lag. Cross-device agreement is **eventual**: guaranteed once both devices have synced past `epochStart − G`.
- 🧪 Epochs are independent. The **end of each epoch is planned**: a program is never cut at the boundary and never runs over it. The scheduler fills the last stretch with shorter programs from the channel's own pool that fit, and a station-ID slate for the rest (filler replaces the slate once F3.1 exists). It never means "start after the current item", which would chain epochs together.
- 🧪 Adding, removing, or re-timing items affects only future epochs. The current epoch's lineup is unchanged (property tests for adds, deletes, and `RunTimeTicks` edits). A day uses each item's fields as the client held them before `epochStart - G`; see the rules format, The pool of an epoch (Edits). Spec 09 keeps the earlier copy.
- 🧪 The library cache **tombstones** deleted items and freezes their runtime until the epoch that referenced them ends, so prefix sums don't shift. Deleted items are skipped at playback time (that slot shows a slate, see F1.3).

**S1.1.3** As a user, I want ordering modes so that channels feel intentional.
- 🧪 `shuffle`: **hash-keyed order** per epoch: sort by `splitmix64(seed_n + fnv1a64(itemIdBytes))`, ties broken by ID bytes (ADR-0002). No repeats within an epoch until the pool is exhausted. Property test: removing any item leaves the relative order of the others unchanged (start times of later items shift by that item's runtime).
- 🧪 `chronological`: by air date / series-season-episode, continuing across epochs. The starting cursor for epoch *n* is **closed-form** from epoch *n*'s snapshot (not replayed from the origin), so it's O(1) to compute and stable.
- 🧪 `randomInOrder`: picks series in hash-keyed order and plays each series' next episode in order. The groups (series) are keyed with the channel `seed` (not `seed_n`), so their order is the same every day; where a day starts is found from the continuous playout, closed-form from that day's pool (see the rules format, Building the schedule).
- 🧪 **Ordering primitives (exact spec):** item ID canonical form = 32 lowercase hex ASCII chars with no hyphens, normalised on ingest (some Jellyfin endpoints return hyphenated GUIDs) and used everywhere (the sorts, hash, tie-break). `fnv1a64` uses offset `0xcbf29ce484222325` and prime `0x100000001b3`. All arithmetic wraps mod 2^64 with logical shifts. Keys compare **unsigned**, and ties compare canonical ID bytes unsigned. `randomInOrder` keys on series ID. When the pool runs out within an epoch, the same order repeats.
- 🧪 Golden vectors for the primitives live in `docs/rules-format/vectors/` (`splitmix64.json`, `fnv1a64.json`, `epoch-seed.json`, `key-order.json` and the lineup vectors `lineup.json`; computed with an independent reference, with 32-bit halves) and are run by `core:model` and `core:scheduler` tests. They cover `splitmix64`, `fnv1a64`, and key ordering **separately** (so ports built from 32-bit halves, e.g. BrightScript, can verify each primitive), including at least one key with the high bit set to catch signed sorting. Property tests cover item removal and insertion.
- 🧪 `blockLength` (e.g. play 2 episodes of a series per **run**; a "block" is one item or one slate) is honoured. The channels the app creates start each run when the previous one ends (they write `alignToGrid=false`, Q-SCH-2 as amended 2026-10-25). The rules format's own default is `alignToGrid=true`: runs start on the `gridMinutes` grid (default :00/:30), and before F3.1 filler exists the gap before the next grid slot is a station ID slate (a non-item `ScheduleBlock`). Golden vectors cover aligned and unaligned channels.

**S1.1.4** As the player, I need `whatsOn(channel, T)` to be fast so that tune-in fits the 2 s budget.
- 🧪 `whatsOn` returns (item, offset, nextItems[n]) in < 5 ms on a JVM benchmark with a 10k-item channel (it uses the cached ScheduleBlocks or prefix-sum binary search, not a linear scan).
- 🧪 A `ScheduleBlock` has `wallStart`, `wallEnd`, and `mediaStartOffsetMs`. It references either an item or a non-item slot (slate or filler). One item may span several blocks, so a program can pause and resume. The offset that `whatsOn` returns is the position in the media, not the time since the block started.
- 🧪 ScheduleBlocks for [now − 1h, now + 24h] are built per channel and regenerated when an epoch rolls or the rules change. Not built yet: persisting them to Room. Spec 12's built-in schedule source builds each switched-on channel's day (settings, epoch and day pool) and keeps the last few sets in memory, per lineup revision.

## F1.2 Channel engine & default channels (M)

**S1.2.1** As a new user, I want channels set up for me automatically so that I can start watching without configuring anything.
- 🧪 The app ships a **themed starter catalog** (`StarterCatalog`, in `core:engine`): 95 recipes, each with a stable key, an original name, a one-line personality, a band, a default number, a rules template and an opt-in flag. Recipes use only portable sources (genre, tag, year range) and optional collection-name hints. It replaces one channel per library, per genre and per collection as the default. Decision, 2026-10-19: [#112](https://github.com/StationBreak/stationbreaktv/issues/112).
- 🧪 Numbers are cable-style bands: Kids 1–99, Comedy and general 100–199, Drama, crime, movies and classics 200–299, Genre and cult 300–399, Anime and international 400–499, Premium-shaped and late night 500–599, your own channels 600–899, 900–999 reserved. A recipe keeps its number whether it is on or on the shelf, and numbers and recipe keys are never reused.
- 🧪 Every kid-safe recipe carries a rating ceiling of PG or lower; unrated items stay off under a ceiling. Grown-up recipes carry none. A test proves no kid-safe recipe lacks a ceiling.
- 🧪 The lineup is not finalised until the first full library sync has filled the cache. A recipe **fills** when its whole pool (exclusions and ceiling applied) holds at least 12 hours of runtime. The best-filling non-opt-in recipes are switched on, up to 50 channels in all, including the two leftovers channels. The rest wait on the shelf. With fewer than 5 qualifying recipes the choice is not final and is tried again after the next sync. Opt-in recipes (After Hours) are never switched on automatically.
- 🧪 **Leftovers:** *Kids' Odds & Ends* (99, ceiling PG) and *Odds & Ends* (199) air what no other switched-on channel takes. Opt-in content and the content of a channel the user hid never reach leftovers, and a kids' item airs once. A lineup count of items airing nowhere is the check that the catalog does its job.
- 🧪 **Self-refresh:** after each library sync and after each full collection pass, a shelved recipe that now fills is switched on at its own number while the lineup is under the cap. Nothing already on the lineup moves, and a channel the user hid or removed does not come back. A newer catalog updates channels the user has not edited; an edited channel is a custom channel and is never touched.
- 🧪 **Per account and per TV:** the lineup is kept as one file per account (server and user) and is never deleted by sign-out, session expiry or an account switch. Only an explicit user action removes it. Lineups are chosen on each TV, so two TVs set up at different times may differ (sharing is "cross-device sync", Later).
- 🧪 A lineup stores one fixed UTC offset, the device zone's standard (non-daylight-saving) offset at first setup, and every starter and leftovers channel uses it, with the broadcast day starting at 06:00.
- 🧪 Generation is deterministic: the same server, catalog version and library give the same lineup, numbers and seeds. The default seed of a starter channel is `splitmix64(fnv1a64(bytes))`, where the bytes are the 32 ASCII characters of the server ID, `0x00`, the kind `recipe`, `0x00`, and the recipe key. The channel's own ID and its other rules are never inputs. It is written into the rules the app generates ([rules format](rules-format.md#seed), `core:rules` `AutoSeed`). The `library`, `genre` and `collection` kinds stay in the format; nothing the app ships uses them yet.
- 🧪 A channel whose pool for the current day is empty (the evaluator's pool after the scheduler's per-day filter) is hidden from the guide and from flipping, kept in channel management with the reason from `PoolCounts`, and comes back by itself when it has items again. This is separate from a user's hide (S2.4.1). Decision, 2026-10-19: [#299](https://github.com/StationBreak/stationbreaktv/issues/299).
- 🧪 Building a day's channels makes one `ruleSnapshot` call per distinct cut-off and no `scheduleItems` call ([#275](https://github.com/StationBreak/stationbreaktv/issues/275)).
- 🧪 The guide (F2.1) and banner (F2.2) are built (spec 17) and read the lineup from `ChannelRepository` and the built-in `ScheduleSource`. The builder (F2.3) and channel management and shelf (F2.4) will do the same when they arrive.
- 📺 Performance, tracked in [#309](https://github.com/StationBreak/stationbreaktv/issues/309) and **to land before F1.3**: on the Insignia (API 25) a lineup refresh takes 7–10 s and `airings` takes 23–25 s for 50 channels. Both are over the 5 s target, and every launch refreshes twice.

**S1.2.2** As the engine, I need rule evaluation against the cache so that channel membership is correct.
- 🧪 Union of sources and exclusions are covered by table tests, a seeded property test and the published `pool.json` vectors; AND of sources is [#293](https://github.com/StationBreak/stationbreaktv/issues/293).

## F1.3 Offset playback (M)

Before this feature starts, the lineup refresh and `airings` speed on the Insignia must be fixed ([#309](https://github.com/StationBreak/stationbreaktv/issues/309); see S1.2.1).

**S1.3.1** As a viewer, I want tuning to a channel to start the current program at the right point so that it feels live.

📺 **Built 2026-10-23, device check pending** (spec 14, [ADR-0017](adr/0017-live-channel-playback.md)). The app opens on the live channel once setup is finished; the device check is the 📺 line below.

📺 **Opening and the screen checked on 2026-10-24** (build 930247c, Firestick 4K AFTKRT API 30 and Insignia AFTJMST12 API 25, same result on both). The offset-error line below and the S1.3.2 and S1.3.3 checks are still pending.
- ✅ The first open after a fresh setup lands on a PG-tier channel ([#375](https://github.com/StationBreak/stationbreaktv/issues/375)).
- ✅ The screen stays awake during live TV past the 5-minute sleep timeout, and the normal screensaver and ambient mode return after leaving live TV ([#374](https://github.com/StationBreak/stationbreaktv/issues/374)).
- ✅ Banner text stays inside the banner and the safe area ([#374](https://github.com/StationBreak/stationbreaktv/issues/374)), checked on the channel that opened. Recheck the long-channel-name case once flipping (F1.4) exists.
- Open: every tune on an item with MPEG-4 Part 2 video and MP3 audio in an AVI container went through a failed direct-play attempt before the transcode fallback, taking 6.7–18.4 s to first frame ([#371](https://github.com/StationBreak/stationbreaktv/issues/371), related [#340](https://github.com/StationBreak/stationbreaktv/issues/340)). The capability probe no longer offers AVI for direct play (PR [#386](https://github.com/StationBreak/stationbreaktv/pull/386)); recheck on the device after flipping lands.
- Open: the first-setup "No channels on the air yet" card showed for about 80–90 s. The wait is accepted, and the copy changes to a "building your channels" state ([#372](https://github.com/StationBreak/stationbreaktv/issues/372)).

- 🧪 The player requests the Jellyfin static stream URL for the item and seeks to `offset`. On the transcode path the player seeks the same way: the HLS URL carries no `StartTimeTicks` (Jellyfin 12.2 rejects it on segments), and the server encodes from the segment the player asks for ([ADR-0016](adr/0016-playback-routes-and-the-media-client.md)).
- 📺 Visual offset error is ≤ ±2 s versus the guide's expected position.
- ✅ 🧪 After a seek, the player compares the actual position with the requested offset. If they differ by more than 2 s (for example an MKV without Cues, which ExoPlayer silently plays from 0; [ADR-0007](adr/0007-tune-latency-spike.md)), it falls back to the transcode path, which the player seeks to the offset. **Delivered in spec 13** (`SeekCheck` and `TuneController`, [ADR-0016](adr/0016-playback-routes-and-the-media-client.md), 4).

**S1.3.2** As a viewer, I want playback to continue seamlessly into the next scheduled item so that the channel never stops.

📺 **Built 2026-10-23, device check pending** (spec 14, [ADR-0017](adr/0017-live-channel-playback.md)). The cut to the next block is checked on the Insignia and the Firestick.

- 🧪 The next item is resolved ahead of time (45 s before the block ends) and played at the block's start. When an item ends early or late (runtime metadata wrong), the schedule wins: the ident card covers the rest of the block, or the item is cut at the boundary. Resolved as Q-SCH-3.

**S1.3.3** As a viewer, I want a dead item (deleted, unplayable) to be skipped so that the channel doesn't hang.

📺 **Built 2026-10-23, device check pending** (spec 14, [ADR-0017](adr/0017-live-channel-playback.md)). The dead-item cases are checked on the Insignia and the Firestick.

- 🧪 On a source error, the player retries the original item once, shows a "Technical difficulties" slate for about 3 s, then moves on to the next scheduled item from its start (30 minutes or less left in the dead block, a configurable default in `LiveTimings`; the channel then runs ahead for this sitting only and any retune re-anchors it) or fills the block with a deterministic stand-in (more than 30 minutes left).

## F1.4 Channel flipping & last-channel recall (M)

**S1.4.1** As a viewer, I want Up/Down (and CH+/CH− where the remote has them) to change channel so that I can surf.

📺 **Built 2026-10-24, device check pending** (spec 15, [ADR-0017](adr/0017-live-channel-playback.md); engine in PR [#390](https://github.com/StationBreak/stationbreaktv/pull/390), keys in PR [#391](https://github.com/StationBreak/stationbreaktv/pull/391)).
- 📺 Up = next channel number, Down = previous, wrapping around. Fire TV remotes without CH keys work via D-pad. The media next and previous keys are not mapped: they are reserved for pause and skip ([#383](https://github.com/StationBreak/stationbreaktv/issues/383)).

**S1.4.2** As a viewer, I want the app to open on the last channel I watched so that it's "just on".
- 🧪 The last channel is persisted on every tune. Cold start goes directly to the live player on that channel (skipping menus) once setup is complete.

**S1.4.3** As a viewer, I want a "previous channel" toggle so that I can jump between two channels.

📺 **Menu half built 2026-10-24** (spec 15, PR [#390](https://github.com/StationBreak/stationbreaktv/pull/390) and PR [#391](https://github.com/StationBreak/stationbreaktv/pull/391)). **Banner "Last" action built 2026-10-26** (spec 17, PR [#419](https://github.com/StationBreak/stationbreaktv/pull/419)); device check done.
- 📺 The now/next banner (opened with Select) offers a **"Last: <channel>"** action that tunes the previous channel; pressing it again toggles back.
- 📺 The **Menu (☰)** key is a one-press shortcut for the same toggle. Back is **not** used for it (Q-UX-2).
- 📺 Back follows the platform convention (Q-UX-2): it closes an open overlay (the banner, Info) first. From the full-screen show it then opens the guide, and Back in the guide leaves live TV. The tune banner never blocks input.

**S1.4.4** As the owner, I want tune latency measured so that the Phase 1 gate is objective.

📺 **Ring buffer and logcat line built 2026-10-24** (spec 15, PR [#390](https://github.com/StationBreak/stationbreaktv/pull/390)): one `SBLIVE flip` line per committed flip, timed from the last press, the newest 50 kept. The ring buffer is in memory, per viewing: it starts empty each time live TV starts. The diagnostics view is deferred.
- 🧪 For each tune, the app records two times: `keypress → first rendered frame` (Media3 `onRenderedFirstFrame`) and `keypress → playing`, the first point at which playback is actually advancing (`onIsPlayingChanged(true)`, so a frozen first frame doesn't count). They're kept in a local ring buffer, shown on a diagnostics screen and exportable to logcat. Nothing is sent anywhere. Spike SP-1 found the first frame can appear 1–2 s before playback moves.

**S1.4.5** As a viewer, I want flipping to be fast even on a budget stick so that surfing feels like cable.

📺 **Gate measurement pending** the device check after PR [#391](https://github.com/StationBreak/stationbreaktv/pull/391), against the two-tier gate in [EPICS.md](EPICS.md). Player reuse and the neighbour pre-resolve are built (look-ahead in PR [#390](https://github.com/StationBreak/stationbreaktv/pull/390), [ADR-0017](adr/0017-live-channel-playback.md)); only the gate measurement is pending.
- 📺 **Gate measurement** (the two-tier Phase 1 gate, [EPICS.md](EPICS.md), decided 2026-10-24), timed from the last key press over 30 flips per device. Firestick 4K (API 30): keypress → playing < 2.0 s on at least 29 of 30 direct-play flips. Insignia (API 25): the banner's channel number updates within about 250 ms, the median direct-play flip is under 4 s and the worst under 8 s, and no flip hangs: every flip ends on a moving picture or the channel's stand-in or slate. Transcoded flips are reported, not gated. First frame is reported but not gated.
- 🧪 Investigate and close the first-frame-to-playing gap SP-1 found on some items, where the frame shows at about 0.9 s but playback starts at 1.9–2.9 s. The suspected cause is audio renderer readiness ([ADR-0007](adr/0007-tune-latency-spike.md)).
- Techniques to evaluate (spike first; see R-PERF-1): reusing one ExoPlayer instance, tuned `LoadControl` buffers for fast start, pre-resolving stream URLs for adjacent channels, and optionally pre-warming the adjacent channel's first segment.

## F1.5 Playback reporting (M)

**S1.5.1** As a Jellyfin user, I want my live viewing to show on the server dashboard without cluttering "Continue Watching", and an item I really watched to be marked played.

Design and evidence: [ADR-0018](adr/0018-live-playback-reporting.md) (spike SP-6, Q-JF-5).
- 🧪 Reporting starts only after the viewer stays on a channel for 30 s (configurable), so flipping adds no play counts or activity-log entries; once settled, the next program on that channel is reported at once. Reporting never delays a tune or a flip.
- 🧪 `POST /Sessions/Playing` carries `PositionTicks` = the safe position (the item's existing resume position, else 0). `POST /Sessions/Playing/Progress` is sent at the same safe position every 30 s. The real position and a positionless progress are never sent.
- 🧪 `POST /Sessions/Playing/Stopped` is sent with `Failed=true` and the safe position when the viewer leaves the item (flip, cut, exit). A stop without a position is never sent.
- 🧪 The app counts the seconds actually watched per item. At 90% or more (configurable, default 90) it calls `POST /UserPlayedItems/{itemId}`. Flipping past never counts.
- 🧪 `jellyfin-contract` tests on 12.1 and 12.2 pin the safe-position cases: user data unchanged for an unwatched item, an item with a resume point and a played item, after a failed stop and after a server auto-stop; mark-played mid-visit holds.
- 📺 The dashboard shows the TV as playing throughout a sitting, and Continue Watching is unchanged afterwards.

## F1.6 Direct-play / transcode fallback (M)

**S1.6.1** ✅ As a viewer with a weak device, I want unsupported codecs transcoded automatically so that everything plays.

✅ **Built 2026-10-21** (spec 13, [ADR-0016](adr/0016-playback-routes-and-the-media-client.md)). The player, the capability probe, the route decision and the media client are in. The 📺 matrix ran on 2026-10-22: [playback matrix](compatibility/playback-matrix.md), 24 of 25 on each Fire TV, with the two failures open as #340 and #344.

- 🧪 A device capability probe (MediaCodecList: codecs, profiles, levels, max resolution, HDR) builds a Jellyfin `DeviceProfile`. `POST /Items/{id}/PlaybackInfo` decides between direct play, direct stream, and transcode.
- 🧪 The `DeviceProfile` declares subtitle formats, both embedded and external, even with subtitles off. Without them Jellyfin refuses direct play for items carrying subtitle streams. Video the device can't decode, such as MPEG-4 Part 2 (seen in AVI), routes to transcode ([ADR-0007](adr/0007-tune-latency-spike.md)).
- 🧪 Transcode uses HLS (`master.m3u8`) from 0, with no `StartTimeTicks`; the player starts at the offset.
- 🧪 **Stream auth rule:** URLs are built from IDs, and the header is gated to the origin and base path. The token travels only in the `Authorization` header, which the media client adds only for the pinned server's origin and base path. We never use the server's `TranscodingUrl`, and no media URL we build carries the token. Any `ApiKey`/`api_key` in a playlist is stripped before the request is sent.
- 📺 ✅ A test matrix on the Insignia (and the Firestick): H.264 MP4, HEVC MKV, AV1 (expect transcode), DTS audio on H.264 sources (expect audio transcode; HEVC with DTS is a full transcode until #351), and image subtitles (expect burn-in; see Q-PLY-1).

---

# E2 — Guide and builder

## F2.1 Grid guide (M)

**S2.1.1** As a viewer, I want a grid guide showing ≥ 6 hours ahead so that I can see what's coming.

📺 **Built 2026-10-27** (spec 17, PRs [#418](https://github.com/StationBreak/stationbreaktv/pull/418), [#426](https://github.com/StationBreak/stationbreaktv/pull/426), [#439](https://github.com/StationBreak/stationbreaktv/pull/439) and [#443](https://github.com/StationBreak/stationbreaktv/pull/443)). Device checks 1, 1b and 2 are done on the Insignia and the Firestick; results are in the [ADR-0008](adr/0008-guide-grid-spike.md) addendum. On the older hardware the guide is accepted against a usable floor instead of the 20% rule (2026-10-27). Open follow-ups: [#430](https://github.com/StationBreak/stationbreaktv/issues/430) (held Up/Down is too fast, and a tune can start during the hold), [#431](https://github.com/StationBreak/stationbreaktv/issues/431) (Back out keeps the player running), [#438](https://github.com/StationBreak/stationbreaktv/issues/438) (VoiceView re-reads with the guide open), [#442](https://github.com/StationBreak/stationbreaktv/issues/442) (C2 leftovers).
- 📺 Rows are channels, columns are time in 30-minute slots, with a "now" line. Program cells are proportional to duration.
- 📺 D-pad navigation: Up/Down moves between channels, Left/Right moves through programs in time. Focus never gets lost or jumps to an off-screen cell.
- 📺 Select on a program airing now changes to that channel and closes the guide. Select on the channel already playing closes the guide without retuning. Select on a later program opens Info. Reminders are out of v1 (Q-UX-3). Menu pressed during a guide tune wins: the pending tune is superseded. A Select before the first channel is on air is dropped.
- 🧪 Guide data comes from materialised ScheduleBlocks (S1.1.4), never computed on the UI thread.
- 🧪 Built with app-owned scrolling: one shared horizontal offset applied to all rows, and the guide model owns focus and moves it on each key press. It uses a plain `Column` of rows with a custom layout per row, not standard Compose lists ([ADR-0008](adr/0008-guide-grid-spike.md), addendum). Laid out left-to-right only for now.

**S2.1.2** As a viewer, I want to open the guide from live TV without losing the picture so that it feels like cable.

📺 **Built 2026-10-27** (spec 17, PRs [#426](https://github.com/StationBreak/stationbreaktv/pull/426), [#439](https://github.com/StationBreak/stationbreaktv/pull/439) and [#443](https://github.com/StationBreak/stationbreaktv/pull/443)); device checks 1, 1b and 2 done on both TVs. Open: [#445](https://github.com/StationBreak/stationbreaktv/issues/445) (Info overlaps the corner picture on smaller screens).
- 📺 Back from the full-screen show opens the guide over the live video. The picture continues in a corner window at the top right (Q-UX-4), with the details panel top-left and the grid below. It is the same video surface as full screen, so there is no re-tune. Back in the guide leaves live TV (Q-UX-2).
- 📺 The Guide key reaches the app on the Fire TV Stick but not on the older Fire TV, where Fire OS opens Amazon's own Live TV guide. Back and Select are the paths (A-DEV-2).

## F2.2 Now/next banner & info overlay (M)

**S2.2.1** As a viewer, I want a banner on every tune showing channel, now, and next so that I know what I'm watching.

📺 **Built 2026-10-26** (spec 17, PR [#419](https://github.com/StationBreak/stationbreaktv/pull/419)); device check done. Channel names are text only (no logos) in v1.
- 📺 The banner appears for ~4 s on tune or on an info key press. It shows channel number and name (text only, no logo), the current title (with S/E for episodes), a progress bar, and the next title + start time.

**S2.2.2** As a viewer, I want an info overlay with the description so that I can decide whether to stay.

📺 **Built 2026-10-26** (spec 17, PRs [#418](https://github.com/StationBreak/stationbreaktv/pull/418), [#419](https://github.com/StationBreak/stationbreaktv/pull/419) and [#426](https://github.com/StationBreak/stationbreaktv/pull/426)); device check done. In the guide, Select on a later program opens Info, and Info shows the description.
- 📺 Select (or the Info key) during playback opens the overview, year, genres, and runtime.

## F2.3 Custom channel builder (M)

**S2.3.1** As a user, I want to build a channel by picking sources so that I can make "Saturday Morning Cartoons".
- 📺 A step flow: name → sources (libraries, genres, collections, tags, specific series, year range) → exclusions → ordering mode → block length → preview → save. Every step is fully D-pad operable.
- 🧪 The builder produces rules JSON valid against the `core:rules` schema.

**S2.3.2** As a user, I want a live preview so that I can see the channel before saving.
- 📺 The preview shows the item count, total runtime, and the next ~6 hours of lineup from the real scheduler.

**S2.3.3** As a user, I want to edit and delete custom channels and reseed shuffle so that I can fix a lineup I don't like.
- 🧪 Editing the rules or seed regenerates ScheduleBlocks from the current epoch onward.
- 🧪 Seed lifecycle ([rules format](rules-format.md#seed)): a **new custom channel** gets 64 random bits from the platform's secure random source; **reseed** and **duplicate** give a fresh random seed; **import** keeps the seed of the file byte for byte; **editing** an automatic channel makes it a custom channel and keeps its seed; changing the name or any field other than `seed` never changes the seed.

## F2.4 Channel management (M)

**S2.4.1** As a user, I want to renumber, hide, and favourite channels so that the lineup is mine.
- 🧪 Channel numbers are unique. Hidden channels are skipped by flipping and by the guide.
- 🧪 A channel hidden automatically because it is empty ([#299](https://github.com/StationBreak/stationbreaktv/issues/299)) shows its reason here and needs no action to come back. A user's hide stays even when the channel has items.
- 📺 Starter channels can be hidden. A channel the user hid or removed is never switched back on by a library change (S1.2.1; Q-UX-1). The shelf of channels not switched on is managed here.
- 📺 A "forget this account's channels" action removes the account's lineup (`LineupService.forget` exists; there is no screen yet). Sign-out, session expiry and an account switch never do.

## F2.5 Large-library performance (M)

**S2.5.1** As a user with 10k+ items and 50+ channels, I want the guide to stay smooth so that the app feels premium on a budget stick.
- 🧪 A macrobenchmark on the Insignia (or the closest emulator profile) runs: guide open → first frame < 1.0 s, and a vertical scroll over 50 channels with no scenario more than **20% worse** than the SP-2 baseline on p90 or p99 frame time, or on missed vsyncs. The baseline scenarios are stepping and held-key scrolling, vertical, horizontal and mixed ([ADR-0008](adr/0008-guide-grid-spike.md)). It's a starting point that can be tightened later. On the older Fire TV the 20% rule is replaced by a usable floor: open under 1 s, no hangs, no focus loss (owner decision 2026-10-27, [ADR-0008](adr/0008-guide-grid-spike.md) addendum). Janky % isn't used: SP-2 showed that stepping at a steady ~30 fps, which looks smooth, counts as 85–100% janky on the Insignia ([ADR-0008](adr/0008-guide-grid-spike.md)).
- 🧪 A Baseline Profile covering the guide is generated in CI and shipped in the release build. It's required: compilation state was the largest factor in SP-2.
- 🧪 Room queries for the guide window are indexed (`channelId, startTime`) and verified with `EXPLAIN QUERY PLAN` in a test.

---

# E3 — Polish and personality

## F3.1 Filler & bumpers (S)

**S3.1.1** As a nostalgia fan, I want bumpers, commercials, or shorts between programs from a chosen library so that it feels like a real station.
- 🧪 A per-channel filler source (library/collection/tag) and mode (`mode` in the rules: `none`, `padToGrid` (fill to the next :00/:30; it needs `alignToGrid: true`, which the app's own channels do not set since 2026-10-25), and `betweenEveryN`; reserved in rules format 1, so a v1 reader treats any mode but `none` as unsupported).
- 🧪 Filler packing is deterministic (it uses hash-keyed ordering, as the scheduler does; there is no PRNG) and covered by golden vectors.
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
- 📺 Welcome → server (with examples of URL formats) → sign in → libraries → "Here are your channels" → first tune.
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
- 🧪 Supply chain: Gradle `verification-metadata.xml` + wrapper validation, GitHub Actions pinned by commit SHA, Renovate for updates, and the signing secret in a protected environment with required reviewers, never available to PR runs.
- 🧪 CI fails a release if the APK is debuggable, or if `apksigner verify --print-certs` shows the debug certificate ([ADR-0011](adr/0011-debug-build-signing.md)).
- 👁 OpenSSF Scorecard runs on the repo, non-blocking.
- 👁 The release publishes the APK SHA-256, the signing-cert fingerprint, and a build-provenance attestation. An offline backup of the `github` signing key exists, because losing it breaks upgrades.

## F4.2 Amazon Appstore listing (M)
- 👁 Listing assets (icon, banner 1280×720, screenshots), privacy policy URL, and content rating. 📺 The app passes Amazon's Fire TV certification (D-pad only, no touch requirement). 👁 Submitted with **no DRM** (ADR-0003).

## F4.3 Google Play listing (M)
- 🧪 The `play` flavor is added here; Phase 0 creates only `amazon` and `github` ([MODULES.md](MODULES.md#build-flavors)).
- 👁 TV-specific requirements: leanback banner, `android.software.leanback` declared, `touchscreen required=false`, TV screenshots, and the Data Safety form (no data collected by default).
- 👁 Distribution is limited to TV form factors in Play Console. With leanback and touchscreen both optional, Play would otherwise offer the app to phones (Granny (Architecture), PR #136 review).

## F4.4 GitHub Releases (M)
- 🧪 The `github` flavor APK + SHA-256 checksums are attached to the release. 👁 Downloader-app instructions (Fire TV "Downloader" short code) are in the docs.

## F4.5 Pro unlock (M)
**S4.5.1** As a supporter, I want a one-time Pro purchase so that I can support the project.
- 🧪 The `ProEntitlement` port has implementations for Amazon IAP and Play Billing. Purchase, restore, and refund revocation are covered by fakes.
- 👁 The Pro feature set is defined (Q-BIZ-2). The `github` flavor behaviour is decided (Q-BIZ-1).

## F4.6 Docs site (M)
- 👁 StationBreak.tv is a static site (e.g. Astro/MkDocs, deployed from this repo or `stationbreak/site`) covering install, server setup, reverse-proxy notes, self-signed certs, channel building, FAQ, and privacy policy.
- 👁 The privacy policy states what the channel lineup holds and how it is removed ([#308](https://github.com/StationBreak/stationbreaktv/issues/308)). By design, sign-out, session expiry and an account switch never delete a household's lineup; only an explicit user action does. The lineup is the per-account file plus up to three set-aside `.unreadable.<timestamp>` copies. It holds channel names and rules, with no credentials and no media titles, and it is app-private and excluded from backup. Today a user removes it by clearing app data; a "forget this account's channels" action is planned (S2.4.1).

---

# E5 — Expansion (outline; re-plan after E4)

- **F5.1 Channel import/export / sharing between accounts**: **deferred.** Every option explored (short links, codes, server-side storage) needs static data hosted somewhere, which is a bigger problem than the core product. Re-plan after the companion service or a plugin approach is decided. Until then, custom channels live on the device that built them. The portable rules format keeps this cheap to add later. Context: sharing is between accounts on the *same* media server, as a copy with a fresh seed (same recipe, not same lineup).
- **F5.2 Companion sync server**: reuses `core:*` and `data:jellyfin` (JVM). It syncs channels/settings, not schedules.
- **F5.3 XMLTV/M3U `ScheduleSource`**: reads Tunarr/ErsatzTV output. The guide and player stay unchanged.
- **F5.4 Roku** (separate repo): consumes the rules format plus the golden vectors.
- **F5.5 Apple TV**: either Swift re-implementation against the golden vectors, or the `core:*` modules converted to KMP (see Q-ARCH-1).
- **F5.6 Emby adapter → F5.7 Plex adapter**: new `MediaServer` implementations.
- **F5.8 Profiles & parental controls**: per-profile channel sets, a rating ceiling, and a PIN.
