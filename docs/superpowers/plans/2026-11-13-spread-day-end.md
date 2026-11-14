# Spread the End of the Day: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In rules-format version 5, the day's leftover time is spread through the day as breaks at show boundaries.
Each break holds best-fit shorts and a station-break card, so there is no "OFF AIR" pile before 06:00. Starters and
newly created channels move to version 5.

**Architecture:**
- A new `ScheduleContent.Break` flows end to end: scheduler → domain `Airing.Break` → the live ident card and the guide
  cell, plus the repair record.
- `SchedulerSettings` gains `leftover: LeftoverPolicy` (`Gather` | `Spread`), mapped from `formatVersion >= 5`.
- `Timeline.layout` keeps today's planned end for `Gather`. For `Spread` it hands the leftover to a new pure `Breaks.kt`
  (best fit, then placement).
- Starters move through the existing keeper path once `StarterCatalog.VERSION` bumps. New channels are written at
  version 5.

**Delivery:** Task 1 may land on its own (a `Break` kind with no producer). Tasks 2 to 5 land together in **one PR**,
so no build ever maps version 5 to `Spread` without the `Spread` layout and its frozen text and vectors. Tasks 6 and 7
come after that PR. Nothing is written at v5 before Task 6.

**Prerequisite (hard): ADR-0026's read-4 release.** Rules `formatVersion` 4 belongs to ADR-0026 (studio fold 2),
which ships first. Before Task 2 starts, this part of ADR-0026's code must be merged: v4 reading, `RulesVocabulary` v4,
fold 2 in `SourceMatcher`, `ChannelRules.writeVersion` with its never-lower-a-4 clause, and its
`normalised`/`DraftRules`/`RecipeResolver` changes. Tasks 2 and 6 build on `writeVersion` and add no parallel version
policy.

**Not required:** ADR-0026's post-gate code (the one-time v3→v4 studio rewrite, the read/import/save invariant, and
`writeVersion` returning 4 for studio features). That code waits on ADR-0026's own gate (every port reads 4 and every
TV checked); this plan does not. v5 behaviour does not depend on the gate state: `maxOf(5, writeVersion)` is 5 whether
`writeVersion` gives 3 or 4. Whichever lands second, this plan or ADR-0026's rewrite, carries the test
`adr0026RewriteLeavesAV5StudioFileAlone`. That is a named obligation on the ADR-0026 rewrite PR if it comes second. The main checkout is behind `origin/main`, so rebase first. The line
numbers in Tasks 2 and 6 are pre-ADR-0026 and must be re-found.

**Release rule (Task 6), owner decision 2026-11-13: an Android-only exception to ADR-0026's release gate.** A
release that writes 5 does **not** wait for Vega OS, Roku, or a check of every TV. It ships as soon as Tasks 1 to 6 are
merged, and the household's Fire TVs switch when they take it. Two conditions remain:
- Task 6 ships in the same release as Tasks 2 to 5, or after them, never before: the build that writes 5 must read it.
- Task 6 must not ship before ADR-0026's read-4 code (it builds on `writeVersion`; see Prerequisite).
- The override is recorded on main no later than the code that uses it: the release-rule ADR text lands in the Task 6
  PR (see Task 6 and Task 7).

**Fold 2 arrives early through v5.** v5 is fold 2 plus the spread end, so every v5 file is matched under studio fold
2. On the Android build, before ADR-0026's gate passes, every studio starter and every new or copied studio channel is
written at 5 and gets fold 2, while existing custom studio files stay at v3 with fold 1. This is a second route around
ADR-0026's gate, and it is accepted on purpose: it also closes, for those channels, the hold-versus-exclusion fold
mismatch that ADR-0026 accepts until its upgrade. The exception does not change ADR-0026's own gate for writing 4.

**The cost the owner accepts.** Lineups are per TV and channel sharing is deferred (`docs/ASSUMPTIONS.md`, Q-SYNC-1
withdrawn). So the cost a household sees during the update window is that the same starter gives a different day on
an updated TV (spread) than on one not yet updated (gather). ADR-0002 allows this, because app version is an input to
a day. A reader that stops at 4 (an older Fire OS build, or the Vega OS and Roku apps until they catch up) sees a v5
file only after a downgrade, restore or import. It then shows that channel as unusable ("update required", off air)
and keeps it byte for byte until it is updated (Review Focus 5).

**Tech Stack:** Kotlin Multiplatform (`core:model`, `core:scheduler`, `core:rules`, `core:engine`), Android
(`core:domain`, `feature:live`, `feature:guide`), Jetpack Compose, Gradle.

**Spec:** `docs/superpowers/specs/2026-11-13-spread-day-end-design.md`

**Revision 2 (2026-11-13):** revised after a `crew:architecture` BLOCK.
- Task 6 now uses the keeper path. There is no special case.
- `curated` fill is constrained per group.
- Leftovers channels stay on `Gather`.
- Ordered modes get grouped best fit.
- Break placement is defined precisely.
- Repairs carry kept-window airings.
- The repair-record and lineup-file versioning is gated.
- `DraftRules` takes a target type.

**Revision 3 (2026-11-13):** revised after a second `crew:architecture` BLOCK. The owner picked option (a), so the
lineup file stays at version 4.
- The spread algorithm is now staged, so it is no longer circular.
- `curated` uses the walk's group key, `groups.groupId`.
- Repairs pass the aired items with their end times.
- Shuffle can't replay an item straight after it airs.
- Each live site decides explicitly how to treat a break.
- `normalised()` is untouched.

**Revision 4 (2026-11-13):** revised after a third `crew:architecture` review, so the normative text is unambiguous.
- `bestGroupedFit` cuts each group at its first candidate that can't fit (the passed-over rule), and the 64 cap only
  ever cuts a group's tail. The grid ordered path and its extra pass use the same rule.
- Curated units are ordered by their first F-index, and items within a unit are in cycle order. There is a wrapped-group
  vector.
