# StationBreak — Spec & Architecture

Sep 29, 2026 · @Phil Critchfield

## Vision and problem

Build the best TV client for turning a personal Jellyfin library into always-on, flip-through broadcast channels, starting on Fire TV / Android TV.

Existing options each fail on something that matters:

- **NostalgiaTV**: library limitations, awkward UI, no install path on some Fire TV models (e.g. Insignia).
- **QuasiTV**: requires an IP:port for Jellyfin; rejects URLs, so reverse-proxied servers can't connect.
- **CoaxTV**: Apple only.
- **ErsatzTV / Tunarr**: excellent schedulers, but viewing is stuck with the plain Jellyfin Live TV UI.
- Most competitors are closed source, single-developer, and of uneven quality.

The opportunity is the client experience, not the scheduler: a fast, polished guide and channel-surfing feel on the TVs people already own.

## Goals, non-goals, users

**Goals**

- Channel surfing that feels like cable: instant tune-in mid-program, up/down to flip, a real grid guide.
- Connect to any Jellyfin server by URL (HTTPS, reverse proxy, subpath) or IP:port.
- Handle large libraries (10k+ items) without slowing the guide.
- Sideloadable and store-installable on every Fire TV, including budget models like Insignia.
- Open-source core so the self-hosted community can trust and extend it.

**Non-goals (v1)**

- Being a general Jellyfin client (browsing, search, music).
- Real broadcast/IPTV sources, DVR, or recording.
- Roku and Apple TV (planned later, separate codebases).

**Target users**

- Self-hosters with an existing Jellyfin library who want a lean-back TV experience.
- Families who want kids or less technical members to just "turn it on" instead of scrolling.
- Nostalgia fans who want period-accurate channels with bumpers and filler.

## Architecture

The client fakes "live" without a streaming server: it computes what is airing now and at what offset, then plays that file directly from Jellyfin, seeked to the offset. No server-side transcoding pipeline is required for v1.

| Layer | Responsibility |
| --- | --- |
| Jellyfin adapter | Auth (URL or IP; user login or Quick Connect, with API keys only for server-side components, see ADR-0001), library sync, stream URLs, playback reporting |
| Library cache | Local DB of items, metadata, durations; incremental sync |
| Channel engine | Channel definitions and rules (libraries, genres, collections, tags, eras, filler) |
| Scheduler | Deterministic, seeded schedule generation so every device shows the same lineup at the same time |
| Guide / EPG | Grid guide, now/next, channel banner, info overlay |
| Player | Direct play at computed offset, fallback to Jellyfin transcode, fast channel switching |
| Sync (later) | Optional companion server to share channels and settings across devices |

**Schedule sources are pluggable.** v1 ships the built-in scheduler. A later adapter reads M3U/XMLTV from Tunarr or ErsatzTV, so power users keep their existing scheduler and just use this app as the front end.

**Determinism is the key trick.** A schedule derived from (channel rules + library snapshot + seed + time) needs no server to stay consistent between two TVs in the same home.

## MVP scope

The MVP is done when it replaces NostalgiaTV on the reference Insignia Fire TV.

**Must have**

- Server setup accepting URL or IP:port, with a connection test and clear errors.
- Auto-generated default channels (per library, per genre, per collection).
- Custom channel builder: pick sources, order (shuffle, chronological, random-in-order), block length.
- Live tune-in at the correct offset; up/down channel flipping; last-channel recall.
- Grid guide (at least 6 hours ahead) plus now/next banner.
- Remote-friendly D-pad navigation throughout.

**Should have**

- Filler: bumpers, commercials, or shorts between programs from a chosen library.
- Retro mode: 4:3 framing, CRT scanline overlay, era-styled guide theme.
- Watch-state options: skip or include watched items.

**Later**

- Channel import/export and cross-device sync.
- Tunarr/ErsatzTV XMLTV source adapter.
- Multi-user profiles and parental controls.
  - Emby then Plex server adapters, behind the same interface as the Jellyfin adapter.

## Platform and tech stack

Fire TV / Android TV first, in native Kotlin, because playback quality and remote navigation are the product.

| Area | Choice | Why |
| --- | --- | --- |
| Language / UI | Kotlin + Jetpack Compose for TV | Native D-pad focus handling, one app for Fire TV, Google TV, Shield |
| Player | Media3 (ExoPlayer) | Direct play, HLS fallback, fast source swaps for channel flipping |
| Local DB | Room (SQLite) | Library cache and schedules offline |
| Networking | Ktor or Retrofit + OkHttp | Jellyfin REST API |
| DI / arch | Hilt, MVVM, unidirectional state | Testable layers matching the architecture table |
| Min SDK | Android 7.1 (API 25) | Covers older and budget Fire TV sticks |
| Distribution | Amazon Appstore, Google Play, GitHub APK | Store install for normal users, sideload for everyone else |

