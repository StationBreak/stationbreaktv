#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Renders the brand art in art/brand/ to the app's PNGs, the README banner and the Amazon listing
# images in store/, at exact pixel sizes.
#   scripts/render-brand-art.sh <font-dir>
# <font-dir> holds Ranchers (Ranchers-Regular.ttf) and Space Mono Bold (SpaceMono-Bold.ttf), both SIL OFL 1.1 from
# github.com/google/fonts (ofl/ranchers, ofl/spacemono). The same files are bundled in core/ui/src/main/res/font/
# (ranchers_regular.ttf, space_mono_bold.ttf). Needs rsvg-convert, ImageMagick (magick, or convert for v6) and fontconfig (fc-list).
set -euo pipefail

if [ "$#" -ne 1 ] || [ ! -d "$1" ]; then
  echo "usage: $0 <font-dir>" >&2
  exit 2
fi
fonts="$(cd "$1" && pwd)"
cd "$(git rev-parse --show-toplevel)"

# ImageMagick 7 is `magick`; ImageMagick 6 only has `convert`.
im() {
  if command -v magick >/dev/null 2>&1; then magick "$@"; else convert "$@"; fi
}
for tool in rsvg-convert fc-list; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "missing tool: $tool" >&2
    exit 1
  fi
done
if ! command -v magick >/dev/null 2>&1 && ! command -v convert >/dev/null 2>&1; then
  echo "missing tool: magick (or convert)" >&2
  exit 1
fi

# Only the given fonts, so a missing one fails loudly below instead of falling back to a system font.
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cat >"$work/fonts.conf" <<CONF
<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig><dir>$fonts</dir><cachedir>$work/cache</cachedir></fontconfig>
CONF
export FONTCONFIG_FILE="$work/fonts.conf"
for family in "Ranchers" "Space Mono:bold"; do
  if [ -z "$(fc-list "$family")" ]; then
    echo "font not found in $fonts: $family" >&2
    exit 1
  fi
done

src=art/brand
res=app/src/main/res

# render <svg> <width> <height> <out.png>: exact size, metadata stripped so the bytes are reproducible.
render() {
  rsvg-convert -w "$2" -h "$3" "$1" -o "$work/out.png"
  im "$work/out.png" -strip -define png:exclude-chunks=date,time "$4"
}

# TV banner: 320x180 dp, shipped at xhdpi only (640x360 px), from the 1280x720 brand banner.
render "$src/tv-banner.svg" 640 360 "$res/drawable-xhdpi/banner.png"

# Launcher icon, per density: the 48 dp legacy PNG (API 25: the main orange icon with its rounded tile), and the
# adaptive foreground (API 26+) at 108 dp. icon-foreground.svg is icon-main.svg without the tile, with Stan's head
# fitted to the 66 dp safe circle; the orange ground is the ic_launcher_background colour.
for d in mdpi:1 hdpi:1.5 xhdpi:2 xxhdpi:3 xxxhdpi:4; do
  name="${d%%:*}"
  scale="${d##*:}"
  icon=$(awk "BEGIN { print 48 * $scale }")
  layer=$(awk "BEGIN { print 108 * $scale }")
  render "$src/icon-main.svg" "$icon" "$icon" "$res/mipmap-$name/ic_launcher.png"
  render "$src/icon-foreground.svg" "$layer" "$layer" "$res/mipmap-$name/ic_launcher_foreground.png"
done

# Amazon listing images. Each size is rendered from the SVG, not scaled from another PNG.
# The launcher mipmaps stay the rounded tile. These two are the same Stan on a full-bleed orange square:
# no rounded corners, no transparency. Stan keeps the proportion he has inside icon-main.svg.
mkdir -p store
render_store_icon() {
  rsvg-convert -w "$2" -h "$3" "$1" -o "$work/icon.png"
  im -size "${2}x${3}" 'xc:#E8642C' "$work/icon.png" -compose over -composite \
    -alpha off -strip -define png:exclude-chunks=date,time "$4"
}
render_store_icon "$src/icon-main.svg" 114 114 store/icon-114.png
render_store_icon "$src/icon-main.svg" 512 512 store/icon-512.png

# Fire TV app icon. The ground stays the full 1280x720 banner. Stan, the wordmark, the tagline and his
# foot shadow scale and move as one lockup into Amazon's centred 882x448 safe area (x 199-1080, y 136-583).
# A 2px inset keeps the re-rasterized edge inside that box. art/brand/tv-banner.svg is not edited.
marker='<rect width="1280" height="720" fill="url(#bnh)"/>'
banner_svg="$(cat "$src/tv-banner.svg")"
banner_head="${banner_svg%%"$marker"*}$marker"
banner_lockup="${banner_svg#*"$marker"}"
banner_lockup="${banner_lockup%</svg>}"
printf '%s\n' \
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1280 720" width="1280" height="720">' \
  "$banner_lockup" \
  '</svg>' >"$work/lockup.svg"
rsvg-convert -w 1280 -h 720 "$work/lockup.svg" -o "$work/lockup.png"
# trim's corner is transparent, so the report is the lockup's bounds: width height +x +y.
read -r bw bh ox oy < <(im "$work/lockup.png" -trim -format '%w %h %X %Y\n' info: | tr -d '+')
read -r fit_s fit_x fit_y < <(awk -v bw="$bw" -v bh="$bh" -v x0="$ox" -v y0="$oy" 'BEGIN {
  inset = 2
  tw = 882 - 2 * inset
  th = 448 - 2 * inset
  s = tw / bw
  if (th / bh < s) s = th / bh
  nw = bw * s
  nh = bh * s
  destx = 199 + (882 - nw) / 2
  desty = 136 + (448 - nh) / 2
  printf "%.6f %.4f %.4f\n", s, destx - s * x0, desty - s * y0
}')
printf '%s\n' \
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1280 720" width="1280" height="720">' \
  "<g transform=\"translate($fit_x $fit_y) scale($fit_s)\">" \
  "$banner_lockup" \
  '</g></svg>' >"$work/lockup-fit.svg"
rsvg-convert -w 1280 -h 720 "$work/lockup-fit.svg" -o "$work/lockup-fit.png"
read -r fw fh fx fy < <(im "$work/lockup-fit.png" -trim -format '%w %h %X %Y\n' info: | tr -d '+')
right=$((fx + fw - 1))
bottom=$((fy + fh - 1))
if [ "$fx" -lt 199 ] || [ "$fy" -lt 136 ] || [ "$right" -gt 1080 ] || [ "$bottom" -gt 583 ]; then
  echo "Fire TV lockup is outside the safe area: x $fx-$right y $fy-$bottom" >&2
  exit 1
fi
printf '%s\n' \
  "$banner_head" \
  "<g transform=\"translate($fit_x $fit_y) scale($fit_s)\">" \
  "$banner_lockup" \
  '</g></svg>' >"$work/banner-fit.svg"
render "$work/banner-fit.svg" 1280 720 store/fire-tv-icon-1280x720.png

# README banner, at 1920x1080 (1.5x the SVG).
render "$src/tv-banner.svg" 1920 1080 "$src/repo-banner.png"