- In shuffle, an `airedBefore` airing's run end is its `endMs`.
- The grid extra pass and the non-grid candidate visit are defined exactly.
- Choosing F also scores today's first fit over the full visit (candidates U, A, firstFit), so a selection the 64 cap
  leaves out is still considered. (Placed sums are compared after S3, so this is not a guarantee of placing at least
  what today places.)
- `KeptBlock` holds a sealed `KeptContent`. The stray `fileVersion` decode parameter is gone.
- Downgrades: an older build keeps every v4 channel as `Unusable`, byte for byte. This is pinned by a test and an ADR
  note.
- Tasks 2 to 5 ship in one PR. Spec item 2's tie rule now matches the plan. Task 2 updates `RecipeResolverTest`.

**Revision 5 (2026-11-13):** revised after a fourth `crew:architecture` review (CONCERN), with
the coordinator's rulings.
- Task 5 defines the break block in the rules-format "Output".
- The S2 wording is exact.
- Items are compared by canonical ID.
- Ordered modes: the card goes before the fill, and the fill is chosen as the better of `bestGroupedFit` and today's
  `fillEnd` selection.
- There are more vectors.
- The repair rule stays out of the rules format (it lives in ADR-0025).
- The downgrade radius is corrected: the whole per-TV repair record is dropped.
- The collection invariant is restated as walk-queue order.
- `airedBefore` is required and built from the kept spans.
- The `normalised()`/`requiredVersion` KDoc is updated.
- The `w == 0` clause is dropped. Spec item 10 has a CAP footnote, and the caps list is extended.

**Revision 6 (2026-11-13):** owner decision. ADR-0026 (studio fold 2) owns rules `formatVersion` 4 and ships first, so
the spread day end becomes **`formatVersion` 5**.
- Everything about the spread end moves from v4 to v5: `Spread` when `>= 5`, `SUPPORTED_FORMAT_VERSION = 5`,
  `VERSION_5`, `lineup-v5.json` and its vector classes, "v6 not scheduled", and starters and new channels at 5.
- v4 maps to `Gather`. The lineup file's own version stays 4; that is unrelated to the rules version.
- There is a hard prerequisite (ADR-0026 merged) and a v5 release rule.
- `RulesTarget` is reconciled with ADR-0026's `writeVersion`: one "never lower a v5 file" clause and a new-channel floor
  of 5.
- The re-resolve and the one-time rewrite are checked against the keeper path.
- The downgrade notes cover builds that read up to 4.

**Revision 7 (2026-11-13):** owner decision ("ship now and accept"). The v5 release gate (every port reads 5, every TV
checked) is replaced by an Android-only exception: Task 6 ships with or after Tasks 2 to 5, and older readers show v5
channels as unusable until updated. Task 7's ADR addendum records the exception as an owner override of ADR-0026's
release-gate rule, for v5 only.

**Revision 8 (2026-11-13):** after a `crew:architecture` review of revision 7 (CONCERN). The prerequisite is split:
only ADR-0026's read-4 code is required, not its post-gate rewrite. The plan says that v5 brings fold 2 ahead of
ADR-0026's gate. The override text moves into the Task 6 PR and into ADR-0026, Task 6 gets a release precondition, and
the cost statement is corrected (per-TV lineups, so the visible cost is a different day on an updated TV).

## Global Constraints

- Stateless: a day stays a function of rules, snapshot, seed, time and app version. A day never reads the day before.
- Nothing runs past `E`, no programme is cut, and every day tiles `[S, E)` exactly. An empty break emits no block,
  because `composedDay` rejects zero-length blocks.
- Versions 1 to 4 behave byte for byte as they do without this plan (`LeftoverPolicy.Gather`; v4 is ADR-0026's studio
  fold 2), and their vectors don't change.
- Breaks go only at **run boundaries** of the regular layout (never inside a run or a pair). There must be at least one
  unchosen run boundary between any two chosen break points.
- Target spacing is one break per **150 minutes** of day (`BREAK_SPACING_MS = 9_000_000`). A card share is at least
  **60 s** (`MIN_CARD_MS = 60_000`), except when the whole `R` is shorter than that.
