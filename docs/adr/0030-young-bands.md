# ADR-0030: Young bands and the one-time renumbering waiver

- **Status:** Accepted
- **Date:** 2026-11-21
- **Decided by:** the owner (age-ordered young bands, 2026-11-19; the waiver below, 2026-11-19; Mask & Mantle named,
  2026-11-21) and Rincewind (the mechanism)
- **Builds on:** [ADR-0028](0028-rating-tiers.md) (audience tiers), [ADR-0023](0023-lineup-curation.md) (the catalog)
- **Informs:** [#1002](https://github.com/StationBreak/stationbreaktv/issues/1002)

## Context

ADR-0028 left family and teen titles airing on grown-up channels until family and teen channels exist. The Kids band
was 1 to 99, and `StarterCatalog.kt` promised that a shipped key or number never changes and is never reused.

## Decision

1. **Three young bands.** Kids 1 to 33, Family 34 to 66, Teen 67 to 99, each ending in its own Odds and Ends (33, 66,
   99). General still starts at 100. Ceilings: Kids PG or lower (kid ratings only in practice, ADR-0028 rule 5), Family
   PG, Teen TV-14 (the PG-13 / TV-14 tier). `Band`, `Recipe` and `LeftoversRecipe` check these.
2. **No new pool policy.** A Family or Teen channel is a grown-up channel (`PoolPolicy.GrownUp`, which drops the kids
   tier) under its ceiling, so it airs family or teen titles. Only the Kids band is kids-only now. (Lights Out, Tall Tales Club and Tiny Chefs were kids recipes kept outside it
   until they were merged away or cut at catalogs 9 and 10, #995 and #996.) The four former kids recipes that moved
   to Family stay in `EVER_KID_SAFE_KEYS`, so a stored copy is still held to PG whatever its edits. The hold also
   survives retirement (#1044): a retired kid recipe's stored copy becomes a Custom channel but keeps its
   `starter:<key>` or `leftovers:<key>` ID, and `KidStatus.isKidChannel` reads a kid key from that ID, so the channel
   stays kids-only at any number. A viewer's own `custom:<id>` channel never matches.
3. **Catalog 8** (frozen manifest V8) moves 17 recipes and the kids leftovers to their new numbers, adds nine recipes
   (Big Screen Toons, Popcorn Matinee, Starship Sunday, Prime Time Pals, Homeroom, Mask & Mantle, Level Up, Popcorn Epic, Nervous
   Laughter) and two leftovers recipes. No recipe changes what it airs. Little Spirits is anime-only (Family is not the Kids band). Paper Lantern is dropped.
4. **The waiver.** The never-reuse rule is waived once, for this move, by the owner ("bypass the veto, this is a pre
   release change"). It is owner-approved for pre-release only: the `StarterCatalog.kt` rule resumes after v1 and
   applies to version 8's numbers as shipped. The manifests V1 to V7 are untouched; `CatalogManifestTest` names the
   waived keys (`NUMBERS_WAIVED_AT_8`), and any other move still fails.
5. **Migration.** A lineup from before catalog 8 takes the new numbers at the refresh that takes the catalog
   (`CatalogRenumbering`): every starter and leftovers channel takes the catalog's number unless a channel that is not
   moving holds it, then the first free number in its band, else none (the shelf). A viewer's own number stays. A custom
   channel numbered 34 to 99 was a kid channel under the old band; it moves to the first free Kids number so that it
   stays a kid channel (a full Kids band leaves it where it is, a known limit).

## Consequences

- Marks are named by number, so 18 marks were renamed and the new channels have temporary placeholder marks until Look
  draws them (`core/ui/CHANNELMARKS.md`).
- Audit goldens and the synthetic lineup fixture moved; no live library was read.

## Addendum: catalog 13 (Oddly Funny into General, #1000)

The owner approved one more pre-release exception to never-reuse, named per key like the first: Oddly Funny (key
`satire-desk`) moves from 528 in Premium to 190 in General, because its filter (a half-hour comedy with a twist) is a
General channel. Its key, name, sources and filter are unchanged, so its fingerprint is too; only the tagline is
rewritten to match the filter. 190 is the free General number after Curtain Call (186), clear of Odds & Ends (199) and
of every retired number. 528 is retired and never reused. `CatalogManifestTest` names the waiver
(`NUMBERS_WAIVED_AT_13`); any other move still fails. An installed lineup takes 190 at the refresh that takes the
catalog (`CatalogRenumbering`, with only that channel moving): a viewer's own number stays, and nothing shares a
number. The mark files are renamed from 528 to 190 and their art is unchanged until Look redraws it.
