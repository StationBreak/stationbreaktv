# Live banner restyle, and audio / subtitle / version choice (#497, #630)

**Area:** `feature:live`, `core:ui`, `core:playback`, `core:domain`, `core:model`. Pre-dogfood (EPICS E2).
**Target mock:** `docs/design/live-banner-target.png` (source `live-banner-target.html`; 1 dp = 2 px). Frames: today, tune banner, Select, audio picker, subtitles picker, channel flip.

This answers the ten questions in "Design brief v2" on #497. Each answer is a decision made so work can start; say so in the PR if one proves wrong. Approved to try on the TV, not final.

## Part A — the banner joins the guide's design and shrinks

Today `CableBanner` is a full-width Ink strip about 150 dp tall (five text lines in Archivo Black and Space Mono, a 4 dp accent rule, a 6 dp progress bar, a number block) plus a 72 sp corner number. It covers roughly a quarter of the picture and looks like a different app from the guide.

### Tokens

`GuideColors`, `guideText` / `guideFigures`, and `ChannelMark` + `MarkCache` + the mark assets are `internal` to `feature:guide`, so `feature:live` cannot use them. **Lift them into `core:ui` as public API** (colours, type helpers, mark composable, cache, loader, initials, and `assets/channelmarks/`), with `feature:guide` consuming them from there. No values change. This is a pure move and is PR 1.

The banner always uses the **Dark** token set, whatever the guide theme: it sits over video, where light chrome glares.

### Tune banner (shows on a tune, 4 s linger as today, nothing focusable)

