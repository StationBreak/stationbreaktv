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
and always fails the row. A difference between Expected and Route with Fallback "no" is a finding. Start (synthetic
section only) is where playback was asked to begin. Position error is how far the first frame was from that point
(empty when it landed within tolerance). Audio heard means the media clock advanced during a 15 s watch; Subtitle
shown means text cues arrived or the server burned the subtitle in; real items start at a random point, so a text
subtitle with no line in the 15 s window is reported ("no cue seen") but not graded. A dash means the column does not apply or nothing
was reported.

Rows for audio the device cannot play (Expected: direct stream) assume an account with default audio-track playback
turned on: the server offers a direct stream for such audio only then. The device runs use such an account.

Timings (first frame, playing) are measured on a non-debuggable build, on the device as it is, and are not graded: a
slow device is slow, and the number is there to be read, not to pass or fail. Only the Result column grades a row.

Measured on 2026-10-22 with the benchmark build at commit 55750d3 (APK sha256 6a32b4550383a751f1da8bfc428e6311d8d0340c40514c8c785bf88cd369ed45), plan seed 1. Known failures: the AVI decode on the AFTJMST12 is #340; the DTS passthrough on the AFTKRT is #344.

## synthetic: AFTJMST12

- Device: AFTJMST12 (API 25)
- App: 0.0.1, a non-debuggable build
- Jellyfin: 12.2.0
- Audio sink: passthrough=ac3+eac3;channels=10
- Passed 16 of 16 (1 via fallback)

| Container | Video | Audio | Subtitle | Route | Expected | Transcode reasons | Burn-in | Fallback | Start (ms) | Position error (ms) | First frame (ms) | Playing (ms) | Stalls | Audio heard | Subtitle shown | Result | Error |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| mp4 | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 1601 | 1614 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | text-in-stream | direct-play | direct-play | — | no | no | 30000 | — | 1255 | 1264 | 0 | yes | yes | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 1042 | 1049 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 1036 | 1042 | 0 | yes | — | pass | — |
| mp4 | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 1050 | 1063 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | none | transcode | direct-play | — | no | seek-miss | 30000 | — | 3879 | 3919 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 1972 | 1978 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | text-in-stream | direct-play | direct-play | — | no | no | 30000 | — | 989 | 993 | 0 | yes | yes | pass | — |
| mp4 | h264 320x240 8bit | aac 1ch | text-separate | direct-play | direct-play | — | no | no | 30000 | — | 1024 | 1031 | 0 | yes | yes | pass | — |
| mp4 | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 1020 | 1024 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | ac3 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 1025 | 1038 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | dts 1ch | none | direct-stream | direct-stream | — | no | no | 30000 | — | 335 | 354 | 0 | yes | — | pass | — |
| mkv | h264 3840x2160 10bit | aac 1ch | none | transcode | transcode | — | no | no | 30000 | — | 1593 | 1613 | 0 | yes | — | pass | — |
| mkv | hevc 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 1095 | 1099 | 0 | yes | — | pass | — |
| mkv | hevc 320x240 8bit | aac 1ch | text-in-stream | direct-play | direct-play | — | no | no | 30000 | — | 1003 | 1010 | 0 | yes | yes | pass | — |
| avi | mpeg4 320x240 8bit | mp3 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 427 | 434 | 0 | yes | — | pass | — |

## synthetic: AFTKRT

- Device: AFTKRT (API 30)
- App: 0.0.1, a non-debuggable build
- Jellyfin: 12.2.0
- Audio sink: passthrough=ac3+dts+eac3+truehd;channels=10
- Passed 15 of 16 (1 via fallback)

| Container | Video | Audio | Subtitle | Route | Expected | Transcode reasons | Burn-in | Fallback | Start (ms) | Position error (ms) | First frame (ms) | Playing (ms) | Stalls | Audio heard | Subtitle shown | Result | Error |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| mp4 | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 1271 | 1284 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | text-in-stream | direct-play | direct-play | — | no | no | 30000 | — | 1065 | 1085 | 0 | yes | yes | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 651 | 656 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 616 | 631 | 0 | yes | — | pass | — |
| mp4 | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 625 | 638 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | none | transcode | direct-play | — | no | seek-miss | 30000 | — | 4285 | 4336 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 628 | 644 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | aac 1ch | text-in-stream | direct-play | direct-play | — | no | no | 30000 | — | 615 | 621 | 0 | yes | yes | pass | — |
| mp4 | h264 320x240 8bit | aac 1ch | text-separate | direct-play | direct-play | — | no | no | 30000 | — | 632 | 642 | 0 | yes | yes | pass | — |
| mp4 | h264 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 647 | 653 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | ac3 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 607 | 747 | 0 | yes | — | pass | — |
| mkv | h264 320x240 8bit | dts 1ch | none | transcode | direct-play | — | no | load-failed | 30000 | — | 6011 | 6020 | 0 | yes | — | FAIL | fallback:load:ERROR_CODE_AUDIO_TRACK_INIT_FAILED |
| mkv | h264 3840x2160 10bit | aac 1ch | none | transcode | transcode | — | no | no | 30000 | — | 1224 | 1239 | 0 | yes | — | pass | — |
| mkv | hevc 320x240 8bit | aac 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 587 | 595 | 0 | yes | — | pass | — |
| mkv | hevc 320x240 8bit | aac 1ch | text-in-stream | direct-play | direct-play | — | no | no | 30000 | — | 576 | 587 | 0 | yes | yes | pass | — |
| avi | mpeg4 320x240 8bit | mp3 1ch | none | direct-play | direct-play | — | no | no | 30000 | — | 279 | 305 | 0 | yes | — | pass | — |

