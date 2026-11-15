# Guide: the menu moves behind the Menu button, and the channel box shows what a channel holds

**Area:** `feature:guide`, `core:domain`, `feature:channels` (properties sheet).
**From:** dogfood feedback, 2026-11-13. Two changes, each its own issue and PR. Owner decisions are marked; flag in the PR if one proves wrong.

## 1. The guide's menu moves behind the Menu button

**Why.** The Channels / Settings / Exit row sits between the info panel and the grid, the two things a viewer reads, and takes about 28 dp the description could use.

**Owner decision:** the row is removed and the remote's Menu button opens a menu sheet.

- Remove the Channels / Settings / Exit row from the info panel. The panel's column keeps its height, so the description gets the freed lines.
- `KEYCODE_MENU` in the guide opens the menu sheet from anywhere, and closes it if it is open. **In full screen Menu stays Last Channel; that is not changed here.**
- Wherever focus moves to the tab row today (Back on the playing channel's cell, and Up from the top row if that exists), the sheet opens instead. **Back stays the way out of the guide:** a sheet that Back opened is left by Back again, which leaves live TV, as Back did from the menu row. A sheet that the Menu key opened is closed by Back, which returns focus to where it came from. So Back, Back from the playing cell leaves live TV.
- **Sheet:** left edge, 220 dp wide, full height, with the same surface, dimming and focus style as the channel properties sheet (`docs/design/channel-properties.md`). Rows 36 dp tall, 13 sp Medium: Channels, Settings, Exit. Channels has focus on open. Up and Down move, OK chooses.
- **Hint row:** add a `Menu` key cap with the label `Menu`; keep `Back · To the menu` where it shows today.

**Tests.** Menu opens and closes the sheet from a programme cell and from the channel column; Menu in full screen is still Last Channel; focus returns to where it was on close; Back on a sheet that Menu opened closes it, and Back on a sheet that Back opened leaves live TV (Back, Back from the playing cell); the description gains lines when the row is gone.

## 2. The channel info box shows what the channel holds

**Why.** With focus on the channel column the panel shows only the mark, number and name. A viewer cannot tell how much a channel has to play or which shows live there.

Below the mark, number and name, add:

| Line | Spec |
|---|---|
| **Hours** | 11 sp `textSecondary`: `412 hours of programmes (about 17 days) · 158 titles`. Days are hours ÷ 24, shown from 2 days up. The hours count everything the channel airs, guests included. |
| **Home to:** | up to six titles whose **home** is this channel, anchors first, then by hours, then title ID; comma-separated, 13 sp `textPrimary`, two lines at most, then `+ 31 more`. The label is 11 sp `textSecondary`. |
| **Mostly:** | for channels with no homes (guest-only decade channels, custom channels, leftovers): the six titles holding the most hours **whatever their status** (on a guest-only channel these are its guests), in the same style, in place of "Home to:". |

- **Owner decision: there is no Guests line and no guest count anywhere** in the panel or the properties sheet. "Home to:" lists only titles whose home is this channel; "Mostly:" lists the titles holding the most hours whatever their status, which on a guest-only channel are its guests. The title count is the number of titles the channel airs, as the hours are the hours it airs: a total, not a guest count.
- "Home" is the docs' "homed": the one channel the curation gives a title, as opposed to a guest spot. On screen the word is "Home to:".
- A channel that is off, or has nothing today, shows its status line as now and none of the above.
- No text under 10 sp.

### The box leads with the channel's tag line (#777)

Top to bottom:

| Line | Spec |
|---|---|
| **Mark, CHANNEL n, name** | unchanged. |
| **Tag line** | under the name, inside the identity block. A starter's personality (`ChannelProperties.personality`), 13 sp Regular `textPrimary`, two lines at most, no quotation marks. A channel with none says one line in the properties sheet's words, in 13 sp `textSecondary`: leftovers `Plays whatever nothing else does`; a custom channel the first row of the sheet's "What it plays" that has anything in it, as `Collections: Star Trek, Stargate`. Left out when there is nothing to say. |
| **Tags** | the programme panel's outlined tags (`PanelTagRow`): `KIDS · UP TO <rating>` (or `KIDS` with no ceiling) for a kids channel, then `BUILT-IN`, `CUSTOM` or `LEFTOVERS`. |
| **On now / Next** | 11 sp `textSecondary`, one line with an ellipsis: `On now: <title> · <N> min left · Next: <title> at <time>`, from the row's own cells. A slate, a break or an unavailable programme reads as the grid labels it (`Off air`, `Station break`, `Unavailable`); Next passes over breaks; it is left out when nothing follows. |
| **Hours, "Home to:" / "Mostly:"** | as above, unchanged. |

- The tag line and the tags show at once with the channel, with no dwell: the guide reads every channel's identity (`ChannelIdentityReader`, the properties reader's own recipe, rules and lookups) when it opens. What needs no lookup (kind, kids, ceiling, personality) comes first; a custom channel's plays row follows once its sources are named, and a reopen with the same lineup looks nothing up. The holdings still come after the 200 ms rest.
- The sheet and the box list what a channel plays from one `playsRowsOf` in `core:domain`, and word it with the same `core:ui` strings, so they word a channel one way.

