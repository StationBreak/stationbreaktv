# Channel rules format

> Version 1 — final (2026-10-16), amended once on 2026-10-19 (see Changes to version 1). Version 2 — final (2026-11-02): it adds features to version 1 and leaves version 1 unchanged (see Format version). Version 3 (2026-11-03): it adds features to version 2 and leaves versions 1 and 2 unchanged. Any later change that alters a lineup needs `formatVersion` 4.

This document defines the file that describes one StationBreak channel. It is written for a developer who has never seen
this repository and who wants to build another client (Roku, Apple TV, a web tool) that shows the same lineup as the
Android TV app. You do not need to read any Kotlin.

The key idea: a channel is stored as a small file of **rules**, not as a list of shows. Given the same rules, the same
media library and the same seed, every client must work out the same lineup. So this document is exact about the few
steps that have to match bit for bit (the hash, the orders, and the layout of a day), and plain about the rest.

The words **must**, **must not**, **should** and **may** are used as described in RFC 2119 and RFC 8174, and mean that
only when they are written in bold.

Worked numbers are published as test vectors in `docs/rules-format/vectors/` (see Files). The scheduler's part of the
contract, which turns a pool into "what is on at time T", is the section Building the schedule. Its vectors (the per-epoch
seed, the key order and whole lineups) are in the same folder.

## Overview and file layout

A rules file is one JSON object (UTF-8, no byte-order mark). Here is a complete file that uses every section:

```json
{
  "formatVersion": 1,
  "name": "Saturday Morning Cartoons",
  "seed": "13835058055282163712",
  "sources": [
    { "type": "genre", "name": "Animation" },
    { "type": "collection", "id": "22222222222222222222222222222222" }
  ],
  "exclusions": [{ "type": "series", "id": "33333333333333333333333333333333" }],
  "maxRating": "TV-Y7",
  "ordering": { "mode": "randomInOrder" },
  "timing": {
    "blockLength": 2,
    "alignToGrid": true,
    "epochHours": 24,
    "broadcastDay": { "utcOffsetMinutes": -300, "start": "06:00" }
  },
  "filler": { "mode": "betweenEveryN", "every": 3, "source": { "type": "tag", "name": "Station IDs" } }
}
```

The same file is `docs/rules-format/examples/full.json`. Every ID in this document and in the examples is a made-up
placeholder.

The top-level fields:

| Field | Required | Meaning |
| --- | --- | --- |
| `formatVersion` | yes, no default | Whole number, 1 or more. The version of this format: `1`, `2` or `3` (see Format version). |
| `name` | no | The channel's display name. |
| `seed` | yes, no default | The channel's random seed, as a decimal string. |
| `sources` | yes, no default | List of sources: what can air. At least 1. |
| `exclusions` | no | List of sources that must not air. Default: none. |
| `maxRating` | no | A rating ceiling. Default: no ceiling. |
| `filters` | no | Version 2 and later. Object that narrows the pool further (see Filters). Default: none. |
| `ordering` | no | Object with the ordering `mode` and, in version 2, `caps` and, in version 3, `repeatGapMinutes` and `pairUpToMinutes`. Default: `shuffle`. |
| `timing` | yes | Object with `blockLength`, `alignToGrid`, `epochHours` and `broadcastDay`. |
| `timing.broadcastDay` | yes | Object with `utcOffsetMinutes` and `start`. |
| `timing.broadcastDay.utcOffsetMinutes` | yes, no default | The channel's fixed UTC offset, in minutes. |
| `filler` | no | Object with filler `mode`, `every` and `source`. Default: `none`. |

Four values have **no default** and must always be written: `formatVersion`, `seed`, `sources` and
`timing.broadcastDay.utcOffsetMinutes`. (`timing` and `timing.broadcastDay` are required only because they hold the
offset.) Every other field has the default given in its section. Those defaults are part of version 1: **changing a
default needs a new `formatVersion`**. A writer may write a default or leave it out; a reader must treat both the same.

What is **not** in the file: the channel number and logo (they belong to each TV's own channel list), the server's
address, any account name or token, the device's time zone or language, and what anyone has watched. A rules file never
holds a secret and never causes a client to fetch a URL.

## Glossary

- **Item**: one movie or one episode that can air.
- **Pool**: the set of items a channel may air, worked out from the sources, the exclusions, the rating ceiling and (in
  version 2 and later) the filters. The pool of one epoch is cut further by the time rules of The pool of an epoch.
- **Library snapshot**: the cached set of items, with their fields, that the signed-in account can see when a schedule is
  worked out (see Items and the pool). It also holds each series that an episode's `SeriesId` points to, with that
  series' rating, genres and tags.
- **Program**: one item as it airs.
- **Run**: `blockLength` items that play back to back (see `blockLength` in Timing and Runs). A run is a unit of the
  order. It is not a unit of the lineup.
- **Block**: one entry of a lineup: either one item (a program) or a slate, with a start and an end. A run is played as one
  block per item.
- **Slate**: a block with no program, for time nothing else fills. It is a station-ID card until filler exists.
- **Epoch**: the stretch of time from `epochStart(n)` up to `epochStart(n + 1)` (default 24 hours). Each epoch's lineup is
  worked out from that epoch's own pool and the channel's rules, with no state carried over from earlier epochs. The
  `shuffle` order has a seed of its own for each epoch; the other orders do not (see Ordering).
- **Cycle**: the runs of one epoch's pool in order, once through, without the runs that are left out (see Left-out runs). It
  repeats when it runs out.
- **Title**: a series, for an episode that has a series ID, and the item itself for every other item. A title is named by
  its canonical ID. Version 3 weights and trims titles (see `curated` and The pool of an epoch).
- **Side input**: something the schedule is built from that is not in the rules file and not an item: the film-to-collection
  map, and in version 3 the weights and the keeps. Two clients give the same lineup only when they give the same side
  inputs; a rules file read on its own gives a weight of 1 to every title, keeps everything, and (for `curated`) uses the
  franchise map of the library.
- **Grid mark**, **slot**, **continuous playout**: see Building the schedule.
- **Broadcast day**: the daily start of the first epoch, on the channel's own fixed-offset clock.
- **Canonical ID**: an item ID as 32 lower-case hex characters (see Item IDs).

## Format version

`formatVersion` is a whole number of 1 or more. This document defines versions `1`, `2` and `3`.

**Version 2 is final** (2026-11-02), with its independently computed vectors. The owner accepted the one known cost of its
layout, that the planned end of a day may be played again at the start of the next (issue #546), rather than change the layout
after the fact.

**Version 3** (2026-11-03) is a strict superset of version 2. It adds, and changes nothing in version 1 or 2: the source
and exclusion `studio`; the filters `tags`, `studios` and `notGenres`; a runtime window for each kind of item
(`filters.runtime.episode` and `filters.runtime.movie`); the ordering mode `curated` with its two tunables
`ordering.repeatGapMinutes` and `ordering.pairUpToMinutes`; and, for the building of a day, two side inputs and one pool
rule, the trim window (see The pool of an epoch). The trim window and the weights act only when a client gives the
scheduler a non-empty `keeps` or `weights`, which no version 1 or version 2 client does, so a version 1 or 2 lineup is the
same in a version 3 client. A later change that alters a lineup needs `formatVersion` 4.

**A writer that creates or edits rules writes the lowest version whose features the rules use.** A file that uses nothing
from version 2 or 3 says `1`. A file that is read and written back unchanged keeps the version it had. The presence of `filters`
(even `{}`) or of `ordering.caps` (even beside a mode that ignores them) counts as using version 2. Any one of these counts
as using version 3: a `studio` source or exclusion; a `tags`, `studios` or `notGenres` filter; a `runtime.episode` or
`runtime.movie` window; the mode `curated`; an `ordering.repeatGapMinutes` or `ordering.pairUpToMinutes` (even beside a
mode that ignores them). A builder that edits a file moves it to version 3 when it adds one of these, and back down to the
lowest version the rules need when it removes the last one.
**A reader that supports version 2 reads a version 1 file unchanged, and a reader that supports version 3 reads version 1
and version 2 files unchanged.** Version 2 adds, and changes nothing in version 1:
the orders `rotation` and `inOrder`, `ordering.caps`, the `item` source and exclusion, and `filters`. Versions 1, 2 and 3
coexist, and no file is migrated.

**Version 2 features are read only in a file that says `formatVersion` 2.** In a file that says `1`:

- a version 2 ordering `mode` (`rotation`, `inOrder`) or source `type` (`item`) is an unknown kind, so the channel is
  **unsupported** (see Unknown fields and unsupported kinds);
- `filters` and `ordering.caps` are unknown fields: they are kept and ignored.

**Version 3 features are read only in a file that says `formatVersion` 3.** In a file that says `1` or `2`:

- the source and exclusion `type` `studio` and the ordering `mode` `curated` are unknown kinds, so the channel is
  **unsupported** (see Unknown fields and unsupported kinds);
- `filters.tags`, `filters.studios`, `filters.notGenres`, `filters.runtime.episode`, `filters.runtime.movie`,
  `ordering.repeatGapMinutes` and `ordering.pairUpToMinutes` are unknown fields: they are kept and ignored. (So a version 2
  `runtime` that holds only `episode` or `movie` has no bound of its own and is invalid, as a `runtime` with neither bound
  is.)

**A reader must read `formatVersion` first, before anything else in the file.** It must be able to do this even when the
rest of the file is unreadable. The only things checked before it are the input limits (see Limits) and the JSON profile
(see JSON profile): a file over a limit, or one that breaks the profile, is invalid even when its `formatVersion` is newer.

- If `formatVersion` is higher than the highest version the reader supports (4 or more, for a reader of this document),
  the reader must **not** schedule the
  channel, must not try to read the rest, and should tell the user that an update is required. It must never play a
  best-effort lineup from a newer file.
- If `formatVersion` is missing, is not a whole number, or is below 1, the file is invalid. The channel is not scheduled.
- A file that is not valid JSON is invalid.

### Changed while Draft (2026-10-16)

The scheduler's part of this document, Building the schedule, was specified by F1.1 (spec 08). While it was written, four
things in version 1 changed from what the earlier text said or implied:

1. **The `randomInOrder` group key uses the channel `seed`, not `seed_n`.** The groups (series) keep the same order on
   every day. The order of the items inside a group is now the in-group key (season class, season, episode, undated flag,
   date, ID), not the `chronological` key. See `randomInOrder`.
2. **`timing.graceHours`** (the grace window, default 6) is a rules field. Which items an epoch includes depends on it.
3. **`timing.gridMinutes`** (the grid, default 30) and **`timing.minRuntimeSeconds`** (the shortest program, default 60) are
   rules fields. Where a program starts, and which items are in the pool, depend on them.
4. **The end of each day is planned.** A program is never cut at the epoch boundary and never runs over it. This replaces
   the earlier "hard cut".

Items 1 to 4 **change lineups**: the same rules and library give a different lineup than the earlier draft text did. They
were allowed into version 1 only because version 1 was a Draft that no client had shipped, so no lineup built from the earlier
text exists anywhere. **Now that version 1 is final, a change like any of these needs version 2.** That includes adding a
rules field that changes a lineup, and changing the default of one.

### Changes to version 1 (2026-10-19)

**Rules 3 and 4 of Items and the pool, and the Fail closed paragraph, were amended together, once, after version 1 was
final.**

*Rule 4.* The earlier text let an episode with a rating of its own use only that rating. An episode's own
`OfficialRating` is often a copy of its series' rating, and whether that copy is updated when the series is re-rated is
not verified, so a stale copy (own `TV-Y7`, series now `TV-MA`) could pass a ceiling the series fails. Now, under a ceiling, an episode needs both its
effective rating and its series' rating to pass. A series rated `NR`, `Unrated` or a blank rating blocks its episodes,
and so does an episode whose series ID is present but whose series is not in the library snapshot; an episode with no
series ID is checked on its own rating alone. `docs/rules-format/vectors/rating.json` carries the new `inheritance` rows.

*Rule 3.* Exclusions now fail closed too. Before, an exclusion the client could not check was skipped, so the item it was
meant to remove aired. Now a `collection` exclusion whose collection is not in the library snapshot empties the pool, and a
`genre` or `tag` exclusion removes an episode whose series ID is present but whose series is not in the snapshot (see
Items and the pool, rule 3). The reason is the same as for the ceiling: an exclusion is a promise of what will not air.

*Scheduler inputs.* The same amendment also adds a MUST to Scheduler inputs: a rating must be the item's own
`OfficialRating`, never `CustomRating` and never a value the client inherits from another item. This can change a
lineup, as the rest of this amendment can.

This changes lineups. Version 1 was already final, so this is a **one-time exception**, allowed because no shipped client
has a pool evaluator. There will be no further in-place changes: any later change that alters a lineup needs
`formatVersion` 2.

## JSON profile

A rules file is JSON as defined by RFC 8259, narrowed by the I-JSON profile of RFC 7493. A reader **must** treat a file
that breaks any of these rules as invalid, and a writer **must not** produce one:

- The text is UTF-8 with no byte-order mark. A reader given bytes rejects bytes that are not valid UTF-8.
- No object has two members with the same name, at any level, after escapes are decoded (`"a"` and `"\u0061"` are the same
  name). This includes unknown fields.
- No string, and no member name, holds an unpaired surrogate (see String matching).
- Numbers follow the JSON grammar exactly: no leading zeros (`01`), no plus sign (`+5`), no `NaN` or `Infinity`.
- Where this document says **whole number** or **integer**, the number is written with digits only, `-?(0|[1-9][0-9]*)`: no
  fraction and no exponent, so `1.0` and `1e2` are invalid, and `-0` is invalid. (The JSON Schema's `integer` accepts `1.0`.
  The schema is an aid for tests and tools. This document is the rule, and a reader follows it.)

A known field whose value has the wrong JSON type, is `null`, is outside the range or list this document gives, or breaks a
rule stated for it, makes the file invalid. A reader never clamps, rounds, or replaces a bad value with the default.

Unknown fields may hold any valid JSON, including numbers. When a file is written again an unknown value is kept as the same
JSON value, not as the same bytes: an escape may be written differently, and a number such as `1e2` may be written as
`100.0`. Writing the shipped examples gives back the same bytes.

**A writer that creates or edits rules must write the lowest `formatVersion` whose features they use.** Rules that only use
version 1 features say `1`, even when the writer also knows version 2 or 3. That keeps older apps working for as long as
possible. (A file read and written back unchanged keeps its version.)

**A change needs a new `formatVersion` when it could change which items air, or in what order.** That includes:

- changing what an existing field or value means, or changing a default;
- changing any algorithm in this document (the hash, the orders, epochs, the pool rule, runs, the day's layout, the planned
  end of the day, the rating ladder or the rating normalisation);
- removing or renaming a field;
- adding a new kind of source, a new ordering mode or a new filler mode (so that an older app says "update required"
  and does not guess).

**A change does not need a new number** when it adds an optional field that cannot change a lineup, such as a label or a
note. Readers keep and ignore fields they do not know (see Unknown fields and unsupported kinds).

## Items and the pool

Sources, exclusions and the rating ceiling decide the **pool**. These rules decide it exactly:

1. **Which items count.** Only **movies** and **episodes**. A series, a season, a collection, a library or any other
   container is never an item; it only selects items. Other types (music, photos, trailers, extras) are not in the pool
   in version 1.
2. **Matching a source.** An item matches:
   - `library`: if it is anywhere inside the library with that ID;
   - `collection`: if it is a **member movie**, a **member episode**, an **episode of a member series**, or an **episode of
     a member season**. A member that is itself a collection selects nothing: collections are not followed (no recursion).
     A member season selects only its own episodes, and a season's own genres and tags are never used (see `genre` and
     `tag` below);
   - `series`: if it is an episode of that series;
   - `item` (version 2): if its own canonical ID is `id`. The ID of anything that is not a movie or an episode matches
     nothing;
   - `genre` or `tag`: if the item has it, **or if it is an episode and its series has it**. Jellyfin keeps genres and tags
     mostly on the series and the movie, not on each episode, so without this rule a genre channel made of TV shows would
     be empty;
   - `yearRange`: if the item's **own** production year is in the range. An item with no production year is **not**
     included. A client must not guess a year from the series;
   - `studio` (version 3): if the item has a studio whose name equals the source's `name` under the **studio fold** (see
     String matching), **or if it is an episode and its series has one**. Jellyfin keeps studios on the series, so an
     episode takes its series' studios as it takes its series' genres and tags.
3. **Union, then exclusions.** The pool starts as every item that matches **any** source. An item that matches several
   sources is in the pool **once** (items are the same item if their canonical IDs are equal). Then every item that
   matches **any** exclusion (with the same matching rules) is removed. Exclusions **fail closed**, in the two cases below.
   In these two cases the client cannot tell whether the item is excluded, so it is removed; this keeps a promise of what
   will not air, as the ceiling fails closed (rule 4):
   - an exclusion of type `collection` whose collection is not in the library snapshot empties the pool;
   - a `genre` or `tag` exclusion also removes an episode whose series ID is present, and no series with that ID is in the
     library snapshot, whatever the episode's own genres and tags are. An episode with no series ID, and a movie, are
     checked on their own genres and tags alone;
   - `library`, `series` and `item` exclusions are decided by the IDs alone and are not changed. A `genre` or `tag` **source**
     is not changed either: for an episode whose series is missing it uses the episode's own names only. A `studio`
     source or exclusion (version 3) is not changed either: for an episode whose series is missing it uses the episode's
     own studios only, and does not fail closed.
   After the exclusions, the `filters` of a version 2 or 3 file narrow the pool further (see Filters).
