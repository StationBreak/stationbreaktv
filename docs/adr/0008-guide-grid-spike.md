# ADR-0008: Guide grid with standard Compose lists (spike SP-2 result)

- **Status:** Accepted
- **Date:** 2026-10-10
- **Decided by:** Phil Critchfield
- **Resolves:** spike SP-2 ([ROADMAP.md](../ROADMAP.md))
- **Informs:** R-PERF-2, F2.1, F2.5
- **Amended:** 2026-10-27, see the addendum (the built guide no longer uses standard Compose lists)

## Context

The guide is the heaviest screen in the app: 50+ channels × 6+ hours of variable-width program cells,
navigated with the D-pad on a budget Fire TV. Risk R-PERF-2 asked whether Compose for TV can do this
smoothly on the reference device (Insignia, Fire OS 6.7.0.8, API 25). The owner decided to test standard
Compose lists first and build a custom `LazyLayout` grid only if they stuttered.

## Spike

A throwaway app (branch `spike/sp-2`) drew a synthetic 50-channel × 6-hour guide:
- foundation `LazyColumn` of `LazyRow`s;
- tv-material 1.1.0 `Surface` cells;
- a fixed channel column, a time header and a "now" line.

**Devices:** the Insignia is the reference device. A Fire TV Stick 4K (2nd gen, API 30) was used as a comparison
only.

**Build and installation:** a release build with R8, using the library baseline profiles that ship with
Compose, speed-profile compiled as background dexopt would do. No app-specific baseline profile was
generated.

**Input:** key presses were injected through the remote's input device, at 150 ms intervals (stepping) and
50 ms intervals (key held down), vertically, horizontally and mixed. Each scenario ran 3 times under clean
and as-lived memory conditions. `adb shell input keyevent` takes about 1.9 s per call on the Insignia, so
it can't be used to drive timing tests there.

**Horizontal sync:** the app owns all scrolling. One shared horizontal offset, animated with a spring, is
applied to every visible row and the time header each frame. User scrolling and the default
bring-into-view behaviour are switched off, so focus moves drive the scroll. There was no drift between rows.

### Results on the Insignia (median of 3 runs)

| Scenario | Condition | Frames | Janky % | Frame time p50 / p90 / p99 (ms) |
| --- | --- | --- | --- | --- |
| Down, stepping (150 ms) | clean | 879 | 87.7 | 18 / 25 / 40 |
| Down, stepping (150 ms) | as-lived | 881 | 84.8 | 18 / 24 / 34 |
| Down, held (50 ms) | clean | 208 | 98.6 | 46 / 77 / 101 |
| Down, held (50 ms) | as-lived | 208 | 93.5 | 44 / 81 / 101 |
| Left/right sweep, stepping (150 ms) | clean | 826 | 89.8 | 19 / 42 / 85 |
| Left/right sweep, held (50 ms) | clean | 164 | 84.2 | 61 / 150 / 250 |
| Left/right sweep, held (50 ms) | as-lived | 149 | 100.0 | 65 / 150 / 200 |
| Mixed directions (100 ms) | clean | 485 | 92.0 | 26 / 57 / 150 |

- **The owner judged it smooth** on the Insignia for normal-pace vertical, normal-pace horizontal and
  held-down vertical scrolling. The owner didn't watch a held left/right sweep or mixed directions, so the weakest
  case (below) is judged on numbers only.
- **"Janky %" misleads on this device.** When stepping, typical frames take about 18 ms, just over the
  16.7 ms budget for 60 Hz, so the app runs at a steady, even ~30 fps, and Android counts every such frame as
  janky. Held-key scrolling is genuinely slower (p50 44–65 ms), not a counting artefact. The tail (p90/p99)
  and missed-vsync counts describe what a viewer sees much better.
- **Clean and as-lived barely differ** in frame timing: p50 within 4 ms in every scenario (table rows not shown follow the same pattern).
- **Focus:** 0 anomalies (lost focus, or focus off screen) in 51 Insignia runs across all scenarios, including those not shown in the table.
- **Cold start** to the first grid frame: 733 ms clean, 884 ms as-lived.
- **Memory:** app peak 37 MB clean, 54 MB as-lived.
- **Compilation matters a great deal.** On the comparison Firestick, a fresh, uncompiled install had 52% janky
  frames, against 1% once compiled. That's a single comparison; the Insignia was only measured compiled.
- **The weakest case is a fast horizontal sweep,** with p99 up to 250 ms.

## Decision

1. **SP-2 passes.** The guide (F2.1) uses **standard Compose lists** (`LazyColumn` of `LazyRow`s). A custom
   `LazyLayout` grid is not built.
2. The guide uses the spike's scrolling pattern: **app-owned scrolling**, with one shared horizontal offset
   applied to all rows and focus moves driving the scroll.
3. **A baseline profile for the guide is required in release builds** (S2.5.1), because compilation state was
   the largest difference measured (in the Firestick comparison).
4. F2.5's performance thresholds use **frame-time percentiles and missed vsyncs, not janky %**. The values are
   set separately (S2.5.1). **Set 2026-10-10:** no scenario may be more than 20% worse than this spike's p90/p99
   frame times or missed vsyncs. It's a starting point.

## Consequences

- No custom layout code to own, and D-pad focus handling comes from the standard lists.
- Fast horizontal sweeps are the case to watch in F2.5. The owner watches a held left/right sweep on the
  Insignia before F2.5 closes.
- Timing tests on the Insignia must inject input through the input device, not `adb shell input`.
- **Repository hygiene:** `spike/sp-2` is deleted before the repository goes public, along with the other
  spike branches (Q-REPO-1).

## Addendum (2026-10-27): the built guide

