# ADR-0019: The kid hold (spec 18, phase A)

- **Status:** Accepted
- **Date:** 2026-10-28
- **Decided by:** the owner (kids means made for kids, 2026-10-27; spec 18 approved 2026-10-28) and Rincewind (the mechanism, the lists and the table)
- **Amends:** [ADR-0002](0002-determinism-scope.md) §2
- **Amended by:** [ADR-0020](0020-grown-up-hold.md) (the grown-up side of §7: children's ratings)
- **Informs:** spec 18 phases A and B, F2.6 and F5.1 ([BACKLOG.md](../BACKLOG.md)), ASSUMPTIONS A-VER-1

## Context

The market promise is that parents can trust the kid channels. A dry run of catalog v1 against the owner's real
library broke it. About 185 groups of grown-up shows and films landed on kid channels. About 126 kids' shows landed
on grown-up channels.

The cause is that the v1 kid recipes are family recipes. Each is a theme genre plus a PG ceiling, and under the v1
rating ladder TV-PG sits on the same step as PG and pre-1968 "Approved" films sit on the lowest step. A theme genre
says what a show is about, not who it is made for.

The app already holds Kids-band and ever-kid-safe channels to PG at scheduling time, whatever their stored rules say
(`LineupDays.ceilingOf`). That hold was never written into an ADR. ADR-0002 §2 only lets *account* limits (Jellyfin
parental controls) narrow what the rules allow.

The owner decisions of 2026-10-27:

- Kids means made for kids, not "family friendly". Family-friendly titles leave kid channels and keep airing on
  grown-up channels.
- Two age tiers, Little Kids and Older Kids (phase B).
- When any library is parent-marked, kid channels draw only from parent-marked libraries (phase B).
- Family channels become their own category, to be tried (phase B).

## Decision

1. **The kid hold is app-version logic, not rules.** Every kid channel airs an item only when the item is made for
   kids and is rated within the kid ceiling, whatever the channel's stored rules say. Rule files and the rules format
   do not change.
2. **Where it lives.** The gate and the kid rating table are a pure function in `core/engine` (`KidHold`), with frozen
   test vectors. Its version name is `kid-hold-2` (`KidHold.VERSION_NAME`; `kid-hold-1` was the first, see §5). The
   name is used in this ADR and the test vectors only. Day caches are in memory and an app update restarts the process, so no stored cache is rebuilt.
   `LineupDays` only decides *whether* a channel is held.
   `PoolEvaluator.evaluate` keeps the contract in [rules-format.md](../rules-format.md).
3. **Which channels are held.** A channel is held when it is numbered in the Kids band, or its recipe key is in
   `StarterCatalog.EVER_KID_SAFE_KEYS` (whatever its number or edit state). That covers catalog, edited, custom and
   retired-recipe channels. The hold is decided from kid status, not from "has a ceiling", so a future family channel
   with a PG-13 ceiling is not held. A custom channel moved out of the Kids band stops being held. A catalog kid
   channel stays held wherever it is numbered.
4. **Which paths it covers.** Every pool a held channel gets:
   - ruled days;
   - the kids' leftovers pool (`Leftovers.pool` skips `evaluate`, so the hold sits inside it);
   - the withheld set for hidden and opt-in channels. A held channel withholds only what it would air if shown, so
     held-out titles stay available to the grown-up leftovers.

   The pool `EvaluationKey` gains a held flag, so a held and an unheld channel with the same rules never share a pool.
   `PoolCounts.heldOut` counts what the hold drops, and `HiddenReason.NothingMadeForKids` lets channel management (F2.4)
   say why an empty kid channel is empty.
5. **Made for kids.** An item passes the gate when it, or its series, has a kids genre. A theme genre alone is never
   enough. Tags never count: Jellyfin tags are TMDB keywords and are wrong about audience. Genres and studios use the
   same folding as genre sources. An episode matches on its own names or its series' names. If its series is unknown in
   the snapshot, it matches on its own names only, so the gate fails closed by matching less.
   - **Kids genres**, exactly these eight spellings (`KidHold.KIDS_GENRES`): Children, Children's (straight
     apostrophe), Children’s (curly apostrophe, U+2019), Childrens, Kids, Kid, Preschool, Pre-school.
     Family is left out on principle: decision 1 says family-friendly is not made for kids, and that holds for any
     library.
   - **Kid-brand studios:** matched against a list admitted by one written rule. A studio is on the list if it is "a
     children's network, channel block or label whose published output is made for children; a studio with grown-up
     or general-family output (a big film studio's main label, for example) is left off." Studios come from the
     library cache; an item or series the cache holds no studios for matches on genres only, which makes kid channels
     thinner, never less safe.
   - A library may confirm these lists but never decides them.
   - **`kid-hold-2` (2026-10-28).** Kid channels were too thin under `kid-hold-1`, so the gate widens in two ways.
     - A children's rating counts as made for kids: `TV-Y`, `TV-Y7` or `TV-Y7-FV`, in any server spelling, on the item
       or on its known series. The TV Parental Guidelines define these as children's programming, so this follows
       decision 1 (kids means made for kids). An unknown series contributes nothing. `TV-G` and `G` do not count.
     - The major animation studios join the studio list (owner decision), except Studio Ghibli, which is left off on
       purpose, as is Nelvana, which also co-produces general-audience work. A big film studio's live-action label
       stays off.

     The kids genres are unchanged, so catalog version 2 is unchanged.
   - **Studio names are cleaned, and the name stays `kid-hold-2` (2026-10-28).** The sync strips controls, format
     characters (except ZWJ and ZWNJ) and lone surrogates from a studio name before it is cached, turns tab and line
     breaks into a space, and trims; the hold reads names through the same cleaning (`cleanStudioName`), so a cache
     written before that matches the same way. The lists and the rating table are unchanged, so the version name is
     too: it changes with the lists or the table, not with how input text is tidied. The only names whose match
     changes are ones that carried stray invisible characters and so failed to match before.
6. **The kid rating table.** A fixed table in the hold, not a change to `RatingLadder`:

   | Tier | Ratings |
   | --- | --- |
   | 0 | TV-Y, TV-G, G |
   | 1 | TV-Y7, TV-Y7-FV |
   | 2 | PG |
   | 3 | TV-PG |
   | 4 | every other name above these on the ladder |

   "Approved" and any name off the ladder count as unrated, and unrated stays off kid channels. The ceiling is still
   capped at PG, so a PG ceiling admits PG films but not TV-PG shows. The check applies to an episode's effective
   rating and to its series' rating. An episode whose series is unknown fails closed.
7. **Catalog version 2.** Every recipe outside the Kids band excludes the same eight kids genre spellings as the gate
   (§5, built from `KidHold.KIDS_GENRES`, so a grown-up channel drops exactly what a kid channel would take), so
   preschool shows stop appearing on grown-up channels. Children's *ratings* are not a genre: see
   [ADR-0020](0020-grown-up-hold.md). A genre exclusion is checked against the episode's series genres, and rules-format
   rule 3 fails it closed when the series is missing from the snapshot: grown-up starters now also drop such episodes, which fall to Odds & Ends. These are rules-format v1 features, so the rule files stay at
   version 1. Kid recipes keep their theme sources, tags included, because the hold does the audience check and the
   themed channels do not go empty. Keys, numbers and `EVER_KID_SAFE_KEYS` do not change. A finalised lineup whose
   collection pass keeps failing also takes the catalog bump, after `GIVE_UP_AFTER` (2) failed passes, so the grown-up
   exclusions reach every household. Kid safety does not depend on that replan.
8. **Amendment to ADR-0002 §2.** App-version limits may narrow what the rules allow, and never widen it. A kid
   channel's lineup is a function of rules, snapshot, seed, time **and app version**. ADR-0002 carries a dated note
   pointing here; [ADR-0020](0020-grown-up-hold.md) widens it to grown-up starters.
9. **Accepted assumption.** Every TV on an account runs the same app version. There is no `formatVersion` refusal to
   protect a TV on an older app, because the rules did not change. During an update, two TVs on different versions can
   disagree about a kid channel. Recorded as A-VER-1 in [ASSUMPTIONS.md](../ASSUMPTIONS.md).

## Consequences

- Kid channels get thinner, and that is the intended failure. A thinly tagged library cannot make them unsafe.
- Family-friendly titles, TV-PG shows and pre-1968 "Approved" films leave kid channels. They keep airing on grown-up
  channels, except where a grown-up channel now excludes a kids genre.
- Kids' shows the hold refuses (unrated, TV-PG) mostly reach the grown-up leftovers (Odds & Ends), not grown-up
  channels in general, because grown-up starters exclude the kids genres. Spec 18 measures that and sets a target for
  it.
- Grown-up starters also drop episodes of a series the snapshot lacks, when the exclusion cannot be checked against
  the series genres (rules-format rule 3 fails closed). Those episodes fall to Odds & Ends too.
- ADR-0002's "same account, same lineup" now reads "same account, same lineup, same app version" for kid channels.
- **Phase B needs no rules-format change, but it is not free of format change.**
  - Parent-marked library trust must be an account-scoped, versioned input (ADR-0002 §1 and its addendum) so two TVs
    on one account agree. Today the lineup is device-local (`LineupCodec`). Trust marks and the family category
    therefore need a `LineupCodec` v2 or a new account document.
  - The two tiers need no new field. They follow from the stored ceiling: G is Little Kids, PG is Older Kids.
  - Family channels keep the ordinary rules ladder, where "Approved" stays on the lowest step. A PG-13 family channel
    can still air classic films.
- **Constraint for F5.1 (channel sharing):** a channel is a kid channel today because of its local number or recipe
  key. Neither travels in a portable file, so a shared kid channel must carry its kid status, or it would air unheld
  on the receiving TV.
- Studios are fetched, stored and migrated in a later PR of this spec. That PR has its own cache migration and sync
  re-read, and is not decided here.

## Alternatives considered

**Rules format version 2** (draft 1 of spec 18). The gate would live in the rules: a `requireAny` field, a `studio`
source and a per-version rating ladder. Rejected, after architecture and data guardian reviews, because:

- Kids' Odds & Ends is a leftovers pool, not a ruled channel, so it stays uncovered.
- Edited, custom and retired kid channels keep their old, leaky rules.
- The fix is tied to a catalog replan. A household whose collection pass keeps failing never gets it.
- Phase B's library trust would likely force a version 3 soon after.
- It changes every v1 rule file and the format doc, for a limit the app already enforces at scheduling time.

**Keep the hold as it was (PG ceiling only).** Rejected: it is the cause of the problem. TV-PG, "Approved" and
family-genre titles all pass a PG ceiling.

**Admit Family as a kids genre.** Rejected on principle (owner decision 1), not by measurement.

## Addendum (2026-11-03, spec 23): `kid-hold-3`

- **Amended by:** this addendum; the decision is the owner's (spec 23 design, R9).

**The gap.** `kid-hold-2` counts a title as made for kids by a kids genre, a kid studio or a children's rating. A
classic animated family film, rated G or TV-G and tagged Animation and Family, has none of the three unless the
library happens to name a listed studio. It aired on no kid channel, and on grown-up channels it competed with
grown-up films. Family alone stays rejected (decision 1 above): a live-action family film is not made for kids.

**The portable signal.** `KidHold.isAnimatedFamily(entry, series)`: the genres of the item and of its known series
together include Animation and Family, and the rating is G-like or unrated. The rating is the effective one (the
item's own, or the series' when the own is null or blank), and a known series with a rating must pass too, as
`passesKidCeiling` reads an episode. It passes when it is null or blank, or after `RatingLadder.normalise` is `G`,
`TV-G`, `TV-Y`, `TV-Y7`, `TV-Y7-FV` or `Approved`, or (ASCII upper-cased, trimmed) is `NR` or `NOT RATED`. It uses
only genres and ratings, which every server has; tags, library names and folder layout never take part.
`isMadeForKids` is the `kid-hold-2` test or this signal. `isSeriesMadeForKids(series)` applies the same lists to a
series alone, for the curation's tone; it is not a hold of its own. The lists and `passesKidCeiling` are unchanged.

**Both directions.** On the kid side, the signal admits these titles to kid channels and to Kids' Odds & Ends (this
change). On the grown-up side, `grown-up-hold-2` makes grown-up channels drop everything this predicate calls made for
kids, so a title is on a kid channel or a grown-up one, not both ([ADR-0020](0020-grown-up-hold.md) addendum). Until
that lands the grown-up hold is as it was, which is no safety loss: a kid channel only ever gains titles that are made
for kids by the new rule.

**The cost.**

- An unrated animated family title is made for kids but fails every ceiling (a title needs a kid tier, and unrated has
  none), so it airs on no kid channel. It stays off kid channels until a rating is set. The signal still counts it for
  the curation's tone and for the grown-up side.
- A title rated PG or above, or off the ladder, is not admitted. A PG animated family film does not become made for
  kids.
- Each item costs two scans of its genre list (and its series') and no allocation.
- Every day made under `kid-hold-2` is remade: `PersistedDays` keys its files by `VERSION_NAME`, so the persisted days
  of kid channels, and of the grown-up channels that share the hold names, are rebuilt once. Like spec 18's holds this
  applies at once, not at the next day boundary, and a programme on now can be cut once at the update. This is
  accepted for kid safety.

**The version.** `KidHold.VERSION_NAME` is `kid-hold-3`. The studio, genre and rating lists are the `kid-hold-2` ones,
frozen under the new name; the manifest tests hold them. Mixed app versions can disagree on a kid channel during an
update (A-VER-1).

