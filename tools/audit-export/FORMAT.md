# The `audit-snapshot` format, version 1

A library snapshot for the lineup audit (`./gradlew :core:engine:lineupAudit`, see [README.md](README.md)). It holds
what the rule engine reads of a library, as `RuleSnapshot` holds it, and nothing else: **no media, no server address,
no account.** It does hold titles: the name of an item, a series and a collection is a field, because a recipe's
anchors match by name. A snapshot of a real library is private. Write it outside the repository, never commit it and
never paste one into an issue (`.gitignore` also keeps a stray copy out).

The audit reads it with `AuditSnapshotCodec` in `core:engine` (the `jvmAudit` compilation), which is strict: a file
over 256 Mi characters is refused before it is parsed, every field is typed, an unknown key is refused, and a reason
for refusing names the place and the rule, never the file's text.

## The file

A JSON object. The exporter writes it with one key to a line and one item, series or collection to a line; the reader
takes any layout and any order of items, series and collections (it puts them in canonical-ID order).

| Key | Type | Meaning |
| --- | --- | --- |
| `format` | `"audit-snapshot"` | Required. |
| `version` | `1` | Required. A reader refuses any other version. |
| `cutoffMs` | integer | `RuleSnapshot.cutoffMs`: when the snapshot was taken, in milliseconds since the epoch. |
| `collectionsSynced` | boolean | `RuleSnapshot.collectionsSynced`: whether a full pass over the collections succeeded. |
| `libraries` | list of IDs | `RuleSnapshot.libraries`: the libraries the items were selected from. |
| `items` | list of items | `RuleSnapshot.items`, unique by `id`. |
| `series` | list of series | `RuleSnapshot.series`: the series an episode among the items points to. |
| `collections` | list of collections | `RuleSnapshot.collections`. |
| `viewer` | object, optional | What the viewer added to the lineup (below). |

An **ID** is the rules format's canonical form: exactly 32 lower-case hexadecimal characters, no hyphens, no braces.
The reader repairs nothing. A **text** is at most 1,024 UTF-16 code units and a **list** at most 1,024 entries (a
collection's member lists, 2,000,000).

### An item (`RuleItem`, with its `ScheduleItem`)

| Key | Type | Field |
| --- | --- | --- |
| `id` | ID | `ScheduleItem.id` |
| `libraryId` | ID | `RuleItem.libraryId` |
| `seasonId` | ID or null | `RuleItem.seasonId` |
| `kind` | `"Movie"` or `"Episode"` | `ScheduleItem.kind` |
| `runtimeMs` | integer, 1 or more | `ScheduleItem.runtimeMs` |
| `createdAtMs` | integer | `ScheduleItem.createdAtMs` |
| `deletedAtMs` | integer or null | `ScheduleItem.deletedAtMs` |
| `premiereDate` | `YYYY-MM-DD` or null | `ScheduleItem.premiereDate` |
| `productionYear` | integer or null | `ScheduleItem.productionYear` |
| `seriesId` | ID or null | `ScheduleItem.seriesId` |
| `seasonNumber` | integer or null | `ScheduleItem.seasonNumber` |
| `episodeNumber` | integer or null | `ScheduleItem.episodeNumber` |
| `officialRating` | text or null | `RuleItem.officialRating`, as stored: null, `""` and `" "` are three values |
| `genres`, `tags`, `studios` | lists of text | `RuleItem.genres`, `.tags`, `.studios`, in the server's order |
| `name` | text or null | `RuleItem.name` |

The reader runs `ScheduleItem.problem` on the fields (the date, the year, the season and episode numbers, and that a
movie has no series data), so a file the app's own types would refuse is refused.

### A series (`RuleSeries`)

`id`, `officialRating` (text or null, as stored), `genres`, `tags`, `studios`, `name`.

### A collection (`RuleCollection`)

`id`, `name` (text or null), and four lists of IDs: `movies`, `episodes`, `series`, `seasons`. A member may be outside
the selected libraries.

### The viewer (optional)

```json
"viewer": {
  "switchedOn": ["recipe-key", "another-key"],
  "customChannels": [{"name": "My comedies", "rules": { "...": "a rules file, docs/rules-format.md" }}]
}
```

`switchedOn` names the catalog recipes the viewer switched on: the audit puts them in the lineup as the viewer's (on,
chosen by the viewer) and plans again, as the refresh at the next boundary does. `customChannels` are the viewer's own
channels: they go in as kept channels, as a reset keeps them, and the audit reports the pool their rules select. The
lineup file itself is read by `core:domain` and is not an input.

## Acceptance rules

The exporter applies the app's own rules, so a snapshot holds what the app's `ruleSnapshot` would hold of the same
library, and nothing it would leave out. They are written again in `acceptance.py`. Two stages, each mirroring code
the app runs.

### Stage 1: a server answer as the library cache holds it

From `data:jellyfin` (`toMediaItem`, `toSeriesInfo`, `toCollectionMember`, `studiosOf`, `createdAtOf`,
`premiereDateOf`, `duration`).

- **Which items are asked for.** Each video library, at any depth, of the types `Movie`, `Episode`, `Video` and
  `MusicVideo`; the box-set collapsing setting is turned off, so a collection does not hide its movies. A library is a
  video library when its collection type is movies, tvshows, homevideos, musicvideos or absent (mixed).
- **A wrong type fails the answer.** The app's decoder fails a page when a field is present with the wrong type (a year
  that is text, say); so does the exporter, and it writes no snapshot. Missing, null and unknown fields are fine.
- `runtimeMs` is `floorDiv(RunTimeTicks, 10000)`; a missing, zero or negative value is no runtime.
- `createdAtMs` is the creation moment in whole milliseconds: the time in ticks floored, any UTC offset honoured, a
  time with no offset read as UTC; null when the text is missing, unreadable or beyond what a `Long` of milliseconds holds.
