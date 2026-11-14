# Spread the end of the day through the day

- **Date:** 2026-11-13
- **Size:** project
- **Decided by:** Phil Critchfield (marked "my call" where Claude decided)

## Intent

Every channel's broadcast day ends at 06:00 (`LineupDefaults.DAY_START`). When the next show won't finish before 06:00,
the planned end of the day (rules-format "The planned end of the day") packs in whatever still fits. On real channels
that is a 15 to 20 minute pile of shorts, then an "OFF AIR" slate until 06:00. The owner is often up at 04:00 to 05:00,
so that is exactly when they hit the pile and the dead air and have to leave the app or wait it out. Other early risers
will hit the same thing.

The day should look planned. The leftover time should be spread through the day as short breaks, and there should be no
dead air before 06:00.

## Goals

- The day's leftover time is no longer gathered at the end. It is spread as **breaks at show boundaries**, roughly
  evenly through the day.
- The shorts that today pile up at the end play **inside those breaks** instead.
- The time the shorts can't fill exactly is shown as a **station-break card** (channel ident plus "Up next") inside the
  breaks, not as an "OFF AIR" slate at the end.
- The day still ends at 06:00 on a show boundary. Nothing runs past it and nothing is cut.
- It stays **stateless**. A day is still a function of rules, snapshot, seed, time and app version (ADR-0002), so two
  TVs on one account still agree.

## Non-goals

- Shows running past 06:00 or days chaining onto each other (option C). Rejected because two TVs could drift apart
  for days.
- Shorts as all-day interstitials, with the pool split into main shows and shorts (option 2). It could come later, on
  top of this.
- Filler from a separate source (the reserved `filler` section of the rules format).
- Gaps before grid marks in the middle of the day (`alignToGrid: true` channels) stay as they are.

## Anti-goals

- Cutting the start or end of any programme.
- A day whose start depends on the previous day.
- Breaks that feel random or too frequent, such as a card after every episode.
- Wrecking series order on in-order channels.

## Approaches

- **A + spread (picked):** lay out the day as now up to the last show that fits. Choose the shorts that fill the
  leftover most closely, then spread them and any remaining time as breaks through the day.
- **A, packed tighter:** the same best fit, left at the end. Rejected by the owner because the end still feels crammed.
- **B, overrun with a fixed morning:** the last show runs past 06:00 and the next day joins its first show in progress.
  Rejected because it cuts the start of things (the first minutes of *The Goonies*).
- **C, chained days:** each day starts when the previous one's last show ends. Rejected because TVs can drift apart
  across days.
- **Option 2, shorts as station breaks all day:** more intentional, but a bigger change that moves every channel's
  walk. Deferred.

## How it works (decided)

