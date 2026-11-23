# Number bands

What the guide and the Channels list show so a viewer can tell what a channel number is for without reading station
idents (#1100). The 1-99 safety setting is Family Mode. The bands are:

| Band | Numbers | Code name | Accent |
| --- | --- | --- | --- |
| Tots | 1-33 | `Band.Kids` | green |
| Kids | 34-66 | `Band.Family` | blue |
| Teens | 67-99 | `Band.Teen` | violet |
| Grown-ups | 100 and up | every other number | none (the neutral stroke colour) |

"Grown-ups" is our proposed label. It is one string in each of `feature/guide` and `feature/channels`
(`band_grownups`). Code names and stored values do not change. The classifier is `NumberBand` and `bandOf(number)` in
`core:domain` (`lineup/NumberBand.kt`); its ranges come from the engine's `Band`, so no screen keeps the numbers. A
null number, or one below 1, has no band.

## Rules

1. The accent is a shape: a stripe, a dot or a rule. Text is never set in it; the label keeps the secondary colour.
2. Never orange, red or yellow. Orange is focus and now (the Spark Rule); red and yellow read as status.
3. 3:1 or better on the page, a card and a sheet, in Dark and Light (`GuideColorsTest`, `ChannelsPaletteTest`).
4. The accent always sits next to the band's name. A stripe alone is never the only signal.
5. Grown-ups draws no stripe (most of a lineup is Grown-ups); its flag dot and its divider are the neutral stroke colour.
6. Flags use the teletype at 10sp, +13%, as every flag. Nothing under 10sp.

Colours (`core:ui` `BandColors`): Dark #6FCF97, #5AB0E8, #A98BE8; Light #2E8B57, #1F6FB5, #6B4FB8.

## Guide

No rows are added and no row height, focus or paging maths changes. On each channel tile:

- a 3dp stripe of the band's accent at the very left edge, clear of the 2dp "playing" bar, which sits 3dp in;
- where the band changes between two visible rows, a 2dp rule of the new band's accent centred on the gap above the
  tile, across the tile only (not the programme cells). The first visible row has none above it;
- the details panel (and the channel panel when the focus is on the channel column) shows the band's flag next to the
  channel line. A screen reader hears the band there, once; the stripe and the rule have no semantics.

```
 NOW 8:12 PM   8:00      8:30      9:00
 ┃▌ 32 [mark] │ Programme ... │ Programme
 ┃▌ 33 [mark] │ Programme ... │ Programme
 ━━━━━━━━━━━━   (2dp rule, Kids accent, tile width only)
 ┃▌ 34 [mark] │ Programme ... │ Programme
 ┃▌ 35 [mark] │ Programme ... │ Programme
 ──────────────
 ● TOTS · 1-33   34 · Channel name        (details panel: dot, flag, channel line)
```

## Channels list

Inside each group (On the air, More channels, Switched off), a band flag opens each run of numbered rows in one band.
Rows with no number get no flag and sit after the numbered ones. Each row has a 3dp stripe of its band's accent inside
its focus ring (none for Grown-ups). The flag is a heading for a screen reader and is not focusable; scrolling up to
the first row under a heading and a flag keeps both in view.

```
 ────────────────────────────
 ON THE AIR · 48
 ● TOTS · 1-33
 ▌ 4   [mark] Channel name
 ▌ 21  [mark] Channel name
 ● KIDS · 34-66
 ▌ 36  [mark] Channel name
 ● GROWN-UPS · 100+
   104 [mark] Channel name
```
