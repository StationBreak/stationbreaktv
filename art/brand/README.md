# Brand art

The SVG sources for the StationBreak brand: Stan, a console TV with rabbit ears, the Ranchers wordmark, and the ink,
cream and orange palette with its paper (light) mode. They come from the StationBreak Brand Standards working draft
(2026-11-17); [DESIGN.md](../../DESIGN.md) is the written system. The text in the SVGs is live `<text>` in Ranchers and
Space Mono (not outlines), so rendering needs those fonts.

## What ships

`scripts/render-brand-art.sh <font-dir>` renders these into the app and the README:

| File | Used for |
|---|---|
| `icon-main.svg` | The launcher icon: cream Stan on an orange tile. Rendered to `mipmap-*/ic_launcher.png` (48 dp, API 25). |
| `icon-foreground.svg` | The adaptive icon's foreground (API 26+, 108 dp): `icon-main.svg` without the tile, Stan's head scaled to fit the 66 dp safe circle. The orange ground is `ic_launcher_background`. |
| `tv-banner.svg` | The TV banner (`drawable-xhdpi/banner.png`, 640 x 360) and the README banner (`repo-banner.png`, 1920 x 1080). |
| `repo-banner.png` | The README banner, rendered. |

## Reference art

Not rendered or shipped yet; the source for Stan's moods and the station screens.

| Files | What |
|---|---|
| `icon-on-ink.svg`, `icon-on-paper.svg` | The icon on an ink tile and on a cream tile, for where orange would shout. |
| `tv-banner-light.svg` | The TV banner on paper. |
| `wordmark-on-ink.svg`, `wordmark-on-cream.svg` | The "Station Break" wordmark with its tagline, on ink and on cream. |
| `wordmark-stacked-on-ink.svg`, `wordmark-stacked-on-orange.svg` | The stacked wordmark. |
| `stan-{content,awake,tuning,asleep,lost,fritz}.svg` | Stan's six moods on ink. |
| `stan-*-light.svg` | The same moods on paper (cream ground, ink cabinet). |
| `stan-anatomy.svg` | Stan's parts, named. A reference sheet. |
| `screen-{tuning,off-air,no-signal,difficulties}.svg` and `-light` | The station screens at 1920 x 1080. Not built yet. |

## Licensing

This is project art, made for StationBreak by its AI-assisted design partner under the owner's direction, like the
channel logo pack. It follows the rule ADR-0003 sets for project art (the 2026-11-07 update): it is under the project
licence, GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore, and is not third-party art. The fonts are different: Ranchers and Space Mono are
SIL OFL 1.1 and stay under it. They are used here by name and are not embedded in these files; the copies bundled in
the app are listed, with their licences, in [`core/ui/FONTS.md`](../../core/ui/FONTS.md).
