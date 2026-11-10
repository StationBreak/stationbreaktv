# Guide visual overhaul: make the guide read as a broadcast EPG, not a themed menu

**Area:** `feature:guide` (plus `core:ui` fonts, `feature:live` picture corner)
**Type:** epic / design-debt
**Target mock:** `docs/design/guide-visual-overhaul-target.png` (source: `guide-visual-overhaul-target.html`, 1920×1080; `:root` holds the tokens). Attach a current guide screenshot as the "before".

## Problem

The guide works, but it does not look like a TV guide. Side by side with any cable or streaming EPG (X1, YouTube TV, Channels DVR) the differences are consistent and nameable:

1. **Everything is a separate rounded capsule.** Program cards, channel plates, menu pills, NOW chips and key hints all use 8–18dp radii and visible gaps, so the screen reads as a field of buttons instead of one grid.
2. **Warm, low-contrast palette.** The page (`#191A19`), bands (`#292B27`), cards (`#33352F`) and especially the channel "plate" gradient (`#4A4236 → #3A342B`) make the guide look like a wood-toned skin. EPGs sit on true neutrals with one accent.
3. **Too big, too sparse, flat hierarchy.** 4 visible rows at 57dp, 19sp program titles, a 40sp header title, and 17–18sp labels everywhere. Nearly all text is the same size and weight, so nothing leads the eye. Real guides show 7–8 rows with a clear three-step type scale.
4. **The accent is overworked.** `#E8642C` is used for the focused card, two NOW chips (panel and timeline), the now-line, the progress bar, the focused menu pill, the row focus tick and the "playing" bar. Any one of these would stand out; all of them together is noise.
5. **Channel marks read as clip-art stamps.** The 32dp marks are pictorial icons on a filled, bordered tile (`MarkTileStyle`: cream or ink square with an accent tick). Real channel identities are typographic (wordmarks/monograms), flat, on a transparent background, with uniform visual weight across the lineup.
6. **Menu chrome lives inside the guide.** The Channels / Settings / Exit pill row sits between the details panel and the grid.

