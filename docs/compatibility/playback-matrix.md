# Playback matrix

What the StationBreak player did with a spread of files on real devices: which route the server chose (direct play,
direct stream or transcode), whether playback started where it was asked to, and whether the sound and subtitles came
through. Each section is one set of files on one device; each row is one file. The synthetic section plays generated
test clips made for this purpose; the real section plays a few files from the owner's own library, named here only by
their format.

How to read it: Expected is the route the player's own device model predicts and the server's first answer should
match; Route is the route that finally played. They differ when Fallback is not "no". Fallback "seek-miss" means a
direct play or direct stream landed away from the requested start and was replaced once by a transcode, which is by
design (ADR-0016) and passes; in the synthetic section it passes only for the one clip expected to do so, an MKV
without a Cues index. Fallback "load-failed" means the first attempt did not load or play (the Error column says why)
and always fails the row. A difference between Expected and Route with Fallback "no" is a finding.

Start (synthetic section only) is where playback was asked to begin. Position error is how far the first frame was
from that point (a dash when it landed within tolerance). Audio heard means the media clock advanced during a 15 s
watch. Subtitle is how the player received the subtitle it chose, which can differ by route for the same file.
Subtitle shown means text cues arrived or the server burned the subtitle in; real items start at a random point, so a
text subtitle with no line in the 15 s window is reported ("no cue seen") but not graded. Transcode reasons is a dash
while the server reports its reasons only inside a transcoding URL the player does not use. A dash elsewhere means the
column does not apply or nothing was reported.

Rows for audio the device cannot play (Expected: direct stream) assume an account with default audio-track playback
turned on: the server offers a direct stream for such audio only then. The device runs use such an account.

Timings (first frame, playing) are measured on a non-debuggable build, on the device as it is, and are not graded: a
slow device is slow, and the number is there to be read, not to pass or fail. Only the Result column grades a row.

## This run

The two devices were measured on different days at different commits, so their rows are not from one build. Both
runs used plan seed 1 and the benchmark build.

- AFTJMST12 is the Insignia Fire TV Edition (API 25), the gate device. Measured on 2026-10-22 at commit 2fe3654 (APK
  sha256 `1c0f9820f687efc4768faa763ed8660a7e01f462f07c9dcb1ef65ff81ac985fa`).
- AFTKRT is a Fire TV Stick (API 30). Measured on 2026-10-23 at commit 645fd93 (APK sha256
  `7997f554ca86ed673989bd1978206e96ed78152256289595b2ed97ea17f05ade`).
- The synthetic set is 18 clips, made by [`synthetic-media.sh`](../../scripts/playback-matrix/synthetic-media.sh). Rows
  that look alike are different clips, differing in ways the columns do not show:
  - the three MP4 rows with no subtitle: index at the front, index at the end, and fragmented MP4;
  - the MKV rows with no subtitle: with a Cues index, without one (the seek-miss row), with a picture-subtitle track
    that is not chosen (so Subtitle reads "none"), and with two audio tracks where the preferred one is second;
  - the subtitle rows: text in the stream (H.264 and HEVC), a sidecar SRT next to an MP4, and two audio and two
    subtitle tracks;
  - the DTS rows: H.264 with DTS, HEVC with DTS, and H.264 with DTS plus an embedded SRT, which the server delivers
    as a separate text track, so its Subtitle column reads "text-separate".