- Leftovers channels (Odds & Ends and Kids' Odds & Ends) stay on `Gather`, per the owner's decision "starters and new
  only". They have no rules file to carry a boundary switch.
- Existing custom channels, edited starters and imported files keep their `formatVersion`, even when saved. The
  lowest-version rule (ADR-0022 ruling 10) is unchanged for them.
- `DaySnapshotCodec` (persisted days) stores pools, not blocks, and its key's `app` part already changes with every
  build. **It needs no change.** Only the repair record stores blocks.
- Copy: the guide cell title is `STATION BREAK`, and the panel and info line read `Station break · Up next at %1$s`.

## Review Focus

1. **A curated short of a group that also airs in the regular layout** (a series or a collection): it goes only into a
   break at or after that group's last airing plus `repeatGapMs`. If there is no such break, it is dropped. It never
   airs before an item that comes earlier in the group's **walk queue**. The curated walk rotates each queue to start
   at `key mod size` (`CuratedWalk.kt:78-82`), so this is queue order, not episode or release order.
2. **A day with no run boundaries before `c`**, including `c == S` in a late repair: one break at `c`, which is a
   `Break`, not a `Slate`.
3. **An all-movie 24 h day:** not a card after every film. Chosen points are at least two boundaries apart, and cards
   are at least 60 s long.
4. **A repair at 15:00** (`layoutTail`): breaks are planned over `[fromMs, E)` only, and airings in the kept window
   count for the group and repeat-gap rules.
5. **An older build opening a lineup file written by this one (a downgrade):** the lineup file is still lineup
   version 4, so the lineup loads.
   - The older `RepairsCodec.decode` throws on the first `-2` row. `LineupCodec.repairsOf` then drops the **whole**
     `repairs` object (`TodayRepairs.NONE`, `repairsUnreadable`). So every channel's selection repairs and `LostFix`es
     on that TV are lost until the next repair, not just the one record. This is accepted under option (a), and the
     owner is told the real radius.
   - An older build reads rules only up to format version 4 (ADR-0026's build, `SUPPORTED_FORMAT_VERSION = 4`) or 3
     (before it). So every channel whose rules are at v5 decodes as `ChannelSource.Unusable` (`LineupCodec.kt`
     ~62-64): migrated starters and channels created on the new build. These channels are off air on that build. The older planner leaves `Unusable`
     channels alone (`LineupPlanner.kt` ~395), and the codec writes their JSON back byte for byte, so they return
     intact after an upgrade.
   - Existing lineup-v4 files with repairs load unchanged on the new build.

Each is pinned by a test in the owning task.

---

### Task 1: The `Break` kind, end to end, with no producer yet

**Files:**
- Model: `core/model/.../schedule/ScheduleBlock.kt`. Add `data object Break : ScheduleContent`, with KDoc "Leftover time
  spread as a station break; rules-format v5."
- Domain, exhaustive `when` (the compiler finds these):
  - `lineup/ChannelOnAir.kt`: `Airing.Break`; and `AiringBlock.positionAt`.
  - `BuiltInScheduleSource.kt:1382-1391`: `toAiring`.
  - `repair/SelectionRepair.kt:608-612`: `kept()`.
  - `repair/RepairedDay.kt:102-103`: `toContent`.
- Domain, non-exhaustive sites. Decide each one explicitly and write the decision in the commit body: whether a break
  acts like `Slate` (no item, no seek) or like a short wait before the next programme:
  - `BuiltInScheduleSource.kt:597, 629, 662`
  - `repair/SelectionRepair.kt:386, 503`. An all-`Break` tail is "nothing".
  - `repair/LostRepair.kt:76, 128`
  - `builder/ChannelPreviews.kt:402`
  - `repair/RepairedDay.kt:41, 51, 78`
  - `repair/KeptWindow.kt:35, 61, 85`
- Repair record:
  - `repair/TodayRepairs.kt`:
    - `KeptBlock(startMs, endMs, content: KeptContent)` replaces `itemId` and `mediaStartMs` with `sealed interface
      KeptContent { data class Program(val itemId: CanonicalId, val mediaStartMs: Long); data object Slate; data
      object Break }`. The type makes an item without a programme unrepresentable. Update every `KeptBlock(` call site
      (8 in `core/domain`).
    - `canonical(blocks)` (around line 297) adds a `~` marker **only for `Break`**, so the digests of existing Program
      and Slate records don't change.
  - `repair/RepairsCodec.kt`: add the sentinel `BREAK = -2L` in `encodeBlocks`/`decodeBlocks`, and update the doc line
    that says "-1 for a slate". The wire layout and the digest are unchanged: `Slate` and `Break` write the media start
    a slate writes today.
  - `LineupCodec.kt`: **no version bump** (owner decision: option a). Add a doc paragraph making an explicit exception to
    "any schema change bumps VERSION": the contents of `repairs` are a per-TV, disposable record. An older build that
    meets an unknown block index drops the **entire** repair record (every channel's repairs and lost fixes on that
    TV, until the next repair) and loads the lineup. Also update the "version 1 to 3" rules line
    to "1 to 5".
- Live, exhaustive: `Banners.kt:32-36, 64-69` and `Sitting.kt:276-292, 321-325`. `Airing.Break` takes the `Slate` path
  (`holdIdent`, so `IdentSlate` shows the Up-next banner) with `reportFlipEnded("break")`.
- Live, non-exhaustive. Decide each site explicitly and list the decisions in the commit body:
  - `LiveChannel.kt:164, 638, 749`, `BehindPlay.kt:253`, `Banners.kt:59, 117`, `Sitting.kt:317, 319`,
    `ui/LiveScreen.kt`, `Visiting.kt`.
  - `NextUp.kt:77, 109, 135, 151, 161`, `Neighbours.kt:127, 154`, `DeadItems.kt:186`, and the warm standby
    (`core/playback/.../StandbySlots.kt` and its feeder): the programme after a break counts as imminent, so it is
    warmed or prefetched during the card, as for the next programme after a programme.
- Guide:
  - `GuideModel.kt`: `CellKind.Break` and `cellOf`. A `Break` is never merged by `mergeContinuations`.
  - `ui/GuideCells.kt:262-282`: the `STATION BREAK` plate.
  - `ui/GuideText.kt:33-37`.
  - `GuideScreen.kt:405`, `GuidePanel.kt:233, 312`, `GuideViewModel.kt:421, 1100`.
  - Strings: `guide_station_break` and `program_station_break_up_next`.
- Test fixtures: the `FakeSchedule` test fixtures, so they can emit `Break`.
- Tests: `RepairsCodecTest`, `LineupCodecTest`, `TodayRepairsTest`, `GuideModelTest`, and the live sitting test.

**Interfaces:**
- Produces: `ScheduleContent.Break`, `Airing.Break`, `CellKind.Break`, `KeptContent`, and `KeptBlock(startMs, endMs,
  content)`. `RepairsCodec.decode` keeps its signature, because the lineup file stays at v4.

- [ ] **Step 1: Write the failing tests:**
  - `aBreakBlockRoundTripsThroughTheRepairsCodec`.
  - `aLineupWithABreakInItsRepairsIsStillVersion4`.
  - `anExistingV4FileWithRepairsStillLoads` (a stored fixture).
  - `slateAndBreakRecordsDigestDifferently`, and `existingProgramAndSlateDigestsAreUnchanged` (a pinned digest).
  - `theProgrammeAfterABreakIsWarmedDuringTheCard`.
  - `everyKeptContentKindRoundTripsThroughTheRepairsCodec` (Program with its media start, Slate, Break).
  - `aBreakBlockBecomesAnUnmergedBreakCell`.
  - `aBreakHoldsTheIdentWithUpNextAndPlaysOnAtItsEnd`.
  - `aFiveSecondBreakEndsCleanlyIntoTheNextProgramme`.
- [ ] **Step 2: Run them and confirm they fail.**
  Run: `./gradlew :core:domain:testDebugUnitTest :feature:guide:testDebugUnitTest :feature:live:testDebugUnitTest`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the step 2 tests, plus `./gradlew :core:model:jvmTest :core:scheduler:jvmTest`. Expected: PASS.
- [ ] **Step 5: Commit**: `feat(schedule): a station-break block kind, shown as the ident with Up next`.

### Task 2: Format version 5 and `SchedulerSettings.leftover`

**Files:**
- `core/model/.../schedule/SchedulerSettings.kt`: `enum class LeftoverPolicy { Gather, Spread }`, and the field
  `val leftover: LeftoverPolicy = LeftoverPolicy.Gather`, placed last.
- `core/rules/.../RulesLimits.kt:7`: `SUPPORTED_FORMAT_VERSION = 5` (ADR-0026 has made it 4).
- `ChannelRules.kt`: the init accepts 1..5, and its message and class KDoc say "1 to 5".
  - `requiredVersion` is **unchanged**. It never reports 5.
  - ADR-0026's `writeVersion` gains **one clause**, and no other version policy is added: "never lower than the
    file's own `formatVersion` when that is 5 or more". ADR-0026's never-lower rule covers only files with studio
    features; this clause covers every v5 file.
  - So `normalised()` (which ADR-0026 makes write at `writeVersion`) keeps a v5 file at 5, and its behaviour for
    v1 to v4 files is ADR-0026's.
  - The KDoc of `requiredVersion`, `writeVersion` and `normalised()` gains a paragraph: "Version 5 is a behaviour
    version (the spread day end), not a feature version. `requiredVersion` never reports it. `writeVersion` keeps a
    v5 file at 5, and callers must not normalise a v5 file down by any other path."
- `RulesVocabulary.kt`: `VERSION_5 = 5`. `topLevel`, `ordering` and `filters` return v4's sets (ADR-0026: v3's) for 5
  explicitly.
- `core/engine/.../SchedulerSettingsFromRules.kt`: `settingsOf(..., leftover: LeftoverPolicy)`. `toSchedulerSettings`
  passes `Spread` when `formatVersion >= 5`. Version 4 maps to `Gather`.
- `RecipeResolver.leftoversSettings` passes `Gather`. Its KDoc says so: "...the same back-to-back timing, and the
  `Gather` day end (a leftovers channel has no rules file to carry a boundary switch)."
- Tests: `ChannelRulesTest`, `RulesReaderTest`, `SchedulerSettingsFromRulesTest`, and `LineupCodecTest`.
- Update `RecipeResolverTest.leftoversSettingsEqualsAnEquivalentResolvedRecipe`
  (`core/engine/src/commonTest/.../lineup/RecipeResolverTest.kt:145`). Its expected value becomes the resolved
  recipe's settings `.copy(leftover = LeftoverPolicy.Gather)`, so it still holds once Task 6 resolves recipes at v5.

**Interfaces:**
- Produces: `LeftoverPolicy`, `SchedulerSettings.leftover`, and `RulesVocabulary.VERSION_5`.

- [ ] **Step 1: Write the failing tests:**
  - `aVersion5FileIsReadAndHeldAt5`.
  - `aVersion6FileIsNewer`.
  - `version5MapsToSpreadAndOlderToGather`, including a v4 studio-fold-2 file that stays `Gather`.
  - `aV4StudioFileStillUsesFold2AtV5`: `SourceMatcher` picks fold 2 for `formatVersion >= 4`, not `== 4`. If
    ADR-0026 built it as `== 4`, Task 2 changes it.
  - `normalisingAV5FileKeepsV5` and `writeVersionNeverLowersAV5File`.
  - `adr0026RewriteLeavesAV5StudioFileAlone`: the one-time rewrite and its invariant touch only files that say 3.
  - `leftoversGather`.
  - `aChannelWithNewerRulesIsKeptAsUnusableAndRoundTripsByteForByte` (`LineupCodecTest`, Review Focus 5). A stored
    fixture holds a starter and a custom channel whose rules are one version past `SUPPORTED_FORMAT_VERSION`. Both
    decode as `ChannelSource.Unusable` with their ID, numbers, name and state, and `encode(decode(x)) == x`. This is
    the path an older build takes with v5 rules. This test pins existing behaviour, so it may already pass.
- [ ] **Step 2: Run them and confirm they fail:**
  `./gradlew :core:rules:jvmTest :core:engine:jvmTest :core:domain:testDebugUnitTest`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew :core:rules:jvmTest :core:engine:jvmTest :core:scheduler:jvmTest
  :core:domain:testDebugUnitTest`. Expected: PASS, with the v1 to v4 vectors untouched.
- [ ] **Step 5: Commit**: `feat(rules): format version 5 maps to the spread day end`.

### Task 3: Best fit, plain and grouped (pure)

**Files:**
- Create: `core/scheduler/src/commonMain/kotlin/tv/stationbreak/core/scheduler/Breaks.kt`.
- Test: `core/scheduler/src/commonTest/.../BestFitTest.kt`.

**Interfaces:**
- Produces:
  - `internal fun bestFit(candidates: List<ScheduleItem>, budgetMs: Long, weightMs: (ScheduleItem) -> Long): List<ScheduleItem>`
  - `internal fun bestGroupedFit(candidates: List<ScheduleItem>, groupOf: (ScheduleItem) -> CanonicalId, budgetMs: Long, weightMs: (ScheduleItem) -> Long): List<ScheduleItem>`
  - Both return a subsequence of `candidates`, in candidate order.

Algorithm (it goes in the rules format verbatim):

```text
Common: w(x) = ceil(weightMs(x) / 1000); B = floor(budgetMs / 1000). MAX_FIT_CANDIDATES = 64.
        reach = IntArray(B + 1) of predecessor indexes (-1 = unset), reach[0] = start.
        Every program is at least minRuntimeMs (>= 1 s) long, so w(x) >= 1.
bestFit: pool = candidates with w(x) <= B, in candidate order; keep the first 64 of pool.
         For each kept x in order, for s from B down to w(x):
           if reach[s] unset and reach[s - w(x)] set: reach[s] = (x, s - w(x)).
bestGroupedFit (ordered modes; the group rule: within a group only a prefix, in candidate order, may be taken):
         Cut: walk candidates in order; once a candidate x of group g has w(x) > B, g is passed over and
           x and every later candidate of g are dropped (fillEnd's passed-over rule). pool = what remains, in
           candidate order; keep the first 64 of pool. Every dropped candidate comes after all kept candidates of
           its group, so the cut and the cap only ever remove a group's tail and each group keeps a prefix.
         Groups in order of their first kept candidate; a group's options = its kept prefixes of length 1..m.
         Process one group at a time against a snapshot of reach from before that group (so at most one option per
           group): for s from B down to 1, for each option o in increasing length, with W = sum of w over o:
           if reach[s] unset and s >= W and snapshot[s - W] set: reach[s] = (o, s - W).
Result: s* = largest set s; walk back to 0; return the items in candidate order.
```

Ties go to the set reached first when candidates are taken in order (the first write to `reach[s]` wins and is never
replaced). Ceil-seconds guarantees the real sum is ≤ budget. Memory is one `IntArray(B + 1)` per call
(≤ 86,401 ints).

- [ ] **Step 1: Write the failing tests:**
  - `itFillsExactlyWhenItCan`: [7, 11, 4] minutes with a 15-minute budget gives [11, 4].
  - `tiesGoToTheEarlierSet`: [5, 5, 10] with a 10-minute budget gives [5, 5].
  - `nothingFitsGivesEmpty`.
  - `onlyTheFirst64CandidatesCount`.
  - `theRealSumNeverExceedsTheBudget`: a property test.
  - `groupedFitOnlyTakesPrefixes`: group A [10, 3] and group B [4] with a 7-minute budget gives [4]. A's 10 is over
    the budget, so A is passed over at its first item and its 3 is never a candidate. [3, 4] would air a later episode
    after an earlier one that was passed over.
  - `groupedFitChoosesAmongSeveralPrefixesOfOneGroup`: group A [4, 3, 5] and group B [6] with a 12-minute budget
    gives [4, 3, 5] (12), not [4, 3] + [6] (13 is over budget) or [4] + [6] (10).
  - `theCapOnlyCutsAGroupsTail`: interleaved groups past 64 candidates. Each group's kept candidates are a prefix.
  - `a24HourBudgetWith64CandidatesRunsUnder50msOnJvm`: a timing guard.
- [ ] **Step 2: Run them and confirm they fail:** `./gradlew :core:scheduler:jvmTest --tests '*BestFitTest'`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them again.** Expected: PASS.
- [ ] **Step 5: Commit**: `feat(scheduler): best-fit selection for the day's leftover`.

### Task 4: Spread layout

**Files:**
- Modify: `core/scheduler/.../Breaks.kt` (placement).
- Modify: `core/scheduler/.../Day.kt`. `Timeline.layout` branches on `settings.leftover`, and `Gather` stays byte for
  byte the same. `Layout` gains `val runEnds: List<Long>`.
- Modify: `core/scheduler/.../DayTail.kt`. `layoutTail` gains a **required** `airedBefore: List<Aired>` (no default;
  `SelectionRepair.kt:428` is its one production caller, and the test `ReferenceSelectionRepair.kt:196` is updated).
  `data class Aired(val item: ScheduleItem, val endMs: Long)` lives in `Breaks.kt`, is public, and lists every
  programme that aired in `[dayStart, fromMs)` as the viewer saw it.
- Modify: `core/domain/.../repair/KeptWindow.kt:58-63` and `SelectionRepair.kt:414-430`. Build `airedBefore` from the
  same source as `KeptWindow.aired`:
  - the day's Program blocks before the window's `from`, with their `endMs`;
  - the window's clipped spans that are not `gone` and have an item, with the span's real `endMs`. This includes the
    gap-fill pieces of `filledSpans`.
  - `KeptWindow` exposes these as a list of (item ID, end) next to `aired`. Items are resolved from the day's pool,
    and an item no longer in the pool is left out, which is documented.
- Test: `core/scheduler/src/commonTest/.../SpreadDayTest.kt`.

**Interfaces:**
- Consumes: `bestFit` and `bestGroupedFit` (Task 3), `LeftoverPolicy` (Task 2), `ScheduleContent.Break` (Task 1).
- Produces:
  - `Aired`.
  - `internal fun spread(settings: SchedulerSettings, regular: List<ScheduleBlock>, runEnds: List<Long>, visit: List<ScheduleItem>, aired: List<Aired>, groups: GroupRules, layoutStart: Long, layoutEnd: Long): List<ScheduleBlock>`
  - `aired` = the airings in `airedBefore` plus every Program the regular layout placed, each with its end time
    (**before** any shift).
  - `runEnd(a)`: for an airing the regular layout placed, the end of its run (an entry of `runEnds`). For an
    `airedBefore` airing it is `a.endMs`. `Aired` gets no extra field.
  - `visit`: fillEnd's visit, starting at the first item of the unstarted run (`firstItem`), over the cycle's items
    `I` (only the runs not left out). For `shuffle` and `curated` it wraps: `I[(firstItem + v) mod |I|]` for
    v = 0..|I|-1. For ordered modes it stops at the cycle's end: `I[firstItem..|I|-1]`. When every run was placed,
    `visit` is empty.
  - `groupKey(item)`:
    - `curated` and `rotation`: `groups.groupId(item)`, the key the walk uses, so a film goes into its collection.
    - Other ordered modes: `item.groupId`, as `fillEnd` uses.
    - `shuffle`: the item's own ID.

How `Spread` works (normative; all divisions are `floorDiv` on non-negative longs):

```text
Identity: items are compared by canonical ID (exact string). "x is in aired", "not in Best", and groupKey equality
  all compare canonical IDs.
c = end of the regular layout (c = layoutStart when no run was placed); L = E - c.

Grid first (any mode, alignToGrid):
  slot(x) = runtime rounded up to the grid; budget G = E - nextMark(c).
  Best: shuffle/curated: bestFit(visit, G, slot). Ordered: bestGroupedFit(visit, groupKey, G, slot); its cut and
    cap are Task 3's, so a group is passed over at its first item whose slot is over G.
  Extra pass (keeps today's first-fit reach for a last item whose runtime fits but whose slot doesn't): after the
    Best items are placed (cursor = end of the last one, or c), scan the full visit in order and take the first
    eligible item x with nextMark(cursor) + runtime(x) <= E; add it at the end. Eligible:
      ordered modes: x is the item right after its group's chosen prefix in visit order, or, for a group with nothing
        chosen, the group's first item in the visit. (So nothing of a group is ever skipped before x.)
      shuffle/curated: x is any visit item not in Best.
    At most one item is added. If none is eligible and fits, nothing is added.
  Any mode: also compute fillEnd's own selection (today's grid first fit over the full visit, uncapped; for ordered
    modes with its passed-over rule). Keep whichever ends later; ties go to Best plus the extra pass.
  Place the kept selection as today's planned end does (each at the next grid mark, grid gaps as Slate). Then one
  Break [cursor, E) if cursor < E. No spreading. Stop.

Not grid — a candidate selection F (a subsequence of visit) is evaluated in stages:
  S1. R0 = L - sum(F); n0 = |F| for shuffle/curated, 0 for ordered modes.
      k = max(1, min((E - layoutStart) / BREAK_SPACING_MS, n0 + R0 / MIN_CARD_MS)).
  S2. Points. chosen = {c}. For i = 0, 1, .., k-2 in increasing order:
      t_i = layoutStart + (i + 1) * (c - layoutStart) / k.
      Among runEnds r in (layoutStart, c), r not in chosen, such that for every chosen p some unchosen runEnd lies
      strictly between r and p, take the r minimising |r - t_i|, ties to the smaller r, and add it to chosen.
      If there is no such r, skip t_i.
      After the loop, sort the points ascending. k' = number of points (>= 1). Breaks are indexed 0..k'-1 in that
      order, so break k'-1 is at c.
  S3. Place F.
      Ordered modes: no units. placed = sum(F) and R = L - sum(F). The fill goes after break k'-1 in F order, and
        break k'-1's card (if any) comes before it, so the day ends on programmes.
      shuffle/curated:
        units: in shuffle each item of F is one unit. In curated the items of F with one groupKey form one unit.
          Units are ordered by the smallest F-index among their items. Inside a unit, items are in cycle index order
          (their index in I, the walk's order), which differs from F order when the visit wrapped inside the group.
        constraint(unit, break b) holds when, for every aired a with groupKey(a.item) == groupKey(unit):
          curated: point(b) >= a.endMs + repeatGapMs.
          shuffle: point(b) > runEnd(a), i.e. at least one run boundary lies between the airing and the break.
        Times are pre-shift. Units placed earlier in S3 do not count as aired.
        Unit j (0-based, in unit order) is tried first at break j mod k', then at each later break in index order; the
        first that satisfies the constraint takes it; if none does, the whole unit is dropped.
        placed = sum of the placed runtimes; R = L - placed.
  S4. m = 0 if R == 0, else max(1, min(k', R / MIN_CARD_MS)). Card breaks: (j + 1) * k' / m - 1 for j = 0..m-1. Each
      card is (R / m / 1000) * 1000 ms; the last card also takes R - m * that.
  Inside a break: its units in the order they were placed, each unit's items in its order, then its card (ordered
  modes: break k'-1 is its card, then the fill). An empty break emits nothing. Every later block shifts by the
  break's length.

Choosing F:
  Ordered modes: two candidates, in this order:
    G        = bestGroupedFit(visit, groupKey, L, runtime);
    firstFit = today's non-grid fillEnd selection: scan the full visit (uncapped), keeping a running sum s from 0;
               skip x if its groupKey is passed over; take x when s + runtime(x) <= L, else pass over its groupKey.
    Run S1–S4 for each; keep the one with the larger placed sum; ties go to G.
  shuffle/curated: three candidates, in this order:
    U        = bestFit(visit items whose item is not in aired, L, runtime);
    A        = bestFit(visit, L, runtime);
    firstFit = today's non-grid fillEnd selection: scan the full visit (uncapped), keeping a running sum s from 0;
               take x when s + runtime(x) <= L.
    Run S1–S4 for each; keep the one with the largest placed sum; ties go to the earliest in the order U, A,
    firstFit. firstFit covers what the 64-candidate cap leaves out of a large pool.
```

- [ ] **Step 1: Write the failing tests.** Each one also asserts that the day tiles `[S, E)` and has no `Slate` unless
  it is a grid channel.
  - `aCuratedDaySpreadsItsShortsAcrossTheDay`.
  - `aCuratedSameSeriesShortWaitsForItsGroupsRepeatGap` (Review Focus 1).
  - `aCuratedShortNeverPrecedesAnEarlierItemInItsGroupsWalkQueue` (a collection): it asserts walk-queue order, read
    from the walk, and does not assume release order. The walk rotates each queue to `key mod size`.
  - `aCuratedUnitThatWrapsTheCycleAirsInWalkOrder`: the visit wraps inside one group, so F holds episode 9 before
    episode 2. The unit sits at the F-index of episode 9 and airs 2 then 9.
  - `aCuratedSameSeriesShortWithNoValidBreakIsDropped`.
  - `aShuffleShortNeverReplaysStraightAfterItsAiring`.
  - `aDayWithNoBoundaryBeforeCGetsOneClosingBreak`, and the variant where `c == layoutStart` (Review Focus 2).
  - `anAllMovieDayHasNoAdjacentBreaksAndMinuteCards` (Review Focus 3).
  - `aShuffleShortAiredBeforeFromNeedsABoundaryAfterItsEnd`: for an `airedBefore` airing, the run end is `endMs`.
  - `placedSumDecidesBetweenUnairedAndAll`.
  - `firstFitWinsWhenTheCapLeavesTheBestShortsOut`: a pool with more than 64 candidates. Ties keep U, then A.
  - `aGridExtraPassNeverSkipsAnEarlierEpisode`: ordered grid, where the extra item is right after its group's prefix.
  - `aGridShuffleKeepsFillEndsSelectionWhenItEndsLater`.
  - `anOrderedChannelUsesGroupedBestFitInOrderAtTheEnd`: the card comes before the fill, and the day ends on a
    programme.
  - `anOrderedChannelNeverFillsLessThanFirstFit`: the 64 cap cuts the grouped best fit. firstFit wins, and ties keep
    G. There is a grid variant.
  - `anOrderedGridChannelKeepsItsEndOnTheGrid` and `aCuratedGridChannelFillsBySlotLength`.
  - `aOneHourEpochHasOneBreak`.
  - `gatherIsUnchanged`.
  - `aRepairTailSpreadsOnlyAfterFromAndHonoursEarlierAirings` (Review Focus 4).
- [ ] **Step 2: Run them and confirm they fail:** `./gradlew :core:scheduler:jvmTest --tests '*SpreadDayTest'`
- [ ] **Step 3: Implement** `spread`, the `layout` branch and the `layoutTail` parameter, then wire `KeptWindow` and
  `SelectionRepair` to pass `airedBefore`.
- [ ] **Step 4: Run** `./gradlew :core:scheduler:jvmTest :core:domain:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit**: `feat(scheduler): spread the day's leftover as breaks (format v5)`.

### Task 5: Rules format v5 text and vectors

**Files:**
- `docs/rules-format.md`:
  - The header (line 3): version 5.
  - "Format version": v5 adds no fields, keeps v4's studio fold 2, and changes the end of the day.
  - "Output" (~1112-1117): a third block content, the **break**, which has no item and no media offset. A break is
    never next to another break. On a grid day a slate never directly precedes the closing break. Versions 1 to 4
    never emit one.
  - A new "The spread end of the day (version 5)" section after "The planned end of the day" (1465). It holds Task 3's
    algorithm, Task 4's pseudo-code verbatim, and one worked example. The repair rule (kept-window airings count)
    stays **out** of the rules format, by ruling: it lives in ADR-0025 only (Task 7).
  - In that section, extend the "a cap can be exceeded in three places only" list (~1504-1510) for version 5. A
    fourth place is the spread breaks of a capped `shuffle`: a short placed in a break can sit next to an episode of
    its own series, so a cap can be exceeded there.
  - "Verify your client" item 9 (1947): `formatVersion` 6 is not scheduled.
- Create: `docs/rules-format/vectors/lineup-v5.json`. The file implies `Spread`, so it has **no `dayEnd` key**. Blocks
  can carry `"break": true` (a JSON boolean). Cases:
  - "spread curated"
  - "spread curated same-series short"
  - "spread curated collection"
  - "spread curated wrapped group" (the visit wraps inside one group; the unit airs in cycle order)
  - "spread all movies"
  - "spread one film"
  - "spread aired short"
  - "spread ordered grouped"
  - "spread grid curated"
  - "spread grid ordered"
  - "spread one-hour epoch"
  - "spread shuffle" (a plain shuffle spread)
  - "spread S2 point skipped" (a t_i with no valid runEnd)
  - "spread S2 nearest tie" (two runEnds equidistant from t_i; the smaller wins)
  - "spread fewer cards than breaks" (m < k')
  - "spread short remainder" (R < 60 s, one card)
  - "spread first fit beats best fit" (more than 64 candidates; firstFit beats U and A)
  - "spread grouped several prefixes" (bestGroupedFit picks among several options of one group)
- Create: `vectors/LineupV5VectorFile.kt`, registered in `VectorFiles.files`. `DayCases.kt` gets a `v5` flag (the
  caller sets `Spread`), and `block()` reads `"break"`.
- Create: `LineupV5VectorsTest.kt`, modelled on `LineupV3VectorsTest`. Include `theFileCoversWhatThePlanAsks` over the
  case names above.

- [ ] **Step 1: Write the vector file by hand from the doc text alone.**
- [ ] **Step 2: Run** `./gradlew :core:scheduler:jvmTest --tests '*LineupV5VectorsTest' --tests '*VectorFilesInSyncTest'`.
  Expected: PASS. A mismatch is resolved against the text, never by copying code output.
- [ ] **Step 3: Commit**: `docs(rules-format): version 5, the spread end of the day, with vectors`.

### Task 6: Starters and new channels move to v5

**Files:**
- `core/engine/.../lineup/RecipeResolver.kt:21,82,94`. ADR-0026 has made line 94 write at `writeVersion`, and the
  re-resolve (`LineupPlanner.kt` ~374) keep "the higher of the stored and the written version". Task 6 sets
  `LATEST_FORMAT = 5`, and a resolved starter is written at `maxOf(5, writeVersion)`, the same floor as a new
  channel. The re-resolve rule then keeps 5 (5 ≥ any stored 3 or 4). Nothing else changes in the resolver.
- `StarterCatalog.kt:26`: `VERSION` + 1 (from whatever ADR-0026 left it at), with a doc line explaining why. The
  recipes are unchanged.
- `LineupPlanner.kt`: **no new code path.** The bump makes `newer` true:
  - Keepers (protected starters and on-air starters, up to CAP) take `withNewRules` and switch at the boundary.
  - Non-keepers (not airing) switch at once.
  - The `rehinted` re-resolve takes the same branch, so it also switches only keepers at the boundary.
  - ADR-0026's one-time v3→v4 studio rewrite is immediate, and its own concern. Then a studio starter goes v4→v5
    through this keeper path like any other.
- `core/domain/.../lineup/builder/DraftRules.kt`: `toRules(draft, utcOffsetMinutes, target: RulesTarget)`, with
  `sealed interface RulesTarget { data class NewChannel(val carry: ChannelRules?) : RulesTarget; data class
  Edit(val previous: ChannelRules) : RulesTarget }`. `RulesTarget` is not a second version policy. It only tells
  `toRules` whether this is a new channel. The version is always ADR-0026's `writeVersion` (with Task 2's v5
  clause), plus one floor:
  - `NewChannel` → `maxOf(5, writeVersion)`.
  - `Edit` → `writeVersion`, reading `previous` as ADR-0026 says. So a v5 file stays 5, a v4 studio file stays 4,
    and a v1 to v3 file gets ADR-0026's result (the lowest version, or 4 for studio features after its gate).
  - ADR-0026's "first attempt at the write version, branch on requires 3 or more" change to `toRules` is taken as
    built. Task 6 adds only the floor.
- `ChannelBuildManager.create` passes `NewChannel(origin rules or null)`, and `save` passes `Edit(stored)`.
- `ChannelPreviews.kt:181` passes the same target as the flow it previews.
- Tests: `LineupPlannerMigrationTest`, `RecipeResolverTest`, `DraftRulesTest`, `ChannelPreviewsTest`.
- Docs in this PR (moved from Task 7, so the override is on main with the code that uses it): the release-rule and
  fold-2 text in ADR-0026 (or a short new ADR that ADR-0026 links to), described in Task 7.

**Release precondition (check before merging):** `SUPPORTED_FORMAT_VERSION == 5` and the v5 vectors
(`lineup-v5.json`) are on main, and ADR-0026's read-4 code is merged.

**Interfaces:**
- Consumes: `RulesVocabulary.VERSION_5` and the `writeVersion` clause (Task 2), and ADR-0026's `writeVersion`.
- Produces: `RulesTarget`, and `DraftRules.toRules(draft, utcOffsetMinutes, target)`.

- [ ] **Step 1: Write the failing tests:**
  - `everyStarterResolvesAtVersion5`, including a studio starter.
  - `anOnAirUneditedStarterIsAtItsOldVersionTodayAndV5Tomorrow` (old = 3, or 4 for a studio starter).
  - `aShelvedUneditedStarterIsV5AtOnce`.
  - `anEditedStarterKeepsItsVersion` (3; the "4 after ADR-0026's rewrite" case is added by whichever lands second).
  - `aNewChannelIsWrittenAtV5`, including a new studio channel.
  - `aNewStudioChannelBeforeAdr0026sGateIsV5AndMatchesUnderFold2` (with `writeVersion` giving 3 for studio features).
  - `aCopiedChannelIsWrittenAtV5`.
  - `aReResolvedStarterKeepsTheHigherOfStoredAndWritten` (ADR-0026: a stored 5 is never written lower).
  - `savingAV2CustomChannelStillLowersToV1WhenItCan`.
  - `savingAV4StudioCustomChannelStaysV4` (ADR-0026; not raised to 5).
  - `savingAV5CustomChannelStaysV5`.
  - `aCopyPreviewLaysOutWithSpread`.
- [ ] **Step 2: Run them and confirm they fail:** `./gradlew :core:engine:jvmTest :core:domain:testDebugUnitTest`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew check`. Expected: PASS.
- [ ] **Step 5: Commit**: `feat(lineup): starters and new channels use format version 5`.

### Task 7: Record the decisions

**Files:**
- `docs/adr/0022-rules-format-v2.md`: an addendum saying new channels are written at v5 on purpose, as an exception to
  "lowest version". Existing channels keep the rule.
- `docs/adr/0002-determinism-scope.md`: a one-line note that v5 stays stateless.
- An ADR note on the beyond-CAP edge: an on-air starter past the keeper CAP switches at once, as it does for any
  catalogue bump.
- The same note records two side effects of the `StarterCatalog.VERSION` bump. On a selecting catalogue it puts the
  lineup in `Mode.Hold` until a full collection pass (`PlannerSelection.kt:39-42`). On synced calls, unedited starters
  pick up v5 through `rehinted` even without the bump (still through the keeper path, `LineupPlanner.kt` ~372-385).
- `docs/adr/0025-repairing-today.md`: the `-2` break sentinel stays inside lineup file version 4 (the lineup file's
  own version, not the rules'), as an explicit exception to the codec's "any schema change bumps VERSION" rule
  (owner decision 2026-11-13, option a). An older build drops the record and loads the lineup. State the real radius: `RepairsCodec.decode` throws on the first `-2` row, and
  `LineupCodec.repairsOf` drops the **entire** per-TV repair record (`TodayRepairs.NONE`). So every channel's
  selection repairs and lost fixes on that TV are gone until the next repair. Also record the repair rule here, which
  is not in the rules format: `airedBefore` (the kept spans as the viewer saw them, with gap-fill pieces) counts for
  the group, repeat-gap and shuffle rules of the tail.
- A downgrade note (in the same ADR-0022 addendum): an older build reads rules only up to format version 4 (ADR-0026)
  or 3. So after a downgrade, every v5 channel (migrated starters, and channels created on this build) is
  `ChannelSource.Unusable` and off air. The older build keeps it byte for byte, and it returns on upgrade. This is
  accepted. It is the behaviour every format bump has, and it is pinned by
  `aChannelWithNewerRulesIsKeptAsUnusableAndRoundTripsByteForByte` (Task 2).
- **In the Task 6 PR, not after it:** the v5 release rule goes in ADR-0026 (or a short new ADR that ADR-0026 links
  to), not in ADR-0022. It records: the owner override of ADR-0026's release gate for v5 only (writing 5 ships with
  the Android build that reads it, without waiting for Vega OS, Roku or a check of every TV); that v5 brings fold 2 to
  studio starters and new studio channels ahead of ADR-0026's gate; and the cost as stated under Release rule. ADR-0022
  keeps only the "new channels at 5" exception.
- The ADR-0022 addendum also records the version map:
  - 4 is ADR-0026's studio fold 2;
  - 5 is fold 2 plus the spread end;
  - `writeVersion` never lowers a v5 file;
  - new channels and starters have a floor of 5.

- [ ] **Step 1: Write the notes**, linking the spec.
- [ ] **Step 2: Commit**: `docs(adr): format v5 for new channels, the spread day end`.

### Review gates

- `crew:architecture`: a fresh re-review of this revision before building, and code review of Tasks 1, 2, 4 and 6.
- `crew:data`: code review of Task 1 (the `KeptContent`, the `RepairsCodec` sentinel, the lineup file staying at v4).
- `crew:frontend`: builds or reviews the guide and live parts of Task 1.
