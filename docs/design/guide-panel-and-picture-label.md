# Guide: fuller info panel, a label on the picture, and a two-hour grid

**Area:** `feature:guide`, `feature:live` (picture corner), `core:model`, `core:domain`, `data:jellyfin`, `core:ui`.
**Target mock:** `docs/design/guide-panel-target.png` (source `guide-panel-target.html`; 1 dp = 2 px).

Three changes from TV use, each its own issue and PR. Owner decisions are marked; flag in the PR if one proves wrong.

## 1. A label on the picture while the guide is open

**Why.** The info panel describes the *highlighted* programme, which is often not what is playing in the corner. Nothing on screen says what the picture is.

**What.** A bar across the **top** of the picture, drawn over the video:

- 32 dp tall, `page` fading from 88 % to 0 % top to bottom (a scrim, no hard edge).
- Left to right, 10 dp padding: the channel **mark** (36×20 dp), the **number** (11 sp SemiBold `textPrimary`, tabular), then `Now playing: <title>` (11 sp; "Now playing:" `textSecondary`, title SemiBold `textPrimary`), one line with ellipsis.
- During a tune, an ident or a slate, the title part follows the banner's label: `Tuning in: …`, or the channel name alone when nothing is playing.
- Always Dark tokens (it sits over video).

**Why the top and not under the picture.** A strip under the picture needs 20 dp of height the layout does not have without shrinking the picture or the rows; along the bottom edge it would cover the film's own subtitles. The top edge costs nothing and is clear of subtitles.

**The Back hint** stays in the footer (`Back · To what's playing`); it is not repeated on the picture.

**Where it lives.** The picture belongs to `feature:live` (`pictureCorner` in `LiveScreen.kt`); it already has the banner's channel, `recipeKey` and title. The label is drawn there when the guide is open, clipped to `ScreenLayout.pictureRect`. Captions (`SubtitleView`) remain hidden in the corner.

## 2. The info panel uses its space and carries the detail

**Why.** The panel stops at two lines of description with a third of its height empty, and the only way to learn more about a later programme is the info overlay on OK.

### Layout (top to bottom, in the panel's existing column)

| Block | Spec |
|---|---|
| Channel line | unchanged: 11 sp `textSecondary` |
| **Title** | the item's **logo** when Jellyfin has one, else the text title (24 sp SemiBold, as now). Logo box: max 46 dp tall, max 70 % of the panel width, left-aligned, scaled to fit. **Owner decision: the logo replaces the text title.** The title text stays as the node's content description. |
| Meta line | unchanged: `ON NOW` · year · runtime · genres (episode line for an episode) |
| **Tags** | one row of outlined tags, in this order, each omitted when unknown: parental rating (`PG-13`), resolution class (`SD` / `HD` / `4K`), audio layout (`Mono` / `Stereo` / `5.1` / `7.1`), `CC` when any subtitle track exists, `Critics 68%`, `Audience 6.6`. **Owner decision: both scores.** |
| Progress and times | unchanged |
| **Description** | no fixed line limit: as many 15 dp lines as the remaining height holds (four to six), ellipsis on the last |
| Menu tabs | unchanged, at the bottom of the column |

Tag style: 9 sp SemiBold `textPrimary`, 1 dp `keyCapStroke` outline, 2 dp radius, 4 dp horizontal padding; the label part of a score (`Critics`, `Audience`) in 9 sp Regular `textSecondary`. Flat: no fills, no icons, no emoji. The existing drop-order rule for a short panel keeps working: the description gives way first, then the tags.

### Logo rules

- Shown on a dark plate in the Light theme (logos are usually light): `#0E0F11` at 92 %, 2 dp radius, 6 dp padding. In Dark, no plate.
- The text title is drawn first. The logo replaces it only if it is already cached or arrives within 150 ms of the highlight settling; a late logo is cached for next time and does not swap in under the viewer.
- A highlight that moves on cancels its fetch.

### Data (facts from the code)

- `ItemDetails` has only overview, year, genres and runtime. Add: `officialRating`, `criticRating` (0–100), `communityRating` (0–10), `logoTag` (the item's `ImageTags.Logo`, or the series' for an episode), and a small `MediaSummary` (video height, audio channel count of the default track, whether any subtitle stream exists) from the item's first media source. Ask for the extra `Fields` in the existing details call; no second request.
- The app has **no image-loading library**. Add one (Coil is the usual choice) under ADR-0013's dependency verification, or a small loader of our own on the existing HTTP client; say which in the PR. Either way: logos are fetched from `Items/{id}/Images/Logo?tag=…&maxHeight=…` with the session's auth, cached on disk by tag (capped, for example 30 MB), never logged, and a failure is simply "no logo".
- Details and logos are fetched for the highlighted cell only, after the highlight has rested 200 ms, through the existing `ProgramDetailsCache`.

### OK on a later programme

**Owner decision:** with the panel carrying the detail, the info overlay is no longer opened from the guide. OK on a programme that has not started **tunes to its channel** (as OK on an on-air cell does). The hint reads `OK · Watch channel` there. A reminder is a possible later feature, not part of this.

`ProgramInfoOverlay` stays for live TV's Info button.

## 3. A two-hour grid

**Owner decision:** the grid shows **120 minutes**, up from 90 (`GUIDE_VIEWPORT_SPAN`). At 780 dp that is 6.5 dp per minute.

- Tick labels stay every 30 minutes (four of them).
- An 11-minute programme becomes about 71 dp wide and a 5-minute one about 32 dp. Check `CompactBelow` (48 dp) and `EllipsisFrom` (32 dp) still give sensible results: a cell under 48 dp shows no title, which is acceptable for fillers.
- Paging and the "extend when scrolled near the end" logic follow the constant; verify they do.

## Acceptance

- [ ] With the guide open, the picture shows the playing channel's mark, number and title along its top edge, and it changes when the channel or programme does; it does not cover the film's subtitles.
- [ ] The description fills the panel's free height; no empty band above the tabs for a long description.
- [ ] A logo appears for an item that has one and the text title for one that does not; scrolling quickly through cells never shows a logo belonging to another cell and never swaps title for logo after 150 ms.
- [ ] Tags show only known facts; an item with no scores shows none.
- [ ] OK on a later programme tunes to its channel; the info overlay no longer opens from the guide.
- [ ] The grid spans two hours; focus, paging and the now-line are correct.
- [ ] All text ≥ 10 sp except tags at 9 sp SemiBold, which must pass 4.5:1; frames before and after, dark and light, on `pr-assets-archive`.

## Work plan

Three issues, three PRs, each in its **own git worktree** so sessions do not share a checkout:

1. **Two-hour grid** — one constant and its tests. Smallest; do first.
2. **Picture label** — `feature:live`, independent of the others.
3. **Info panel** — the data, the loader, the layout and the OK change. May be split into "data and loader" then "layout" if it grows.

Out of scope: reminders, a channel filter row like "All Channels / Classics", and any change to live TV's own info overlay.
