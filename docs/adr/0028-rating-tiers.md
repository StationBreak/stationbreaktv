# ADR-0028: Audience tiers, fail upward, and the US-first rating map

- **Status:** Accepted
- **Date:** 2026-11-20
- **Decided by:** the owner (four exclusive tiers, fail upward, unrated is adult, 2026-11-19; family ceiling PG / TV-PG
  and teen PG-13 / TV-14, 2026-11-20; a StationBreak-owned rating map, 2026-11-19) and Rincewind (the mechanism, the
  lists, the US-first scope after the licence check)
- **Amended by:** [ADR-0029](0029-tier-moves-up.md) (`kid-hold-7`: moves up only, lists); the addendum of 2026-11-22
  (`kid-hold-8`: the Explicit tier, tiers on the young bands, #1078; `kid-hold-9`: input-noise tidy, #1088)
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

The rating ladder knows only US names. Every non-US rating failed closed: that was the v1 decision in #104
(2026-10-06). #987 asked for a StationBreak-owned map seeded from MovieLabs `cmr-ratings`, if its licence allowed; it
did not, and the owner chose the Apache-2.0 Android TV ratings XML instead (rules 6 and 7, and the 2026-11-23 addendum
(4)).

## Decision

1. **Four exclusive tiers.** Every title is in exactly one of kids, family, teen and adult (`RatingTier.of` in
   `core/engine`). A rejection only moves a title up a band, never down:
   - **Kids:** a kid rating (level ALL or 7: `G`, `TV-G`, `TV-Y`, `TV-Y7`, `TV-Y7-FV`, and from `kid-hold-10` a
     country-prefixed rating at that level such as `DE-6` or `KR-ALL`; a bare age is never a kid rating, rule 6) and
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
6. **The rating map.** `RatingMap.levelOf` (`core/engine`) maps a rating string to the shared ladder ALL, 7, 10, 13, 14,
   16, 18, X (the same indices as the rules ladder's tiers) or to unknown. Since `kid-hold-14` an age of 17 is level 6, not 5 (addendum (9)). The map was US-first under `kid-hold-6`; from
   `kid-hold-10` (#987) it also reads non-US systems (rule 7). The parse order: unrated spellings; clean (trim U+0020,
   ASCII upper case, en dash to hyphen, drop `Rated `); the US names whole, first (so `TV-14` never reads as Tuvalu);
   a spelling that is neither a bare number nor a single letter, read without a prefix at the highest level any
   country gives it (`SAM 13`, `C8`); a country prefix (`-`, `:` or `/`); a `/` list at its highest
   level (unknown if any part is); a bare minimum age rounded up, **capped at Family** (level 2 at least: a bare age
   names no system, so it is never kids, #1006); anything else unknown. The owner-defined entries are in: `GP` = 10,
   `PG13` = PG-13, `XXX` = X, and the ambiguous letters at their strictest (`C` and `M` are 16, `A` is 18). The
   mock-library strings `U/A 13+`, `B-15`, `B`, `10AP` and `T` stay unknown, so adult. Rules for every non-US entry:
   - **Fail upward.** A rating's minimum age (its `contentAgeHint`) is rounded up to the next rung, never down.
   - **Overrides.** AU `PG` is 10 (owner decision; the XML's 15 would make it adult). It is the only entry below its
     age. Where the XML says 0 but the name says otherwise, the entry fails up to the US reading of the same name: NZ
     `PGR` and SG `PG` are 10, NZ `AO` and ES `X` / `X-Rated` are X (Explicit). Canada's `E` / `Exempt` (news, sports,
     talk) is not a classification, so it is unknown like `NR`. SG `R21` and AU `R` were 18 under `kid-hold-10` and are Explicit since `kid-hold-11` (addendum 5).
   - **Country prefix.** Only a code the XML lists for a system, followed by `-`, `:` or `/` (`DE-12`, `de:12`,
     `KR/15`). A space is not a separator (`AU PG` is unknown). A single letter or a bare number needs a prefix: a
     letter means different things in each country, and a bare number is a bare age. Two systems in one country take
     the higher level for a shared spelling.
   - **Superseded by `kid-hold-13` (addendum 2026-11-24 (7)):** the board ratings of the UK, Germany, Japan and
     Australia now map. Before it, the following stayed unknown, so adult, because the XML does not define it: UK BBFC (`U`, `12A`, `15`, `GB-15`); Germany's
     FSK spellings (`FSK 16`, `DE-FSK 12`; only the DVB numbers such as `DE-12` map); Japan's Eirin (`JP-PG12`,
     `JP-R15+`); Australia's film ratings (`MA15+`, `MA 15+`); a US-prefixed name (`US-PG`); a country written as a
     name (`Germany: FSK 18`); Canada's Exempt; and a single letter with no country. No other source fills these.
   - **One prefix rule for the level and the explicit check.** `RatingMap.isExplicit` takes a two-letter prefix off
     with the same rule (`-`, `:` or `/`, spaces allowed around it), so `GB/R18`, `DE/X18+` and `GB - R18` are
     Explicit; a `/` list such as `TV-14/TV-MA` is read as a list, as before (fail up).
   - **Who reads the level (`kid-hold-11`).** The map decides a title's tier (`RatingTier.of`), a kid channel's ceiling
     (`KidHold.passesKidCeiling`, which a kid channel uses in place of the ceiling check) and, since `kid-hold-11` /
     `grown-up-hold-11` (#1124, addendum 5), the ceiling of every grown-up or custom channel (`passesCeiling`: a
     Kids-band PG, a Teens-band TV-14, a custom channel's maximum rating). Under `kid-hold-10` those last read the US
     ladder alone, so a `DE-12` title was teen tier yet aired on no ceilinged channel; that is no longer so. A rating with
     no level still fails every ceiling. The rules-format reference policy (`Open`) still reads the US ladder.
7. **The source and the licence.** The non-US table is transcribed from the Android TV ratings XML: AOSP
   `platform/packages/apps/TV`, `ratings/res/xml/tv_content_rating_systems.xml` (with its string resources), at commit
   `4e256ebdeb1333af4f2b9832d61078965e4a672f`. Both files are Apache-2.0 ("Copyright (C) 2015 The Android Open Source
   Project"). The attribution is in the root `NOTICE`, the README and the header of `AndroidTvRatingSystems.kt`; as with
   Media3 (ADR-0003) it is in the repository, not the APK. The table is data in `core/engine`
   (`AndroidTvRatingSystems`, `CountryRatingTables`); the two US systems and the sub-ratings (D, L, S, V) are left out.
   The earlier finding stands for the other sources: on 2026-11-20 MovieLabs `cmr-ratings` had no licence, so nothing is
   copied from it; nothing is copied or derived from Jellyfin, MediaPortal or TMDb either. **This supersedes the #104
   US-only v1 decision** for supported ratings; #104 stays closed and is not reopened. #104's fail-closed part is kept:
   an unknown or unrated rating is adult under every tier and ceiling (rule 3).
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
  unrated. #987 widens what counts as supported: the US spellings, bare ages and, from `kid-hold-10`, the systems of the
  Android TV ratings XML (rule 7). That supersedes #104's US-only scope; #104 is not reopened.
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

## Addendum 2026-11-22: the Explicit tier, and tiers on the young bands (#1078)

Explicit titles reached regular channels: no pool outside the Kids band read the tier, a grown-up channel with no
ceiling took everything but the kids tier, and Odds & Ends 199 took any explicit title no other channel claimed. The
Teen and Family bands checked only the ladder, so a TV-14 series tagged ecchi and nudity aired on a Teen channel. And
After Hours aired nothing: its sources had no hentai signal, and the curation's anime rule dropped every anime title
from it. The owner's direction: fail up. A gate that is too rigid beats one that is too loose.

`kid-hold-8` / `grown-up-hold-7` and catalog 14:

1. **A fifth tier, Explicit, above Adult** (Kids < Family < Teen < Adult < Explicit). A title is Explicit when its own
   rating or its known series' rating is an Explicit rating, or a genre or tag of it or its series is an Explicit one,
   folded and matched exactly, anime or not:
   - ratings: level 7 (now also `X`, `AO`, `Adults Only`), `NC-17` / `NC17` (which keeps level 6 on the ladder), and
     GB and NZ `R18`, JP `R18+` / `R-18`, AU `X18+` / `R18+`, KR KMRB Restricted Screening and AniDB's `18 Restricted`,
     with or without a two-letter country prefix (`RatingMap.EXPLICIT`). AU `R18+` and NZ `R18` also cover mainstream
     films; under fail-up they count. #987 is still the wider ladder.
   - genres: hentai, adult, erotica, erotic, porn, pornography, sexploitation (`RatingTier.EXPLICIT_TIER_GENRES`).
   - tags: the TMDb keywords of explicit material, verified on themoviedb.org on 2026-11-22, and the spellings other
     scrapers use (`RatingTier.EXPLICIT_TIER_TAGS`). The plot keywords `porn industry` and `porn star` are not triggers.
2. **Adult gains three tags**: `sex comedy`, `teen sex comedy` and `erotic thriller`. `ecchi`, `nudity`, `harem`, `sex`,
   the `erotic` tag, `erotic thriller`, TV-MA, R and unrated stay Adult, not Explicit.
3. **The grown-up hold drops Explicit as well as the kids tier**, on every grown-up starter and every leftovers pool,
   Odds & Ends 199 included, also while no kids leftovers channel is on. Custom channels keep no hidden filters.
4. **A grown-up leftovers pool drops a title with no metadata** (no rating, genre or tag on it or its series). It is
   Adult, not Explicit, and stays eligible wherever a source matches it.
5. **Young bands follow tiers, not only ceilings**, as #576 specified: a grown-up pool under a ceiling up to PG (the
   Family band, Odds & Ends 66) admits the family tier only; up to TV-14 (the Teen band, Odds & Ends 99) family and
   teen. The tier limit is read from the ceiling, so a starter edited to a young ceiling follows it too. Kids is
   unchanged.
6. **After Hours is `PoolPolicy.ExplicitOnly`**: every Explicit title the account can see, as if a source matched it,
   plus what its own sources match. It is exempt from the curation's anime rule (the curation and a late switch-on), so
   an Explicit title airs there and nowhere else, and with After Hours off it airs nowhere. A copy of After Hours is a
   custom channel with the adult mark (#984) and keeps its sources only.
7. **Catalog 14** gives After Hours the genres Hentai, Porn, Pornography and Sexploitation and the tags hentai,
   pornography, porn, softcore, sexploitation and erotic movie, so a copy keeps most of the reach.

Owner calls, resolved 2026-11-22: NC-17 is Explicit; the `hentai` tag alone counts, on any title; the `erotic` tag,
`sex`, `ecchi`, nudity and harem stay Adult; no-metadata titles leave Odds & Ends 199 and are not Explicit; which
libraries an account sees is the user's responsibility (the engine reads no library names); and the triggers cover
real adult films and older sexploitation, not only anime signals.

Accepted costs and limits: a mainstream film with an Explicit keyword or an NC-17 rating leaves the regular channels
for After Hours. A file with no metadata at all is Adult, not Explicit: it stays off the kids, family and teen channels
and out of Odds & Ends 199, but a grown-up channel whose sources match it (a library or collection anchor) can still air
it. The engine can't tell it from a test clip; the account's library access on the server is the real lock. An
episode's own Explicit rating fails up past its series' rating, unlike the kid hold's decisive rating.

Fix round 1 (security review, 2026-11-22):
- The young-band limit also follows the number: a built-in channel (starter or leftovers) numbered 34 to 66 is
  scheduled under at most PG, and 67 to 99 at most TV-14 (`Ceilings`, alongside the existing Kids-band PG cap), so
  the tier limit of rule 5 holds wherever a built-in is moved. Custom channels keep their own ceiling. After Hours
  can't be numbered below 100 (`LineupEdits`, by a number or a swap, refused as `OutOfRange`), and a stored one below
  100 is evaluated as grown-up, not `ExplicitOnly`. Since #1097 no channel may be numbered below its band's floor
  (`ChannelFloors`, refused as `BelowBand`), and a built-in stored below it goes back to its default number on the next
  refresh (`FloorMigration`), so the cap above no longer applies to it and it airs as its recipe says.
- A combined rating (split on `/`) is Explicit when any part is (`R/NC-17`, `GB-R18/AU-X18+`).
- An episode whose series ID is set but whose series the snapshot lacks is dropped from every grown-up leftovers pool
  and from grown-up pools with no ceiling (its series could be Explicit); under a ceiling rule 4 already blocks it.

Every held day is remade once under the new hold names, and a lineup moves to catalog 14 as any catalog bump does.
Vectors: `kid-hold-8.json`; `kid-hold-6.json` and `kid-hold-7.json` are unchanged, and a `kid-hold-8` port reads
their `adult` as adult or explicit and their `X` row at level 7. Tests: `ExplicitTierTest`, `ExplicitPoolTest` and the
SYNTHETIC adult slice (`SyntheticAdultSlice`) in `ratingsafety`.

## Addendum 2026-11-22 (2): input-noise tidy, `kid-hold-9` / `grown-up-hold-8` (#1088)

A rating, genre or tag with a no-break space, a tab, a doubled space or a dash variant (en or em dash, minus sign,
non-breaking hyphen) hid a tier signal. `NoiseTidy` removes that noise in one place:

- Ratings (`RatingMap.levelOf`, the clean step): runs of spaces and dash variants only. A tab or no-break space stays
  present and off the ladder, as the frozen `kid-hold-5` vectors pin it. A still-unknown key with a space and a letter in
  it is read once more with its spaces taken out (`PG - 13`); `0 7` and `1 0` stay unknown.
- `RatingMap.isExplicit` reads every white space as a space too, so `"\tNC-17"` and `"R18 "` (tab or NBSP) are Explicit.
  Fail up; it supersedes the `"\tX"` row of `kid-hold-8.json`.
- Genres and tags are tidied of every white space and dash variant before the tier lists match them (`RatingTier`).
  Only the tier lists: the kid hold's own genre matching and user sources are unchanged.
- `GrownUpHold.hasNoMetadata` counts a blank rating, genre or tag (empty or white space only) as none.

Inconsistency kept on purpose: `GrownUpHold.blank` reads a tab- or NBSP-only rating as absent, while
`EpisodeRating.isAbsent` and `RatingMap.levelOf` read it as present. The direction is safe (a leftovers pool only drops
more). A later change to make them consistent must not let a blank series rating open the episode-inherits-series path
of `kid-hold-5`: there only a U+0020-only series rating is absent and defers to the episode's own rating.

Also recorded: an edited retired starter keeps its `starter:` ID and so is a built-in for the band cap (`ChannelCeilings`);
the cap follows the ID, not the source type.

Vectors: `kid-hold-9.json` (a delta; the older files are unedited). Every held day is remade once under the new hold names.

## Addendum 2026-11-23 (3): a custom channel under a young ceiling, `grown-up-hold-9` (#1108)

Since #1097 the floor of a viewer's own channel follows its ceiling (`ChannelFloors`): PG or less is numbered 34 or up,
TV-14 or less 67 or up, anything else 100 or up. The channel was then in a band whose built-in channels air family (or
family and teen) titles only, but `PoolPolicy.Custom` still let through what rule 5's tier limit keeps off those bands:
a PG-rated title with the Horror genre or a gore or violence tag, or with an explicit or adult tag (Teen, Adult or
Explicit by tier). The ceiling, and `Approved` as unrated under it, already held (`kid-hold-6`); they were not the gap.

**Decision (owner, 2026-11-23: fail closed by default; reversible).** Under `PoolPolicy.Custom` a ceiling at or below
PG or at or below TV-14 applies `GrownUpHold.tierLimit`, the one function a built-in channel of the band uses. A title
above the limit is `blockedByCeiling`. Nothing else of the grown-up hold is taken over: a custom channel keeps its own
sources, filters, ceiling, name and number, and it keeps the kids tier (the made-for-kids drop is a rule for sharing
titles between channels, not a promise of the band). The rule follows the ceiling, not the number, so a channel the
migration left below its floor airs the same. Kid-flag channels (`KidsOnly`), adult copies and channels with no ceiling
or a ceiling above TV-14 are unchanged. It is a documented exception to "no hidden filters" for custom channels
(`docs/guide/mature-content.md`).

A retired recipe's channel kept as Custom (its `starter:` or `leftovers:` ID) in a young band is a built-in for the band
cap (`ChannelCeilings`); with that cap as its ceiling it now also gets the tier limit, which only narrows it.

**Version.** `GrownUpHold.VERSION_NAME` is `grown-up-hold-9`; `kid-hold-9` does not change, since no kid rule did.
`PersistedDays.HOLDS` follows, so every stored day is rebuilt once. Vectors: `grown-up-hold-9.json`, a delta of
`customCeiling` rows (the older files are unedited).

## Addendum 2026-11-23 (4): non-US ratings, `kid-hold-10` / `grown-up-hold-10` ([#987](https://github.com/StationBreak/stationbreaktv/issues/987))

Rules 6 and 7 are rewritten in place above; the earlier text (US names only, the MovieLabs licence finding, an empty
table registry, AU PG pinned as unknown) no longer holds. Owner decision, 2026-11-23: seed from the Apache-2.0 Android
TV ratings XML, not MovieLabs; unknown and unrated stay adult; a bare age caps at Family.

- `KidHold.VERSION_NAME` is `kid-hold-10` and `GrownUpHold.VERSION_NAME` is `grown-up-hold-10` (rule 9's `kid-hold-6` and
  `grown-up-hold-5` are the earlier names). `PersistedDays.HOLDS` follows, so every stored day is rebuilt once.
- Vectors: `kid-hold-10.json`, a delta (the older files are unedited and pinned by `FrozenHoldVectorsTest`). It
  supersedes these rows of `kid-hold-6.json`: ratingMap `0`, `6`, `6+`, `7+` are level 2 (were 0, 1, 1, 1) and `DE-12`
  is 3 (was unknown); the tier of "bare 6 with a kids genre" is family (was kids); `passesKidCeiling` of "bare 6 under
  TV-Y7" is false (was true). `NonUsRatingVectors` (a hand-written table) holds the readable cases.
- Effect on a synthetic library of about 25,000 titles: bare `6` and `6+` titles leave kids (to family, or to teen where
  a family veto applies), and `DE-12` titles move from adult to teen. Nothing else moves toward a younger tier.
- Cost, accepted: a rating the XML lacks (UK, FSK spellings, Eirin, AU film `MA15+`) stays adult on a real server. The
  open acceptance item of #987, a re-run on the owner's server with counts, is the owner's.

## Addendum 2026-11-23 (5): ceilings read the mapped level, `kid-hold-11` / `grown-up-hold-11` ([#1124](https://github.com/StationBreak/stationbreaktv/issues/1124))

Addendum (4) left the grown-up and custom ceilings on the US ladder: `DE-12` or `AU-PG` set a title's tier but never aired
on a Kids-band (PG), Teens-band (TV-14) or ceilinged custom channel. It failed closed, so it was safe, and it left
non-US titles under-used. Now one shared reading serves both:

- Under a ceiling, a channel of any app policy but `Open` (a grown-up or custom channel, and a leftovers pool) reads each
  rating as the tier holds read it (`RatingMap.levelOf`): the item passes when that level is at most the rank of the
  ceiling, a ladder name, whose rank equals its level. Rule 4's shape is unchanged: an episode of a known series needs its
  effective rating and the series' rating to pass, an episode whose series is missing fails, a movie is judged on its own
  rating. A rating with no level (unknown, unrated, none) fails every ceiling, and `Approved` still fails (kid-hold-6).
  Fail closed is unchanged. `Open` stays the rules-format reference and reads the US ladder alone. The kid ceiling
  (`passesKidCeiling`) is untouched. `GateInvariants`' ceiling oracle does not call `RatingMap` (#1123, #1124): it reads US
  names, a country age, an all-ages word and its own short list of the other spellings the pool admits (`AU-PG`, `GP`,
  `PG13`, `SG-PG13`, `NZ-PGR`, `Rated PG`, `SAM 13`, `C8`, `M`, `A`), and treats any other spelling as unknown. It is
  ANDed with the pool check, so it can only be stricter: on a real library a title with a spelling outside its list
  that the pool rightly airs is reported as a ceiling violation (a false alarm, never a leak); extend its list then.
- Also read this way now, where the ladder did not: the aliases `GP` (as PG) and `PG13` (as PG-13), a dash variant or a
  doubled space in a US name (the tidy of kid-hold-9) and a bare age (family at least). They already set the tier; now
  they also pass a ceiling at their level. Nothing airs above its level: a title never passes a ceiling its level exceeds.
- Calls made (owner approved, reversible): the adult-only labels AU `R`, SG `R21`, KR `19` and JP `19` and `20` are
  level 7, Explicit, through `CountryRatingTables`' overrides. They were level 6 (adult); no kid channel took them
  either way. Explicit means After Hours only.
- Versions: `KidHold.VERSION_NAME` is `kid-hold-11` (the map changed) and `GrownUpHold.VERSION_NAME` is
  `grown-up-hold-11`. `PersistedDays.HOLDS` follows, so every stored day is rebuilt once. Vectors: `kid-hold-11.json`, a
  delta (the older files are unedited and pinned by `FrozenHoldVectorsTest`, now including `kid-hold-10.json`). It
  supersedes these rows of `kid-hold-10.json`: ratingMap `AU-R`, `JP-19`, `JP-19+`, `JP-20`, `JP-20+`, `KR-19`, `KR-19+`,
  `SG-R21`, `R21` are level 7 (were 6) and explicitRating `SG-R21`, `KR-19`, `JP-20` are true.
- Effect: on the gate's non-US fixture (`DE-6`, `DE-12`, `DE-16`, `DE-18`, bare `6`, `AU-PG`, `GP`, `PG13`, `Rated PG`, `SG-PG13` and an unknown spelling) a PG
  Kids-band channel gains the titles whose level is 2 or below (bare `6`), the Odds & Ends pools of the Kids and Teens
  bands take non-US titles within their ceilings, and `DE-12` can air under TV-14 and PG-13 but not PG. No title airs
  above its ceiling; the unknown spelling airs under none.

## Addendum 2026-11-23 (6): every adult-only label is Explicit, also with a space, `kid-hold-12` / `grown-up-hold-12` ([#1128](https://github.com/StationBreak/stationbreaktv/issues/1128))

Two gaps the review of addendum (5) found, both failing safe and both closed now. Both only make a title stricter.

- TH `19` was level 6 (adult) while JP `19` was Explicit. It is level 7 now (`CountryRatingTables`' overrides), so every
  adult-only label (AU `R`, SG `R21`, KR `19`, JP `19` and `20`, TH `19`) is Explicit.
- A spelling with a space after the country code (`SG R21`, `AU R`, and the same for every other Explicit label) was
  unknown, so adult, not Explicit. `RatingMap.isExplicit` now also reads it with a hyphen in place of that space and
  applies the same Explicit check, the one that already reads `NC - 17` as a whole key. The level of such a spelling is
  unchanged (unknown, which fails every ceiling), and a spaced spelling that is not adult-only (`AU PG`) stays unknown and
  is not Explicit. `ExplicitPrefixInvariantTest` still shows nothing Explicit before stops being Explicit.
- Calls made (reversible): TH `19` Explicit, and the spaced spellings Explicit. Stricter: such titles leave every Tots,
  Kids, Teens and starter grown-up pool and air on After Hours only (a custom channel with no ceiling is not touched).
- Versions: `kid-hold-12` and `grown-up-hold-12`, so every stored day is rebuilt once. Vectors: `kid-hold-12.json`, a delta
  (`kid-hold-11.json` is now pinned by `FrozenHoldVectorsTest`); it supersedes the rows `TH-19` and
  `TH-19+` of `kid-hold-10.json` and `TH-19` of `kid-hold-11.json` (level 7, were 6). The gate oracle does not read `RatingMap` and needs no change: it decides Explicit through
  `RatingTier`.

## Addendum (2026-11-23): product names ([#1101](https://github.com/StationBreak/stationbreaktv/issues/1101))

This record keeps the names of its date. Since [#1100](https://github.com/StationBreak/stationbreaktv/issues/1100) the
1 to 99 setting is called **Family Mode** (it was Kids Mode), and its bands are **Tots** (1 to 33, was the Kids band),
**Kids** (34 to 66, was the Family band) and **Teens** (67 to 99, was the Teen band). Where this record says Kids Mode,
the Kids band or the Family band, read Family Mode, Tots and Kids. Code names, `kid-hold-*`, `kidsOnly`, the
`kids-odds-and-ends` key and stored values keep the old words. See the [glossary](../guide/glossary.md).

## Addendum 2026-11-24 (7): board ratings the XML lacks, bare all-ages words, one Explicit prefix rule, `kid-hold-13` / `grown-up-hold-13` ([#987](https://github.com/StationBreak/stationbreaktv/issues/987))

Owner decisions, 2026-11-24, final. The Android TV XML was a starting point, not the whole map: StationBreak's own table
now covers more ratings. This lifts the 2026-11-23 line "no other source fills these". No table is copied from
Jellyfin, MediaPortal, TMDb or any other project: each tier below is read from the board's published definition
(`BoardRatings`, with the sources in its comment).

- **The boards** (levels as ADR rule 6: ALL, 7, 10, 13, 14, 16, 18, X; in-between ratings round up).
  - BBFC, prefix `GB`, `UK`, `United Kingdom` or `Great Britain`, and `12A` bare: `U` ALL, `PG` 10, `12A` and `12` 13,
    `15` 16, `18` 18, `R18` X.
  - FSK, prefix `FSK`, `DE`, `Germany` or `Deutschland`, and `FSK n`, `FSKn`, `ab n` bare: only the five official values
    `0` ALL, `6` 7, `12` 13, `16` 16 and `18` 18, and `Keine Jugendfreigabe` 18. `FSK-7` is unknown. `DE-0` is ALL.
  - Eirin, `JP` or `Japan`: `G` ALL, `PG12` 13, `R15+` (also `R15`, `R-15`, `R 15+`) 16, `R18+` X.
  - Australia: `G` ALL, `PG` 10 (the override), `M` and `MA15+` (also `MA15`, `MA 15+`) 16, `R18+`, `R` and `X18+`
    (also `X18`, `X 18+`) X. Singapore `R21` is X.
  - An optional `US:` or `US-` prefix reads the rest on the US ladder.
- **Fail up where two tiers meet** (reversible calls): `12A`, FSK 12 and Eirin PG12 are 13, not 10; AU `M` is 16, not 14;
  BBFC 15 is 16. A label a board does not define (`GB-12PG`, `JP-PG15`, `AU-MA16+`, `FSK-7`) is unknown, so adult.
- **Bare all-ages words are Family at most.** `U`, `ALL`, `GA`, `ATP` and `UNIVERSAL` with no system read as level 2,
  like a bare age (#1006); with a system (`GB-U`, `KR-ALL`, `BR-GA`) they keep their level.
- **One prefix rule for the level and the Explicit check.** A system is a two-letter code, `UK` (GB), a country name of
  the XML (United Kingdom, Great Britain, Germany, Deutschland, Japan, Australia, Singapore and the rest of
  `BoardRatings.PREFIXES`) or `FSK`, then `-`, `:` or `/`. This fixes a bug: `isExplicit` took a two-letter code only, so
  `United Kingdom:R18`, `Australia:X18+`, `Australia:R18+` and `Japan:R18+` were not Explicit and could air outside After
  Hours. `ExplicitPrefixInvariantTest` now covers every country name. A bare `R18`, `R18+` or `X18+` is Explicit by name
  and keeps its unknown level, as before.
- **KR `19`, JP `19` and `20` are Adult again** (owner reversal of addendum (5): mainstream adult ratings, not
  Explicit). They are level 6. Korea's Restricted Screening is Explicit; AU `R`, SG `R21` and TH `19` stay Explicit.
- Versions: `kid-hold-13` and `grown-up-hold-13`, so every stored day is rebuilt once. Vectors: `kid-hold-13.json`, a
  delta (`kid-hold-12.json` is now pinned by `FrozenHoldVectorsTest`). Its description names the rows of earlier files it
  supersedes. The gate oracle (`CeilingOracle`) reads these spellings on its own, from its own list, not from `RatingMap`.
- Effect, on the gate's synthetic non-US fixture, extended with these spellings: the hard-fail invariants hold (no title
  above its ceiling, no rating of 15 or older in a young band, no Explicit title outside After Hours).

## Addendum 2026-11-25 (8): a custom channel's cap is its number band ([#1046](https://github.com/StationBreak/stationbreaktv/issues/1046), [#1152](https://github.com/StationBreak/stationbreaktv/issues/1152))

Owner decision (2026-11-24, #1152) amends #993 and #1046: a custom channel has its own cap, Tots, Kids or Teens, and the
cap limits what the channel airs. Family Mode is a separate reachability filter on which channels are seen; it does
not rewrite a channel's cap. A Teens channel is not reachable while Family Mode is Tots. The earlier rule (the channel's
ceiling follows the viewer's Family Mode cap, carried as a schedule input) is withdrawn.

**What holds it, with no new hold rule.** The tier is the channel's number band (`FamilyTier`, #1157), and the cap is
the channel's own number and ceiling, so it is the same on every TV and ADR-0017 holds with no new schedule input:

- **Tots** (1 to 33) is a kid channel (`PoolPolicy.KidsOnly`): the kids tier, under a ceiling capped at TV-Y7 (rule 5).
- **Kids** (34 to 66) is `PoolPolicy.Custom` under at most PG / TV-PG, with the tier limit of addendum (3): up to the
  family tier.
- **Teens** (67 to 99) is `PoolPolicy.Custom` under at most PG-13 / TV-14, with the tier limit up to the teen tier.
- **Unrated is out under every cap**: no rating, a blank one, `NR`, `Approved`, or a name the map does not know. The
  #754 exception stays dropped (rule 4); it never applies to Tots.
- **A thin pool is a thin channel.** Nothing is filled from above the cap: a day's gap fill reads the channel's own pool.
- **The band cannot sit under a higher ceiling.** `ChannelFloors` numbers a ceiling above a band's cap in a higher band,
  and `FloorMigration` moves a channel found below its floor, so a channel's number and its cap agree, which is what
  keeps Family Mode (it reads the number alone) in step with the content cap.

**No version bump.** The engine already did all of this (addenda 3, 5 and `ChannelFloors`); no hold function changed, so
`kid-hold-13` and `grown-up-hold-13` and their vectors stand and no stored day is rebuilt. `FamilyCapCeilingsTest` pins
it: every ceiling of every tier, over every rating spelling, US and non-US, with episodes of series rated above and below
their own, a missing series and no series: nothing airs above the cap, nothing unrated airs, a thin pool stays empty.

**Known limit (owner may overrule).** The cap is not read from the number alone: a viewer's own channel in 34 to 99
keeps its own stored ceiling ("no hidden filters", addendum 3), and only the floor ties the two. One that finds no free
number to move to anywhere stays below its floor (`FloorMigration`). Clamping a custom channel's ceiling to its band, as a
built-in's is, would close that, but it would make a below-floor channel stop moving (the floor reads the clamped
ceiling) and narrow the viewer's channel silently; it is left as a decision.

## Addendum 2026-11-27 (9): the 17+ band is Adult, a dash variant reads plain, `kid-hold-14` / `grown-up-hold-14` ([#1006](https://github.com/StationBreak/stationbreaktv/issues/1006))

Owner decisions of 2026-11-23, confirmed 2026-11-27. This tightens what reaches the kid and young channels.

- **A minimum age of 17 is level 6.** The rung of an age is its minimum age rounded up; the rungs are now 0, 1 to 7, 8 to
  10, 11 to 13, 14, 15 to 16 and 17 and up (they were 15 to 17 and 18 and up). `17` and `17+`, bare or after a system
  (`DE-17`, `FR-17+`), are level 6, so a title so rated is above a TV-14 or R ceiling of a grown-up or custom channel
  that it used to pass at R. Ages 15 and 16 stay level 5. No title changes tier on this alone (levels 5 and 6 are both
  Adult), only under a ceiling.
- **A bare age stays Family at most, never kids** (`0`, `6`, `7+`, `10`), until licensed country tables land (#987). This
  was already the rule (rule 6); it is restated in `kid-hold-14.json` with its own rows, with a prefixed age (`DE-6`)
  unaffected and still kids.
- **A dash variant reads plain in the animated-family signal.** `AnimatedFamilySignal` read a rating through
  `RatingLadder.normalise`, which takes ASCII only, while `RatingMap` has always read an en dash, em dash or minus sign
  as a hyphen: an Animation and Family title rated `TV–Y` was Family and the plain `TV-Y` was Kids (found in #1182).
  The signal now tidies the rating as the map does (`NoiseTidy`) before the ladder lookup, so the two spellings tier
  alike. `RatingLadder` itself is unchanged: it is the published rule-4 ladder and `rating.json` pins it ASCII only, and
  every other reader of the ladder on a rating fails up on a dash variant (unknown).
- Versions: `kid-hold-14` and `grown-up-hold-14`, so every stored day is rebuilt once. Vectors: `kid-hold-14.json`, a
  delta (`kid-hold-13.json` is now pinned by `FrozenHoldVectorsTest`); it names the rows of `kid-hold-6.json` and
  `kid-hold-10.json` that it supersedes (`17` and every `XX-17` and `XX-17+`), and the checker holds the earlier files to
  the new answer. No catalog or curation version moves.
