# ADR-0031: The seasonal band and date-gated channels

- **Status:** Accepted, windows pending the owner's confirmation in the pull request
- **Date:** 2026-11-21
- **Decided by:** the owner (issue #592: the band, the four channels, "they do not need to keep 182 / 340") and
  Rincewind (the mechanism and the proposed windows)
- **Builds on:** [ADR-0023](0023-lineup-curation.md) (the catalog and the planner's selection),
  [ADR-0030](0030-young-bands.md) (the pre-release waiver)
- **Informs:** [#592](https://github.com/StationBreak/stationbreaktv/issues/592)

## Context

Selection had no notion of season, so the four holiday channels aired in the wrong months. The numbers 900 to 999 were
reserved ([ADR-0022](0022-rules-format-v2.md), ruling 15) and unused.

## Decision

1. **Band.** 900 to 999 is the Seasonal band (`Band.Seasonal`). A recipe in it must have a season.
2. **Gate.** A recipe may carry a `Season` (Christmas or Halloween); a season is a month-and-day window, named once in
   `LineupDefaults` (`CHRISTMAS_SEASON`, `HALLOWEEN_SEASON`), so a window is one change. Out of its window the recipe's
   pool is empty for the day a plan is for (the boundary's, by the lineup's standard offset), so the existing selection
   leaves it out the way it leaves out any channel with nothing that fits: untouched, it goes to the shelf with reason
   Empty at the boundary, and the same selection switches it on again when the window opens. No new state, no clock
   read: it is a pure function of the day, so every TV agrees. Common code, no `java.*`.
3. **Windows (proposed).** Christmas, November 22 to December 31. Halloween, September 24 to October 31. Both ends
   are in the window; a leap day is not special.
4. **Channels.** Snowglobe (Kids 20) and Spooky Sleepover (Family 45) stay in place and take the gate. Always December
   moves from 182 to 900 and Jack-o'-Lantern Lounge from 340 to 910, keeping their keys, recipes and marks (renamed
   files only). The old numbers are retired and never reused. The move is a named per-key waiver of never-reuse, owner
   approved and pre-release, as at version 8 (`NUMBERS_WAIVED_AT_12` in the catalog tests).
5. **Migration.** A lineup from before catalog 12 takes the two new numbers at the refresh that takes the catalog
   (`CatalogRenumbering`, with only the seasonal recipes moving): a viewer's own number stays, nothing shares a number,
   state and rules are kept.
6. **Catalog 12** freezes manifest V12. A recipe's season and window are in its fingerprint, so a change to a window
   needs a newer catalog.

## Consequences

- A viewer's own number and on/off survive the off months. A starter the viewer switched on is pinned and stays on all
  year (it airs its pool, as the viewer chose); that is the per-channel always-on override in all but name, and the
  separate setting sketched in icebox #126 is not built.
- The gate is evaluated when the planner runs (start, sync, the day roll), at the boundary's date. A TV left running
  across a window edge changes at its next plan, as for any other planner change.
- Out of season the titles of these channels fall to the other channels and the leftovers, as for any off channel.