1. **Main layout is unchanged.** The regular layout runs as today. Call the point where it stops (the first run that
   doesn't fit) `c`. The leftover is `L = E - c`.
2. **Fill.** From the items the planned end may visit today, under the same eligibility as today (same visit order and,
   for ordered modes, the same group rule), choose the set whose runtimes add up closest to `L` without going over.
   Ties are broken deterministically (my call: ties go to the set reached first when candidates are taken in order,
   as defined in the rules format). This replaces
   today's first-fit pass. The remaining time `R = L - sum(fill)` is what the cards will cover.
3. **Breaks.** Pick `k` break points at show boundaries in `[S, c]`, as close as possible to evenly spaced targets
   through the day. My call: aim for about one break every 2 to 3 hours, never more breaks than there is content and
   time to put in them, and never two breaks back to back. The last boundary (`c` itself) can be a break point.
4. **Distribute.** Share the fill items out across the breaks in order, and share `R` out across the breaks as cards.
   My call: split `R` evenly, rounded to whole seconds, with the remainder going to the last break. Every show after a
   break moves later by that break's length. The day still ends exactly at `E`.
5. **Ordered modes** (`chronological`, `randomInOrder`, `rotation`, `inOrder`). My call: the fill items stay at the end
   of the day in order, because moving a later episode into a mid-day break would put it before earlier ones. Only the
   card time `R` is spread. `shuffle` and `curated` (the starters) spread both.
6. **A channel with no shorts** (all movies). `R` can be an hour or more, so it becomes cards a few minutes long
   between films. That is accepted.
7. **Repairing today (ADR-0025, `layoutTail`).** A repair rebuilds the rest of the day from `fromMs` with the same
   spread, over the rest of the day only.
8. **Already-aired items in `shuffle` and `curated`** (my call; the anti-goal is breaks that feel random). The fill
   prefers items that have not aired yet that day. An item that already aired is used only when nothing unaired fills
   better. It then goes only into a break after its earlier airing, and in `curated` only where `repeatGapMs` from
   that airing is met. If no such break exists, it is left out.
9. **Grid channels and short days** (my call; the app never writes either). With `alignToGrid: true`, version 5 keeps
   today's planned end with the best-fit selection added and no spreading, because moving shows would take them off
   their grid marks. With any epoch length there is at least one break, at `c`, so a short day behaves like today's end
   but with best fit and a card in place of the slate.
10. **When the switch happens** (my call, from the anti-goal on cutting). A channel moves to version 5 **at the next
    broadcast-day boundary**, through the existing previous-rules and keeper mechanism. It does not switch at once the
    way the `alignToGrid` rewrite did. Today keeps its version 3 or 4 layout, so the programme on air never moves.[^cap]

[^cap]: One exception: an on-air starter beyond the planner's keeper CAP switches at once, as it does for any
    catalogue bump. This is recorded in the plan's Task 7 ADR note.

## The station-break card

- A new schedule content kind, distinct from `Slate`. My call on the name: `ScheduleContent.Break`.
- **Live:** shows the channel ident and "Up next: <title> at <time>". The next programme starts automatically when the
  card ends.
- **Guide:** a narrow cell styled as a break, not "OFF AIR". Very short cards can merge visually into the cell next to
  them (left to the builder).
- `Slate` stays for a truly empty channel and for the grid gaps in the middle of the day.

## Constraints

- **Rules format:** "Any later change that alters a lineup needs a new `formatVersion`." This is a lineup change, so
  it needs a new version. **Owner decision 2026-11-13:** `formatVersion` 4 belongs to ADR-0026 (studio fold 2), which
  ships first, so this change is **`formatVersion` 5**: fold 2 plus the spread end. Files at versions 1 to 4 keep the
  planned end of the day. My call: version 5 turns it on with no new field and no off switch. The vectors of versions
  3 and 4 stay frozen, and version 5 gets its own. **Owner decision 2026-11-13 ("ship now and
  accept"):** writing 5 is an Android-only exception to ADR-0026's release-gate rule. It ships with the Fire OS build
  that reads 5, without waiting for Vega OS, Roku or a check of every TV. Until a reader is updated, it shows v5
  channels as unusable. Because v5 includes fold 2, this also brings fold 2 to studio starters and new studio channels
  ahead of ADR-0026's gate; that is accepted and recorded in ADR-0026.
- **Which channels move (owner decision 2026-11-13: starters and new only).** Unedited starters move, through a
  `StarterCatalog.VERSION` bump and the planner's rewrite of unedited starters, taking effect at the next boundary
  (item 10). Channels created in the app from now on are written at version 5. Existing custom channels, edited
  starters and imported files keep their version, even when saved again. That also leaves the "write the lowest version
  the rules need" rule (ADR-0022 ruling 10) untouched for them. The one exception is a new channel, which is written at
  version 5 on purpose (a floor on ADR-0026's `writeVersion`), and that exception needs a line in ADR-0022.
- Determinism and vectors: ADR-0002. Persisted days (`PersistedDays`, `DayStore`) must store and read the new block
  kind. That touches the stored format, so it needs a data review.
- The planned end's cost today is one pass over at most `N` items. The best-fit selection must stay cheap on TV
  hardware for big pools (thousands of items) and long leftovers (hours). Bound it, for example with a capped
  subset-sum over seconds, or with a greedy fallback above a size limit.

## Left to the builder

- The exact fill algorithm and its bounds, provided it is deterministic and documented in the rules format.
- Exactly how break points are picked from the targets, and the guide styling and copy for cards.
- Test layout and new vectors.

## Reviews

- `crew:architecture`: a new content kind other modules depend on, and a new format version. Mandatory at plan review
  and code review.
- `crew:data`: the persisted-day format change. Mandatory at code review.
- `crew:frontend` builds the live and guide card.
