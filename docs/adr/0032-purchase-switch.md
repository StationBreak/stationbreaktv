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
3. **A cache with a 30-day grace.** The last verified answer, and the time the store last said true, are kept in a
   DataStore file of its own (`noBackupFilesDir`, never backed up), so a TV that starts offline keeps Pro for 30 days
   after that time ([#1065](https://github.com/StationBreak/stationbreaktv/issues/1065); it replaces "never
   expires"). On day 29 it still grants; from day 30 it does not, and the TV is free until the store next answers true.
   A lapse is not a refund: nothing is reset or deleted, the customs beyond the five lowest IDs pause (7), and the
   store's next true answer brings Pro and them back. The store is rechecked whenever the TV is online: the adapter
   asks at start and each resume (6), a true answer renews the time, and a process that stays up renews it daily while
   the answer stands. A cache that owns but has no readable time (one written before this rule, or a garbled value) is
   taken as verified at the first start after it, so an upgrade never locks a paying viewer out, and a time in the
   future (a clock set back) counts as now, so the grace is never longer than 30 days. A verified false clears the
   cache and beats it. A start before the store has
   answered trusts the cache only when the refund record (7) reads as not owed: an owed or unknown refund beats a
   cached owned, so a kill between writing the one and clearing the other, or a refund file that is corrupt or cannot
   be read, never grants Pro offline ([#1067](https://github.com/StationBreak/stationbreaktv/issues/1067)). It is a convenience, not a proof: someone who
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
   cache never held true is never reset, save the one case below where the refund record is unknown, and a TV that
   never held Pro has no mark to reset. The refund is written before the owned answer is cleared, with the accounts
   already reset for it (opaque lineup-owner keys, never names), and only a verified purchase clears either
   ([#1063](https://github.com/StationBreak/stationbreaktv/issues/1063)). Both live in a DataStore file of their own,
   apart from the cache, so a corrupt cache cannot drop them. A refund file that is corrupt or cannot be read is
   unknown, a third state: it never sets `revoked` and never resets anything, so an unanswered store still cannot
   reset a paying viewer's starters, but the cache does not grant Pro while it stands (3). The store's first verified
   answer settles it: true ends it; false is a refund, since the unknown file may hide one, and the resets run per
   account as for any refund (the handled accounts it held are lost, and a second reset finds nothing to reset).
   A corrupt file keeps the unknown as a mark until that answer is written. An install that kept the refund in the cache file moves it on first read
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
   - nothing is deleted, and buying Pro again, or the store's next true answer after a lapse of the offline grace (3),
     unlocks them untouched, with no lineup-file change. The locked IDs are read from the loaded lineup, with no channel
     list built, so a free-mode schedule call does not rebuild one ([#1065](https://github.com/StationBreak/stationbreaktv/issues/1065)).

## Accepted costs ([#1072](https://github.com/StationBreak/stationbreaktv/issues/1072))

- While a refund is owed, or the refund record is unknown and the store says false, each start or sign-in does one
  lineup read, and the set of handled accounts grows until a purchase clears it.
- A free TV that never held Pro, has a corrupt refund file and a lineup file from before version 3 (only pre-release
  installs carry one) can have its starters with inferred marks returned to defaults when the store first answers
  false. Telling that TV from a refunded one needs a stored "ever held Pro" field or a mark that says inferred, which
  is a lineup-format decision, so it is left for that decision.

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

## Addendum (2026-11-23): product names ([#1101](https://github.com/StationBreak/stationbreaktv/issues/1101))

This record keeps the names of its date. Since [#1100](https://github.com/StationBreak/stationbreaktv/issues/1100) the
1 to 99 setting is called **Family Mode** (it was Kids Mode), and its bands are **Tots** (1 to 33, was the Kids band),
**Kids** (34 to 66, was the Family band) and **Teens** (67 to 99, was the Teen band). Where this record says Kids Mode,
the Kids band or the Family band, read Family Mode, Tots and Kids. Code names, `kid-hold-*`, `kidsOnly`, the
`kids-odds-and-ends` key and stored values keep the old words. See the [glossary](../guide/glossary.md).
