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
# The small and large icons are the launcher icon. The Fire TV app icon is the brand banner at its native 1280x720.
mkdir -p store
render "$src/icon-main.svg" 114 114 store/icon-114.png
render "$src/icon-main.svg" 512 512 store/icon-512.png
render "$src/tv-banner.svg" 1280 720 store/fire-tv-icon-1280x720.png

# README banner, at 1920x1080 (1.5x the SVG).
render "$src/tv-banner.svg" 1920 1080 "$src/repo-banner.png"