| | Today | Target |
|---|---|---|
| Height | ~150 dp | **58 dp** (2 dp progress + 54 dp row + borders) |
| Corner number | 72 sp accent, top right | **removed** |
| Surface | Ink, opaque, square | `page` @ 92 %, 1 dp `line` border, 2 dp radius |
| Accent rule 4 dp + progress bar 6 dp | two elements | **one** 2 dp progress line along the top edge (`progressTrack` / `accent`) |
| Channel | number block 150–280 dp, 36 sp number, 14 sp caps name | **mark** 50×28 dp + number 13 sp Medium tabular `textSecondary`; no name. A channel with no mark (initials only) adds its **name** after the number: 13 sp Medium `textSecondary`, one line, ellipsis, at most 140 dp |
| Title | Display 26 sp | Manrope 18 sp Bold `textPrimary`, one line, ellipsis (the bar stays 58 dp and the meta line 11 sp) |
| Line 2 | episode line, slot, joined, each on its own line | one 11 sp `textSecondary` line: **NOW** (8.5 sp Bold accent; or TUNING IN / UP NEXT / FILLING IN) · episode line, or the production year for a film · slot · "N min in" (tune banner only; "Just started" under a minute; not on UP NEXT) · "N min left" |
| Right column | — | one right-aligned 11 sp `textSecondary` line, **"Next: … at …" only**. No stream line: what plays is on the Select banner's chips (Part B1; owner decision after TV testing) |
| Signal bars | present | removed |
| "joining N min in" | own line | the short form "N min in", before "N min left", on the tune banner only (the Select banner's second row is its buttons) |

Padding 16 dp sides; same safe-area insets and `LocalScreenFit` as today. Keep every existing test tag on the element that now carries that text (`live:banner:joined` on "N min in", `live:banner:subtitle` on the year of a film, `live:banner:channel` on the name of a channel with no mark); add `live:banner:mark`. There is no `live:banner:stream` tag: the stream line is gone.

The year is `ProgramInfo.year`, read with the item's other names from the library's stored production year: the same Jellyfin field the info overlay shows, with no fetch at tune time.

### Select banner (actions)

The tune banner plus a 44 dp row under a 1 dp `line`: **102 dp** total. Buttons are 28 dp tall, 2 dp radius, 12 sp Medium, `card` fill; focused = `accent` fill with `focusText`, **no scale, no ring** (guide focus grammar; drop `FocusStyle` here). Order: `Live` (only while behind) · `Info` · `Last` (if any) · 1 dp divider · `Audio` · `Subtitles` · `Version`, then, right-aligned at the end of the row, "Transcoding" (11 sp `textSecondary`, not focusable) only while the route is a transcode; nothing for direct play or direct stream. Info takes focus on open, as today.

### Other live chrome

- **Channel flip** (`ChannelBanner`): same 58 dp bar: mark, number 20 sp SemiBold `textPrimary`, channel name 15 sp SemiBold.
- **Transport strip**: same position; `page` @ 92 %, 1 dp `line`, 2 dp radius, 12 sp Medium Manrope; spinner unchanged.
- **Info overlay** (`ProgramInfoOverlay`, shared with the guide): move to the same tokens: `page` @ 96 %, channel line 11 sp `textSecondary`, title 24 sp SemiBold, body 13 sp / 18 sp line height, details 11 sp.
- **Slates and snow stay as they are** (`BroadcastStyle`); they belong to the brand work in `docs/design/brand/`.

## Part B — stream details, and choosing audio, subtitles and version

### B1. What the banner shows (owner direction, 2026-10-30)

There is **no separate stream line** on any banner (owner decision after TV testing; it was `1080p HEVC · English 5.1 · Subtitles off` under the "Next" line). Stream details live on the **Select banner's chips**: the three facts are the three focusable chips, each `Label  Value ⌃`. The tune banner's right column is the "Next" line alone and shows no stream facts. The fact-to-text rules (the picture, the audio in use, the subtitles in use; a transcode starts with `Transcoding · `) still decide what the chips say.

### B2. Answers to brief v2

1. **Remote keys.** `KEYCODE_CAPTIONS` opens the Subtitles picker and `KEYCODE_MEDIA_AUDIO_TRACK` the Audio picker, straight from the picture, on remotes that have them. No long-press gestures.
2. **Accessibility.** If Android's system captioning is on (`CaptioningManager.isEnabled`) and the viewer has made no subtitle choice, treat it as "Subtitles: on, system locale". Each chip and each picker row is one merged semantics node; a row that interrupts the picture says so in its content description.
3. **Where.** The Select banner's chips. The tune banner shows no stream facts and nothing focusable.
4. **Focus and Back.** Right from Info/Last reaches the chips. OK on a chip opens its picker above the chip with focus on the playing row. Up/Down move in the picker (they do not flip channels while it is open); OK chooses and closes; Back closes the picker to its chip; Back again closes the banner. The 4 s linger is suspended while a picker is open and re-arms when it closes.
5. **Scope of a choice.**
   - *Audio and subtitles:* the exact track applies to the programme on air. What carries on is the **language** (and subtitles on/off), stored on the device per signed-in user and kept across restarts. **Never written back to Jellyfin**, since that would change the viewer's other apps.
   - *Version:* remembered per item on the device (item id → media source id, most recent 200). It plays again whenever the item does, **only while the item still offers that source**; otherwise the server's default version plays, silently. A choice made on the programme on air beats it. Choosing a version re-tunes at the current position (`SWITCHING VERSION`) with the version named and **no track choice**: the new version's audio and subtitles come from the carried language preferences, since the old version's track indices mean nothing there. Choosing the version that plays does nothing. The Version chip is a button, with a picker over it, only when the item has more than one version. Failing to play the chosen version falls back to the previous one with "Couldn't switch", and remembers nothing.
6. **Next programme.** The carried language applies when the next item has a track in it (the default-flagged one, else the first); commentary, forced and audio-description tracks never carry. **Subtitles off does not remove a forced track** (owner decision, 2026-11-09): when the source's default subtitle is forced, the server default stands instead of Off. No match → the server default, silently; the chips show what is actually playing. A carried subtitle preference picks **text** tracks; it uses a picture subtitle only when that is also the server's default, so a preference never quietly turns direct play into a transcode. **SDH carries** (owner decision, 2026-11-09): the subtitle preference is a language plus whether the chosen track was SDH (hearing-impaired). With it set, the next programme prefers an SDH track in that language and falls back to a plain track in that language; without it, a plain track is preferred and an SDH track in that language is the fallback (owner decision, 2026-11-09). The server's own hearing-impaired flag marks an SDH track, and a title that says SDH or hearing impaired does too.
7. **Interrupting changes.** Rows that need a re-tune carry a trailing "brief pause" label (11 sp `textSecondary`); when every row of the picker pauses, the label is said once as a heading above the rows instead of on each. No confirmation dialog. Choosing one re-tunes at the current position under the usual snow with the banner label `SWITCHING AUDIO` / `SWITCHING SUBTITLES` / `SWITCHING VERSION`. When the picture is back, the Select banner reopens with the focus on the chip that was used; on success that chip shows the new value. If the re-tune fails, fall back to the previous choice and reopen the same way, but that chip's value reads "Couldn't switch" for one linger, in the chip's normal text colour (no orange or red: the chip is focused), then the chips show what is actually playing. Its content description reads "Audio, couldn't switch, still English 5.1". An instant switch reopens nothing. The message is on the chip only: no banner has a stream line. Undo is choosing the previous row.
8. **Edge states.** One audio track or one version → that chip is shown but not focusable. No subtitle tracks → `Subtitles  None`, not focusable. Row labels: language (or "Unknown"), then flags from the server title (`Commentary`, `SDH`, `Forced`), then layout and codec for audio, `Text` / `Picture` for subtitles, resolution · codec · edition name for versions. `Off` is always the first subtitle row, and the last row is **Server default**: the way back from a chosen subtitle (or Off) to the server deciding again. It re-tunes, and forgets the stored subtitle choice.
9. **Kids channels.** Same choices.
10. **Names.** Audio, Subtitles, Version. "Off", not "None", for the chosen-off state.

### B3. Which choices interrupt

| Choice | Direct play | Transcode / direct stream |
|---|---|---|
| Audio track | instant (`TrackSelectionOverride` mid-play) | re-tune |
| Text subtitle in the container | instant | re-tune until proven instant on a TV (spike in PR 4) |
| Text subtitle in a separate file | re-tune (the player cannot be told to show a side-loaded file by a position in the file; TV, 2026-11-10: a row labelled instant re-tuned) | re-tune |
| Picture subtitle | re-tune (burn-in) | re-tune |
| Version | re-tune | re-tune |

One function decides this from the resolved playback and the target (`switchCost(...) : Instant | Retune`) so the picker label and the behaviour cannot disagree.

### B4. Plumbing (facts from the code)

- `TuneOutcome.Playing` carries only route and report facts; the sources, streams and chosen tracks stop at `TunePortAdapter.toOutcome()`. Add a `StreamFacts` value (route, chosen source id, chosen audio / subtitle indices, and for every source its audio, subtitle and video summaries) and carry it to `Playing` and the `Banner`.
- `MediaStreamInfo` has no title. Map the server's `Title` / `DisplayTitle` so Commentary and SDH can be labelled.
- `Banner` needs the channel's `recipeKey` (from `ChannelAiring`) for the mark.
- `TunePortAdapter` always passes `override = null`. Pass `TrackOverride` **and** `prefer = <chosen source id>` (ADR-0016, update 2026-10-30), and honour the rule that an override discards a prepared default-track handle.
- Re-tune through the existing `Sitting.retuneAt(...)` path.
- `Media3VideoPlayer` clears overrides on every play and has no mid-play switch; add one for direct play, verified the way `lastPickVerified` is.
- New `TrackChoiceStore` in `core:domain` (language preferences per user; version per item).
- Record the decisions as an update to ADR-0016 decision 6.

## PR split

1. **Lift guide tokens, type and marks into `core:ui`** (pure move; guide screenshots unchanged).
2. **Banner restyle and shrink** (Part A), with `recipeKey` on `Banner`, plus a `LiveScreenshotTest` covering tune, Select, channel flip and transport, built the way `GuideScreenshotTest` is.
3. **Plumbing** (B4); the passive stream line it first showed was dropped (B1). Unit tests for labels, `switchCost`, carry-over rules.
4. **Chips, pickers, keys, instant switch, re-tune and fallback** (#497), including the on-TV spike for text subtitles while transcoding.
5. **Version picker** (#630) on the same surface: the Version chip opens a picker of every version the server offered (resolution · codec · edition name), and the remembered version is applied on tune.

## Acceptance

- [ ] Tune banner ≤ 58 dp and Select banner ≤ 102 dp at 960×540 dp; no corner number; no text under 10 sp; all text ≥ 4.5:1 on the banner surface over a white frame.
- [ ] Banner, flip bar, transport strip and info overlay use Manrope and the lifted tokens; no `BroadcastType` / `BroadcastColors` left in them.
- [ ] Mark shown for starter channels; initials and the channel's name for custom ones.
- [ ] The Select banner's chips match what is playing after every tune, switch and fallback.
- [ ] Each cell of the B3 table behaves as stated on the Insignia; instant switches do not drop the picture.
- [ ] Choices carry to the next programme and survive a restart per B2.5–6; nothing is written to Jellyfin.
- [ ] Back, focus and the linger timer behave per B2.4; Up/Down never flip channels while a picker is open.
- [ ] Screenshot frames (before/after) in each UI PR, including the guide's info panel (`guide-info`, `guide-info-light`) and the live tune banner of a channel with no mark (`live-tune-nomark`).

## Open for the owner

- Carry-over across restarts (chosen here) versus for the sitting only.
- Whether following the system caption setting (B2.2) is wanted, given ADR-0016 says defaults are the server's.

Decided by the owner since: SDH carries (B2.6).
