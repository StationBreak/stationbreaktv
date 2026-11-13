# ADR-0026: Studio fold 2 (closing look-alike gaps in studio matching)

**Status:** Proposed. Owner decisions recorded; revised 2026-11-13 after security review rounds 1-3; awaiting
security re-review. Nothing is implemented yet.

**Date:** 2026-11-12 (revised 2026-11-13)

## Context

Studio names come from the media server's metadata (Jellyfin, filled from TMDB or by hand). Two features match them:

- the **rules format** (version 3): a `studio` source, a `studio` exclusion and the `studios` filter match under the
  **studio fold** of `docs/rules-format.md` (String matching, Studio names): clean, then ASCII fold. This ADR calls it
  **studio fold 1**;
- the **holds** (ADR-0019 `kid-hold-3`, ADR-0020 `grown-up-hold-2`): the kid-brand studio list is matched through the
  same cleaning (`cleanStudioName`), so a title from a listed children's label counts as made for kids.

Studio fold 1 already drops controls and format characters and trims the ends. A security review of the Roku port
(StationBreak-Roku #22) found studio names that still **look like** a listed or excluded studio but **do not match** it,
and the same gaps are in the reference and every port (the ports follow `docs/rules-format.md` exactly):

1. **Space look-alikes inside a name.** Only the ends are trimmed. `Walt Disney` with U+00A0 (no-break space), U+2007,
   U+202F, U+3000 or any of U+2000 to U+200A between the words does not match `Walt Disney`. Tab and line breaks become
   U+0020, but U+000B, U+000C, U+001C to U+001F and U+0085, which also render as a break or as white space, are dropped,
   so `Walt` U+000B `Disney` folds to `waltdisney`. Runs of spaces are not collapsed (`Walt  Disney`).
2. **Blank-width letters.** U+3164 (Hangul filler), U+FFA0 (halfwidth Hangul filler), U+2800 (Braille pattern blank)
   and, in many fonts, U+115F and U+1160 (Hangul choseong and jungseong fillers) render as a blank the width of a space.
3. **Joiners inside Latin words.** U+200C (ZWNJ) and U+200D (ZWJ) are kept everywhere, so `Disney` with a ZWJ between
   `s` and `n` does not match `Disney`. They are kept on purpose: some scripts need them. Latin never does.
4. **Invisible code points that are not format characters.** Fold 1 drops the Unicode 15 format (Cf) set. Many other
   code points render as nothing: U+034F (combining grapheme joiner), U+17B4 and U+17B5 (Khmer inherent vowels), the
   variation selectors, the Mongolian free variation selectors, and the **reserved** default-ignorable code points
   (U+2065, U+FFF0 to U+FFF8, U+E0000, U+E0002 to U+E001F, U+E0080 to U+E00FF, U+E01F0 to U+E0FFF), which a renderer
   must also show as nothing. `Dis` U+E0002 `ney` and `Pix` U+2065 `ar` evade fold 1.

### Who is affected

- **A studio exclusion** a parent wrote into a rules file (for example "never this studio on the kids channel") is
  evaded by a studio name with one of the code points above: the excluded titles air. This is the case that matters
  for safety.
- **A studio source** matches fewer titles than the person meant. Harmless, but surprising.
- **The kid hold** (`kid-hold-3`) fails closed for kid channels (a title whose kid studio does not match only loses that
  signal; genres and ratings still count), but the grown-up hold (`grown-up-hold-2`) then does not move it off grown-up
  channels.

An attacker needs control of the server's metadata, or a scraper that brings such a name in. Neither is likely, but
studio names come from outside the household, and the exclusion is a promise to a parent, so the fold should not be
evadable by text that looks identical.

## Decision (proposed)

Add **studio fold 2**, a new, separately named function. Studio fold 1 (`cleanStudioName` and the rules text that
defines it) is **not changed**. Fold 2 is one pass over the name by code point, followed by nothing: no trim, no
collapse. It turns a name into a **comparison key**; two names match when their keys are equal code point for code
point. It is applied to the name in the rules and to every studio name of an item or series alike. Every set below is a
fixed list of code points, never a platform's idea of whitespace, control, format, letter or case.

### The sets

- **Space set S** (Unicode Zs): U+0020, U+00A0, U+1680, U+2000 to U+200A, U+202F, U+205F, U+3000.
- **Blank and break set B**: U+0009, U+000A, U+000B, U+000C, U+000D, U+001C to U+001F, U+0085, U+2028, U+2029,
  U+115F, U+1160, U+2800, U+3164, U+FFA0.
- **Dropped set D**: the union of
  1. every code point of fold 1's dropped set: U+0000 to U+001F, U+007F to U+009F, U+00AD, U+0600 to U+0605, U+061C,
     U+06DD, U+070F, U+0890 to U+0891, U+08E2, U+180E, U+200B, U+200E to U+200F, U+202A to U+202E, U+2060 to U+2064,
     U+2066 to U+206F, U+FEFF, U+FFF9 to U+FFFB, U+110BD, U+110CD, U+13430 to U+1343F, U+1BCA0 to U+1BCA3, U+1D173 to
     U+1D17A, U+E0001, U+E0020 to U+E007F;
  2. the whole **Default_Ignorable_Code_Point** set of `DerivedCoreProperties.txt`, **Unicode 15.1.0**, reserved code
     points included, except U+200C and U+200D: U+00AD, U+034F, U+061C, U+115F to U+1160, U+17B4 to U+17B5, U+180B to
     U+180D, U+180E, U+180F, U+200B, U+200E to U+200F, U+202A to U+202E, U+2060 to U+2064, U+2065, U+2066 to U+206F,
     U+3164, U+FE00 to U+FE0F, U+FEFF, U+FFA0, U+FFF0 to U+FFF8, U+1BCA0 to U+1BCA3, U+1D173 to U+1D17A, U+E0000, U+E0001,
     U+E0002 to U+E001F, U+E0020 to U+E007F, U+E0080 to U+E00FF, U+E0100 to U+E01EF, U+E01F0 to U+E0FFF;
  3. U+2800, which the first draft of this ADR listed and which is not default-ignorable.

  The list is frozen here. A later Unicode version that changes Default_Ignorable_Code_Point changes nothing until a new
  fold version says so. The generator of the vector file checks list 2 against `DerivedCoreProperties-15.1.0.txt`.
- **Removed set R** = S ∪ B ∪ D. The sets overlap (U+3164 is in B and D, for example); only R matters to the fold. S, B
  and D are named apart so a reader can check each against its source.
- **Latin range L**: U+0000 to U+024F (Basic Latin, Latin-1 Supplement, Latin Extended-A and -B) and U+0300 to U+036F
  (Combining Diacritical Marks).
- **Gap set G**: the code points that show as a space or a blank and that fold 1 keeps (as themselves or as U+0020):
  all of S, and U+0009, U+000A, U+000D, U+2028, U+2029, U+115F, U+1160, U+2800, U+3164, U+FFA0. That is S together with
  the members of B that are not in fold 1's dropped set. The other members of B (U+000B, U+000C, U+001C to U+001F,
  U+0085) and the members of D that are not listed here are **not** in G, because fold 1 removes them, and a fold-1
  name must give the same key as the raw name (see Properties).

