#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# Writes the synthetic clips the playback contract tests and the playback matrix use (spec 13):
#   synthetic-media.sh FFMPEG OUT_DIR
# FFMPEG is the ffmpeg to run (the contract suite passes the jellyfin-ffmpeg inside the server image). Each clip is 60 s
# long, named by its format only, and lives in its own folder under OUT_DIR, so a media server sees one movie per clip.
# Every picture is a generated test pattern and every sound a sine, so no title, file or person comes from anywhere.
# The output is deterministic for a given ffmpeg: fixed sources, one encoder thread, no metadata or timestamps written.
# No network is used. A clip whose encoder this ffmpeg lacks (AV1) is skipped with a note on stderr.
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: $0 FFMPEG OUT_DIR" >&2
  exit 2
fi
ffmpeg_bin=$1
out=$2

seconds=60
mkdir -p "$out"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Quiet, deterministic ffmpeg: no stdin, no banner, bit-exact muxing, no input metadata copied over. The last argument
# is the output; the output options go just before it, after the caller's inputs and options.
ff() {
  local target="${!#}"
  set -- "${@:1:$#-1}"
  "$ffmpeg_bin" -nostdin -hide_banner -loglevel error -y "$@" -threads 1 -fflags +bitexact -flags:v +bitexact \
    -flags:a +bitexact -map_metadata -1 "$target"
}

picture="-f lavfi -i testsrc=size=320x240:rate=5:duration=$seconds"
sound="-f lavfi -i sine=frequency=440:sample_rate=48000:duration=$seconds"
h264="-c:v libx264 -preset ultrafast -pix_fmt yuv420p"

# A clip's folder, created fresh; prints the path of the clip file inside it.
slot() {
  mkdir -p "$out/$1"
  echo "$out/$1/$2"
}

# Two short text tracks, English and French, at known times.
srt() {
  printf '1\n00:00:01,000 --> 00:00:09,000\n%s\n\n2\n00:00:10,000 --> 00:00:19,000\n%s\n' "$2" "$2 again" >"$1"
}
srt "$work/eng.srt" "Hello"
srt "$work/fre.srt" "Bonjour"

# 1. MP4 with the index first, so it plays while it downloads.
# shellcheck disable=SC2086
ff $picture $sound $h264 -c:a aac -movflags +faststart "$(slot h264-aac h264-aac.mp4)"
# 2. The same with the index at the end (the default), which a player must read before it can start.
# shellcheck disable=SC2086
ff $picture $sound $h264 -c:a aac "$(slot h264-aac-moov-end h264-aac-moov-end.mp4)"
# 3. Fragmented MP4.
# shellcheck disable=SC2086
ff $picture $sound $h264 -c:a aac -movflags frag_keyframe+empty_moov+default_base_moof \
  "$(slot h264-aac.fmp4 h264-aac.fmp4.mp4)"
# 4. Matroska with a Cues index, and 5. one muxed to a pipe, which cannot write the index back to the start.
# shellcheck disable=SC2086
ff $picture $sound $h264 -c:a aac "$(slot h264-aac-cues h264-aac-cues.mkv)"
# shellcheck disable=SC2086
ff $picture $sound $h264 -c:a aac -f matroska pipe:1 >"$(slot h264-aac-nocues h264-aac-nocues.mkv)"
# 6. 10-bit High, level 5.1, 3840x2160: past every limit a set-top box of this kind has. One frame a second keeps it small.
ff -f lavfi -i "testsrc=size=3840x2160:rate=1:duration=$seconds" $sound \
  -c:v libx264 -preset ultrafast -profile:v high10 -level:v 5.1 -pix_fmt yuv420p10le -c:a aac \
  "$(slot h264-high10-l51-3840 h264-high10-l51-3840.mkv)"
# 7. HEVC.
# shellcheck disable=SC2086
ff $picture $sound -c:v libx265 -preset ultrafast -pix_fmt yuv420p -x265-params log-level=none:pools=none:frame-threads=1 \
  -c:a aac "$(slot hevc-aac hevc-aac.mkv)"
# 8. AV1, with whichever encoder this ffmpeg has.
av1=""
for candidate in libsvtav1 libaom-av1 librav1e; do
  if "$ffmpeg_bin" -hide_banner -encoders 2>/dev/null | grep -q " $candidate "; then
    av1=$candidate
    break
  fi
done
if [ -n "$av1" ]; then
  # shellcheck disable=SC2086
  ff $picture $sound -c:v "$av1" -pix_fmt yuv420p -c:a aac "$(slot av1-aac av1-aac.mkv)"
