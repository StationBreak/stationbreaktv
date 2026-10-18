#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Renders the placeholder art in art/placeholder/ to the app's PNGs and the README banner, at exact pixel sizes.
#   scripts/render-placeholder-art.sh <font-dir>
# <font-dir> holds Archivo Black (ArchivoBlack-Regular.ttf) and Space Mono Bold (SpaceMono-Bold.ttf), both SIL OFL 1.1
# from github.com/google/fonts (ofl/archivoblack, ofl/spacemono). They are not committed. Needs rsvg-convert, magick.
set -euo pipefail

if [ "$#" -ne 1 ] || [ ! -d "$1" ]; then
  echo "usage: $0 <font-dir>" >&2
  exit 2
fi
fonts="$(cd "$1" && pwd)"
cd "$(git rev-parse --show-toplevel)"

# Only the given fonts, so a missing one fails loudly below instead of falling back to a system font.
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cat >"$work/fonts.conf" <<CONF
<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig><dir>$fonts</dir><cachedir>$work/cache</cachedir></fontconfig>
CONF
export FONTCONFIG_FILE="$work/fonts.conf"
for family in "Archivo Black" "Space Mono:bold"; do
  if [ -z "$(fc-list "$family")" ]; then
    echo "font not found in $fonts: $family" >&2
    exit 1
  fi
done

src=art/placeholder
res=app/src/main/res

# render <svg> <width> <height> <out.png>: exact size, metadata stripped so the bytes are reproducible.
render() {
  rsvg-convert -w "$2" -h "$3" "$1" -o "$work/out.png"
  magick "$work/out.png" -strip -define png:exclude-chunks=date,time "$4"
}

# TV banner: 320x180 dp, shipped at xhdpi only (640x360 px).
render "$src/tv-banner.svg" 640 360 "$res/drawable-xhdpi/banner.png"

# Launcher icon: 48 dp legacy PNG per density (API 25), and the adaptive foreground for API 26+: the card fills the
# 72 dp visible area of the 108 dp layer, on a transparent margin over the card's own ground colour.
for d in mdpi:1 hdpi:1.5 xhdpi:2 xxhdpi:3 xxxhdpi:4; do
  name="${d%%:*}"
  scale="${d##*:}"
  icon=$(awk "BEGIN { print 48 * $scale }")
  visible=$(awk "BEGIN { print 72 * $scale }")
  layer=$(awk "BEGIN { print 108 * $scale }")
  render "$src/launcher-icon.svg" "$icon" "$icon" "$res/mipmap-$name/ic_launcher.png"
  rsvg-convert -w "$visible" -h "$visible" "$src/launcher-icon.svg" -o "$work/fg.png"
  magick "$work/fg.png" -background none -gravity center -extent "${layer}x${layer}" -strip \
    -define png:exclude-chunks=date,time "$res/mipmap-$name/ic_launcher_foreground.png"
done

# README banner, at 2x.
render "$src/repo-banner.svg" 1920 1080 "$src/repo-banner.png"