- `premiereDate` is the UTC calendar date of `PremiereDate`, null when it is missing, unreadable or its UTC year is
  not 1 to 9999.
- `officialRating`, `genres`, `tags` and the names are kept exactly as received.
- `studios`: a null entry or one with no name is dropped, so is a name over 256 characters; each name is cleaned
  (`cleanStudioName`: controls and format characters removed, a tab, CR, LF, U+2028 and U+2029 made a space, the ends
  trimmed of Unicode space separators); a name with nothing left is dropped; at most 16 are kept.
- A collection's members are read from its direct children only. A member whose type is not Movie, Episode, Series or
  Season (a BoxSet inside a BoxSet, a photo, anything new) is not kept.

### Stage 2: the rows as the rule snapshot has them

From `data:library` (`ScheduleInputs.read`, `RuleItemMapper`, `RuleInputs`).

- **`ScheduleInputs.read`.** Each item is dropped by the first of these that applies, in this order, or kept: its kind is
  not Movie or Episode (`Video`, `MusicVideo`); its ID is not canonical; its library ID is not canonical; it has a
  series ID that is not canonical; its runtime is missing or not positive; its creation time is missing; its creation or
  deletion time is outside plus or minus 2^52 ms; `ScheduleItem.problem` finds a malformed premiere date, a production
  year outside 1 to 9999, a negative season or episode number, or series data on a movie. Of two items with one
  canonical ID the first wins. The items are in canonical-ID order.
- **`RuleItemMapper`.** An item with a season ID that is not canonical is kept, with no season. The rating, the lists
  and the name are as stored.
- **`RuleInputs.series`.** A series is in the snapshot when an episode among the items points to it and the series
  list holds it; of two rows of one canonical ID the first wins; in canonical-ID order. An episode whose series the
  list lacks stays in, with no series row.
- **`RuleInputs.collections`.** Every collection held, whatever the libraries, with its members in the four sets by
  kind, a member that is not a canonical ID left out, in canonical-ID order, the first of an ID winning.
- **`libraries`** are the selected libraries that are canonical IDs. **`collectionsSynced`** is true only when every
  page of the collections and of their members was read.

### The extras filter is the audit's, not the exporter's

`ExtrasFilter` (clips under a minute, items past the sanity limits, creditless openings, trailers and featurettes, the
shorter copies of one episode number) is applied by the planner to every snapshot it ranks from
(`LineupService.readForPlan`). The audit applies the same `ExtrasFilter`, from the app's own code, so a snapshot is the
read **before** it, as `ruleSnapshot` returns it, and the exporter must not apply it (the filter is idempotent, but a
copy would drift). The audit's output says how many items it took out.

### Where the exporter differs from the app, on purpose

- **One library for an item.** The app stores one library per item, and which one an item gets when two libraries
  list it depends on the order the sync met them; the exporter gives it the first library in ID order and counts the
  second listing as a duplicate. The catalog's recipes read no library, so this moves no starter's pool; a custom
  channel with a `library` source could differ.
- **Bounds the file has and the app does not.** A name over 1,024 UTF-16 code units is cut to it; a genre, tag or studio
  over it, or a rating over it, is dropped (the rating then reads as none). Each is counted. A server that sends them
  is not one a recipe could match anyway.
- **Dates.** A date with a bracketed zone ID (`2020-01-01T00:00:00Z[UTC]`) is read as unreadable; Jellyfin does not send
  one.
- **The time of the snapshot** is the time of the export, and no item is deleted: the export is the server as it is.

## The output: `lineup-audit`, version 1

`AuditJson` writes it (`--format json`, or `KEY.json` in the output folder), and `AuditText` writes the text of the same
report. It says first that it is **the planner's view**: `LineupPlanner.plan` and the curation as the planner runs them,
without the rating ceilings and the late-on pools of a built day (`LineupDays.curationChannelOf`); what a day airs is out
of scope.

- Hours are whole hundredths of an hour (`centiHours`), as integers; a title has `centiHours` too.
- `termOrder`: the order of a score's terms: `specificity, genre, tag, studio, decade, anchor, kids, format, tone`. Every
  score is its terms in 16-bit fixed point (65,536 is one point); `total` is their sum, the score the curation compares.
  `decade` is the guest-only penalty: a decade channel is a guest-only one.
- `starters`: one row for every starter of the catalog: `state` (`on` or `off`), `by` (`planner` or `viewer`), its
  `shelfReason` when it is off (`Empty`, `TooThin`, `BelowCap`, `OptIn`; with nothing on yet, `NotFinalised`), `candidates`,
  and two readings of its stats: `selection` (homed over every candidate, the planner's selection) and `onAir` (homed over
  the channels that are on), each with `titles`, `totalCentiHours`, `usableCentiHours` (under the share cap), `anchors`,
  `qualifies` and, for `selection`, the selection `score`.
- `overlaps`: each pair of channels that are on and air the same items: `titles` and `centiHours`.
- `totals`: titles, how many have a home, the titles with no home by cause, the titles each channel airs, and how many
  channels air how many titles.
- `titles`: per title (a series, or a film), its `home` and `runnerUp` (the channel, the total and the terms), its
  `guestSpots`, or no home and its `cause`: `no-source-match`, `excluded-by-recipe`, `held-by-kid-or-grown-up-hold`,
  `filtered-or-rated`, `not-airable`, `anime-rule`, or `only-on-shelved-channels`, with the `closest` recipes (the ones
  that took it furthest), the `shelvedLanes` (where it is a candidate) and the `fallback` channels that air it anyway
  (the leftovers).
