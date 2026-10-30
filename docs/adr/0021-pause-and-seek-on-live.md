# ADR-0021: Pause, go back and skip ahead on a live channel (spec 20, spike SP-7 result)

- **Status:** Accepted
- **Date:** 2026-10-30
- **Decided by:** the owner (2026-10-24, 2026-10-29 and 2026-10-30, quoted below) and Rincewind (the mechanism and the spike rulings)
- **Resolves:** [#383](https://github.com/StationBreak/stationbreaktv/issues/383); the open point in ADR-0018, "pausing within a visit"
- **Amends:** ADR-0016 (the player gains transport), ADR-0017 (2 and 6) and ADR-0018 (paused check-ins)
- **Informs:** spec 20, F1.5

## Context

A viewer on a live channel can flip and look at the guide, but cannot pause, step back to catch something again, or
step forward. The media keys were left unmapped for this (ADR-0017, flipping). The channel must stay a channel: the
schedule never moves and every TV still agrees on what is on (ADR-0002).

The owner decisions of 2026-10-24:

- Pause holds the picture. Play carries on from where I paused. The channel's schedule keeps running in the meantime, so
  I am now behind it.
- Back / Skip ahead move within the item that is on. Skipping ahead can't go past where the schedule is now.
- Catching up happens at the next item, not by delaying the schedule. When the item I'm behind on ends, the next item
  starts at the schedule's current position for it.
- The schedule itself never moves. Every TV keeps agreeing on what is on.

Three more, from the open points:

- 2026-10-29: a paused place is kept for **60 minutes** after the screen stops.
- 2026-10-30: **Back reaches the start of the item** (the block's in-point), even if the viewer tuned in halfway, so any
  show can be restarted and reach 90% watched. It is not limited to the join point.
- 2026-10-30: an untended pause must let the device sleep: the app releases the screen while paused, and the device's
  own screensaver, ambient and sleep settings take over, so the TV can turn itself off. The hold is configurable. After
  it, a return **joins live, playing** (like turning a cable box back on); within it, the viewer gets the paused place,
  still paused. The app never wakes the device or resumes play on its own.

Three things were unproven: whether pause check-ins are safe for user data (ADR-0018 did not test pausing), what an
idle transcode does while paused, and what a seek on a Jellyfin transcode costs. Spike SP-7 answers them.

## Decision

1. **The owner rules above are binding.** The schedule is untouched. Anything the viewer falls behind is cleared at the
   next item. Nothing is carried across the evening.
2. **Keys** (live screen, full picture, no overlay). Back steps 10 s, skip steps 30 s; both are defaults in
   `LiveTimings`.

   | Key | Live or behind | Paused |
   |---|---|---|
   | Play/Pause | Pause | Play |
   | Rewind, MEDIA_PREVIOUS, SKIP_BACKWARD | Back 10 s | Back 10 s, stays paused; D-pad Left scrubs the same (scrubbing: provisional; detailed UX with the design partner) |
   | Fast-forward, MEDIA_NEXT, SKIP_FORWARD | Ahead 30 s, capped at live; at the cap, live. Already live: nothing | Ahead 30 s, capped, stays paused; D-pad Right scrubs the same |
   | Select | Actions banner; "Live" is added while behind (provisional; detailed UX with the design partner) | Play |
   | Back | Guide | Guide |
   | Up, Down, CH+, CH-, Menu, guide tune | Flip (the behind state is dropped) | Flip (dropped) |

   A held Rewind or FF moves only the bar's target; one seek happens on release or after `flipSettle`. Transport keys are
   ignored while the snow shows, while the number is moving, and on any card. On a non-seekable direct-play source, back
   and skip do nothing; the app does not re-tune to gain seeking. D-pad Left and Right stay unmapped while playing, so a
   viewer reaching for Up or Down never moves their place. The transport bar's look is left to the design partner.
3. **State model.** Per sitting, held by the program being played: `Live` (position follows the clock),
   `PausedBehind(place)` and `PlayingBehind(place)`. `place` is the block (channel, block start, item, in-point, end
   position) and a media position. **Behind is derived**, not stored: the live position minus the media position,
   clamped at 0. It is scoped to the item. `Sitting.driftMs` is never made negative, because the sitting's limit and its
   `driftMs == 0L` lost-channel check depend on it. Ahead-drift from a dead item (ADR-0017, 2) still applies: catch-up
   uses the sitting's own clock. Skip clamps at the lesser of the live position and the item's cut; reaching that clamp
   is the move to `Live`. Back clamps at the block's in-point.
4. **Catch-up rule.** When a behind play reaches its item's end (the cut at the block's end, or the file's end, whichever
   comes first), the sitting looks up what is on the air now and plays it at its schedule position. This is a straight cut
   on the same channel, with no snow, and is not a flip. The next item is pre-resolved from the time remaining.
   A catch-up is a straight cut when the next item was resolved within `preResolveLead` of the cut; a file that ends
   early past its block, by more than the lead before its cut, tunes fresh with the usual snow.

   | Case | Result |
   |---|---|
   | Behind longer than the next item | Lands on whatever is on now |
   | File ends early while behind | Once the clock has passed the block: straight to catch-up, no ident fill. While the clock is still inside the block: the ident holds to the block's end, as on the live path |
   | File runs long while behind | Cut at the block's end, then catch up (the schedule wins, Q-SCH-3) |
   | Item's schedule slot ends while paused | Nothing; Play continues it |
   | Dead item or source error while behind | One retry at the place, from the block's single retry budget, which is shared with the live path. If still dead (or no retry is left): while the clock is inside the block, the existing dead-item handling (slate, then stand-in); once the block has ended by the clock, treated as the item's end and caught up, with no stand-in |
   | Stand-in | Can be paused and sought like a program |
   | Split item | "The item" is the block; Back stops at its in-point |
   | Flip, Previous or guide tune to another channel | Behind state dropped; coming back joins live |
   | Guide Select on the playing channel | Guide closes, state kept |
5. **Pause hold.** In the foreground a pause has no limit; the player is only paused. The screen's keep-awake is
   released while paused, so the screensaver can come on. If the screen stops, the paused place moves to the ViewModel
   for `pauseHoldMax` (default 60 minutes, in `LiveTimings`). Play within it tunes the item at the place and pauses on
   the first frame if the viewer was paused. Past it, or after the process dies, the viewer joins live. The place is
   never persisted.
6. **Seek uses the player's native seek inside the existing HLS playlist.** There is no new `PlaybackInfo`, no new
   `PlaySessionId`, and no `StartTimeTicks`. A seek's rebuffer shows as a spinner over the frozen frame, not snow
   (provisional; detailed UX with the design partner), and is not reported as a stall. A segment that fails after a
   seek is a mid-play failure: the play ends, and `BehindPlay` retries once at the place. That fresh tune runs
   ADR-0016, 4 internally (its one fallback and the 2 s landing check), as any tune does.
7. **Paused reporting.** While paused the app sends `POST /Sessions/Playing/Progress` with `IsPaused=true`, at the safe
   position (ADR-0018, 1), with the `PlaySessionId`, every 30 s, for up to `pauseReportMax` (default 5 minutes, in
   `LiveTimings`). After that it goes silent. With "Throttle transcodes" on, the server then reclaims the encode, and in
   every case it ends the session by itself. On Play the app sends a fresh `POST /Sessions/Playing` and continues the
   usual check-ins. The server restarts the encode on the next segment request; the app treats a slow first segment as
   normal, not as a stall or failure. With throttling on, this stops a long pause parking an ffmpeg job and its
   temporary files on a home server for up to an hour.
8. **Watched time is the union of media intervals played** per airing, not wall time open minus stalls (ADR-0018, 2).
   Pause is not watched time. Rewinding and rewatching does not count twice towards the 90%. Seeking ahead counts
   nothing. The airing key stays the behind block's key until catch-up. Counting needs position-tagged start, stop and
   seek events, including a failed-play retry and a resume from the kept place; the exact set is left to the plan.
   This rewrites the shipped `WatchLedger` (wall time minus stalls). Stalls fall out of the count by construction.
9. **Seams.** `VideoPlayer` gains pause, resume, seek and a position read. Transport is a per-play handle
   (`PlayControl`) in `TuneOutcome.Playing`, tied to that play's generation, not new `TunePort` methods. Commands reach
   the sitting through a per-sitting buffered channel, tagged with the sitting's id; a command whose id does not match
   the current sitting is dropped, so a press just before a flip can never seek the new channel. They do not go through
   `Flipper`. The sitting owns the seek target: repeated Back and Ahead presses during a hold add up there, and one
   absolute-target seek is issued on release or after the settle time. The sitting resolves Play/Pause against its
   current state, so two quick presses cannot invert it. Command names are left to the plan. The behind loop is its own
   class, not part of `playProgram`. The hold waits on media remaining, re-armed on each pause or seek, not on wall
   time left.
10. **Diagnostics** (numbers only): `SBLIVE seek` (direction, target ms, ms to playing, route) and
    `SBLIVE pause heldMs resumeMs`.

## Spike SP-7

**Method.** Jellyfin 12.1.0 and 12.2.0 at the digests the `jellyfin-contract` workflow pins, one throwaway container
at a time on loopback, with synthetic test-pattern media and a throwaway admin. No home server or credential was used.
The harness is [`scripts/spikes/sp7-pause-seek.sh`](../../scripts/spikes/sp7-pause-seek.sh); every row is in
`scripts/spikes/sp7-results-12.1.tsv`, `sp7-results-12.2.tsv` and the `sp7-lowcpu-*` pair (seeks only, container limited
to 0.5 CPU). Results were identical on both versions except the last row below. Segment fetching stands in for a
player.

| Question | Result |
|---|---|
| Are `IsPaused=true` check-ins at the safe position safe? | Yes. Every 30 s for 3 min, then resume and a `Failed=true` stop: Played, position, PlayedPercentage and Resume unchanged for an unwatched item, a 50% resume point and a played item. Only `Playing` adds a PlayCount. `/Sessions` shows the item paused at the safe position. |
| Does a pause flag extend the idle auto-stop? | No. One paused check-in then silence: the session was auto-stopped at about 10 min, user data unchanged. |
| Idle transcode, throttling on, no check-ins | ffmpeg killed after 5 to 7 min of silence (a 5 min timer on a roughly 1 min tick); session auto-stopped at about 10 min. |
| Idle transcode, paused check-ins with the `PlaySessionId` | ffmpeg stayed alive and parked for the whole 15 min tested. |
| Idle transcode, default config (throttling off, as shipped) | ffmpeg encodes the whole clip and exits by itself in about 100 to 130 s, with or without check-ins. Finished segments stay served for at least 15 min. Same as any HLS play, not a new cost of pausing. |
| Resume after 1, 5 and 15 min | No new `PlaybackInfo` needed. Same URLs answered 200 every time. After a kill the server restarts ffmpeg at the segment asked for: 0.22 to 0.35 s, against 0.01 to 0.03 s when the segment exists. |
| Seek, target already encoded | Native in-playlist seek 0.03 to 0.09 s, same ffmpeg process. A restart took 0.4 to 1.1 s. |
| Seek, target ahead of the encoder | The server restarts ffmpeg itself: 0.5 to 0.85 s at 2 CPUs, 1.4 to 1.7 s at 0.5 CPU, about what a client restart costs. |
| `StartTimeTicks` on the HLS URL | HTTP 400. The playlist is always full length; HLS seeking is asking for another segment. |
| Direct stream (HLS remux) seek | 0.01 to 0.5 s. It is HLS, not an HTTP-range file. |
| 12.2 only | Every positioned check-in, paused or not, moves `LastPlayedDate` for an item that already has a resume point; 12.1 sets it once, at `Playing`. Filed as [#511](https://github.com/StationBreak/stationbreaktv/issues/511). This ADR does not decide it (see Consequences). |

**Not tested.**
- A real player (ExoPlayer): playlist refresh after a pause, buffer eviction, reaction to a slow first segment.
- The full sequence: 5 min of paused check-ins, then silence, then a fresh `Playing`.
- An item with a resume point through that sequence, and hours-long items with throttling off.
- Pauses over 15 min, and check-ins without the `PlaySessionId`.

The items above are planned for the SP-7 re-run before the contract matrix moves. Not planned:
- Hardware transcode, subtitle burn-in, many parallel jobs.
- Segment deletion on (a back seek past the retention window may restart ffmpeg).
- Direct-play range seeks, seeks near the item's end, and seeking while paused.
- Whether Continue Watching on a real client re-sorts on the 12.2 `LastPlayedDate` change.

## Consequences

- The viewer keeps their place through a pause of any length in the foreground and for 60 minutes after the screen
  stops. The schedule and the other TVs are untouched.
- Pause, resume and seek act on the existing player. The only unavoidable tune is the return after the screen stopped.
- A resume after more than about 5 minutes pays one extra Playing report (+1 `PlayCount`, two activity-log entries)
  and, on a weak server, up to about a second for
  the first segment.
- `Sitting` stops being purely wall-clock: the behind loop works in media time. This is the largest code change.
- **#511 is an open defect, not a decision of this ADR.** The owner's Q-JF-5 rule (no Continue Watching noise) stands
  unchanged. On 12.2 the shipped 30 s check-ins already break it for items with a resume point, pause or not, so F1.5
  on 12.2 depends on #511 and a technical fix is required. Only if no fix exists does it go back to the owner. A
  `jellyfin-contract` tripwire records `LastPlayedDate` after a check-in on each version.
- **Risk:** the design relies on server behaviour outside the API contract (the idle kill, auto-stop and restart on
  request). The `jellyfin-contract` suite gains paused check-in cases on 12.1 and 12.2; the long timers are too slow for
  CI, so SP-7 is re-run against new images before the matrix moves, as SP-6 is. A failed case means this ADR needs the
  spike re-run.
- **Device checks** (measure, do not gate): back 10 s on older hardware for direct and transcode; resume after 1, 5 and
  15 min on each route; whether the Fire OS screensaver delivers `ON_STOP` on API 25 and API 30; a real player's
  behaviour after a pause.

## Amendments

- **ADR-0016.** `VideoPlayer` gains transport (decision 9).
- **ADR-0017, 6.** A screen stop still tears the period down in the same order (close the tuner, detach, release). The
  one change is that the paused place now survives it, in the ViewModel, for `pauseHoldMax`. The next run is passed the
  place. The place is read from `PlayControl` before the teardown begins. Everything else in 6 stands.
- **ADR-0017, 2.** A retune still works the position out from the clock, with one exception: a return within
  `pauseHoldMax` resumes at the kept place. If the place's block has not started by the clock (possible after
  ahead-drift), the place is dropped and the viewer joins live. A place ahead of the clock's live position (paused in
  an ahead-drifted sitting) is clamped to the live position, so the viewer is never ahead of the schedule; a paused viewer is still paused there. A
  block that has already ended by the clock is fine: the viewer is behind and catches up at the next item.
- **ADR-0018.** Added to 1: while paused, progress check-ins carry `IsPaused=true` at the safe position, for up to
  `pauseReportMax`, then stop; Play sends a fresh `Playing`. Added to 4: a resume after the silence starts a new
  `Playing` report, which raises `PlayCount` by 1 and adds two activity-log entries. Changed in 2: watched time is the
  union of media intervals played. The "not covered" note on pausing is now covered, on 12.1 and 12.2.

## Out of scope

- A buffer across items or channels, and going back into the previous item.
- 2x and 4x speeds, and thumbnail scrubbing.
- Keeping the paused place across process death.
- Any change to the schedule.
- Pausing from the guide.
- The transport bar's visual design (the design partner).
