# Channel rules format

> Draft — v1 is final once F1.1 completes the scheduling section and the vectors land.

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
| `formatVersion` | yes, no default | Whole number, 1 or more. The version of this format. `1` here. |
| `name` | no | The channel's display name. |
| `seed` | yes, no default | The channel's random seed, as a decimal string. |
| `sources` | yes, no default | List of sources: what can air. At least 1. |
| `exclusions` | no | List of sources that must not air. Default: none. |
| `maxRating` | no | A rating ceiling. Default: no ceiling. |
| `ordering` | no | Object with the ordering `mode`. Default: `shuffle`. |
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
- **Pool**: the set of items a channel may air, worked out from the sources, the exclusions and the rating ceiling.
- **Library snapshot**: the cached set of items, with their fields, that the signed-in account can see when a schedule is
  worked out (see Items and the pool).
- **Program**: one item as it airs.
- **Run**: `blockLength` items that play back to back (see `blockLength` in Timing and Runs). A run is a unit of the
  order. It is not a unit of the lineup.
- **Block**: one entry of a lineup: either one item (a program) or a slate, with a start and an end. A run is played as one
  block per item.
- **Slate**: a block with no program, for time nothing else fills. It is a station-ID card until filler exists.
- **Epoch**: the stretch of time from `epochStart(n)` up to `epochStart(n + 1)` (default 24 hours). Each epoch's lineup is
  worked out from that epoch's own pool and the channel's rules, with no state carried over from earlier epochs. The
  `shuffle` order has a seed of its own for each epoch; the other two orders do not (see Ordering).
- **Cycle**: the runs of one epoch's pool in order, once through, without the runs that are left out (see Left-out runs). It
  repeats when it runs out.
- **Grid mark**, **slot**, **continuous playout**: see Building the schedule.
- **Broadcast day**: the daily start of the first epoch, on the channel's own fixed-offset clock.
- **Canonical ID**: an item ID as 32 lower-case hex characters (see Item IDs).

## Format version

`formatVersion` is a whole number of 1 or more. It is `1` for this document.

**A reader must read `formatVersion` first, before anything else in the file.** It must be able to do this even when the
rest of the file is unreadable. The only things checked before it are the input limits (see Limits) and the JSON profile
(see JSON profile): a file over a limit, or one that breaks the profile, is invalid even when its `formatVersion` is newer.

- If `formatVersion` is higher than the highest version the reader supports, the reader must **not** schedule the
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
text exists anywhere. **After version 1 is final, a change like any of these needs version 2.** That includes adding a
rules field that changes a lineup, and changing the default of one.

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

Unknown fields may hold any valid JSON, including numbers. When a file is written again an unknown value is kept as the same
JSON value, not as the same bytes: an escape may be written differently, and a number such as `1e2` may be written as
`100.0`. Writing the shipped examples gives back the same bytes.

**A writer must write the lowest `formatVersion` whose features the file uses.** A file that only uses version 1 features
says `1`, even when the writer also knows version 2. That keeps older apps working for as long as possible.

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
   - `collection`: if it is a member of the collection, or an episode of a series that is a member;
   - `series`: if it is an episode of that series;
   - `genre` or `tag`: if the item has it, **or if it is an episode and its series has it**. Jellyfin keeps genres and tags
     mostly on the series and the movie, not on each episode, so without this rule a genre channel made of TV shows would
     be empty;
   - `yearRange`: if the item's **own** production year is in the range. An item with no production year is **not**
     included. A client must not guess a year from the series.
3. **Union, then exclusions.** The pool starts as every item that matches **any** source. An item that matches several
   sources is in the pool **once** (items are the same item if their canonical IDs are equal). Then every item that
   matches **any** exclusion (with the same matching rules) is removed.
4. **Rating ceiling.** If `maxRating` is present, an item stays only if it passes the rating rule (see Maximum rating
   and the rating ladder). An episode with no rating of its own (none, or the empty string) uses its series' rating; any
   other own rating, even `NR` or a blank, is used as it is.
5. **The library snapshot.** The pool is worked out from the *library snapshot*: the cached set of items, and their
   genres, tags, years, ratings, dates, season and episode numbers, that the signed-in account can see. Rules 1 to 4 pick
   the items from it. Which of those items count for which epoch (the creation time, the deletion time and the runtime) is
   decided by The pool of an epoch, in Building the schedule. The format only says what is looked up in the snapshot.

