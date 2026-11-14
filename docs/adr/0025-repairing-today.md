# ADR-0025: Repairing today (spec 26)

- **Status:** Accepted, 2026-11-08 (proposed 2026-11-07; see the amendments below for what was built)
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
runs once per process, after the lineup refresher's start check, or, when that start finds a sync run writing, at the
first trigger after that run ends.

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

## Amendments 2026-11-08: as built (spec 26, Parts 2 to 5)

The decision above stands. These amendments record what the code does where the plan was open or has since changed.
The parts are [#649](https://github.com/StationBreak/stationbreaktv/pull/649),
[#652](https://github.com/StationBreak/stationbreaktv/pull/652),
[#655](https://github.com/StationBreak/stationbreaktv/pull/655),
[#673](https://github.com/StationBreak/stationbreaktv/pull/673) and [#676](https://github.com/StationBreak/stationbreaktv/pull/676) (Sync now).

### A1. Holds and add stamps

- **An add stamp, not a token.** Ticking a library stamps it with the seq of the change that saved the tick (`addStamp`;
  the settings store `adding=id:stamp,...`). Every stamp is at or below the saved seq, and a library is never both chosen
  and adding.
- **The seq is floored above every stamp.** Each allocation takes `max(next seq, highest stamp in the cache + 1)`, read
  under the selection's lock, so no seq can equal a stamp left in the cache. The repairer's `selectionChanged`,
  `saveWithoutRepair` and `resetSelection` all take this floor (`atLeast`, no default).
- **Holds** come from `heldPins`: the shown pins with `pinEnd + pauseHold` still ahead. A hold given for a library that
  already has one is stored as the later of the two. A held library loses its stamp and its walk proof, so it never
  lands. Re-ticking a held library bumps nothing until it lands. A library that holds no pinned programme is deleted
  at once.
- **A tick, an untick and the add are three steps in one order**, so a crash between any two leaves a state the next start
  repairs: the repair record, then the settings (the truth), then the library cache.

### A2. The two-read listing rule

A chosen or adding library that one listing leaves out is noted as missing and kept. It is dropped only when a later
listing, from a different pass and at least `SelectionConfig.missingConfirmAfter` after the first miss (3 hours, a
default), leaves it out again. A library the next listing gives clears its miss. When every chosen library is confirmed
missing the selection is **gone**: the choice is kept, nothing is playable, the guide and live say so, and a listing
that gives any chosen library back ends the state. While the selection is gone, a stay-current run makes no pass, no hold
expiry and no rewalk, and still lists. Two reads exist because a listing can miss a library for a while.

### A3. Re-id rules and tolerance

`LibraryRepository.successors` matches a gone item to where it came back, among the live rows of the playable libraries
only. A candidate is accepted on, in order:

1. a shared identity id (`imdb`, `tmdb` or `tvdb` only) with no identity key that disagrees;
2. else an episode of the same series, season and episode;
3. else a film of the same title and year.

A disagreeing identity id rules a candidate out of the two name rules. Every match also needs a runtime within 2 minutes
or 2% of the gone item's, whichever is larger, exactly one candidate, and no other gone item that claims or sees it. An
unknown runtime on either side fails the check. When the rules do not give one answer, the block is gap-filled. A
wrong successor would put a different programme in the slot, so the rules lean to no match.

### A4. Schema 10, then schema 11 and item ownership

- **Schema 10** added `providerIds` to `item` and `series`, and `heldUntilMs` and `addStamp` to `library`. Its migration
  sets `backfillPending` so the studios re-read fills the provider ids. A row tombstoned before the upgrade is never read
  again and keeps no provider ids until it is revived or purged. Such a gone item has nothing to match on and is
  gap-filled.
- **Schema 11** added `walkedStamp`, `rewalkPending` and `coveredFromMs` to `library`, the `item_also_listed` table and
  an index on `item(serverId, userId, seriesId)`. Its migration sets no flag and inserts nothing.
- **Item ownership.** An item that two libraries list has one owner row. Every other library that lists it has an
  `item_also_listed` row, so deleting one library's rows never takes an item that another chosen library lists. The
  invariant is that `(item, owner)` never appears in the table and every other lister has a row. Before a library's rows
  go (untick, a hold ending, an add dropped), each item it owns that a staying library lists moves to that library, as a
  versioned move: a chosen library first, then one being added, then a held one, then the smallest id. A chosen
  library's write takes an item from an owner that is being added, and any writer takes a tombstone. An item owned by a
  held library and listed by a chosen one is off the chosen library's channels until the hold ends. That is accepted.
- **Where the rows come from.** The first ID reconcile finds the items that two libraries already shared before the
  upgrade; there is no backfill read. A hold that expires at the first start after the upgrade, before that reconcile,
  deletes rows instead of moving them, as it did in schema 10.

### A5. One writer of the selection

`LibrarySelection` (`core:domain`, `selection` package) is the only writer of the stored selection. Features reach it
through `LibraryChoices` (the guide and live through the read-only `LibrarySelectionView`), setup through `LibrarySetup`, and the workers through narrow ports (`AddLandings`,
`FirstSyncLibraries`, `ServerListings`, `HoldExpiry`, `SelectionResume`).

- **The lock and the order of stores.** One lock in this order, and no path takes two out of order: the selection's lock,
  then the repairer's turn, then the lineup's entry lock, then the settings' locks. Cache transactions are leaves. Stores
  are written in the order: repair record, settings, cache. A cache write that fails after its save is put right by the
  next operation, before anything else. A re-entry into the selection or the repairer from inside their own lock throws.
- **`SelectionReady`.** The resume at start finishes first, and every selection operation and the first-sync,
  stay-current and add workers wait on it (the resume itself is exempt). It is open however the resume ended.
- **The resume** raises the seq above the cache's stamps, branches each stamped chosen row (a row whose walk is proven
  is landed; one whose walk is not is set back), writes the cache from the settings, expires holds that have ended and
  asks for the add pass and the first sync when there is work.
- **Setup** writes the new choice under a reset seq (`selectionResetSeq`), which hides every repair of an older seq, and
  starts the first sync, so a same-account setup walks its libraries. A newer-version lineup record is left untouched.
  It is hidden by the reset seq, not deleted.

### A6. The add pass and its walk proof

An added library is walked under its stamp by `LibraryPass`, the one walk of a library. Its pages move no generation.
Its last stamp-checked transaction records the proof that the walk ended (`walkedStamp`) and the walk's start
(`coveredFromMs`). The runner never lands a library. It hands it to the selection (`passLanded`), which lands it only
while the library still has that stamp and that proof: the repair and the saved selection come first, then the cache,
and the generation moves once. Every write of a walk names its library and stamp and is checked inside its own
transaction, so a library dropped, held or restamped since the walk began takes nothing. Pages with `walkedStamp`
unequal to `addStamp` are unproven: they never land.

### A7. The rewalk

A chosen row whose stamp was never proven (found at the start, at setup, or by the first sync) is set back, not landed:
no stamp, pending, marked `rewalkPending`. A stay-current run whose passes all completed walks each marked, listed
library from its checkpoint after holds expire, using that run's listing. A failure keeps the mark and is retried the
next run, and never changes how the run ended. A marked library the listing omits keeps its mark and is skipped; the
mark goes when the library leaves the choice. The rewalk stops while a first sync runs, which takes the library
itself. `coveredFromMs` keeps the window open: until the next incremental pass has walked a library, that pass reads
from the library's `coveredFromMs`, and only that pass clears it.

### A8. Hold expiry

Holds expire after each stay-current run whose incremental pass completed, and at the start. The cache trusts the clock
for expiry only within the pause hold (60 minutes) of the last successful sync. After a longer gap, an ended hold waits
for the next incremental pass, which is at most about 6 hours.

### A9. A title added mid-day (round 4)

A title added mid-day gets its home only. Guest spots wait for the next boundary. §3 says the same; it is repeated
here because Part 2's review fixed it as a ruling.

### A10. Sync now

Sync now is the second trigger. `RunnerSyncNow` (behind the `SyncNow` port) lists the server once per press, and a
failed listing ends the press as `Failed` with nothing written. Otherwise it runs stay-current's passes with
`StayCurrentRunner.runWaiting`, which waits for the gate instead of skipping, so it never runs beside a background run
and one press never runs two. It hands the press's own listing to the run, skips the runner's own follow, and expires
holds. A completed run is `Done`, a failed one is `Failed` with its reason, and a cancelled one returns to `Idle`. A
press while one runs starts nothing. Lost blocks are repaired by the lineup refresher's hook once the run's writing
ends, as for a background pass.

## Amendment 2026-11-13: the station-break block in the record

Rules format 5 (the spread end of the day) adds a station-break block (`ScheduleContent.Break`). A repaired day keeps
its breaks, so the record stores them. A kept block is now a programme (an item from a media start), a slate or a
break, and in the `repairs` object a break is the block index **-2** (a slate is -1). A slate and a break write the
media start a slate writes, 0. The wire layout is otherwise unchanged. The digest marks a break with `~` and a slate
with `-` as before, so the digest of a record of programmes and slates is unchanged.

**The -2 stays inside lineup file version 4.** This is an explicit exception to the codec's rule that any schema change
bumps `LineupCodec.VERSION` (owner decision 2026-11-13, option a). The `repairs` contents are a per-TV record that the
next repair rebuilds, and a bump would make every older build show a planned default lineup and refuse to save.

The real downgrade radius. Only a channel whose rules are at `formatVersion` 5 lays out breaks, so only such a channel
can put a -2 in the record. An older build already shows that channel as unusable (off air, its source kept byte for
byte), and that is the main thing a viewer sees after a downgrade. The record drop is secondary: the older build reads
the file as version 4 as before, but its `RepairsCodec.decode` throws on the first -2 row (an index outside the
table). `LineupCodec.repairsOf` then drops the **entire** per-TV record (`TodayRepairs.NONE`), not only the channel
with the break. As for any unreadable record (§6), the older build reports `LineupEvent.RepairsUnreadable`, so a report
after a downgrade is expected and is not a bug. It keeps a copy of the file's bytes in the one-time
`repairs-unreadable` slot (`UNREADABLE_RECORD_COPY`; an earlier unreadable copy is never replaced), and then writes the
file at once without the record. On that TV every channel's selection repairs and lost-block fixes are lost until the
next repair, so a repaired day goes back to its day as built from the saved selection, which can change the programme
on air. The lineup itself loads, and is not set aside.

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