Spec 17 built the guide (F2.1) and the now/next banner (F2.2): PRs
[#418](https://github.com/StationBreak/stationbreaktv/pull/418),
[#419](https://github.com/StationBreak/stationbreaktv/pull/419),
[#426](https://github.com/StationBreak/stationbreaktv/pull/426),
[#439](https://github.com/StationBreak/stationbreaktv/pull/439) and
[#443](https://github.com/StationBreak/stationbreaktv/pull/443). The decision above is not rewritten. The built
guide differs from the spike in two ways, and the device checks are recorded here.

### What changed from the spike

1. **Focus is model-driven, and standard Compose lists are no longer used.** The guide model owns focus and moves it
   on each key press. The grid is a plain `Column` of at most 6 rows (`GUIDE_VISIBLE_ROWS`) from the first visible
   row the model chooses. Each row is one custom `Layout` that composes all of its cells, places them by time from
   one origin, and moves them by one shared viewport offset read in the layout phase. A Left/Right page therefore
   recomposes no cell. The cells are not focusable, so there is no `LazyColumn`, no `LazyRow` and no per-cell
   focus handling; the guide root takes the key events (`GuideGrid.kt`, `GuideCells.kt`, `GuideScreen.kt`,
   `GuideDestination.kt`). A press jumps to the next cell, at about 1.7 frames per press. The spike's
   spring-animated scroll took about 8.8 frames per press. Decision 2 (one shared horizontal offset) still holds;
   decision 1 (standard lists) does not.
2. **One video surface.** The corner picture is the same `SurfaceView` as full screen, moved and resized by a custom
   layout modifier. There is no second player and no surface recreation, so no black flash (seen on the Fire TV
   Stick; the Insignia's screen capture cannot show video). Live TV owns the picture rectangle
   (`ScreenLayout.pictureRect`). The guide lays out around the hole it is given (`GuideGeometry(screen, hole)`) and
   requires the hole in the top-right corner of the safe area. The guide is laid out left-to-right only for now.

Also built:
- R8 is on for release and benchmark builds, with `-dontobfuscate`.
- A hand-written app baseline profile (`app/src/main/baseline-prof.txt`) stands in until a generated one lands
  ([#416](https://github.com/StationBreak/stationbreaktv/issues/416)).
- During held-key moves the details panel defers the description by about 150 ms.

### Device results

Benchmark builds compiled with the app profile, median of 3 runs. Scenarios: s1 Down stepping (150 ms), s2 Down held
(50 ms), s3 Left/right sweep stepping (150 ms), s4 Left/right sweep held (50 ms), s5 Mixed (100 ms). SP-2 values are
clean-condition p90 / p99 from the table above. The other columns are frame time p50 / p90 / p99 in ms. Device checks 1 (C1), 1b (C1b)
and 2 (C2) ran on both TVs.

**The comparison is not like-for-like.** SP-2 had no details panel and no video. Its percentiles are also diluted by
animation frames. So the built guide is compared per key press (frames per press, missed vsyncs per press), beside
the spike's percentiles.

The older Fire TV (Insignia, API 25):

| Scenario | SP-2 p90 / p99 | C1 first build (video behind) | C1b (2026-10-27) | C2, corner picture playing |
| --- | --- | --- | --- | --- |
| s1 | 25 / 40 | 53.2 / 74.5 / 186.1 | 39.0 / 62.8 / 155.4 | 46.5 / 81.4 / 291.1 |
| s2 | 77 / 101 | 70.6 / 114.5 / 244.8 | 62.6 / 103.2 / 187.2 | 60.0 / 113.6 / 229.6 |
| s3 | 42 / 85 | 38.5 / 97.7 / 352.8 | 42.5 / 106.0 / 182.5 | 38.7 / 105.3 / 196.8 |
| s4 | 150 / 250 | 43.3 / 100.8 / 438.6 | 47.0 / 107.0 / 263.1 (n=2) | 59.6 / 120.7 / 256.3 |
| s5 | 57 / 150 | 36.2 / 72.1 / 420.7 | 33.6 / 71.6 / 142.4 | 37.4 / 84.6 / 256.0 |

C2 per key press on the Insignia:

| Scenario | Frames per press | Missed vsyncs per press |
| --- | --- | --- |
| s1 | 1.73 | 4.75 |
| s2 | 1.18 | 4.62 |
| s3 | 2.07 | 6.38 |
| s4 | 0.87 | 3.41 |
| s5 | 2.10 | 5.22 |

First guide open on the Insignia:

| Build | After a cold launch | Later opens |
| --- | --- | --- |
| C1 | 1973–2478 ms | 417–1219 ms |
| C1b | 536–850 ms | 327–412 ms |
| C2 | 572–758 ms | 385–640 ms (open test median 406 ms) |

The owner, watching the Insignia on C1b by eye: "Guide scrolling looks good".

The Fire TV Stick (API 30), C2 with the corner picture playing (frame time p50 / p90 / p99, ms):

| Scenario | p50 / p90 / p99 |
| --- | --- |
| s1 | 20.2 / 32.7 / 118.7 |
| s2 | 21.2 / 34.2 / 104.0 |
| s3 | 22.0 / 45.1 / 78.1 |
| s4 | 23.3 / 50.3 / 99.4 |
| s5 | 15.5 / 35.1 / 110.5 |

The first open after a cold launch took 324–734 ms, so the under-1-second gate passes every time (C1 took
1307–1365 ms). Later opens took 165–287 ms. This is the same as or better than C1b, which had no picture.

### Gate status: open

Decision 4 says no scenario may be more than 20% worse than SP-2's p90/p99. By raw percentiles the Insignia is over
that on most scenarios, but the comparison is not like-for-like (above). **The gate for the older hardware is the
owner's call and is still open.** No pass or fail is recorded here. The project's direction for slow hardware is
"measure, don't gate", with a usable floor.
