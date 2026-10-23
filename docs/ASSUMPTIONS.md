# StationBreak — Assumptions & Open Questions

These need owner review **before Phase 0 starts**. Questions marked ⛔ block Phase 0. The others
can be answered later, by the phase shown.

## Assumptions made

| ID | Assumption | Where it matters |
| --- | --- | --- |
| A-DEV-1 | ✅ **Confirmed:** the reference Insignia runs **Fire OS 6.7.0.8 (Android 7.1, API 25)**, exactly the minSdk. Owner's principle: *my needs are the floor*, so this device defines minimum support. Model AFTJMST12, about 1.5 GB RAM, hardware-backed Keystore (ADR-0009). Codec capabilities are captured by the F1.6 probe. | All gates; minSdk |
| A-NET-1 | The Jellyfin server for the gates is on the same LAN (or a fast reverse proxy) as the Insignia. | Phase 1 < 2 s gate |
| A-ARCH-1 | ✅ Confirmed by ADR-0005/0006: `jellyfin-sdk-kotlin` on its OkHttp client. The Jellyfin adapter stays pure JVM and is reusable by the companion server. | MODULES.md |
| A-ARCH-2 | Channels are stored as rules JSON (the portable source of truth) inside one lineup file per account in `data:settings` (spec 12); settings live in **DataStore**. **Room** holds only the library cache; ScheduleBlocks are built in memory and not yet persisted. | F0.6, F1.1 |
| A-ARCH-3 | The scheduler uses **epochs** (default 24 h) anchored to a fixed UTC origin plus an optional fixed offset in the rules (never device TZ/DST). The snapshot = items created before `epochStart − grace`. This is my design to make "determinism" survive library changes and sync lag; the spec doesn't prescribe it. Cross-device agreement is *eventual*. | F1.1 |
| A-ARCH-4 | Ordering is hand-specified rather than taken from platform RNGs, so Roku/Apple TV can match bit-for-bit: every order comes from a hash key (`splitmix64` and `fnv1a64`) and a total sort, with no random-number stream and no rejection sampling ([rules format](rules-format.md#no-random-stream-no-bounded-integers), spec 08). | F0.7, F1.1 |
| A-ARCH-5 | `core:scheduler` depends only on `core:model`, not on the engine (Granny's veto, accepted). | MODULES.md |
| A-SEC-1 | "Keystore-backed storage" = a Keystore AES-GCM key wrapping values in DataStore. `androidx.security:security-crypto` (EncryptedSharedPreferences) is avoided because it's deprecated. | F0.4 |
| A-SEC-2 | Self-signed opt-in = per-server leaf-SPKI pinning via a custom `X509TrustManager` (TOFU), shared by API and Media3 clients. Not "trust all", and not `CertificatePinner`. | F0.5 |
| A-SEC-3 | Tokens travel only in headers, never in media URLs. | S1.6.1 |
| A-REL-1 | Three build flavors: `amazon`, `play`, `github`. The `github` flavor has no proprietary SDKs. Phase 0 creates only `amazon` and `github`; `play` arrives at F4.3. | MODULES.md, F4 |
| A-SCOPE-1 | Phases 4 and 5 have no gates in the spec, so I proposed them. Phase 5 is only outlined. | EPICS.md |
| A-SCOPE-2 | The "should haves" (filler, retro, watch-state) are scheduled in Phase 3 per the roadmap, not in the MVP gate (Phase 2). | ROADMAP.md |
| A-REPO-1 | ✅ **Confirmed:** the repo is `github.com/StationBreak/stationbreaktv` (created 2026-10-04, private; see Q-REPO-1). | F0.1 |
| A-LIC-1 | All dependencies must be GPL-3.0-compatible (Apache-2.0, MIT, BSD, LGPL OK). Media3's optional FFmpeg extension is compatible if used. | S0.1.2 |

## Questions for the owner

### ⛔ Blocking Phase 0 (all resolved)

- ~~**Q-DEV-1**~~ ✅ **Resolved 2026-10-04:** Fire OS 6.7.0.8 (API 25), and minSdk 25 stays. Follow-up done 2026-10-09: model AFTJMST12, Android 7.1.2, about 1.5 GB RAM, of which about 300 MB is reserved for the video decoder and GPU.
- ~~**Q-LIC-1**~~ ✅ **Resolved 2026-10-04 ([ADR-0003](adr/0003-licensing-and-contributions.md)):** `GPL-3.0-or-later`, DCO, and an app-store additional permission from day one.
- ~~**Q-ARCH-1**~~ ✅ **Resolved 2026-10-04 ([ADR-0004](adr/0004-kmp-shared-core.md)):** KMP for the shared core, with a `jvm()` target and a `linuxX64()` canary. tvOS is added in Phase 5.
- ~~**Q-ARCH-2**~~ ✅ **Resolved 2026-10-04 ([ADR-0005](adr/0005-jellyfin-client.md)):** the SDK's OkHttp client (corrected from "Ktor on OkHttp" by SP-4). No Retrofit.
- ~~**Q-JF-6**~~ ✅ **Resolved 2026-10-04 (ADR-0005):** use `jellyfin-sdk-kotlin`, gated by spike SP-4. If SP-4 fails, the decision returns to the owner.
- ~~**Q-JF-2**~~ ✅ **Resolved 2026-10-04 ([ADR-0001](adr/0001-client-authentication.md)).** The TV client uses user login + Quick Connect. Server-side components (the Phase 5 container) use a non-admin service user's token. Dashboard API keys are not used.
- ~~**Q-REPO-1**~~ ✅ **Resolved 2026-10-04:** `StationBreak/stationbreaktv` was created **private**. It goes public only once there's a working Phase 0 skeleton, with branch protection (required CI + DCO checks) in place **before** that. Signed commits are required, and the flip-day security checks in S0.1.4 are turned on. Spike branches (`spike/*`) are deleted before the visibility flip, because their history can hold home-network details. The docs contain no details of the owner's home setup. Epics, features, stories, spikes, and open decisions are tracked as GitHub issues (sub-issue hierarchy, milestones = phases).

### Before/during Phase 1

- ~~**Q-SYNC-1**~~ **Withdrawn:** channel sharing is deferred (F5.1).
- ~~**Q-RULES-1**~~ ✅ **Resolved 2026-10-06 (starting point):** v1 understands **US TV + MPAA** ratings. Under a `maxRating` ceiling, unrated items and items rated in a system v1 doesn't understand (BBFC, FSK, …) are **blocked**: the filter fails closed ("an empty channel rather than a four-year-old seeing Psycho", SPEC Icebox). Channels without a ceiling play everything. An allowlist and other regional systems stay in the icebox (#131).

- ~~**Q-JF-1**~~ ✅ **Resolved 2026-10-06 ([ADR-0006](adr/0006-jellyfin-12-only.md)):** support **Jellyfin 12.0+ only**. The originally chosen 10.x + 12.x support failed spike SP-4 (the SDK can't decode 10.11 items). CI tests the oldest supported and latest 12.x (S0.3.6).
- ~~**Q-JF-3**~~ ✅ **Resolved: Quick Connect is in Phase 0** (ADR-0001).
- **Q-JF-4** How quickly should deletions on the server disappear from channels? (Next periodic reconciliation, e.g. 6 h, vs immediately via the Jellyfin WebSocket.)
- **Q-JF-5** Should tuning in mid-episode and watching to the end **mark it played** on Jellyfin? Should flipping past it count as anything? Default proposal: mark played only if ≥ 90% was actually watched, and make it configurable.
- ~~**Q-SCH-1**~~ ✅ **Resolved by spec 07 (2026-10-16):** the broadcast day is a per-channel `start`, default `06:00`, at a fixed UTC offset in the rules (`timing.broadcastDay`), never the device time zone. During daylight saving the wall-clock start shifts by an hour, on purpose, so that every device agrees (see the rules format, Timing).
- ~~**Q-SCH-2**~~ ✅ **Resolved 2026-10-06:** programs **align to :00/:30 grid slots**, as cable did. `alignToGrid` defaults to on. Until F3.1 filler exists, gaps are shown as a station ID slate (a non-item `ScheduleBlock`, S1.1.4).
- ~~**Q-SCH-3**~~ ✅ **Resolved 2026-10-23 (spec 14; [ADR-0017](adr/0017-live-channel-playback.md), 1 and 2):** the schedule wins. A show that ends early is followed by the ident card until its block ends, and one that runs long is cut at the block boundary. The one exception is a per-sitting drift after a dead item: the channel moves on from the dead block and runs ahead on that TV until any retune (a channel change, reopening the app, waking the TV) works the position out from the clock again.
- **Q-GATE-1** For the Phase 1 "< 2 s" gate: LAN only, or also via your reverse proxy over WAN? Direct play only, or must transcoded channels also hit 2 s? (Transcode start is usually slower.) *Proposed: home network only, direct-play channels only.* **Partly resolved 2026-10-09 (after SP-1):** the gate times two moments, **first frame** and **playing** (picture moving). Playing must happen within 2 s; first frame is tracked with no hard target ([ADR-0007](adr/0007-tune-latency-spike.md)). The network and direct-play questions are still open.
- ~~**Q-PLY-1**~~ ✅ **Resolved 2026-10-20 (owner decision, spec 13; [ADR-0016](adr/0016-playback-routes-and-the-media-client.md), 6 and 7):** image subtitles such as PGS are **burned in by the server**, which forces a transcode when one is selected. Default audio and subtitle tracks follow the user's own Jellyfin preferences, including their "play default audio track" setting; the client accepts what the server resolves. Text subtitles are delivered in the stream or as WebVTT.

### Before/during Phase 2–3

- ~~**Q-UX-1**~~ ✅ **Resolved 2026-10-19 ([#112](https://github.com/StationBreak/stationbreaktv/issues/112), spec 12):** a themed starter catalog replaces per-library, per-genre and per-collection automatic channels. The best-filling recipes are switched on, up to a default cap of 50; the rest wait on a "More channels" shelf. A recipe fills when its pool holds at least 12 hours of runtime. A channel the user hid or removed is never switched back on by the app. Opt-in recipes (After Hours) are never switched on automatically.
- ~~**Q-UX-2**~~ ✅ **Resolved 2026-10-06:** Back follows the platform convention: it goes **up a level or exits, depending on context** (closes an open overlay or guide first, then leaves live TV). It is **not** the previous-channel key. Previous channel is a "Last" action in the now/next banner, with Menu (☰) as a one-press shortcut (S1.4.3).
- **Q-UX-3** Reminders ("notify me when X starts") are in or out of v1?
- **Q-UX-4** Guide over live video: translucent overlay or PIP-in-corner?
- **Q-UX-5** Phone-assisted setup (QR → local web form for the server URL/password) is in or out? It's a big win for typing URLs with a remote.
- **Q-PRIV-1** For opt-in crash reporting, which tool? Self-hosted Sentry/GlitchTip, ACRA to your own endpoint, or none (manual diagnostics export only)?
- **Q-BRAND-1** Logo/visual branding is still open. Who designs it, and is it needed by the Phase 3 beta?

### Before Phase 4

- **Q-BIZ-1** Should the GitHub APK have Pro unlocked (it's GPL anyway), be honor-system, or have no Pro features?
- **Q-BIZ-2** What *is* Pro? Candidates: unlimited custom channels, retro mode/themes, filler, multiple servers, and (later) sync. Free must still fully replace NostalgiaTV.
- **Q-BIZ-3** Pricing, and whether there are regional prices.
- **Q-REL-1** Which Google Play account owns the listing (personal vs org)? New personal Play accounts have closed-testing requirements before production access, and this affects Phase 4 timing.

## Next steps (once approved)

1. Answers to the ⛔ questions are folded into `SPEC.md`/`MODULES.md` as ADRs.
2. Ask before creating GitHub issues: one issue per story, labelled by epic/feature/priority, with milestones = phases.
3. Phase 0 scaffolding (F0.1, F0.2) starts. It can go through `/SDD-1-generate-spec` per feature if you want the SDD flow.