Put the scheduler and channel engine in a pure Kotlin module with no Android dependencies. It can then be reused by a future companion server and unit tested on the Omarchy box.

**Later platforms:** Roku (BrightScript/SceneGraph, separate repo) and Apple TV (Swift) consume the same channel rules format. After those, long-term: Samsung (Tizen), LG (webOS), and Vizio (SmartCast) via a shared web app, then Xbox if demand appears. Other Android TV brands (Sony, TCL, Hisense) are covered by the v1 build.

## Data model, integrations, security

Channels are stored as portable JSON rules, not generated schedules, so they can be exported, shared, and regenerated anywhere.

**Core entities**

- **Server**: base URL, auth token, user ID, selected libraries.
- **MediaItem**: Jellyfin ID, type, series/season/episode, runtime, genres, tags, year, watched state.
- **Channel**: number, name, logo, source rules, ordering mode, filler rules, seed.
- **ScheduleBlock**: channel, start time, item, in/out offsets (derived, cached, never the source of truth).

**Jellyfin integration**

- Use the official REST API; report playback so watch state stays accurate.
- Prefer direct play; request a transcode only when the device can't decode the codec.
- Accept http/https URLs with subpaths; handle self-signed certificates with an explicit opt-in.

**Security**

- Store tokens in Android Keystore-backed storage, never plain prefs.
- Recommend a dedicated, non-admin Jellyfin user in onboarding.
- No telemetry by default; any crash reporting is opt-in.

## Roadmap

Each phase ends with a gate that must pass before the next starts.

1. **Phase 0 — Foundations.** Repo, CI, module layout, Jellyfin adapter, library sync.
   - Gate: app connects by URL and lists all libraries on the Insignia Fire TV.
2. **Phase 1 — Live core.** Scheduler, default channels, offset playback, channel flipping.
   - Gate: flip through 10 channels, each lands mid-program in under 2 seconds.
3. **Phase 2 — Guide and builder.** Grid guide, now/next, custom channel builder.
   - Gate: used daily on the reference Insignia for 2 weeks, replacing NostalgiaTV.
4. **Phase 3 — Polish and personality.** Filler/bumpers, retro mode, themes, onboarding.
   - Gate: 5 to 10 outside beta testers from r/jellyfin run it without hand-holding.
5. **Phase 4 — Public release.** Amazon Appstore, Play Store, GitHub releases, docs site.
6. **Phase 5 — Expansion.** Sync/companion server, XMLTV adapter, then Roku, then Apple TV.

## Icebox: future possibilities (not committed)

Captured so they aren't lost. None of these block the MVP; most build on the filler system and profiles.

**Educational interstitials for kids.** Instead of real commercials, short clips in the spirit of Schoolhouse Rock, Blue's Clues, or 80s PBS spots: "What color is this?", "What's 5 + 5?", or "Get up and wiggle!" The pause and answer are baked into the clip, so no app interactivity is needed. They play through the existing filler rules, tagged for kids' channels. Truly interactive versions (remote input, tracking answers) are a later roadmap item.

