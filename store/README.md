# Appstore images

The Amazon listing images, rendered from `art/brand/` by `scripts/render-brand-art.sh`. The PNGs are generated.
Change the SVG, then run the script. They are the same project art as the launcher icon and the TV banner.

Checked against Amazon's store listing page on 2026-11-21
([Step 2](https://developer.amazon.com/docs/app-submission/add-store-listing-information.html), last modified
2026-11-16).

| File | Size | Slot | Source |
| --- | --- | --- | --- |
| `icon-114.png` | 114×114 PNG | Small icon, website and tablets | `icon-main.svg` |
| `icon-512.png` | 512×512 PNG | Large icon, website and tablets | `icon-main.svg` |
| `fire-tv-icon-1280x720.png` | 1280×720 PNG, opaque | Fire TV app icon | `tv-banner.svg` |

The small and large icons are the launcher icon: cream Stan on the orange tile, rounded corners and transparent
corners included. The Fire TV icon is the brand banner: Stan on the left, "Station Break" stacked, the tagline
underneath, on the dark ground.

Amazon's Fire TV icon keeps the subject and the title inside an 882×448 area. Centered on 1280×720, that box is
x 199–1080 and y 136–583. On this file Stan's left side, ear tips and shoes, and the right edge of the wordmark,
sit outside it. The file is `tv-banner.svg` at its own size.

The launcher mipmaps and the Android TV banner (`app/src/main/res`) are the same two SVGs. Screenshots are in
`docs/store/screenshots/`.

Two slots have no source in `art/brand/` and are not in this folder:

- The Fire TV background, 1920×1080, is required. Amazon asks for the subject on the right and no logo or title.
  The brand banner is Stan on the left with the wordmark.
- The promotional image, 1024×500, is optional.
