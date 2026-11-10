# channelmarks

Generates StationBreak channel brand marks from the catalog and `brands.json`.
Rules: `docs/design/channel-identity.md`.

    pip install fonttools cairosvg      # cairosvg needs the cairo library; only --png uses it
    python3 scripts/channelmarks/brands.py \
        core/engine/src/commonMain/kotlin/tv/stationbreak/core/engine/lineup/CatalogV3Recipes.kt \
        scripts/channelmarks/brands.json \
        scripts/channelmarks/out-brands \
        --png core/ui/src/main/assets/channelmarks

Writes `out-brands/svg/` (colour masters, text outlined), `out-brands/svg-mono/` (all-white
variant) and `out-brands/contact-sheet.html`. Open the sheet and check the 1:1 strip and the
mono strip before accepting a mark.

With `--png <dir>` it also renders each mark to PNG with cairosvg, at 48 dp and 64 dp at 2x (96 by 54 and
128 by 72 px), transparent, colour and mono: `sl_<NNN>_<key>_<N>dp.png` and `sl_<NNN>_<key>_mono_<N>dp.png`. The
files are stripped to the PNG chunks the app's manifest test allows. After a run, regenerate
`core/ui/channelmarks.sha256`; the steps are in `core/ui/CHANNELMARKS.md`.

The twelve first-review masters in `out-brands` (Sing-Along Station, Saturday Morning, Snowglobe, Punchline,
Dial-Up, Always December, Order in the Court, Silver Screen, The Midnight Projector, Neon Rain, Masala Hour,
Graveyard Shift) carry a metadata block that a regenerate does not write. Their shapes are identical; restore the
committed files with `git checkout -- <file>` if the diff shows only that block.

`brands.json` entry shape:

    "<catalog key>": {
      "note": "face · colours (shown on the sheet)",
      "device": "circle_left | arc_above | rules | bars | rule_below | rule_below_dashed | ring | box | crescent_right | dot_after",
      "device_color": "#hex",
      "device_size": 0.22,              // circle_left only: radius as a fraction of the mark height
      "gap": 0.06,                      // between lines, fraction of a full-size line
      "lines": [
        {"rel": 1.0, "align": "c|l|r", "segs": [
          {"text": "WORD", "font": "<file stem in fonts/>", "color": "#hex", "tracking": 0.02, "skew": -12}
        ]}
      ]
    }

Fonts in `fonts/` are used only here; the app does not ship them. Licences in `fonts/licenses/`
(all SIL OFL 1.1 except Chewy, Luckiest Guy, Ultra and Special Elite, Apache 2.0).