4. **Rating ceiling.** If `maxRating` is present, an item stays only if it passes the rating rule (see Maximum rating
   and the rating ladder). A **movie** passes only if its own rating passes. An **episode** passes only if **both** of
   these hold:
   - (a) its **effective** rating passes. The effective rating is its own rating, or its series' rating when its own is
     none or the empty string. Any other own rating, even `NR` or a blank rating, is used as it is;
   - (b) its series' rating passes, when the series has one. "Has one" means anything other than none or the empty
     string, so a series rated `NR`, `Unrated` or a blank rating (one or more spaces) blocks its episodes.

   An episode whose series ID is present, and no series with that ID is in the library snapshot, is not allowed under a
   ceiling: its series' rating is unknown, so it fails closed. An episode with no series ID (a loose episode) is checked
   under (a) alone, with its own rating. The reason for (b): an episode's own `OfficialRating` is often a copy of its
   series' rating, and whether that copy is updated when the series is re-rated is not verified. (b) keeps the ceiling
   safe whatever the copy says, also when a sync fails partway or a series is edited near the cut-off. In the reverse
   case, a series re-rated down while an episode keeps a stale, higher copy, (a) still blocks the episode. That fails
   closed, and the cure is to fix the episode's rating on the server.
5. **The library snapshot.** The pool is worked out from the *library snapshot*: the cached set of items, and their
   genres, tags, years, ratings, dates, season and episode numbers, that the signed-in account can see. It also holds each
   series that an episode's `SeriesId` points to, with that series' rating, genres, tags and studios. Rules 1 to 4 pick the items
   from it. Which of those items count for which epoch (the creation time, the deletion time and the runtime) is decided by
   The pool of an epoch, in Building the schedule. The format only says what is looked up in the snapshot.

### Scheduler inputs from the server

The scheduler's item fields (see Building the schedule, Inputs and outputs) come from the server's item like this. A client
**must** follow every line, because a different conversion gives a different lineup:

- `kind` **must** come from `Type`: Jellyfin's `Movie` is the kind `movie` and `Episode` is the kind `episode` (lower case, as
  Inputs and outputs says). Nothing else is an item.
- `runtimeMs` **must** be `floorDiv(RunTimeTicks, 10000)` (Jellyfin's ticks are 100 nanoseconds). An item with a missing or
  `null` `RunTimeTicks` **must** be left out of the pool.
- `createdAt` **must** be `floorDiv(T, 10000)`, where `T` is `DateCreated` as ticks of 100 nanoseconds since
  1970-01-01T00:00:00Z. The result is the server's creation time as milliseconds since the Unix epoch.
- The premiere date **must** be the UTC calendar date of `PremiereDate`, as `YYYY-MM-DD`. An item with no `PremiereDate` has
  no premiere date.
- `ProductionYear` **must** be used as it is, and `SeriesId` **must** be used as the series ID (in canonical form). The
  season number **must** be `ParentIndexNumber` and the episode number **must** be `IndexNumber`.
- The rating of a movie, an episode and a series **must** be that item's own `OfficialRating`, as the server sends it. It
  must never be `CustomRating`, and never a value the client has inherited from another item itself.
- The `genres` and `tags` of an item or a series are the server's `Genres` and `Tags`, as sent. The `studios` of an item or a
  series are the `Name` of each entry of the server's `Studios`, in the server's order: an entry whose name is missing, or
  is longer than 256 UTF-16 code units, is dropped; every other name is cleaned as the studio fold says (see String
  matching) and is dropped if nothing is left; and only the first 16 names that remain are kept. A client **must** follow
  this, because a `studio` source and the `studios` filter compare against exactly these names. The limits of 16 names and of
  256 code units belong to this conversion from the server's data and nowhere else: a pool evaluator is given the studio
  lists of the snapshot as they are and does not apply either limit. It applies the studio fold (see String matching) to
  **both** sides when it compares, and the fold is idempotent, so a name that has already been cleaned compares the same.
- A collection's members **must** be read as rule 2 says (member movies, member episodes, episodes of member series and
  episodes of member seasons, with no recursion into nested collections).

`deletedAt` is not a server field: see The pool of an epoch.

## Sources

`sources` is a list of up to 256 objects. Each has a `type` that says what kind of source it is. Every source is an
object that is open to extra fields. The kind `item` is read only in a file of version 2 or 3, and the kind `studio` only in a version 3 file.

| `type` | Other fields | Matches |
| --- | --- | --- |
| `library` | `id` | Every item in the library with that ID. |
| `genre` | `name` | Every item that has a genre with that name. |
| `collection` | `id` | Every item in the collection (a Jellyfin box set) with that ID. |
| `tag` | `name` | Every item that has a tag with that name. |
| `yearRange` | `from`, `to` | Every item whose production year is from `from` to `to`, both included. |
| `series` | `id` | A specific series: every episode of the series with that ID. |
| `item` | `id` | Version 2. One movie or one episode: the item with that ID. |
| `studio` | `name` | Version 3. Every item that has a studio with that name under the studio fold, and every episode whose series has one. |

The exact matching is in Items and the pool. A `series` source already selects a whole show; to leave one episode out of
it, use the exclusions list, which accepts `item` too (see Exclusions).

```jsonc
{ "type": "item", "id": "44444444444444444444444444444444" }
```

`item` (version 2) matches the one movie or episode whose canonical ID is `id`. An ID that names anything else (a
series, a collection, a library), or that is not in the library snapshot, matches nothing. As a source it adds that item
to the union. As an exclusion it removes it, and it is decided by the ID alone: it does not fail closed.

- `id` is an item ID in the canonical form (see Item IDs).
- `name` is 1 to 256 Unicode code points and is matched as described in String matching. (For a `studio` it is matched
  under the **studio fold**, which is stricter about what it ignores: see Studio names in String matching. A `studio`
  name that is empty once cleaned matches nothing.)
- `from` and `to` are whole numbers from 1 to 9999. `to` must not be lower than `from`. A reader treats a file where it
  is lower as invalid. (The JSON Schema cannot express this; the reader checks it.)

Each kind has its own example file:

- `docs/rules-format/examples/library-only.json` selects a whole library.
- `docs/rules-format/examples/genre-with-max-rating.json` selects a genre, with a rating ceiling.
- `docs/rules-format/examples/collection.json` selects a collection.
- `docs/rules-format/examples/tag.json` selects a tag.
- `docs/rules-format/examples/year-range.json` selects the years 1990 to 1999.
- `docs/rules-format/examples/series.json` selects one series.

`studio` (version 3) matches an item that has a studio whose name equals `name` under the studio fold, and an episode whose
series has one. It is a source and an exclusion like `genre` and `tag`. `docs/rules-format/examples/v3/studio.json` has a
`studio` source and a `studio` exclusion:

```jsonc
{ "type": "studio", "name": "Studio A" }
```

For example, the whole of `genre-with-max-rating.json`:

```json
{
  "formatVersion": 1,
  "name": "Saturday Morning Cartoons",
  "seed": "13835058055282163712",
  "sources": [{ "type": "genre", "name": "Animation" }],
  "maxRating": "TV-Y7",
  "timing": { "broadcastDay": { "utcOffsetMinutes": 0 } }
}
```

An item is only ever referenced by the ID the server gave it, or by an exact name. The IDs belong to one server, so a
rules file that names a library, collection or series by ID only makes sense on a client signed in to that server. Names
(genres and tags) work on any server that has the same names.

## Exclusions

`exclusions` is an optional list of up to 256 objects. It uses **the same kinds as sources**, with the same fields. That
includes `item` in a file of version 2 or 3, which removes one movie or one episode, and `studio` in a version 3 file, which
removes every item of that studio and every episode of a series of that studio.

How they combine: first take the union of all `sources`. Then remove every item that matches **any** exclusion. An item
that is excluded cannot be brought back by any source. The rating ceiling is applied on top: an item has to pass both.
Exclusions that cannot be resolved fail closed (see rule 3 in Items and the pool).

`docs/rules-format/examples/exclusion.json` shows one exclusion of every kind (library, genre, collection, tag, year
range and series). Its `sources` and `exclusions` are:

```jsonc
{
  "sources": [
    { "type": "genre", "name": "Comedy" },
    { "type": "library", "id": "11111111111111111111111111111111" }
  ],
  "exclusions": [
    { "type": "library", "id": "11111111111111111111111111111111" },
    { "type": "genre", "name": "Horror" },
    { "type": "collection", "id": "22222222222222222222222222222222" },
    { "type": "tag", "name": "Seen It" },
    { "type": "yearRange", "from": 1900, "to": 1969 },
    { "type": "series", "id": "33333333333333333333333333333333" }
  ]
}
```

(In this example the library is both a source and an exclusion, so it contributes nothing. The exclusion wins.)

## Filters

`filters` is an optional object, **read only in a file of version 2 or 3** (in a version 1 file it is an unknown field: kept
and ignored). It narrows the pool. Every part it holds must pass, and so must the rating ceiling: all given parts are
**AND-combined** with each other and with `maxRating`. Filters are applied to the items that remain after the sources
minus the exclusions (see Items and the pool). An item that fails a filter is out of the pool, and nothing brings it back.

| Part | Value | An item passes when |
| --- | --- | --- |
| `genres` | List of 1 to 256 names | It has **any** of the names. A name is matched exactly as a `genre` source is (see String matching), and an episode also takes its series' genres, as a `genre` source says; an episode whose series is not in the snapshot is checked on its own genres alone, and the filter does not fail closed. |
| `notGenres` | Version 3. List of 1 to 256 names | It has **none** of the names. A name is matched as in `genres`, and an episode also takes its series' genres, so an episode of a series that has a listed genre fails. It **fails closed**: an episode whose series ID is present but whose series is not in the snapshot fails, whatever its own genres are, because its series' genres cannot be checked. A movie, and an episode with no series ID, are checked on their own genres alone. |
| `tags` | Version 3. List of 1 to 256 names | It has **any** of the names. A name is matched as a `tag` source is, and an episode also takes its series' tags; an episode whose series is not in the snapshot is checked on its own tags alone, and the filter does not fail closed. |
| `studios` | Version 3. List of 1 to 256 names | It has **any** of the studios, under the studio fold (see String matching), and an episode also takes its series' studios; an episode whose series is not in the snapshot is checked on its own studios alone, and the filter does not fail closed. |
| `years` | `{ "from", "to" }` | Its **own** production year is from `from` to `to`, both included, as for a `yearRange` source. An item with no production year fails. `from` and `to` are whole numbers from 1 to 9999 and `to` is not below `from`. |
| `runtime` | `{ "minMinutes", "maxMinutes" }`, and in version 3 also `"episode"` and `"movie"` | Its runtime, as read at the cut-off `S - G` (see The pool of an epoch), is at least `minMinutes` and at most `maxMinutes`, both included, in whole minutes (compared in milliseconds: `minMinutes * 60000` and `maxMinutes * 60000`). Each is a whole number from 1 to 1440; `minMinutes` is not above `maxMinutes`. An item with no runtime is already out of the pool. The version 3 parts are described below. In version 2, at least one of `minMinutes` and `maxMinutes` is given. In version 3, at least one of the four parts is given. |
| `kind` | `"movie"` or `"episode"` | It is of that kind. |

**The per-kind runtime windows** (version 3). `runtime.episode` and `runtime.movie` are each an object
`{ "minMinutes", "maxMinutes" }` with the same ranges as the general window (whole minutes from 1 to 1440, `minMinutes`
not above `maxMinutes`) and with at least one bound given. The `episode` window applies to episodes only and the `movie`
window to movies only; the other kind is not touched by it. An item passes the `runtime` part when it passes the general
window (if there is one) **and** the window of its own kind (if there is one). All of them compare the runtime as read at the
cut-off `S - G`, in milliseconds, both bounds included, and are applied where the epoch's pool is cut (see The pool of an
epoch). For example `{ "maxMinutes": 180, "episode": { "maxMinutes": 30 }, "movie": { "minMinutes": 80, "maxMinutes": 150 } }`
keeps an episode of up to 30 minutes and a movie of 80 to 150 minutes, and nothing longer than 180 minutes. A `runtime`
object that has only an `episode` window is valid in version 3: films are then not limited by it.

An unknown `kind` value (not `"movie"` or `"episode"`) is an unknown kind, not a bad value: the file is **unsupported**, as
for an unknown source type (see Unknown fields and unsupported kinds). The other parts are still checked first, so a file with
an unknown `kind` and a bad `genres` list is invalid. An empty `filters` object is valid and changes nothing. A part that is not on this list is an unknown field. A filter
with a bad value (an empty `genres` list, `years` with `to` below `from`, `minMinutes` above `maxMinutes`, a `runtime`
with no bound, a `kind` of the wrong type, or in version 3 an empty `tags`, `studios` or `notGenres` list, or a per-kind
window with no bound) makes the file invalid. In a file that says `2`, the version 3 parts are unknown fields and are not
checked.

`filters` change which items air, so they are a version 2 feature, and the parts listed as version 3 above need version 3.
They do not change the rating ceiling: an item still needs to pass `maxRating` (and its fail-closed rules) as well.

```jsonc
{
  "filters": {
    "genres": ["Comedy", "Animation"],
    "years": { "from": 1990, "to": 1999 },
    "runtime": { "maxMinutes": 40 },
    "kind": "episode"
  }
}
```

(This fragment is part of a version 2 file, `docs/rules-format/examples/v2/filters.json`.)

The version 3 parts each have a file: `docs/rules-format/examples/v3/filters-tags-studios.json` (`tags` and `studios`),
`docs/rules-format/examples/v3/not-genres.json` (`genres` with `notGenres`) and
`docs/rules-format/examples/v3/runtime-per-kind.json` (a general window and a window for each kind). A `runtime` object
may hold a window for one kind only and a field this version does not know
(`docs/rules-format/examples/v3/runtime-kind-window-with-unknown-field.json`); the unknown field is kept.

## Ordering

`ordering` is an object with the field `mode` and, in version 2, the field `caps` and, in version 3, the fields
`repeatGapMinutes` and `pairUpToMinutes`. `mode` is one of six exact names in lowerCamelCase: `shuffle`, `chronological`,
`randomInOrder`, `rotation`, `inOrder` or `curated`. `rotation` and `inOrder` are read only in a file of version 2 or 3, and
`curated` only in a version 3 file. If `ordering` is absent, the mode is `shuffle`.

**`ordering.caps`** (version 2, optional) is an object `{ "series", "film" }`. Each is a whole number from **1 to 10**, and
each is optional: the default for `series` is `3` and the default for `film` is `1`. A cap is the longest stretch of one
group that may play in a row while something else is waiting (see Groups in version 2). `caps` is read by `shuffle` (which
then becomes the capped shuffle) and by `rotation`, and **ignored by the other three modes**, but it is validated whatever
the mode: a value outside 1 to 10 makes the file invalid.

- The `caps` defaults are `series` `3` and `film` `1`, and each cap is a whole number in the range 1 to 10.
- `shuffle` without `caps` has no cap: it is the version 1 shuffle, unchanged.
- `rotation` without `caps` uses the defaults, 3 and 1.
- A file with caps in force and `timing.epochHours` below 24 is invalid. Caps are in force in a version 2 file when
  `shuffle` has a `caps` object, and always for `rotation` (with its defaults when it has none). They are not in force for
  the other modes, so a `caps` object next to those modes is valid with any `epochHours`, and so is a version 1 file with
  `caps` and a short epoch, because the caps are unknown fields there and are ignored.
- `timing.blockLength` is ignored by `rotation`: its turns are set by the caps.
- `curated` has no caps: a version 3 file whose `ordering` has `mode` `curated` and a `caps` object is invalid.
  `timing.blockLength` is ignored by `curated` too: its runs are set by `pairUpToMinutes`.

**`ordering.repeatGapMinutes`** (version 3, optional) is a whole number of minutes from **0 to 1440**. **`ordering.pairUpToMinutes`**
(version 3, optional) is a whole number of minutes from **1 to 1440**. They are read by `curated` (see `curated`) and
**ignored by the other five modes**, but they are validated whatever the mode: a value outside its range, or of the wrong
type, makes the file invalid. In a file that says `1` or `2` they are unknown fields. When a file does not give them, a reader
uses the defaults: `repeatGapMinutes` (0 to 1440, default `180`) and `pairUpToMinutes` (1 to 1440, default `35`). A writer may
write a default or leave it out, as for every default.

All six modes work in **epochs** (see Timing), and the pool of each epoch is worked out on its own (see Building the
schedule). This section defines the **order of one epoch's pool**, as a list. Which seed each mode uses:

| Mode | Seed used for the order |
| --- | --- |
| `shuffle` | `seed_n`, the seed of the epoch (see Primitives). Each epoch has its own order. With `caps` it is the capped shuffle, which also uses `seed_n`. |
| `chronological` | None. The order is a sort by a key. |
| `randomInOrder` | The channel `seed` itself, **not** `seed_n`. The order of the groups is the same on every day. |
| `rotation` | The channel `seed` itself, as `randomInOrder`. |
| `inOrder` | None. The order is a sort by a key. |
| `curated` | `seed_n`, the seed of the epoch, so each day has its own order. |

Where in this order a day starts, and how the order is cut into runs and laid out on the clock, is in Building the
schedule.

**There is no base order.** No mode starts from a pre-sorted list: each mode's key (below) is a total order, because its
last part is the canonical ID, and the result is the same whatever order the items are listed in. Never sort by title or
by anything locale-dependent, since collation depends on the language of the device. Comparing canonical IDs is comparing
their 32 ASCII bytes, unsigned, ascending.

**When the order runs out inside an epoch**, the same order starts again from the beginning. The pool is not reshuffled
and the seed does not change.

### `shuffle`

The hash-keyed order, in numbered steps:

1. For every item in the pool, work out the key `splitmix64(seed_n + fnv1a64(idBytes))`, where `idBytes` are the 32
   ASCII bytes of its canonical ID and the sum wraps modulo 2^64.
2. Sort the items by key, ascending, comparing keys **as unsigned 64-bit numbers**.
3. If two keys are equal, the item with the smaller canonical ID bytes (unsigned, byte by byte) comes first.

Two keys are equal only when two different IDs have the same `fnv1a64` value: `splitmix64` is a bijection, so different
sums give different keys, and `seed_n + h` is different for different `h`. That is rare, but it happens: the key-order
vectors hold a real collision.

Removing one item from the pool leaves the relative order of every other item unchanged. That is the reason the order is
a hash key and not a shuffle that depends on the size of the pool.

`docs/rules-format/examples/ordering-shuffle.json` selects it. The vectors for the keys and the resulting order, with a
key that has the high bit set and a tie, are in `docs/rules-format/vectors/key-order.json`.

### `chronological`

Items play in air-date order. For equal dates, the group ID, then the season and episode numbers decide. (So the
episodes of one series are **not** promised to stay in season and episode order: an episode whose date is out of step with
its number plays by its date. A channel that wants a series in its own numbering is a `randomInOrder` channel.)

**The sort key** of an item is this list, compared from left to right:

1. **Undated flag**: `0` if the item has a date, `1` if not. Undated items come after all dated ones.
2. **Date**: the item's premiere (air) date, as a calendar date `YYYY-MM-DD` in UTC, compared as text. The year has four
   digits (`0001` to `9999`), like a production year, so text order is date order. If an item has no premiere date but has
   a production year, its date is `YYYY-01-01`, with the year zero-padded to four digits. If it has neither, it is
   undated. **All undated items have an equal date part** (it is empty); they are told apart by the parts that follow.
3. **Group ID**: the canonical ID of the series, for an episode. For a movie, its own canonical ID. An episode with no
   series is a group of one, like a movie.
4. **Season number**: for an episode. Season `0` (specials) is an ordinary season number: a special sorts by its own date
   like any other episode, and before season 1 when dates are equal. A missing season number sorts after every present
   one, and two missing season numbers tie. A movie has none, which counts as missing.
5. **Episode number**: for an episode; a missing number sorts after every present one, and two missing numbers tie. A
   movie has none, which counts as missing.
6. **Canonical ID** bytes, unsigned, ascending. This is the final tie-break.

The steps:

1. Build the pool.
2. Work out the key of each item.
3. Sort by key. Movies and episodes are mixed in the one list by their dates.

Worked example. Series `1…1` has two episodes, series `2…2` has two, series `3…3` has a special and an undated season 1
episode, and `4…4` is a movie.

| Item | Date | Season, episode |
| --- | --- | --- |
| series `1…1`, first episode | 2020-01-01 | 1, 1 |
| series `1…1`, second episode | 2020-01-08 | 1, 2 |
| series `2…2`, first episode | 2020-01-01 | 1, 1 |
| series `2…2`, second episode | 2020-01-02 | 1, 2 |
| series `3…3`, special | 2019-12-25 | 0, 1 |
| series `3…3`, season 1 episode 1 | none | 1, 1 |
| movie `4…4` | 2020-01-01 | none |

The order is: series 3 special (2019-12-25); then the three items dated 2020-01-01 by group ID, so series 1 episode 1,
series 2 episode 1, movie 4; then series 2 episode 2 (2020-01-02); then series 1 episode 2 (2020-01-08); then, last, the
undated series 3 episode.

Where in this list a day starts, and how it carries on from one day to the next, is in Building the schedule (Where the day
starts). `docs/rules-format/examples/ordering-chronological.json` selects it. The key-order vectors
(`docs/rules-format/vectors/key-order.json`) include this worked example.

### `randomInOrder`

Series are taken in a hash-keyed order, and each series plays its episodes in the order of its numbering.

1. Split the pool into **groups**. All the episodes of one series are one group, with the series' canonical ID as its
   group ID. Each movie is a group of one, with its own canonical ID. An episode with no series is a group of one, with
   its own canonical ID.
2. Order the items **inside** each group by the **in-group key**, compared from left to right. The numbering decides, not
   the air date, because season and episode numbers exist for nearly every episode while air dates exist only when metadata
   matched:
   1. **Season class**: `0` for a regular season (season number 1 or more), `1` for specials (season number 0), `2` for a
      missing season number. So specials play after the last season, and an episode with no season after the specials.
   2. **Season number**. (Two missing season numbers tie here.)
   3. **Episode number**; a missing number sorts after every present one, and two missing numbers tie.
   4. **Undated flag**: `0` for an item with a date (as in `chronological`: a premiere date, or else a production year as
      `YYYY-01-01`), `1` for one without.
   5. **Date**, as text. All undated items have an equal (empty) date part.
   6. **Canonical ID** bytes, unsigned, ascending.
3. Work out the **group key** of each group: `splitmix64(seed + fnv1a64(groupIdBytes))`, where `seed` is the **channel
   seed** from the rules and not `seed_n`. (With `seed_n` the order of the series would change every day.) Sort the groups
   by key ascending, as unsigned numbers; ties go to the smaller group ID bytes.
4. Walk the groups in that order, in rounds. In each round, every group in turn takes its **next turn**: up to
   `blockLength` of its next items, in order. A group that has fewer items left than that takes a short turn with the
   items it has. A group that has no items left is skipped. Each group remembers its own position. The order of the pool
   is the turns written one after another, so for `randomInOrder` it depends on `blockLength`. (One turn is one run: see
   Runs.)
5. When every group is used up, start again at the first group, with every position back at the first item (see "When
   the order runs out", above).

Worked example. Group `B` has episodes B1, B2; group `A` has A1, A2, A3; group `C` has one episode C1. Suppose the
group keys put them in the order B, A, C. With `blockLength` 1 the result is:

```text
round 1: B1 A1 C1
round 2: B2 A2          (C is used up and skipped)
round 3: A3             (B is used up and skipped)
then it starts again: B1 A1 C1 B2 A2 A3 ...
```

With `blockLength` 2, each turn takes up to two items, and the turns are `[B1 B2] [A1 A2] [C1]` in round 1 and `[A3]` in
round 2 (A has one item left, so its turn is short). The order is B1 B2 A1 A2 C1 A3, and then it starts again.

**What stays the same when the pool changes** (these are facts about the order, not about the lineup, which also depends
on runtimes and the clock):

- The order of the groups depends only on the channel seed and the group IDs. Adding or removing a group never reorders
  the other groups.
- The order inside a group is a total sort, so it does not depend on the order the items are listed in.
- Removing the **last** item of a group, or the tail of a group, or a whole group, leaves every other item and every other
  turn exactly where it was.
- Removing an item that is **not** last in its group can move that group's later items into earlier rounds. Other groups'
  items can then be in a different position relative to them, though the order of the groups and the order inside each
  group are unchanged.

`docs/rules-format/examples/ordering-random-in-order.json` selects it. The key of a group is the same function with the
group's canonical ID (the series ID) hashed in place of an item's, and the channel seed in place of `seed_n`. The group
keys, the order inside groups and the turns are in `docs/rules-format/vectors/key-order.json`, with this worked example.

### Groups in version 2

`rotation`, the capped shuffle and `inOrder` work on **groups**. Version 1's `randomInOrder` groups are unchanged: a
series' episodes are one group (its group ID is the series' canonical ID), and a movie, or an episode with no series, is
a group of one with its own ID. The in-group key of `randomInOrder` is the order inside a series group in version 2 too.

In version 2, for `rotation` and the capped shuffle, **films of a collection are one group**, with these rules:

- Only a collection that is named in `sources` (as a `collection` source) groups films. A collection that is only an
  exclusion, or that the channel does not name, groups nothing, even when the library has it.
- Only a **member movie** of the collection is in the group; episodes keep their series' group. A collection of shows
  groups no films.
- The group's ID is the collection's canonical ID.
- A film that is a member of several named collections belongs to the one with the **smallest canonical ID** (compared as
  the 32 ASCII bytes, unsigned).
- A collection that is not in the library snapshot groups nothing.
- In `rotation`, inside a collection group the films play in **release order**: by the key (undated flag, date, canonical
  ID), where the date is the premiere date, or else the production year as `YYYY-01-01`, undated films last, and the ID
  breaks ties. In the capped `shuffle` the films of a collection keep the relative order the version 1 shuffle gives them:
  the mode stays fully random, and only the cap is applied.
- Any other item is a group of one.

A film that is a member of a named collection is grouped however it came into the pool, for example through a library
source. The map is defined by these rules whatever the ordering mode; only `rotation` and the capped `shuffle` read it, and
any other mode ignores it, so an implementation may leave it empty there, since nothing reads it. An `item` source that holds the ID of a library, a series
or a collection matches nothing: it names a movie or an episode.

The map from film to collection ID is worked out for the epoch from the as-of library snapshot (see The pool of an
epoch) and the rules' `sources`. It is an input to building the day, as the items are; an item has no collection field.
(`curated` reads a different map, the franchise map, which is not limited to the collections named in `sources`: see
`curated`.)

For `inOrder`, **every film is a group of one**: collections do not group films in that mode.

The **cap of a group** is the `series` cap for a series, the `film` cap for a collection group, and `1` for an item that
is in neither (an ungrouped item: a cap on one item cannot be exceeded). A series with one episode in the pool keeps the
`series` cap, and a collection with one film keeps the `film` cap: this cannot change an order, but it is the cap the group
has.

Canonical IDs are unique across items, series and collections, so a group ID is never both a series and a collection, or an
item and a collection; the walk and the group keys compare group IDs as one set.

### `rotation`

`rotation` is `randomInOrder` with the groups of version 2 and with each group's turn length set by its cap instead of by
`blockLength`:

