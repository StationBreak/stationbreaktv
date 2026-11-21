# ADR-0028: Audience tiers, fail upward, and the US-first rating map

- **Status:** Accepted
- **Date:** 2026-11-20
- **Decided by:** the owner (four exclusive tiers, fail upward, unrated is adult, 2026-11-19; family ceiling PG / TV-PG
  and teen PG-13 / TV-14, 2026-11-20; a StationBreak-owned rating map, 2026-11-19) and Rincewind (the mechanism, the
  lists, the US-first scope after the licence check)
- **Amended by:** [ADR-0029](0029-tier-moves-up.md) (`kid-hold-7`: moves up only, lists)
- **Amends:** [ADR-0019](0019-kid-hold.md) (the kid hold: `kid-hold-6`), [ADR-0020](0020-grown-up-hold.md) (the grown-up
  hold: `grown-up-hold-5`)
- **Builds on:** [ADR-0002](0002-determinism-scope.md) §2 as amended by ADR-0019 §8 (app-version limits only narrow)
- **Informs:** [#576](https://github.com/StationBreak/stationbreaktv/issues/576),
  [#987](https://github.com/StationBreak/stationbreaktv/issues/987),
  [#992](https://github.com/StationBreak/stationbreaktv/issues/992); [rules-format.md](../rules-format.md)

## Context

`kid-hold-5` puts a title on kid channels when it has made-for-kids evidence and a rating up to PG. A children's rating
(`TV-Y`, `TV-Y7`) counted as evidence on its own. Grown-up channels drop what a kid channel could take (ADR-0020). That
left a large watch-together shelf with no home of its own, and a G-rated grown-up title could reach a kid channel on a
weak signal.

The rating ladder knows only US names. Every non-US rating already fails closed: that was the v1 decision in #104
(2026-10-06), and it stays. #987 asked for a StationBreak-owned map seeded from MovieLabs `cmr-ratings`, if its licence
allows.

## Decision

1. **Four exclusive tiers.** Every title is in exactly one of kids, family, teen and adult (`RatingTier.of` in
   `core/engine`). A rejection only moves a title up a band, never down:
   - **Kids:** a kid rating (level ALL or 7: `G`, `TV-G`, `TV-Y`, `TV-Y7`, `TV-Y7-FV`, a bare age up to 7) and
     made-for-kids evidence (`KidHold.isMadeForKids`): a kids genre or kid studio on the item or its series (the
     `kid-hold-4` lists, unchanged), the animated-family signal (unchanged), or a kids tag (the kids-genre spellings as
     tags) next to the kid rating. G alone is not enough. A TMDb "kids" keyword never counts alone. A children's rating
     is no longer evidence on its own: the "TV-Y alone" tweak in #576 was not approved, so a TV-Y show with no other
     evidence is family. Studio Ghibli stays off the kid-studio list.
   - **Family:** level up to 10 (`PG`, `TV-PG`, `GP`, and kid ratings without evidence), not kids.
   - **Teen:** levels 13 and 14 (`PG-13`, `TV-14`, a bare 11 to 14).
   - **Adult:** everything else: R and up, every unknown or unrated rating, an episode whose series is missing from the
     snapshot.
2. **Vetoes.** An explicit tag (`RatingTier.EXPLICIT_TAGS`: nudity, sex, hentai, ecchi, rape, incest, harem and
   similar) or an adult genre (`RatingTier.ADULT_GENRES`) on the item or its series sends any title to adult. A gore tag
   (`RatingTier.GORE_TAGS`) keeps a title out of kids and family, so a gory PG title is teen. Tags and genres are folded
   to ASCII lower case and matched exactly, so every port reads them the same way. A false positive only moves a title
   up.
3. **Unrated is adult.** No rating, a blank one, `NR`, `Not Rated`, `Unrated`, `Approved`, `Passed` and every string the
   map does not know are adult under every tier and every kid ceiling. The "trust this library" opt-in of #754 is
   dropped: nothing lets an unrated title into kids or family. The same holds for every other ceiling the app applies
   (fix round 1, 2026-11-20): a custom channel outside the Kids band (`PoolPolicy.Custom`) and a grown-up channel read
   `Approved` as unrated, so it fails a G, PG-13 or any other ceiling, as `NR`, `Passed` and no rating already did
   under rule 4. The test is on the item's effective rating and its known series' rating, as rule 4 reads them.
4. **The decisive rating** is the `kid-hold-5` one for every tier: a movie's own rating; for an episode of a known series,
   the series' rating when present, otherwise the episode's own (`EpisodeRating.decisive`). Its accepted risk (a TV-MA
   episode of a TV-Y series) is unchanged.
5. **Who reads it.** A kid channel (`PoolPolicy.KidsOnly`) airs the kids tier only, under its ceiling capped at TV-Y7:
   a kids channel with a PG ceiling airs what TV-Y7 airs. The builder still offers G, TV-Y7 and PG; which choices to
   show is a UX call left to the owner. A grown-up channel (`PoolPolicy.GrownUp`) drops exactly the kids tier
   (`GrownUpHold.dropsFromGrownUp`), so family, teen and adult titles keep airing on grown-up channels until family and
   teen channels exist. Nothing airs nowhere because of the holds.
6. **The rating map is US-first.** `RatingMap.levelOf` (`core/engine`) maps a rating string to the shared ladder ALL, 7,
   10, 13, 14, 16, 18, X (the same indices as the rules ladder's tiers) or to unknown, in the parse order of #987: unrated
   spellings; clean (trim U+0020, ASCII upper case, en dash to hyphen, drop `Rated `); the US names whole, first (so
   `TV-14` never reads as Tuvalu); a country prefix; a `/` list at its highest level (unknown if any part is); a bare
   minimum age rounded up; anything else unknown. The owner-defined entries are in: `GP` = 10, `PG13` = PG-13, `XXX` =
   X, and the ambiguous letters at their strictest (`C` and `M` are 16, `A` is 18). Every other country's names (`U`,
   `12A`, `FSK 16`, `MA15+`, `AU PG`, country-prefixed strings such as `DE-12` or `US:PG-13`) and the mock-library
   strings `U/A 13+`, `B-15`, `B`, `10AP` and `T` stay unknown, so adult.
7. **The licence finding.** On 2026-11-20 the MovieLabs `cmr-ratings` repository had no LICENSE file, no README and no
   licence header in its XML. Per #987's stop rule, nothing is copied, transcribed or derived from it, nor from the
   Android TV ratings XML, Jellyfin, MediaPortal or TMDb. The country-prefix step is kept with an empty table registry,
   so a licensed table can be added later; the AU PG override (#987) waits for that table. A test pins AU PG as unknown
   today.
8. **Rule 4 changes under `kid-hold-6`, not under a new `formatVersion`.** `kid-hold-6` is an app version, like
   `kid-hold-5`; no rule file changes and `formatVersion` stays at 5. The rules-format reference evaluation
   (`PoolPolicy.Open`, which `rating.json` and the pool vectors hold, and which older apps and ports run) keeps rule 4
   as it was, with `Approved` on rank 0. Every channel the app builds under `kid-hold-6` narrows it (ADR-0019 §8:
   app-version limits only narrow): `Approved` has no rank under a ceiling. [rules-format.md](../rules-format.md) says
   so next to the ladder, and that non-US systems are unknown until licensed tables land. A `formatVersion` 6 was not
   used: the file format did not change, and a bump would force a schema, a write rule and a catalog version for a
   narrowing the app already applies at scheduling time. `RatingLadder` names stay stable, so the typed ceiling of #380
   can land on top later.
9. **Version names.** `KidHold.VERSION_NAME` is `kid-hold-6` and `GrownUpHold.VERSION_NAME` is `grown-up-hold-5`. The tier
   lists, the rating map and its aliases are frozen under `kid-hold-6`. `StarterCatalog.VERSION` is not bumped: the
   recipes do not change (the kid recipes keep their PG ceilings, which the hold caps).
10. **Shared vector.** `docs/rules-format/vectors/kid-hold-6.json` holds the level of each rating string, the tier of each
    item shape and the kid ceiling. Like `kid-hold-5.json` it was written by hand from this ADR, not computed with an
    independent reference, and a test in `core/engine` holds the code to the published file. Every port ships the rule
    as `kid-hold-6` and must also pass `kid-hold-5.json` (unchanged answers) and the `kidHold4` column of
    `kid-hold-4.json`. That file's `grownUpHold3Drops` column holds under `kid-hold-6` only for rows with a kid rating;
    a made-for-kids row rated PG or TV-PG is family now and is kept by a grown-up channel. This amends ADR-0026's release
    gate for a `kid-hold-6` port.

## Relationship to earlier decisions

- **ADR-0019** (the kid hold) stays the mechanism: app-version logic, which channels are held, the paths covered, the
  lists. `kid-hold-6` replaces its rating table (§6 and the `kid-hold-5` cap at PG) with the tiers, and drops the
  children's-rating evidence that `kid-hold-2` added.
- **ADR-0020** (the grown-up hold) keeps its policy table and the leftovers fallback. `grown-up-hold-5` drops the kids
  tier instead of "made for kids and within PG".
- **#104** (US TV and MPAA only; unrated and unsupported systems blocked under a ceiling) is kept for unknown and
  unrated. #987 widens what counts as supported, by the US spellings and bare ages above for now.
- **Retired kid recipes** (#1044) keep the kids-only policy: a stored copy that migrates to a Custom channel is still
  a kid channel by the key in its `starter:` or `leftovers:` ID (ADR-0030 rule 2), so rule 5 applies to it at any number.
- **#576** replaces the earlier proposal of a PG-13 family ceiling with unrated titles allowed.

## Consequences

- Kid channels get thinner: PG and TV-PG titles, and children's-rated titles with no other evidence, move to family.
  Owner figures before the map (#576): of 344 titles the old hold admitted, 229 stay kids and 115 move to family.
- Family and teen channels are not built here: the bands land in
  [#1002](https://github.com/StationBreak/stationbreaktv/issues/1002) (Kids 1-33, Family 34-66, Teen 67-99), with the
  `Band`, `Recipe` and `LeftoversRecipe` changes and the family and teen pool policies. Until then family and teen
  titles air on grown-up channels.
- A custom channel outside the Kids band is `PoolPolicy.Custom`, no longer `Open` (this amends ADR-0020's policy table):
  what the viewer chose airs, except `Approved` under a ceiling. The grown-up leftovers' fallback while no kid leftovers
  channel is on (ADR-0020) is `PoolPolicy.Custom` too, no longer `Open` (fix round 2): it lifts the grown-up hold only,
  and still reads `Approved` as unrated under its ceiling. `Open` is used for no channel; it is the rules-format
  reference. The policy is part of each channel's day key, so those channels' days rebuild once.
- A grown-up title rated G and tagged with a kids keyword reaches kids. That is the rule as approved (it is how a G-rated
  Totoro with a kids tag reaches kids).
- Every day made under `kid-hold-5` or earlier is remade once: `PersistedDays` keys its files by the hold version names.
  As with earlier hold versions this applies at once, and a programme on now can be cut once at the update. Mixed app
  versions can disagree on held channels during an update (A-VER-1).
- Tests: `RatingSafetyLeakTest` checks the #992 never-in-kids fixtures, their metadata-stripped copies and a synthetic
  library of about 25,000 titles, and pins its tier counts.

## Alternatives considered

**Copy the MovieLabs tables anyway.** Rejected: there is no licence to copy under, and #987 says to stop and record
the finding.

**Route the rules ladder through the map** (as #987 first suggested). Rejected for now: it would change rule 4 for open
channels and the shared `rating.json` and pool vectors for every port. The tiers are app-version logic, like the kid
hold.

**Count TV-Y alone as kid evidence.** Not approved by the owner (#576).
