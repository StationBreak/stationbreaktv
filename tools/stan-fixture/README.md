<!-- SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore -->
# The Stan fixture

`core/ui`'s Stan engine (`tv.stationbreak.core.ui.stan`) is a Kotlin port of the brand's "Stan In Motion" motion study
(2026-11-17). The study is the source of truth. `StanFixtureTest` holds the port to the study's own output, and this
folder is how that output is made.

- `stan-motion-core.js` is the pure part of the study's script: Stan as a list of plain shapes, and the five scenes
  (`SCENE[k](t)`). No DOM and no drawing API. It is copied unchanged from the study (plus the licence header).
- `fixtures.js` writes `SCENE[k](t)` for every scene at a fixed list of times, as JSON. It is deterministic. The times
  include the branches of each scene (the standby static, the no-signal flicker window, the opening's fade-out).

## Regenerate

With Node (any recent version; no packages needed), from this folder:

```
node fixtures.js > ../../core/ui/src/test/resources/stan/stan-scenes-fixture.json
```

If you change the list of times in `fixtures.js`, update `TIMES` in `StanFixtureTest` to match. If the motion study
changes, replace `stan-motion-core.js` with its new pure part, regenerate, and update the Kotlin port until the test
passes again. The test compares exactly: same shapes in the same order, same path strings, same rounded numbers.
