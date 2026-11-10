# Bundled channel marks

Provenance and pipeline for the channel marks in `core/ui`. The checked-in hashes are in `channelmarks.sha256`
(sha256sum format, paths relative to `core/ui`); `ChannelMarkManifestTest` fails if a bundled file stops matching,
and also if any file under a `src/*/assets/channelmarks/` folder of any module in the repo (Android merges assets across
modules), or a symlink there, is missing from the manifest. Dot-folders and `build/` are skipped at the repo walk.

## What they are

- 97 fictional in-app channels, one mark each: the 95 recipes of the channel catalog and its two catch-all channels
  (Kids' Odds & Ends, 99, and Odds & Ends, 199). Not the StationBreak company brand.
- Made for this project: the identity system is `docs/design/channel-identity.md`, designed with AI assistance under the
  owner's direction. Every mark is generated, not hand-drawn: a typeface, one or two brand colours plus white, a lockup
  and at most one geometric device, all flat vector.
- Licence: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore, the project's licence (the marks are project art,
  not third-party art). See ADR-0003.
- They replace the 97-logo pack copied from the `design/channelmarks-assets` branch (pinned commit
  `35f03e466c1ac27adb3ae83d58a939f569ebe1d2`, 2026-11-07), which is removed.

## The pipeline

```
scripts/channelmarks/brands.json  (one entry per channel: faces, colours, lockup, device)
  + the catalog keys and numbers (CatalogV3Recipes.kt; the two leftovers channels carry theirs in brands.json)
        |
        v   scripts/channelmarks/brands.py
SVG masters, text converted to outlines          scripts/channelmarks/out-brands/svg/ and svg-mono/
        |
        v   cairosvg (the --png step)
PNG renders, transparent, 48 dp and 64 dp at 2x  core/ui/src/main/assets/channelmarks/
```

- **Masters.** One 200 by 112 viewBox per channel (the 50 by 28 dp box at 4 px per dp), in colour (`svg/`) and with every
  fill white (`svg-mono/`). Committed, so a mark can be reviewed and its renders redone without touching the fonts.
- **Fonts.** Used only by the generator to draw the text, then outlined: no font file is in the APK, and no text is left
  in an SVG. Each is SIL OFL 1.1 or Apache 2.0, with its licence in `scripts/channelmarks/fonts/licenses/`.
- **Renders.** A bucket is N dp at 2x, so `_48dp` is 96 by 54 px and `_64dp` is 128 by 72 px (the 200:112 box). RGBA,
  under 16 KB each (about 1 to 6 KB). The generator strips cairo's `bKGD` chunk: only `IHDR`, `PLTE`, `tRNS`, `IDAT`
  and `IEND` are allowed.

### File names

| File | What |
|---|---|
| `sl_<NNN>_<key>_<N>dp.png` | the colour render |
| `sl_<NNN>_<key>_mono_<N>dp.png` | the same mark with every fill white |

`<NNN>` is the channel's catalog number and `<key>` its recipe or leftovers key; `CatalogMarksDriftTest` (in `core:domain`) checks both
ways that every catalog key has a mark and every mark is for a catalog key, with the number matching. `<N>` is 48 or 64.
Four files per key, 388 in all; `ChannelMarkManifestTest` checks the count, the pixel sizes, the PNG chunks and the size.

## How the app uses them

`AssetMarkLoader` lists the folder once, and for a key and a screen density decodes the smallest bucket whose pixels
(2N across) cover the 50 dp box, else the largest there is:

| Density | Needs | Bucket |
|---|---|---|
| up to 1.9x | up to 95 px | 48 dp (96 px) |
| 2x and above | 100 px and up | 64 dp (128 px) |

- **Dark** draws the colour render as it is.
- **Light** draws the mono render tinted with ink (`GuideColors.Light.markTint`), so every mark is one dark shape on the
  near-white page.
- `MarkCache` holds each variant separately and only decodes the one the current theme draws.

## Rule

Marks load only from the APK. Never from the server, the network or user content.

Known gap: Android also merges `assets/` from dependency AARs, which the repo scan cannot see; a check of the merged
assets in the built APK against this manifest is a tracked follow-up.

## Updating

1. Change an entry in `scripts/channelmarks/brands.json` (or the catalog), following the rules in
   `docs/design/channel-identity.md`. Needs `pip install fonttools cairosvg` (and the cairo library).
2. Regenerate the masters and the renders from the repo root:

   ```
   python3 scripts/channelmarks/brands.py \
       core/engine/src/commonMain/kotlin/tv/stationbreak/core/engine/lineup/CatalogV3Recipes.kt \
       scripts/channelmarks/brands.json \
       scripts/channelmarks/out-brands \
       --png core/ui/src/main/assets/channelmarks
   ```

   Review `scripts/channelmarks/out-brands/contact-sheet.html` (the 1:1 and mono strips) before accepting a mark.
3. Regenerate the manifest:

   ```
   cd core/ui && sha256sum src/main/assets/channelmarks/*.png | LC_ALL=C sort -k2 > channelmarks.sha256
   ```

4. Run `./gradlew :core:ui:testDebugUnitTest :core:domain:test`, and commit the masters, the renders and the
   manifest together. A channel added to the catalog without a mark fails `CatalogMarksDriftTest`.