## real: AFTJMST12

- Device: AFTJMST12 (API 25)
- App: 0.0.1, a non-debuggable build
- Jellyfin: 12.2.0
- Audio sink: passthrough=ac3+eac3;channels=10
- Passed 8 of 9 (0 via fallback)

| Container | Video | Audio | Subtitle | Route | Expected | Transcode reasons | Burn-in | Fallback | Position error (ms) | First frame (ms) | Playing (ms) | Stalls | Audio heard | Subtitle shown | Result | Error |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| mkv | hevc 1920x804 10bit | truehd 8ch | none | transcode | — | — | no | no | — | 5570 | 5636 | 0 | yes | — | pass | — |
| mkv | h264 1920x1080 8bit | flac 2ch | none | transcode | — | — | no | no | — | 1530 | 1864 | 0 | yes | — | pass | — |
| mp4 | h264 560x240 8bit | aac 2ch | text-separate | direct-play | direct-play | — | no | no | — | 1473 | 1482 | 0 | yes | yes | pass | — |
| avi | mpeg4 496x368 8bit | mp3 2ch | text-separate | transcode | direct-play | — | no | load-failed | — | 5962 | 5968 | 0 | yes | no cue seen | FAIL | fallback:load:ERROR_CODE_DECODING_FAILED |
| mkv | mpeg2video 720x480 8bit | ac3 2ch | image | transcode | transcode | — | yes | no | — | 4386 | 4407 | 0 | yes | yes | pass | — |
| mkv | vc1 1920x1080 8bit | dts 6ch | none | transcode | transcode | — | no | no | — | 5313 | 5586 | 0 | yes | — | pass | — |
| mkv | av1 1920x1080 8bit | opus 2ch | none | transcode | transcode | — | no | no | — | 1959 | 1973 | 0 | yes | — | pass | — |
| mkv | hevc 3840x2160 10bit | eac3 6ch | text-in-stream | direct-play | direct-play | — | no | no | — | 1276 | 1497 | 0 | yes | yes | pass | — |
| mp4 | h264 720x480 8bit | aac 2ch | none | direct-play | — | — | no | no | — | 2312 | 2319 | 0 | yes | — | pass | — |

## real: AFTKRT

- Device: AFTKRT (API 30)
- App: 0.0.1, a non-debuggable build
- Jellyfin: 12.2.0
- Audio sink: passthrough=ac3+dts+eac3+truehd;channels=10
- Passed 9 of 9 (0 via fallback)

| Container | Video | Audio | Subtitle | Route | Expected | Transcode reasons | Burn-in | Fallback | Position error (ms) | First frame (ms) | Playing (ms) | Stalls | Audio heard | Subtitle shown | Result | Error |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| mkv | hevc 1920x804 10bit | truehd 8ch | none | transcode | — | — | no | no | — | 4739 | 4746 | 0 | yes | — | pass | — |
| mkv | h264 1920x1080 8bit | flac 2ch | none | direct-play | — | — | no | no | — | 553 | 702 | 0 | yes | — | pass | — |
| mp4 | h264 560x240 8bit | aac 2ch | text-separate | direct-play | direct-play | — | no | no | — | 1547 | 1555 | 0 | yes | yes | pass | — |
| avi | mpeg4 496x368 8bit | mp3 2ch | text-separate | direct-play | direct-play | — | no | no | — | 1093 | 1099 | 0 | yes | no cue seen | pass | — |
| mkv | mpeg2video 720x480 8bit | ac3 2ch | image | transcode | transcode | — | yes | no | — | 4214 | 4353 | 0 | yes | yes | pass | — |
| mkv | vc1 1920x1080 8bit | dts 6ch | none | transcode | transcode | — | no | no | — | 4992 | 5092 | 0 | yes | — | pass | — |
| mkv | av1 1920x1080 8bit | opus 2ch | none | direct-play | direct-play | — | no | no | — | 894 | 1124 | 0 | yes | — | pass | — |
| mkv | hevc 3840x2160 10bit | eac3 6ch | text-separate | transcode | transcode | — | no | no | — | 4761 | 5058 | 0 | yes | yes | pass | — |
| mp4 | h264 720x480 8bit | aac 2ch | none | direct-play | — | — | no | no | — | 1526 | 1548 | 0 | yes | — | pass | — |
