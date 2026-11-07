# ADR-0025: Repairing today (spec 26)

- **Status:** Proposed, 2026-11-07
- **Date:** 2026-11-07
- **Decided by:** the owner (spec 26 goals, approved 2026-11-07: the library selection is a setting, a changed selection
  updates the rest of today, a gap is always filled, each TV keeps its own selection) and the planning session (the
  mechanism and the calls below)
- **Addresses:** [#632](https://github.com/StationBreak/stationbreaktv/issues/632) (update library now),
  [#495](https://github.com/StationBreak/stationbreaktv/issues/495) (library changes after setup) and
  [#633](https://github.com/StationBreak/stationbreaktv/issues/633) (blocks whose item left the library)
- **Builds on:** [ADR-0002](0002-determinism-scope.md), [ADR-0017](0017-live-channel-playback.md) (the stand-in),
  [ADR-0023](0023-lineup-curation.md) (D1, which this relaxes for three triggers only)
- **Informs:** spec 26

## Context

ADR-0023 D1 says nothing changes another channel's rest of today: every change waits for the next broadcast-day start.
That was right for catalog and curation changes, which the viewer did not ask for. It is wrong for three things the
viewer does or the server does underneath the app:

- The viewer changes the library selection in Settings. Today the only way to do that is a reset and a new setup.
- The viewer presses Sync now to catch a server-side change.
- The background pass finds that items left the library, or came back under another id.

Until the next boundary the guide shows blank blocks for the items that left, and the live screen has a dead slot. A
block whose item has left the library also has no row left to retry, so the stand-in (`StandIn.pick`, which keeps
trying items until the runtimes cover the block) has nothing to work from.

The constraints are the ones of spec 26: the programme being watched must never be cut, a failed sync changes nothing, the
tune path gets no new work, and the oldest supported hardware must not get noticeably slower.

## Decision

### 1. Three triggers, and only these

A repair runs for: (a) a selection change in Settings, (b) Sync now when it finds changes, and (c) a background pass that
changed the library. Only these relax D1. Every other change in ADR-0023 §3 still waits for the boundary: catalog
updates, new rules, the on-set, the household setting.

### 2. What stays, and the recorded tail

- **The programme on air stays.** On every channel the programme on air keeps playing to its end. A gone programme on
  air is filled at once from the day pool, as the schedule source fills it now.
- **The kept window** runs from the half hour at or before the repair (never before the day's start) to the end of the
  programme on air, exactly as the viewer sees it. The end of the programme on air is the **pin**. A selection change
  that removes the pinned programme's library holds that library's rows until the pin ends, plus the pause hold
  (`RepairConfig.pauseHold`, 60 minutes).
- **The rest of the day is a recorded tail.** On a selection change it is laid out again by the scheduler from the pin's
  end. The kept window and the tail are stored as one `ChannelRepair` per channel day, the latest of a day winning, so
  every later lookup and rebuild on that TV reads the same blocks. Nothing is worked out again per lookup.
- **The repair fill pool.** On a repaired day, a block is filled from the tail pool plus what the channel admits of the
  current selection at repair time. On a day with no selection repair, the fill pool is the day pool.

### 3. Frozen rules and no re-homing

A repair changes what is in the pool and nothing else. Each channel keeps today's rules, keepers and basis.

- **Homing runs over the day's frozen curation** (`Scheduled.curation`), through the build's own homing
  (`LineupDays.homesFor`), not a fresh curation.
- **A title already airing keeps its channel.** A new item of a title that is on the air today goes only to a channel
  that already holds that title in its day pool.
- **A title added mid-day gets its home only.** Guest spots wait for the next boundary (ADR-0023 §1: guests).
- **Leftovers follow the build's own claim set:** curated claims plus earlier leftovers, with custom channels claiming
  too, exactly as the build does. A repaired tail then matches what tomorrow's build does with the same titles.
- **A custom channel or an edited starter** takes everything its rules admit of the added libraries. It never loses a
  title.
- **Every candidate is checked again** against the channel's rules for the day and the kid and grown-up holds (the build's
  own admitted pool), so a repair never widens what a channel may air.

### 4. A gone item is filled, beside the stand-in

An item that left the library gets a **gap fill** (`GapFill.pick`), not `StandIn.pick`. They share the seed idea (a hash
of the channel, the block start and the item id) and differ on purpose:

| | `StandIn.pick` | `GapFill.pick` |
| --- | --- | --- |
| Used for | a block that cannot play now | a block whose item has left the library |
| Choice | in hash order, until the runtimes cover the block | the longest item that still fits what is left, ties by hash |
| End of the block | may run past it (the last item is clipped) | never runs past it |

Two reasons for the second. A gone item has no row to retry, so no retry loop is needed. And a fit beats a clipped piece:
the viewer sees whole programmes ending when the slot ends. Time that nothing fits stays recorded as the gone item's rest and is never filled again
(the guide and live wording for it is the design partner's, spec 26).

A successor (the item came back under a new id) is tried first, and only when it passes the same rules and holds. It airs
in the block's slot from the block's own media start. Otherwise the block is filled. The fill never picks the gone item,
an item with no current row, or an item of the fix being filled around.

When the selection itself is gone (no playable library), nothing is read from the libraries. The fill comes from the
recorded tail's items only, or the card.

Each fix is recorded per block with the library's content generation it was checked at, so a lookup reads the record only.

### 5. Repairs are per TV

Each TV keeps its own selection (spec 26 goal 4), so a repair is recorded in that TV's lineup file. ADR-0002's scope
reads **same account, same selection, same recorded inputs**. The repair is a recorded input of the day, like the
selection it follows. Two TVs on one account can differ for the rest of today and agree again at the next boundary when
their selection and library state match. A day gives the same answer to every lookup on one TV (D1's rule, kept).

### 6. Lineup file version 4, and the size rule

The repair record is a new optional `repairs` object in the lineup file. The file is **version 4 only while a repair is
held**. With no repairs it is written as version 3, the lowest version that holds its content. The first version-4
save keeps a copy of a version-3 file, with the existing `.bak` rule. An older app sees version 4 as a newer version, shows
a planned default lineup and cannot save over it.

The record must never make a save fail. It is rolled at each save (days that have ended go), and made to fit under the file's
cap with 64 KiB kept free:

- **A repair's own save:** at most 256 fixes stay. When it does not fit, the oldest fixes go first (the oldest day, then the
  earliest block), never one whose block is on air. If it still does not fit, the repair is refused and nothing changes.
- **Any other save of the lineup** (a refresh, say) trims the record instead of failing: the oldest fixes not on air first,
  then fixes on air, then selection repairs by oldest repair time. The lost-block generation always stays.

What was dropped is reported as three counts and nothing else. A dropped repair means that day is built as the build
would, which is what the day looked like before the repair.

### 7. The concurrency contract

Callers serialise selection repair and lost-block repair (one `Mutex`). Each plans from the record it read, and writes only if
the lineup's revision and the record's selection repairs are still those it read. Otherwise it writes nothing and tells
the caller. A selection change **saves the new selection only after the repair is recorded**, and the repair runs while
the removed libraries' rows still exist. If the repair could not be saved, the selection is not changed.

## Consequences

- The rest of today follows the viewer's change, a Sync now or a background pass, and no block is blank. The tune path reads
  the record and does no repair work.
- D1 is relaxed for three triggers and no more. Everything else about ADR-0023 §3 stands.
- **A title added mid-day airs on its home channel only.** It does not guest, and it does not claim a place on a channel
  that has a different home for it, until the next boundary.
- **A title that moves channel under the after-selection homing starts airing there from the next day.** Its new episodes in
  an added library go only to the channel that already airs it today, because a title on the air today is never homed again.
- A repair's fill pool is bigger than the day pool, so a repaired channel can air a title the original day did not hold.
  Every one has passed the rules for the day and the holds.
- Two TVs on one account can differ for the rest of a day. ADR-0002's scope is now "same account, same selection, same
  recorded inputs".
- The lineup file has a fourth version, and a version-4 file exists only while a repair is held. An app older than this
  one cannot save a lineup it wrote while a repair is held.
- A trimmed record loses a repair: a selection repair dropped by a save of another kind means that channel day is built
  as the build would. This only happens when the file is close to its cap.
- Callers owe the serialisation in §7. A caller that skips it gets a repair that writes nothing, not a corrupt record.
