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
   answered trusts the cache, unless a refund is owed (7): an owed refund beats a cached owned, so a kill between
   writing the one and clearing the other never grants Pro offline
   ([#1067](https://github.com/StationBreak/stationbreaktv/issues/1067)). It is a convenience, not a proof: someone who
   edits it on their own rooted TV gains only what the GitHub build gives everyone. A corrupt file is replaced by an
   empty one, which grants nothing ([#1063](https://github.com/StationBreak/stationbreaktv/issues/1063)) and leaves
   the refund alone.
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
   start and each resume. Set `verified` to false only from a successful, fully paged (`hasMore == false`)
   `getPurchaseUpdates(reset = true)` sync that lists no Pro SKU or lists it canceled. Anything else (an empty delta
   from `reset = false`, an error, a partial page, offline) leaves it null.
7. **A refund, by ADR-0023.** A refund is the change from owned (verified, or kept in the cache) to a verified false
   (`ProEntitlement.revoked`), never an unanswered store, and never a false on a TV that did not hold Pro. A TV whose
   cache never held true is never reset. The refund is written before the owned answer is cleared, with the accounts
   already reset for it (opaque lineup-owner keys, never names), and only a verified purchase clears either
   ([#1063](https://github.com/StationBreak/stationbreaktv/issues/1063)). Both live in a DataStore file of their own,
   apart from the cache, so a corrupt cache cannot drop them; a corrupt refund file fails closed, read as a refund
   owed with no account handled, so the resets run again (each one only acts on a starter that still holds a mark)
   until a verified purchase ends it. An install that kept the refund in the cache file moves it on first read
   ([#1067](https://github.com/StationBreak/stationbreaktv/issues/1067)). So a kill before a reset still resets
   at the next start, every account that signs in while the refund stands is reset once, and an account is counted as
   done only when its reset ran or a lineup that was read and may be written holds no mark to reset; a lineup that
   could not be read, or an account that does not resolve, is asked again later. Then:
   - the starters return to the planner's defaults, by the same reset the viewer's own reset runs (`ProRevocation`,
     which only acts while a starter still holds a viewer's mark, so asking again at every start is safe);
   - the customs beyond the first five by channel ID are locked by a rule (`lockedCustoms`), not a stored field. The ID
     is the only stable rank without a lineup-file change: the lineup is re-sorted by number at every plan, the number
     is the viewer's, and `onSince` moves on each switch-on. IDs are random, so the five that stay are a fixed but
     arbitrary set, not the first five made; ranking by creation time needs a stored field (a format-version decision)
     and is left for that decision. The set is ranked over the whole lineup, before Kids Mode filters anything:
     `ProLockedChannelRepository`, `ProLockedScheduleSource` and `ProLockedHoldings` leave them out of every list,
     schedule and channel box (the way the Kids lock does), and `ChannelManager` and `ChannelBuildManager` refuse to
     change or delete one. Every `custom:` ID is ranked, one this app cannot read too: it holds its place among the
     five, as it does under the free cap, and is never deleted;
   - the gates are asked again inside the lineup's lock, on the lineup the change would write, and the refund's reset
     asks again there just before it saves, so a refund or a purchase that lands while a change waits for the lock is
     not missed ([#1063](https://github.com/StationBreak/stationbreaktv/issues/1063));
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
