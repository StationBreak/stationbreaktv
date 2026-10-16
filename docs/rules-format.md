# Channel rules format

This document defines the file that describes one StationBreak channel. It is written for a developer who has never seen
this repository and who wants to build another client (Roku, Apple TV, a web tool) that shows the same lineup as the
Android TV app. You do not need to read any Kotlin.

The key idea: a channel is stored as a small file of **rules**, not as a list of shows. Given the same rules, the same
media library and the same seed, every client must work out the same lineup. So this document is exact about the few
steps that have to match bit for bit (the random-number step, the hash and the shuffle order), and it is plain about the
rest.

Places marked `TODO(vectors)` point at worked numbers that will be published as test vectors in
`docs/rules-format/vectors/`. The algorithms in this document are complete without them.

Words used in this document: **must**, **must not** and **may** mean what they usually mean in a specification.

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
  "ordering": { "mode": "RANDOM_IN_ORDER" },
  "timing": {
    "blockLength": 2,
    "alignToGrid": true,
    "epochHours": 24,
    "broadcastDay": { "utcOffsetMinutes": -300, "start": "06:00" }
  },
  "filler": { "mode": "between-every-N", "every": 3, "source": { "type": "tag", "name": "Station IDs" } }
}
```

The same file is `docs/rules-format/examples/full.json`. Every ID in this document and in the examples is a made-up
placeholder.

The top-level fields:

| Field | Required | Meaning |
| --- | --- | --- |
| `formatVersion` | yes | Whole number, 1 or more. The version of this format. `1` here. |
| `name` | no | The channel's display name. |
| `seed` | yes | The channel's random seed, as a decimal string. |
| `sources` | yes | List of sources: what can air. At least 1. |
| `exclusions` | no | List of sources that must not air. Default: none. |
| `maxRating` | no | A rating ceiling. Default: no ceiling. |
| `ordering` | no | Object with the ordering `mode`. Default: `SHUFFLE`. |
| `timing` | no | Object with `blockLength`, `alignToGrid`, `epochHours` and `broadcastDay`. Default: see Timing. |
| `filler` | no | Object with filler `mode`, `every` and `source`. Default: `none`. |

What is **not** in the file: the channel number and logo (they belong to each TV's own channel list), the server's
address, any account name or token, the device's time zone or language, and what anyone has watched. A rules file never
holds a secret and never causes a client to fetch a URL.

Fields that are absent take the defaults given in each section. A writer may leave a default out or write it; a reader
must treat both the same way.

## Format version

`formatVersion` is a whole number of 1 or more. It is `1` for this document.

**A reader must read `formatVersion` first, before anything else in the file.** It must be able to do this even when the
rest of the file is unreadable.

- If `formatVersion` is higher than the highest version the reader supports, the reader must **not** schedule the
  channel, must not try to read the rest, and should tell the user that an update is required. It must never play a
  best-effort lineup from a newer file.
- If `formatVersion` is missing, is not a whole number, or is below 1, the file is invalid. The channel is not scheduled.
- A file that is not valid JSON is invalid.

**A change needs a new `formatVersion` when it could change which items air, or in what order.** That includes:

- changing what an existing field or value means, or changing a default;
- changing any algorithm in this document (the random-number step, the hash, the shuffle order, epochs, the rating
  ladder or the rating normalisation);
- removing or renaming a field;
- adding a new kind of source, a new ordering mode or a new filler mode (so that an older app says "update required"
  and does not guess).

**A change does not need a new number** when it adds an optional field that cannot change a lineup, such as a label or a
note. Readers keep and ignore fields they do not know (see Unknown fields and unsupported kinds).

## Sources

`sources` is a list of up to 256 objects. Each has a `type` that says what kind of source it is. The set of items that
can air is the **union** of all the sources (an item in any source can air), and then the exclusions are removed (see
Exclusions). Every source is an object that is open to extra fields.

| `type` | Other fields | Matches |
| --- | --- | --- |
| `library` | `id` | Every item in the library with that ID. |
| `genre` | `name` | Every item that has a genre with that name. |
| `collection` | `id` | Every item in the collection (a Jellyfin box set) with that ID. |
| `tag` | `name` | Every item that has a tag with that name. |
| `yearRange` | `from`, `to` | Every item whose production year is from `from` to `to`, both included. |
| `series` | `id` | A specific series: every episode of the series with that ID. |

- `id` is an item ID in the canonical form (see Item IDs).
- `name` is 1 to 256 characters and is matched as described in String matching. It is not matched with a locale.
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
  "maxRating": "TV-Y7"
}
```

