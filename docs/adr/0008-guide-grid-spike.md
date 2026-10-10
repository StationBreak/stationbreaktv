# ADR-0008: Guide grid with standard Compose lists (spike SP-2 result)

- **Status:** Accepted
- **Date:** 2026-10-10
- **Decided by:** Phil Critchfield
- **Resolves:** spike SP-2 ([ROADMAP.md](../ROADMAP.md))
- **Informs:** R-PERF-2, F2.1, F2.5

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
  held-down vertical scrolling. He didn't watch a held left/right sweep or mixed directions, so the weakest
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