### The pass

Read the name by code point, from the start, with a variable **last kept** that is empty at the start and a flag
**gap** that is off at the start. For each code point c, apply the first rule that fits:

1. c is a surrogate that is not half of a pair: **remove** it.
2. c is in R: **remove** it; if c is in G, set gap on.
3. c is U+200C or U+200D: if last kept is empty, or last kept is in L, or gap is on, **remove** c; otherwise **keep** c,
   set last kept to c and set gap off.
4. Otherwise **keep** c, written as its ASCII fold (`A` to `Z` become `a` to `z`; nothing else changes), set last kept
   to c and set gap off.

A removed code point never becomes last kept, so a joiner is judged by the last code point that is actually in the key,
after every removal, and by whether a space or blank stood between them. A joiner never joins across a space, in any
script. The key is the kept code points in order. A rules name or a studio name whose key is empty is **empty**:
under `formatVersion` 4, a `studio` source or a `studios` filter name that is empty matches nothing, and a `studio`
exclusion that is empty matches every item that has, or whose series has, a studio whose key is empty (see Properties
for why). No Unicode normalisation and no locale are used.

Because every space and blank is removed, names that differ only in spacing match: `Walt Disney`, `Walt  Disney`,
`Walt` U+3000 `Disney`, `Walt` U+3164 `Disney` and `WaltDisney` all have the key `waltdisney`. A false merge of two real
studios whose names differ only by a space is accepted as negligible.

