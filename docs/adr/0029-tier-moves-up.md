# ADR-0029: Tier moves up: violence tags, Horror, a hand-kept bump list

- **Status:** Accepted
- **Date:** 2026-11-20
- **Decided by:** the owner (the tag set and the bump list, 2026-11-19 and 2026-11-20; Horror out of family,
  2026-11-20) and Rincewind (the mechanism)
- **Amends:** [ADR-0028](0028-rating-tiers.md) (`kid-hold-7`, `grown-up-hold-6`)
- **Informs:** [#988](https://github.com/StationBreak/stationbreaktv/issues/988),
  [#989](https://github.com/StationBreak/stationbreaktv/issues/989),
  [#990](https://github.com/StationBreak/stationbreaktv/issues/990),
  [#1001](https://github.com/StationBreak/stationbreaktv/issues/1001); [rules-format.md](../rules-format.md)

## Decision

All four are part of the one tier decision (`RatingTier.of`). Each only moves a title up (kids to family, family to
teen). None moves a title down, and an explicit tag, an adult genre or an adult rating still sends it to adult.

1. **Violence-heavy tags (#988).** `RatingTier.FAMILY_VETO_TAGS`: `mass murder`, `decapitation`, `child-death`. A title
   that would be family (level 10 or below, not kids) and that has one of them, on the item or its known series, is
   teen. They are matched like every other tag list: ASCII fold, exact. `murder` and `killer` alone do nothing. No
   spelling variants: none are known to be in use. The rule is not tied to a year: the engine has no production year,
   and the issue's test ("carries violence-heavy tags") is about the tag. `decapitation` was already in
   `GORE_TAGS`, which keeps a title out of kids and family; it is in the new list so the set reads whole.
2. **Horror (#1001).** `RatingTier.FAMILY_VETO_GENRES`: `horror`. A family title with the Horror genre (item or known
   series) is teen. Thriller and Mystery do not count. Horror was never an adult genre for kids and stays that way: a
   kid-rated title with made-for-kids evidence is kids whatever its genres or tags (rules 1 and 2 apply to family only).
3. **TV Tokyo (#989).** It is not on the kid-studio list (`KidHold.KID_STUDIOS`), and so was never kid evidence there:
   the "TV Tokyo" the issue saw is the curation's anime-studio table (`CurationTables.ANIME_STUDIOS`, which only
   names a format, never a tier). Nothing changes; a test pins that no spelling of TV Tokyo is a kid studio and that a
   title with it as its only evidence is not kids. A title such as Sonic X that reached kids did so through another
   entry (a co-producing kid studio such as 4Kids Entertainment) or through its genres.
4. **The bump-to-family list (#990).** `KidBumpList` in `core/engine`: a small Kotlin table of series, each with a
   one-line reason and provider ids (`tvdb`, `tmdb`, `imdb`, the keys the cache keeps). A known series with any id equal
   to an entry's is family instead of kids; its episodes follow it. The title is a comment only, never the match. The
   list never moves a title down, so a TV-MA series carrying a listed id stays adult. An entry needs a reason, an id and
   a test; the test fails on an entry without an id. First entry: The Ren & Stimpy Show (1991, Nickelodeon), TVDb 76088,
   TMDb 504, IMDb tt0101178 (checked against thetvdb.com, themoviedb.org and imdb.com on 2026-11-20). `RuleSeries`
   carries `providerIds` (read from the cache's series row) so the engine can match; items do not, so movies are not
   listed yet. Provider ids are versioned like the other fields the tier reads: `series_version` has a `providerIds` column (cache schema 13, `Migration12To13`), a change to an id the engine reads (`ProviderIdKeys.ENGINE`: imdb, tmdb, tvdb, the keys `KidBumpList` matches) writes a new version, and any other key (zap2it, tvrage, plugin ids) writes none; an answer with no engine ids for a series that has some keeps the old ids with no version (stale ids can only keep a listed series in family, the safe direction); such a version moves the content generation (the spec 26 rule that ids move nothing no longer holds for a series's engine ids), and `heldAsOf` reads the ids from the version that covers the cut-off, so a day rebuilt for one cut-off is the same on every TV. Version rows written before the migration hold no ids (not recoverable), so a cut-off older than the first id change after the update reads none. The bump list applies from the first cut-off after a TV's backfill has written the ids, not to the day in progress, and the one-off refresh the backfill causes may produce an identical day. "Every TV agrees" is therefore not guaranteed before each TV's backfill; it holds once the backfill (`sync_meta.backfillPending`, set by the spec 26 migration, which writes ids onto series rows) has run on each TV: before it, a TV without ids holds a listed series as kids and one with them as family for the same cut-off, and the backfill's first id write makes a version with the old (empty) ids, so cut-offs before it still read none on every TV. The audit-snapshot format gains an optional series `providerIds` (the Kotlin reader and writer; `export.py` does not write it yet). Not a rules-format field: the list is app-version logic like the kid-studio list.
5. **Versions.** `KidHold.VERSION_NAME` is `kid-hold-7` and `GrownUpHold.VERSION_NAME` is `grown-up-hold-6`: which
   channel a title airs on changed, every TV must agree, and `PersistedDays` keys its files by the version names, so
   every held day is remade once. The lists are frozen under `kid-hold-7`. `StarterCatalog.VERSION` is not bumped.
6. **Shared vector.** `docs/rules-format/vectors/kid-hold-7.json` is a delta of 'tier' rows. `kid-hold-6.json` is
   unchanged and `kid-hold-7` passes it, as `kid-hold-6` passes `kid-hold-5.json`.

## Consequences

- Family has fewer titles; teen has more. A kids title on the bump list is family.
- Phil's server counts in the issues (4 of 81 pre-1984 PG films, 18 family Horror titles) are not re-counted here: no
  server was contacted. The synthetic library (`RatingSafetyLeakTest`) pins the movement.
