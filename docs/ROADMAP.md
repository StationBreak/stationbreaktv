# StationBreak — Roadmap, Dependencies & Risks

Phases follow [SPEC.md](SPEC.md). **No phase starts until the previous gate passes**
([EPICS.md](EPICS.md)). Within a phase, features run in the dependency order shown below.
No calendar estimates are given; this is a solo/evenings project, and gates matter more than dates.

## Design principles

1. **The owner's home is the floor.** The bare minimum for StationBreak is that it works in the owner's
   home: the reference Fire TV (Insignia, Fire OS 6.7.0.8) against the owner's Jellyfin 12.x server
   behind a reverse proxy. If it doesn't work there, it doesn't work. The aim is to work in many more
   places, but that setup **must** work. Gates are tested there first.
2. **Platform conventions come first.** Every button's job follows industry standards and the way people
   already use a TV remote and a streaming app. A UI that fights those conventions is the most
   frustrating kind. Back, for example, goes up a level in the app or exits, depending on context. The
   exact control mapping is worked out during UX design, not fixed here.
3. **When unsure, ask what cable did.** Where conventions don't settle how a UI element should look or a
   flow should go, cable TV is the tiebreaker. It has already answered several questions:
   scrambled-but-visible locked channels, premium content airing late, and breaks that interrupt every
   channel at once. (The :00/:30 grid was one too, until 2026-10-25: without filler it left dead air, so
   programmes now start back to back by default.) Cable never overrides principle 2.

## Phase overview

```
Phase 0  Foundations ─────► Gate: connects by URL, lists libraries on Insignia
   │
Phase 1  Live core ───────► Gate: 30 flips; Firestick < 2 s, Insignia median < 4 s, no hangs
   │
Phase 2  Guide + builder ─► Gate: 14 days daily use replacing NostalgiaTV
   │
Phase 3  Polish ──────────► Gate: 5–10 r/jellyfin testers succeed unassisted
   │
Phase 4  Public release ──► Gate (proposed): live in both stores + GitHub, Pro works
   │
Phase 5  Expansion ───────► Re-plan
```

## Phase 0 — Foundations

| Order | Feature | Depends on |
| --- | --- | --- |
| 1 | F0.1 Repo & CI | Owner creates the `StationBreak/stationbreaktv` GitHub repo ✅ |
| 2 | F0.2 Module skeleton | F0.1 |
| 3 | F0.7 Rules format v1 (doc + types) | F0.2. Can run in parallel with F0.3 |
| 3 | F0.3 Jellyfin adapter (starts with the jellyfin-sdk-kotlin fitness spike, SP-4) | F0.2 |
| 4 | F0.4 Secure storage | F0.2 |
| 5 | F0.5 Server setup UI | F0.3, F0.4 |
| 6 | F0.6 Library sync | F0.3, F0.5 |

**CI first (decided 2026-10-10):** F0.1 lands before any app code. Every APK that goes on a device comes from
CI, starting with the F0.2 hello-world. Nothing is built by hand.

**Spikes in Phase 0** (time-boxed; record the results as ADRs in `docs/adr/`):
- **SP-1 Tune-latency spike.** ✅ **Done 2026-10-09** ([ADR-0007](adr/0007-tune-latency-spike.md)). On the
  Insignia, the median time to playback starting is about 1.0 s with a warm player and about 1.6 s with a cold
  one, but 2–5 of 18 valid library tunes per run go over 2 s. Cold-player misses are mostly a gap between the
  first frame and playback starting; warm-player misses are already slow at first frame. The network route
  isn't the bottleneck. The gate now measures "playing", with first frame tracked.
- **SP-2 Compose for TV on the Insignia.** ✅ **Done 2026-10-10** ([ADR-0008](adr/0008-guide-grid-spike.md)). A
  50-channel × 6-hour guide from standard Compose lists, with app-owned horizontal sync, looks smooth to the owner
  on the Insignia at normal pace (~30 fps) and when holding Down, with no focus anomalies. No custom grid is
  needed. A baseline profile is required. De-risks R-PERF-2.
- **SP-3 Keystore on Fire OS.** ✅ **Done 2026-10-10** ([ADR-0009](adr/0009-keystore-spike.md)). On the Insignia the AES-256-GCM key
  reports itself as hardware-backed and survives a restart, an app update and 3 reboots with no interaction. Every
  injected fault (clearing app data, deleting the key, a flipped or truncated blob) fails safely, and a Firestick 4K
  matched. Fail-closed handling now covers any failure, wiping only on definitive ones.