## Sources

`sources` is a list of up to 256 objects. Each has a `type` that says what kind of source it is. Every source is an
object that is open to extra fields.

| `type` | Other fields | Matches |
| --- | --- | --- |
| `library` | `id` | Every item in the library with that ID. |
| `genre` | `name` | Every item that has a genre with that name. |
| `collection` | `id` | Every item in the collection (a Jellyfin box set) with that ID. |
| `tag` | `name` | Every item that has a tag with that name. |
| `yearRange` | `from`, `to` | Every item whose production year is from `from` to `to`, both included. |
| `series` | `id` | A specific series: every episode of the series with that ID. |

The exact matching is in Items and the pool.

- `id` is an item ID in the canonical form (see Item IDs).
- `name` is 1 to 256 Unicode code points and is matched as described in String matching.
- `from` and `to` are whole numbers from 1 to 9999. `to` must not be lower than `from`. A reader treats a file where it
  is lower as invalid. (The JSON Schema cannot express this; the reader checks it.)

Each kind has its own example file:

- `docs/rules-format/examples/library-only.json` selects a whole library.
- `docs/rules-format/examples/genre-with-max-rating.json` selects a genre, with a rating ceiling.
- `docs/rules-format/examples/collection.json` selects a collection.
- `docs/rules-format/examples/tag.json` selects a tag.
- `docs/rules-format/examples/year-range.json` selects the years 1990 to 1999.
- `docs/rules-format/examples/series.json` selects one series.

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

`exclusions` is an optional list of up to 256 objects. It uses **the same kinds as sources**, with the same fields.

How they combine: first take the union of all `sources`. Then remove every item that matches **any** exclusion. An item
that is excluded cannot be brought back by any source. The rating ceiling is applied on top: an item has to pass both.

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

## Ordering

`ordering` is an object with one field, `mode`, which is one of three exact names in lowerCamelCase: `shuffle`,
`chronological` or `randomInOrder`. If `ordering` is absent, the mode is `shuffle`.

All three modes work in **epochs** (see Timing), and the pool of each epoch is worked out on its own (see Building the
schedule). This section defines the **order of one epoch's pool**, as a list. Which seed each mode uses:

| Mode | Seed used for the order |
| --- | --- |
| `shuffle` | `seed_n`, the seed of the epoch (see Primitives). Each epoch has its own order. |
| `chronological` | None. The order is a sort by a key. |
| `randomInOrder` | The channel `seed` itself, **not** `seed_n`. The order of the groups is the same on every day. |

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

## Timing

`timing` is an object, and it is **required** because it holds the broadcast-day offset, which has no default. Its other
fields are optional.

| Field | Default | Meaning |
| --- | --- | --- |
| `blockLength` | `1` | Items per run: the number of consecutive items (for example 2 episodes of one series) that play together, back to back, as one run (see Runs). A whole number from 1 to 100. |
| `alignToGrid` | `true` | Runs start on the grid marks (every `gridMinutes`, see that row) of the clock described below. If `false`, a run starts when the previous one ends. |
| `epochHours` | `24` | The length of an epoch, in hours. One of 1, 2, 3, 4, 6, 8, 12 or 24 (a whole number of hours that divides 24, so that the day repeats). |
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

- mode: `shuffle`, `chronological` or `randomInOrder`;
- `seed`: the channel seed, an unsigned 64-bit number;
- `blockLength`: items per run, 1 to 100;
- `alignToGrid`: true or false;
- `gridMinutes`: 15, 30 or 60;
- `epochHours`: 1, 2, 3, 4, 6, 8, 12 or 24;
- `utcOffsetMinutes`: -720 to 840, a multiple of 15;
- `start`: minutes after midnight on the channel's clock, on :00 or :30, and on :00 with a 60-minute grid, as Timing says;
- `graceHours`: 0 to 48;
- `minRuntimeSeconds`: 1 to 3600.

From these: `offsetMs`, `startMs` and `epochMs` as in Timing, and

```text
gridMs       = gridMinutes * 60000
graceMs      = graceHours * 3600000                 (this is G)
minRuntimeMs = minRuntimeSeconds * 1000
```

**Items.** The items that match the rules (sources, exclusions and the rating ceiling: see Items and the pool). Each item
has:

- its canonical ID;
- its kind, movie or episode;
- `runtimeMs`: its runtime, in whole milliseconds. A runtime of 0 or less is not an error: it is below the minimum, so the
  item is left out of the pool. (From Jellyfin's ticks of 100 nanoseconds, truncating `ticks / 10000` is
  recommended. The scheduler uses the number it is given, and it decides whether an item right at `minRuntimeSeconds` counts.)
- `createdAt`: when the server created the item, in milliseconds since 1970-01-01T00:00:00Z;
- `deletedAt`: absent, or the time this client first saw the item was gone (see The pool of an epoch);
- the premiere date, or none: exactly `YYYY-MM-DD` (UTC), with a four-digit year, a month `01` to `12` and a day `01` to
  `31`. The shape is all that is checked: there is no check against a real calendar (`2020-02-31` is accepted and compared
  as text). Anything else is an input error;
- the production year, or none: a whole number from 1 to 9999. Any other value is an input error;
- for an episode: its series ID, or none, and its season number and episode number (each a whole number of 0 or more, or
  none). A negative number is an input error. A movie that has a series ID, a season number or an episode number is an
  input error.

Two items with the same canonical ID are an input error, whether or not either is kept. The scheduler rejects the input
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
  exact in a double-precision float.
- **Sums.** A day holds at most `epochMs / minRuntimeMs` programs, and the sum of the slots of a cycle is at most the
  number of items times `epochMs`. So the list of input items must hold **fewer than 2^26 items** (67,108,864); that many or
  more is an **input error** (the whole input is rejected), and below it that sum is below 2^53.
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

Only `shuffle` uses `seed_n`. The other two orders do not use it (see Ordering).

### The pool of an epoch

The pool of day `n` is worked out from the items alone, at the moment `S - G`. Let `S = epochStart(n)` and `G = graceMs`.
An item is in the pool of day `n` if and only if **all four** hold:

```text
1. createdAt < S - G
2. deletedAt is absent, or deletedAt >= S - G
3. runtimeMs >= minRuntimeMs
4. runtimeMs <= epochMs
```

1. **Added before the cut-off.** An item created inside the grace window waits for the next epoch. The server stamps
   `createdAt`, so two devices that have synced the library past `S - G` agree.
2. **Deleted at or after the cut-off.** An item deleted shortly before `S` still airs in day `n`, so the lineup does not
   shift under the viewer. The player skips it when it is reached (that slot then shows a slate, a matter for the player,
   not for the scheduler). The scheduler uses the runtime it is given for such an item, which the library layer keeps as it
   was before the deletion.
3. **A program has a minimum length.** An item shorter than `minRuntimeMs` is not a program. Without this a bad runtime of
   a few milliseconds would make millions of blocks a day.
4. **A program fits in an epoch.** A longer item could never play without running over `E`, so it is left out.

**What `deletedAt` means.** `deletedAt` is the time at which **this client** first saw that the item was gone from the
server. Jellyfin gives no deletion time, so it is a local observation and it differs between devices: TV A may see the
item gone at 20:00 and TV B at 23:30. Items that are still present have no `deletedAt`.

**The stated disagreement window.** For additions the devices agree, as item 1 says. For deletions they can disagree. Let a
device sync at least every `G`. An item deleted on the server at time `d` is first seen gone at a time between `d` and
`d + G`. So:

- if `d >= S - G`, every device has `deletedAt >= S - G`, and the item is in the pool everywhere;
- if `d < S - 2G`, every device has `deletedAt < S - G`, and the item is out everywhere;
- if `S - 2G <= d < S - G`, it depends on when the device looked: **devices can disagree about day `n`**.

So two devices agree on a day unless an item was deleted in `[S - 2G, S - G)`.

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

The result is the **order** of day `n`. Those sections are the definition; this section only uses them.

### Runs

A **run** is a group of items that play back to back. The order is cut into runs, **once per day**:

- For `shuffle` and `chronological`: a run is `blockLength` consecutive items of the order, counted from the first item.
  The last run is shorter when the number of items is not a multiple of `blockLength`. Runs never span the end of the
  order: when the order starts again, the count starts again with it.
- For `randomInOrder`: one **turn** is one run. (The turns of Ordering already hold up to `blockLength` items, and a short
  turn is a short run.)

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
day never visits them.

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

