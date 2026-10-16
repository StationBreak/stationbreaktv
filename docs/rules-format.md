# Channel rules format

> Draft — v1 is final once F1.1 completes the scheduling section and the vectors land.

This document defines the file that describes one StationBreak channel. It is written for a developer who has never seen
this repository and who wants to build another client (Roku, Apple TV, a web tool) that shows the same lineup as the
Android TV app. You do not need to read any Kotlin.

The key idea: a channel is stored as a small file of **rules**, not as a list of shows. Given the same rules, the same
media library and the same seed, every client must work out the same lineup. So this document is exact about the few
steps that have to match bit for bit (the hash, the shuffle order and the chronological order), and plain about the rest.

The words **must**, **must not**, **should** and **may** are used as described in RFC 2119 and RFC 8174, and mean that
only when they are written in bold.

Worked numbers are published as test vectors in `docs/rules-format/vectors/` (see Files). Sections marked "specified by
F1.1" are not written yet: they are the scheduler's part of the contract (see Building the schedule). Vectors for what the
scheduler runs (the per-epoch seed, bounded integers and the key order) will be published with F1.1's definitions.

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
- **Block**: one or more programs that play back to back (see `blockLength` in Timing).
- **Epoch**: a stretch of time (default 24 hours) with its own seed and its own order. Epochs do not depend on each other.
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
- changing any algorithm in this document (the hash, the orders, epochs, the rating ladder or the rating normalisation);
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
   genres, tags, years, ratings, dates, season and episode numbers, that the signed-in account can see. Which snapshot is
   used for which epoch, and when it is taken, is part of "Building the schedule" (specified by F1.1). The format only
   says what is looked up in it.

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

All three modes work in **epochs** (see Timing), and each epoch has its own seed, `seed_n` (see Primitives). Epochs do not
depend on each other. This section defines the **order of the pool**, as a list. Where a mode needs a position that
depends on the epoch or the time, that part is in Building the schedule (specified by F1.1).

**The base order**, before any mode is applied, is the pool sorted by canonical item ID as bytes, ascending (compare the
32 ASCII bytes; no title, no locale, since collation depends on the language of the device).

**When the pool runs out inside an epoch**, the same order starts again from the beginning. The pool is not reshuffled
and the seed does not change.

### `shuffle`

The hash-keyed order, in numbered steps:

1. For every item in the pool, work out the key `splitmix64(seed_n + fnv1a64(idBytes))`, where `idBytes` are the 32
   ASCII bytes of its canonical ID and the sum wraps modulo 2^64.
2. Sort the items by key, ascending, comparing keys **as unsigned 64-bit numbers**.
3. If two keys are equal, the item with the smaller canonical ID bytes (unsigned, byte by byte) comes first.

Removing one item from the pool leaves the relative order of every other item unchanged. That is the reason the order is
a hash key and not a shuffle that depends on the size of the pool.

`docs/rules-format/examples/ordering-shuffle.json` selects it. Vectors for the keys and the resulting order, with a
key that has the high bit set and a tie, will be published with F1.1's definitions.

### `chronological`

Items are put in air-date order, and the episodes of a series stay in season and episode order.

**The sort key** of an item is this list, compared from left to right:

1. **Undated flag**: `0` if the item has a date, `1` if not. Undated items come after all dated ones.
2. **Date**: the item's premiere (air) date, as a calendar date `YYYY-MM-DD` in UTC, compared as text. If an item has no
   premiere date but has a production year, its date is `YYYY-01-01`, with the year zero-padded to four digits. If it has neither, it is undated.
3. **Group ID**: the canonical ID of the series, for an episode. For a movie, its own canonical ID. An episode with no
   series is a group of one, like a movie.
4. **Season number**: for an episode. Season `0` (specials) is an ordinary season number: a special sorts by its own date
   like any other episode, and before season 1 when dates are equal. A missing season number sorts after every present
   one. A movie has none.
5. **Episode number**: for an episode; a missing number sorts after every present one. A movie has none.
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

Where in this list epoch `n` starts, and how it carries on across epochs, is specified by F1.1.
`docs/rules-format/examples/ordering-chronological.json` selects it.