- **SP-4 jellyfin-sdk-kotlin fitness.** ✅ **Done 2026-10-06** ([ADR-0006](adr/0006-jellyfin-12-only.md)). The SDK passes on 12.x: injected OkHttp client (pinning), subpath base URLs, and distinguishable TLS and connection errors. It fails on 10.11, so StationBreak supports **Jellyfin 12.0+ only**.
- **SP-5 Jellyfin change signals.** ✅ Done 2026-10-18 ([ADR-0015](adr/0015-jellyfin-change-signals-spike.md)). Series and BoxSet edits are signalled by `MinDateLastSaved` on 12.1 and 12.2, but a deleted collection member is not: it silently leaves the member list, so the sync drops tombstoned items from their collections.

## Phase 1 — Live core

| Order | Feature | Depends on |
| --- | --- | --- |
| 1 | F1.1 Scheduler (+ golden vectors) | F0.7 |
| 1 | F1.6 Capability probe + PlaybackInfo | F0.3 (parallel with F1.1) |
| 2 | F1.2 Channel engine & defaults | F1.1, F0.6 |
| 3 | F1.3 Offset playback | F1.1, F1.6 |
| 4 | F1.4 Flipping & recall (+ latency metric) | F1.3 |
| 4 | F1.5 Playback reporting | F1.3 |

## Phase 2 — Guide and builder

| Order | Feature | Depends on |
| --- | --- | --- |
| 1 | F2.2 Now/next banner | F1.4 |
| 1 | F2.1 Grid guide | F1.1 (materialised blocks), SP-2 result |
| 2 | F2.3 Channel builder | F1.2, F0.7 |
| 2 | F2.4 Channel management | F1.2 |
| 3 | F2.5 Perf hardening + baseline profile | F2.1 |
| — | **14-day dogfood** | all of the above |

## Phase 3 — Polish and personality

F3.1 Filler (depends on the F1.1 scheduler) · F3.2 Retro / F3.3 Themes (depend on the F2.1 guide UI) ·
F3.4 Onboarding (reworks F0.5) · F3.5 Watch-state (depends on F1.5) · F3.6 Diagnostics ·
F3.7 Beta program (last; it needs everything else).

## Phase 4 — Public release

F4.1 Release pipeline → (F4.2, F4.3, F4.4 in parallel) · F4.5 Pro unlock (needs Q-BIZ answers early;
store billing integration and review take time) · F4.6 Docs site (can start any time after Phase 2).

## Phase 5 — Expansion

F5.1 Import/export (deferred; needs a hosting/storage decision) → F5.2 Sync server → F5.3 XMLTV → F5.4 Roku → F5.5 Apple TV.
F5.6 Emby and F5.7 Plex can slot in anywhere after F5.1; they only need a new `MediaServer` implementation.

---

## Risks & unknowns

Severity: 🔴 high · 🟠 medium · 🟢 low.