**When the box is short**, lines give way whole, never clipped, in this order: "Home to:" / "Mostly:" first, then the hours line, then On now / Next, then the tags. The identity block, with the tag line, always stays.

### Data (facts from the code)

- `Curation.curate` already yields `Homes` (`homeOf(title)`, and per channel `ChannelStats.titles`, `totalMs`, `anchors`) and each `CuratedPool`'s weights (3 anchor, 2 home, 1 guest). None of it reaches the screen today.
- Add a read-only `ChannelHoldings` to `core:domain`: total ms, title count, home titles (each with its hours and an anchor flag), and the top titles by hours. Fill it from the day's existing curation result, not a second run.
- For channels outside the curation, use the pool that `ChannelPreviews` already totals (`itemCount`, `totalRuntimeMinutes`).
- Titles are named through the same lookups the properties sheet uses.
- The set of titles whose home is a channel is **saved with the day** (the days-file format version is bumped; files written before still load). A day with no saved home set, such as one read from an older file, has no homes in `ChannelHoldings`: the box and the sheet show the hours and the title count only, with no "Home to:" and no "Mostly:" guess from that day's data.
- Read it after focus has rested 200 ms on a channel; cache it for the broadcast day.

### Properties sheet

Show the same block in the channel properties sheet under a **What it holds** heading, from the same `ChannelHoldings` value, so the two never disagree.

### Tests

Holdings for a curated starter, a guest-only channel, a custom channel and leftovers; ordering (anchors, then hours, then title ID); the `+ N more` count; a channel that is off shows none of it.

## Acceptance

- [ ] No menu row between the info panel and the grid; the description uses the space.
- [ ] Menu opens and closes the sheet anywhere in the guide; in full screen it is still Last Channel.
- [ ] Back, Back from the playing cell leaves live TV; Back closes a sheet that Menu opened.
- [ ] The channel box shows hours, title count and home shows for a curated channel, and "Mostly:" for one with no homes; a day with no saved home set shows hours and title count only.
- [ ] There is no Guests line and no guest count anywhere; "Mostly:" lists the titles holding the most hours whatever their status.
- [ ] The properties sheet shows the same figures as the channel box.
- [ ] Frames before and after, dark and light, on `pr-assets-archive/<PR number>/`.

## Work plan

A docs PR for this file, then two issues and two PRs, each in its **own git worktree** off `origin/main` under `../sb-worktrees/`:

1. **Menu sheet** — `feature:guide` only.
2. **Channel holdings** — the domain read, the channel box and the properties sheet.

Out of scope: changing what Menu does in full screen, a mock for either change, and showing guests.