The mockup attached to this issue is the target. The numbers below are the spec; where the two disagree, the numbers win (the mock's metadata text is a hair under the 10-foot minimum; the mock uses the sample brand marks from `docs/design/channel-identity.md`).

## Design rules (apply everywhere in the guide)

- **One grid, not many cards.** Cells are near-flat against the panel, separated by 1dp gaps. Corner radius ≤ 2dp on anything inside the grid.
- **True neutrals.** No warm tints in page, bands, cards, or plates. Text is white at 90 / 58 / 38% alpha for primary / secondary / tertiary.
- **One accent, three jobs.** `accent` may be used for (a) the focused cell fill, (b) the now-marker in the timeline + its thin line, (c) the progress line on current programs. Nowhere else. Remove the second NOW chip and the accent pill on the menu.
- **Three type sizes, two weights** in the grid: title (13sp Medium), metadata (11sp Regular), labels/figures (11sp Medium, tabular). The header title is the one large thing on screen.
- **Density target: 5 rows visible** in the 960×540dp layout (8 in the first cut; reviewed on the TV and reduced for legibility from the couch).
- **Marks are flat colour on transparent, untiled, same box, same optical size** (see `channel-identity.md`).

## Spec by area (current → target)

Paths are under `feature/guide/src/main/kotlin/tv/stationbreak/feature/guide/ui/` unless noted.

### Tokens — `GuideColors.kt` (now in core:ui), `GuideChrome.kt`

| Token | Current (Dark) | Target |
|---|---|---|
| `page` | `#191A19` | `#0E0F11` |
| `pageGradientStart` | `#212420` | remove gradient; solid `page` |
| `gridPanel` | `#222320` | transparent (no panel chrome) |
| `rowBand` | `#292B27` | remove banding (or white @ 3.5% if kept) |
| `card` | `#33352F` | white @ 6% alpha |
| `plateTop/plateBottom/plateHighlight/plateShadow` | warm gradient | remove the plate entirely |
| `plateNumber` | `#FFD9B8` | `textSecondary` (number is not decorated) |
| `textPrimary` | `#F3EEE3` | white @ 90% |
| `textSecondary` | `#B3B4AE` | white @ 58% |
| `textSupporting` | `#CFCBC2` | white @ 38% (rename `textTertiary`) |
| `programmeText` | `#E0DDD5` | same as `textPrimary` |
| `accent`/`focusFill`/`nowLine`/`progressFill` | `#E8642C` | keep `#E8642C` (or `#F26B2B`); drop `nowChipFill`, `menuPillFocused` |
| `focusText` | `#1C1B19` | `#1A0D06` |
| `progressTrack` | `#3E3C36` | white @ 8% |
| `focusedRowWash`, `focusShadow`, `shadowAlpha` | — | remove (no washes, no shadows) |
| `GuideCardRadius` | 12dp | 2dp |
| `GridPanelRadius` | 18dp | n/a (panel removed) |
| `GuideCardInset` / `CellInset` | 2dp / 1dp | 1dp gap between cells, 1dp between rows |
| `GuideFocusExtendX/Y`, focus shadow (4dp/8dp) | grow + shadow | no growth, no shadow; fill change only |
| top highlight line (1dp `plateHighlight` @ .25) | present | remove |

Light theme (retuned after the TV review: the near-white page was glaring and low-contrast on the Insignia, and the first retune was still bright): a blue-grey page `#D2D5DA` (about 83% lightness, no gradient; `GuidePaletteTest` keeps every page at or under 90% and never warm), ink `#111214` for everything else. Card ink @ 12%, line ink @ 16% (the menu underline and the hint-cap outlines use it), progress track ink @ 14%, text primary / secondary / tertiary ink @ 92 / 66 / 52% (primary and secondary at least 4.5:1 on the page and on a card; tertiary 3:1, strokes only). The now-line is 60% (Dark stays 35%) so the line and its triangle show on the grey. Two oranges darker than Dark's, the least that hold the floors on this page: the focus fill `#AD4216` (3:1 over a card) with white text, and the accent used as text, as a line and for progress `#8F3811` (ON NOW at 4.5:1 on the page, the cell progress line at 3:1 over a card). Marks stay the mono render tinted ink. Dark is unchanged. Light is held to the same contrast floors as Dark. If this is still too bright on the TV, the next step is a dim Light (a mid-grey page with light text), not a paler one.

### Geometry — `GuideScreen.kt` (`GuideGeometry`), `GuideState.kt`

| | Current | Target |
|---|---|---|
| `GUIDE_VISIBLE_ROWS` | 4 | 5 |
| `RowHeight` | 57dp | 40dp (band = 5 × 40 + 4 × 1dp gaps = 204dp; each row is an equal 40.8dp part of the band and the 0.5dp cell inset is the gap, so a cell is 39.8dp) |
| `ChannelColumnWidth` | 168dp | 84dp (number 22dp + gap + logo box 50dp) |
| `ClockHeight` | 24dp | 20dp |
| `TopGap` / `PanelGap` / `HintGap` | 6 / 16 / 8dp | 4 / 12 / 4dp |
| Hint row height | 24dp | 20dp (the 16dp key caps are unchanged; with the 48% picture the column is 485.3dp of the 486dp safe height) |
| Picture corner (`ScreenLayout.pictureRect`) | 40% of safe width | 48% of safe width (16:9): 414.7 × 233.3dp, about 19% of the screen; the panel is 437dp wide |

### Screen fit and bleed — `ScreenFit` (`core:ui`), `GuideGrid.kt`

- **Screen fit** (Settings, Appearance, beside Dark and Light): **Safe** (the default) keeps text and pictures 5% in from every edge, which no TV crops; **Edge** keeps 1.5%, for a TV that shows the whole picture. It is stored in its own preference (`screen-fit`) next to the guide's theme, and `LocalScreenFit` feeds it into `ScreenLayout.safeArea(screen, fit)` for live TV (the corner picture, the banner, the transport strip, the info overlay), the guide, and the channel and settings screens, so they all move together. The onboarding screens still use a fixed 5%.
- **Bleed:** surfaces run past the safe area to the screen's edge while text stays inside it. The page background has no inset (it is drawn full-screen outside the safe-area column). Program cells run on to the screen's right edge (a cell that runs past the time columns is clipped at the screen, not the safe area; its focus fill too), the timeline's 1 dp rule runs from the screen's left edge to its right, and each row has a card-coloured surface from the screen's left edge behind the channel column, 1 dp from the first cell. The picture, the panel's text, channel numbers, marks, tick labels, titles, chevrons and hints keep their safe-area positions. The time scale is unchanged: the bleed adds surface, not minutes.

### Timeline — `GuideGrid.kt` `ClockStrip`, `NowLine`

- Clock label "MON 8:05 PM" uppercase, 11sp Medium tabular, `textSecondary`, left-aligned in the channel column.
- Tick labels 11sp Regular tabular `textSecondary`, 1dp tick in `line` colour (white @ 10%). Strip has a 1dp bottom rule in `line`.
- **Remove the timeline NOW pill.** Replace with a small accent triangle (6dp) on the strip's bottom rule at the current time.
- `NowLineWidth` 1.5dp → 1dp, drawn through the full grid height at 35% alpha. It may cross cells; the progress line (below) is what shows "how far along".

### Channel column — `GuideGrid.kt` `ChannelCell`, `Modifier.channelPlate`

- Delete `channelPlate` (gradient, shadows, highlight, playing bar).
- Layout: `[number 11sp Medium tabular, right-aligned, textSecondary] [8dp] [logo box 50×28dp]`. **Do not render the channel name in the grid**; it is already in the details panel.
- Focused row: number switches to `textPrimary`. No tick, no wash.

### Program cells — `GuideCells.kt`

- `ProgramStyle` 19sp → 13sp Medium `textPrimary`; `SecondLineStyle` 16sp → 11sp Regular `textSecondary`. `LINE_HEIGHT_FACTOR` 1.25 → 1.2.
- Second line is metadata, not just episode: `S2 E7 · Animated` / `1954 · NR · Musical` (year · rating · genre; fall back gracefully). `EpisodeFrom` 160dp → 110dp.
- Optional `NEW` tag: 7sp Bold tracking +8%, white @ 14% pill with 1dp radius, before the metadata.
- Current program (any row): 1.5dp progress line along the cell's bottom edge, track white @ 8%, fill `accent`, ending exactly at the now-line x.
- Focused cell: fill `accent`, title `focusText` SemiBold, metadata `focusText` @ 72%, progress fill `focusText`. Nothing else changes (no scale, ring, or shadow).
- Continuation chevron: keep; 1.5dp stroke in `textTertiary`.
- `CompactBelow` 77dp → 48dp (smaller type fits more).

### Details panel — `GuidePanel.kt`

- Channel line 17sp → 11sp Regular `textSecondary` (number in `textTertiary`).
- Title 40sp → 24sp SemiBold, letter-spacing −0.015em.
- Meta row: `ON NOW` 8.5sp Bold tracking +6% in `accent` (this replaces the chip; no fill), then `1954 · NR · 1h 32m · Musical` 11sp `textSecondary` separated by 1.5dp dots.
- Progress: 2dp track white @ 10%, fill `accent`, width = panel width. Below it `8:00 PM … 55 min left … 9:00 PM` 11sp tabular `textSecondary`.
- Overview: 11sp Regular, line height 15sp, 2 lines max, `textSecondary`.

### Menu — `GuideMenu.kt`, `GuideScreen.kt`

- Move Channels / Settings / Exit out of the vertical flow into a 36dp-wide icon rail on the left edge of the safe area (guide, gear, power glyphs; 13dp line icons, 1.8dp stroke, `textTertiary`, active = `textPrimary` + 2dp accent bar on the rail's left). Grid and panel shift right by the rail width.
- Alternatively, if the rail is a larger change than wanted for this epic: keep the row but render as plain text tabs (11sp, `textSecondary`, active `textPrimary` with a 1dp underline) and **no pills**.

### Key hints — `GuideScreen.kt` `HintRow`, `HintKey`

- Key cap: 1dp stroke white @ 28%, 3dp radius, 7.5sp SemiBold, 15dp tall; OK key is a circle. Action label 10sp `textSecondary`. 20dp between pairs. Remove the divider line.

### Channel marks — `ui/mark/*`, `core/ui/src/main/assets/channelmarks/`

Content and rules live in `docs/design/channel-identity.md`; this issue only changes how marks are drawn.

- **Rendering:** draw the colour mark **as-is, no tint, no tile, no border**, centred in a 50×28dp box. Delete `MarkTileStyle` for assets and the initials-tile border/tick.
- **Assets:** masters are outlined SVGs from `scripts/channelmarks/brands.py` (`out-brands/svg/` colour, `out-brands/svg-mono/` white). Ship PNG renders at 48dp and 64dp (2×) and retire the `_32dp` set; update `AssetMarkLoader` to pick a size bucket by density and `CHANNELMARKS.md` / `channelmarks.sha256`.
- **Light theme:** use the mono variant tinted `#1C1B19`.
- **Fallback** for channels with no mark (user-built): initials in Manrope Bold 12sp `textPrimary`, no tile, same box.
- **No new app fonts.** The brand typefaces are used only by the generator.

### Channel focus (dependency, not in scope) — see #631

`docs/design/channel-properties.md` adds a channel position to `GuideFocus` (Left from the earliest cell) with a 2dp accent outline on the channel cell. Keep the channel column's padding so that outline fits inside the 84dp column without moving the mark.

## Acceptance criteria

- [ ] 5 channel rows visible at 960×540dp with no clipping; no text in the guide below 10sp.
- [ ] No element inside the grid has a corner radius > 2dp; no shadows, washes or gradients remain in `feature:guide`.
- [ ] Exactly one orange slab on screen (the focused cell); "NOW" appears once (the panel's `ON NOW` text); the menu has no filled pills.
- [ ] Every channel mark is drawn flat on transparent (no tile, border, shadow or tint) in a 50×28dp box; channel name is not drawn in the grid.
- [ ] Current programs show a progress line ending at the now-line.
- [ ] `GuidePaletteTest` / `ContrastTest` pass with the new tokens (≥ 4.5:1 for 11sp text, ≥ 3:1 for 13sp+).
- [ ] `GuideScreenshotTest` baselines regenerated; before/after attached to the PR.
- [ ] Light theme updated with the same structure.

## Suggested PR split

1. Tokens + geometry (`GuidePalette`, `GuideChrome`, `GuideGeometry`, `GUIDE_VISIBLE_ROWS`) — biggest visual win, lowest risk.
2. Cells + timeline + channel column (remove plate, add progress line, retune type).
3. Details panel + hints.
4. Menu rail (or text tabs).
5. Mark rendering (loader size buckets, tile removal, fallback) and the asset set generated by `scripts/channelmarks/brands.py`.

## Out of scope

Designing the marks themselves (`docs/design/channel-identity.md`) and the channel-properties sheet (#631). Focus/navigation model (ADR 0008), schedule data, live TV chrome (`BroadcastStyle`), channel builder screens (`feature:channels` has its own palette and should follow in a separate issue once this lands).
