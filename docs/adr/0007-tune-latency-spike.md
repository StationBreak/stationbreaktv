# ADR-0007: Tune latency on the reference device (spike SP-1 result)

- **Status:** Accepted
- **Date:** 2026-10-09
- **Decided by:** Phil Critchfield
- **Resolves:** spike SP-1 ([ROADMAP.md](../ROADMAP.md)); partly resolves Q-GATE-1 in [ASSUMPTIONS.md](../ASSUMPTIONS.md)
- **Informs:** R-PERF-1, F1.3, F1.4, F1.6

## Context

The Phase 1 gate says a channel flip lands mid-program in under 2 seconds on the reference device: an
Insignia Fire TV on Fire OS 6.7.0.8 (API 25), with about 1.5 GB of RAM. SP-1 measured whether that's
reachable before the Phase 1 design is committed, and where the time goes.

## Spike

A throwaway Android app (branch `spike/sp-1`; minSdk 25, Media3 1.11.1, jellyfin-sdk-kotlin 1.9.2, one
OkHttp client shared by both, core library desugaring on) ran on the Insignia.

- **Measurement:** each tune started from a D-pad key sent over adb and played a different item from the
  previous one. Latency was measured from the key event to the first rendered frame, and to the first
  `STATE_READY` ("playing"). Each tune started 20 minutes in (or at 40% for items shorter than 25 minutes).
- **Player modes:** cold (a new player for each tune) and warm (one player reused).
- **Memory conditions:** clean (third-party apps force-stopped) and as-lived (normal use first, with other
  apps still cached).
- **Sources:**
  1. **The owner's library:** 10 items through the owner's reverse proxy over HTTP/2 and TLS, as direct play.
     This is the real home setup.
  2. **Synthetic fixtures:** 30-minute 1080p H.264/AAC files at 7.2 Mb/s, served over plain HTTP on the home
     LAN, in fragmented MP4, MP4 with the index at the front, MP4 with the index at the end, MKV with Cues,
     and MKV without Cues.

### Results (milliseconds, n = 20 tunes for the library, 10 per fixture variant)

| Source | Mode | Condition | First frame, median / p90 | Playing, median / p90 / max | Playing over 2 s |
| --- | --- | --- | --- | --- | --- |
| Library | cold | clean | 900 / 1711 | 1554 / 2284 / 2405 | 2 of 18 |
| Library | warm | clean | 943 / 2649 | 964 / 2906 / 3036 | 4 of 18 |
| Library | cold | as-lived | 956 / 2055 | 1627 / 2220 / 2494 | 4 of 18 |
| Library | warm | as-lived | 924 / 2584 | 965 / 2891 / 2947 | 5 of 18 |
| Fixtures, all seekable variants | both | both | 430–1297 / ≤ 2382 | 654–1337 / ≤ 2415 | 2 of 160 |

Fixtures: 4 seekable variants × 2 modes × 2 conditions × 10 tunes = 160. Two tunes in each library run were an AVI item that can't be decoded on the device, so those runs have 18
valid tunes. A tuned `LoadControl` (start buffer 750 ms) didn't consistently help either measure.

- **The network route isn't the bottleneck.** Resolving playback through the reverse proxy takes about
  55–65 ms, and the fixtures (no proxy, no Jellyfin) show the same player-side costs.
- **The gap between first frame and playing is the main problem when the player is cold.** On some library items (for example
  MKV H.264 1080p with AAC audio, and MP4 480p with AC3 audio) the first frame shows at about 0.9 s but playback
  doesn't start until 1.9–2.9 s. The cause looks like audio-side readiness, but that's unconfirmed. With a warm player, the slow tunes
  are already slow at first frame (p90 2.6 s), and that cause wasn't investigated.
- **Container matters a little.** Across the fixtures, fragmented MP4 was fastest to first frame (medians
  430–562 ms) and MKV with Cues was fastest to playing (654–776 ms). Both plain MP4 layouts were slowest
  (first frame 894–1297 ms), and moving the index to the front didn't help.
