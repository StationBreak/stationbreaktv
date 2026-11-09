# ADR-0022: Rules format version 2 (spec 22, the channel builder)

- **Status:** Accepted, 2026-11-02 (PR [#545](https://github.com/StationBreak/stationbreaktv/pull/545) merged)
- **Date:** 2026-11-02
- **Decided by:** the owner (the design, 2026-11-02) and Rincewind (rulings 1 to 25 below)
- **Resolves:** F2.3, the channel builder
- **Amends:** [ADR-0002](0002-determinism-scope.md), only by adding orders
- **Informs:** spec 22; [docs/rules-format.md](../rules-format.md), which holds the exact text

## Context

Rules format version 1 is final. It can say which items a channel may air (sources, exclusions, one rating ceiling) and
in what order (`shuffle`, `chronological`, `randomInOrder`). A viewer who builds a channel by hand needs five things it
cannot say:

1. **Caps.** "Random order, but never more than three episodes of one show in a row." Version 1's `shuffle` has no
   such limit, and `randomInOrder` always plays the same shows in the same turns.
2. **Collection groups.** A film collection (a box set) should take turns as one unit, like a show does, and in
   Rotation play in release order.
3. **Group by group, in order.** One show start to finish, then the next, oldest first.
4. **AND filters.** "Comedy, from the 1990s, under 40 minutes, shows only." Sources are a union and exclusions only
   subtract, so a narrowing condition cannot be written.
5. **Single items.** "This one film, that one episode." Today a one-off has to be a whole series or a whole collection.

Version 2 is final (2026-11-02), with independently computed vectors. The owner accepted the one known cost of its
layout, that the planned end of a day may be played again at the start of the next (issue #546); a later change that
alters a lineup needs `formatVersion` 3.

Version 1 cannot change (it is final, and the apps in the field read it), and every TV must still compute the same
schedule from the same rules and library (ADR-0002). So the additions are a second version of the file, defined in full
in [docs/rules-format.md](../rules-format.md).

## Decision

Version 2 adds, and nothing else:

- two ordering modes, `rotation` and `inOrder`;
- optional `ordering.caps` (`series` and `film`, each 1 to 10, defaults 3 and 1), read by `shuffle` (a capped shuffle)
  and `rotation`;
- one source and exclusion kind, `item` (one film or one episode, by ID);
- an optional top-level `filters` object (`genres`, `years`, `runtime`, `kind`), AND-combined.

**Version 1 and version 2 coexist, with no migration.** A writer writes the lowest version whose features the file
uses, so every channel that does not use a version 2 feature stays a version 1 file, byte for byte, and older apps keep
reading it. A reader that supports 2 reads 1 unchanged. The version 1 vectors stay valid and unchanged.

The rulings, each as `Ruling: what — why — cost if wrong`:

1. **Ruling:** films are grouped only by the collections the channel names as sources, and a film in several of them
   plays under the smallest collection ID — the viewer chose those collections, and a household box set must not
   silently turn into one giant group — cost: a channel sourced from a whole library gets no collection grouping until
   the viewer adds the collections as sources.
2. **Ruling:** Rotation has no seam rule at its cycle wrap; the format documents the wrap, a capped shuffle's in-day
   wrap and the planned end as the places a cap can be exceeded (a left-out run can also bring two turns together, and a
   group left alone plays on, which is designed behaviour) — the fix would break "a group joining the pool never
   reorders the others" in about half the cases, and a full cycle is days to weeks long — cost: at most once per cycle a
   show runs from its tail into its head.
3. **Ruling:** a capped shuffle keeps episodes, and the films of a collection, individually shuffled (the version 1 shuffle order inside a group; release order is Rotation's) — the
   owner asked for full randomness, with the cap only as a ceiling — cost: none known.
4. **Ruling:** In order treats every film as a group of its own, and orders all groups by the date of each group's
   first dated item; caps do not apply — the owner described it as one show after another, oldest first, with films
   mixed in — cost: a franchise marathon is a Rotation with a high film cap, not In order.
5. **Ruling:** In order cuts its order into runs of `blockLength` (default 1), as `chronological` does — a whole show as
   one run would exceed the epoch and be left out — cost: none.
6. **Ruling:** caps are not enforced in the planned end of the day in version 2 (measured as no exceed on a large
   pool), which keeps that rule simple — cost: a rare repeat at the tail of a day.
7. **Ruling:** a capped shuffle spreads a dominant group's forced tail instead of front-loading the other groups. When
   one group is tight (it needs every remaining gap), the other groups are held back until it reaches its cap, so they
   are used one per `cap + 1` slots; with no tight group the walk is the plain first-legal-item walk. The order stays a
   total function of (pool, `seed_n`, caps, collection map) and is built in O(n log n). If this construction cannot
   keep its stability or its speed, the plain walk ships with an issue and the document is amended in the same PR — a
   lopsided channel (one big show plus a few films) is common, and a day ending in hours of one show defeats the cap —
   cost: one more algorithm and its vectors.
8. **Ruling:** the film-to-collection map is a side input to building a day, worked out per epoch from the as-of
   library snapshot; it is not a field on the scheduler's item — that keeps the item type and every existing vector
   unchanged — cost: one more parameter on the build entry point.
9. **Ruling:** caps run from 1 to 10; the app always writes `epochHours` 24 for builder channels; a file with caps in
   force and `epochHours` below 24 is invalid — a three-episode turn of hour-long episodes would not fit a short epoch
   and would be left out whole — cost: power users cannot pair caps with short epochs.
10. **Ruling:** `formatVersion` is 2 only when a version 2 feature is used; otherwise the writer emits 1 — older apps
    keep working on untouched channels — cost: two write paths.
11. **Ruling:** the runtime filter is applied where the epoch's pool is cut, using the runtime as read at the cut-off;
    the genre, year and kind filters are applied when the pool is evaluated — runtime is a scheduler field read as of the
    cut-off — cost: none.
12. **Ruling:** "Pick shows and films" searches the synced on-device catalog (item and series names), not the server —
    typed text stays on the TV and there is no new network surface — cost: items not yet synced cannot be found until the
    next sync.
13. **Ruling:** library names for the picker come from the server's current-state read the app already does for
    libraries, not from a new stored column — cost: the library picker needs the server reachable to show names.
14. **Ruling:** names shown in the preview (picked items, groups) come from the synced catalog, and the preview line is
    worked out off the main thread behind a "Working it out" state — cost: none.
15. **Ruling:** grown-up custom channels take the first free number in the reserved custom band (600 to 899); 900 to 999
    stays reserved for seasonal channels, and a full band refuses with a "no free number" message — the code already
    reserves these bands — cost: none.
16. **Ruling:** an edit is promised as "the airing item is never interrupted; from its end the channel follows the new
    rules at the clock, possibly joining part-way into an item", which amends the design's "from the next programme" —
    a stateless schedule cannot line the new rules' boundaries up with the old — cost: the first item after an edit may
    start part-way in.
17. **Ruling:** the film-to-collection map is stored with the built day, and the day snapshot format version is bumped —
    a day restored from storage must schedule identically to one just built — cost: one snapshot format bump (old
    snapshots are rebuilt).
18. **Ruling:** Reset (spec 21) keeps custom channels and resets the starters only — deleting what the viewer built on
    a "Reset channels" press would be destructive and surprising — cost: a viewer who wants them gone deletes them one by
    one.
19. **Ruling:** ruling 7 is the paced walk (hold the other groups back while the dominant group is tight), with the
    plain-walk fallback plus an issue — the forced run's length is fixed by the counts and only its placement can
    improve — cost: as ruling 7.
20. **Ruling:** a file that says `formatVersion` 1 and uses a version 2 mode or source reads as unsupported, and
    version 2-only fields in a version 1 file are unknown extras, kept and ignored, as the version 1 document requires —
    cost: none.
21. **Ruling:** the scheduler settings' new fields are required nullable parameters at every construction site, in line
    with the project's rule of no defaults outside `core:rules` — cost: churn at about 22 construction sites.
22. **Ruling:** Copy to customise gives the copy a fresh seed — cost: the copy's shuffled order differs from the
    built-in channel's.
23. **Ruling:** on Reset a custom channel keeps its number; a starter whose default number collides gives way to the
    first free number in its own band, and is shelved (off, under More channels) if that band is full, so a kids custom
    channel never leaves the kids band — cost: a starter can land on an unfamiliar number after a reset.
24. **Ruling:** on Reset the planner is given the kept channels (their numbers and how many are on) and stays within the
    lineup cap; if it still cannot, the lowest-ranked starters are shelved, never a kept channel — cost: a new `kept`
    parameter on the lineup planner.
25. **Ruling:** the preview's pool provider is an uncached call into the same pool evaluator, sharing the day-building
    helper and the ceiling function with the lineup, but not its cache — a draft is not a lineup channel and must not
    pollute the lineup's cache — cost: a preview recompute evaluates the pool each time (milliseconds on the JVM).

Ruling 20 is also stated in the Format version section of the document: a version 1 file that uses `rotation`,
`inOrder` or an `item` source is unsupported, and its `filters` or `caps` are kept and ignored.

## What was measured

A spike built throwaway implementations of the three new orders against a pool of 10,000 items. Every order took under
4 ms on a warm JVM, and a whole day (pool, order, runs and layout) took 5 to 7 ms. The paced walk of ruling 7 and the
filters were not part of the spike. They are expected to add work proportional to the pool, and a later scheduler task
checks that expectation. Speed on the oldest supported TV hardware is measured when the scheduler tasks land; this record
does not claim it.

- Caps in the planned end (ruling 6): the capped shuffle was run over 60 consecutive days on a pool of more than 4,000
  items, and it produced 0 cap exceeds, in the regular layout and in the planned end.

## Consequences

- The document, a version 2 JSON Schema, version 2 examples and three version 2 vector files (`key-order-v2.json`,
  `lineup-v2.json`, `pool-v2.json`) are the contract. They are computed with an independent reference.
- A file with `formatVersion` 3 is not scheduled. Version 2 does not change any version 1 default, rule or vector.
- Every ruling's cost is accepted. The costs that a viewer can see (an edit joining part-way into an item, a show
  running tail into head once per cycle, a rare repeat at the end of a day) are told to the owner.

## Addendum: Version 3 (spec 23, lineup curation, 2026-11-03)

Version 2's last consequence ("a file with `formatVersion` 3 is not scheduled") held until this addendum. Spec 23 needs
three things that version 2 cannot say, and a lineup that changes for existing rules needs a new version (ADR-0002).

**Why version 3.**

1. **Studios.** A themed channel is "this studio's output", and a studio is a field every Jellyfin server has. Version 2 has
   no source or filter for it. Version 3 adds the `studio` source and exclusion and the `studios` filter, matched under a
   fixed studio fold (clean, then ASCII fold) so every TV reads the same name the same way.
2. **Narrower themes.** A theme is often "this, but not that". Version 3 adds the filters `tags` (any of), `notGenres` (none
   of, failing closed when an episode's series is not in the snapshot) and a runtime window for each kind of item
   (`runtime.episode`, `runtime.movie`), so "short shows, mid-length films" can be one channel.
3. **A programmed order.** `shuffle` spreads titles by chance and `rotation` by caps. A channel that should feel
   programmed (short episodes in pairs, no title or franchise again within three hours, a heavier title a little more
   often) needs an order of its own: `curated`, with the tunables `ordering.repeatGapMinutes` (0 to 1440, default 180) and
   `ordering.pairUpToMinutes` (1 to 1440, default 35).

**Rulings.**

- **Side inputs are not in the file (Resolution 9).** The weights (title to 1 to 3), the franchise map (every snapshot
  collection, the smallest ID) and the trim window's `keeps` (series to a cap in milliseconds) depend on the whole lineup
  or on the library, not on one channel's rules. They are inputs to building a day, like the film-to-collection map in
  version 2. A rules file read on its own gives every title weight 1 and keeps everything. Two TVs agree because they
  compute the same side inputs from the same lineup and snapshot (spec 23 tests this).
- **Tunables are in the file (Resolution 10).** The repeat gap and the pair-up length change a lineup and are properties
  of one channel, so they are optional version 3 fields with fixed defaults, in the file, validated whatever the mode and
  read only by `curated`. Their presence counts as using version 3.
- **The trim window (Resolution 12).** When curation caps a series' share on a channel, it claims all of the series and
  gives the scheduler a cap in milliseconds; the scheduler turns it into a count `k` from the series' items in the in-group
  order and keeps a window of `k` items that starts at `floorMod(n * k, s)` and wraps. Films are never windowed. This is the
  last step of the pool of an epoch, in every mode, and is empty for every version 1 and 2 caller.
- **`curated` is placed like `shuffle`.** Each day has its own seed and starts at its first run; the repeat gap restarts with
  the day (a title can end one day and open the next); the planned end of the day wraps and has no group rule. `curated` has
  no caps (a file with both is invalid) and ignores `timing.blockLength`.
- **A studio source is not fail-closed.** Like a genre or tag source it uses an episode's own studios when its series is
  missing. Only `notGenres` fails closed, because it is a promise of what will not air.

**Coexistence.** Versions 1, 2 and 3 coexist with no migration. A reader that supports 3 reads 1 and 2 unchanged, and a
version 3 feature in a file that says 1 or 2 is an unknown kind (`studio`, `curated`: unsupported) or an unknown field (the
filters and tunables: kept and ignored). **A builder or other writer writes the lowest version its features need**, so a
channel that uses no version 3 feature stays a version 2 (or 1) file byte for byte and older apps keep reading it. The
starter catalog 3 uses version 3 ([ADR-0023](0023-lineup-curation.md)), and the curation step supplies the weights and the
trim window's `keeps`.

**Consequences.**

- The document, a version 3 JSON Schema and the version 3 examples are the contract; the version 3 vectors are computed by
  an independent reference written from the document alone. A disagreement is fixed in the code or the document, never in a
  vector.
- A file with `formatVersion` 4 is not scheduled. Version 3 does not change any version 1 or 2 default, rule or vector.
