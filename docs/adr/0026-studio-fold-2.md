# ADR-0026: Studio fold 2 (closing look-alike gaps in studio matching)

**Status:** Proposed. Owner decisions recorded; revised 2026-11-13 after the security review (round 1); awaiting
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

### The pass

Read the name by code point, from the start, with a variable **last kept** that is empty at the start. For each code
point c, apply the first rule that fits:

1. c is a surrogate that is not half of a pair: **remove** it.
2. c is in R: **remove** it.
3. c is U+200C or U+200D: if last kept is empty or is in L, **remove** c; otherwise **keep** c and set last kept to c.
4. Otherwise **keep** c, written as its ASCII fold (`A` to `Z` become `a` to `z`; nothing else changes), and set last
   kept to c.

A removed code point never becomes last kept, so a joiner is judged by the last code point that is actually in the key,
after every removal. The key is the kept code points in order. A name whose key is empty is **dropped**: an item has no
such studio, and a rules name with an empty key matches nothing. No Unicode normalisation and no locale are used.

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
| U+0915 U+094D U+200D U+0937 (Devanagari) | last kept U+094D, not in L: U+200D kept | unchanged |

The exclusion `Walt Disney` has the key `waltdisney`, so the first five rows are excluded by it, and every
multi-word kid-list entry is matched the same way.

### Properties

Three facts about the pass give the properties below: (a) whether a code point is removed depends only on that code
point and on last kept; (b) a removed code point never changes last kept; (c) the ASCII fold maps a kept code point to
one in the same class (`A` to `Z` and `a` to `z` are all in L and none is in R or a joiner).

- **Idempotent: fold2(fold2(x)) = fold2(x).** The key holds no surrogate half and nothing in R, so rules 1 and 2 remove
  nothing from it, and rule 4 keeps every non-joiner. Each joiner in the key was kept because its last kept code point
  was outside L; in the second pass the code point before it in the key is that same code point (ASCII-folded, same
  class by (c)), so it is kept again. Example: the key of `Walt` U+3000 U+200D `Disney` is `waltdisney`; fold 2 of
  `waltdisney` is `waltdisney`.
- **Fold 2 of fold 1 is fold 2: fold2(fold1(x)) = fold2(x).** Fold 1 only removes lone surrogates and code points of its
  dropped set, writes U+0009, U+000A, U+000D, U+2028 and U+2029 as U+0020, trims S from the ends and ASCII-folds. Every
  code point it removes or writes is a lone surrogate or in R (its dropped set is part 1 of D, its mapped code points
  are in B, U+0020 and the trimmed set are S), and it never removes or alters a joiner or any other code point. So x and
  fold1(x) have the same kept code points in the same order, up to ASCII case, and by (a) to (c) the same key. Example:
  fold 1 of U+3000 U+200D `Disney` trims the U+3000 and gives U+200D `disney`; fold 2 of that removes the leading
  U+200D (last kept empty): `disney`, as fold 2 of the original. This is why fold 2 can be
  applied at match time to names the sync stored after fold 1, and to the fold-1 keys a resolved starter writes into
  its rules as `studio` source names.
- **Fold 2 never splits what fold 1 joins.** If fold1(a) = fold1(b), then fold2(a) = fold2(fold1(a)) = fold2(fold1(b))
  = fold2(b). Moving a file from fold 1 to fold 2 can only add matches: an exclusion excludes at least what it did, a
  source and the `studios` filter take at least what they did.

The table invariant behind the second property (fold 1's dropped, mapped and trimmed sets are all inside R) is checked
by a test in every port.

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
| `GroupCollections.isBoxSet` | box-set detection (`docs/rules-format.md`, the name ends in U+0020 `collection`) | fold 1. It needs the space; a change changes which collections group and would need its own version |

A snapshot therefore holds fold-2 studio keys alongside the fold-1 ones for every item and series: the holds read fold 2
on every build, and catalog, curation and any `formatVersion` 3 file read fold 1. Keys are shared through `NameFolds`
as today, but most multi-word names give a second, distinct string. The memory and the snapshot build time are to be
measured on the older device before the change merges.

Within one kid channel the hold and a `formatVersion` 3 studio exclusion then use different folds: the hold can call a
title made for kids under fold 2 while the file's exclusion misses the same name under fold 1. That window closes with
the one-time rewrite below.

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
- **One-time rewrite.** The release that writes 4 rewrites, once, every rules file that says `formatVersion` 3 and holds
  a `studio` source, a `studio` exclusion or a `studios` filter: `formatVersion` becomes 4 and nothing else changes
  (unknown fields are written back as the JSON profile says). A file that says 1 or 2 is not rewritten: a studio
  source there is an unknown kind, and rewriting it would make an unsupported channel supported. A file already at 4 is
  left alone, so a second TV doing the same rewrite changes nothing. After the rewrite no TV can read such a file under
  fold 1, and by "fold 2 never splits what fold 1 joins" no rewritten channel loses a match it had. With one household
  using the app today, a format change of this kind costs nothing in practice; the gate above is still the rule for
  later households. From that release on, every writer writes 4.

### Tests

`docs/rules-format/vectors/studio-fold-2.json`, shared by all ports, with at least:

- each code point of S, alone, at the start, at the end and between two words;
- each code point of B, including U+000B, U+000C, U+001C to U+001F and U+0085 between two words;
- each range of D, by its first and last code point and one inside, alone and inside a word, including the reserved
  default-ignorables U+2065, U+FFF0, U+FFF8, U+E0000, U+E0002, U+E0080, U+E01F0 and U+E0FFF;
- ZWJ and ZWNJ: inside a Latin word; at the start; after a code point of S and between two of them; after a dropped
  code point (`s` U+034F U+200D); a run of joiners inside a Latin word; after a combining mark (U+0301, U+036F); after
  U+024F and after U+0250, after U+036F and after U+0370 (the boundaries); after a non-Latin letter (kept); a run after
  a non-Latin letter (kept);
- the walk-through table above;
- a name whose key is empty;
- for **every** vector: fold2(fold2(x)) = fold2(x) and fold2(fold1(x)) = fold2(x);
- `formatVersion` 3 files giving the fold-1 answer for the same names, and `formatVersion` 4 files the fold-2 answer.

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
   differently. (This replaces the first draft's "upgrade on save", which could leave an exclusion on fold 1 for good.)
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
- **Other code points that render as nothing in some fonts** but are not in R, such as U+16FE4 (Khitan small script
  filler), and code points that a later Unicode version makes default-ignorable.