An item is only ever referenced by the ID the server gave it, or by an exact name. The IDs belong to one server, so a
rules file that names a library, collection or series by ID only makes sense on a client signed in to that server. Names
(genres and tags) work on any server that has the same names.

## Exclusions

`exclusions` is an optional list of up to 256 objects. It uses **the same kinds as sources**, with the same fields.

How they combine: first take the union of all `sources`. Then remove every item that matches **any** exclusion. An item
that is excluded cannot be brought back by any source. The rating ceiling (see Maximum rating and the rating ladder) is
applied on top: an item has to pass both.

`docs/rules-format/examples/exclusion.json` shows one exclusion of every kind (library, genre, collection, tag, year
range and series):

```json
{
  "formatVersion": 1,
  "name": "Comedy, Minus One",
  "seed": "99",
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

`ordering` is an object with one field, `mode`, which is one of three exact, upper-case names. If `ordering` is absent,
the mode is `SHUFFLE`.

All three modes work in **epochs** (see Timing). Each epoch gets its own seed, called `seed_n` (see Primitives), and the
epochs do not depend on each other.

The **base order**, before any mode is applied, is the pool sorted by canonical item ID as bytes, ascending (compare the
32 ASCII bytes; no title, no locale, since collation depends on the language of the device).

- `SHUFFLE`: the hash-keyed order. Every item gets the key `splitmix64(seed_n + fnv1a64(idBytes))` (the sum wraps
  modulo 2^64). Items are sorted by key ascending, comparing keys **as unsigned 64-bit numbers**; if two keys are equal,
  the item whose canonical ID bytes are smaller (unsigned, byte by byte) comes first. Nothing repeats within an epoch
  until the pool is used up; if the pool runs out inside an epoch, the same order starts again.
  Removing one item from the pool leaves the relative order of every other item unchanged. That is the reason the order
  is a hash key and not a shuffle algorithm that depends on the pool size.
  `docs/rules-format/examples/ordering-shuffle.json` selects it.
- `CHRONOLOGICAL`: by air date, or by series, season and episode number, in order. The starting position for epoch `n`
  is worked out directly from epoch `n`'s own library snapshot, not by replaying every earlier epoch, and it carries on
  across epochs.
  `docs/rules-format/examples/ordering-chronological.json` selects it.
- `RANDOM_IN_ORDER`: picks series in hash-keyed order, then plays each series' next episode in order. The key of a series
  is built exactly like the `SHUFFLE` key, from the **series'** canonical ID. Each series' position is worked out
  directly from `seed_n` and epoch `n`'s snapshot.
  `docs/rules-format/examples/ordering-random-in-order.json` selects it.

`TODO(vectors)`: worked key-ordering numbers, including one key with the high bit set and one tie.

## Timing

`timing` is an optional object. Every field in it is optional.

| Field | Default | Meaning |
| --- | --- | --- |
| `blockLength` | `1` | Items per block: the number of consecutive items (for example 2 episodes of one series) that play together as one block. A whole number from 1 to 100. |
| `alignToGrid` | `true` | Blocks start on the half-hour grid: at :00 and :30 of the clock described below. If `false`, a block starts when the previous one ends. |
| `epochHours` | `24` | The length of an epoch, in hours. A whole number from 1 to 168. |
| `broadcastDay` | see below | When an epoch starts. An object with `utcOffsetMinutes` and `start`. |

**The broadcast day.** `broadcastDay` has two fields:

- `utcOffsetMinutes`: a fixed offset from UTC, in minutes, east positive. A whole number from -720 to 840. Default `0`.
  For example, UTC-5 is `-300`.
- `start`: the time of day the broadcast day starts, as `HH:MM` on a 24-hour clock at that offset. Default `"06:00"`.

The time zone of the device and its daylight saving rules are **never** used. All the times in a rules file are on the
channel's own fixed-offset clock. Epoch `n` is found like this, from a time `t` in milliseconds since 1970-01-01T00:00:00Z:

```text
offsetMs = utcOffsetMinutes * 60000
startMs  = (hours * 60 + minutes) of "start" * 60000
epochMs  = epochHours * 3600000
n        = floor((t + offsetMs - startMs) / epochMs)        (round toward negative infinity)
epochStart(n) = n * epochMs + startMs - offsetMs            (milliseconds since the Unix epoch)
```

The "grid" for `alignToGrid` is the :00 and :30 marks of that same fixed-offset clock.

**Daylight saving.** Because the offset is fixed, in a place that observes daylight saving the wall-clock time at which
the broadcast day starts moves by one hour, twice a year. A channel set to start at 06:00 at UTC-5 starts at 07:00
local time in summer where the local zone is UTC-4. This is deliberate: it keeps every device in agreement.

An epoch boundary is a hard cut. A program that is still playing when the next epoch starts is cut off; it does not push
the next epoch's start later.

Examples: `docs/rules-format/examples/broadcast-day-offset.json` (UTC-5, starts 06:00) and
`docs/rules-format/examples/timing-unaligned.json` (blocks of 4, no grid, 12-hour epochs):

```json
{
  "formatVersion": 1,
  "name": "Eastern Evening",
  "seed": "8",
  "sources": [{ "type": "library", "id": "11111111111111111111111111111111" }],
  "timing": { "broadcastDay": { "utcOffsetMinutes": -300, "start": "06:00" } }
}
```

## Filler

`filler` is an optional object that says what plays in the gaps. **In version 1 the filler section is reserved**: the
format defines it, a reader must read it and keep it, and a rules file with a filler section is valid, but nothing plays
filler yet. The mode only has to be understood; scheduling it comes in a later version of the apps.

| Field | Meaning |
| --- | --- |
| `mode` | One of `none`, `pad-to-grid` or `between-every-N` (exactly this spelling, including the capital N). Default `none`. |
| `every` | A whole number from 1 to 1000. Required when `mode` is `between-every-N`: one filler item after every N programs. |
| `source` | Where filler comes from: a `library`, a `collection` or a `tag` source, in the same form as in Sources. Required for `pad-to-grid` and `between-every-N`. |

- `none`: no filler. `docs/rules-format/examples/filler-none.json`.
- `pad-to-grid`: fill the time before the next grid mark. `docs/rules-format/examples/filler-pad-to-grid.json`, with a
  library as filler source, and `docs/rules-format/examples/filler-tag-source.json`, with a tag.
- `between-every-N`: one filler item after every N programs. `docs/rules-format/examples/filler-between-every-n.json`,
  with a collection as filler source:

```json
{
  "formatVersion": 1,
  "name": "Station Breaks",
  "seed": "9",
  "sources": [{ "type": "library", "id": "11111111111111111111111111111111" }],
  "filler": {
    "mode": "between-every-N",
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

  `TODO(vectors)`: a worked example for a library, a collection and a non-ASCII genre, with the hash and the seed in
  decimal and hex, including a seed with the high bit set.

- **Reseeding** a channel, and **duplicating** one, give the new channel a fresh random seed (a custom channel's rule).
- **Importing** a rules file keeps the seed byte for byte.
- **Editing** an automatic channel turns it into a custom channel and keeps its seed.

Changing the channel's name, or any field other than `seed`, never changes the seed.

## Name

`name` is the display name of the channel: 1 to 100 characters, free text. It is the only cosmetic field that travels with
the rules, so that a channel shared from one TV is recognisable on another. It is never used for matching, for ordering
or for working out the seed. Channel number and logo are not in the format.

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

**Normalising an item's rating.** The server gives each item a free-form rating string (or none). Before looking it up:

1. Trim spaces from both ends.
2. Convert the ASCII letters `a` to `z` to upper case (nothing else: no locale). `Approved` becomes `APPROVED`, and is
   accepted as `G`.
3. Look the result up on the ladder. If it is there, that is its rank.
4. Otherwise, if it starts with `TV-`, remove one trailing content-descriptor group (a hyphen followed by one or more of
   the letters `D`, `L`, `S`, `V` and `F`, for example `-V` or `-DLSV`) and look up what is left. A descriptor never
   changes the age tier: `TV-14-V` is `TV-14`.
5. Anything else has no rank.

**Fail closed.** Under a ceiling, an item is allowed **only if** it has a rank at or below the ceiling's rank. An item
with no rating, a blank rating, `NR`, `Unrated`, or any rating that is not on the ladder (for example a British or German
rating such as `FSK 16`) is **not** allowed. With no ceiling, every item is eligible, rated or not. Account limits set on
the server can only narrow what the rules allow; they can never widen a ceiling.

Normalisation is the working assumption and is being checked against Jellyfin's own rating parsing before it is final.

## Item IDs

Every item ID in a rules file is in **canonical form**: exactly 32 lower-case hexadecimal characters (`0` to `9` and `a`
to `f`) with no hyphens, no braces and no other characters. Some Jellyfin endpoints return IDs with hyphens
(`8-4-4-4-12`) or in upper case.

- A client that gets an ID from the server must normalise it to canonical form first: remove any hyphens and braces, then
  convert to lower case. If anything other than 32 hex characters is left, it is not an ID.
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

**Random number stream.** For a stream that starts from `seed_n`, keep a state `s` (start with `s = seed_n`). To draw the
next 64-bit number `r`: `r = splitmix64(s)`, then `s = s + 0x9E3779B97F4A7C15`.

**Bounded integers** (a number from 0 up to but not including `bound`, for `bound` of at least 1) use **rejection
sampling**, never plain modulo:

```text
threshold = (2^64 - bound) mod bound          (unsigned; the remainder of (0 - bound) divided by bound)
loop:
    r = next draw
    if r >= threshold: return r mod bound
```

**`fnv1a64(bytes)`** is 64-bit FNV-1a:

```text
h = 0xcbf29ce484222325
for each byte b: h = (h ^ b) * 0x100000001b3
return h
```

`fnv1a64` of no bytes is `0xcbf29ce484222325`, and of the single byte `a` (0x61) is `0xaf63dc4c8601ec8c`. The input to
the ordering hash is the **32 ASCII bytes** of the canonical ID, not UTF-16 text and not the 16 raw bytes of the GUID.

**Hash key.** The key of an item (or, for `RANDOM_IN_ORDER`, of a series) in epoch `n` is
`splitmix64(seed_n + fnv1a64(canonicalIdAsAsciiBytes))`. Keys are compared **as unsigned numbers**. A signed comparison
is a bug that only shows for keys with the high bit set, so the vectors include one. Ties are broken by the canonical ID
bytes, unsigned, ascending.

`TODO(vectors)`: published vectors for the per-epoch seed, bounded integers and key order.

## String matching

Genre, tag and other names in the rules are matched against the server's names with these rules and no others:

- compare **exact UTF-8** bytes;
- fold case for the ASCII letters `A` to `Z` only (they become `a` to `z`); every other character, including accented
  and non-Latin letters, must match exactly;
- do **no** Unicode normalisation (NFC and NFD forms are different strings);
- use **no** locale, no collation and no trimming.

Names are never used to sort or to hash. Only canonical IDs are.

## Unknown fields and unsupported kinds

A reader will meet files written by newer apps. Two cases must be handled differently:

- **An unknown field** (a key the reader does not know, in any object: the top level, a source, `ordering`, `timing`,
  `broadcastDay`, `filler` or a filler source) is **kept and ignored**. The reader must not use it, must not fail on it,
  and must write it back unchanged when it writes the file again.
  `docs/rules-format/examples/forward-compat-extras.json` has unknown fields at every level.
- **An unknown kind** (a `type` of a source, an ordering `mode` or a filler `mode` that the reader does not know) is **not**
  an unknown field. The channel is **unsupported**: it is not scheduled, and nothing is dropped silently to make it
  work. This keeps a channel from playing the wrong thing.

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
| Length of a source `name` | 256 characters |
| Length of the channel `name` | 100 characters |

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
  source kind, no sources, `between-every-N` without `every`).
- `docs/rules-format/vectors/`: test vectors for the primitives (added with the test-vector work).

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
| `docs/rules-format/examples/ordering-shuffle.json` | `SHUFFLE`. |
| `docs/rules-format/examples/ordering-chronological.json` | `CHRONOLOGICAL`. |
| `docs/rules-format/examples/ordering-random-in-order.json` | `RANDOM_IN_ORDER`, a block of 2. |
| `docs/rules-format/examples/timing-unaligned.json` | No grid, 12-hour epochs, blocks of 4. |
| `docs/rules-format/examples/broadcast-day-offset.json` | A broadcast day at UTC-5. |
| `docs/rules-format/examples/filler-none.json` | Filler mode `none`. |
| `docs/rules-format/examples/filler-pad-to-grid.json` | Filler mode `pad-to-grid` with a library. |
| `docs/rules-format/examples/filler-tag-source.json` | Filler mode `pad-to-grid` with a tag. |
| `docs/rules-format/examples/filler-between-every-n.json` | Filler mode `between-every-N` with a collection. |
| `docs/rules-format/examples/full.json` | Every section together. |
| `docs/rules-format/examples/forward-compat-extras.json` | Unknown fields at every level. |