- Known failures and findings:
  - AFTJMST12: the real AVI (MPEG-4 Part 2) does not decode and plays through the transcode fallback
    ([#340](https://github.com/StationBreak/stationbreaktv/issues/340)).
  - AFTJMST12: HEVC with DTS reports direct stream, but its first frame took about 3.7 s against about 0.3 s for H.264
    with DTS. That suggests, unconfirmed, that the server re-encodes the video behind the direct-stream label
    ([#351](https://github.com/StationBreak/stationbreaktv/issues/351)).
  - AFTKRT: all three DTS clips fail the passthrough start (ERROR_CODE_AUDIO_TRACK_INIT_FAILED) and play through the
    transcode fallback ([#344](https://github.com/StationBreak/stationbreaktv/issues/344)).
  - Both devices: real rows that use an audio-track override (the HEVC/TrueHD row and, on AFTJMST12, the H.264/FLAC
    row) were transcoded by the server, with no fallback, where the player expected direct stream (AFTJMST12) or
    direct play (AFTKRT), so they fail. The expectation for an overridden track does not yet match the server's choice
    ([#357](https://github.com/StationBreak/stationbreaktv/issues/357)).
  - AFTKRT: the HEVC/TrueHD override row took about 16 s to its first frame, against about 5 s for the same file on
    AFTJMST12 (not graded). It was also silent to a person listening at the TV, although Audio heard reads yes: that
    column measures the media clock, not sound ([#355](https://github.com/StationBreak/stationbreaktv/issues/355)).

## synthetic: AFTJMST12

- Device: AFTJMST12 (API 25)
- App: 0.0.1, a non-debuggable build
- Jellyfin: 12.2.0
- Audio sink: passthrough=ac3+eac3;channels=10
- Passed 18 of 18 (1 via fallback)

| Container | Video | Audio | Subtitle | Route | Expected | Transcode reasons | Burn-in | Fallback | Start (ms) | Position error (ms) | First frame (ms) | Playing (ms) | Stalls | Audio heard | Subtitle shown | Result | Error |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| mp4 | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 2291 | 2321 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | text-in-stream | direct-play | direct-play | — | no | no | 30000 | — | 1241 | 1251 | 0 | yes | yes | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 1105 | 1113 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 1019 | 1025 | 0 | yes | — | pass | — |
| mp4 | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 1068 | 1074 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | none | transcode | direct-play | — | no | seek-miss | 30000 | — | 3988 | 4010 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 2011 | 2021 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | text-in-stream | direct-play | direct-play | — | no | no | 30000 | — | 985 | 992 | 0 | yes | yes | pass | — |
| mp4 | h264 320x240 8bit | aac 1ch | text-separate | direct-play | direct-play | — | no | no | 30000 | — | 1077 | 1083 | 0 | yes | yes | pass | — |
| mp4 | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 1093 | 1102 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | ac3 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 1053 | 1066 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | dts 1ch | none | direct-stream | direct-stream | — | no | no | 30000 | — | 316 | 326 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | dts 1ch | text-separate | direct-stream | direct-stream | — | no | no | 30000 | — | 388 | 400 | 0 | yes | yes | pass | — |
| mkv | h264 3840x2160 10bit | aac 1ch | none | transcode | transcode | — | no | no | 30000 | — | 1505 | 1517 | 0 | yes | — | pass | — |
| mkv | hevc 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 1087 | 1095 | 0 | yes | — | pass | — |
| mkv | hevc 320x240 8bit | aac 1ch | text-in-stream | direct-play | direct-play | — | no | no | 30000 | — | 1006 | 1011 | 0 | yes | yes | pass | — |
| mkv | hevc 320x240 8bit | dts 1ch | none | direct-stream | direct-stream | — | no | no | 30000 | — | 3665 | 3674 | 0 | yes | — | pass | — |
| avi | mpeg4 320x240 8bit | mp3 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 427 | 431 | 0 | yes | — | pass | — |

## synthetic: AFTKRT

- Device: AFTKRT (API 30)
- App: 0.0.1, a non-debuggable build
- Jellyfin: 12.2.0
- Audio sink: passthrough=ac3+dts+eac3+truehd;channels=10
- Passed 15 of 18 (1 via fallback)

| Container | Video | Audio | Subtitle | Route | Expected | Transcode reasons | Burn-in | Fallback | Start (ms) | Position error (ms) | First frame (ms) | Playing (ms) | Stalls | Audio heard | Subtitle shown | Result | Error |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| mp4 | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 1171 | 1186 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | text-in-stream | direct-play | direct-play | — | no | no | 30000 | — | 1119 | 1146 | 0 | yes | yes | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 673 | 687 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 657 | 669 | 0 | yes | — | pass | — |
| mp4 | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 674 | 678 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | none | transcode | direct-play | — | no | seek-miss | 30000 | — | 4065 | 4106 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 615 | 621 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | text-in-stream | direct-play | direct-play | — | no | no | 30000 | — | 615 | 622 | 0 | yes | yes | pass | — |
| mp4 | h264 320x240 8bit | aac 1ch | text-separate | direct-play | direct-play | — | no | no | 30000 | — | 553 | 563 | 0 | yes | yes | pass | — |
| mp4 | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 711 | 717 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | ac3 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 700 | 830 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | dts 1ch | none | transcode | direct-play | — | no | load-failed | 30000 | — | 6040 | 6054 | 0 | yes | — | FAIL | fallback:load:ERROR_CODE_AUDIO_TRACK_INIT_FAILED |
| mkv | h264 320x240 8bit | dts 1ch | text-separate | transcode | direct-play | — | no | load-failed | 30000 | — | 5825 | 5845 | 0 | yes | yes | FAIL | fallback:load:ERROR_CODE_AUDIO_TRACK_INIT_FAILED |
| mkv | h264 3840x2160 10bit | aac 1ch | none | transcode | transcode | — | no | no | 30000 | — | 1082 | 1094 | 0 | yes | — | pass | — |
| mkv | hevc 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 585 | 588 | 0 | yes | — | pass | — |
| mkv | hevc 320x240 8bit | aac 1ch | text-in-stream | direct-play | direct-play | — | no | no | 30000 | — | 627 | 633 | 0 | yes | yes | pass | — |
| mkv | hevc 320x240 8bit | dts 1ch | none | transcode | direct-play | — | no | load-failed | 30000 | — | 5961 | 5973 | 0 | yes | — | FAIL | fallback:load:ERROR_CODE_AUDIO_TRACK_INIT_FAILED |
| avi | mpeg4 320x240 8bit | mp3 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 276 | 308 | 0 | yes | — | pass | — |

## real: AFTJMST12

- Device: AFTJMST12 (API 25)
- App: 0.0.1, a non-debuggable build
- Jellyfin: 12.2.0
- Audio sink: passthrough=ac3+eac3;channels=10
- Passed 6 of 9 (0 via fallback)

| Container | Video | Audio | Subtitle | Route | Expected | Transcode reasons | Burn-in | Fallback | Position error (ms) | First frame (ms) | Playing (ms) | Stalls | Audio heard | Subtitle shown | Result | Error |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| mkv | hevc 1920x804 10bit | truehd 8ch | none | transcode | direct-stream | — | no | no | — | 5014 | 5035 | 0 | yes | — | FAIL | — |
| mkv | h264 1920x1080 8bit | flac 2ch | none | transcode | direct-stream | — | no | no | — | 1603 | 1857 | 0 | yes | — | FAIL | — |
| mp4 | h264 560x240 8bit | aac 2ch | text-separate | direct-play | direct-play | — | no | no | — | 1479 | 1493 | 0 | yes | yes | pass | — |
| avi | mpeg4 496x368 8bit | mp3 2ch | text-separate | transcode | direct-play | — | no | load-failed | — | 5792 | 5802 | 0 | yes | no cue seen | FAIL | fallback:load:ERROR_CODE_DECODING_FAILED |
| mkv | mpeg2video 720x480 8bit | ac3 2ch | image | transcode | transcode | — | yes | no | — | 4444 | 4463 | 0 | yes | yes | pass | — |
| mkv | vc1 1920x1080 8bit | dts 6ch | none | transcode | transcode | — | no | no | — | 5421 | 5707 | 0 | yes | — | pass | — |
| mkv | av1 1920x1080 8bit | opus 2ch | none | transcode | transcode | — | no | no | — | 2137 | 2148 | 0 | yes | — | pass | — |
| mkv | hevc 3840x2160 10bit | eac3 6ch | text-in-stream | direct-play | direct-play | — | no | no | — | 1293 | 1379 | 0 | yes | yes | pass | — |
| mp4 | h264 720x480 8bit | aac 2ch | none | direct-play | direct-play | — | no | no | — | 2244 | 2282 | 0 | yes | — | pass | — |

## real: AFTKRT

- Device: AFTKRT (API 30)
- App: 0.0.1, a non-debuggable build
- Jellyfin: 12.2.0
- Audio sink: passthrough=ac3+dts+eac3+truehd;channels=10
- Passed 8 of 9 (0 via fallback)

| Container | Video | Audio | Subtitle | Route | Expected | Transcode reasons | Burn-in | Fallback | Position error (ms) | First frame (ms) | Playing (ms) | Stalls | Audio heard | Subtitle shown | Result | Error |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| mkv | hevc 1920x804 10bit | truehd 8ch | none | transcode | direct-play | — | no | no | — | 15955 | 15963 | 0 | yes | — | FAIL | — |
| mkv | h264 1920x1080 8bit | flac 2ch | none | direct-play | direct-play | — | no | no | — | 1685 | 1940 | 0 | yes | — | pass | — |
| mp4 | h264 560x240 8bit | aac 2ch | text-separate | direct-play | direct-play | — | no | no | — | 1324 | 1333 | 0 | yes | yes | pass | — |
| avi | mpeg4 496x368 8bit | mp3 2ch | text-separate | direct-play | direct-play | — | no | no | — | 1271 | 1298 | 0 | yes | no cue seen | pass | — |
| mkv | mpeg2video 720x480 8bit | ac3 2ch | image | transcode | transcode | — | yes | no | — | 4332 | 4469 | 0 | yes | yes | pass | — |
| mkv | vc1 1920x1080 8bit | dts 6ch | none | transcode | transcode | — | no | no | — | 5134 | 5333 | 0 | yes | — | pass | — |
| mkv | av1 1920x1080 8bit | opus 2ch | none | direct-play | direct-play | — | no | no | — | 1304 | 1327 | 0 | yes | — | pass | — |
| mkv | hevc 3840x2160 10bit | eac3 6ch | text-separate | transcode | transcode | — | no | no | — | 4914 | 5327 | 0 | yes | yes | pass | — |
| mp4 | h264 720x480 8bit | aac 2ch | none | direct-play | direct-play | — | no | no | — | 2406 | 2468 | 0 | yes | — | pass | — |
