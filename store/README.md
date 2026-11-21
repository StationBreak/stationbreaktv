# Appstore images

The Amazon listing images. The icons and the Fire TV app icon are rendered from `art/brand/` by
`scripts/render-brand-art.sh`. Those PNGs are generated: change the SVG, then run the script. They are the same
project art as the launcher icon and the TV banner.

Checked against Amazon's store listing page on 2026-11-21
([Step 2](https://developer.amazon.com/docs/app-submission/add-store-listing-information.html), last modified
2026-11-16).

| File | Size | Slot | Source |
| --- | --- | --- | --- |
| `icon-114.png` | 114×114 PNG | Small icon, website and tablets | `icon-main.svg` |
| `icon-512.png` | 512×512 PNG | Large icon, website and tablets | `icon-main.svg` |
| `fire-tv-icon-1280x720.png` | 1280×720 PNG, opaque | Fire TV app icon | `tv-banner.svg` |
| `fire-tv-background-1920x1080.png` | 1920×1080 PNG, opaque, no text | Fire TV background | `fire-tv-background-1920x1080.svg` |

The small and large icons are Stan from `icon-main.svg`, at the same size on the tile, on a full-bleed `#E8642C`
square. No rounded corners and no transparency. The launcher mipmaps stay the rounded tile.

The Fire TV icon keeps the banner's ground across the whole 1280×720. Stan, "Station Break" and the tagline are
the same lockup, scaled and moved together so they sit inside the centred 882×448 safe area (x 199–1080, y 136–583).

The Fire TV background is the supplied PNG: the waving Stan, with no wordmark. `fire-tv-background-1920x1080.svg` is
its vector source, composited from the `art/brand/` vectors and kept beside the PNG. The render script leaves this
PNG as supplied.

The launcher mipmaps and the Android TV banner (`app/src/main/res`) are rendered from the same SVGs, without this
store-only fit. Screenshots are in `docs/store/screenshots/`.

Not in this folder: the optional 1024×500 promotional image.