### Why this closes the reported cases

| Input | Fold 2 walk | Key |
| --- | --- | --- |
| `Walt` U+2009 U+200D `Disney` | U+2009 removed (rule 2); last kept is `t` (in L), so U+200D removed | `waltdisney` |
| U+3000 U+200D `Walt Disney` | U+3000 removed; last kept empty, so U+200D removed | `waltdisney` |
| `Walt` U+3000 U+200D `Disney` | as the first row | `waltdisney` |
| `Walt` U+3164 `Disney` | U+3164 removed (B) | `waltdisney` |
| `Walt` U+000B `Disney`, `Walt` U+0085 `Disney` | removed (B) | `waltdisney` |
| `Dis` U+E0002 `ney` | U+E0002 removed (D, reserved default-ignorable) | `disney` |
| `Pix` U+2065 `ar` | U+2065 removed (D) | `pixar` |
| `Pathe` U+0301 U+200D (decomposed `Pathé`) | last kept is U+0301 (in L), so U+200D removed | `pathe` U+0301 |
| `Dis` U+200D U+200C U+200D `ney` | each joiner sees last kept `s`, all removed | `disney` |
| `Pixars` U+034F U+200D | U+034F removed; last kept `s`, U+200D removed | `pixars` |
| U+0915 U+094D U+200D U+0937 (Devanagari) | last kept U+094D, not in L, gap off: U+200D kept | unchanged |
| `東映` U+3000 U+200D `Animation` | U+3000 removed, gap on; last kept `映` is not in L, but gap is on: U+200D removed | `東映animation` |
| `東` U+000B U+200D `x` | U+000B removed but not in G, gap off; last kept `東`: U+200D kept | `東` U+200D `x` |
| `東映` U+200D (trailing) | last kept `映`, gap off: U+200D kept | `東映` U+200D (a residual gap) |

The exclusion `Walt Disney` has the key `waltdisney`, so the first five rows are excluded by it, and every
multi-word kid-list entry is matched the same way.

### Properties

Four facts about the pass give the properties below: (a) whether a code point is kept depends only on that code point,
on last kept and on gap; (b) a removed code point never changes last kept, and changes gap only by setting it on when
it is in G; (c) every keep sets gap off, so gap only says whether a code point of G was removed since the last keep;
(d) the ASCII fold maps a kept code point to one in the same class (`A` to `Z` and `a` to `z` are all in L and none is
in R, in G or a joiner).

- **Idempotent: fold2(fold2(x)) = fold2(x).** The key holds no surrogate half and nothing in R (so nothing in G), so in
  the second pass rules 1 and 2 remove nothing, gap is never set on, and rule 4 keeps every non-joiner. Each joiner in
  the key was kept because its last kept code point was outside L and gap was off; in the second pass the code point
  before it in the key is that same code point (ASCII-folded, same class by (d)) and gap is off, so it is kept again.
  Example: the key of `東映` U+3000 U+200D `Animation` is `東映animation`, and fold 2 of that is the same.