**`shuffle`:** the start run is run `0` of the day's own order. Each day has its own seed, so each day has its own order,
and nothing carries over: `j = 0`.

**`chronological` and `randomInOrder`:** the start run is the run of the channel's **continuous playout** that contains
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

For `chronological` and `randomInOrder`, with an **unchanged pool** (the same cycle on the days compared):

- **No run played in a day's regular layout is played again at the next seam.** The next day never repeats a run.
- Day `n` starts `delta_n` late against the continuous playout, `0 <= delta_n < slot(run j_n)`. So the runs from the first
  run that day `n` did not start, up to but not including the first run of day `n + 1`, **may be skipped**. They are not
  played on day `n` and day `n + 1` has moved past them.
- **Nothing is skipped when every slot is the same length and that length divides `epochMs`.** The runs of the day then fill
  it exactly, so the last one ends at `E`, the next day starts at the next run, and `delta` does not matter. Example:
  60-minute slots in a 4-hour epoch with a `:30` start give `delta = 30` minutes, and still skip nothing. Equal slots that
  do **not** divide `epochMs` can skip (worked example 1 below: 90-minute slots in a 4-hour epoch). Also, when
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

For `shuffle` the order is new on each day, so there is no continuity to promise.

### The planned end of the day

When step 3 of the regular layout stops (run `i` would not end by `E`), the rest of the day is **planned**, not cut. Let `c`
be the end of the last block placed (it is `S` if none). Number the items of the cycle `L[0] ... L[N - 1]`, runs in order
and items in order inside each run. Here `i` is the index of the run that did not fit, taken modulo `B` (the regular layout may have gone round the cycle
more than once). Let `f` be the number of items in runs `0` to `i - 1`: `L[f]` is the first item of run `i`.

1. **Visit order.** Starting at `L[f]`, visit items in order. For `shuffle`, wrap from `L[N - 1]` to `L[0]` and visit
   exactly `N` items (so every item once). For `chronological` and `randomInOrder`, **do not wrap**: stop after
   `L[N - 1]` (that is `N - f` visits). So an earlier episode is never replayed after a later one.
2. **Each visit.** For the visited item `x`, with the cursor `c`:
   1. (`chronological` and `randomInOrder` only.) If an earlier visit of this fill **passed over** an item of the same
      **group** as `x`, then `x` is not eligible. Skip it: nothing changes. The group of an item is its series ID; a movie,
      and an episode with no series, are a group of one (their own ID).
   2. Its start is `s = nextMark(c)` (just `c` when `alignToGrid` is false).
   3. If `s + runtimeMs(x) <= E`, **place** it. Its **actual runtime** is used, not a slot. If `s > c`, put a slate
      `[c, s)` first. Put the program block `[s, s + runtimeMs(x))`. Set `c` to its end.
   4. Otherwise `x` is **passed over**. (`chronological` and `randomInOrder`: remember that its group was passed over. For
      `shuffle`, nothing: any later item may still fit.)
3. **Close.** If `c < E`, put one slate `[c, E)`. A grid gap that has no item after it, and any time too short for any
   item, are all part of this one slate.