else
  echo "synthetic-media: no AV1 encoder in this ffmpeg; av1-aac.mkv skipped" >&2
fi
# 9. Old MPEG-4 part 2 and MP3 in AVI.
# shellcheck disable=SC2086
ff $picture $sound -c:v mpeg4 -c:a libmp3lame "$(slot mpeg4-mp3 mpeg4-mp3.avi)"
# 10. DTS audio, 11. AC-3 audio.
# shellcheck disable=SC2086
ff $picture $sound $h264 -c:a dca -strict -2 "$(slot h264-dts h264-dts.mkv)"
# shellcheck disable=SC2086
ff $picture $sound $h264 -c:a ac3 "$(slot h264-ac3 h264-ac3.mkv)"
# 12. An SRT track inside the file, with H.264 and with HEVC.
# shellcheck disable=SC2086
ff $picture $sound -i "$work/eng.srt" $h264 -c:a aac -c:s srt -metadata:s:s:0 language=eng \
  "$(slot h264-aac-srt-embedded h264-aac-srt-embedded.mkv)"
# shellcheck disable=SC2086
ff $picture $sound -i "$work/eng.srt" -c:v libx265 -preset ultrafast -pix_fmt yuv420p \
  -x265-params log-level=none:pools=none:frame-threads=1 -c:a aac -c:s srt -metadata:s:s:0 language=eng \
  "$(slot hevc-aac-srt-embedded hevc-aac-srt-embedded.mkv)"
# 13. MP4 with an SRT file beside it (an external subtitle).
sidecar=$(slot h264-aac-srt-sidecar h264-aac.mp4)
# shellcheck disable=SC2086
ff $picture $sound $h264 -c:a aac -movflags +faststart "$sidecar"
cp "$work/eng.srt" "${sidecar%.mp4}.en.srt"

# 14. A picture subtitle track. ffmpeg cannot make a bitmap subtitle from text (nor encode PGS at all), so a one-track
# Matroska file holding one DVD-subtitle packet is written byte by byte and then copied in next to the video.
hex() { printf '%s' "$1" | od -An -v -tx1 | tr -d ' \n'; }
bytes() { printf "$(printf '%s' "$1" | sed 's/../\\x&/g')"; }
# One EBML element with an ID and data given in hex; data must be under 127 bytes.
el() {
  local data=$2
  printf '%s%02x%s' "$1" $((${#data} / 2 + 128)) "$data"
}
palette=$(hex "palette: 000000, ffffff, 808080, 404040")
header=$(el 4286 01)$(el 42f7 01)$(el 42f2 04)$(el 42f3 08)$(el 4282 "$(hex matroska)")$(el 4287 04)$(el 4285 02)
track=$(el ae "$(el d7 01)$(el 73c5 01)$(el 83 11)$(el 86 "$(hex S_VOBSUB)")$(el 63a2 "$palette")")
# Block: track 1, relative time 0, no flags, then a minimal packet (size, control offset, control sequence with an end).
packet=000600040000ff00
cluster=$(el e7 00)$(el a0 "$(el a1 "81000000$packet")$(el 9b 03e8)")
{
  bytes "$(printf '%s' "1a45dfa3")$(printf '%02x' $((${#header} / 2 + 128)))$header"
  bytes "18538067""01ffffffffffffff"
  bytes "$(el 1549a966 "$(el 2ad7b1 0f4240)")"
  bytes "$(el 1654ae6b "$track")"
  bytes "1f43b675""01ffffffffffffff""$cluster"
} >"$work/picture-subs.mkv"
# shellcheck disable=SC2086
ff $picture $sound $h264 -c:a aac -f matroska "$work/video-only.mkv"
ff -i "$work/video-only.mkv" -i "$work/picture-subs.mkv" -map 0 -map 1 -c copy \
  "$(slot h264-aac-dvdsub h264-aac-dvdsub.mkv)"

# 15. Two audio and two text subtitle tracks (English, French), the French audio flagged as the default.
# shellcheck disable=SC2086
ff $picture $sound -f lavfi -i "sine=frequency=880:sample_rate=48000:duration=$seconds" -i "$work/eng.srt" \
  -i "$work/fre.srt" -map 0:v -map 1:a -map 2:a -map 3:s -map 4:s $h264 -c:a aac -c:s srt \
  -metadata:s:a:0 language=eng -metadata:s:a:1 language=fre -metadata:s:s:0 language=eng \
  -metadata:s:s:1 language=fre -disposition:a:0 0 -disposition:a:1 default -disposition:s:0 0 -disposition:s:1 0 \
  "$(slot h264-aac-2audio-2subs h264-aac-2audio-2subs.mkv)"
