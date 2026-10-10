# StationBreak — Epics

One epic per roadmap phase. Each epic's **gate is its acceptance criteria**. An epic isn't done,
and the next one doesn't start, until the gate passes on the reference device.

Reference device: **Insignia Fire TV** (bedroom). See [ASSUMPTIONS.md](ASSUMPTIONS.md) A-DEV-1 for the
exact model and Fire OS version. Features and stories: [BACKLOG.md](BACKLOG.md). Sequencing and risks:
[ROADMAP.md](ROADMAP.md).

---

## E0 — Foundations (Phase 0)

**Goal:** a buildable, tested, CI-gated repo with the module skeleton, a working Jellyfin adapter
that connects by URL or IP:port, secure credential storage, and a local library cache.

**Features:** F0.1 Repo & CI · F0.2 Module skeleton · F0.3 Jellyfin adapter · F0.4 Secure credential storage ·
F0.5 Server setup & connection test · F0.6 Library sync & cache · F0.7 Channel rules format v1 (spec only)

**Gate (acceptance criteria):**
- [ ] On the Insignia Fire TV, entering the owner's real reverse-proxied HTTPS URL connects and lists
      **all** libraries visible to the user.
- [ ] The same works with a bare `IP:port` on the LAN.
- [ ] Subpath base URLs (e.g. `https://media.example.com/jellyfin`) are proven by automated tests (S0.3.1).
- [ ] Adapter contract tests pass in CI against the **oldest supported and latest Jellyfin 12.x** containers (S0.3.6, ADR-0006).
- [ ] The app was installed on the Insignia by sideloading a CI-built APK, not by a local build.
- [ ] CI is green on `main`: build, unit tests, lint, detekt, license header check.

---

## E1 — Live core (Phase 1)

**Goal:** turn the cached library into deterministic, always-on channels that the user can tune into
mid-program and flip between with the remote.

**Features:** F1.1 Deterministic scheduler · F1.2 Channel engine & default channels · F1.3 Offset playback ·
F1.4 Channel flipping & last-channel recall · F1.5 Playback reporting · F1.6 Direct-play / transcode fallback

**Gate (acceptance criteria):**
- [ ] **3 runs of 10 consecutive flips** on the Insignia. Each flip is timed at two moments (Q-GATE-1):
      - **first frame**: the correct mid-program picture appears. Target pending (Q-GATE-1).
      - **playing**: the picture is moving, not a frozen first frame. Within **2 seconds** of the key press.

      At most 1 of the 30 flips may be over. Timing is logged by the app (S1.4.4). Network conditions
      and direct-play-only are pending Q-GATE-1.
- [ ] Scheduler golden-vector tests pass in CI.

Cross-device agreement is **not** a gate ([ADR-0002](adr/0002-determinism-scope.md)). It's observed during
the Phase 2 dogfood with two devices on the same account.

---

## E2 — Guide and builder (Phase 2)

**Goal:** a real grid guide and now/next banner, plus a custom channel builder, so StationBreak can
fully replace NostalgiaTV.

**Features:** F2.1 Grid guide · F2.2 Now/next banner & info overlay · F2.3 Custom channel builder ·
F2.4 Channel management (renumber, hide, delete) · F2.5 Large-library performance

**Gate (acceptance criteria):**
- [ ] Used as the **daily** bedroom TV app for **14 consecutive days**, with NostalgiaTV uninstalled or
      unused. Issues found go into a dogfood log (`docs/dogfood/phase-2.md`).
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

---

## E4 — Public release (Phase 4)

**Goal:** StationBreak is installable by normal users from stores and by everyone else from GitHub,
with a docs site, and the Pro unlock works.

**Features:** F4.1 Release pipeline & signing · F4.2 Amazon Appstore listing · F4.3 Google Play listing ·
F4.4 GitHub Releases · F4.5 Pro unlock · F4.6 Docs site (StationBreak.tv)

**Gate (proposed, not in the spec):**
- [ ] Live on Amazon Appstore and Google Play. A signed APK is published on GitHub Releases
      by CI from a tag.
- [ ] Fresh install from each channel works on a device that has never had a dev build.
- [ ] The Pro purchase and restore work on both stores.
- [ ] The docs site covers install, server setup (URL, reverse proxy, self-signed), and channel building.

---

## E5 — Expansion (Phase 5)

**Goal:** reach beyond a single Android device and a single scheduler.

**Features (ordered):** F5.1 Channel import/export *(deferred until a hosting/storage approach is chosen)* · F5.2 Companion sync server · F5.3 XMLTV/M3U schedule
source · F5.4 Roku client (separate repo) · F5.5 Apple TV client (separate repo/KMP) ·
F5.6 Emby adapter · F5.7 Plex adapter · F5.8 Profiles & parental controls

**Gate (proposed):** each sub-feature gets its own gate when it's planned. This epic gets broken down
again after E4 ships. Only the stories listed in the backlog are firm.