Every item placed here is a **block of its own** and takes no part in run grouping: `blockLength` plays no role in the
planned end. Nothing is cut and nothing runs past `E`. The work is one pass over at most `N` items. An item placed may
already have aired earlier that day (when the cycle is shorter than the day, or for `shuffle`'s wrap): the planned end does
not check.

Why the group rule: without it, when episode 3 of a series does not fit but episode 4 does, episode 4 would air before
episode 3 has aired. So once an item of a group is passed over, no later item of that group is eligible. `shuffle` has no
such limit, because its order has no series order to keep.

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
`lineup.json` (whole days: rules subset, items and `t`, with the block that is on and its offset). They were computed
with a reference that was written from this text alone, not with the code that is tested. Once version 1 is final, a wrong
vector means version 2.

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

- `none`: no filler. `docs/rules-format/examples/filler-none.json`.
- `padToGrid`: fill the time before the next grid mark. `docs/rules-format/examples/filler-pad-to-grid.json`, with a
  library as filler source, and `docs/rules-format/examples/filler-tag-source.json`, with a tag.
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

  The library and the `科幻` seeds have the high bit set. `animé` (lower case) and `Animé` written as `Anime` plus a
  combining acute accent are different genre names and give different seeds: nothing is folded or normalised.

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

`maxRating` is an optional rating **ceiling**. Absent means no ceiling: every item is eligible. The value must be one of
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
with no rating, a blank rating, `NR`, `Unrated`, or any rating that is not on the ladder (for example a British or German
rating such as `FSK 16`) is **not** allowed. With no ceiling, every item is eligible, rated or not. Account limits set on
the server can only narrow what the rules allow; they can never widen a ceiling.

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

## Unknown fields and unsupported kinds

A reader will meet files written by newer apps. Three cases must be handled differently:

- **An unknown field** (a key the reader does not know, in any object: the top level, a source, `ordering`, `timing`,
  `broadcastDay`, `filler` or a filler source) is **kept and ignored**. The reader must not use it, must not fail on it,
  and must write it back with the same JSON value when it writes the file again (see JSON profile: the value, not the bytes).
  `docs/rules-format/examples/forward-compat-extras.json` has unknown fields at every level.
- **An unknown kind** (a `type` of a source, an ordering `mode` or a filler `mode` that the reader does not know) is **not**
  an unknown field. The channel is **unsupported**: it is not scheduled, and nothing is dropped silently to make it
  work. This keeps a channel from playing the wrong thing.
- **A known kind that the reader does not schedule** is treated the same way: **unsupported**. In version 1 this is every
  filler mode except `none` (see Filler).

Unknown fields are data, not instructions. A reader never executes or fetches anything in them.

The JSON Schema describes version 1 only, so it rejects an unknown kind. Readers rely on the rules above, not on the
schema.

## Limits

A rules file can come from an export or from another app, so a reader must treat it as untrusted input and fail plainly
(with an error, not a crash) rather than run out of memory. A reader must reject a file that is over:

| Limit | Value |
| --- | --- |
| File size | 256 KiB |
| Nesting depth of objects and lists | 16 |
| Sources in one list, and exclusions in one list | 256 each |
| Length of a source `name` | 256 code points |
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
7. **Ratings.** `tv-14-v` has rank 4. `Approved` has rank 0. `NR`, a blank string and `FSK 16` have no rank. Under the
   ceiling `PG-13`, `PG` is allowed and `TV-14` is not. With no ceiling, an unrated item is allowed.
8. **Names.** A `name` of 100 emoji is accepted, and one of 101 is rejected (`name-100-code-points.json` and
   `invalid/name-101-code-points.json`).
9. **Version gate.** A file with `formatVersion` 2 is not scheduled, even when the rest of it is unreadable.
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

## Files

Everything below is in this repository, under `docs/`:

- `docs/rules-format.md`: this document.
- `docs/rules-format/channel-rules.v1.schema.json`: a JSON Schema (draft 2020-12) for version 1. It checks the shape of a
  file and leaves every object open to extra fields. It is for tests and tools, not for deciding at run time whether to
  schedule a channel; a reader follows this document. It does not check the range of a seed or that a year range's `to`
  is not below `from`.
- `docs/rules-format/examples/`: one worked example per feature, each of which validates against the schema.
- `docs/rules-format/examples/invalid/`: files the schema must reject (wrong type, missing `formatVersion`,
  `formatVersion` 0, a missing or malformed seed, a hyphenated or upper-case ID, a rating off the ladder, an unknown
  source kind, no sources, a missing `timing` or offset, an offset that is not a multiple of 15, an `epochHours` not on
  the list, a `start` off the half hour, an upper-case ordering mode, a kebab-case filler mode, a name of 101 code
  points, `betweenEveryN` without `every`).
- `docs/rules-format/vectors/`: test vectors, computed with an independent reference (not with the code that is tested):
  `splitmix64.json`, `fnv1a64.json`, `canonical-id.json`, `auto-seed.json`, `rating.json` (the ladder, the normalisation, the
  filter and the series rule, built on Jellyfin 12.1's own table), `epoch-seed.json` (`seed_n`, including negative `n`) and
  `epoch-math.json` (the epoch number and `epochStart` for many offsets, starts and epoch lengths), `key-order.json` (the three
  orders, the group keys, the in-group order and the runs, with the worked examples of Ordering) and `lineup.json` (82 whole
  days: the rules the scheduler reads, the items, the day number `n`, every block of the day, and queries giving the block
  that is on at a time `T` (its index, and the offset), the next blocks, and times outside the day that are errors).

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
