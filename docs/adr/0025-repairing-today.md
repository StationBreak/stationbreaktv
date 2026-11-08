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
- **Every switched-on channel gets a repair**, the leftovers channel included, so the first tune after a change is made
  from the record alone. A day hidden at the change has nothing on air to keep: its tail is laid out from the half hour,
  or, when the new selection gives it nothing either, the whole day is one slate with the new selection's empty reason.
- **A second change on a repaired day.** The record keeps only the tail's items, so the tail's base pool is the
  recorded tail pool plus what the channel admits of the current selection (read for it), less what the record has
  airing before the change. What aired before the first change's kept window is not recorded, so it may come round
  again later that day.
- **The repair fill pool.** In `repairLost`, a block of a repaired day is filled from the tail pool plus what the
  channel admits of the current selection at repair time. On a day with no selection repair, the fill pool is the day
  pool.
- **Fills at lookup** (the naming fill and the stand-in) on a repaired day use the tail pool only, read by id. A lookup
  never builds the day to find a wider pool.

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

When the selection itself is gone (no playable library), nothing more is read from the libraries for a repaired day:
its fill comes from the recorded tail's items only, or the card. A day with no repair still has its built day pool, and
only that pool's liveness (which items still have a row) is read to fill from it.

Each fix is recorded per block, so a lookup reads the record only. A fix belongs to the day as it shows when the
check runs: it carries the seq of that day's selection repair (0 for a day as built), shows only with that repair,
and goes when that repair goes. The check itself is remembered in memory (the
library's content generation, and the end of the earliest channel day it saw), never in the file: it holds until the
generation moves or one of those days ends, and a restart costs one more check in the background. That restart check
runs once per process, after the lineup refresher's start check, or, when that start finds a sync run writing, after
that run ends.

### 5. Repairs are per TV

Each TV keeps its own selection (spec 26 goal 4), so a repair is recorded in that TV's lineup file. ADR-0002's scope
reads **same account, same selection, same recorded inputs**. The repair is a recorded input of the day, like the
selection it follows. Two TVs on one account can differ for the rest of today and agree again at the next boundary when
their selection and library state match. A day gives the same answer to every lookup on one TV (D1's rule, kept).

### 6. Lineup file version 4, and the size rule

The repair record is a new optional `repairs` object in the lineup file. The file is **version 4 only while a record is
held**: a selection repair or a fix. With a selection repair it also holds `lastSeq`, the highest selection seq held,
kept through rolling and trimming while any selection repair is. With neither it is written as version 3, the lowest
version that holds its content. A channel day may hold two selection repairs of different seqs, and never more: the
one shown, and a later one whose selection is being saved (§7). Each fix stores the seq of the repair it patches. A
`repairs` object that does not read loads the lineup with no record, is reported once, and the file is written once
without it after a copy of its bytes is kept (tag `repairs-unreadable`); the lineup is not set aside. The copy is best
effort. Only the first unreadable record is kept, because a later copy never replaces it. When the copy cannot be
written, that is reported and the file is not written then. The next save of the lineup writes it without the record,
and those bytes are lost. The first version-4 save keeps a copy of a version-3 file, with the existing `.bak` rule.
An older app sees version 4 as a newer version, shows a planned default lineup and cannot save over it.

The record must never make a save fail. It is rolled at each save (days that have ended go), and made to fit under the file's
cap with 64 KiB kept free:

- **A repair's own save:** at most 256 fixes stay. When it does not fit, the oldest fixes go first (the oldest day, then the
  earliest block), never one whose block is on air. If it still does not fit, the repair is refused and nothing changes.
- **Any other save of the lineup** (a refresh, say) trims the record instead of failing. The selection repairs above the
  saved seq go first, because they are not shown. Then the oldest fixes not on air go, then fixes on air, then the other
  selection repairs by oldest repair time. A selection repair takes its fixes with it. The saved seq is read before
  the lineup's lock is taken. While a change's save is under way, its seq counts as shown, so its repair is not
  dropped first. When the saved seq is unknown (no provider, or a read that fails), every repair counts as shown, and
  the order is the older one: fixes first, then repairs by age. Dropping a fix or a shown repair also forgets the
  lost-block check, so the next check runs over the days as they then show. If dropping everything still does not
  fit, the record is emptied.

What was dropped is reported as three counts and nothing else. A dropped selection repair means that day is then built
from the saved selection, which can change the programme on air. This only happens near the file cap.

### 7. The concurrency contract

The repairer is one instance per process. Its calls take turns (one `Mutex` inside it) and run on the background save
thread, never the caller's. Each plans from the record it read, and writes only if the lineup's revision and the
record's selection repairs are still those it read. Each call says what it did: `Saved`, `Stale` (the lineup moved on;
the caller retries once after a fresh read), `Refused` (the record could not be saved), `Unsaved` (the selection
could not be saved), `UndoFailed` (the selection could not be saved, and its repair could not be taken back out),
`SelectionUnknown` (the saved selection could not be read, so nothing was changed), `AlreadySaved` (`dropSelection` was
given a seq that is saved, so nothing was dropped) or `NoLineup`.