1. Split the pool into the groups of Groups in version 2. Order the items inside a series group by the in-group key of
   `randomInOrder`; inside a collection group by release order (this is `rotation` only: the capped shuffle does not
   sort a collection's films).
2. Group keys: `splitmix64(seed + fnv1a64(groupIdBytes))` with the **channel seed**, sorted ascending as unsigned numbers,
   ties to the smaller group ID bytes: exactly as `randomInOrder`.
3. Walk the groups in that order, in rounds. In each round every group takes its next turn: its next items in its own
   order, up to its cap (the `series` cap for a show, the `film` cap for a collection, `1` for a single). A turn **stops
   before the item that would make its runtime longer than `epochMs`**: the turn's runtime is the sum of the `runtimeMs` of
   its items, and an item is added only while that sum stays at or below `epochMs`. A turn always holds at least one item
   (an item longer than `epochMs` is not in the pool, so one item always fits). The turn's runtime is the plain sum of its
   items' runtimes as read at the cut-off, not rounded up to the grid. A turn stops at the first item that does not fit
   and never skips ahead to a shorter later item. The group's next turn continues from the item the turn stopped before,
   so the group keeps its place. A group with fewer items left takes a short turn; a group with none is skipped.
4. When every group is used up, start again at the first group with every position back at the first item.

One turn is one run, as in `randomInOrder`; `blockLength` is ignored. Rotation is **continuous playout** (see Where the
day starts). A group that is the only one left plays its remaining turns one after another, as in `randomInOrder`.

Worked example. Group `B` is a show with episodes B1, B2; group `A` is a show with A1 to A5; group `C` is a collection
named in `sources` with three films C1, C2, C3 in release order. The channel uses the default caps (3 and 1) and the
group keys put the groups in the order B, A, C:

```text
round 1: [B1 B2] [A1 A2 A3] [C1]
round 2:         [A4 A5]    [C2]          (B is used up and skipped)
round 3:                    [C3]          (only C is left)
then it starts again: B1 B2 A1 A2 A3 C1 A4 A5 C2 C3 B1 ...
```

Worked example of a long turn. `epochHours` is 24 and the film cap is 3. A collection named in `sources` has four films
F1, F2, F3, F4 in release order, each 10 hours long, and nothing else is in the pool. The first turn takes F1 and F2
(20 hours); F3 would make 30 hours, more than 24, so the turn stops before it. The next turn starts at F3 and takes F3
and F4 (20 hours). The cycle is `[F1 F2] [F3 F4]`, then it starts again. Without the stop, `[F1 F2 F3]` (30 hours) would be
a run longer than the epoch and would be left out whole (see Left-out runs), taking F1 to F3 out of the schedule.

### The capped shuffle

The capped shuffle is `shuffle` with `ordering.caps`. It starts from the version 1 shuffle order of the epoch and moves
items later to keep the caps and, while one group is tight, to space it. It is a **total function of (the pool,
`seed_n`, the caps and the film-to-collection map)**.

```text
S      = the version 1 shuffle of the pool with seed_n              (unchanged: see shuffle)
queue  = for each group g, its items in the order they have in S
pos(g) = the index in S of the head of g's queue
heads  = the groups that still have items, ordered by pos(g)
count  = the items left in each group; N = the items left in all
counts = the groups ordered by (count descending, group ID bytes ascending)
last = none;  run = 0
legal(g) = not (g == last and run >= cap(g))
tight    = the first group in counts with count * (1 + cap(g)) > cap(g) * N, or none      (at most one: it holds more than half)

repeat N times:
    pick = tight                         if tight is not none and legal(tight)
         = the first g in heads with legal(g)                                         otherwise
         = the first group in heads       if no group in heads is legal ("giving way": only `last` has items left)
    emit the head of queue[pick] and remove it; update heads, count, counts and N
    run  = run + 1 if pick == last, else 1;   last = pick
```

(`tight` and the `heads` and `counts` orders are recomputed after every item; nothing depends on a hash-map order.)

What it guarantees:

- **Order inside a group is the version 1 shuffle's.** Episodes stay individually shuffled: the capped shuffle never puts
  a series in episode order.
- **The cap holds whenever another group has an item left.** No group plays more than its cap in a row while any other
  group still has an item. The walk gives way (and a group runs past its cap) only when `last` is the only group with
  items left.
- **A dominant group's forced run has a lower bound, and the walk keeps close to it.** A **forced run** is a run of one
  group past its cap, because nothing else is left to play. If one group has `d` items and the other groups hold `m` items
  in all, no order that obeys the caps can end with a run of that group shorter than `d - cap * m` (when that is
  positive). The walk's final run is never shorter than this bound, and for a dominant group it meets it in the common
  case (a bound of 70 for 100 episodes, cap 3 and 10 other items gives a final run of 70). While a group is *tight* (it needs every remaining gap) the walk holds the other groups back until it has
  played its cap, so they are used one per `cap + 1` positions, which keeps the forced run at the end as near to the bound as
  the walk can. The bound is not always met: in the worked example below the bound is `5 - 2 * 2 = 1` and the walk ends with
  a run of 2, which is within the cap.
  When no group is tight the walk is the plain "first legal item in shuffle order" walk.
- The work is `O(n log n)` for `n` items.

Runs of the capped shuffle are `blockLength` consecutive items of the order, counted from its first item, as for
`shuffle`. A capped shuffle starts at run 0 each day (see Where the day starts).

Worked example. Show `A` has five episodes (cap 2), and the films `f` and `g` are singles. The version 1 shuffle puts the
items in the order `f A1 g A2 A3 A4 A5` (the episodes are named in the order the shuffle gives them):

```text
N = 7, A has 5:  5 * 3 = 15 > 2 * 7 = 14, so A is tight         pick A1
N = 6, A has 4:  4 * 3 = 12 > 12 is false, nobody is tight       pick f   (the first legal group in S order)
N = 5, A has 4:  12 > 10, tight                                  pick A2
N = 4, A has 3:  9 > 8, tight                                    pick A3  (run of A is now 2)
N = 3, A has 2:  6 > 6 is false                                  pick g   (A may not go on)
N = 2, A has 2:  6 > 4, tight                                    pick A4, then A5
result:  A1 f A2 A3 g A4 A5       (the plain walk gives f A1 g A2 A3 A4 A5: a run of 4)
```

### `inOrder`

`inOrder` plays one group start to finish, then the next, oldest first. Caps are ignored (the mode has no cap), and no seed
is used.

1. Split the pool into groups: a series' episodes are one group (group ID: the series ID), and every other item, **every
   film included**, is a group of one (group ID: its own ID).
2. Order the items inside a series group by the in-group key of `randomInOrder`.
3. Order the groups by the key (**undated flag**, **date**, **group ID** bytes), compared left to right. A group is dated
   when any of its items is dated (an item is dated when it has a premiere date or a production year); the undated flag is
   `0` for a dated group and `1` otherwise, so undated groups come last. The date is that of the group's **first dated
   item in its own order**, as in `chronological` (the premiere date, or else `YYYY-01-01` from the production year), as
   text. The group ID bytes are the final tie-break.
4. The order is the groups one after another, each played start to finish.

The order is cut into runs of `blockLength` consecutive items, counted from the first item, as for `shuffle` and
`chronological` (default `1`). A whole show is not one run, because a run longer than the epoch would be left out.
`inOrder` is **continuous playout** (see Where the day starts).

Worked example. Show `Y` has episodes Y1, Y2, and Y1 premiered on 2000-03-01; the film `F` premiered on 2000-06-01; show `X`
has episodes X1, X2, and X1 premiered on 2001-01-01. The groups sort by their first dated item: Y (2000-03-01), then F
(2000-06-01), then X (2001-01-01). The order is Y1 Y2 F X1 X2, whatever the channel seed.

### `curated`

(Version 3.) `curated` plays every title in turn, in a day-by-day order that spreads titles and franchises apart, gives
short episodes in pairs, and lets a heavier title come round a little more often. Like `shuffle`, it works from the seed of
the day, so each day has its own order, and a day starts at its first run. Its inputs are the pool of the day (after the
trim window of The pool of an epoch), `seed_n`, the two tunables, the grid (`alignToGrid` and `gridMinutes`) and two side
inputs. Everything below is in whole numbers; there is no random stream.

**The tunables.** `repeatGapMs = repeatGapMinutes * 60000` and `pairUpToMs = pairUpToMinutes * 60000`, with the defaults of
Ordering (`180` and `35` minutes, that is `10800000` and `2100000` ms) when the file does not give them.

**The side inputs.**

- **The franchise map.** Every collection in the library snapshot whose name is a **box set name** is a candidate (not
  only the collections named in `sources`, and including a collection that `exclusions` only exclude); a collection that is
  not in the snapshot, one with no name, and one whose name is not a box set name group nothing. A name is a box set name
  when, after the whole **clean** step of the studio fold (an unpaired surrogate is dropped; tab, carriage return, line feed,
  U+2028 and U+2029 become a space; the dropped set is removed; the space set is trimmed from both ends; see Studio names in
  String matching) and then the ASCII fold, it ends with a U+0020 and `collection`; so `Box Set 1 Collection`,
  `box set 1 COLLECTION ` and `Box Set<TAB>Collection` are box set names and `Collection`, `Box Set Collections` and
  `Box Set Collection 2` are not. A collection with no name, or a name that is empty after the clean, is not a box set.
  The rule does not depend on any setting of the app. A film (a movie of the pool) that is a **member
  movie** of at least one such collection is mapped to the collection with the **smallest canonical ID** (compared as the
  32 ASCII bytes, unsigned) among those it is a member of. Episodes are never mapped. The map is worked out from the as-of
  library snapshot (see The pool of an epoch).
- **The weights.** A map from a title ID to a whole number from 1 to 3. A title that is not in it has weight 1. A rules file
  read on its own gives every title weight 1. The title of an item is the series ID of an episode that has one, else the item's
  own ID, so a film's title is its own ID. The ID of a collection is not the title of anything, so a weight given for a
  collection ID has no effect; the weight of a franchise group is the largest weight among its films' titles (step 3 below).

**The walk.** `curated` is the order of the walk below, cut into runs as the walk emits them.

1. **Groups.** An episode that has a series ID is in the **series group** whose group ID is that series ID. A film that is in
   the franchise map is in the **franchise group** whose group ID is the collection ID the map gives. Every other item (a
   film that is in no collection, an episode with no series ID) is a group of one, with its own ID as group ID.
2. **The queue of a group.** The members of a group are first put in order: a series group by the in-group key of
   `randomInOrder`; a franchise group by release order (the key of `rotation`'s collection groups: undated flag, date,
   canonical ID, where the date is the premiere date or else the production year as `YYYY-01-01`); a group of one is its item.
   Let `size` be the number of members and `key(g)` be the **group key** `splitmix64(seed_n + fnv1a64(groupIdBytes))`, with
   the seed `seed_n` of this day and `groupIdBytes` the 32 ASCII bytes of the group ID; keys are compared as unsigned numbers.
   The queue is the members from member number `key(g) mod size` (unsigned remainder, counting from 0) to the last, then
   from the first to the member before it. Each member is in the queue once.
3. **The weight of a group.** `w(g)` is the largest weight, over the members of the group, of the member's title (the
   series ID of an episode that has one, else the item's own ID; weight 1 when the title is not in the weights).
4. **Start.** `clock = 0`. For every group, `last(g)` is none, and its **pass** starts at
   `floorDiv((key(g) mod 5400000) * 6, w(g))`, where `key(g) mod 5400000` is the unsigned remainder.
5. **Repeat** until every queue is empty:
   1. The **live** groups are those whose queue is not empty. A live group is **ready** when `last(g)` is none or
      `clock - last(g) >= repeatGapMs`.
   2. If at least one live group is ready, choose among the ready groups the one with the smallest `pass`; ties go to the
      smaller `key(g)` (unsigned), then to the smaller group ID bytes (unsigned). If none is ready, choose among all live
      groups the one with the smallest `last(g)` (the one that aired longest ago; every live group has aired by then, because a
      group that has not is ready), with the same ties after it: `pass` only separates groups whose `last(g)` is equal, then
      the smaller `key(g)`, then the smaller group ID bytes.
   3. Let `x` be the first member in the chosen group's queue. The **run** is `[x]`, except that it is `[x, y]` when the
      chosen group is a series group, at least two members are left in its queue, `y` is the second of them, and both
      `runtimeMs(x)` and `runtimeMs(y)` are at most `pairUpToMs`. (A film, a franchise group and a group of one never pair.)
   4. Remove the run's items from the queue and emit the run. Then, with `slot(run)` as defined in Grid marks and slots:

      ```text
      last(g) = clock
      clock   = clock + slot(run)
      pass(g) = pass(g) + floorDiv(runtime(run) * 6, w(g))        (runtime(run) is the sum of runtimeMs of its items)
      ```

The `clock`, the `last(g)` values, the `pass` values and the sums of runtimes in the walk (including every pass sum above)
are **signed 64-bit** integers. `key(g)` is an unsigned 64-bit number; `key(g) mod size` and `key(g) mod 5400000` are
unsigned remainders, so they are small and fit any integer type afterwards.

The order is the emitted runs, in the order they were emitted. Each item of the pool is in exactly one run. When a group is
the only one live it is chosen each time (it is the only choice, ready or not), so a pool of a single group, or a pool
shorter than `repeatGapMs`, plays straight through: the walk never stops or leaves a gap for a repeat. The `pass` values
make a title with weight 2 or 3 come back about two or three times as often as a title with weight 1 for the same
airtime; the part of the pass that comes from the key is a jitter of at most 90 minutes of airtime. With
`repeatGapMinutes` `0` every group is always ready.

The repeat gap is measured on the walk's own `clock`, which starts at `0` at the start of the walk, and the walk is made
afresh for each day: the gap restarts at the start of each day, and two titles that end one day and start the next can
air close together. Runs that can never fit a day are left out as for every mode (see Left-out runs), after the walk: the walk does not know
the length of the epoch, so it makes every run of the pool, and its `clock` and the passes count the slot of a run that is
afterwards left out like any other; the left-out runs are then removed from the list of runs and the walk is not made again.

Worked example. `alignToGrid` is true on a 30-minute grid, both tunables are the defaults, every weight is 1, and every
epoch is long enough. Series `A` has four 22-minute episodes a1 to a4 (in queue order), series `B` has two 45-minute episodes
b1 and b2, the franchise `C` has two 100-minute films c1 and c2, and `D` is a single 90-minute film. Suppose `key(g) mod 5400000`
is `0`, `100`, `200` and `300` for `A`, `B`, `C` and `D`, so the starting passes are `0`, `600`, `1200` and `1800`. Times are in
milliseconds in the trace, with `1 min = 60000`.

```text
step clock       ready groups     chosen   run        slot      last(g) pass after
 1   0           A B C D          A        [a1 a2]    60 min    0       0 + 2640000*6 = 15840000
 2   3600000     B C D            B        [b1]       60 min    3600000 600 + 2700000*6 = 16200600
 3   7200000     C D              C        [c1]       120 min   7200000 1200 + 6000000*6 = 36001200
 4   14400000    A B D            D        [d]        90 min    14400000 1800 + 5400000*6 = 32401800
 5   19800000    A B C            A        [a3 a4]    60 min    19800000 15840000 + 15840000 = 31680000
 6   23400000    B C              B        [b2]       60 min    23400000 (b has one left: no pair)
 7   27000000    C                C        [c2]       120 min   27000000
```

At step 2 `A` is not ready (`3600000 < 10800000`); at step 4 `B` is ready at exactly `14400000 - 3600000 = 10800000` (the test is
`>=`) and `A` is ready, but `D` has never aired and has the smallest pass, `1800`, so `D` goes first. The cycle is
`[a1 a2] [b1] [c1] [d] [a3 a4] [b2] [c2]`. `docs/rules-format/examples/v3/curated.json` selects it with the defaults and
`docs/rules-format/examples/v3/curated-tuned.json` sets both tunables.

## Timing

`timing` is an object, and it is **required** because it holds the broadcast-day offset, which has no default. Its other
fields are optional.

| Field | Default | Meaning |
| --- | --- | --- |
| `blockLength` | `1` | Items per run: the number of consecutive items (for example 2 episodes of one series) that play together, back to back, as one run (see Runs). A whole number from 1 to 100. Ignored by `rotation` and `curated`. |
| `alignToGrid` | `true` | Runs start on the grid marks (every `gridMinutes`, see that row) of the clock described below. If `false`, a run starts when the previous one ends. |
| `epochHours` | `24` | The length of an epoch, in hours. One of 1, 2, 3, 4, 6, 8, 12 or 24 (a whole number of hours that divides 24, so that the day repeats). With caps in force it must be 24 (see Ordering). |
| `graceHours` | `6` | The grace window G, in hours: an item joins epoch `n` only if it was added before `epochStart(n)` minus G, so that every device has had time to sync it (see The pool of an epoch). A whole number from 0 to 48. |
| `gridMinutes` | `30` | The spacing of the grid marks, in minutes: 15, 30 or 60. With `60`, `broadcastDay.start` must be on the hour (`:00`), so that the day starts on a grid mark (see Grid marks). |
| `minRuntimeSeconds` | `60` | The shortest runtime, in seconds, that counts as a program: an item shorter than this is left out of the epoch's pool (see The pool of an epoch). A whole number from 1 to 3600. |
| `broadcastDay` | required | When an epoch starts. An object with `utcOffsetMinutes` (required) and `start`. |

**The broadcast day.** `broadcastDay` has two fields:

- `utcOffsetMinutes`: a fixed offset from UTC, in minutes, east positive. **Required; no default.** A whole number from
  -720 to 840 that is a multiple of 15. For example, UTC-5 is `-300`, and Nepal (UTC+5:45) is `345`.
- `start`: the time of day the broadcast day starts, as `HH:MM` on a 24-hour clock at that offset (`00` to `23` hours).
  Only `00` or `30` minutes are allowed. Default `"06:00"`. `start` must be on a grid mark, so it is on :00 or :30 for
  `gridMinutes` 15 and 30, but with 60 it must be :00. A reader treats a file with `gridMinutes` 60 and a `start` off the
  hour as invalid.

**Which offset to write.** A creator picks the zone's **standard (non-daylight-saving) offset**, never the offset the
zone has at the moment. A channel made in New York in July uses `-300` (UTC-5), not `-240`.

The time zone of the device and its daylight saving rules are **never** used. All the times in a rules file are on the
channel's own fixed-offset clock. Epoch `n` is found like this, from a time `t` in milliseconds since
1970-01-01T00:00:00Z:

```text
offsetMs = utcOffsetMinutes * 60000
startMs  = (hours * 60 + minutes) of "start" * 60000
epochMs  = epochHours * 3600000
n        = floor((t + offsetMs - startMs) / epochMs)        (round toward negative infinity)
epochStart(n) = n * epochMs + startMs - offsetMs            (milliseconds since the Unix epoch)
```

`t` is "now": the UTC wall clock of the device, with **no correction for clock error**. (A time source is outside this
format.) `t`, `offsetMs` and `n * epochMs` do not fit in 32 bits, so a client **must** use 64-bit integers for them. This
matters on BrightScript, whose plain integers are 32-bit: use its 64-bit integer type (`LongInteger`), not a float or an
integer.

**Floor division.** "Round toward negative infinity" is `floorDiv(a, b)`, the largest whole number `q` with `q * b <= a`
(here `b` is always positive). Many languages divide by truncating toward zero instead (the `/` and `%` of C, Java and
Kotlin, the `%` of JavaScript, the `\` and `MOD` of BrightScript), and that is wrong whenever `a` is negative. The matching remainder is
`floorMod(a, b) = a - b * floorDiv(a, b)`, always from `0` up to `b - 1`. Worked example: UTC-5, `start` `"06:00"`,
`epochHours` 24, and `t = 0` (midnight at the start of 1970 in UTC):

```text
t + offsetMs - startMs = 0 + (-18000000) - 21600000 = -39600000
floorDiv(-39600000, 86400000) = -1            (truncating division gives 0, which is wrong)
n             = -1
epochStart(-1) = -1 * 86400000 + 21600000 - (-18000000) = -46800000
floorMod(-39600000, 86400000) = 46800000      (the remainder operator % of most languages gives -39600000)
```

That is epoch `-1`, and it is the same example as check 6 under Verify your client. Everything that follows uses `floorDiv`
and `floorMod` wherever it divides or takes a remainder.

The "grid" for `alignToGrid` is the grid marks (every `gridMinutes`) of that same fixed-offset clock, defined exactly in
Building the schedule (Grid marks). **Every epoch boundary is a grid mark of the channel's clock.** It is not a multiple of
the grid in UTC: with a Nepal offset (+345 minutes), a boundary falls at `:15` or `:45` past the UTC hour.

**Daylight saving.** Because the offset is fixed, in a place that observes daylight saving the wall-clock time at which
the broadcast day starts moves by one hour, twice a year. A channel set to start at 06:00 at UTC-5 starts at 07:00
local time in summer where the local zone is UTC-4. This is deliberate: it keeps every device in agreement.

**The end of each epoch is planned.** A program is never cut at an epoch boundary, and it never runs over one: the next
epoch always starts exactly at `epochStart(n + 1)`. The scheduler leaves the last stretch of the epoch to shorter programs
that fit, and to a slate for what is left (see The planned end of the day, in Building the schedule). Nothing is carried
over from one epoch to the next.

Examples: `docs/rules-format/examples/broadcast-day-offset.json` (UTC-5, starts 06:00),
`docs/rules-format/examples/broadcast-day-offset-nepal.json` (UTC+5:45, starts 18:30) and
`docs/rules-format/examples/timing-unaligned.json` (runs of 4, no grid, 12-hour epochs) and
`docs/rules-format/examples/timing-grid-and-grace.json` (UTC+5:30, an hourly grid, 12-hour epochs, 12 hours of grace):

```json
{
  "formatVersion": 1,
  "name": "Eastern Evening",
  "seed": "8",
  "sources": [{ "type": "library", "id": "11111111111111111111111111111111" }],
  "timing": { "broadcastDay": { "utcOffsetMinutes": -300, "start": "06:00" } }
}
```

## Building the schedule

This section turns a channel's rules and a list of items into **what is on, from when to when**, for any moment `t`. It is
the part of the format that makes two TVs on one account show the same program at the same moment. It is written so that a
client can be built from this text alone. Every step is exact, and every number in it is a whole number.

The schedule is a pure function. Its inputs are the settings, the items and the day number `n`. It uses **no state kept
from an earlier day, no device clock setting, no locale and no random-number stream**. Everything below is one day (one
epoch) at a time, and a day is worked out from that day's items alone.

### Inputs and outputs

**Settings.** The numbers the scheduler reads from the rules (see Timing and Ordering). Defaults for absent fields are
filled in from the rules first; the scheduler has none of its own.

- mode: `shuffle`, `chronological`, `randomInOrder`, `rotation`, `inOrder` or `curated`;
- `caps`: none, or the `series` and `film` caps, each 1 to 10. A scheduler always gets both values: the rules layer fills
  in the defaults (3 and 1), the scheduler has none of its own. They are present, and so in force, for `rotation` (which
  always carries them) and for a capped `shuffle`. The rules layer drops `caps` for the other modes, so a scheduler is not given
  them there; one that is ignores them in the order and checks them like any caps (so `epochHours` must be 24 below). This
  holds for `curated` too: a rules file with `curated` and `caps` is invalid (see Ordering), so the rules layer never gives
  caps to a `curated` scheduler, but a scheduler that is handed caps beside `curated` ignores them in the order and does not
  fail because of their presence. Their values are still checked like any caps: a cap outside 1 to 10, or an `epochHours`
  other than 24 with caps present, is an input error;
- `runtimeFilter`: none, or a window `minMs` and/or `maxMs` in milliseconds (`minMinutes * 60000`, `maxMinutes * 60000`)
  and, in version 3, a window `episodeMinMs`/`episodeMaxMs` for episodes and a window `movieMinMs`/`movieMaxMs` for movies,
  each part given or not: a pool condition, see The pool of an epoch;
- `repeatGapMs` and `pairUpToMs`: `repeatGapMinutes * 60000` and `pairUpToMinutes * 60000`, with the defaults `10800000` and
  `2100000` filled in by the rules layer. A scheduler is given both exactly when its mode is `curated` (they are not part of
  the settings of any other mode, which has no use for them), and only `curated` reads them. A scheduler accepts
  `repeatGapMs` from 0 to 86400000 and `pairUpToMs` from 60000 to 86400000 inclusive, as whole milliseconds: it does not
  require a whole number of minutes (the rules layer always gives one), and a value outside its range is an input error;
- `seed`: the channel seed, an unsigned 64-bit number;
- `blockLength`: items per run, 1 to 100 (not read by `rotation`);
- `alignToGrid`: true or false;
- `gridMinutes`: 15, 30 or 60;
- `epochHours`: 1, 2, 3, 4, 6, 8, 12 or 24;
- `utcOffsetMinutes`: -720 to 840, a multiple of 15;
- `start`: minutes after midnight on the channel's clock, on :00 or :30, and on :00 with a 60-minute grid, as Timing says;
- `graceHours`: 0 to 48;
- `minRuntimeSeconds`: 1 to 3600.

With caps in force, `epochHours` must be 24; any other value is an input error at the scheduler. A scheduler also takes the
**film-to-collection map** of the epoch (see Groups in version 2) as an input; it is empty unless the mode is `rotation` or
a capped `shuffle`, or `curated` (which reads the franchise map of `curated` instead: every film of the pool that is a member
movie of any collection of the library snapshot whose name is a box set name, mapped to the smallest such collection ID), and every other mode ignores it.
A scheduler also takes two more **side inputs** (version 3): the **weights**, a map from a title ID to a whole number from 1 to
3 (only `curated` reads them; a title that is absent has weight 1), and the **keeps**, a map from a series ID to a cap in
whole milliseconds, 1 or more, with no upper bound (0 or less is an input error; every mode applies them; see the trim window in
The pool of an epoch). An empty map and an absent
map are the same, and a scheduler that is given none of them gives the same day as a version 2 scheduler.

From these: `offsetMs`, `startMs` and `epochMs` as in Timing, and

```text
gridMs       = gridMinutes * 60000
graceMs      = graceHours * 3600000                 (this is G)
minRuntimeMs = minRuntimeSeconds * 1000
```

**Items.** The items that match the rules (sources, exclusions, the rating ceiling and the filters: see Items and the pool
and Filters). Each item
has:

- its canonical ID: exactly 32 lower-case hexadecimal characters (see Item IDs). Anything else is an input error;
- its kind, exactly `movie` or `episode` (lower case). Any other kind is an input error;
- `runtimeMs`: its runtime, in whole milliseconds, from `RunTimeTicks` as Scheduler inputs from the server says
  (`floorDiv(RunTimeTicks, 10000)`; no runtime, no item). A runtime of 0 or less is not an error: it is below the minimum,
  so the item is left out of the pool.
- `createdAt`: when the server created the item, in milliseconds since 1970-01-01T00:00:00Z;
- `deletedAt`: absent, or the time this client first saw the item was gone (see The pool of an epoch);
- the premiere date, or none: exactly 10 ASCII characters, `YYYY-MM-DD` (UTC), written with ASCII digits only, a year from
  `0001` to `9999` (so `0000` is an input error, as it is for a production year), a month `01` to `12` and a day `01` to
  `31`. The shape is all that is checked: there is no check against a real calendar (`2020-02-31` is accepted and compared
  as text). Anything else is an input error, including leading or trailing whitespace and a time part;
- the production year, or none: a whole number from 1 to 9999. Any other value is an input error;
- for an episode: its series ID, or none, and its season number and episode number (each a whole number of 0 or more, or
  none). A series ID is a canonical ID, like the item's own. A negative number is an input error. A movie that has a
  series ID, a season number or an episode number is an input error.

The settings must be inside the ranges and lists of Inputs and outputs, above: a setting outside its range or list is an
input error. That includes an unknown ordering mode, and a `seed` outside 0 to 2^64 - 1. (A rules reader has normally refused
such a file already.)

Two items with the same canonical ID (the same 32 characters, compared as exact strings) are an input error, whether or
not either is kept. `deletedAt` before `createdAt` is accepted: each is tested against the cut-off on its own. The scheduler rejects the input
and builds no day.

**Output.** A **day**: the epoch number `n`, its start `S = epochStart(n)`, its end `E = S + epochMs` (which is
`epochStart(n + 1)`), and a list of **blocks**. A block is a start and an end, in milliseconds since the Unix epoch, and
its content: either a program (an item ID and a **media start offset**, which is always `0` in version 1) or a **slate**.
A block is **half-open**, `[start, end)`: a time exactly on a block's end belongs to the next block. A block is always
longer than zero. The blocks of a day follow each other with no gap and no overlap, the first starts at `S`, the last ends
at `E`, and no two slates are next to each other. Nothing crosses `S` or `E`.

### Numbers and ranges

All times, offsets, runtimes, slots and sums are whole numbers of milliseconds in **signed 64-bit integers**. All hashes and
seeds are **unsigned 64-bit** (see Primitives). Every division of times rounds toward negative infinity (`floorDiv`) and
every remainder is `floorMod` (see Timing).

- **Valid times.** `t`, every `createdAt` and `deletedAt`, `epochStart(n)` and `epochStart(n + 1)` must lie from `-2^52` to
  `2^52` milliseconds, **both bounds included** (about 142,000 years each way; today is about `1.8 * 10^12`). A time outside
  that range is an **input error**: building the day **rejects the whole input** and builds nothing. This includes any item
  whose `createdAt` or `deletedAt` is out of range, even an item the pool would have left out, and a day whose `S` or `E` is
  out of range. Filtering bad items is the caller's job (the library layer), not the scheduler's. In this range `t + offsetMs - startMs` and `n * epochMs` cannot overflow 64 bits, and they are also
  exact in a double-precision float. `S` and `E` are always multiples of 900,000 ms (15 minutes), so they can never equal
  `-2^52` or `2^52`: the inclusive bounds matter only for item times and `t`.
- **Sums.** A day holds at most `epochMs / minRuntimeMs` programs, and the sum of the slots of a cycle is at most the
  number of items times `epochMs`. So the list of input items must hold **fewer than 2^26 items** (67,108,864); that many or
  more is an **input error** (the whole input is rejected), and below it that sum is below 2^53. The limit counts the list as given, before duplicates are looked for and before
  any filtering.
- **Runtimes.** `runtimeMs` must fit a signed 64-bit integer; that is the caller's job. Any value larger than `epochMs` is
  simply outside the pool (rule 4 of The pool of an epoch).
- **Errors.** An input error, and a `whatsOn` time outside the day, are both **errors**: the call fails, and builds or
  returns nothing. A port may use its own kind of error. The vectors assert only that the call fails.
- **BrightScript** has 32-bit plain integers: use `LongInteger` for every time, runtime, slot and sum, and for each 64-bit
  hash use the two-32-bit-halves method of Primitives. Its `\` and `MOD` truncate: write `floorDiv` and `floorMod`.
- **JavaScript** numbers are exact for whole numbers up to `2^53`, so times, slots and sums fit in a `Number`, but its `%`
  truncates and `Math.floor(a / b)` can round the wrong way for large `a`. Compute `q = Math.floor(a / b)`, then adjust:
  `r = a - q * b`; if `r < 0` then `q = q - 1`; if `r >= b` then `q = q + 1`. Seeds and hashes are not exact in a `Number`:
  use `BigInt` (with `BigInt.asUintN(64, x)` after each wrapping step) or 32-bit halves.

### Epochs and `seed_n`

Day `n` is found from `t` with the formulas of Timing:

```text
n  = floorDiv(t + offsetMs - startMs, epochMs)
S  = epochStart(n) = n * epochMs + startMs - offsetMs
E  = S + epochMs
```

and its seed is

```text
seed_n = splitmix64(seed + nU * 0x9E3779B97F4A7C15)       (all wrapping modulo 2^64)
nU     = n mod 2^64                                       (n is a signed 64-bit number, read as unsigned, two's complement)
```

So for `n = -1` the unsigned `nU` is `0xFFFFFFFFFFFFFFFF` (2^64 - 1), and for `n = -365` it is `0xFFFFFFFFFFFFFE93`. The
product is the low 64 bits of `nU * 0x9E3779B97F4A7C15`, so `(2^64 - 1) * gamma` is `-gamma` modulo 2^64. Worked example:
seed `1`, `n = -1`: the sum is `1 + (2^64 - gamma) = 0x61C8864680B583EC`, and `seed_n = splitmix64(0x61C8864680B583EC) =
0x5692161D100B05E5`. The vectors are in `docs/rules-format/vectors/epoch-seed.json`, and include negative `n`.

Only `shuffle` (with or without `caps`) and `curated` use `seed_n`. `chronological`, `randomInOrder`, `rotation` and `inOrder` do not use it (see Ordering).

### The pool of an epoch

The pool of day `n` is worked out from the items alone, at the moment `S - G`. Let `S = epochStart(n)` and `G = graceMs`.
An item is in the pool of day `n` if and only if **all four** hold:

```text
1. createdAt < S - G
2. deletedAt is absent, or deletedAt >= S - G
3. runtimeMs >= minRuntimeMs
4. runtimeMs <= epochMs
```

A `runtime` filter (version 2) is a fifth condition of the same pool, a setting of the scheduler (`runtimeFilter`, see
Inputs and outputs): it compares `runtimeMs` as read at the cut-off `S - G` with its bounds, both included and in
milliseconds, `minMs <= runtimeMs` and `runtimeMs <= maxMs`, each only when given. In version 3 an episode must also satisfy the
bounds `episodeMinMs` and `episodeMaxMs` that are given, and a movie the bounds `movieMinMs` and `movieMaxMs` that are given,
in the same way and at the same cut-off; the window of the other kind never touches an item. An item the filter rejects is not
in the pool of day `n` (see Filters). The other filters need no time and are applied when the pool is evaluated, as the
sources are. The `runtime` filter is not: it is applied here, where the epoch's pool is cut, so the pool that evaluating the
sources, the exclusions and the other filters gives is the pool **before** the runtime part, and the pool of day `n` is that
set cut by the conditions above and then by the `runtime` filter.

**The trim window** (version 3). After the pool of day `n` is cut by the conditions above (the four conditions and the
runtime filter), the side input `keeps` (a map from a series ID, a title ID, to a cap in whole milliseconds, 1 or more; absent
or empty means none) trims some series. This happens in every mode, and it is the last step of the pool of day `n`. For each
series ID `T` that is in `keeps` and is the series ID of at least one episode of the pool, with `cap` its cap:

```text
1. The s episodes of the pool whose series ID is T, in the in-group key order of randomInOrder (season class, season,
   episode, undated flag, date, ID), are e[0] ... e[s - 1].
2. k = the largest j from 1 to s such that runtimeMs(e[0]) + ... + runtimeMs(e[j - 1]) <= cap.
   If even runtimeMs(e[0]) is above cap, k = 1: k is at least one.
3. If k = s, nothing is removed. Otherwise, with n the day number (a signed 64-bit number) and floorMod as in Timing,
       start = floorMod(floorMod(n, s) * k, s)
   and the pool keeps the k episodes e[floorMod(start + j, s)] for j = 0 ... k - 1, so the window wraps past e[s - 1] to
   e[0]. The other s - k episodes are not in the pool of day n. (floorMod(floorMod(n, s) * k, s) equals floorMod(n * k, s),
   but it is written this way because the product floorMod(n, s) * k is below s * s and cannot overflow, while n * k can.
   All of s, k, start and the sums of step 2 are signed 64-bit integers.)
```

Movies, episodes that have no series ID, and every series that has no entry in `keeps` are untouched, and so is a `keeps`
entry whose ID is not the series ID of any episode of the pool. The count `k` is worked out from the episodes that are in the pool
(the runtimes at the cut-off, every other condition already applied: `s`, `k`, `start` and the sums are worked out on the pool
after the four conditions and after the runtime filter with its per-kind windows, so an episode they left out is neither in
`s` nor in a sum), so it can change from day to day; the window moves
with `n`, so over `ceil(s / k)` days with the same pool every episode of the series is kept at least once. The day number
`n` makes two consecutive days keep adjacent windows. The pool is a set: the order its items are listed in plays no part.

Worked example. A series has four episodes in the in-group order, of 22, 22, 44 and 22 minutes, and `keeps` caps it at 60
minutes (3600000 ms). The prefix sums are 22, 44 and 88 minutes, so `k = 2` (the largest prefix at or under 60). On day
`n = 0` the pool keeps `e[0]` and `e[1]`; on day 1 it keeps `e[2]` and `e[3]`; on day 2, `e[0]` and `e[1]` again; on day
`-1`, `floorMod(-2, 4) = 2`, so `e[2]` and `e[3]`. With a cap of 20 minutes even the first episode is over the cap, so `k = 1`
and one episode is kept each day, `e[floorMod(n, 4)]`.

Non-normative: in StationBreak the `keeps` come from the lineup curation step ([ADR-0023](adr/0023-lineup-curation.md)).
It gives a cap to every series that would take more than a quarter of the channel's curated hours (the cap is at least one hour), so the
share cap becomes this window. A client that works out its own `keeps` agrees with another client only if both work out the
same ones; the format fixes what the window does with them, not how they are chosen.

1. **Added before the cut-off.** An item created inside the grace window waits for the next epoch. The server stamps
   `createdAt`, so two devices that have synced the library past `S - G` agree.
2. **Deleted at or after the cut-off.** An item deleted shortly before `S` still airs in day `n`, so the lineup does not
   shift under the viewer. The player skips it when it is reached (that slot then shows a slate, a matter for the player,
   not for the scheduler). Non-normative: StationBreak's player may fill such a slot with a deterministic stand-in from
   the channel's pool (ADR-0017), chosen client-side; the same channel settings, day, scheduled block start, library
   contents and set of items already tried give the same stand-in, but TVs whose libraries or failures differ can differ
   for that slot. The Day vectors do not change. The scheduler uses the runtime it is given for such an item, which the
   library layer keeps as it was before the deletion.
3. **A program has a minimum length.** An item shorter than `minRuntimeMs` is not a program. Without this a bad runtime of
   a few milliseconds would make millions of blocks a day.
4. **A program fits in an epoch.** A longer item could never play without running over `E`, so it is left out.

**Edits.** Day `n` is built from each item's fields as this client held them before `S - G`. A change first seen at or after
`S - G` takes effect from the next day whose cut-off is after it. A client with no earlier copy (a new install) uses the
oldest copy it has. Devices can disagree about day `n` only when an edit or deletion falls in `[S - 2G, S - G)`. This
covers every field this document reads (the scheduler's fields, and the genres, tags, ratings and memberships the pool rules
use), a runtime edit among them, and a deletion is the same case (the item then stays in
the pool with the runtime it had, as item 2 says). Keeping the earlier copy is the library layer's job.

**What `deletedAt` means.** `deletedAt` is the time at which **this client** first saw that the item was gone from the
server. Jellyfin gives no deletion time, so it is a local observation and it differs between devices: TV A may see the
item gone at 20:00 and TV B at 23:30. Items that are still present have no `deletedAt`.

**The stated disagreement window.** For additions the devices agree, as item 1 says. For deletions they can disagree. Let a
device sync at least every `G`. An item deleted on the server at time `d` is first seen gone at a time between `d` and
`d + G`. So:

- if `d >= S - G`, every device has `deletedAt >= S - G`, and the item is in the pool everywhere;
- if `d < S - 2G`, every device has `deletedAt < S - G`, and the item is out everywhere;
- if `S - 2G <= d < S - G`, it depends on when the device looked: **devices can disagree about day `n`**.

So two devices agree on a day unless an item was deleted, or edited (see Edits), in `[S - 2G, S - G)`.

Worked example, with the default `G` of 6 hours (21,600,000 ms), 24-hour epochs and `minRuntimeSeconds` 60 (so
`minRuntimeMs` is 60,000 and `epochMs` is 86,400,000). The cut-off is `S - 21600000`:

```text
createdAt = S - 21600001   in the pool (before the cut-off)
createdAt = S - 21600000   NOT in the pool (the test is "<", so an item created exactly at the cut-off waits)
deletedAt = S - 21600000   in the pool (the test is ">=")
deletedAt = S - 21600001   NOT in the pool
runtimeMs = 59999          NOT in the pool       runtimeMs = 60000      in the pool
runtimeMs = 86400000       in the pool           runtimeMs = 86400001   NOT in the pool
```

### The order

The pool is put in order by the rules of Ordering, for the mode of the channel:

- `shuffle`: with `seed_n` of this day.
- `chronological`: by the six-part key.
- `randomInOrder`: groups keyed with the channel `seed`, items in a group by the in-group key, and the walk in turns of up
  to `blockLength` items (this is the **in-group key** and the **group key** of Ordering, and the order depends on
  `blockLength`).
- `rotation`: as `randomInOrder` with the groups of Groups in version 2 and turns set by the caps.
- `shuffle` with `caps`: the capped shuffle, with `seed_n` of this day, the caps and the film-to-collection map.
- `inOrder`: groups by the key of `inOrder`, each played start to finish.
- `curated`: the walk of `curated`, with `seed_n` of this day, the tunables, the franchise map and the weights; its order is
  already cut into runs.

The result is the **order** of day `n`. Those sections are the definition; this section only uses them.

### Runs

A **run** is a group of items that play back to back. The order is cut into runs, **once per day**:

- For `shuffle` (also with caps), `chronological` and `inOrder`: a run is `blockLength` consecutive items of the order,
  counted from the first item.
  The last run is shorter when the number of items is not a multiple of `blockLength`. Runs never span the end of the
  order: when the order starts again, the count starts again with it.
- For `randomInOrder`: one **turn** is one run. (The turns of Ordering already hold up to `blockLength` items, and a short
  turn is a short run.)
- For `rotation`: one **turn** is one run. (A turn holds up to the group's cap of items; `blockLength` plays no part.)
- For `curated`: one **step of the walk** is one run: a pair of episodes is one run of two items, and anything else is a run
  of one. `blockLength` plays no part.

Worked example: seven items 1 to 7 in order, with `blockLength` 3, are the runs `[1 2 3] [4 5 6] [7]`. When the order
runs out it starts again with `[1 2 3]`, not with `[7 1 2]`. With `blockLength` 100 the whole order is one run.

The **runtime of a run** is the sum of the runtimes of its items, because they play back to back.

### Grid marks and slots

The grid is on the **channel's clock**, which is UTC plus `offsetMs`. A **grid mark** is any time `t` with

```text
floorMod(t + offsetMs, gridMs) == 0
```

`S` is always a grid mark: `S + offsetMs = n * epochMs + startMs`, `epochMs` is a whole number of hours, and `startMs` is on
the grid (a `start` on :00 or :30, or on :00 when `gridMinutes` is 60). So every day starts on a grid mark. The grid is
not a grid of UTC: with `utcOffsetMinutes` 345 (Nepal) and `gridMinutes` 30 the marks are at UTC `:15` and `:45`.

The first grid mark at or after a time `t` is

```text
nextMark(t) = t + floorMod(-(t + offsetMs), gridMs)
```

(`nextMark(t) = t` when `t` is already a mark.) When `alignToGrid` is false there is no grid and `nextMark(t)` means `t`.

The **slot** of a run is the time it takes up on the clock:

```text
slot(run) = ceil(runtime(run) / gridMs) * gridMs      when alignToGrid is true     (ceil(a / b) = floorDiv(a + b - 1, b))
slot(run) = runtime(run)                                when alignToGrid is false
```

### Left-out runs

A run whose slot is **longer than `epochMs`** can never fit in a day, and it would make every day that starts inside it
stall at the same place. So it is **left out**: removed from the list of runs. The list that remains, in order, is the
**cycle**: runs `0` to `B - 1`, `B` runs in all. Runs are formed from the whole order first, and then removed, so a run
that remains keeps exactly the items it had. The items of a left-out run are not in the cycle at all; the planned end of the
day never visits them. The runs on each side of a left-out run become neighbours, so in the capped shuffle two turns of one
group can end up together (see The planned end of the day, Where a cap can be exceeded).

A `rotation` turn is already cut so that its runtime is at most `epochMs` (see `rotation`), so no `rotation` run is left
out. The other modes cut runs by `blockLength` alone, and a long run of those is left out as described. A `curated` run
(a pair of episodes or a single item) can have a slot longer than `epochMs`, for example a pair of 35-minute episodes in a
one-hour epoch, or a single item whose runtime rounded up to the grid passes `epochMs`: such a run is left out as described,
after the walk, and the runs on each side of it become neighbours.

A run whose slot is **equal** to `epochMs` stays in the cycle: it fits only on a day that starts with it.

If `B = 0`, because the pool is empty or because every run was left out (for example a large `blockLength` with short
epochs), the **whole day is one slate**, `[S, E)`. When the pool is not empty that is a misconfiguration, and a client
that builds rules should warn about it.

`N` is the number of items in the cycle.

### The regular layout

The day is laid out from a start run `j` (see Where the day starts) with a cursor `c = S`, and a run index `i = j`:

1. If `c >= E`, stop; the day is laid out.
2. Take run `i`. Its start is `r = nextMark(c)` (so `r = c` when `alignToGrid` is false).
3. If `r + runtime(run i) > E`, the run does not fit. Stop the regular layout and go to The planned end of the day, with
   this run as the **first run that did not start**. (The test uses the run's actual runtime, not its slot.)
4. Otherwise, if `r > c`, there is a gap before the grid mark: put a **slate** `[c, r)`. Then put the run's items back to
   back, one block each, starting at `r` (see One block per item). Set `c` to the end of the last of them, and `i` to
   `(i + 1) mod B`. Go to step 1.

The cycle wraps **inside a day, in every mode**, including `shuffle`: after run `B - 1` comes run `0`, so a day longer than
one cycle plays the cycle again. If the regular layout reaches `E` exactly (step 1), the day is complete: there is no
planned end, and no closing slate. Otherwise the planned end finishes the day.

A gap before a grid mark is a slate only when a run is placed after it. A gap that has no run after it is part of the
closing slate (see The planned end of the day).

### Where the day starts

**`shuffle`, with or without caps, and `curated`:** the start run is run `0` of the day's own order. Each day has its own
seed, so each day has its own order, and nothing carries over: `j = 0`. The capped shuffle starts at run `0` each day too,
and so does `curated`, whose walk begins afresh at the start of the day, with its own clock at `0`: no repeat gap is carried
from the day before.

**`chronological`, `randomInOrder`, `rotation` and `inOrder`:** the start run is the run of the channel's **continuous playout** that contains
`S`. Nothing is replayed from earlier days; `j` is worked out directly.

The **continuous playout** is the cycle played forever, back to back, each run taking its **slot**, starting from the
**origin** `-offsetMs`: the time at which the channel's clock reads midnight at the start of 1970-01-01. (The origin is a
grid mark for every offset, because `floorMod(-offsetMs + offsetMs, gridMs)` is `0`.) In steps:

```text
1. Q[0] = 0;  Q[i + 1] = Q[i] + slot(run i)  for i = 0 .. B - 1.          Q[B] = C, the length of one cycle, in ms
2. pos   = floorMod(S + offsetMs, C)                                       where S + offsetMs is S on the channel's clock
3. j     = the largest i from 0 to B - 1 with Q[i] <= pos                  (the runs' slots are positive, so Q grows;
                                                                            a binary search finds it)
4. delta = pos - Q[j]                                                      0 <= delta < slot(run j)
```

The day starts at run `j`, **at `S` exactly**, although the continuous playout is `delta` into that run at `S`. So the
day is `delta` late against the continuous playout, and it never starts a run part-way through. The run after run
`B - 1` is run `0` (the playout wraps). Start at run `j`, not at the run after it: starting after `S` would lose the run
that crosses `S` at every seam, which is worse.

The cycle used is the day's own: it is made from day `n`'s pool. When the library changes, `C` changes and the channel
resumes at a different place in its run. A stateless schedule cannot avoid that.

### Worked example 1: a day end to end

A chronological channel on UTC (`utcOffsetMinutes` 0), `start` `"00:30"`, `epochHours` 4, a 30-minute grid, `alignToGrid`
true, `blockLength` 1. The pool is three movies of 90 minutes each, M1, M2 and M3, in air-date order. Times below are
minutes since 1970-01-01T00:00Z (multiply by 60,000 for milliseconds).

```text
runs:   [M1] [M2] [M3]            slot 90 each            Q = 0, 90, 180, 270        C = 270
day n   S      pos = S mod 270    j   delta    blocks
 0      30     30                 0   30       M1 [30,120)  M2 [120,210)  slate [210,270)
 1      270    0                  0   0        M1 [270,360) M2 [360,450)  slate [450,510)
 2      510    240                2   60       M3 [510,600) M1 [600,690)  slate [690,750)
 3      750    210                2   30       M3 [750,840) M1 [840,930)  slate [930,990)
```

Day 0: `S = epochStart(0) = 0 * 14400000 + 1800000 - 0`, i.e. minute 30. After M1 and M2 the cursor is at minute 210. M3 would
start at 210 and end at 300, after `E = 270`, so the regular layout stops and the planned end of the day (below) places
nothing: M3 does not fit, and there are no later items. The rest is a slate. Day 2: `pos = 240` lies in `[Q[2], Q[3]) = [180, 270)`, so `j = 2`
and `delta = 60`: day 2 starts with M3 at minute 510 on the dot.

### The continuity guarantee

For `chronological`, `randomInOrder`, `rotation` and `inOrder`, with an **unchanged pool** (the same cycle on the days compared):

- **No run played in a day's regular layout is played again at the next seam.** The next day never repeats a run.
- Day `n` starts `delta_n` late against the continuous playout, `0 <= delta_n < slot(run j_n)`. So the runs from the first
  run that day `n` did not start, up to but not including the first run of day `n + 1`, **may be skipped**. They are not
  played on day `n` and day `n + 1` has moved past them.
- Items that the planned end of day `n` placed may air again at the start of day `n + 1`, because day `n + 1` starts its
  run from the run's beginning (see issue #546).
- **Nothing is skipped when every slot is the same length and that length divides `epochMs`.** The runs of the day then fill
  it exactly, so the last one ends at `E`, the next day starts at the next run, and `delta` does not matter. Example:
  60-minute slots in a 4-hour epoch with a `:30` start give `delta = 30` minutes, and still skip nothing. Equal slots that
  do **not** divide `epochMs` can skip (worked example 1 above: 90-minute slots in a 4-hour epoch). Also, when
  `delta_n = 0` nothing is skipped: for example half-hour episodes in runs of 1 on a 30-minute grid have `C` a multiple of
  `gridMs`, so `pos` is too.

A skip needs leftover time at the seam. In worked example 1, count the runs of the continuous playout 0, 1, 2, 3 ... (run
`k` is cycle run `k mod 3`). Day `n` starts at continuous run `K_n = floorDiv(S + offsetMs, C) * B + j` and starts `r_n`
runs in its regular layout (counting each time round the cycle), so `u_n = K_n + r_n` is the first it did not start:

```text
day   K    r    u = K + r    next day's K    skipped
 0    0    2    2            3               run 2 (M3): it did not fit on day 0 (60 minutes left, 90 needed), and
                                              day 1 starts at run 3, the M1 that contains minute 270
 1    3    2    5            5               none
 2    5    2    7            8               run 7 = cycle run 1 (M2)
```

Three 90-minute movies on a 30-minute grid in a 4-hour day starting 00:30 skip run 2 at the first seam. This is the price
of a stateless schedule: not skipping would need the previous day's `delta`, which chains the days together.

For `shuffle` (also with caps) the order is new on each day, so there is no continuity to promise.

### The planned end of the day

When step 3 of the regular layout stops (run `i` would not end by `E`), the rest of the day is **planned**, not cut. Let `c`
be the end of the last block placed (it is `S` if none). Number the items of the cycle `L[0] ... L[N - 1]`, runs in order
and items in order inside each run. Here `i` is the index of the run that did not fit, taken modulo `B` (the regular layout may have gone round the cycle
more than once). Let `f` be the number of items in runs `0` to `i - 1`: `L[f]` is the first item of run `i`.

1. **Visit order.** Starting at `L[f]`, visit items in order. For `shuffle` (also with caps) and `curated`, wrap from `L[N - 1]`
   to `L[0]` and visit exactly `N` items (so every item once). For `chronological`, `randomInOrder`, `rotation` and `inOrder`, **do
   not wrap**: stop after `L[N - 1]` (that is `N - f` visits). So an earlier episode is never replayed after a later one.
2. **Each visit.** For the visited item `x`, with the cursor `c`:
   1. (`chronological`, `randomInOrder`, `rotation` and `inOrder` only.) If an earlier visit of this fill **passed over** an
      item of the same **group** as `x`, then `x` is not eligible. Skip it: nothing changes. The group of an item is its
      series ID; a movie, and an episode with no series, are a group of one (their own ID). For `rotation` the group is the
      group of Groups in version 2: a film of a named collection is in its collection's group, so passing over one film
      of a collection makes the rest of the collection ineligible. For `inOrder` the group is the series, or the item
      itself (every film is a group of one).
   2. Its start is `s = nextMark(c)` (just `c` when `alignToGrid` is false).
   3. If `s + runtimeMs(x) <= E`, **place** it. Its **actual runtime** is used, not a slot. If `s > c`, put a slate
      `[c, s)` first. Put the program block `[s, s + runtimeMs(x))`. Set `c` to its end.
   4. Otherwise `x` is **passed over**. (`chronological`, `randomInOrder`, `rotation` and `inOrder`: remember that its group
      was passed over. For `shuffle`, with or without caps, and for `curated`, nothing: any later item may still fit.)
3. **Close.** If `c < E`, put one slate `[c, E)`. A grid gap that has no item after it, and any time too short for any
   item, are all part of this one slate.

Every item placed here is a **block of its own** and takes no part in run grouping: `blockLength` plays no role in the
planned end. Nothing is cut and nothing runs past `E`. The work is one pass over at most `N` items. An item placed may
already have aired earlier that day (when the cycle is shorter than the day, or for the wrap of `shuffle` and `curated`): the
planned end does not check.

Why the group rule: without it, when episode 3 of a series does not fit but episode 4 does, episode 4 would air before
episode 3 has aired. So once an item of a group is passed over, no later item of that group is eligible. `shuffle` has no
such limit, because its order has no series order to keep (the capped shuffle keeps episodes shuffled, so it has none
either, and wraps as `shuffle` does). `curated` is placed like `shuffle` here: it also wraps, and it has no group rule, so the
planned end of a curated day may place an episode before an earlier episode of its series and does not keep a pair together.

**Caps are not enforced in the planned end.** The planned end places items one by one by their fit, so a cap can be
exceeded there.

**Where a cap can be exceeded.** A cap holds in the order a mode produces. It can be exceeded in three places only:

- `rotation`'s **cycle wrap**: the last turns of one cycle can be followed by the first turn of the next, so a show can run
  from its tail into its head. There is no rule to mend this seam;
- the capped shuffle's **in-day wrap**: when the cycle is shorter than the day, the order starts again and its tail meets
  its head;
- the **planned end** of the day, as above.

A separate consequence of the left-out rule: when a run is left out (see Left-out runs), the runs on each side of it
become neighbours, and in the capped shuffle that can put two turns of one group together. (No `rotation` run is left out.)

**Giving way is not an exceed case.** When only one group has items left, it plays on: in the capped shuffle the walk
gives way to `last`, and in `rotation` the lone group takes its turns one after another, as `randomInOrder` does. That is
the designed behaviour, and the forced run it makes at the end of the order is what the lower bound above measures.

Worked example 2: UTC (`utcOffsetMinutes` 0), `start` `"00:00"`, `epochHours` 2 (so day 0 is `[0, 120)` minutes), a 30-minute
grid, `alignToGrid` true, `blockLength` 1, `graceHours` 6, `minRuntimeSeconds` 60, day `n = 0`. The channel seed is `8` for
`chronological` (it plays no part) and `4` for `shuffle`. Every item was created long before (`createdAt` -10^12 ms) and
none is deleted. The items, in the vector file's IDs (shortened to the last digits: `...01` is
`00000000000000000000000000000001`):

```text
ID    kind     runtime   premiere date   series   season, episode
...01 movie    75 min    2020-02-01      none     none            (P)
...02 episode  45 min    2020-02-02      ...63    1, 1            (A1)
...03 episode  20 min    2020-02-03      ...63    1, 2            (A2)
...04 movie    25 min    2020-02-04      none     none            (B)
```

`chronological` order is by date: P, A1, A2, B. Slots are 90, 60, 30 and 30, `C = 210`, `S = 0`, so `pos = 0` and `j = 0`.
For `shuffle` with seed `4`, the order of day 0 happens to be P, A1, A2, B as well, and the layout starts at run 0.

```text
regular layout:  P [0,75). Next run A1: nextMark(75) = 90, and 90 + 45 = 135 > 120. It does not fit. i = 1, f = 1, c = 75.
chronological:   visit A1: s = 90, 135 > 120, passed over (group A).
                 visit A2: group A was passed over, not eligible (it would fit, but A1 has not aired).
                 visit B:  s = 90, 90 + 25 = 115 <= 120, placed: slate [75,90), B [90,115). Close: slate [115,120).
shuffle (seed 4):  the order is P A1 A2 B too, so visit A1, A2, B, P (N = 4, wrapping):
                 A1: no.   A2: s = 90, 110 <= 120, placed: slate [75,90), A2 [90,110).
                 B: s = nextMark(110) = 120, 145 > 120, no.   P: s = 120, no.   Close: slate [110,120).
```

### One block per item

A **block** holds one item, or is a slate. A run is played as **consecutive blocks**, one per item, each starting where the
last ended: item `k` of a run that starts at `r` starts at `r` plus the runtimes of items `0` to `k - 1`, and ends
`runtimeMs` later. The media start offset of every program block is `0`: no program is ever joined part-way through at the
start of a block.

An empty cycle (`B = 0`) makes the whole day one slate block `[S, E)`.

### `whatsOn`

`whatsOn(day, t, k)` answers "what is on at `t`" from a built day:

1. `t` must satisfy `S <= t < E`. A `t` outside the day is an **error**, not a guess: the caller picks the day (`n` from
   `t` as in Epochs and `seed_n`), and builds day `n + 1` or `n - 1` itself. `k` must be 0 or more: a negative `k` is an error.
2. The block is the **last block whose start is at or before `t`**, found by binary search (at most `ceil(log2(number of
   blocks))` comparisons). Blocks are half-open, so a `t` exactly on the end of one block is in the next one.
3. The **offset** is `mediaStartOffset + (t - blockStart)` for a program: the position in the media to play from. A
   **slate has no offset**.
4. The **next `k` blocks** are the `k` blocks that follow in the same day (fewer when the day ends). The caller builds the
   next day for any more.

Worked example, for day 0 of worked example 1 (times in minutes, multiply by 60,000 for milliseconds):

```text
t = 120               block M2 [120,210), offset 0              (the edge belongs to the later block)
t = 119 min 59.999 s  block M1 [30,120),  offset 5399999 ms     (t is 7199999 ms; 7199999 - 1800000)
t = 240               the slate [210,270), no offset
t = 270               an error for day 0: build day 1, where it is M1 at offset 0
whatsOn(day 0, t = 30, k = 2)  ->  M1 at offset 0, next: M2, then the slate
```

The scheduler keeps no cache: the caller keeps the day.

### No random stream, no bounded integers

Nothing in this section draws from a random-number stream, and no step needs an integer chosen from a range. Every order
comes from a **hash key** and a total sort, and the planned end of the day is a deterministic scan. So the format defines
no random-number generator and no bounded-integer step (such as rejection sampling), and a client **must not** add one. The
only primitives are `splitmix64` and `fnv1a64` (see Primitives).

### Vectors for the schedule

The anchors for this section are in `docs/rules-format/vectors/`: `epoch-math.json` and `epoch-seed.json` (the epoch number,
`epochStart` and `seed_n`), `key-order.json` (the three orders, the group keys, the in-group order and runs) and
`lineup.json` (whole days: rules subset, items and `t`, with the block that is on and its offset). Version 2 adds
`key-order-v2.json` and `lineup-v2.json`, of the same shapes with `caps`, `films` and `runtimeFilter`. Version 3 adds
`pool-v3.json` (the pool: `studio` sources and exclusions, the version 3 filters and the franchise map; a snapshot collection there may carry a `name`, which the box set names of the franchise map read), `key-order-v3.json`
and `lineup-v3.json`, of the same shapes as the version 1 and 2 files with the franchise map (`franchises`), the weights,
the keeps, the two tunables (`repeatGapMs` and `pairUpToMs`) and the per-kind runtime windows. They were computed with a reference that was
written from this text alone, not with the code that is tested. Version 1 is final (amended once, on 2026-10-19), so a wrong
vector now means version 2, and for version 2 now means version 3.

## Filler

`filler` is an optional object that says what plays in the gaps. **In version 1 the filler section is reserved**: the
format defines it and a reader must read it and keep it, but nothing schedules filler yet.

| Field | Meaning |
| --- | --- |
| `mode` | One of `none`, `padToGrid` or `betweenEveryN`. Default `none`. |
| `every` | A whole number from 1 to 1000. Required when `mode` is `betweenEveryN`: one filler item after every N programs. A reader ignores `every` when the mode is not `betweenEveryN`, and keeps it. |
| `source` | Where filler comes from: a `library`, a `collection` or a `tag` source, in the same form as in Sources. Required for `padToGrid` and `betweenEveryN`. |

**A reader that does not schedule a filler mode treats the file as unsupported.** In version 1 no reader schedules filler,
so a version 1 reader treats every file whose filler `mode` is anything other than `none` as **unsupported** (and reports
"update required"), in the same way as for an unknown kind (see Unknown fields and unsupported kinds). It does not
play the channel without the filler. A writer should not write a filler mode other than `none` until a version of the
apps schedules it. The examples below are valid files, and a version 1 reader keeps them, but it does not schedule them.

How a filler mode other than `none` is scheduled is defined by a later `formatVersion`, and a file that uses one is written
with that version.

- `none`: no filler. `docs/rules-format/examples/filler-none.json`.
- `padToGrid`: fill the time before the next grid mark. Note: padToGrid only has gaps to fill when `alignToGrid` is
  true (the default). `docs/rules-format/examples/filler-pad-to-grid.json`, with a library as filler source, and
  `docs/rules-format/examples/filler-tag-source.json`, with a tag.
- `betweenEveryN`: one filler item after every N programs. `docs/rules-format/examples/filler-between-every-n.json`,
  with a collection as filler source:

```json
{
  "formatVersion": 1,
  "name": "Station Breaks",
  "seed": "9",
  "sources": [{ "type": "library", "id": "11111111111111111111111111111111" }],
  "timing": { "broadcastDay": { "utcOffsetMinutes": 0 } },
  "filler": {
    "mode": "betweenEveryN",
    "every": 3,
    "source": { "type": "collection", "id": "66666666666666666666666666666666" }
  }
}
```

## Seed

`seed` is a **required** field. A missing or malformed seed makes the file invalid and the channel is not scheduled.
There is no rule that says "if there is no seed, work one out". The seed is the only seed input to the schedule. (The
channel's own ID is never an input.)

**Form.** The seed is a **decimal string** of an unsigned 64-bit number, in canonical form:

- digits `0` to `9` only: no sign, no spaces, no `+`, no exponent;
- no leading zeros, except that zero itself is `"0"`;
- the value is at most 18446744073709551615 (2^64 - 1).

So `"0"`, `"7"` and `"18446744073709551615"` are valid; `"007"`, `"-1"`, `""`, `"abc"` and `"18446744073709551616"` are
not. It is a string and not a JSON number because a JSON number above 2^53 loses its low bits in JavaScript and in
BrightScript. A reader must reject a seed that is a JSON number.

A reader must write the seed back exactly as it read it. Rewriting a file never changes its seed.
`docs/rules-format/examples/tag.json` uses the largest seed, and `docs/rules-format/examples/genre-with-max-rating.json`
uses a seed with the high bit set.

The JSON Schema checks the shape of the seed (the pattern: no sign, no leading zeros, at most 20 digits). It cannot check
the range. A reader checks the range.

**How seeds are made.** The seed lives in the rules, so a client does not need to repeat how it was made. It only needs
this section to *create* a channel.

- **A custom channel** gets 64 random bits from the platform's secure random source when it is created. Write them as an
  unsigned decimal string.
- **An automatic channel** (one per library, genre or collection, made by the app when you first sign in) gets a derived
  seed, so that two TVs on the same account make the same channel with the same seed. The seed is written into the rules
  the app generates. To derive it, build these bytes and hash them:

  1. the 32 ASCII characters of the server ID in canonical form (lower-case hex, no hyphens);
  2. one byte `0x00`;
  3. the kind, as ASCII: `library`, `genre` or `collection`;
  4. one byte `0x00`;
  5. the key: for a library or a collection, the 32 ASCII characters of its canonical ID; for a genre, the exact UTF-8
     bytes of the genre name, with no case folding and no Unicode normalisation.

  `seed = splitmix64(fnv1a64(bytes))`, written as an unsigned decimal string. The functions are defined in Primitives.

  Worked examples (server ID `11111111111111111111111111111111`; all in `docs/rules-format/vectors/auto-seed.json`, which
  also lists the exact bytes and the 32-bit halves):

  | Kind | Key | `fnv1a64` (hex) | Seed (decimal) | Seed (hex) |
  | --- | --- | --- | --- | --- |
  | `library` | `22222222222222222222222222222222` | `d22a74589ce8af26` | `16427903836559823302` | `e3fba380266cd1c6` |
  | `collection` | `33333333333333333333333333333333` | `1559bdec1b2bb6cd` | `1052978938816395171` | `0e9ceec4141e77a3` |
  | `genre` | `Animé` | `edd5cb810fd750ff` | `182484535837226886` | `028850b7ef947786` |
  | `genre` | `科幻` | `28f84939c584258a` | `12794934325292202904` | `b190bcfa777e7b98` |
  | `recipe` | `little-farm` | `e006c64fd4ef627a` | `15418518407132515164` | `d5f994ccebf48b5c` |
  | `recipe` | `odds-and-ends` | `23856fa7efc0fbbe` | `3985964905457314131` | `3750fdf684f4e953` |

  The library and the `科幻` seeds have the high bit set. `animé` (lower case) and `Animé` written as `Anime` plus a
  combining acute accent are different genre names and give different seeds: nothing is folded or normalised.

- **A starter channel** (a themed channel the app offers from its built-in recipes) gets a derived seed in the same way,
  with the kind `recipe` and the recipe key as the key: 1 to 64 characters of `a-z`, `0-9` and `-` (for example
  `little-farm`), written as ASCII bytes. The server ID is the one in step 1. (Added 2026-10-19; creation guidance only,
  no reader or lineup changes.)

- **Reseeding** a channel, and **duplicating** one, give the new channel a fresh random seed (a custom channel's rule).
- **Importing** a rules file keeps the seed byte for byte.
- **Editing** an automatic channel turns it into a custom channel and keeps its seed.

Changing the channel's name, or any field other than `seed`, never changes the seed.

## Name

`name` is the display name of the channel: 1 to 100 Unicode **code points** (not UTF-16 units and not bytes; an emoji
outside the Basic Multilingual Plane is one code point), free text. It is the only cosmetic field that travels with the
rules, so that a channel shared from one TV is recognisable on another. It is never used for matching, for ordering or
for working out the seed. Channel number and logo are not in the format.

`docs/rules-format/examples/name-100-code-points.json` has a name of exactly 100 such characters, each of which takes two
UTF-16 units, so a reader that counts UTF-16 units wrongly rejects it.

## Maximum rating and the rating ladder

`maxRating` is an optional rating **ceiling**. Absent means no ceiling: every item is eligible. (The ceiling and the version
2 `filters` are both applied: an item has to pass both. A filter never relaxes the ceiling, and a ceiling never changes
what a filter means. See Filters.) The value must be one of
the ladder names below, spelled exactly like this (including the capital A in `Approved`). A value that is not on the
ladder makes the whole file invalid. It is never read as "no ceiling".

The ladder follows the table Jellyfin ships for United States ratings (TV Parental Guidelines and film ratings on one
scale). Ratings on the same rank are the same tier. A ceiling allows an item whose rank is at or below the ceiling's rank.

| Rank | Ratings |
| --- | --- |
| 0 | TV-Y, TV-G, G, Approved |
| 1 | TV-Y7, TV-Y7-FV |
| 2 | TV-PG, PG |
| 3 | PG-13 |
| 4 | TV-14 |
| 5 | R |
| 6 | TV-MA, NC-17 |
| 7 | TV-X, TV-AO |

### Normalising an item's rating

The server gives each item a free-form rating string (or none). The steps below were checked against the rating table and
lookup in Jellyfin 12.1 (`Localization/Ratings/us.json` and `LocalizationManager.GetRatingScore`): every United States
string in that table gets the tier below, and Jellyfin's lookup is also case-insensitive and trims the string. Before
looking a string up:

1. Trim spaces (U+0020) from both ends. Nothing else is trimmed: a tab, a line break or a non-breaking space is not a
   space, and the string then has no rank.
2. Convert the ASCII letters `a` to `z` to upper case (nothing else: no locale). `Approved` becomes `APPROVED`, which is
   the ladder name `Approved` (rank 0, the same tier as `G`).
3. Look the result up on the ladder. If it is there, that is its rank.
4. Otherwise, accept it if it is `TV-PG-`, `TV-14-` or `TV-MA-` followed by **one** descriptor group: a non-empty run of
   the letters `D`, `L`, `S` and `V`, in that order, each at most once, for example `-V`, `-DL` or `-DLSV`. For `TV-MA`
   the letters are `L`, `S` and `V` only. These are exactly the strings Jellyfin lists for those tiers. A descriptor never
   changes the age tier: `TV-14-V` is `TV-14`, and `TV-MA-LSV` is `TV-MA`. (`TV-Y7-FV` is already on the ladder.)
5. Anything else has no rank. That includes `TV-14-VD` (letters out of order), `TV-MA-D`, `TV-G-V` and `TV-14-`.

Jellyfin also understands strings this format does not: a country prefix such as `US:PG-13`, a leading `Rated `, a plain
number such as `13` or `16+`, and a list such as `TV-14/TV-MA`. They have no rank here, so they are blocked under a ceiling.
That is on purpose: this format fails closed, and a client that wants them must map them to a ladder name first.

**Fail closed.** Under a ceiling, an item is allowed **only if** it has a rank at or below the ceiling's rank. An item
with no rating, a blank rating (one or more spaces; the empty string counts as no rating), `NR`, `Unrated`, or any rating
that is not on the ladder (for example a British or German rating such as `FSK 16`) is **not** allowed. An episode is held
to the same test on its series too: it is **not** allowed when its series has a rating that has no rank or is above the
ceiling (`NR`, `Unrated`, a blank rating, `FSK 16`, or one too high), or when its series ID is present but that series
is not in the library snapshot. Rule 4 of Items and the pool gives the exact rule. With no ceiling, every item is
eligible, rated or not. Account limits set on the server can only narrow what the rules allow; they can never widen a
ceiling.

## Item IDs

Every item ID in a rules file is in **canonical form**: exactly 32 lower-case hexadecimal characters (`0` to `9` and `a`
to `f`) with no hyphens, no braces and no other characters. Some Jellyfin endpoints return IDs with hyphens
(`8-4-4-4-12`) or in upper case.

- A client that gets an ID from the server must normalise it to canonical form first, in this order: (1) if the string
  starts with `{` and ends with `}`, remove those two characters; (2) remove every hyphen, wherever it is; (3) convert to
  lower case. If anything other than 32 hex characters is left, it is not an ID. So `-{<32 hex>}` and `{<32 hex>}-` are not
  IDs (the braces are not at the ends, and are not removed), while hyphens in odd places are simply removed.
- A reader of a rules file must **reject** a file that carries a non-canonical ID (hyphenated, upper case, wrong length).
  The reader does not repair it.

The canonical form is used everywhere: the sorts, the hash and the tie-break.

## Primitives

All arithmetic is on **unsigned 64-bit** numbers and **wraps around modulo 2^64**. Shifts are logical (zeros come in).
Never use a language's built-in random-number generator or its default hash: they differ between platforms.

**`splitmix64(x)`** is one step of SplitMix64 from state `x` (public-domain algorithm by Steele, Lea and Flood; see
Vigna's reference `splitmix64.c`):

```text
z = x + 0x9E3779B97F4A7C15
z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9
z = (z ^ (z >>> 27)) * 0x94D049BB133111EB
return z ^ (z >>> 31)
```

`splitmix64(0)` is `0xE220A8397B1DCDAF` (16294208416658607535). In a language without native 64-bit integers, do each
step on two 32-bit halves; the vector files list the halves.

**Per-epoch seed.** For epoch `n` (a signed 64-bit whole number, which may be negative) and the channel's seed:

```text
seed_n = splitmix64(seed + (n mod 2^64) * 0x9E3779B97F4A7C15)       (all wrapping modulo 2^64)
```

`n mod 2^64` is `n` read as an unsigned 64-bit number in two's complement: **`n = -1` becomes `0xFFFFFFFFFFFFFFFF`**, and
`n = -365` becomes `0xFFFFFFFFFFFFFE93`. The product is then the low 64 bits. Building the schedule has a worked example,
and `docs/rules-format/vectors/epoch-seed.json` has many, including negative `n`.

Never use `seed XOR n`; neighbouring epochs would be correlated.

**`fnv1a64(bytes)`** is 64-bit FNV-1a:

```text
h = 0xcbf29ce484222325
for each byte b: h = (h ^ b) * 0x100000001b3
return h
```

`fnv1a64` of no bytes is `0xcbf29ce484222325`, and of the single byte `a` (0x61) is `0xaf63dc4c8601ec8c`. The input to
the ordering hash is the **32 ASCII bytes** of the canonical ID, not UTF-16 text and not the 16 raw bytes of the GUID.

**Hash key.** The key of an item in a `shuffle` epoch `n` is `splitmix64(seed_n + fnv1a64(canonicalIdAsAsciiBytes))`. The key
of a group in `randomInOrder` is the same function of the group's ID and the **channel `seed`**, not `seed_n`:
`splitmix64(seed + fnv1a64(groupIdAsAsciiBytes))`. Keys are compared **as unsigned numbers**. A signed comparison is a bug
that only shows for keys with the high bit set, so the key-order vectors include one. Ties are broken by the canonical ID
bytes, unsigned, ascending.

Vectors: `docs/rules-format/vectors/splitmix64.json` and `fnv1a64.json` (the primitives, with the published anchor
values). The per-epoch seed and the key order have vectors of their own: `epoch-seed.json` and `key-order.json`. No step of
the schedule needs a random-number stream or a bounded integer (see Building the schedule).

## String matching

Genre, tag and other names in the rules are matched against the server's names with these rules and no others:

- First decode the JSON: escapes such as `é` and surrogate pairs such as `😀` are decoded to their
  characters before anything is compared. A string that contains an **unpaired surrogate** (a lone `\ud800` to `\udfff`
  escape) makes the whole file invalid.
- Then compare **exact UTF-8** bytes.
- Matching is **case-insensitive for the ASCII letters `A` to `Z` only** (they become `a` to `z`); every other
  character, including accented and non-Latin letters, must match exactly.
- Do **no** Unicode normalisation (NFC and NFD forms are different strings).
- Use **no** locale, no collation and no trimming.

Lengths of names are counted in Unicode code points (see Name). Names are never used to sort or to hash. Only canonical
IDs are.

### Studio names

(Version 3.) A `studio` source or exclusion and the `studios` filter match a studio name under the **studio fold**, which
is applied to the name in the rules **and** to each studio name of the item or series, and then the two results are compared
as exact sequences of Unicode code points. The studio fold is **clean**, then fold:

1. **Clean.** Go through the name by code point and write a new name:
   1. a surrogate that is not half of a pair is dropped (in the name of the rules and in each studio name of an item or a
      series alike; a name that is only such surrogates is then empty, see below);
   2. U+0009, U+000A, U+000D, U+2028 and U+2029 are written as one U+0020 (space);
   3. a code point in the **dropped set** is dropped. The dropped set is U+0000 to U+001F (the controls not named in step 2),
      U+007F to U+009F, U+00AD, U+0600 to U+0605, U+061C, U+06DD, U+070F, U+0890 to U+0891, U+08E2, U+180E, U+200B, U+200E to
      U+200F, U+202A to U+202E, U+2060 to U+2064, U+2066 to U+206F, U+FEFF, U+FFF9 to U+FFFB, U+110BD, U+110CD, U+13430 to
      U+1343F, U+1BCA0 to U+1BCA3, U+1D173 to U+1D17A, U+E0001 and U+E0020 to U+E007F. (U+200C and U+200D are **not** in it:
      they are kept.) The set is fixed by this list, never by a platform's idea of a control or format character;
   4. every other code point is written as it is.
   Then remove, from both ends of the result and repeatedly, any code point of the **space set**: U+0020, U+00A0, U+1680,
   U+2000 to U+200A, U+202F, U+205F and U+3000. Nothing else is trimmed or collapsed (spaces inside the name stay as they
   are, and two spaces are not one). A name that is empty after this is **dropped**: an item has no such studio, and a rules
   name that is empty after this matches nothing.
2. **Fold.** The ASCII letters `A` to `Z` become `a` to `z`, as in the rest of this section, and nothing else changes: no
   Unicode normalisation, no locale.

So the rules name `Studio A` matches the studios `STUDIO A`, `studio a` and `Studio A` followed by a no-break space (U+00A0),
and does not match `Studio  A` (two spaces) or `Studiö A`. A client **must not** use a platform's `trim`, whitespace test or
case function for this: they differ between platforms.

## Unknown fields and unsupported kinds

A reader will meet files written by newer apps. Three cases must be handled differently:

- **An unknown field** (a key the reader does not know, in any object: the top level, a source, `ordering`, `timing`,
  `broadcastDay`, `filler`, a filler source, and in version 2 `ordering.caps`, `filters`, `filters.years` and
  `filters.runtime`, and in version 3 `filters.runtime.episode` and `filters.runtime.movie`) is **kept and ignored**. The reader must not use it, must not fail on it,
  and must write it back with the same JSON value when it writes the file again (see JSON profile: the value, not the bytes).
  `filters` and `ordering.caps` in a file that says `formatVersion` 1 are unknown fields in this sense: kept and ignored, and so
  are the version 3 fields (`filters.tags`, `studios`, `notGenres`, `runtime.episode`, `runtime.movie`, `ordering.repeatGapMinutes`
  and `ordering.pairUpToMinutes`) in a file that says `formatVersion` 2 or 1.
  `docs/rules-format/examples/forward-compat-extras.json` has unknown fields at every level.
- **An unknown kind** (a `type` of a source, an ordering `mode`, a filler `mode` or, in version 2, a `filters.kind`
  that the reader does not know) is **not** an unknown field. That includes a version 2 `mode` (`rotation`,
  `inOrder`) or source `type` (`item`) in a file that says `formatVersion` 1, and a version 3 `mode` (`curated`) or
  source `type` (`studio`) in a file that says `formatVersion` 1 or 2: for that file the kind is unknown. The
  channel is **unsupported**: it is not scheduled, and nothing is dropped silently to make it work. This keeps a
  channel from playing the wrong thing.
- **A known kind that the reader does not schedule** is treated the same way: **unsupported**. In version 1 this is every
  filler mode except `none` (see Filler).

Unknown fields are data, not instructions. A reader never executes or fetches anything in them.

The version 1 JSON Schema describes version 1 only, so it rejects an unknown kind. The version 2 schema describes
version 2, and it also rejects a version 2 mode or source in a file that says `formatVersion` 1. The version 3 schema
describes version 3, and it also rejects a version 3 mode or source in a file that says `formatVersion` 1 or 2 and a
version 2 mode or source in a file that says `formatVersion` 1. Readers rely on the rules above, not on the schemas.

## Limits

A rules file can come from an export or from another app, so a reader must treat it as untrusted input and fail plainly
(with an error, not a crash) rather than run out of memory. A reader must reject a file that is over:

| Limit | Value |
| --- | --- |
| File size | 256 KiB |
| Nesting depth of objects and lists | 16 |
| Sources in one list, and exclusions in one list | 256 each |
| Length of a source `name` | 256 code points |
| Names in each of `filters.genres`, `filters.notGenres`, `filters.tags` and `filters.studios` | 256 each, each name 256 code points at most |
| Length of the channel `name` | 100 code points |

A reader that is given a stream or a file must not read more than 256 KiB plus one byte into memory before it checks the
size; the extra byte is how it knows the file is too big. An endless source must never be read to its end.

## Verify your client

Before you trust your implementation, check these. The files in `docs/rules-format/vectors/` have many more values of the
same kinds, each written in decimal, in hex and as 32-bit halves.

1. **Schema.** Every file in `docs/rules-format/examples/` validates against the schema, and every file in
   `docs/rules-format/examples/invalid/` fails.
2. **splitmix64.** `splitmix64(0)` is `0xE220A8397B1DCDAF`, and `splitmix64(0xE220A8397B1DCDAF)` is
   `0xA706DD2F4D197E6F`.
3. **fnv1a64.** `fnv1a64` of no bytes is `0xcbf29ce484222325`; of the byte `a` it is `0xaf63dc4c8601ec8c`.
4. **Seeds.** `"0"` and `"18446744073709551615"` are accepted. `"007"`, `"-1"`, `""` and `"18446744073709551616"` are
   rejected. A seed written as the JSON number `12345` is rejected.
5. **Item IDs.** `{AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA}` from the server becomes
   `aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa`. A rules file with `AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` as an ID is rejected.
6. **Epochs.** With `utcOffsetMinutes` `-300`, `start` `"06:00"` and `epochHours` 24: `t = 0` is in epoch `-1`, which
   starts at `-46800000`; `t = 46800000` is in epoch `0`, which starts at `39600000`.
7. **Ratings.** `tv-14-v` has rank 4. `Approved` has rank 0. `NR`, a blank rating (one or more spaces) and
   `FSK 16` have no rank. Under the ceiling `PG-13`, `PG` is allowed and `TV-14` is not. An episode with its own rating
   `TV-Y7` whose series is rated `TV-MA` is not allowed under the ceiling `TV-Y7`. With no ceiling, an unrated item is
   allowed.
8. **Names.** A `name` of 100 emoji is accepted, and one of 101 is rejected (`name-100-code-points.json` and
   `invalid/name-101-code-points.json`).
9. **Version gate.** A file with `formatVersion` 4 is not scheduled, even when the rest of it is unreadable.
10. **JSON profile.** A repeated member name (also in an unknown field), a byte-order mark, `01`, `1.0` or `1e2` where a whole
    number is expected, and `NaN` are all rejected.
11. **Automatic seed.** For server ID `11111111111111111111111111111111` and the library
    `22222222222222222222222222222222`, the bytes hash to `fnv1a64` `0xd22a74589ce8af26` and the seed is
    `"16427903836559823302"`. For the genre `Animé` on the same server it is `"182484535837226886"`.
12. **Per-epoch seed.** With `seed` `"0"` and `n = -1`, the sum is `0x61C8864680B583EB` and `seed_n` is `0`. With `seed` `"1"` and
    `n = -1`, the sum is `0x61C8864680B583EC` and `seed_n` is `0x5692161D100B05E5`.
13. **The pool.** With the default `graceHours` 6, an item created at `S - 21600001` is in the epoch's pool and one created at
    `S - 21600000` is not; one deleted at `S - 21600000` is in the pool and one deleted at `S - 21600001` is not; a runtime
    of 59999 ms is out and 60000 ms is in (default `minRuntimeSeconds`).
14. **A whole day.** Worked example 1 in Building the schedule gives the blocks of days 0 to 3, and worked example 2 gives the
    end of day 0 for `chronological` (seed 8) and for `shuffle` (seed 4), with the items, dates, IDs and seeds written out.
    A client should reproduce both.
15. **`whatsOn`.** In day 0 of worked example 1, `t` at minute 120 is M2 at offset 0, `t` one millisecond earlier is M1 at offset
    5399999 ms, `t` at minute 240 is a slate with no offset, and `t` at minute 270 is an error for that day.
16. **The pool.** The case "rule 3 fail closed: a genre exclusion removes an episode whose series is missing" in
    `pool.json`: one library `...1001` holds the episode `...201` (series `...399`, which is **not** in the snapshot,
    genre `Comedy`), the episode `...202` (series `...301`, which is in the snapshot, with no genres), the loose episode
    `...203` (no series ID) and the movie `...101`. The source is that library and the exclusion is the genre `Horror`,
    with no ceiling. The pool is `...101`, `...202` and `...203` (each ID is 32 digits; the leading zeros are left out
    here).
    The episode `...201` is removed even though its own genres do not match, because its series cannot be checked.
    The other cases of the file cover the other rules.
17. **`rotation` and `inOrder`.** With the default caps (series 3, film 1), a show `B` of 2 episodes, a show `A` of 5 and a
    named collection `C` of 3 films, and group keys that order them B, A, C, `rotation` gives
    `B1 B2 A1 A2 A3 C1 A4 A5 C2 C3` (the worked example in Ordering). With `inOrder`, a show whose first episode premiered in
    2000 and a film premiered later that year, then a show whose first episode premiered in 2001, play as the first show
    start to finish, the film, then the second show. The capped shuffle's worked example (show `A` of 5, cap 2) gives
    `A1 f A2 A3 g A4 A5`.
18. **A version 1 file that uses version 2.** A file that says `formatVersion` 1 and uses `rotation` or `inOrder` as its
    `mode`, or an `item` source, is **unsupported** (not scheduled, "update required"). The same file with `filters` or
    `ordering.caps` and only version 1 features is read, and both are kept and ignored. A file that says `formatVersion` 2
    with `ordering.caps` and `epochHours` 12 is invalid. A version 2 file with a `filters.kind` that is not `movie` or
    `episode` is unsupported.
19. **A version 2 file that uses version 3.** A file that says `formatVersion` 2 and has a `studio` source or exclusion, or the
    `ordering.mode` `curated`, is **unsupported** (`docs/rules-format/examples/invalid/v3-feature-in-v2.json`). The same file
    with `tags`, `studios` or `notGenres` in `filters`, a `runtime.episode` or `runtime.movie` window, or
    `ordering.repeatGapMinutes` or `pairUpToMinutes`, and only version 2 features otherwise, is read, and those fields are kept
    and ignored. A file that says `3` with `mode` `curated` and an `ordering.caps` is invalid; with `repeatGapMinutes` 1441 or
    `pairUpToMinutes` 0 it is invalid.
20. **The studio fold.** The rules name `Studio A` matches an item whose studio is `STUDIO A`, or `Studio A` followed by U+00A0
    (trimmed), or `Stu` + U+200B + `dio A` (the zero-width space is dropped); it does not match `Studio  A` (two spaces) or
    `Studiö A`.
21. **The trim window.** A series with four episodes of 22, 22, 44 and 22 minutes in the in-group order, and a `keeps` cap of
    3600000 ms, has `k = 2`: day 0 keeps the first two episodes, day 1 the last two, day 2 the first two, and day -1 the last two.
22. **`curated`.** The worked example in `curated` gives the cycle `[a1 a2] [b1] [c1] [d] [a3 a4] [b2] [c2]`, with the starting
    passes `0`, `600`, `1200` and `1800`. A `curated` day starts at its first run, each day, and its repeat gap restarts at the
    start of each day.

## Files

Everything below is in this repository, under `docs/`:

- `docs/rules-format.md`: this document.
- `docs/rules-format/channel-rules.v3.schema.json`: a JSON Schema (draft 2020-12) for version 3. Like the earlier schemas, it
  checks shape, leaves every object open to extra fields, and is for tests and tools. It also rejects a version 3 mode or
  source (and the version 3 filter and ordering fields' ranges) in a file that says `formatVersion` 1 or 2, and a version 2 mode
  or source in a file that says `formatVersion` 1. It does not check the cross-field rules that the version 2 schema leaves to
  a reader, nor that a name is not empty once cleaned.
- `docs/rules-format/channel-rules.v2.schema.json`: a JSON Schema (draft 2020-12) for version 2. Like the version 1 schema,
  it checks shape, leaves every object open to extra fields, and is for tests and tools. It also rejects a version 2 mode
  or source type in a file that says `formatVersion` 1. It does not check the cross-field rules (`to` not below `from`,
  `minMinutes` not above `maxMinutes`, caps with a short epoch, the range of a seed); a reader checks those.
- `docs/rules-format/channel-rules.v1.schema.json`: a JSON Schema (draft 2020-12) for version 1. It checks the shape of a
  file and leaves every object open to extra fields. It is for tests and tools, not for deciding at run time whether to
  schedule a channel; a reader follows this document. It does not check the range of a seed or that a year range's `to`
  is not below `from`.
- `docs/rules-format/examples/`: one worked example per feature, each of which validates against the version 1 schema.
- `docs/rules-format/examples/v2/`: one worked example per version 2 feature (`rotation`, `inOrder`, a capped `shuffle`,
  `filters`, an `item` source, and a file that uses all of them), each of which validates against the version 2 schema, and
  `docs/rules-format/examples/v2/invalid/`, files a reader must reject (the schema rejects those it can); a file there
  whose kind is unknown (a version 1 file that uses version 2, or an unknown `filters.kind`) is rejected as unsupported.
- `docs/rules-format/examples/v3/`: one worked example per version 3 feature (`studio.json`, `filters-tags-studios.json`,
  `not-genres.json`, `runtime-per-kind.json`, `runtime-kind-window-with-unknown-field.json`, `curated.json` and `curated-tuned.json`), each of which validates against the
  version 3 schema; and `docs/rules-format/examples/invalid/v3-feature-in-v2.json`, a file that says `formatVersion` 2 and
  uses a `studio` source, which the version 3 schema rejects and a reader reads as unsupported.
- `docs/rules-format/examples/invalid/`: files the schema must reject (wrong type, missing `formatVersion`,
  `formatVersion` 0, a missing or malformed seed, a hyphenated or upper-case ID, a rating off the ladder, an unknown
  source kind, no sources, a missing `timing` or offset, an offset that is not a multiple of 15, an `epochHours` not on
  the list, a `start` off the half hour, an upper-case ordering mode, a kebab-case filler mode, a name of 101 code
  points, `betweenEveryN` without `every`, a `blockLength` of 0 or 101, a `null` `maxRating`, an `alignToGrid` that is a
  string).
- `docs/rules-format/vectors/`: test vectors, computed with an independent reference (not with the code that is tested):
  `splitmix64.json`, `fnv1a64.json`, `canonical-id.json`, `auto-seed.json`, `rating.json` (the ladder, the normalisation, the
  filter and the series rule, built on Jellyfin 12.1's own table), `epoch-seed.json` (`seed_n`, including negative `n`) and
  `epoch-math.json` (the epoch number and `epochStart` for many offsets, starts and epoch lengths), `key-order.json` (the three
  orders, the group keys, the in-group order and the runs, with the worked examples of Ordering) and `lineup.json` (whole
  days: the rules the scheduler reads, the items, the day number `n`, every block of the day, and queries giving the block
  that is on at a time `T` (its index, and the offset), the next blocks, and times outside the day that are errors),
  `key-order-v2.json` and `lineup-v2.json` (the same for the version 2 orders: `rotation`, the capped `shuffle` and
  `inOrder`, with the caps, the film-to-collection map and the runtime filter), and `pool.json` (the pool: a library snapshot, the sources, the exclusions and the ceiling, and the IDs of the pool,
  for every row of rule 2, the exclusions with their fail-closed cases, rule 4 and the String matching cases), and `pool-v2.json` (the same shape for version 2: `item` sources and
  exclusions, the filters and the film-to-collection map).

- `docs/rules-format/vectors/` (version 2): `pool-v2.json` (the pool with `item` sources and exclusions, every filter and
  the film-to-collection map), `key-order-v2.json` (the rotation turns, the capped shuffle and the `inOrder` order) and
  `lineup-v2.json` (whole days for the new orders). They are computed with an independent reference. The version 1 vectors
  are unchanged.

- `docs/rules-format/vectors/` (version 3): `pool-v3.json` (the pool with `studio` sources and exclusions, the filters
  `tags`, `studios` and `notGenres`, and the franchise map), `key-order-v3.json` (the `curated` walk: pairs, the gap, the
  weights, a franchise and the tunables) and `lineup-v3.json` (whole days for `curated`, the per-kind runtime windows and the
  trim window). They are computed with an independent reference that was written from this document alone, and are added to
  the folder with that reference. The version 1 and version 2 vectors are unchanged.

Examples in `docs/rules-format/examples/`, with what they show:

| File | Shows |
| --- | --- |
| `docs/rules-format/examples/library-only.json` | The smallest useful file. |
| `docs/rules-format/examples/genre-with-max-rating.json` | A genre, a rating ceiling, a high-bit seed. |
| `docs/rules-format/examples/collection.json` | A collection source. |
| `docs/rules-format/examples/tag.json` | A tag source, the largest seed. |
| `docs/rules-format/examples/year-range.json` | A year range source. |
| `docs/rules-format/examples/series.json` | A series source. |
| `docs/rules-format/examples/exclusion.json` | One exclusion of every kind. |
| `docs/rules-format/examples/ordering-shuffle.json` | `shuffle`. |
| `docs/rules-format/examples/ordering-chronological.json` | `chronological`. |
| `docs/rules-format/examples/ordering-random-in-order.json` | `randomInOrder`, runs of 2. |
| `docs/rules-format/examples/timing-unaligned.json` | No grid, 12-hour epochs, runs of 4. |
| `docs/rules-format/examples/timing-grid-and-grace.json` | An hourly grid, 12 hours of grace, a minimum runtime. |
| `docs/rules-format/examples/broadcast-day-offset.json` | A broadcast day at UTC-5. |
| `docs/rules-format/examples/broadcast-day-offset-nepal.json` | A broadcast day at UTC+5:45, starting 18:30. |
| `docs/rules-format/examples/name-100-code-points.json` | A name of 100 code points that are 200 UTF-16 units. |
| `docs/rules-format/examples/filler-none.json` | Filler mode `none`. |
| `docs/rules-format/examples/filler-pad-to-grid.json` | Filler mode `padToGrid` with a library. |
| `docs/rules-format/examples/filler-tag-source.json` | Filler mode `padToGrid` with a tag. |
| `docs/rules-format/examples/filler-between-every-n.json` | Filler mode `betweenEveryN` with a collection. |
| `docs/rules-format/examples/full.json` | Every section together. |
| `docs/rules-format/examples/forward-compat-extras.json` | Unknown fields at every level. |
| `docs/rules-format/examples/v3/studio.json` | A `studio` source and a `studio` exclusion (version 3). |
| `docs/rules-format/examples/v3/filters-tags-studios.json` | The `tags` and `studios` filters. |
| `docs/rules-format/examples/v3/not-genres.json` | `genres` with `notGenres`. |
| `docs/rules-format/examples/v3/runtime-per-kind.json` | A general runtime window and a window for each kind. |
| `docs/rules-format/examples/v3/runtime-kind-window-with-unknown-field.json` | A runtime with a window for one kind and an unknown field. |
| `docs/rules-format/examples/v3/curated.json` | `curated` with the default tunables. |
| `docs/rules-format/examples/v3/curated-tuned.json` | `curated` with both tunables, a `studio` source and `notGenres`. |
