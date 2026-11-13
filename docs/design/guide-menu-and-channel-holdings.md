# Guide: the menu moves behind the Menu button, and the channel box shows what a channel holds

**Area:** `feature:guide`, `core:domain`, `feature:channels` (properties sheet).
**From:** dogfood feedback, 2026-11-13. Two changes, each its own issue and PR. Owner decisions are marked; flag in the PR if one proves wrong.

## 1. The guide's menu moves behind the Menu button

**Why.** The Channels / Settings / Exit row sits between the info panel and the grid, the two things a viewer reads, and takes about 28 dp the description could use.

**Owner decision:** the row is removed and the remote's Menu button opens a menu sheet.

- Remove the Channels / Settings / Exit row from the info panel. The panel's column keeps its height, so the description gets the freed lines.
- `KEYCODE_MENU` in the guide opens the menu sheet from anywhere, and closes it if it is open. **In full screen Menu stays Last Channel; that is not changed here.**
- Wherever focus moves to the tab row today (Back on the playing channel's cell, and Up from the top row if that exists), the sheet opens instead. Back in the sheet closes it and returns focus to where it came from.
- **Sheet:** left edge, 220 dp wide, full height, with the same surface, dimming and focus style as the channel properties sheet (`docs/design/channel-properties.md`). Rows 36 dp tall, 13 sp Medium: Channels, Settings, Exit. Channels has focus on open. Up and Down move, OK chooses.
- **Hint row:** add a `Menu` key cap with the label `Menu`; keep `Back · To the menu` where it shows today.

**Tests.** Menu opens and closes the sheet from a programme cell and from the channel column; Menu in full screen is still Last Channel; focus returns to where it was on close; the description gains lines when the row is gone.

## 2. The channel info box shows what the channel holds

**Why.** With focus on the channel column the panel shows only the mark, number and name. A viewer cannot tell how much a channel has to play or which shows live there.

Below the mark, number and name, add:

| Line | Spec |
|---|---|
| **Hours** | 11 sp `textSecondary`: `412 hours of programmes (about 17 days) · 158 titles`. Days are hours ÷ 24, shown from 2 days up. The hours count everything the channel airs, guests included. |
| **Home to:** | up to six titles whose **home** is this channel, anchors first, then by hours, then title ID; comma-separated, 13 sp `textPrimary`, two lines at most, then `+ 31 more`. The label is 11 sp `textSecondary`. |
| **Mostly:** | for channels with no homes (guest-only decade channels, custom channels, leftovers): the six titles holding the most hours, in the same style, in place of "Home to:". |

- **Owner decision: guests are not shown or counted** anywhere in the panel or the properties sheet. Only home shows are listed.
- "Home" is the docs' "homed": the one channel the curation gives a title, as opposed to a guest spot. On screen the word is "Home to:".
- A channel that is off, or has nothing today, shows its status line as now and none of the above.
- No text under 10 sp.

### Data (facts from the code)

- `Curation.curate` already yields `Homes` (`homeOf(title)`, and per channel `ChannelStats.titles`, `totalMs`, `anchors`) and each `CuratedPool`'s weights (3 anchor, 2 home, 1 guest). None of it reaches the screen today.
- Add a read-only `ChannelHoldings` to `core:domain`: total ms, title count, home titles (each with its hours and an anchor flag), and the top titles by hours. Fill it from the day's existing curation result, not a second run.
- For channels outside the curation, use the pool that `ChannelPreviews` already totals (`itemCount`, `totalRuntimeMinutes`).
- Titles are named through the same lookups the properties sheet uses.
- Read it after focus has rested 200 ms on a channel; cache it for the broadcast day.

### Properties sheet

Show the same block in the channel properties sheet under a **What it holds** heading, from the same `ChannelHoldings` value, so the two never disagree.

### Tests

Holdings for a curated starter, a guest-only channel, a custom channel and leftovers; ordering (anchors, then hours, then title ID); the `+ N more` count; a channel that is off shows none of it.

## Acceptance

- [ ] No menu row between the info panel and the grid; the description uses the space.
- [ ] Menu opens and closes the sheet anywhere in the guide; in full screen it is still Last Channel.
- [ ] The channel box shows hours, title count and home shows for a curated channel, and "Mostly:" for one with no homes.
- [ ] Guests appear nowhere.
- [ ] The properties sheet shows the same figures as the channel box.
- [ ] Frames before and after, dark and light, on `pr-assets-archive/<PR number>/`.

## Work plan

A docs PR for this file, then two issues and two PRs, each in its **own git worktree** off `origin/main` under `../sb-worktrees/`:

1. **Menu sheet** — `feature:guide` only.
2. **Channel holdings** — the domain read, the channel box and the properties sheet.

Out of scope: changing what Menu does in full screen, a mock for either change, and showing guests.