The schedule source reads the saved selection and its seq together, from one provider: a cheap, in-memory read on the
tune path, and by the repairer under `NonCancellable`, so it must not block. Once the caller's save returns true, or
its write has committed, the provider gives the new seq. Part 4's in-memory read reflects the write before the save
returns. A non-null read always carries the seq actually saved, never a default 0 once a seq is stored. A null read
means the selection is unknown: the repairer then records, drops, undoes and checks nothing. Per channel day the source shows the repair of the highest
seq at or below the saved seq, with that repair's fixes only. A day that shows no repair gets the fixes of its build. A
later repair is held back with its fixes. One that a saved later seq has superseded is pruned, with its fixes, at the
next write of the record or at a roll.

The repairer owns the whole of a selection change, in one turn, so a second change never starts before the first is
saved:

1. Work out the seq: N = max(the record's `lastSeq`, the saved selection's seq) + 1, so N is above every seq a shown
   repair has used. A seq whose repairs are gone (rolled off with their day, or undone) can come round again, because
   nothing of it is left to show.
2. Record the repair of seq N beside the repair each day shows now. The shown repair keeps its fixes and stays until N
   is saved. Repairs of a change never saved go, with their fixes. This runs while the removed libraries' rows still
   exist.
3. Call the caller's save: its one write of the new selection and seq N (Part 4's settings write).
4. If the save returns true, the repair shows. If it returns false or throws (a cancellation included), the repairer
   reads the saved seq again, outside cancellation. When the saved seq is N or above, the write committed, so nothing is
   undone. A false result then counts as `Saved`. A throw after the write committed leaves the repair in place, and
   the throwable still reaches the caller. Otherwise the record is put back as it was, so each day shows the repair it
   showed before, with its fixes. The outcome is `UndoFailed`, and it is reported, when the undo's write fails or when
   the saved seq cannot be read again (the read throws or gives null). Nothing is undone then. The repair stays held
   back above the saved seq.

A crash between 2 and 3 leaves the repair unseen, and the next change drops it. Part 4's start-up resume can also
finish the save or drop the repair (`dropSelection`, which takes the change's fixes with it, and drops nothing for a
seq at or below the saved one). Each lost-block check
starts by dropping every selection repair above the saved seq, with its fixes. No change is being saved outside the
repairer's turn, so those can only be left by a crash or by a failed undo.

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
- The lineup file has a fourth version, and a version-4 file exists only while a record (a selection repair or a fix) is
  held. An app older than this one cannot save a lineup it wrote while one is held.
- A trimmed record loses a repair: a selection repair dropped by a save of another kind means that channel day is built
  from the saved selection, which can change the programme on air. This only happens when the file is close to its cap.
- A failed selection save puts the record back as it was. A change dropped later (`dropSelection`) gives each day back
  the repair it showed before, together with that repair's fixes of lost blocks. The record holds those fixes beside
  the change until the change is saved.
- A second change on a repaired day may repeat a programme that aired before the first change's kept window.
