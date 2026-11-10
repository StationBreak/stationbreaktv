<!-- SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore -->
# Test assets

## `h264-aac-10s.mp4`

A 10 s synthetic clip (no third-party content): H.264 Constrained Baseline level 1.1, 320x240, 5 fps, with mono AAC-LC
audio at 48 kHz. It plays on an API 25 device without any codec negotiation, and `Media3VideoPlayerTest` serves it from a
MockWebServer.

Made from ffmpeg's built-in test sources:

```sh
ffmpeg -f lavfi -i testsrc=size=320x240:rate=5:duration=10 \
       -f lavfi -i sine=frequency=440:sample_rate=48000:duration=10 \
       -c:v libx264 -preset ultrafast -profile:v baseline -level 1.1 -pix_fmt yuv420p -threads 1 \
       -c:a aac -ac 1 -movflags +faststart \
       h264-aac-10s.mp4
```

The command that first produced the committed file was not recorded. This one was reconstructed from the file's stream
properties and its x264 settings (`ffprobe`, and the options string x264 writes into the file), and it reproduces them
with ffmpeg n9.0.2 (same codecs, profile, level, size, frame rate, duration and the same x264 options). The output is not
byte-identical (the AAC encoder differs by a few bytes per second), which does not matter to the test: it needs a clip
that plays, and 10 s of video to seek in.

## `h264-aac-2audio-sub-10s.mp4`

The same clip with two audio tracks (a 440 Hz tone tagged `eng`, an 880 Hz tone tagged `spa`) and one embedded text
subtitle (`mov_text`, tagged `eng`, one cue over the first 9 s), so `Media3SwitchTracksTest` can switch tracks while it
plays. Made from ffmpeg's built-in sources and a one-cue SRT (`1`, `00:00:00,000 --> 00:00:09,000`, `Test subtitle`):

```sh
ffmpeg -f lavfi -i testsrc=size=320x240:rate=5:duration=10 \
       -f lavfi -i sine=frequency=440:sample_rate=48000:duration=10 \
       -f lavfi -i sine=frequency=880:sample_rate=48000:duration=10 -i en.srt \
       -map 0:v -map 1:a -map 2:a -map 3:s \
       -c:v libx264 -preset ultrafast -profile:v baseline -level 1.1 -pix_fmt yuv420p -threads 1 \
       -c:a aac -ac 1 -c:s mov_text \
       -metadata:s:a:0 language=eng -metadata:s:a:1 language=spa -metadata:s:s:0 language=eng \
       -movflags +faststart h264-aac-2audio-sub-10s.mp4
```
