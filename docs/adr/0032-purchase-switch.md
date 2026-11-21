# ADR-0032: The Pro purchase switch

- **Status:** Accepted, foundation only: the Amazon Appstore adapter waits on the owner's licence decision (below)
- **Date:** 2026-11-21
- **Decided by:** the owner ([#119](https://github.com/StationBreak/stationbreaktv/issues/119): the GitHub build is Pro and
  has no billing; [#36](https://github.com/StationBreak/stationbreaktv/issues/36): Amazon IAP only, $20 one time) and
  Rincewind (the mechanism)
- **Builds on:** [ADR-0023](0023-lineup-curation.md) ("Free, Pro and a refund")
- **Informs:** [#99](https://github.com/StationBreak/stationbreaktv/issues/99),
  [#36](https://github.com/StationBreak/stationbreaktv/issues/36)

## Context

Every free-tier gate already reads `ProEntitlement.pro` ([#793](https://github.com/StationBreak/stationbreaktv/issues/793)),
which a build constant answered. A purchase has to change it live, a refund has to take it back, and the GitHub build
must stay free of any billing code.

## Decision

1. **The provider is "build unlock OR store entitlement".** `StoreBackedPro` (`core:domain`) is true when the build
   unlocks Pro, and otherwise while the store's last verified answer is true. `ProEntitlement` is unchanged, so no gate
   moved.
2. **One port, `StoreEntitlement`.** `verified` (true, false, or null before the store has answered), `purchase()` and
   `restore()`. A purchase ends as `Purchased`, `AlreadyOwned`, `Cancelled`, `Failed` or `Pending`; only the first two
   verify Pro. The port carries no receipt, user ID or token, and nothing logs one.
3. **A boolean cache.** The last verified answer is kept in a DataStore file of its own (`noBackupFilesDir`, never backed
   up), so a TV that starts offline keeps Pro. A verified false clears it and beats it. A start before the store has
   answered trusts the cache. It is a convenience, not a proof: someone who edits it on their own rooted TV gains only
   what the GitHub build gives everyone.
4. **Flavors.**

   | Build | `PRO_UNLOCKED` | Store binding | Pro comes from |
   | --- | --- | --- | --- |
   | debug, benchmark (both flavors) | true | amazon: the adapter seam; github: none | the build, with no purchase |
   | `github` release | true | none (never owned) | the build: GPL, honor system ([#119](https://github.com/StationBreak/stationbreaktv/issues/119)) |
   | `amazon` release | false (a build error if true) | `app/src/amazon` | the store's verified entitlement only |

   The Appstore SDK, when linked, enters `app/src/amazon` in one file, and no other source set names it, so the GitHub
   build has neither the code nor the dependency. The Play flavor is out of scope; the GitHub flavor never gets Play
   Billing.
5. **One SKU constant**, `PRO_SKU` in that file. The console's SKU is the owner's to create.
6. **Verification (the adapter's contract, written in the seam file).** Register a `PurchasingListener`; set `verified`
   to true only after the SDK reports a valid receipt and `notifyFulfillment` was called; ask `getPurchaseUpdates` at
   start and each resume; set false when it reports the receipt cancelled or lists none; leave null while the SDK has
   not answered.
7. **A refund, by ADR-0023.** The refund is seen only as a verified false, never an unanswered store. Then:
   - the starters return to the planner's defaults, by the same reset the viewer's own reset runs (`ProRevocation`,
     which only acts while a starter still holds a viewer's mark, so asking again at every start is safe);
   - the customs beyond the five oldest are locked by a rule over creation time (`lockedCustoms`), not a stored field:
     `ProLockedChannelRepository` and `ProLockedScheduleSource` leave them out of every list and schedule (the way the
     Kids lock does), and `ChannelManager` and `ChannelBuildManager` refuse to change or delete one;
   - nothing is deleted, and buying Pro again unlocks them untouched, with no lineup-file change.

## Open: the Appstore SDK

`com.amazon.device:amazon-appstore-sdk` (3.0.9 on Maven Central) is licensed under Amazon's Program Materials License,
which is not on the GPL-compatible list `licensee` enforces ([ADR-0003](0003-licensing-and-contributions.md)). Linking it
into the `amazon` flavor needs the owner's decision (the GPL exception, [#745](https://github.com/StationBreak/stationbreaktv/issues/745),
and a reasoned `allowUrl` entry in `LicenseePlugin`), plus its checksum in dependency verification. Until then the Amazon
release builds with a stand-in that owns nothing, so it stays free.

## Consequences

- The GitHub APK has no billing class, string or dependency; a release check can prove it.
- The screen for buying, the nag and the restore button are a later change; they call `StoreEntitlement` through a port.
- Every outcome is tested against `FakeStoreEntitlement`, with no SDK.
