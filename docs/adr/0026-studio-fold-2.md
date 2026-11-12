# ADR-0026: Studio fold 2 (closing look-alike gaps in studio matching)

**Status:** Proposed (needs an owner decision on the two open questions at the end). Nothing is implemented yet.

**Date:** 2026-11-12

## Context

Studio names come from the media server's metadata (Jellyfin, filled from TMDB or by hand). Two features match them:

- the **rules format** (version 3): a `studio` source, a `studio` exclusion and the `studios` filter match under the
  **studio fold** of `docs/rules-format.md` (String matching, Studio names): clean, then ASCII fold;
- the **holds** (ADR-0019 `kid-hold-3`, ADR-0020): the kid-brand studio list is matched through the same cleaning
  (`cleanStudioName`), so a title from a listed children's label counts as made for kids.

The studio fold already drops controls and format characters and trims the ends. A security review of the Roku port
(StationBreak-Roku #22) found studio names that still **look like** a listed or excluded studio but **do not match** it,
and the same gaps are in the reference and every port (the ports follow this document exactly):

1. **Space look-alikes inside a name.** Only the ends are trimmed. `Walt Disney` with U+00A0 (no-break space), U+2007,
   U+202F, U+3000 or any U+2000 to U+200A between the words does not match `Walt Disney`. Tab and line breaks already
   become U+0020, so the handling is inconsistent. Runs of spaces are not collapsed either (`Walt  Disney`).
2. **Joiners inside Latin words.** U+200C (ZWNJ) and U+200D (ZWJ) are kept everywhere, so `Dis‍ney` (a ZWJ inside) does
   not match `Disney`. They are kept on purpose: some scripts need them. Latin never does.
3. **Invisible code points that are not format characters.** The dropped set is the Unicode 15 format (Cf) set. These
   are not in it and render as nothing, or as blank space: U+034F (combining grapheme joiner), U+115F and U+1160 (Hangul
   choseong and jungseong fillers), U+17B4 and U+17B5 (Khmer inherent vowels), U+180B to U+180D and U+180F (Mongolian free
   variation selectors), U+3164 (Hangul filler), U+FE00 to U+FE0F (variation selectors), U+FFA0 (halfwidth Hangul filler),
   U+E0100 to U+E01EF (variation selectors supplement), and U+2800 (Braille pattern blank).

### Who is affected

- **A studio exclusion** a parent wrote into a rules file (for example "never this studio on the kids channel") is
  evaded by a studio name with one of the code points above: the excluded titles air. This is the case that matters
  for safety.
- **A studio source** matches fewer titles than the person meant. Harmless, but surprising.
- **The kid hold** (`kid-hold-3`) fails closed for kid channels (a title whose kid studio does not match only loses that
  signal; genres and ratings still count), but the grown-up hold (ADR-0020) then does not move it off grown-up channels.

An attacker needs control of the server's metadata, or a scraper that brings such a name in. Neither is likely, but
studio names come from outside the household, and the exclusion is a promise to a parent, so the fold should not be
evadable by text that looks identical.

## Decision (proposed)

Add **studio fold 2**. It is studio fold 1 with three changes to the **Clean** step, all table-based (never a platform's
idea of whitespace, format or letters), applied to the name in the rules and to every studio name of an item or series
alike:

1. **Interior spaces are one space.** After the existing steps, every code point of the **space set** (U+0020, U+00A0,
   U+1680, U+2000 to U+200A, U+202F, U+205F, U+3000) inside the name is written as U+0020, and a run of U+0020 is written
   as one U+0020. (The ends are still trimmed as in fold 1.) So `Walt Disney` with a U+00A0, `Walt  Disney` and
   `Walt Disney` are the same name.
2. **Joiners only where a script needs them.** U+200C and U+200D are dropped when the code point written just before
   them is in U+0000 to U+024F (Basic Latin, Latin-1 Supplement, Latin Extended-A and -B), or when they are the first
   code point written. They are kept after any other code point, as in fold 1.
3. **A larger dropped set.** Add to the dropped set: U+034F, U+115F, U+1160, U+17B4, U+17B5, U+180B to U+180D, U+180F,
   U+2800, U+3164, U+FE00 to U+FE0F, U+FFA0 and U+E0100 to U+E01EF.

The **Fold** step is unchanged (ASCII `A` to `Z` only; no normalisation, no locale).

Studio fold 2 is idempotent, and fold 2 of a name that fold 1 left unchanged and that has none of the code points above
and no run of spaces is the same as fold 1.

### Versioning

Changing the fold changes which items air, so by this document's own rule it needs a new version:

- **Rules format:** `formatVersion` 4 uses studio fold 2 for `studio` sources, exclusions and the `studios` filter. A file
  that says 1 to 3 keeps studio fold 1 exactly, so every port still agrees on every existing file. A writer that wants
  the stricter matching writes 4. (Format 4 adds nothing else in this ADR.)
- **Holds:** `kid-hold-4` and the matching grown-up hold version read studio names through studio fold 2. Hold
  versions are named so a mismatch is visible (ADR-0019), so bumping the name is the documented way.

### Tests

A vector file `studio-fold-2.json` in `docs/rules-format/` with, at least: each interior space-set code point; a run of
two and of five spaces; ZWJ and ZWNJ inside a Latin word, at the start, after a non-Latin letter (kept), and after
U+024F and U+0250 (the boundary); each added dropped code point, alone and inside a name; a name that is empty after the
fold; idempotence; and fold-1 files (`formatVersion` 3) giving the fold-1 answer for all of the above.

## Consequences

- Every port (Kotlin, Vega OS, Roku) implements fold 2, its vectors, `formatVersion` 4 reading, and the hold version, in
  one release each, before any writer emits `formatVersion` 4.
- Older apps say "update required" for a format-4 file, which is the existing behaviour for a newer version.
- The cache keeps raw (cleaned by fold 1) studio names; fold 2 is applied at match time, so no cache migration is needed.

## Open questions for the owner

1. **Version or fix?** This ADR proposes `formatVersion` 4 so existing files keep their exact meaning. The alternative is
   to treat the gaps as a bug and change fold 1 for version 3 in place, which changes some existing lineups on update.
2. **Scope of rule 2 (joiners).** Dropping joiners only after Latin code points is the smallest change that closes the
   Latin look-alikes. Should other scripts where a joiner never changes meaning (Greek, Cyrillic) be added?
