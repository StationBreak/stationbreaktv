# ADR-0017: Live channel playback

- **Status:** Accepted
- **Date:** 2026-10-23
- **Decided by:** the owner (Phil, 2026-10-23) for how a dead item, a stand-in and the drift behave and for how the screen looks; technical calls under the owner's standing instruction for the rest (marked in the text)
- **Revisit:** if a later spec lets a viewer flip channels (F1.4) or call up the banner (F2.2), when the input shape under "Future input" is built; if stand-ins ever need to agree across TVs whose libraries differ; if the shared channel manager in #354 is built
- **Informs:** spec 14 (offset playback, F1.3), ADR-0016 (the player's lifecycle seam), `docs/rules-format.md` (the stand-in note under the pool's cut-off rules), BACKLOG S1.3.1 to S1.3.3, ASSUMPTIONS Q-SCH-3

## Context

Spec 12 built the lineup, spec 13 built and proved the player, and ADR-0016's 2026-10-23 update gave a screen a way to
pre-resolve the next item and to learn when a play ends. Spec 14 joins them: once setup is finished the app opens on a
channel and plays what the schedule says is on, at the position it would be at if the channel had been running all
along, then carries on into the next block. The scheduler plans from the runtimes in the library's metadata and never
reads playback, so the real length of a file can differ from its block, and a file can fail altogether. This ADR records
what the screen does then, and the few structural choices that go with it.

## Decision

1. **The schedule wins (Q-SCH-3).** A show that ends before its block ends is followed by the StationBreak ident card
   (the test card with an "Up next" banner) until the block ends. A show that runs past its block is cut at the block
   boundary. The next block starts on the clock, whatever the file did. The one deliberate exception is the drift after
   a dead item (2).
2. **After a dead item the channel drifts for this sitting only.** An item that cannot play is retried once under the
   snow, then the "Technical difficulties" slate shows for about 3 seconds. If the rest of its block is short (see 3),
   the channel moves straight on to the next show from its start, and from then on this channel runs ahead of the
   schedule on this TV: each show plays through in order and the banner says what is actually on. The schedule itself
   never moves. **Any retune** (a channel change, the app reopening, the TV waking from standby, the screen coming
   back from the background) works the position out from the clock again and lands back on the schedule. Nothing is
   paid back, and other TVs are never affected. A drifted sitting is limited to the schedule already built ahead
   (`ChannelOnAir.builtUntilMs`, the end of the next day by the channel's own epoch length); if the drift reaches it,
   the channel re-anchors to the clock.
3. **The 30-minute rule is applied per tune, by the time left at that moment.** If the time left in the dead block is
   at or under the threshold (default 30 minutes, a configurable default in `LiveTimings`), the channel moves on
   and drifts. If it is over, a
   stand-in fills the rest of the block and the channel is back on the schedule at the next block. Each TV decides for
   itself from the clock; TVs never talk to each other. So a long film that fails with 70 minutes left gets a stand-in on
   one TV, and a second TV that tunes the same channel with 30 minutes left, finds the same failure and simply moves
   on. A TV already playing a stand-in keeps it until the block ends; it does not decide again. The dead item stays in
   the schedule and is tried again when it next airs.
   The tunables of the live screen are all defaults in `LiveTimings`, in code and not user-configurable: banner linger
   4 seconds, minimum snow 0.5 seconds, slate hold 3 seconds, stand-in threshold 30 minutes, stand-in attempts 2, and
   pre-resolve lead 45 seconds.
4. **Stand-ins are deterministic and client-local.** A stand-in is an item from the same channel's pool, chosen from
   the channel's settings, the broadcast day, the dead block's start, the library's contents and the set of items
   already tried. It plays as if it had been scheduled in that block all along: it is joined at the clock position
   (now minus the block's start), not from its beginning, and it is cut at the block boundary. If it is shorter than the
   block, a second one is chained the same way. The banner shows it marked "Filling in". If a stand-in also fails one
   more is tried, then the ident card holds until the block ends. TVs with the same library and the same failures pick
   the same stand-in and show the same picture. TVs whose libraries or failures differ may differ for that block, and
   each shows what it can play. A stand-in is player behaviour, not part of the rules format or of the Day vectors; the
   rules document only notes it as non-normative.
5. **The banner shows wall-clock times in the device's local time zone, with daylight saving.** The schedule's own
   clock is a channel's fixed UTC offset (spec 07's broadcast day, Q-SCH-1), so a block's time slot is
   converted for display only: a viewer sees the times on their own clock, and "joining N min in" comes from the offset.
   The conversion never feeds back into the schedule. (Technical call.)
6. **The player belongs to a started period, not to the ViewModel.** The screen makes a player when it becomes STARTED
   and ends it when it stops, in this order: close the tuner (so any server encode ends), detach the player from the
   surface, release it (ADR-0016, 5). Backgrounding or standby therefore stops everything at once, and coming back
   starts a new player and a new sitting, anchored to the clock. An unexpected cancel while the sitting is running
   starts a fresh sitting rather than leaving a frozen screen; the machine stops only when the session has ended.
7. **The schedule source is main-safe.** `ScheduleSource` is read from the main thread by a screen, and building a day
   is real work (seconds on the slowest device), so the built-in source switches to an injected dispatcher
   (default `Dispatchers.Default`) for its work. Its timings are logged as numbers only (the day's index and the
   milliseconds), never an ID or a title.
8. **The last channel is saved on every tune** in a small file per account holding only the channel ID (S1.4.2). The
   first time (nothing saved, or the saved channel is not on the air), the first of these with something on now is used
   (#375): the lowest-numbered unhidden channel whose rating ceiling is the PG tier (`RatingLadder` rank of PG, so
   TV-PG counts too), else the lowest with any ceiling, else the lowest. A little kids' channel is not the first thing
   a new owner sees.
9. **The app opens on the live screen.** The setup-complete placeholder is gone. After setup, and on every launch with a
   finished setup, the launch router sends the app to the live screen with nothing beneath it, so Back leaves the app.
   A session that ends while watching goes back to the launch router, which decides where the viewer goes.
10. **The shared channel manager is a later idea.** Letting TVs agree on a stand-in or a drift would need a shared
    place to keep that state; a Jellyfin plugin acting as a channel manager is parked in
    [#354](https://github.com/StationBreak/stationbreaktv/issues/354). Spec 14 needs none of it, because every
    decision above comes from the clock and the library.

## Future input

F1.4 (flipping) and F2.2 (Select for the banner) need a way to send the live channel a command. Spec 14 does not build
it. The intended shape, so that it is not a rewrite later: `run(requests: Flow<LiveCommand>)`, with the machine
collecting with `collectLatest` so that a newer command replaces the sitting the older one started. The cleanup of a
sitting (cancelling its watchers and cut timers) stays separate from `tuner.stop`, since a flip keeps the player and
the picture up until the new one is ready. The current banner is kept across a command that does not change what is
on, such as asking for the banner again.

## Consequences

- A viewer sees a channel that matches the guide, apart from a deliberate, bounded drift that any retune clears.
- Two TVs in one home can show a different stand-in only when their libraries or failures differ. That is accepted.
- The banner's times are for people; the schedule's are for the clock. The two are never mixed.
- The screen and the schedule source are read on the main thread safely, and leaving the screen never leaves an encode
  running on the server.
