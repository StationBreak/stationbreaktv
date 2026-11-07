# ADR-0024: Keep the next channel's decoder warm (spec 24, spikes #564 and #580)

- **Status:** Accepted, off by default (2026-11-07)
- **Date:** 2026-11-06; accepted 2026-11-07
- **Decided by:** the owner for warm audio and for the open questions in spec 24; the mechanism and the default-off call
  by the planning session
- **Addresses:** [#563](https://github.com/StationBreak/stationbreaktv/issues/563) (prepare the neighbour channels' media
  ahead of a flip). It does not resolve the Firestick part of [#392](https://github.com/StationBreak/stationbreaktv/issues/392),
  which stays open
- **Builds on:** [ADR-0007](0007-tune-latency-spike.md), [ADR-0016](0016-playback-routes-and-the-media-client.md),
  [ADR-0017](0017-live-channel-playback.md) (flipping and the neighbour look-ahead)
- **Informs:** spec 24; the Phase 1 gate in [EPICS.md](../EPICS.md)

## Context

The Phase 1 gate asks for keypress to playing under 2.0 s on at least 29 of 30 direct-play flips on the Firestick 4K
(API 30), and a usable floor on the Insignia (API 25: median under 4 s, worst under 8 s). The formal run of 2026-11-03
(benchmark build, CI run 41603328960, 30 direct-play neighbour flips per TV) gave:

- **Insignia:** pass. Median 2.63 s, worst 4.66 s.
- **Firestick:** fail. 16 of 30 under 2.0 s, median 1.94 s, worst 3.22 s.

The miss was one channel. Flips onto the upper channel (a Matroska file with an 18 KB index) were 15 of 15 under 2 s.
Flips onto the lower channel were 1 of 15: an MP4 whose index sits at the end of a very large file. Each tune opens the
file, seeks to the end, downloads and parses a 4.4 MB index (`parseMs` 660-750, `tracksMs` 965-2022) before the first
frame. The neighbour look-ahead already resolves the stream URL but never opens the media, so every flip pays this
again. Decoder start-up was not the cost on a cold flip (29-74 ms). The media's layout belongs to the server, and the
app should not ask people to remux.

Two spikes tried to move that work ahead of the key press. Both were built from CI, with the device run on the
Firestick through the bridge.

**#564, preload the neighbour's media.** Media3's `DefaultPreloadManager` opened the neighbour, read the index and
selected tracks, then handed the prepared source to the one player. On the slow MP4 flips this was worse than doing
nothing: median 4952 ms, against 3748 ms with preload off (same build, debug APK, so only the difference counts). The
index was no longer read at the flip, but the video decoder then started after the hand-over, about 3.7 s later. The
player also had to be built through the preload manager's builder, which shares threads, renderers and load control with
it. That is the riskiest part of the spike.

**#580, keep the decoder warm.** Built on #564. A second ExoPlayer prepares the neighbour paused, with no surface; Media3
starts the codec on a placeholder surface. On a flip the standby takes the visible surface, seeks to the live position
and plays, and the old player becomes the next standby. On the Firestick, flips onto the slow-start MP4 dropped to a 1.05 s
median, and 10 of 10 were under 2 s (7 of 10 before). Warm audio worked: the standby opens the audio track muted, and the
owner heard clean sound after the swap. On the Insignia (AFTJMST12), no decoder came up behind the surfaceless
standby, so the runtime probe switched warming off there. The slow flips that remain are transcodes, about 5.6 s, of
which most is the server's first segment. The gate reports those and does not judge them.

## Decision

The mechanism is built and kept behind `warmNextDecoder`, which is **off by default**. The reason is in
[Result of the gate](#result-of-the-gate). The numbered points below describe what shipped.

1. **Keep one standby player, holding the neighbour most likely to be flipped to next, with its decoder running.** It is
   paused, muted, surfaceless and buffers about 2 s only (a holding load control stops it filling a second full buffer).
   The spike warmed the Down neighbour. The app warms the neighbour in the direction of the viewer's last flip, and
   Down until there has been one. The other neighbour is not warmed.
2. **Only direct-play neighbours.** A neighbour on the transcode route is not warmed. Nothing is gained there, and it
   would start a server transcode nobody asked for. The flip is reported with the reason `transcode`.
3. **When.** The standby is prepared after the settled channel's first picture, for the neighbour whose stream is
   already resolved. It is never prepared while the on-screen tune is still starting, so the standby cannot slow the
   flip in progress, and never while a card (not a picture) is showing. A new settled channel drops a standby that no
   longer matches.
4. **The flip.** If the standby holds the target's source and is ready, the app moves the surface to it, seeks to the
   schedule's live position and plays. If not, it takes the existing path. The warm path adds a way to be fast and no
   new way to fail.
5. **Which devices.** The standby is used where a decoder can start without a surface. It is off for the device models
   in `warmDeviceDenylist` (the Insignia, `AFTJMST12`, is the one in the default) and where Media3's workaround list
   says it cannot. Two further rules cover devices off those lists: if the video decoder is not up within 1.5 s of the
   standby being ready, warming stops for the rest of the process; and after three failures in a row it stops for the
   player's lifetime. The first is the runtime probe, the second a circuit breaker. Each logs its reason. Devices that fall off
   are exactly as fast as before.
6. **Audio.** The standby opens its audio track muted, so the swap does not wait for the audio renderer. This is **on by
   default** when warming is on. A passthrough audio output may refuse a second open track. In that case the standby
   fails with an audio error and counts one failure toward the circuit breaker. The same standby is then rebuilt
   video-only, and audio stays off for the rest of the player's lifetime, so video warming survives.
7. **Limits.** Exactly one standby and one front player. The standby's buffer is held at about 2 s. The standby is
   released when the app leaves the live screen and when the screen stops. It also answers memory trim signals (#637):
   - A running level below `warmTrimLevel` (default 10, running-low) is logged and nothing else.
   - At `warmTrimLevel` or above, the standby is released and warming is paused for the visit.
   - At level 20 (UI hidden) or above, the standby is released but warming is not paused. The app has left the screen,
     which ends the visit anyway.

   `warmTrimLevel` can be set from 5 to 15.
8. **Tunables and switches.** The tunables are in `WarmConfig`, with safe defaults, not hard-coded:

   | Tunable | Default | Meaning |
   | --- | --- | --- |
   | `warmNextDecoder` | off | Warm the next channel at all |
   | `warmNextAudio` | on | The standby opens its audio track, muted |
   | `warmHoldBufferMs` | 2000 | How much the standby buffers |
   | `warmReadyTimeoutMs` | 15000 | The standby must be ready within this, else a `timeout` failure |
   | `warmDecoderGraceMs` | 1500 | The video decoder must be up this long after ready, else warming stops |
   | `warmSeekToleranceMs` | 250 | After a swap, the standby seeks to the live position only if it is off by more than this |
   | `warmMaxFailures` | 3 | Failures in a row before the breaker opens |
   | `warmDeviceDenylist` | `AFTJMST12` | Device models that never warm |
   | `warmTrimLevel` | 10 | Lowest running trim level that pauses warming |

   The debug and benchmark builds also read `adb shell setprop log.tag.SBWARM ASSERT` (warming off) and
   `log.tag.SBWARMAUD ASSERT` (standby audio off) without a new APK, through `WarmSwitches`. A switch can only turn a
   tunable off. A release build reads no switch. A settings screen entry is not added.
9. **Reporting.** The player issues the warm handle, a string `w<n>`, and the play carries it. Every play given a handle
   sends `PlayerEvent.WarmDecided` with whether it swapped, and if not, a reason: `none` (a swap),
   `disabled`, `notReady`, `mismatch`, `error`, `timeout` from the player, `wrongDirection`, `transcode`, `noHandle`
   from the controller, and `disabled` from the controller too, when warming is not wanted and it gave no key. `noHandle`
   means the tune had no prepared handle to warm (the first tune after launch, or a stale handle). A swap that is
   followed by a seek miss falls back to the cold path, reports the swap with `fallback=seekMiss`, and does not count
   as a warm flip.
10. **Off by default (#638).** `warmNextDecoder` is false. The Firestick run showed the standby too costly in memory
    (next section). Keeping the mechanism in the code, off, costs nothing at run time and keeps the work for when the
    standby is cheaper.

## Alternatives considered

- **Source-only preload (#564).** Rejected as the end state. It made the target case slower, because the index cost moved
  but the decoder cost grew, and it forced the player through the preload manager's builder. The warm standby needs the
  open, index and track selection to have happened, which it does itself, so it does not need the manager.
- **Do nothing.** The Firestick stays at 16 of 30 on the formal run, and the gate fails on one file layout. Raising the
  threshold to fit the miss is not an honest option: the gate was set from measurement and the stick can do better.
- **Two full players, both on screen.** Keep a second player at full buffer and a surface for each, swap on a flip.
  Rejected: a second full buffer can take a quarter of the heap, and two visible surfaces add a compositing cost. The
  standby is the same idea with the buffer held and no surface until the swap.
- **Remux or cache on the server.** Out of scope. The app does not change the library.
- **Warm both neighbours.** Two decoders is more memory and more contention for a hardware decoder pool of a few
  instances. Not tried. Revisit only if the one-neighbour result misses the gate on a library with a slow-start file on
  both sides.

## Result of the gate

The gate run of 2026-11-07 did not pass on the Firestick. Warming ships off.

- **Insignia (API 25, AFTJMST12).** Denylisted: the log shows `disabled reason=denylist` and no standby is built. The
  usable floor passes. Direct flips: median 3267 ms, worst 4126 ms. Transcode flips: median 6164 ms, worst 7998 ms
  (reported, not gated). "No slower than 2026-11-03" is not shown, because the two runs used different channels.
- **Firestick (API 30, 1.7 GB).** The run was stopped before the flips. Memory trim paused warming in 3 of 3 launches,
  at levels 10, 15 and 15 (a level 5 signal was ignored, as designed). Native heap was about 62-67 MB when the standby
  was built and about 124-128 MB when it was ready, so the standby costs about 60 MB. The trim came 2-4 s after ready.
  About 300 MB was available after the standby was released. The first run, before #637, was blocked by a trim at the
  first tune.

The standby is memory-bound at its current cost on a 1.7 GB device: it trips the system's running-low trim, so warming
cannot hold. The Firestick part of #392 (under 2.0 s on 29 of 30 direct flips) is therefore still open. The evidence is
on [#563](https://github.com/StationBreak/stationbreaktv/issues/563) and
[#392](https://github.com/StationBreak/stationbreaktv/issues/392).

## Revisit when

A cheaper standby is measured on a device of about 1.5-2 GB without tripping the running-low trim. Options, not
decisions: no standby audio, a smaller held buffer, a lower-resolution decoder. If one holds, turn `warmNextDecoder` on
and repeat the 30-flip gate.

## Consequences

- **Memory.** A second player's heap and one extra codec instance on devices that use it, about 60 MB of native heap on
  the Firestick, which on a 1.7 GB device brings the system's running-low trim. This is why warming is off by default.
  The Insignia, the tight device, never builds the standby, so it pays nothing. With the tunable off, no second player
  is built.
- **Decoder contention.** Two codec instances at once (front and standby). A device with one hardware decoder instance of
  that type fails the standby's start; the probe and circuit breaker above turn warming off, and the flip is the cold
  path.
- **Flip direction.** The warm neighbour only helps the direction it was chosen for. A flip the other way is as it is now.
  The standby never makes it worse beyond the contention above.
- **Complexity.** A second player in `Media3VideoPlayer` and a surface hand-over, which Media3 blocks on until the old
  renderer lets go. The spike shows the order that works. This is the main review risk.
- **What stays slow.** Transcode flips (4.5-8 s, mostly the server's first segment), a cold first tune after launch, a
  neighbour that is not ready in time (a very fast double flip), and the 400 ms settle, which is in every flip.
- **Gate honesty.** Warming does not change the gate's rules or its device tiers. The gate still times from the last key
  press and reports transcodes without judging them. With warming off, the Firestick's result is the 2026-11-03 one.

## Verification

- **Unit and androidTest:** the swap, the fallbacks, the circuit breaker, the holding load control, the trim levels.
- **The 30-flip gate on the Firestick**, with the CI benchmark APK from a fully green run, in both directions, as in the
  2026-11-03 formal run. Pass is at least 29 of 30 under 2.0 s on direct-play flips. The shipped default has warming
  off, so a default build is the control run. A run with warming on needs a build where `warmNextDecoder` is true. On
  such a build, `log.tag.SBWARM ASSERT` (debug and benchmark builds) turns warming off again without a new APK. The
  result goes on #392 and the Phase 1 gate in EPICS.md.
- **Insignia regression check**, same build: the SBWARM lines show `disabled reason=denylist` and no standby is built,
  and the usable floor holds.
- **Warm audio check** by ear on the Firestick after the swap, on stereo and surround items, and by the logs (no audio
  errors counted). Not done: the run stopped before the flips.
- Evidence goes on the PR and on #563; the benchmark numbers go on #392.
