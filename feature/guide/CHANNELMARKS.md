# Bundled channel logos

Provenance for the channel logos in `feature/guide`. The checked-in hashes are in `channelmarks.sha256` (sha256sum
format, paths relative to `feature/guide`); `ChannelMarkManifestTest` fails if a bundled file stops matching, and also
if any file under a `src/*/assets/channelmarks/` folder of any module in the repo (Android merges assets across
modules), or a symlink there, is missing from the manifest. Dot-folders and `build/` are skipped at the repo walk.

## The pack

- Source repository: `github.com/StationBreak/stationbreaktv`, branch `design/channelmarks-assets`, pinned commit
  `35f03e466c1ac27adb3ae83d58a939f569ebe1d2`, folder `design/channelmarks/station-logos/`.
- Author: the design partner, StationBreak Look.
- Copied into the repo: 2026-11-07.
- Pack commit date: 2026-11-08T00:49:37Z (committed by a Cursor agent on the design branch).
- Licence: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore (the project's licence; owner decision 2026-11-07:
  the logos were made for this project, they are not third-party art). See ADR-0003.
- What they are: 97 fictional in-app stations; not the StationBreak company brand.

## What was taken

Only the 32 dp PNG renders, `sl_<NNN>_<key>_32dp.png` (97 files), unchanged and byte-identical to the pack blobs, in
`src/main/assets/channelmarks/`. Not taken: the SVG masters, the 512 px, 48 dp and 64 dp renders, the JSON files and
the sheets. Each file is 64 x 64 px and under 16 KB; the test checks both.

## How the art was made

The marks were produced by an AI-assisted design process (the project's design partner, StationBreak Look), directed
by the owner. The 97 SVG masters are composed of simple vector primitives (rectangles, circles and short paths) and
were not hand-drawn. They are rendered to PNG by generator scripts in the pack (`build_logos.py` and
`build_logos2.py`; SVG to cairosvg to PIL). The SVGs contain no metadata or comments and no path data longer than 400
characters. The pack's own spec says "flat vector, no third-party IP". The project's review checked all 97 SVGs for
icon-library markers on 2026-11-07 and found none.

## Rule

Logos load only from the APK. Never from the server, the network or user content.

## Manifest

```
cd feature/guide && sha256sum src/main/assets/channelmarks/*.png | LC_ALL=C sort -k2 > channelmarks.sha256
```

## Updating

1. Pick a newer commit of the pack branch. Copy only the `_32dp.png` files with
   `git show <commit>:design/channelmarks/station-logos/<file> > <dest>` (never a tool that re-encodes), and check
   each copy with `git hash-object` against the blob.
2. Remove files that left the pack, then regenerate the manifest with the command above.
3. Update the commit in this file and record it in the commit message.
