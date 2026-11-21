# Channel identity system

How StationBreak channels get a brand, and the rules their marks follow. The marks are designed in the StationBreak
brand session under the owner's direction, and this page describes them as they are.

Generator: `scripts/channelmarks/v2/` (see the README there). Contact sheet of all 112 at guide size, in Dark and in
Light: `docs/design/channel-marks-sheet.png`. Provenance and the app side: `core/ui/CHANNELMARKS.md`.

## Goal

StationBreak competes with apps that fill their guide with knock-offs of real networks. StationBreak's channels are 100% invented, and the brief is to make them **feel like networks that have always existed** without resembling any real one. So the lineup is a *portfolio of independent brands*, not one network family: every channel has its own picture, its own lettering and its own colours, and the thing that makes them sit together is production discipline, the way real channels from different owners still look at home in the same cable guide.

## What a channel brand is

A **mark** (the logo shown in the guide): a flat picture plus the channel's name, in the channel's own colours. The
picture and the name work together in one of three ways:

- the name sits **inside an object** (a ticket, a barn, a headstone, a case folder);
- a **symbol sits beside the name** (a paw print, a flask, a padlock);
- the idea is **built into the lettering**: a letter is replaced by an object (the O of Wild Hours is a paw print, the O of
  Starport a ringed planet), as long as the object is obvious.

Each mark is built from the channel's name, tagline and theme.

## Rules

1. **Original, not adjacent.** Nothing in a mark may evoke a real network: not the name, the picture, the colour pairing or the lockup. Test: cover the words; if the shape and colours alone call a real network to mind, change one of them. No splats, no ball-and-letter marks, no peacock-fan colour arrays, no "Jr." suffixes, no first-letter-only wordmarks that match a real network's silhouette.
2. **A picture plus the name.** Every mark carries the channel's name (it may be shortened to the part that carries the identity: Cold Case Cable reads Cold Case, Kids' Odds & Ends reads odds & ends) and a picture, in one of the three forms above.
3. **Flat colour on transparent.** No tile, border, shadow or gradient behind or around the mark, and no outline used as decoration. A shape that is part of the picture (a ticket, a sign, a window) is not a tile.
4. **Its own colours, readable on the guide.** Every colour reads at 4.5:1 or better on the guide base `#0E0F11`. Nothing is orange-adjacent (hue 10° to 35° at saturation above 50%), because the focused cell is orange and the mark would merge with it; pumpkins and flames are yellow for that reason. Most marks use one or two colours plus white; a picture may add a colour of its own (Neon Decade, Jack-o'-Lantern Lounge, Masala Hour).
5. **Same box.** Every mark fills a 50×28dp box, drawn on a 200×112 master (4 units per dp) and fitted to the 176×88 safe area (44×22dp), centred. Small parts (a descender, a raindrop, a flag, a road's centre line) may run into the margin; nothing leaves the canvas.
6. **Legible at guide size.** The whole name stays readable at 1:1 in the guide (50×28dp; 100×56 px on a 1080p TV). Check the guide-size strip of the contact sheet. Letters keep their proportions: never stretched or squashed. A slant (as an italic) and a small turn of whole letters (Continental Drift) are allowed.
7. **Mono-safe, for Light.** Every mark still reads with all fills white; the build writes this version (`svg-mono/` and the `_mono_` renders), and the Light theme draws it tinted ink. Nothing is drawn in a dark colour: where a shape needs a hole, the hole is cut out of it.
8. **No voice face.** The station's own faces (Ranchers, Space Mono and Manrope) are never used in a mark, so the station never looks like one of its own channels.
9. **Lettering is outlined type from a licensed face.** Text is set in a face from `scripts/channelmarks/fonts/` or `scripts/channelmarks/v2/fonts/` (SIL OFL 1.1 or Apache 2.0, licence text beside it) and converted to outlines. A new face needs its licence checked and its file and licence text added there first.
10. **Band flavour, not band uniform.** Bands have a leaning, not a rule: Kids → rounded faces, saturated primaries; General → clean display faces, friendly mids; Drama → serifs and slabs, metals and jewel tones; Genre → condensed and italic, neon; World → warm, expressive; Premium → restrained.

Four marks were approved with a note to revisit if they do not hold up on a TV: Order in the Court (ORDER IN THE is small), Corsets & Candlelight (long name, slim candle), Continental Drift (CONTINENTAL is small) and Cherry Blossom Hour (three small lines).

## Adding or changing a mark

1. Read the channel's name, `says` line and sources in the catalog.
2. Pick the picture and the form (inside an object, beside the name, or built into the lettering), a face, and its colours; check them against rules 1 and 4.
3. Add or change it in the round file for its band in `scripts/channelmarks/v2/`, run the build, and look at the contact sheet at guide size and in one colour.
4. Render and commit as `core/ui/CHANNELMARKS.md` says.

## Files and formats

- Masters: SVG, 200×112 viewBox, all text converted to outlines, written by `scripts/channelmarks/v2/build.py` to `v2/out/svg/sl_<NNN>_<key>.svg` (colour) and `v2/out/svg-mono/` (white). They are rebuilt, not committed.
- Renders: PNG at 48dp and 64dp at 2× (96×54 and 128×72 px), transparent, colour and mono, written by `build.py --png` into `core/ui/src/main/assets/channelmarks/` as `sl_<NNN>_<key>_<N>dp.png` and `sl_<NNN>_<key>_mono_<N>dp.png`. `AssetMarkLoader` chooses the size bucket by screen density.

## Runtime (feature:guide)

- `ChannelMark` draws the PNG centred in a 50×28dp box with **no tile**. Dark draws the colour render as it is.
- Light theme draws the mono render tinted with the guide's ink (`GuideColors.Light.markTint`), so every mark is one dark shape; no colour mark has to pass a contrast test on the light page.
- Fallback for user-built channels with no mark: initials in Manrope Bold 12sp, white, no tile.