- **Fold 2 of fold 1 is fold 2: fold2(fold1(x)) = fold2(x).** Fold 1 does four things, and none changes the key:
  1. it removes lone surrogates and the code points of its dropped set: all are removed by fold 2 too (its dropped set
     is part 1 of D), and none is in G, so in x they neither change last kept nor set gap;
  2. it writes U+0009, U+000A, U+000D, U+2028 and U+2029 as U+0020: both the original and U+0020 are in G, so fold 2
     removes either and sets gap on either way;
  3. it trims S from both ends. A leading run sets gap on in x only before anything is kept, when every joiner is
     removed anyway (last kept empty), and the first keep sets gap off. A trailing run is followed in x only by code
     points of fold 1's dropped set (else it would not be trailing after step 1), none of which is a joiner or kept;
  4. it ASCII-folds, which by (d) changes no decision.

  Fold 1 never removes or alters a joiner or any other code point. So, by (a) to (d), every decision of fold 2 is the
  same on x and on fold1(x), and so is the key. This is why U+000B and the other B members fold 1 removes are kept out
  of G: `東` U+000B U+200D `x` becomes `東` U+200D `x` under fold 1, so counting U+000B as a gap would give the raw
  name and its fold-1 form different keys. Example: fold 1 of U+3000 U+200D `Disney` trims the U+3000 and gives
  U+200D `disney`; fold 2 of either removes the U+200D (last kept empty) and gives `disney`. This is why fold 2 can be
  applied at match time to names the sync stored after fold 1, and to the fold-1 keys a resolved starter writes into
  its rules as `studio` source names.
- **Fold 2 never splits what fold 1 joins, for an exclusion.** If fold1(a) = fold1(b), then fold2(a) = fold2(fold1(a))
  = fold2(fold1(b)) = fold2(b). When that shared key is non-empty, the match survives under fold 2. When it is empty
  (a name made only of code points in R that fold 1 keeps, such as U+3164), an empty key on its own would match
  nothing, and the match would be lost. The empty-key rule for exclusions covers that case: an exclusion whose key is
  empty matches every item with a studio whose key is empty. The sync drops only names that are empty under fold 1
  (`ServerText.studioName`), so a studio such as U+3164 is stored and can be matched this way. The snapshot records,
  per item and series, whether it has a studio with an empty fold-2 key. So moving a file from fold 1 to fold 2
  never loses an exclusion match: an exclusion excludes at least what it did. A `studio` source and the `studios`
  filter keep the plain rule (an empty name matches nothing); they lose a match only for names made entirely of
  invisible or blank code points, which only makes the pool smaller and so is safe.

The table invariants behind the second property (fold 1's dropped, mapped and trimmed sets are all inside R; G contains
fold 1's mapped and trimmed sets and nothing from fold 1's dropped set) are checked by a test in every port.

### Where each fold is used

Fold 2 is a separate function (Kotlin: a new `studioKey2` in `core/model` next to `cleanStudioName`); fold 1 is left as
it is. Every current call site of `cleanStudioName`, and of the fold-1 keys built from it, with its fold after this ADR:

| Site | What it does | Fold |
| --- | --- | --- |
| `ServerText.studioName` (`data/jellyfin`) | cleans a studio name at sync, for storage | fold 1 |
| `foldStudioNames`, `NameFolds.ofStudios`, `RuleItem.foldedStudios`, `RuleSeries.foldedStudios` (`core/model`) | fold-1 keys per item and series in the snapshot | fold 1, unchanged; a new `foldedStudios2` (through `NameFolds.ofStudios2`) holds the fold-2 keys |
| `SourceMatcher` (`Source.Studio` name; item and series keys), used by `PoolEvaluator` for sources and for exclusions and by `ItemFilters` for the `studios` filter | rules matching | fold 1 for `formatVersion` 3, fold 2 for `formatVersion` 4 |
| `KidHold.isMadeForKids`, `KidHold.isSeriesMadeForKids` (the `KID_STUDIOS` set), and through them `GrownUpHold` and the kid test in curation `Titles` | the holds | fold 2 (`kid-hold-4`, `grown-up-hold-3`) |
| `Theme.NameMatcher.fold` (catalog studio anchors), `LibraryNames` studio keys, `RecipeResolver`, `CurationLanes` studio anchors, curation `Titles` against `CurationTables` | catalog and curation | fold 1. `NameMatcher` matches whole words, which needs the spaces; a change is a new catalog or curation version, not part of this ADR |
| `CatalogPicker.studios` | the spelling shown for a stored studio | fold 1 (display only) |
| `DraftReader.spelled` and `DraftReader.withStudioNames` (builder), `BuiltInChannelProperties` (channel properties), both through the `StudioNames` lookup (`CatalogPicker.studios`) | the spelling shown for a studio source or exclusion the rules hold | fold 1 (display only). A format-4 exclusion whose name has no fold-1 twin in the library shows its stored spelling; what it matches is unchanged |
| `ChannelRules.requiredVersion` (`core/rules`, `ChannelRules.kt`) and `ChannelRules.normalised` | the lowest version a file's features need, which every save writes | 3 for a studio source, studio exclusion or `studios` filter until the gate has passed, then 4 (see Versioning) |
| `DraftRules.toRules` (builder, `DraftRules.kt`) | the rules a builder save writes, tried at version 3, then 2, then 1 | must branch on "requires 3 or more", not "requires exactly 3" (today `v3.requiredVersion == FORMAT_3`), or a studio channel that requires 4 falls to the version-2 path and drops carried version-3 data such as a `curated` order; it keeps the higher of the previous `formatVersion` and the required one (see Versioning) |
| `RecipeResolver` (`RecipeResolver.kt`, `fitted.copy(formatVersion = fitted.requiredVersion)`) | the version a resolved starter is written at | follows `requiredVersion`: 4 for a starter with a studio source or exclusion once the gate has passed, 3 before |
| Rules read and import: `RulesReader.read` through `LineupCodec` (a channel's rules and its previous rules), and any import or restore of a rules file | where a stored, imported or restored file enters the app | a `formatVersion` 3 file with a studio feature is written back as 4 once the gate has passed (see Versioning) |
| `GroupCollections.isBoxSet` | box-set detection (`docs/rules-format.md`, the name ends in U+0020 `collection`) | fold 1. It needs the space; a change changes which collections group and would need its own version |

A snapshot therefore holds fold-2 studio keys alongside the fold-1 ones for every item and series: the holds read fold 2
on every build, and catalog, curation and any `formatVersion` 3 file read fold 1. Keys are shared through `NameFolds`
as today, but most multi-word names give a second, distinct string. The memory and the snapshot build time are to be
measured on the older device before the change merges.

Within one kid channel the hold and a `formatVersion` 3 studio exclusion then use different folds: the hold can call a
title made for kids under fold 2 while the file's exclusion misses the same name under fold 1. That window closes with
the upgrade to format 4 below.

### Versioning

Changing the fold changes which items air, so by `docs/rules-format.md`'s own rule it needs a new version:

- **Rules format.** `formatVersion` 4 uses studio fold 2 for `studio` sources, exclusions and the `studios` filter. A
  file that says 1 to 3 keeps studio fold 1 exactly, so every port agrees on every existing file. Format 4 adds nothing
  else. `docs/rules-format.md` gains a Studio fold 2 subsection and `channel-rules.v4.schema.json` when this is accepted.
- **Holds.** `KidHold.VERSION_NAME` becomes `kid-hold-4` and `GrownUpHold.VERSION_NAME` becomes `grown-up-hold-3`: the
  grown-up hold is built from `KidHold.isMadeForKids`, so the two names move together (ADR-0020). The lists and the
  rating table are unchanged; only the fold the studio list is matched under changes. The holds are safety policy and
  are fixed at the update, **whatever the `formatVersion` of a channel's file**. `PersistedDays` keys its files by the
  hold names (`PersistedDays.HOLDS`), so every day built under `kid-hold-3` or `grown-up-hold-2` is rebuilt once under
  the new names.
- **The gate: "every port reads 4".** It is not something an app can check at run time, so it is a release gate. Each
  port records the first released version that reads `formatVersion` 4 and runs `kid-hold-4`:

  | Port | Minimum version that reads 4 |
  | --- | --- |
  | Fire OS (Kotlin, this repo) | to be filled at release |
  | Vega OS | to be filled at release |
  | Roku | to be filled at release |

  The owner verifies that every listed version is released and that every TV in the household runs at least it (the
  version and `rulesFormat` are on the app's diagnostics screen), and only then is the release that writes 4 shipped.
  A TV that missed the update (automatic updates off) or was downgraded reads a format-4 file as "update required":
  that channel is not scheduled, so the excluded titles do not air. It fails closed.
- **Upgrade to 4: a one-time rewrite, kept as an invariant.** In the release that ships after the gate has passed:
  - **The rewrite.** On its first start the app rewrites, once, every rules file that says `formatVersion` 3 and holds
    a `studio` source, a `studio` exclusion or a `studios` filter: `formatVersion` becomes 4 and nothing else changes
    (unknown fields are written back as the JSON profile says).
  - **The invariant.** From then on, whenever the app reads, imports or saves a rules file that says `formatVersion` 3
    and holds one of those features, it writes it back as 4, the same way. That covers a file restored from a backup,
    imported from an older TV or from another household, and a file an older TV that missed the gate saves as 3 after
    the rewrite (that TV reads it under fold 1 until a newer app next reads it, which is the existing behaviour of an
    old app). `ChannelRules.requiredVersion` returns 4 for those features, so `normalised` and every save write 4.
    The invariant holds on a TV that has passed the gate as much as on one that missed it: no save on any TV that reads
    4 writes a studio file below 4.
  - **Never lower a file that says 4.** The release that reads 4 ships before the gate, while `requiredVersion` still
    returns 3 for studio features. It must not write a file that says 4 back as 3: `ChannelRules.normalised` and
    `DraftRules.toRules` keep the higher of the previous `formatVersion` and `requiredVersion` when the file holds a
    `studio` source, a `studio` exclusion or a `studios` filter. (Today `DraftRules.toRules` tries version 3 by a fixed
    `FORMAT_3` and `normalised` sets the version to `requiredVersion`, which can lower it; both change in that release.)
  - The upgrade is idempotent: a file already at 4 is left alone, so two TVs doing it at once write the same file.
    A file that says 1 or 2 is never upgraded: a studio source there is an unknown kind, and upgrading it would make an
    unsupported channel supported.

  After the upgrade no up-to-date TV reads such a file under fold 1, and by "fold 2 never splits what fold 1 joins" no
  upgraded exclusion loses a match it had. With one household using the app today, a format change of this kind costs
  nothing in practice; the gate above is still the rule for later households.

### Tests

`docs/rules-format/vectors/studio-fold-2.json`, shared by all ports, with at least:

- each code point of S, alone, at the start, at the end and between two words;
- each code point of B, including U+000B, U+000C, U+001C to U+001F and U+0085 between two words;
- each range of D, by its first and last code point and one inside, alone and inside a word, including the reserved
  default-ignorables U+2065, U+FFF0, U+FFF8, U+E0000, U+E0002, U+E0080, U+E01F0 and U+E0FFF;
- ZWJ and ZWNJ: inside a Latin word; at the start; after a code point of S and between two of them; after a dropped
  code point (`s` U+034F U+200D); a run of joiners inside a Latin word; after a combining mark (U+0301, U+036F); after
  U+024F and after U+0250, after U+036F and after U+0370 (the boundaries); after a non-Latin letter (kept); a run after
  a non-Latin letter (kept); after a non-Latin letter and a code point of G (`東映` U+3000 U+200D `Animation`, removed,
  key `東映animation`); after a non-Latin letter and a member of B that is not in G (`東` U+000B U+200D `x`, kept, and
  the same key as fold 1 of it); at the end after a non-Latin letter (`東映` U+200D, kept);
- the walk-through table above;
- a name whose key is empty; under `formatVersion` 4, an exclusion named U+3164 excludes an item whose only studio is
  U+2800 U+3164, and a `studio` source named U+3164 matches nothing;
- for **every** vector: fold2(fold2(x)) = fold2(x) and fold2(fold1(x)) = fold2(x);
- `formatVersion` 3 files giving the fold-1 answer for the same names, and `formatVersion` 4 files the fold-2 answer.

`docs/rules-format/vectors/upgrade-v4.json`, shared by all ports: a `formatVersion` 3 file with a studio source, one
with a studio exclusion and one with a `studios` filter, each read or imported after the gate, come out as
`formatVersion` 4 with nothing else changed; the same file read twice is written once; a `formatVersion` 3 file
without those features and a `formatVersion` 1 or 2 file come out unchanged; a `formatVersion` 4 file edited and
saved by the release that reads 4 (before the gate) stays 4.

A builder test in each port: a `formatVersion` 4 file with a studio source and a `curated` order, opened and saved,
keeps both and stays at 4.

`docs/rules-format/vectors/kid-hold-4.json`, shared by all ports: titles whose studio is a `KID_STUDIOS` entry spelled
with each kind of look-alike above, made for kids under `kid-hold-4` and not under `kid-hold-3`, and moved off a
grown-up channel by `grown-up-hold-3`.

In each port: every `KID_STUDIOS` entry has a non-empty fold-2 key that is a fixed point of fold 2; two entries that
differ only by a space now share one key, which is harmless (they name the same studio). A test checks that fold 1's
dropped, mapped and trimmed sets are inside R, and the `PersistedDays` hold names are `kid-hold-4` and
`grown-up-hold-3`.

## Consequences

- Every port (Fire OS, Vega OS, Roku) implements fold 2, its vectors, `formatVersion` 4 reading and both hold versions,
  in one release each, before any writer emits `formatVersion` 4 (the gate above).
- The snapshot carries a second studio key list; its cost on the older device is measured before merge.
- Names that differ only in spacing match under fold 2, for rules and holds alike.
- Older apps say "update required" for a format-4 file, the existing behaviour for a newer version.
- Every day built under the old hold names is rebuilt once.

## Owner decisions (2026-11-13)

1. **Version, not an in-place fix; a one-time rewrite.** `formatVersion` 4, `kid-hold-4` and `grown-up-hold-3` as
   above. Fixing fold 1 in place was declined: during an update window two TVs in one household could build different
   lineups from the same file. Once every port reads `formatVersion` 4, the app rewrites, once, every rules file that
   holds a studio source, studio exclusion or studios filter to `formatVersion` 4, so no TV can then read it
   differently. (This replaces the first draft's "upgrade on save", which could leave an exclusion on fold 1 for good.
   The read, import and save upgrade in Versioning keeps this true for files that arrive or are saved as 3 later.)
2. **Joiners: Latin only**, as in rule 3 of the pass, with the Latin range including the combining marks U+0300 to
   U+036F. Adding Greek or Cyrillic buys almost nothing, because their look-alike letters (a Cyrillic `а` for a Latin
   `a`) already evade the fold with or without a joiner.

## Known residual gaps

Studio fold 2 closes invisible and space look-alikes for the sets above. These still do not match, and each needs its
own version to close:

- **Canonical equivalents.** No normalisation: `Pathé` with U+00E9 and with `e` U+0301 are different keys.
- **Compatibility forms.** Fullwidth Latin (U+FF21 to U+FF5A, so a fullwidth `Ｄ` U+FF24 in `Disney`), mathematical
  alphanumerics (U+1D400 to U+1D7FF) and ligatures (U+FB00 to U+FB06, such as U+FB01 for `fi`) are not folded to ASCII.
- **Latin-internal confusables.** `l` and `I`, `rn` and `m`, `0` and `O`, `vv` and `w` stay different.
- **Cross-script confusables.** Cyrillic or Greek letters that render like Latin ones do not match. Closing that needs a
  confusables skeleton (Unicode UTS #39).
- **Joiners after other Latin-like blocks.** A joiner is kept after IPA Extensions (U+0250 to U+02AF), Spacing Modifier
  Letters (U+02B0 to U+02FF), Latin Extended Additional (U+1E00 to U+1EFF, for example a precomposed `ḟ`), the other
  Latin Extended blocks, fullwidth Latin, and combining marks outside U+0300 to U+036F (U+1AB0 to U+1AFF, U+1DC0 to
  U+1DFF, U+20D0 to U+20FF, U+FE20 to U+FE2F). A name using those letters can carry an invisible joiner.
- **A trailing joiner after a non-Latin letter** is kept (`東映` U+200D does not match `東映`). Dropping it would need
  the pass to look ahead; it is invisible only at the end of a name, so it is left for a later version.
- **A joiner between a non-Latin letter and a following Latin letter** is kept: `東映` U+200D `Animation` has the key
  `東映` U+200D `animation`, and the exclusion `東映 Animation` has `東映animation`, so it does not match. Closing it
  needs the pass to hold a joiner back until the next kept code point is known (buffered joiners); it is left for a
  later version.
- **Other code points that render as nothing in some fonts** but are not in R, such as U+16FE4 (Khitan small script
  filler), and code points that a later Unicode version makes default-ignorable.
