# Bundled fonts

Provenance for the fonts in `core:ui`. The checked-in hashes are in `fonts.sha256` (sha256sum format, paths
relative to `core/ui`); `FontManifestTest` fails if a bundled file stops matching, and also if a font or licence
file anywhere in the repo is missing from the manifest. Fonts are found by extension (`.ttf`, `.otf`, `.ttc`, `.woff`,
`.woff2`) anywhere under a module's `src/`; licence files are only checked under `assets/**/licenses/`. Dot-folders,
`build/` and nested checkouts are skipped.

## Space Mono

- Upstream repository: `github.com/google/fonts`, pinned commit `e7db6ca603fd4862010383ff17ffd6cc4ffac1c0`.
- Licence (all bundled fonts): SIL Open Font License 1.1. The texts ship in `src/main/assets/licenses/`.

| Bundled file | Upstream path |
|---|---|
| `src/main/res/font/space_mono_bold.ttf` | `ofl/spacemono/SpaceMono-Bold.ttf` |
| `src/main/assets/licenses/OFL-SpaceMono.txt` | `ofl/spacemono/OFL.txt` |

## Manrope

Manrope is the guide face. Upstream repository: `github.com/googlefonts/manrope`, pinned commit
`6f81ebecdf65e4463b798cc07b16a4f8d5216917`. That is the source google/fonts records for Manrope in
`ofl/manrope/METADATA.pb` at the pinned google/fonts commit above. The bundled files are the upstream static TTFs,
fetched from `raw.githubusercontent.com` at that commit only. Copyright 2018 The Manrope Project Authors. Nothing is
fetched at runtime. Live TV keeps Space Mono.

| Bundled file | Upstream path (googlefonts/manrope at the pinned commit) |
|---|---|
| `src/main/res/font/manrope_regular.ttf` | `fonts/ttf/manrope-regular.ttf` |
| `src/main/res/font/manrope_medium.ttf` | `fonts/ttf/manrope-medium.ttf` |
| `src/main/res/font/manrope_semibold.ttf` | `fonts/ttf/manrope-semibold.ttf` |
| `src/main/res/font/manrope_bold.ttf` | `fonts/ttf/manrope-bold.ttf` |
| `src/main/assets/licenses/OFL-Manrope.txt` | `ofl/manrope/OFL.txt` (google/fonts at the pinned commit above) |

Tabular figures (times, ranges, the clock, channel numbers) come from the OpenType `tnum` feature in these same files,
set with `fontFeatureSettings = "tnum"`. There is no separate tabular cut and no variable font.

## Ranchers

Ranchers is the brand face (DESIGN.md, Marks), used for the "Station Break" bug in the guide's details panel. Upstream
repository: `github.com/google/fonts`, pinned commit `90abd17b4f97671435798b6147b698aa9087612f` (not the commit pinned
above for the other fonts). Unmodified. Copyright 2012 Pablo Impallari and Brenda Gallo, Reserved Font Name "Ranchers",
SIL Open Font License 1.1.

| Bundled file | Upstream path (google/fonts at the commit above) |
|---|---|
| `src/main/res/font/ranchers_regular.ttf` | `ofl/ranchers/Ranchers-Regular.ttf` |
| `src/main/assets/licenses/OFL-Ranchers.txt` | `ofl/ranchers/OFL.txt` |

The font file is byte-identical to upstream (sha256 `19568aba8965664d1bc6f5a48c24d269c97501eed87af26b912f42b6e6f22adc`).
`OFL-Ranchers.txt` is CRLF upstream (sha256 `0fc8e6675568c02aac6b41dd3cccaf2264fc5969601effe68e09b26635879b2c`) and is
stored with LF line endings by `.gitattributes` (sha256 `53612c24b8b4652a2a71191e89a0e32e85b8c062ad9acb80cc46a6fd4f55a9c9`,
the value in `fonts.sha256`). The text is otherwise byte-identical to upstream.

## Rule

Fonts load only from the APK. Never from the server, the network or user content.

## Updating

1. Pick a new `google/fonts` commit and fetch the Space Mono files above from
   `raw.githubusercontent.com` at that commit only.
2. For Manrope, read `ofl/manrope/METADATA.pb` at that google/fonts commit; its `source` block names the
   googlefonts/manrope repository and commit. Fetch the four `fonts/ttf/manrope-*.ttf` files from
   `raw.githubusercontent.com` at that source commit only. Check the `tnum` feature is still
   present in each file: `ttx -t GSUB -o - <file> | grep tnum` must print a `FeatureTag` line, or
   `python3 -c "import sys;from fontTools.ttLib import TTFont;print('tnum' in {r.FeatureTag for r in TTFont(sys.argv[1])['GSUB'].table.FeatureList.FeatureRecord})" <file>`
   must print `True`.
3. Compare the hashes with the old ones and review what changed; replace the OFL texts too.
4. Update `fonts.sha256` and the commits above in this file, and record the commits and hashes in the commit message.
5. Ranchers is pinned to its own `google/fonts` commit (see its section above) and does not move with the pin used
   for Space Mono: bump it on its own, only when the brand face itself changes. Fetch
   `ofl/ranchers/Ranchers-Regular.ttf` and `ofl/ranchers/OFL.txt` from `raw.githubusercontent.com` at that commit only.
   The OFL text is CRLF upstream and stored as LF, so re-hash the stored file (as for the other OFL texts) and record both
   hashes in its section.
