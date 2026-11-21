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
   tier) under its ceiling, so it airs family or teen titles. Only the Kids band and the kids recipe kept outside it
   (Tiny Chefs, until #996; Lights Out and Tall Tales Club were merged away at catalog 9, #995) are kids-only. The four former kids recipes that moved
   to Family stay in `EVER_KID_SAFE_KEYS`, so a stored copy is still held to PG whatever its edits.
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
