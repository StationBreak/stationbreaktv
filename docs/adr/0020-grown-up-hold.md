# ADR-0020: The grown-up hold (spec 18)

- **Status:** Accepted
- **Date:** 2026-10-29
- **Decided by:** Rincewind (the mechanism), following the owner's decision that kids means made for kids (2026-10-27)
- **Amends:** [ADR-0019](0019-kid-hold.md) §7
- **Informs:** spec 18, ASSUMPTIONS A-VER-1

## Context

ADR-0019 §7 says a grown-up channel drops exactly what a kid channel would take. That held while the kid gate was
genres and studios, because the catalog's grown-up recipes exclude the kids genres. `kid-hold-2` then added children's
ratings (`TV-Y`, `TV-Y7`, `TV-Y7-FV`, on the item or its known series) as made for kids, and nothing on the grown-up
side followed. A children's-rated series with no kids genre is held out of kid channels only by being a different
kind of show: it still airs on about twenty grown-up starters. Rules format v1 has no rating exclusion, so the fix is
app-side, like the kid hold.

## Decision

1. **A pool policy, not a flag.** `PoolEvaluator.evaluate` takes a `PoolPolicy` (`core/engine`): `Open`, `KidsOnly` or
   `GrownUp`. `Open` is exactly the rules-format evaluation (the default). `KidsOnly` is the kid hold of ADR-0019,
   unchanged. `GrownUp` removes, after the exclusions, every item for which `KidHold.hasChildrensRating` is true.
   The ladder ceiling still applies to what is left.
2. **One predicate.** `KidHold.CHILDRENS_RATINGS` and `KidHold.hasChildrensRating(entry, series)` are the single
   definition of a children's rating (the item's rating or its known series' rating, after normalisation).
   `KidHold.isMadeForKids` uses the same predicate for its ratings rule, so the two gates cannot drift. `kid-hold-2`
   is unchanged.
3. **Version name.** The grown-up gate is `grown-up-hold-1` (`GrownUpHold.VERSION_NAME`). It is frozen with
   `CHILDRENS_RATINGS`: one test pins the set and names both hold versions, so a change to the set is a new version
   name of each.
4. **Who gets which policy** (`KidStatus.policyOf`):

   | Channel | Policy |
   | --- | --- |
   | A kid channel (Kids band, or an ever-kid-safe key) including the kids' leftovers | `KidsOnly` |
   | Any other starter, edited or not | `GrownUp` |
   | A custom channel outside the Kids band | `Open` |
   | The grown-up leftovers (Odds & Ends) | `Open` |

   An edited starter is `GrownUp` because the channel still carries a catalog name and the same trust promise; a
   user who wants children's-rated shows there has the kid channels or a custom channel. A custom channel is the
   user's own recipe and stays `Open`. The grown-up leftovers is `Open` on purpose: it is where items go that no other
   channel will take. The kid hold refuses some items (unrated, TV-PG) and the grown-up hold drops others
   (children's-rated); if the grown-up leftovers dropped the second set too, an item no channel airs could air
   nowhere. Keeping it `Open` keeps "airing nowhere" at zero while both leftovers channels are on.

5. **Counts and reasons.** `PoolCounts.childrensRatingOut` counts what the grown-up hold drops; `heldOut` stays the
   kid hold's. A grown-up channel emptied by the hold is not "no matches": it reports the existing
   `HiddenReason.AllExcluded`.
6. **No catalog change.** Catalog version and manifest are unchanged. Every pool call site (the planner's ranking,
   ruled days, leftovers, and the withheld sets for hidden and opt-in channels) asks `policyOf`, and the pool cache key
   carries the policy.

## Consequences

- Grown-up starters can get thinner. A finalised lineup is not re-ranked, so a starter that is On stays On even if
  the hold leaves it little to air; it shows the existing hidden reason when empty. New lineups rank on the held pool.
- Children's-rated items a grown-up starter drops fall to the grown-up leftovers.
- Mixed app versions can disagree on grown-up starters during an update, as for kid channels (A-VER-1).
