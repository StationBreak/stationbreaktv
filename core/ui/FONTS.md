# Bundled fonts

Provenance for the two fonts in `core:ui`. The checked-in hashes are in `fonts.sha256` (sha256sum format, paths
relative to `core/ui`); `FontManifestTest` fails if a bundled file stops matching.

- Upstream repository: `github.com/google/fonts`, pinned commit `e7db6ca603fd4862010383ff17ffd6cc4ffac1c0`.
- Licence: SIL Open Font License 1.1. The texts ship in `src/main/assets/licenses/`.

| Bundled file | Upstream path |
|---|---|
| `src/main/res/font/archivo_black.ttf` | `ofl/archivoblack/ArchivoBlack-Regular.ttf` |
| `src/main/res/font/space_mono_bold.ttf` | `ofl/spacemono/SpaceMono-Bold.ttf` |
| `src/main/assets/licenses/OFL-ArchivoBlack.txt` | `ofl/archivoblack/OFL.txt` |
| `src/main/assets/licenses/OFL-SpaceMono.txt` | `ofl/spacemono/OFL.txt` |

`OFL-ArchivoBlack.txt` is CRLF upstream (sha256 `3173acd82f8c6159b5b1037b539fcbd4edff68e65c2ea8b9412b5a5ca97b08ff`)
and is stored with LF line endings by `.gitattributes` (sha256
`4eb085b4f06e3c1625d8b577902bf639d480633635837bf2ed94d1a7b1b842f5`, the value in `fonts.sha256`). The text is
otherwise byte-identical to upstream.

## Rule

Fonts load only from the APK. Never from the server, the network or user content.

## Updating

1. Pick a new `google/fonts` commit and fetch the four files above from `raw.githubusercontent.com` at that commit
   only.
2. Compare the hashes with the old ones and review what changed; replace the OFL texts too.
3. Update `fonts.sha256` and the commit above in this file, and record the commit and hashes in the commit message.