| ID | Risk | Sev | Mitigation |
| --- | --- | --- | --- |
| R-PERF-1 | **< 2 s mid-program tune-in on a budget Fire TV.** Seeking into an MKV without cues, slow server disk, remote (WAN) servers, or a transcode start can each blow the budget. The spec's gate also implies a LAN server. The 2026-10-24 gate holds the 2 s bar on the Firestick 4K only; the Insignia has a usable-floor bar (EPICS.md). | 🔴 | SP-1 spike in Phase 0; a single reused player; pre-resolve adjacent channels; close the first-frame→playing gap (ADR-0007); verify seeks landed and fall back to transcode; the gate is measured on the home network (A-NET-1). |
| R-PERF-2 | Compose for TV performance on low-end hardware (≈1–1.5 GB RAM, older Mali GPU) for a 6h × 50-channel grid. | 🟠 | SP-2 passed with standard Compose lists ([ADR-0008](adr/0008-guide-grid-spike.md)); Baseline Profiles (decisive in SP-2); R8; watch fast horizontal sweeps. |
| R-DET-1 | **Cross-device determinism.** It depends on both devices having the same snapshot (sync lag), the same epoch math (timezone/DST), and the same hash-keyed ordering bit-for-bit, including future Swift/BrightScript ports. | 🔴 | Snapshot = items with `dateCreated < epochStart − grace`; tombstones; UTC origin + fixed offset in rules; SplitMix64-derived seeds; ID-byte canonical sort; hard epoch cuts; golden vectors (incl. `formatVersion`) as the cross-platform contract. |
| R-DET-2 | Per-user library permissions and watch-state make lineups account-dependent. | 🟢 | **Resolved by ADR-0002:** same account → same lineup; channel rules set content limits; hash-keyed ordering keeps reduced lineups recognisably similar. |
| R-DET-4 | Custom channels are per device until sharing is designed, so a custom channel doesn't appear at Grandma's. Starter lineups are chosen on each TV and can differ between TVs set up at different times (spec 12); a given channel's rules and seed schedule identically everywhere. | 🟢 | Accepted for v1. Channel sharing is deferred (F5.1). |
| R-DET-3 | Wrong `RunTimeTicks` metadata makes playback drift from the schedule. | 🟠 | Re-sync at item boundaries; a diagnostics list of items whose real duration differs from metadata by > 5%. |
| R-PLAT-1 | **API 25 floor vs library minSdk creep.** The reference device (Fire OS 6.7.0.8) is *exactly* API 25, so any core library (Compose, Media3, Room, Hilt) raising minSdk above 25 strands the gate device. | 🔴 | CI fails on any dependency whose minSdk is above 25. The manifest merger does this for Android libraries (AARs), because `tools:overrideLibrary` is banned (see MODULES.md); plain JARs are caught by the API 25 emulator job; Renovate PRs that raise it need manual review; keep API 25 in the CI emulator matrix. |
| R-SEC-5 | **Let's Encrypt cross-sign retirement.** Current Let's Encrypt chains reach ISRG Root X1 only via the transitional X2←X1 cross-sign. Old Fire OS never gets new roots, so retiring it breaks API 25 devices. | 🟠 | Bundle ISRG Root X2 and Root YE as extra trust anchors (S0.3.5). |
| R-PLAT-2 | AGSL/`RenderEffect` shaders need API 33. Retro CRT overlay on API 25–32 needs a fallback. | 🟢 | A GL surface overlay or a static scanline bitmap; degrade gracefully. |
| R-PLAT-3 | Fire TV has no Google Play Services. Any GMS dependency (e.g. Firebase Crashlytics) breaks the `amazon` flavor and conflicts with the FOSS `github` flavor. | 🟠 | No GMS anywhere; a CI check on dependency coordinates. |
| R-SEC-1 | Self-signed certificate handling done wrong (trust-all, or `CertificatePinner` that never fires, or Media3 bypassing the pinned client) exposes tokens to MITM. Also https→http downgrade on TLS error. | 🔴 | Per-server SPKI pin in a custom TrustManager shared by every HTTP stack; downgrade only on connection refused; tests (S0.3.1, S0.5.2). |
| R-SEC-2 | Keystore bugs on some budget devices/firmware. | 🟢 reference device · 🟡 elsewhere | SP-3 passed on the Insignia and a Firestick 4K ([ADR-0009](adr/0009-keystore-spike.md)); other firmware and Fire OS updates are untested. F0.4 built the store with the fail-closed rules and per-fault wipe scopes ([ADR-0009 amendment](adr/0009-keystore-spike.md)); the emulator suite passes, and the reference-device run is recorded on the F0.4 PR. Any failure fails closed to re-login, never plaintext, so the impact is availability, not exposure. |
| R-SEC-3 | The token is embedded in stream URLs (`api_key=`) and could leak via logs/crash reports. | 🟠 | Hard rule: header-only auth via an origin-scoped interceptor; strip `ApiKey` from `TranscodingUrl`; case-insensitive redaction; crash reports opt-in only. |
| R-SEC-4 | Supply chain / signing-key loss. | 🟠 | Dependency verification metadata, SHA-pinned Actions, protected signing environment, provenance attestation, offline key backup (F4.1). |
| R-JF-1 | Jellyfin API drift between **12.x minors** (SP-4 showed new required model fields can break decoding). | 🟡 | 12.0+ only (ADR-0006); CI contract tests against the two newest 12.x minors, never dropping the owner's (S0.3.6); track SDK releases with Renovate. |
| R-JF-2 | 10k+ item sync time/memory on a 1 GB device; incremental sync semantics (deletions). | 🟠 | Paging, streaming JSON decode, explicit `Fields`, ID reconciliation. |
| R-BIZ-1 | Store policy vs GPL: rebuilt forks with Pro unlocked are legal under GPL. Amazon/Play may also flag duplicate listings by forks. | 🟢 | Accept it (the spec says Pro code stays public); trademark the name/logo instead of the code. |
| R-BIZ-2 | Amazon Appstore's future on non-Fire OS (Vega OS transition on newer Fire TV devices) could affect where an Android app can be installed going forward. | 🟠 | Verify Fire TV OS roadmap status before Phase 4; sideload path remains. Flagged as an **unknown** for the owner to track. |
| R-SCOPE-1 | Solo maintainer; Phase 5 is very broad (server + 2 platforms + 2 adapters). | 🟠 | Phase 5 is re-planned after Phase 4; port interfaces now so contributors can pick up adapters. |

## Verification of this plan

- Each epic gate is a checklist in [EPICS.md](EPICS.md). Gates are recorded as signed-off checkboxes
  in a `docs/gates/phase-N.md` file (with evidence: screenshots, latency logs, dogfood log).
- Spikes SP-1..3 each produce an ADR before Phase 1 work starts.

## Rollback

These are documentation-only changes. Reverting means deleting `docs/` files (`EPICS.md`, `BACKLOG.md`,
`ROADMAP.md`, `MODULES.md`, `ASSUMPTIONS.md`). `SPEC.md` is the owner's original text and stays.
