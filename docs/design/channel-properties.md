# Channel properties: a sheet showing what a channel is and what feeds it

**Replaces the body of #631.** Area: `feature:guide`, `feature:channels`, `core:domain`. Blocks the 14-day dogfood (EPICS E2 gate).
**Target mock:** `docs/design/channel-properties-target.png` (source `channel-properties-target.html`). Uses the tokens from `docs/design/guide-visual-overhaul.md` and the marks from `docs/design/channel-identity.md`.

## Why

A viewer should be able to answer, without leaving the TV, "what is this channel, why is it showing this, and why is it dark today?" Today the only per-channel information is the name, number and one reason line inside channel management, and nothing in the guide itself. For the dogfood this also needs to expose enough of the schedule inputs that two devices showing different lineups can be compared (EPICS: cross-device agreement is observed during Phase 2).

## Decisions (made here so the work can start; flag in the PR if any proves wrong)

1. **Where it opens — in the guide, from the channel column.** Press **Left** from a row's earliest cell (the one that starts at `originMs`, where Left is currently a no-op) to move focus onto the channel; the channel cell shows a 2dp accent outline and the details panel switches to the channel's identity. **OK** on the channel opens the properties sheet. This is the "C6" idea; it matches how cable guides behave and keeps the viewer in the guide. The channel column stays non-focusable in the Compose sense (ADR 0008 holds): this is a new `GuideFocus` position, not a new focusable.
2. **Also reachable from channel management.** `actionsOf()` gains **About this channel** so the same sheet opens from the Channels list. One composable, two entry points.
3. **Not a full screen — a right-hand sheet over the dimmed guide.** 380dp wide in the 960×540 layout, full height, `#141518` with a 1dp `line` left edge. Back closes it and returns focus to where it came from. The guide and picture stay where they are behind it.
4. **Sheet content shows what the viewer can reason about, in plain words. No JSON, no wire names.** Sources, filters and ordering are humanised with the same formatter the builder's edit sheet uses (`SheetRow.Sources` / `NarrowDown` / `PlayOrder` / `Kept` lines), so the two never disagree.
5. **The seed is shown, as a "Schedule code".** `fnv1a64`/first 32 bits of `ChannelRules.seed`, formatted `XXXX-XXXX`. It is the one thing that lets two households (or two devices) confirm they're on the same lineup. It is read-only; Reshuffle stays in the builder.
6. **Actions on the sheet reuse `actionsOf()`** (Watch, Switch on/off, Customize or Edit, Change number, Default number). No new actions. The sheet is informational first; actions are there so the viewer doesn't have to go hunting.

## Sheet layout (top to bottom)

