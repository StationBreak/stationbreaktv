# Lineup: the viewer's channels take no seats, the catalog's gaps close, and an audit measures both

**Area:** `core:engine` (planner, selection, catalog, an audit compilation), `feature:channels` (shelf reason copy).
**From:** dogfood, 2026-11-15. **Owner decision:** a v1 requirement coming out of the 14-day trial (`release:fire-v1`,
#752 track G). Three issues, worked in this order: the audit tool, the seat rule, the catalog gaps.
**Changes:** [ADR-0023](../adr/0023-lineup-curation.md) "The lineup keeps its cap of 50" and the keeper bound, by an
addendum. `crew:architecture` reviewed two drafts: the first was blocked on the meaning of "pinned" and on a
rules-format version, the second came back CONCERN, and this revision takes its findings in. The owner approved the seat
definition in §3 on 2026-11-15.

## 1. What the viewer saw

- **Adding channels shelves the planner's picks.** The planner's room is `LineupDefaults.CAP - channels.count {
  takesRoom }` (`PlannerSelection.kt:110`), and every channel that is on takes room except the planner's own Free picks:
  a pinned channel, a custom channel, an edited starter and the two leftovers channels. Switching on five channels gives
  55 for the rest of the day; at the next boundary five of the planner's picks go to the shelf with "Fits, but stronger
  channels filled the lineup". Switching on is never refused (`LineupEdits.kt:144`), so the cost shows up only at the
  boundary.
- **A custom channel makes a near-duplicate and loses a different channel.** Custom channels never enter the curation
  (`roleOf` returns null for a non-starter; `LineupDays.curationChannel` is null for them). A starter that covers the
  same titles keeps them as homes and stays strong; the starter shelved for the custom channel's seat is the planner's
  weakest, usually an unrelated, distinct one.
- **Some titles have no home.** A title is a candidate only where a recipe's pool holds it (`placeHome`,
  `Curation.kt:220`). Examples on a real library:
  - an adult animated sitcom with no adult-animation tag and no Adult Swim, Comedy Central or FX studio. Laugh Track and
    Comedy County exclude Animation, and Cartoons After Dark's pool needs that tag or studio;
  - a sitcom whose genres include one beyond Comedy, Family and Romance (Laugh Track's `notGenres`);
  - a horror film when the horror starter is too thin to qualify (12 usable hours, 6 titles): it lands on a thriller
    channel or in Odds & Ends.

The owner's view of the cap: its reasons (build time on the oldest TV, keeper copies for 50) are sound, but it charges
them to the viewer's own choices.

## 2. The audit tool (first)

A command-line run of the real planner and curation on a library snapshot that explains the result. It is the yardstick
for sections 3 and 4, and the stand-in for "someone else's server".

- **Shape:** an extra JVM compilation in `core:engine` (`jvmAudit`, associated with `main` so it sees `internal` code)
  and a `JavaExec` task. No new module, no public explain API, nothing for feature or app modules to call.
- **Scope: the planner's view.** It runs `LineupPlanner.plan` and the curation as the planner does, without the rating
  ceilings and late-on pools of a built day (`LineupDays.curationChannelOf`). Its output says so. What a day airs is out
  of scope.
- **The score terms:** `fitPointsQ16` stays the only sum on the hot path, which allocates nothing. A sibling returns the
  per-term values (specificity, genre, tag, studio, anchor, kids, format, tone, decade) from the same constants, for the
  audit only, and a test holds that the terms add up to `fitPointsQ16` for every lane and title of the profile libraries.
- **Input: a library snapshot file**, a new format defined and versioned with the tool (`audit-snapshot`, version 1):
  every field of `RuleSnapshot`, `RuleItem` / `ScheduleItem` (production year, premiere date, season and episode numbers,
  created time, library ID included), `RuleSeries` and `RuleCollection`, and `collectionsSynced`. No media, no server
  address, no account. The jvmAudit compilation brings its own serialization dependency; `commonMain` stays without one. Optionally a list of the viewer's switch-ons and custom
  channels' pools, so section 3 can be shown with them (the lineup file itself is read by `core:domain` and is not an
  input).
