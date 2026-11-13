# StationBreak — Epics

One epic per roadmap phase. Each epic's **gate is its acceptance criteria**. An epic isn't done until
its gate passes on the reference device. Later work has started while earlier gates were open or waived (see
[ROADMAP.md](ROADMAP.md)): E2 began with the E1 Firestick bar waived, and the free Fire listing work
([#752](https://github.com/StationBreak/stationbreaktv/issues/752), label `release:fire-v1`) runs alongside the
E2 dogfood.

Reference device: **Insignia Fire TV**. See [ASSUMPTIONS.md](ASSUMPTIONS.md) A-DEV-1 for the
exact model and Fire OS version. Features and stories: [BACKLOG.md](BACKLOG.md). Sequencing and risks:
[ROADMAP.md](ROADMAP.md).

---

## E0 — Foundations (Phase 0)

**Goal:** a buildable, tested, CI-gated repo with the module skeleton, a working Jellyfin adapter
that connects by URL or IP:port, secure credential storage, and a local library cache.

**Features:** F0.1 Repo & CI · F0.2 Module skeleton · F0.3 Jellyfin adapter · F0.4 Secure credential storage ·
F0.5 Server setup & connection test · F0.6 Library sync & cache · F0.7 Channel rules format v1 (spec only)

**Gate (acceptance criteria):**
- [x] On the Insignia Fire TV, entering the owner's real reverse-proxied HTTPS URL connects and lists
      **all** libraries visible to the user.
- [x] The same works with a bare `IP:port` on the LAN (accepted on automated evidence, owner 2026-11-03).
- [x] Subpath base URLs (e.g. `https://media.example.com/jellyfin`) are proven by automated tests (S0.3.1).
- [x] Adapter contract tests pass in CI against the **oldest supported and latest Jellyfin 12.x** containers (S0.3.6, ADR-0006).
- [x] The app was installed on the Insignia by sideloading a CI-built APK, not by a local build.
- [x] CI is green on `main`: build, unit tests, lint, detekt, license header check.

Evidence and sign-off: [gates/phase-0.md](gates/phase-0.md).

---

## E1 — Live core (Phase 1)

**Goal:** turn the cached library into deterministic, always-on channels that the user can tune into
mid-program and flip between with the remote.

**Features:** F1.1 Deterministic scheduler · F1.2 Channel engine & default channels · F1.3 Offset playback ·
F1.4 Channel flipping & last-channel recall · F1.5 Playback reporting · F1.6 Direct-play / transcode fallback

**Gate (acceptance criteria):**
- [ ] **A two-tier flip gate** (owner decision 2026-10-24, Q-GATE-1). Each flip is timed at two moments:
      - **first frame**: the correct mid-program picture appears. Tracked and reported, with no hard target ([ADR-0007](adr/0007-tune-latency-spike.md)).
      - **playing**: the picture is moving, not a frozen first frame. This is what the gate judges.

      Timing runs from the **last** key press, so a short settle delay after rapid presses is inside the budget. Timing is logged by the app (S1.4.4).
      - **Firestick 4K (AFTKRT, API 30), the speed gate (waived for submission on 2026-11-04, see the status below):** keypress → playing under **2.0 seconds** on at least 29 of 30 flips onto items that direct play. Flips onto items the server transcodes are timed and reported, not gated.
      - **Insignia (AFTJMST12, API 25, the minSdk floor), the usable floor:**
        - every key press updates the banner's channel number within about 250 ms;
        - over 30 direct-play flips, the median keypress → playing is under **4 s** and the worst is under **8 s**;
        - no hangs: every flip ends on a moving picture or the channel's stand-in or slate, never a frozen or black screen;
        - transcoded flips are reported only.

      Both devices keep being optimised. Only the pass or fail bar moved. Older and budget hardware can never match a newer stick, and people with older devices deserve a good app too, so the floor device must be comfortable to use rather than fast. The thresholds are a starting point and may be adjusted after measurement.
- [ ] Scheduler golden-vector tests pass in CI.

Status 2026-11-03: not met. Firestick runs in [#392](https://github.com/StationBreak/stationbreaktv/issues/392) reach about 20 of 21 under 2 s; the formal 30-flip run was done on 2026-11-03 (the Insignia met its usable floor; the Firestick missed its 2 s gate because of the end-of-file MP4 index). Phase 2 work started while this gate was open.

Status 2026-11-07 (spec 24, [ADR-0024](adr/0024-warm-next-decoder.md)): Firestick speed gate not met. Warming the next channel's decoder was built and measured; on the Firestick its standby costs about 60 MB and trips the system's running-low memory trim, so it ships off by default and [#392](https://github.com/StationBreak/stationbreaktv/issues/392) stays open. Insignia usable floor met: direct flips median 3267 ms and worst 4126 ms (transcode flips, reported only: median 6164 ms, worst 7998 ms).

Status 2026-11-13: **built; the Firestick flip-speed gate is waived for submission** (owner decision 2026-11-04), unless flips make the app unusable. The Insignia usable floor is met, so the gate stays unchecked only for the Firestick bar. [#392](https://github.com/StationBreak/stationbreaktv/issues/392) and [#563](https://github.com/StationBreak/stationbreaktv/issues/563) continue as optimisation, not as a blocker.

Cross-device agreement is **not** a gate ([ADR-0002](adr/0002-determinism-scope.md)). It's observed during
the Phase 2 dogfood with two devices on the same account.

---

## E2 — Guide and builder (Phase 2)

**Goal:** a real grid guide and now/next banner, plus a custom channel builder, so StationBreak can
fully replace NostalgiaTV.

**Features:** F2.1 Grid guide · F2.2 Now/next banner & info overlay · F2.3 Custom channel builder ·
F2.4 Channel management (renumber, hide, delete) · F2.5 Large-library performance ·
F2.6 Kid channels, phase B

Also built in this phase: lineup curation and the themed starter catalog (spec 23), library settings and Sync now
(spec 26), channel properties (#631), channel marks and logos (#571), audio and subtitle track choice and video
version choice (#497, #630), and "Even out volume" (#498).

**Gate (acceptance criteria):**
- [x] The owner's named pre-dogfood items are done: [#631](https://github.com/StationBreak/stationbreaktv/issues/631) (channel properties), [#571](https://github.com/StationBreak/stationbreaktv/issues/571) (channel marks), and [#497](https://github.com/StationBreak/stationbreaktv/issues/497) and [#630](https://github.com/StationBreak/stationbreaktv/issues/630) (track and version choice). All four are built and closed (#497 and #630 on 2026-11-11; #631 and #571 on 2026-11-12).
- [ ] Used as the **daily** TV app on the reference Insignia for **14 consecutive days**, with NostalgiaTV uninstalled or
      unused. Issues found go into a dogfood log (`docs/dogfood/phase-2.md`). The clock started 2026-11-12; the earliest pass is 2026-11-25.
- [ ] No P0/P1 defects are open at the end of the 14 days.
- [ ] (Non-blocking observation) Two devices on the same account show the same lineup (ADR-0002).
- [ ] The guide scrolls smoothly (see F2.5 thresholds) against a 10k+ item library.

---

## E3 — Polish and personality (Phase 3)

**Goal:** the features that make it *feel* like TV (filler, retro mode, themes), plus onboarding
good enough that strangers succeed without help.

**Features:** F3.1 Filler & bumpers · F3.2 Retro mode · F3.3 Themes · F3.4 Guided onboarding ·
F3.5 Watch-state options · F3.6 Diagnostics & opt-in crash reporting · F3.7 Beta program

**Gate (acceptance criteria):**
- [ ] **5 to 10** outside beta testers recruited from r/jellyfin install the app, connect, and watch a
      channel **without hand-holding**. Each tester reaches first playback with zero support
      messages, or the friction they hit is logged and fixed.
- [ ] At least 3 distinct Fire TV models and 1 non-Amazon Android TV are represented among the testers.

Status 2026-11-13: not started as a gate. The prerequisites (onboarding, phone setup, install guide) are tracks A to F
of [#752](https://github.com/StationBreak/stationbreaktv/issues/752) and run alongside the E2 dogfood.

---

## E4 — Public release (Phase 4)

**Goal:** StationBreak is installable by normal users from the Fire App Store (V1), and later from Google Play
and GitHub, with a docs site. The Pro unlock follows as the first update after the listing.

**Features:** F4.1 Release pipeline & signing · F4.2 Amazon Appstore listing · F4.3 Google Play listing ·
F4.4 GitHub Releases · F4.5 Pro unlock · F4.6 Docs site (StationBreak.tv)

**Scope (owner decisions 2026-11-04 and 2026-11-06):** V1 is a **free Fire App Store listing**. Release work is
tracked in [#752](https://github.com/StationBreak/stationbreaktv/issues/752) and the `release:fire-v1` label.
Google Play (F4.3) and GitHub Releases (F4.4) follow the listing, and Pro (F4.5) is the first update after it.
None of them is V1.

**Gate (proposed, not in the spec):**
- [ ] V1: live on the Amazon (Fire) App Store as a free app, with the release pipeline (F4.1) producing the signed build.
- [ ] Fresh install from the Fire App Store works on a device that has never had a dev build.
- [ ] The docs site covers install, server setup (URL, reverse proxy, self-signed), and channel building.
- [ ] First updates after the listing (not V1): live on Google Play; a signed APK published on GitHub Releases
      by CI from a tag; Pro purchase and restore work on both stores.

---

## E5 — Expansion (Phase 5)

**Goal:** reach beyond a single Android device and a single scheduler.

**Features (ordered):** F5.1 Channel import/export *(deferred until a hosting/storage approach is chosen)* · F5.2 Companion sync server · F5.3 XMLTV/M3U schedule
source · F5.4 Roku client (separate repo) · F5.5 Apple TV client (separate repo/KMP) ·
F5.6 Emby adapter · F5.7 Plex adapter · F5.8 Profiles & parental controls

**Gate (proposed):** each sub-feature gets its own gate when it's planned. This epic gets broken down
again after E4 ships. Only the stories listed in the backlog are firm.

Already in v1 under F5.8: the kid hold ([ADR-0019](adr/0019-kid-hold.md)), the grown-up hold
([ADR-0020](adr/0020-grown-up-hold.md)) and kid-safe-by-default channels. Still deferred: a PIN and per-profile
channel sets. [ADR-0026](adr/0026-studio-fold-2.md) (studio fold 2, accepted 2026-11-13) is not built.
