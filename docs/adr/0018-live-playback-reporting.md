# ADR-0018: Playback reporting for live channels (spike SP-6 result)

- **Status:** Accepted
- **Date:** 2026-10-25
- **Decided by:** the owner (Q-JF-5, 2026-10-25: the dashboard shows the TV as playing, no Continue Watching noise, spike first) and Rincewind (the mechanism and the 30 s dwell)
- **Resolves:** Q-JF-5 ([ASSUMPTIONS.md](../ASSUMPTIONS.md)); the F1.5 reporting design
- **Informs:** F1.5 ([BACKLOG.md](../BACKLOG.md), S1.5.1), ASSUMPTIONS Q-JF-5

## Context

A live channel is a sequence of items the viewer joins part-way through and often leaves again within seconds
(ADR-0017). Reporting that viewing to Jellyfin in the usual way has two effects the owner does not want. A positioned
progress report saves a resume point, so Continue Watching fills with items the viewer only passed. A stop with no
position marks the item played. The owner also needs the Jellyfin dashboard to show a TV as playing, so that server
maintenance does not cut off a viewer.

The owner decisions of 2026-10-25 (Q-JF-5):

- An item counts as **watched** only when at least 90% of it was actually viewed. The 90% is configurable.
- Flipping past an item never counts.
- The dashboard shows the TV as playing.
- Channel viewing leaves no Continue Watching or resume-point noise.
- The design is proven by a spike before it is recorded.

Jellyfin's behaviour that matters, from `Emby.Server.Implementations/Session/SessionManager.cs` and
`Library/UserDataManager.cs` on master:

- `OnPlaybackStart` adds 1 to `PlayCount` and sets `LastPlayedDate`.
- A progress report with `PositionTicks` runs `UpdatePlayState`. It uses `MinResumePct` 5, `MaxResumePct` 90 and
  `MinResumeDurationSeconds` 300 to save a resume point or mark the item played. A progress report without a position
  skips it.
- `OnPlaybackStopped` with `Failed=true` returns before touching user data. A stop with no `PositionTicks` marks the
  item played.
- An idle check stops a playing session whose last manual check-in is older than 5 minutes, using the position of that
  last check-in.

These rules are server code, not part of the API contract.

## Spike

**Images.** The same two as ADR-0015: Jellyfin 12.1.0 and 12.2.0, at the digests the `jellyfin-contract` workflow
pins. Results were identical on both.

**Harness.** [`scripts/spikes/sp6-playstate.sh`](../../scripts/spikes/sp6-playstate.sh) starts one throwaway container
on loopback, with synthetic 420 s episodes. Modes: the default run, and `SP6_ONLY=followup|crash|safe`. Results are in
`scripts/spikes/sp6-*results-12.1.tsv` and `sp6-*results-12.2.tsv`. No owner server or credential was used. The script
can be re-run against any image.

### Results

**Round 1: real position, positionless check-ins, failed stop.** `Playing` with the real position, positionless
check-ins, then `Stopped` with `Failed=true`:

- No resume noise; an existing resume point and the Played flag are kept.
- But positionless check-ins null the dashboard position.
- A positioned progress report saves a resume point even when the stop is `Failed=true`.
- A stop without `PositionTicks` marks the item Played.
- Every tune adds a `VideoPlayback` and a `VideoPlaybackStopped` activity-log entry.
- With no check-ins, the server auto-stopped after about 8 minutes and saved a resume point at the `Playing` position.

**Round 2: `Playing` at 0, no check-ins.** The auto-stop (about 10 minutes) was clean for an unwatched and a played
item, but it **wiped an existing resume point**.

**Round 3: positionless check-ins, then silence.** The auto-stop **marked the item Played** and wiped an existing
resume point. `Playing` at the existing resume position with no check-ins kept it.

**Round 4: the design.** `Playing` and positioned check-ins at a **safe position** (the item's existing resume position,
else 0), then `Stopped` with `Failed=true`:

- User data was unchanged for an unwatched item, an item with a 50% resume point, and a played item.
- User data was also unchanged when the TV went silent and the server auto-stopped (about 10 minutes).
- Marking the item played mid-visit (`POST /UserPlayedItems/{id}`) held.
- The dashboard showed `NowPlayingItem` throughout, with a position from the safe position to the safe position plus
  about 21 s.

## Decision

1. **Report a live sitting at a safe position, never the real one.** The safe position is the item's existing resume
   position, else 0. For each reported visit:
   - `POST /Sessions/Playing` with `PositionTicks` set to the safe position.
   - `POST /Sessions/Playing/Progress`, positioned at the same safe position, every 30 s. This is well under the
     5-minute idle check.
   - `POST /Sessions/Playing/Stopped` with `Failed=true` and the safe position when the viewer leaves the item (a
     flip, a cut to the next block, or leaving live TV).

   The app never sends the real position, never a positionless progress report, and never a stop without a position.
2. **The app counts the watched time itself.** It counts the seconds actually watched per item. At 90% or more
   (configurable, default 90) it calls `POST /UserPlayedItems/{itemId}`. Time on the same airing adds up across
   flips away and back; flipping past an item never counts. An item with no known runtime is never marked. After the
   mark, the safe position becomes 0, as for any played item, so a later check-in cannot write the old resume point
   back onto it.
3. **Reporting starts only after the viewer stays on a channel for 30 s** (configurable). This is a technical call by
   Rincewind: a flip through several channels then adds no play counts and no activity-log entries. The gate is the
   channel, not the item: once the viewer has settled, the next program on the same channel is reported straight away.
   Reporting never delays a tune or a flip: it runs outside the sitting, in order, with a short timeout per call.
4. **Accepted costs.**
   - `PlayCount` rises by 1 and `LastPlayedDate` is updated for each reported visit.
   - Each reported visit adds two activity-log entries.
   - The dashboard progress bar is wrong: it shows the safe position plus up to about 20 s.
   - `Failed=true` is a semantic stretch of the API: the playback did not fail.
5. **Risk and guard.** The design depends on server behaviour that is not in the API contract. The
   `jellyfin-contract` CI suite pins what it can: the F1.5 implementation adds contract tests on 12.1 and 12.2 for the
   safe-position sequences (unwatched, resume point, played), marking played followed by a check-in at 0, and the
   dashboard's `NowPlayingItem`. The server's idle auto-stop takes about 10 minutes, too slow for CI: the SP-6 script
   (`SP6_ONLY=crash` and `safe`) covers it and is re-run against new images before the contract matrix moves.

## Consequences

- The dashboard shows the TV as playing for the whole sitting, so server maintenance warnings see an active viewer.
- Continue Watching and resume points are unchanged by channel viewing, including when the TV loses power or network
  and the server auto-stops the session.
- An item watched through on a channel is marked played by the app, not by the server's position logic.
- A dwell of 30 s and a 30 s check-in interval are defaults and belong in configuration, not in code constants
  (the safe defaults rule; the 90% too).
- F1.5 changes shape: S1.5.1 in [BACKLOG.md](../BACKLOG.md) no longer reports real positions every ~10 s. It
  reports the safe position every 30 s.
- A future Jellyfin version may change the idle check or `Failed=true` handling. A contract-test failure then means this
  design needs the spike re-run.
- **Not covered:** the spike did not test pausing within a visit, or accounts with non-default watch-state settings.