- **Output, as text and JSON:**
  - per title: its home, the runner-up, both scores by term, its guest spots, or why it has none (no lane's pool holds
    it, and which source or filter left it out);
  - per starter: on or off, its shelf reason, usable hours, titles, anchors and selection score;
  - per pair of channels that are on: titles both air, and hours;
  - totals: titles with no home by cause, titles per channel, channels per title.
- **Profile libraries**, generated in code with invented titles and committed: a sitcom household, a horror fan, a kids'
  household, an anime viewer, a broad 1,900-title library and a thin 200-title server. Each carries the cases it is there
  to test (an adult animated sitcom with no studio match, a small horror shelf, a sitcom with an extra genre). Their
  output is pinned, so sections 3 and 4 show their effect as a diff.
- **The owner's library:** a small exporter script under `tools/audit-export/`, run by the owner on his machine. It reads
  the server address and API key from the environment, never from a file in the repo, and writes the snapshot to a
  folder outside the repo (also in `.gitignore`). Nothing from that library is committed or named in issues, PRs, ADRs or
  fixtures. The app is not changed for the export, and `data:jellyfin` is not used. The exporter applies the app's own
  acceptance rules (`ScheduleInputs.read`'s drops, `RuleItemMapper`, `RuleInputs.series`, the extras filter), written
  down with the format, and a fixture test runs a `data:library`-built snapshot and its exporter equivalent through the
  audit and compares the output.

## 3. The seat rule

**The planner's own picks are capped; a channel it would not have picked costs no seat.**

### What a seat is

The viewer's mark `chosenByViewer` is also set by a renumber and by a swap (`LineupEdits.kt:198-219`), and the inference
for older files sets it generously (`LineupPlanner.kt:476-492`). So "pinned" cannot mean "sits on top", or renumbering
one pick would add a channel at the next boundary. The rule is worked out from the selection instead, with no new field:

Each round of `Selection.select`:

1. Every title is homed over all the candidates left (pinned and free), as now.
2. Every candidate that qualifies is scored from that homing, pinned ones included (today `scoresOf` skips pinned ones).
   A pinned starter that does not qualify has no score.
3. One total order: score, then `CHANNEL_ORDER`.
4. A pinned starter **inside the top `PLANNER_PICKS`** of that order uses a seat. One **outside** it, or with no score,
   sits on top and costs no seat.
5. The free places are `PLANNER_PICKS` minus the pins inside the top, worked out again each round; the planner fills them
   with the best free starters.

- **Opt-in pins** take part in the homing but not in the ranking: the planner never picks an opt-in starter, so one the
  viewer switched on always sits on top.
- Custom channels, edited starters and the leftovers channels never enter the selection and always sit on top.
- It is integer and ordered, so deterministic. A free starter dropped in a round re-homes everything, so a pin can move
  in or out of the top between rounds; the rounds still settle (`Selection.kt:24-30`), and the property test covers the
  pinned ranking.

**What it does to the count.** The lineup is the planner's `PLANNER_PICKS` plus the custom, edited, opt-in and
outside-top pinned channels that are on, plus the leftovers. Renumbering or swapping a pick that stays inside the top
changes nothing.

A pinned starter still takes part in homing. One that takes titles from a planner's pick can leave that pick too thin;
it then goes to the shelf as "Too little fits here once every show has its best home", which is the right channel to
lose.

### Code

- A named constant `PLANNER_PICKS` (48) replaces the derived `CAP - leftovers` in Select mode. `CAP` stays as the
  legacy rule's constant for the ranked paths and the inference. The Select-mode KDoc that says "together at most CAP"
  (`LineupPlanner.kt:29-45, 113, 122, 145, 510, 578-583`, `RecipeResolver.kt:45-48`) is rewritten; `:471` stays.
- `PlannerSelection.refreshed` and `Selection.select`: the rule above.
- `PlannerSelection.firstChoice` and `LineupPlanner.chosen` (`:260-261`, the first choice and a reset): room is
  `PLANNER_PICKS`, and kept custom channels sit on top, so a reset and the next boundary agree.
- `LineupPlanner.added` (`:418-422`): a numbered leftovers channel is always on.
- The ranked paths (`switchedOn` `:438`, the ranked branch of `chosen`) run only for a catalog that does not select by
  identity, and keep the old rule, as `withInferredChoices` (`:476`) does.

### Keepers

Keepers (previous rules kept until the boundary) get their own bound, `KEEPER_COPIES` = 60, used by `previousKeepers`
(`:546-547`) and `keeperRoom` (`:593`) in place of `CAP`. The per-copy budget stays 3,084 bytes: the room comes from the
catalog's headroom, `MAX_CATALOG_STARTERS` 120 to 110 (95 recipes today, plus the two leftovers), since (1 MiB / 2) /
(110 + 60) = 3,084. No stored rules change on the day of the update. The cost is catalog growth room (15 more recipes,
not 25). A new test holds `StarterCatalog.recipes.size <= MAX_CATALOG_STARTERS`, so a later catalog cannot break the
file bound silently; the stale "97" counts (`RecipeResolver.kt:38`, `BoundaryRulesTest.kt:333`) are corrected.

Past 60 starters on, a re-resolve can change today on the rest, as ADR-0023 already documents past 50.

### Build time and file sizes

Measure, through the test session on the Insignia, the day build, the restore and the guide open at 50, 60, 75 and
every starter on (95, plus the leftovers and a few custom channels), against the 10% bar, and the worst-case days file
against `FileDayStore.MAX_BYTES`, whose "50-channel day" comment is updated. The warning threshold comes from those
numbers: the first count whose day build passes the bar is where the Channels screen starts to say "More channels make
the guide slower to get ready" on a switch-on. Nothing is shelved to make it faster.

### Known effects (for the addendum)

- **The first boundary after the update** adds one planner pick for each custom, edited, opt-in or outside-top pinned
  channel that is on, since those stop costing seats. That includes the inference's pins of a starter the older
  planner put on that no longer fills.
- **Drift at the margin.** A pin near rank 48 can move in or out of the top as the library changes: out, it sits on top
  and the planner fills its seat; back in, it takes a seat again and the 48th free pick leaves. A renumber that un-leaves
  a leaving pick makes an outside-top pin the same way.

- A downgrade to a build before this change reads the lineup with its old rule (room = 50 minus pinned and leftovers) and
  shelves planner picks at the boundary.
- "Fits, but stronger channels filled the lineup" now means only that the planner's own picks are stronger.

### Tests

New: switching on five shelf starters at a full lineup shelves none of the planner's picks at the boundary; a custom
channel shelves none; renumbering or swapping picks that stay inside the top leaves the on-count unchanged; the first
boundary after the update grows the lineup by exactly the custom, edited, opt-in and outside-top pinned channels on; an
opt-in pin always sits on top; a reset with kept custom channels and the next boundary agree; a switch-on that thins a
planner's pick below qualifying shelves that pick as too thin.

Changed (they pin the old rule): `LineupPlannerCurationTest` (`theCapCountsPinnedAndCustomChannels`,
`aLeavingChannelDoesNotTakeRoom`, `thePreviousRulesStayWithinTheCap` and `:335`,
`noRoomShelvesEveryUntouchedStarterAtTheBoundary`), `LineupPlannerKeptTest.keptOnChannelsCountTowardTheCap...`,
`LineupPlannerTest` (`atCapNewQualifierWaitsOnShelf`, `newLeftoversRecipeAtCapWaitsOnShelf`), `LineupPlannerPropertyTest`
(`on.size <= CAP` becomes "selected free and in-top pinned <= PLANNER_PICKS"),
`BoundaryRulesTest.withMoreThanTheCapOnOnlyTheLowestNumberedKeepPreviousRules`, `LineupPlannerMigrationTest:383`,
`LineupPlannerViewerChoicesTest:57-68`, `LineupEditsTest:146-149`, `RecipeResolverThemeTest` (if the budget's
derivation moves), `LineupServiceResetKeepsCustomTest`, `LineupCodecV3Test`'s worst cases.

### Free and Pro (#793)

Owner decision, 2026-11-15: this rule replaces the cap of 50 for every viewer, and #793 adds the free-tier gates in v1.

- **Free:** the planner's lineup (`PLANNER_PICKS` plus Odds & Ends, 50) and up to 5 custom channels on top, so 55 at
  most. Copying a starter to customise it counts toward the 5. Shelved starters can be seen and browsed, but switching
  one on is Pro. On free, only custom channels sit on top.
- **Pro (later):** switch-ons on top of the planner's picks, as this section defines them, and more than 5 custom
  channels.
- The rule is built and tested in full in v1 (#785). The gates are #793's, behind one Pro check that returns false in
  v1. #785 adds no gate.
- The timings at 60, 75 and every starter on measure the Pro worst case. Free tops out at 55. The switch-on warning only
  ever shows on the Pro path.
- **Still open in #793:** whether a free viewer can switch a starter off, hide it, renumber or swap it, edit one in
  place, or switch on an opt-in starter. A renumber or swap that stays inside the top already costs nothing under this
  rule, whatever the answer.

### Not in scope

A custom channel claiming its titles as homes. With this rule a custom channel costs no seat, and the remaining overlap is
the viewer's choice. The audit's overlap report measures it; if it is large, a hint in the builder ("Starport already
airs most of this") is the next step, not a change to homing.

## 4. The catalog gaps

Each fix is judged by the audit: titles with no home by cause, before and after, on every profile library. No rules
format change is planned.

1. **Adult animation.** Add the `Animation` genre to Cartoons After Dark's sources, with `notGenres = ["Family"]` on its
   filter. The grown-up hold drops only what is made for kids and passes PG, so without the Family exclusion a PG
   animated family film from a studio off the kid list, or unrated made-for-kids animation, would be homed on "Animation
   made for adults". The audit measures what still comes in. A minimum-rating filter is held in reserve; if needed, it is
   a **new rules format version (6)** with its documentation, vectors, `requiredVersion` / `writeVersion` and the
   ADR-0026/0027 release rule, checks both the item's effective rating and the series' rating as `maxRating` does
   (`PoolEvaluator.kt:26-27`), fails an unrated item, and runs in both `PoolEvaluator` and `Leftovers`. Neither hold's
   version changes.
2. **A sitcom with one extra genre.** Narrow Laugh Track's `notGenres` to the genres that mean "not a sitcom" (for
   example Animation, Documentary, Reality, News, Talk), measured on the sitcom profile. Comedy County's twist genres
   (Crime, Action, Mystery, Fantasy, Science Fiction, Music and the TV forms) stay in Laugh Track's `notGenres`, so Laugh
   Track (102) does not take Comedy County's (528) titles on ties; the audit names Comedy County as the channel to watch.
3. **A niche starter too thin to qualify.** An explicit per-recipe field, a lower qualifying bar (for example 6 hours and
   4 titles), set on the recipes that are the catalog's one lane for their theme (the horror starter among them, which
   shares the Horror genre with two monster channels, so a "no other recipe has this genre" rule would not reach it).
   It is recipe data, frozen in the catalog manifest, so catalog 5 covers it and `curation-1` does not change. It depends
   on the catalog and the library only, never on which channels are on, so the rounds still settle. Guesting such titles
   on another channel is ruled out: a curation only ever takes items out of a pool (`Curation.kt:17-18`).

Recipe changes ship as catalog version 5 through `StarterCatalog` (the `CATALOG_V3_RECIPES` list, edited), so unedited
starters take them at the next boundary as any catalog update does; keys and numbers do not change.
`StarterCatalogTest` and the frozen manifest (`CatalogManifestTest`) follow.

A title with no lane at all still airs on Odds & Ends; that does not change.

## 5. Release

This work does not restart the 14-day dogfood clock (owner, 2026-11-15).

## Work plan

Three issues, each labelled `release:fire-v1`, each in its own worktree off `origin/main`:

1. **Lineup audit**, #784 (`core:engine` `jvmAudit` compilation, the scoring's terms, the snapshot format, profile libraries,
   the exporter script). First, so 2 and 3 are measured.
2. **The seat rule**, #785 (`core:engine` planner and selection, keepers, `feature:channels` reason copy, ADR-0023 addendum,
   Insignia timings and file sizes through the test session).
3. **The catalog gaps**, #786 (`core:engine` recipes shipped as catalog 5, the per-recipe qualifying bar), judged by the
   audit.
