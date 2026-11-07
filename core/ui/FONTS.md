# Bundled fonts

Provenance for the fonts in `core:ui`. The checked-in hashes are in `fonts.sha256` (sha256sum format, paths
relative to `core/ui`); `FontManifestTest` fails if a bundled file stops matching, and also if a font or licence
file anywhere in the repo is missing from the manifest. Fonts are found by extension (`.ttf`, `.otf`, `.ttc`, `.woff`,
`.woff2`) anywhere under a module's `src/`; licence files are only checked under `assets/**/licenses/`. Dot-folders,
`build/` and nested checkouts are skipped.

## Archivo Black and Space Mono

- Upstream repository: `github.com/google/fonts`, pinned commit `e7db6ca603fd4862010383ff17ffd6cc4ffac1c0`.
- Licence (all bundled fonts): SIL Open Font License 1.1. The texts ship in `src/main/assets/licenses/`.

| Bundled file | Upstream path |
|---|---|
| `src/main/res/font/archivo_black.ttf` | `ofl/archivoblack/ArchivoBlack-Regular.ttf` |
| `src/main/res/font/space_mono_bold.ttf` | `ofl/spacemono/SpaceMono-Bold.ttf` |
| `src/main/assets/licenses/OFL-ArchivoBlack.txt` | `ofl/archivoblack/OFL.txt` |
| `src/main/assets/licenses/OFL-SpaceMono.txt` | `ofl/spacemono/OFL.txt` |

## Manrope

Manrope is the guide face. Upstream repository: `github.com/googlefonts/manrope`, pinned commit
`6f81ebecdf65e4463b798cc07b16a4f8d5216917`. That is the source google/fonts records for Manrope in
`ofl/manrope/METADATA.pb` at the pinned google/fonts commit above. The bundled files are the upstream static TTFs,
fetched from `raw.githubusercontent.com` at that commit only. Copyright 2018 The Manrope Project Authors. Nothing is
fetched at runtime. Live TV keeps Archivo Black and Space Mono.

| Bundled file | Upstream path (googlefonts/manrope at the pinned commit) |
|---|---|
| `src/main/res/font/manrope_regular.ttf` | `fonts/ttf/manrope-regular.ttf` |
| `src/main/res/font/manrope_medium.ttf` | `fonts/ttf/manrope-medium.ttf` |
| `src/main/res/font/manrope_semibold.ttf` | `fonts/ttf/manrope-semibold.ttf` |
| `src/main/res/font/manrope_bold.ttf` | `fonts/ttf/manrope-bold.ttf` |
| `src/main/assets/licenses/OFL-Manrope.txt` | `ofl/manrope/OFL.txt` (google/fonts at the pinned commit above) |

Tabular figures (times, ranges, the clock, channel numbers) come from the OpenType `tnum` feature in these same files,
set with `fontFeatureSettings = "tnum"`. There is no separate tabular cut and no variable font.

`OFL-ArchivoBlack.txt` is CRLF upstream (sha256 `3173acd82f8c6159b5b1037b539fcbd4edff68e65c2ea8b9412b5a5ca97b08ff`)
and is stored with LF line endings by `.gitattributes` (sha256
`4eb085b4f06e3c1625d8b577902bf639d480633635837bf2ed94d1a7b1b842f5`, the value in `fonts.sha256`). The text is
otherwise byte-identical to upstream.

## Rule

Fonts load only from the APK. Never from the server, the network or user content.

## Updating

1. Pick a new `google/fonts` commit and fetch the Archivo Black and Space Mono files above from
   `raw.githubusercontent.com` at that commit only.
2. For Manrope, read `ofl/manrope/METADATA.pb` at that google/fonts commit; its `source` block names the
   googlefonts/manrope repository and commit. Fetch the four `fonts/ttf/manrope-*.ttf` files from
   `raw.githubusercontent.com` at that source commit only. Check the `tnum` feature is still
   present in each file: `ttx -t GSUB -o - <file> | grep tnum` must print a `FeatureTag` line, or
   `python3 -c "import sys;from fontTools.ttLib import TTFont;print('tnum' in {r.FeatureTag for r in TTFont(sys.argv[1])['GSUB'].table.FeatureList.FeatureRecord})" <file>`
   must print `True`.
3. Compare the hashes with the old ones and review what changed; replace the OFL texts too.
4. Update `fonts.sha256` and the commits above in this file, and record the commits and hashes in the commit message.