- **A warm player** improves the median time to playing by about 600 ms (1554 → 964 ms clean, 1627 → 965 ms
  as-lived). It leaves first frame unchanged and makes the tail worse (p90 2.9 s against 2.2–2.3 s; 4–5 tunes over
  2 s against 2–4). It helps the typical flip but not the worst ones, and the reason isn't yet understood.
  S1.4.5 must check the tail, not just the median.
- **Memory fits.** The spike app's peak was 127–161 MB, against about 244 MB free as-lived. About 300 MB
  of the device's RAM is permanently reserved for the video decoder and GPU.
- **TLS** to the owner's server works on API 25 with no extra trust anchors (for that certificate chain).

### Findings the real app must handle

1. **MKV without Cues can't be seeked, and fails silently.** ExoPlayer plays from the start and reports no
   error. The player must compare the requested position with the actual one and fall back (transcode,
   started by player seek; `StartTimeTicks` on the HLS URL is rejected by Jellyfin 12.2, ADR-0016) when they differ (S1.3.1).
2. **MPEG-4 Part 2 video (seen in AVI) fails to decode** on the device. The capability probe must route it
   to a transcode (S1.6.1).
3. **The device profile must declare subtitle formats,** both embedded and external, even with subtitles
   off. Without them Jellyfin refuses direct play for any item that carries subtitle streams (14 of 20 tunes
   in the first library run) (S1.6.1).
4. **jellyfin-sdk-kotlin 1.9.2 needs an SLF4J binding on Android.** Without one, the first request crashes
   with `NoClassDefFoundError: org.slf4j.LoggerFactory` (see the [ADR-0006](0006-jellyfin-12-only.md) notes).
5. **"Playing" must be measured with `onIsPlayingChanged(true)`.** The spike logged the first
   `STATE_READY` with `playWhenReady` already set, which should be equivalent but wasn't verified (S1.4.4).

## Decision

1. The Phase 1 gate times **two moments** for each flip: **first frame** and **playing**.
2. **Playing must happen in under 2 s** (strictly less), with at most 1 of 30 flips over (3 runs of 10, [EPICS.md](../EPICS.md)).
3. **First frame is tracked and reported, with no hard target.** The gate passes or fails on playing alone.
   Viewers judge a channel by when it's actually playing.
4. The Phase 1 design keeps the planned direction: a single reused player, adjacent-channel pre-resolution,
   and direct play where the device supports it. It adds an explicit investigation of the gap between the
   first frame and playing as part of F1.4 (S1.4.5).

Still open in Q-GATE-1: whether the gate also covers access from outside the home network, and whether
transcoded channels must meet 2 s.

> **Amendment, 2026-10-24 (owner decision, Q-GATE-1).** The pass bar in item 2 above was replaced by a
> two-tier gate: a 2.0 s keypress → playing bar on the Firestick 4K (API 30), and a usable-floor bar on the
> Insignia (API 25). Items 1, 3 and 4 stand. The open questions are settled: the gate is measured on the home
> network (A-NET-1), and transcoded flips are timed and reported but not gated. See the Phase 1 gate in
> [EPICS.md](../EPICS.md). The text above is left as decided on 2026-10-09.

## Consequences

- The gate (the 2026-10-09 bar; see the amendment above) is **not met today** on the owner's library: 2–5 of 18 valid tunes per run are over 2 s. That's
  expected at spike stage. Phase 1 has a specific, measured problem to solve instead of a guess.
- Files that can't seek or decode are a correctness problem as well as a latency one, so they're now
  acceptance criteria in F1.3 and F1.6.
- **Repository hygiene:** `spike/sp-1` contains a home-LAN address in its history. The branch is deleted
  before the repository goes public (Q-REPO-1). These results don't depend on keeping it.