### `randomInOrder`

Series are taken in a hash-keyed order, and each series plays its episodes in order.

1. Split the pool into **groups**. All the episodes of one series are one group, with the series' canonical ID as its
   group ID. Each movie is a group of one, with its own canonical ID.
2. Order the items **inside** each group by the `chronological` key above (without its group ID part).
3. Work out the **group key** of each group: `splitmix64(seed_n + fnv1a64(groupIdBytes))`. Sort the groups by key
   ascending, as unsigned numbers; ties go to the smaller group ID bytes.
4. Walk the groups in that order, in rounds. In each round, take the **next item** of each group in turn. A group that
   has no items left is skipped. Each group remembers its own position.
5. When every group is used up, start again at the first group, with every position back at the first item (see "When
   the pool runs out", above).

Worked example. Group `B` has episodes B1, B2; group `A` has A1, A2, A3; group `C` has one episode C1. Suppose the
group keys put them in the order B, A, C. The result is:

```text
round 1: B1 A1 C1
round 2: B2 A2          (C is used up and skipped)
round 3: A3             (B is used up and skipped)
then it starts again: B1 A1 C1 B2 A2 A3 ...
```

Where in this walk epoch `n` starts is specified by F1.1. `docs/rules-format/examples/ordering-random-in-order.json`
selects it. The key of a group is the same function with the group's canonical ID (the series ID) hashed in place of an
item's. Worked group keys will be published with F1.1's definitions.

## Timing

`timing` is an object, and it is **required** because it holds the broadcast-day offset, which has no default. Its other
fields are optional.

| Field | Default | Meaning |
| --- | --- | --- |
| `blockLength` | `1` | Items per block: the number of consecutive items (for example 2 episodes of one series) that play together as one block. A whole number from 1 to 100. |
| `alignToGrid` | `true` | Blocks start on the grid marks (every `gridMinutes`) of the clock described below. If `false`, a block starts when the previous one ends. |
| `epochHours` | `24` | The length of an epoch, in hours. One of 1, 2, 3, 4, 6, 8, 12 or 24 (a number that divides 24, so that every epoch boundary falls on a grid mark). |
| `graceHours` | `6` | The grace window G, in hours: an item joins epoch `n` only if it was added before `epochStart(n)` minus G, so that every device has had time to sync it. A whole number from 0 to 48. |
| `gridMinutes` | `30` | The spacing of the grid marks, in minutes: 15, 30 or 60. With `60`, `broadcastDay.start` must be on the hour (`:00`), so that the day starts on a grid mark. |
| `broadcastDay` | required | When an epoch starts. An object with `utcOffsetMinutes` (required) and `start`. |

**The broadcast day.** `broadcastDay` has two fields:

- `utcOffsetMinutes`: a fixed offset from UTC, in minutes, east positive. **Required; no default.** A whole number from
  -720 to 840 that is a multiple of 15. For example, UTC-5 is `-300`, and Nepal (UTC+5:45) is `345`.
- `start`: the time of day the broadcast day starts, as `HH:MM` on a 24-hour clock at that offset (`00` to `23` hours).
  Only `00` or `30` minutes are allowed. Default `"06:00"`. `start` is on :00 or :30 for every `gridMinutes`; with 60 it must be :00.

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

`t` is "now": the UTC wall clock of the device, with **no correction for clock error**. (Time sources are a different
question; see the scheduler.) `t`, `offsetMs` and `n * epochMs` do not fit in 32 bits, so a client **must** use 64-bit
integers for them. This matters on BrightScript, whose plain integers are 32-bit: use its 64-bit integer type
(`LongInteger`), not a float or an integer.

The "grid" for `alignToGrid` is the grid marks (every `gridMinutes`) of that same fixed-offset clock.

**Daylight saving.** Because the offset is fixed, in a place that observes daylight saving the wall-clock time at which
the broadcast day starts moves by one hour, twice a year. A channel set to start at 06:00 at UTC-5 starts at 07:00
local time in summer where the local zone is UTC-4. This is deliberate: it keeps every device in agreement.

An epoch boundary is a hard cut. A program that is still playing when the next epoch starts is cut off; it does not push
the next epoch's start later.

Examples: `docs/rules-format/examples/broadcast-day-offset.json` (UTC-5, starts 06:00),
`docs/rules-format/examples/broadcast-day-offset-nepal.json` (UTC+5:45, starts 18:30) and
`docs/rules-format/examples/timing-unaligned.json` (blocks of 4, no grid, 12-hour epochs) and
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

## Building the schedule — specified by F1.1

This section is **specified by F1.1** (the scheduler) and is not written yet. Until it is, version 1 is a draft. F1.1
will define, exactly and with test vectors:

- the library snapshot for each epoch: when it is taken, which items it includes, and the grace window for slow syncs;
- the time-to-item timeline: how runtimes add up, where a block starts, how `blockLength` and `alignToGrid` combine, and
  what fills the gaps before the next grid mark (a slate, until filler exists);
- where each ordering mode stands at the start of epoch `n`: the starting position for `chronological` and the position
  of each group for `randomInOrder`, both worked out directly and not by replaying earlier epochs;
- the random-number stream and the bounded-integer step (rejection sampling), if any step needs them;
- what `whatsOn(channel, t)` returns.

Nothing in this document is changed by F1.1 except by a new `formatVersion`; F1.1 only adds what is missing here.

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

The canonical form is used everywhere: the base sort, the hash and the tie-break.

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

**Per-epoch seed.** For epoch `n` (a whole number, which may be negative) and the channel's seed:

```text
seed_n = splitmix64(seed + n * 0x9E3779B97F4A7C15)       (all wrapping, n taken modulo 2^64)
```

Never use `seed XOR n`; neighbouring epochs would be correlated.

**`fnv1a64(bytes)`** is 64-bit FNV-1a:

```text
h = 0xcbf29ce484222325
for each byte b: h = (h ^ b) * 0x100000001b3
return h
```

`fnv1a64` of no bytes is `0xcbf29ce484222325`, and of the single byte `a` (0x61) is `0xaf63dc4c8601ec8c`. The input to
the ordering hash is the **32 ASCII bytes** of the canonical ID, not UTF-16 text and not the 16 raw bytes of the GUID.

**Hash key.** The key of an item (or, for `randomInOrder`, of a group) in epoch `n` is
`splitmix64(seed_n + fnv1a64(canonicalIdAsAsciiBytes))`. Keys are compared **as unsigned numbers**. A signed comparison
is a bug that only shows for keys with the high bit set, so the F1.1 key-order vectors will include one. Ties are broken by the canonical ID
bytes, unsigned, ascending.

Vectors: `docs/rules-format/vectors/splitmix64.json` and `fnv1a64.json` (the primitives, with the published anchor
values). Vectors for the per-epoch seed, the bounded-integer step and the key order will be published with F1.1's
definitions.

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
  `epoch-math.json` (the epoch number and `epochStart` for many offsets, starts and epoch lengths). The remaining vectors
  for the scheduler's functions come with F1.1.

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
| `docs/rules-format/examples/ordering-random-in-order.json` | `randomInOrder`, a block of 2. |
| `docs/rules-format/examples/timing-unaligned.json` | No grid, 12-hour epochs, blocks of 4. |
| `docs/rules-format/examples/broadcast-day-offset.json` | A broadcast day at UTC-5. |
| `docs/rules-format/examples/broadcast-day-offset-nepal.json` | A broadcast day at UTC+5:45, starting 18:30. |
| `docs/rules-format/examples/name-100-code-points.json` | A name of 100 code points that are 200 UTF-16 units. |
| `docs/rules-format/examples/filler-none.json` | Filler mode `none`. |
| `docs/rules-format/examples/filler-pad-to-grid.json` | Filler mode `padToGrid` with a library. |
| `docs/rules-format/examples/filler-tag-source.json` | Filler mode `padToGrid` with a tag. |
| `docs/rules-format/examples/filler-between-every-n.json` | Filler mode `betweenEveryN` with a collection. |
| `docs/rules-format/examples/full.json` | Every section together. |
| `docs/rules-format/examples/forward-compat-extras.json` | Unknown fields at every level. |