| Block | Content | Source |
|---|---|---|
| Identity | Mark (50×28dp box at 2× = 100×56dp), `CHANNEL <n>` 11sp `textSecondary`, name 20sp SemiBold, tags row: `BUILT-IN` / `CUSTOM` / `LEFTOVERS` and, when kid-safe, `KIDS · UP TO <rating>` in `#FFD166` | `ChannelSummary.kind`, `Recipe.kidSafe`, `maxRating` |
| Personality | Recipe `personality` in quotes, 11sp italic `textSecondary`. Custom channels: omitted. | `StarterCatalogView.recipes[key].personality` |
| **What it plays** | Key/value rows: Genres, Tags, Years, Studios, Collections/Series/Items (named, not IDs), Libraries; **Always** = theme anchors; **Never** = exclusions + "anything rated above X"; **Narrowed to** = filters (kind, runtime, not-genres). Omit empty rows. | `ChannelSource.*.rules.sources/exclusions/filters`, `Recipe.theme.anchors` |
| **How it runs** | Order (Shuffle / In order / Rotation / Chronological / Random in order / **Programmed** for Curated) with "· new day at <broadcastDay.start>"; block length when set; Schedule code | `ChannelRules.ordering`, `timing`, `seed` |
| **What it holds** | (#757) The hours line, then **Home to:** (the titles whose home is the channel) or, for a channel with none, **Mostly:** (the six titles with the most hours): the same lines, from the same `ChannelHoldings` value, as the guide's channel box (`docs/design/guide-menu-and-channel-holdings.md`, section 2). Only home shows are listed; the hours and the title count are totals of everything the channel airs. A day whose homes are not known (built before they were kept) shows the hours line alone. Omitted for a channel that is off, hidden or has nothing today. | `ChannelProperties.holdings` (`ChannelHoldingsReader`) |
| Status | One line with a 5dp dot: green **On since <date>** · *showing today* / *hidden today: <HiddenReason text>*; amber **Not switched on** · *<ShelfReason text>*; grey **Off** · *hidden by you* | `ChannelSummary.state/onSince/shelfReason`, `ScheduleSource.airings().hidden` |
| Actions | Buttons 11sp Medium, 3dp radius, focused = accent fill + `focusText` | `actionsOf()` |
| Hint | `BACK` Return to the guide / to Channels | |

Type and colour tokens are the guide's (`docs/design/guide-visual-overhaul.md`). Reuse `channels_reason_*`, `channels_shelf_*`, `channels_label_*` strings; add the handful of new ones (`channel_props_*`).

## Data: one new domain read

`ChannelSummary` deliberately hides rules. Add to `core:domain`:

```kotlin
data class ChannelProperties(
    val summary: ChannelSummary,
    val personality: String?,          // starter recipes only
    val rules: ChannelRules?,          // null for Leftovers / Unusable
    val anchors: List<Anchor>,         // theme anchors, starters only
    val sourceNames: Map<Source, String>, // resolved names for Collection/Series/Item/Library ids
    val hidden: HiddenReason?,         // from ScheduleSource.airings(now)
)
interface ChannelPropertiesReader { suspend fun read(id: ChannelId, at: Instant): ChannelProperties? }
```

Implementation reads `LineupChannel.source` (`Starter.rules`, `Custom.rules`, `Leftovers.maxRating`), the recipe from the starter catalog, resolves names through the same Jellyfin lookups the builder preview uses, and asks `ScheduleSource.airings(at)` for the hidden reason. No writes.

## Guide model changes (`feature:guide`)

- `GuideFocus` gains a channel position (e.g. `cellIndex = -1` or a sealed `GuideFocus.Channel(channel)`). `GuideModel.move(Left)` from the earliest cell → channel position; `Right` → back to that cell; `Up/Down` move between channels staying in the column.
- `ChannelCell` draws a 2dp accent outline (inside its bounds) when its row holds the channel focus. Nothing else changes in the column.
- `DetailsPanel` in channel focus shows: mark large, channel line, name, personality, status line. (Same data as the sheet's top; no sources.)
  Since #757 it also shows what the channel holds (hours, and "Home to:" or "Mostly:"), read once focus has rested 200 ms on the channel; see `docs/design/guide-menu-and-channel-holdings.md`, section 2.
- `GuideViewModel.select` in channel focus → `GuideEvent.OpenChannelProperties(channel)`. The sheet is a slot like `ChannelsSlot`, so `feature:guide` doesn't depend on `feature:channels`.
- Hints row in channel focus: `OK` About this channel · `▸` Back to programs.

## Acceptance criteria

- [ ] From any row, Left at the earliest cell moves to the channel; the channel cell shows the outline and the details panel changes; Right returns.
- [ ] OK on a channel opens the sheet within 150 ms with the identity block populated; the "What it plays" block may load after (show the three-dot loading state used by `ProgramInfoOverlay`).
- [ ] The sheet shows correct, humanised content for: a starter with a theme (ch 8), a starter without (ch 122), a custom channel, a Leftovers channel (no rules: "Plays whatever nothing else does"), an Unusable channel ("Can't be read").
- [ ] Status line matches channel management for the same channel at the same moment (shared strings, shared `HiddenReason`).
- [ ] Schedule code is identical on two devices signed into the same server for the same channel, and changes after Reshuffle.
- [ ] Actions perform exactly what they do in channel management and the sheet reflects the result (e.g. Switch off → status turns grey) without closing.
- [ ] Back closes the sheet and restores focus (guide: the channel position; Channels: the row).
- [ ] No text under 10sp; all text ≥ 4.5:1 on `#141518`; screenshot test added for the sheet in both themes.

## PR split

1. `core:domain` — `ChannelProperties` + reader + humanising formatter extracted from the builder so both use it. Unit tests over the five channel kinds.
2. `feature:channels` — the sheet composable (`ChannelPropertiesSheet`) + "About this channel" action. Screenshot test.
3. `feature:guide` — channel focus position, outline, details-panel variant, hints, `OpenChannelProperties` event + slot wiring in `app`.

## Out of scope

Editing anything from the sheet beyond the existing actions; showing per-item schedules (that's the grid); a seed entry UI; the channel mark redesign (#571 follow-up, `docs/design/channel-identity.md`).

## Open for the owner

- Should Leftovers channels show their donor list ("plays what channels 4–80 don't")? Cheap if `LeftoversRecipe` exposes it; otherwise defer.
- Is "Schedule code" the right name for viewers, or should it be shown only in a Settings → Diagnostics context after dogfood?
