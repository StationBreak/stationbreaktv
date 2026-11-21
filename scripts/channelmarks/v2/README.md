# Channel marks, second generation

The generator for all 112 channel marks. 102 are bundled in the app; the ten for catalog channels that are not in code yet
(#1017 to #1026) are bundled with each channel's own catalog change, because `CatalogMarksDriftTest` rejects a logo whose key is
not in the catalog. Each mark is built from the channel's name, tagline and
theme, and was approved band by band in the StationBreak brand session. The rules the marks follow are in
`docs/design/channel-identity.md`; provenance and the app side are in `core/ui/CHANNELMARKS.md`.

## Build

    pip install fonttools skia-pathops               # --png also needs rsvg-convert (librsvg) on the PATH
    python3 scripts/channelmarks/v2/build.py
    python3 scripts/channelmarks/v2/build.py --png core/ui/src/main/assets/channelmarks

Without `--png` it writes `v2/out/svg/` (colour masters), `v2/out/svg-mono/` (all fills white) and
`v2/out/contact-sheet.html`. With `--png` it also renders the PNGs the app loads: 48 dp and 64 dp at 2x
(96x54 and 128x72 px), colour and mono, 448 files named `sl_<NNN>_<key>_<N>dp.png` and
`sl_<NNN>_<key>_mono_<N>dp.png`, stripped to the PNG chunks the app's manifest test allows. After a `--png` run,
copy into the app only the files for keys that are in the catalog, then regenerate `core/ui/channelmarks.sha256`; the steps
are in `core/ui/CHANNELMARKS.md`.

`out/` is not committed except for the contact sheet (the masters are ignored); run the build to regenerate it.

## What is where

| File | What it is |
|---|---|
| `build.py` | Collects the marks, writes masters, contact sheet and PNGs. |
| `marklib.py` | Drawing helpers: outlined text fitted to a box, shapes, cut-outs, merging. |
| `round1.py` … `round8.py` | The marks, in the order they were made (round 1 was a cross-band sample; 2 Kids, 3 General, 4 Drama, 5 Genre, 6 World and Premium; 7 and 8 are the channel overhaul: 7 holds the replacements for renamed channels and the odds & ends marks, 8 the new channels). A later round replaces an earlier mark with the same key. |
| `rabbit.py` | The original Rabbit Ears mark, which needed its own layout code. Round 7 replaces it with Vertical Hold. |
| `fonts/` | The seven typefaces added for these marks, with their licences in `fonts/licenses/`. Everything else comes from `../fonts`. |
| `out/contact-sheet.html` | All 112 large, at guide size, and in one colour. |

## Fonts

Used by the generator only and outlined into the masters; no font file ships in the app. The seven faces added
here are Corben, Limelight, Rammetto One, Rye, Shrikhand and Stardos Stencil (SIL Open Font License 1.1) and
Yellowtail (Apache License 2.0). The shared set in `../fonts` is listed in `../README.md`.

## Things to know

- **Overlapping cut-outs.** If two holes overlap, or joined script letters are cut out of a shape, the overlap fills back in. Use `union(...)` on the holes, or `merge=True` on `text_box`, before cutting. Once Upon and Gallows Humor needed this.
- **Two marks to watch on the TV:** Order in the Court (ORDER IN THE is small) and Corsets & Candlelight (long name, slim candle). Both were approved with a note to revisit if they do not hold up.
- **Names that changed length.** Jack-o'-Lantern Lounge now writes its full name (the old mark said Lantern Lounge). Cherry Blossom Hour now includes Hour. Cold Case Cable still reads Cold Case, and Kids' Odds & Ends still reads odds & ends.
- **Renamed channels keep their old keys.** Round 7 files the new marks under `open-road` (Riding Shotgun), `silver-screen` (Golden Age Matinee), `rabbit-ears` (Vertical Hold), `kung-fu-theater` (Fists & Fury) and `satire-desk` (Oddly Funny, channel 190). Each mark says the new name.
