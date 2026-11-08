# Bundled channel logos

Provenance for the channel logos in `feature/guide`. The checked-in hashes are in `channelmarks.sha256` (sha256sum
format, paths relative to `feature/guide`); `ChannelMarkManifestTest` fails if a bundled file stops matching, and also
if any file under a `src/*/assets/channelmarks/` folder of this module is missing from the manifest. Dot-folders and
`build/` are skipped.

## The pack

- Source repository: `github.com/StationBreak/stationbreaktv`, branch `design/channelmarks-assets`, pinned commit
  `35f03e466c1ac27adb3ae83d58a939f569ebe1d2`, folder `design/channelmarks/station-logos/`.
- Author: the design partner, StationBreak Look.
- Date: 2026-11-08.
- Licence: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore (the project's licence; owner decision 2026-11-08:
  the logos were made for this project, they are not third-party art). See ADR-0003.
- What they are: 97 fictional in-app stations; not the StationBreak company brand.

## What was taken

Only the 32 dp PNG renders, `sl_<NNN>_<key>_32dp.png` (97 files), unchanged and byte-identical to the pack blobs, in
`src/main/assets/channelmarks/`. Not taken: the SVG masters, the 512 px, 48 dp and 64 dp renders, the JSON files and
the sheets. Each file is 64 x 64 px and under 16 KB; the test checks both.

## Rule

Logos load only from the APK. Never from the server, the network or user content.

## Manifest

```
cd feature/guide && sha256sum src/main/assets/channelmarks/*.png | sort -k2 > channelmarks.sha256
```

## Updating

1. Pick a newer commit of the pack branch. Copy only the `_32dp.png` files with
   `git show <commit>:design/channelmarks/station-logos/<file> > <dest>` (never a tool that re-encodes), and check
   each copy with `git hash-object` against the blob.
2. Remove files that left the pack, then regenerate the manifest with the command above.
3. Update the commit in this file and record it in the commit message.
