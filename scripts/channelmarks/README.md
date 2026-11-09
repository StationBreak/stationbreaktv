# channelmarks

Generates StationBreak channel brand marks from the catalog and `brands.json`.
Rules: `docs/design/channel-identity.md`.

    pip install fonttools
    python3 scripts/channelmarks/brands.py \
        core/engine/src/commonMain/kotlin/tv/stationbreak/core/engine/lineup/CatalogV3Recipes.kt \
        scripts/channelmarks/brands.json \
        scripts/channelmarks/out-brands

Writes `out-brands/svg/` (colour masters, text outlined), `out-brands/svg-mono/` (all-white
variant) and `out-brands/contact-sheet.html`. Open the sheet and check the 1:1 strip and the
mono strip before accepting a mark.

`brands.json` entry shape:

    "<catalog key>": {
      "note": "face · colours (shown on the sheet)",
      "device": "circle_left | arc_above | rules | bars | rule_below | rule_below_dashed | ring | box | crescent_right | dot_after",
      "device_color": "#hex",
      "gap": 0.06,                      // between lines, fraction of a full-size line
      "lines": [
        {"rel": 1.0, "align": "c|l|r", "segs": [
          {"text": "WORD", "font": "<file stem in fonts/>", "color": "#hex", "tracking": 0.02, "skew": -12}
        ]}
      ]
    }

Fonts in `fonts/` are used only here; the app does not ship them. Licences in `fonts/licenses/`
(all SIL OFL 1.1 except Chewy, Luckiest Guy, Ultra and Special Elite, Apache 2.0).

`channelmarks.py` / `marks.json` are the superseded monochrome pass; delete when `brands.py`
covers the lineup.