**Interest-based interstitials for adults.** The same idea for grown-ups: two-minute clips between programs on topics the viewer cares about (the A-10 Warthog, Roman siege weapons). Short-form with value, not an engagement feed; the schedule decides when it ends. Simplest version: filler rules pull from a user-tagged folder of clips (e.g. this channel's breaks use the aviation tag).

**Parental viewing windows.** Parent-configured lockouts on kids' profiles (e.g. no TV 10:00 to 2:00 during school or homeschool hours), with a parent PIN override and switchable presets like School Year and Summer. Requires profiles, which pulls them forward. The app can only lock itself; the UI should say so plainly and point parents to Fire TV's own parental controls.

**House-wide station breaks.** Movement breaks (e.g. 15 minutes at a set point each hour) applied as a global layer above every channel's schedule, so changing channels lands on the same break. Shown in the guide as one bar across every row. Content can be a timer or visualizer screen, or high-energy movement videos via filler. This is the feature that makes the name literal.

**Break scheduling rule (hybrid by runtime):**

- Programs that fit between breaks (Bluey, most cartoons) are scheduled around them, never cut.
- Longer programs (Cars, A Bug's Life) pause for the break and resume afterward. Their effective slot is runtime plus the breaks that fall inside it (a 90-minute movie crossing two 15-minute breaks occupies 120 minutes), which keeps the scheduler deterministic.
- Test frame-accurate resume after a long pause on real hardware; it is a different path from tuning in at an offset.
- The math is real work and is explicitly not MVP.

**Eligibility layer.** Holiday windows, late night, movement breaks, and parental lockouts are all the same mechanism: time-and-date rules deciding whether a channel or item is eligible right now. Build it once.

**Holiday scheduling.** Tag-driven: items tagged Halloween surface in October, Christmas in December, drawing on Jellyfin tags and collections. A per-channel "always on" toggle disables the date check for anyone who wants Rankin-Bass in July.

**Late night.** Two things that look alike and aren't. Time-of-day scheduling (mature content only airs after midnight) is a scheduling preference and can ship without profiles. A PIN gate before mature content plays is access control and should wait for profiles, or it's a toggle anyone with the remote can flip — worse than no gate, because a parent might trust it.

**Locked channels in the guide.** A locked channel greys out as a whole, name included, with no titles shown — the channel's own name is readable text otherwise. Flipping past it skips rather than prompting. This matches how cable actually behaved: you could see a channel existed without seeing what was on it.

**Kids content model.** Content rating as the ceiling, an explicit allowlist as the truth. Ratings do the bulk filtering, but they fail at the edges (Hitchcock is rated G), and Jellyfin's rating metadata is only as good as whatever scraped it — missing values, regional systems like BBFC or FSK that don't map to MPAA, occasional bad scrapes. An allowlist of approved collections or tagged items doesn't care what the metadata says. Kids profiles start empty and a parent adds to them, so the failure mode is an empty channel rather than a four-year-old seeing Psycho. Curation should happen from the guide — flag what's on screen as kid-approved — not in a settings list nobody finishes.

**Mature content stance.** These controls are opt-in conveniences for an owner who knows what's on their own server, not safety features the app owes anyone. Mature settings default to off, and the PIN is a convenience rather than a security boundary. Document it that way.

**Profiles and the PIN model.** The admin profile is the unfiltered view: everything on the server, no windows, no allowlist. Restrictions are applied to other profiles, never opted out of. The PIN guards only leaving a restricted profile — switching out of a kids profile asks for it, switching from admin into any profile does not. Parents with no kids profile never see a PIN at all. Borrowed from Fire OS, so the mental model comes free.

Known gap, worth watching: whatever profile the TV is left on is what protects you. Switch to admin after bedtime, walk away, and it's open in the morning. An idle timeout that drops back to the last restricted profile would close it if it ever matters.

**Appliance mode.** A dedicated device (e.g. a kid's room TV) that boots straight into StationBreak, locked to one restricted profile, so only the media server and allowed content are reachable. This closes the "press Home and open YouTube" gap. App-side work is small: register as a home/launcher app, support Android lock task (kiosk) mode via one-time adb device-owner setup, pin the device to a profile, and require the parent PIN to exit. Hardware caveats: Raspberry Pi needs an unofficial Android build (e.g. LineageOS for Pi 4/5) with patchy hardware decoding, so a cheap Android/Google TV box is often the better appliance. Fire TV blocks launcher replacement, so appliance mode is any Android TV device except Fire TV; say so in the docs.

**Design tiebreaker: ask what cable did.** It has already resolved several of these — scrambled-but-visible locked channels, premium content airing late, breaks that interrupt every channel at once.

## Open decisions and Claude Code handoff

**Open decisions**

- [ ] Project name: decided, StationBreak (domain StationBreak.tv, GitHub org stationbreak). Logo and visual branding still open.
- [x] License: decided, GPL-3.0 for the whole project.
- [x] Business model: decided, free GPL-3.0 app with a one-time Pro unlock in the stores (Pro code stays public).
- [ ] Server adapters: decided, v1 Jellyfin-only, then Emby (API close to Jellyfin), then Plex (larger reach).

**Instructions for Claude Code**

Using this document as the source of truth:

1. Generate epics that map to the roadmap phases, each with a goal and its gate as acceptance criteria.
2. Break each epic into features and user stories with testable acceptance criteria.
3. Propose the Gradle module layout, keeping the scheduler in a pure Kotlin module.
4. Produce a phased roadmap with dependencies and flag any risks or unknowns.
5. Write these as markdown files under `docs/` and as GitHub issues, but ask before creating issues.
6. List any assumptions you made and questions for the owner before starting Phase 0.
