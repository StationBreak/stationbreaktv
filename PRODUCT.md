# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

The shipping surface is the Fire TV / Android TV app (Kotlin, Jetpack Compose for TV, minSdk 25): a 10-foot,
D-pad-only interface, not a phone or touch UI. A future web surface (the stationbreak.tv site and docs, Phase 4) is in
scope for this record but will declare its own platform in its surface brief.

## Users

**Primary: the household on the couch.** Whoever picks up the remote, including kids and less technical family
members, who want to "just turn it on" and flip channels instead of scrolling a library. When audiences trade off,
their experience wins.

**Secondary:**

- The self-hoster who runs Jellyfin. They set StationBreak up once (server URL, sign-in, channels) and from then on
  are mostly another viewer on the couch.
- Nostalgia fans who want period-feeling channels with bumpers and filler (Phase 3 retro mode and filler serve them).

## Product Purpose

StationBreak turns a personal Jellyfin library into always-on, flip-through broadcast channels. It computes what is
airing now and at what offset, then plays that file directly from Jellyfin, so tuning in lands mid-programme like
cable. Success for v1 (the MVP gate): it replaces NostalgiaTV for daily use on the reference Insignia Fire TV for two
weeks. Phase 3 success: 5 to 10 outside beta testers from r/jellyfin run it without hand-holding.

## Positioning

The opportunity is the client experience, not the scheduler: a fast, polished guide and channel-surfing feel on the
TVs people already own. Against the alternatives:

- Connects by URL (HTTPS, reverse proxy, subpath) or IP:port, unlike clients that only take IP:port.
- Installs on every Fire TV, including budget models like the Insignia, and stays usable on 1.5 GB RAM.
- Open source (GPL-3.0-or-later), where most competitors are closed, single-developer apps.
- Deterministic scheduling: two TVs in one home show the same lineup at the same time with no server.
- Its channels are 100% invented brands that feel like networks that have always existed, never knock-offs of real
  networks.

## Operating Context

- Lean-back viewing across a room, with a TV remote: D-pad, Select, Back, Menu. Fire OS takes the Guide key on older
  devices, so Back and Select are the dependable paths.
- Back walks outward one step at a time (overlay → full-screen show → guide → menu row → exit). It is not the
  previous-channel key; "Last" lives in the now/next banner and on Menu.
- The guide is a grid EPG (at least 6 hours ahead) with the live picture in a top-right corner window, a details panel
  top-left and the grid below. The now/next banner appears on tune.
- Setup is typing a server URL with a remote; phone-assisted setup (QR to a local web form) is planned.
- Reference devices: the Insignia Fire TV (API 25, the floor: "my needs are the floor") and the Fire TV Stick 4K
  (API 30, the speed bar).

## Capabilities and Constraints

- Built: scheduler, themed starter catalog, live player with offset playback, channel flipping, playback reporting,
  grid guide and banner, kid channels, pause and seek on live, channel management, channel builder.
- Starter catalog: the planner switches on its best-filling 48 channels; every channel the viewer switches on is added
  on top. The rest wait on a "More channels" shelf. A channel the viewer hid is never switched back on by the app.
- Kid channels are safe by default and fail closed: unrated or unrecognised ratings are blocked under a ceiling.
- Channels are portable JSON rules, not stored schedules. The schedule wins: an early-ending show is followed by the
  station ident card; a long one is cut at the block boundary.
- Performance is a design constraint: flips must feel fast on the Firestick and comfortable on the Insignia; the guide
  must stay smooth with 10k+ item libraries and a 6-hour × 50+ channel grid on low-end GPUs.
- Non-goals for v1: being a general Jellyfin client (browsing, search, music), real broadcast/IPTV, DVR, reminders.
- Business model: free GPL app with a one-time Pro unlock in the stores. What Pro contains is undecided (Q-BIZ-2);
  free must fully replace NostalgiaTV. Pro is not part of the first store listing.
- No telemetry by default; any crash reporting is opt-in.
- Undecided: logo and visual branding (Q-BRAND-1), Pro contents and pricing, crash-reporting tool.

## Brand Commitments

- Name: StationBreak (domain stationbreak.tv, GitHub org StationBreak).
- Voice: plain, with a wink. Clear plain English comes first; broadcast flavour ("We'll be right back", station IDs)
  appears only where it costs no clarity.
- Design tiebreaker: ask what cable did. It has already settled locked channels (visible but scrambled), premium
  content airing late, and breaks that interrupt every channel at once.
- Channel identity is a portfolio of independent, original brands, governed by `docs/design/channel-identity.md`.
  Nothing may evoke a real network.
- The launcher icon, TV banner and README banner are the approved brand art (Stan and the Ranchers wordmark), in `art/brand/`.
- Public text (docs, issues, fixtures, store copy) uses generic stand-ins, never real library titles.

## Evidence on Hand

- Product docs: `docs/SPEC.md` (source of truth), `docs/ROADMAP.md`, `docs/BACKLOG.md`, `docs/ASSUMPTIONS.md`,
  `docs/adr/`.
- Design specs and target mocks in `docs/design/` (guide overhaul, guide panel, live banner, channel properties,
  channel identity and its approved sample sheet).
- Measured playback results on real Fire TV devices: `docs/compatibility/playback-matrix.md`.
- Bundled fonts and provenance: `core/ui/FONTS.md`.
- No testimonials, user counts, press or store ratings exist yet. Do not fabricate them.

## Product Principles

1. **The couch comes first.** Every screen must work for someone who just picked up the remote and never read a
   setting. Setup complexity belongs to the self-hoster, once.
2. **Feels like cable, works like a modern app.** Instant tune-in, up/down flipping and a real grid guide; when in
   doubt, ask what cable did, then make it clearer.
3. **The floor device sets the bar.** If it is not comfortable on the Insignia, it is not done.
4. **Safe by default, honest about limits.** Kid channels fail closed; the app says plainly what it cannot lock.
5. **Original through and through.** Invented channels, invented brands, no borrowed network identity.

## Accessibility & Inclusion

- 10-foot legibility from the couch is required: guide density was reduced after on-TV review, and metadata text
  must not drop below the 10-foot minimum.
- Every action reachable by D-pad, with a visible focus state at all times.
- Kids are among the viewers. Inferred, not yet confirmed: flipping and tuning should not depend on reading.
