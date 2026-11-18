# Stan Hosts Setup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stan, animated, hosts every first-time setup screen from the empty corner, with his own short lines and
reactions to the big moments, on the warm brand colours.

**Architecture:**
- **core:ui:** gets a warm Material colour scheme, an orange no-scale focus for setup, and a `StanHost`
  composable. `StanHost` draws Stan in one of six moods, with a speech bubble, from new host frames built from the
  engine's existing shape code. The five motion-study scenes are left untouched.
- **feature:onboarding:** gets a `SetupScaffold` (form on the left, Stan bottom-right, a doze timer) and a pure
  `phase → StanCue` mapping per screen.
- **First sync:** gets a Done phase that holds 1.5 s on Stan's cheer before Live.

**Tech Stack:** Kotlin 2.4, Compose for TV (`androidx.tv:tv-material`), JUnit, Compose UI tests, coroutines test.

**Spec:** `docs/superpowers/specs/2026-11-18-stan-hosts-setup-design.md`. Read it with this plan.

## Global Constraints

- minSdk 25. The floor device is the 1.5 GB Insignia (API 25).
- Use the core:ui engine (`tv.stationbreak.core.ui.stan`). No Lottie, no video, no second renderer. Do not
  change the five `StanScene`s or `StanFixtureTest`.
- **Instructions and errors stay in the screen's own text.** Stan is extra.
- **Stan is hidden from screen readers** (`clearAndSetSemantics {}`) and is never focusable.
- **Stan's lines** follow DESIGN.md's "Stan's line" rule: Ranchers (`BroadcastType.Voice`), capitals, a few words
  at most. They are string resources.
- **Reduced motion** (`LocalMotionReduced`): a still frame per mood (t = 3 s), and no talking flap.
- **Strings:** the final lines come from Phil's mock-up review (Task 1). Until then use the spec's draft table.
- **Light mode is out of scope.** `StationBreakTheme` stays dark-only.
- **Launch screen:** Routing and Trusting show no Stan.
- **Before every push, run for each touched module:** `spotlessCheck`, `lintDebug`, `detekt`, `detektDebug`,
  `detektDebugUnitTest`, `detektDebugAndroidTest`, `testDebugUnitTest` and `compileDebugAndroidTestKotlin`. For
  app, use the amazonDebug variants. Push only when gradle exits 0.
- Sign off every commit (`git commit -s`), ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **New public core:ui API** (`StanHost`, `StanMood`, the `StanStageTransform` parameters) gets a
  crew:architecture review.
- **No device runs from a working session.** Device checks go through the test session, with Phil's approval.

## Review Focus

1. **The keyboard or a short window squeezes the screen** (Android TV's docked keyboard resizes the window). Stan
   must not overlap the form. Hide him when the scaffold is shorter than 400 dp. Pinned in Task 4.
2. **A large font size** (font scale 1.3) makes the form taller and wider. The form and Stan's area must never
   intersect. Pinned in Task 4.
3. **A dialog is open** (cleartext confirm, certificate trust). Its keys go to the dialog's window, not the
   scaffold, so Stan must not doze while the person is pressing keys in it. Pinned in Task 4.
4. **Reduced motion:** still moods, no talking flap, and the first-sync cheer still holds 1.5 s. Pinned in Tasks 3
   and 5.
5. **Back during the cheer hold** goes on to Live. It does not leave the app or go back into setup. Pinned in
   Task 5.

---

### Task 1: Mock-ups for Phil (gate)

**Files:**
- Create: the scratchpad page `stan-setup-mock.html`, published as an Artifact.

- [ ] **Step 1: Draw the screens.** Draw the server, sign-in and first-sync screens at 1920×1080 in the warm
  ink, with the form on the left. Put Stan bottom-right inside the safe area, drawn from the motion study's
  `stanShapes` (https://claude.ai/artifact/3uWY2jLWpM26DdotaGEx5Q), with his bubble in Ranchers capitals. Show
  four states side by side: Idle "WHAT'S ON TONIGHT?", Pleased "THERE IT IS!", Shrug "CAN'T PICK IT UP", and
  first sync Cheer "WE'RE ON THE AIR!". Include the orange focus fill on a button. Add a list of every draft line
  that Phil can edit (the page keeps his edits).
- [ ] **Step 2: Publish it and ask Phil in plain words.** Is Stan the right size and in the right spot? Do the
  lines sound like Stan? **Stop until he answers.** Copy his final lines into Task 4 and Task 5's strings.

### Task 2: Warm colour scheme and setup focus (PR 1)

**Files:**
- Modify: `core/ui/src/main/kotlin/tv/stationbreak/core/ui/StationBreakTheme.kt`
- Modify: `core/ui/src/main/kotlin/tv/stationbreak/core/ui/FocusStyle.kt`
- Test: `core/ui/src/test/kotlin/tv/stationbreak/core/ui/ContrastTest.kt`

**Interfaces:**
- Produces: `StationBreakDarkColors` (same name), and `FocusStyle.buttonColors(emphasis: Boolean): ButtonColors`.
  `FocusStyle.buttonScale()` and `surfaceScale()` now return 1.0.

- [ ] **Step 1: Write the failing contrast tests in `ContrastTest`.**
  - `inkOnFocusFillIsReadable`: ink #1C1B19 on orange #E8642C is at least 4.5.
  - `creamOnGroundIsReadable`: `onSurface` on `surface` is at least 4.5.
  - `greyOnGroundIsReadable`: `onSurfaceVariant` on `surface` is at least 4.5.
  - `focusRingStandsOut`: `border` (orange) against `surface` and against `surfaceVariant` is at least 3.0.
  - `errorIsReadable`: `error` on `surface` is at least 4.5.
- [ ] **Step 2: Run `./gradlew :core:ui:testDebugUnitTest --tests '*ContrastTest*'`.** Expected: FAIL. The cream
  test should pass already; the orange and ink ones should fail.
- [ ] **Step 3: Build `StationBreakDarkColors` from `BrandColors`.**
  - `background` and `surface`: ink.
  - `surfaceVariant`: panel #262320.
  - `onSurface` and `onBackground`: cream.
  - `onSurfaceVariant`: grey #CFCBC2.
  - `primary` and `border`: orange.
  - `onPrimary`: ink.
  - `error`: keep tv-material's if it passes the test, or pick the nearest warm red that passes.

  Then change `FocusStyle`:
  - A focused button fills orange with ink text, and its scale is 1.0. Add `buttonColors(emphasis)`. Emphasis
    unfocused is panel with an orange 1 dp border; plain is panel.
  - A focused surface (text field, collection row) gets a 4 dp orange ring and scale 1.0.
  - Update the KDoc.
- [ ] **Step 4: Switch `ServerButton`'s colours** (`feature/onboarding/.../server/ServerStyle.kt:94`) to
  `FocusStyle.buttonColors(emphasis)`.
- [ ] **Step 5: Run the global verification list** for core:ui, feature:onboarding and feature:settings.
  Settings reads `MaterialTheme.colorScheme`, so it changes too. Fix any onboarding UI test that asserted the
  old scale.
- [ ] **Step 6: Commit.** Message: "feat(ui): warm Material scheme and orange no-scale focus for setup".

### Task 3: Stan's moods and `StanHost` (PR 2)

**Files:**
- Modify: `core/ui/.../stan/StanShapes.kt`. Add `Arms.Cheer`: both arms up. Left arm
  `"M-116 0 Q-156 -36 -150 -98"`, hand at (-150, -102); right arm mirrored. Move both by `armBob`.
- Modify: `core/ui/.../stan/StanScenes.kt`. Add `StanBackground.None`. The painter draws no ground for it.
- Modify: `core/ui/.../stan/StanStageTransform.kt`. Add the parameters
  `stageWidth: Float = STAGE_W, stageHeight: Float = STAGE_H` and use them in `scale`, `offsetX` and `offsetY`.
- Modify: `core/ui/.../stan/StanStage.kt`. Factor the clock out of `rememberStanPlayback` into
  `internal fun rememberStanClock(key: Any, timeSeconds: Double?, maxFps: Int?): LongState`. Both
  `rememberStanPlayback` and the host use it.
- Create: `core/ui/.../stan/StanMoods.kt`, with the internal `hostState` and `hostFrame`.
- Create: `core/ui/.../stan/StanHost.kt`, with the public `StanMood` and `StanHost`.
- Test: `core/ui/src/test/.../stan/StanMoodsTest.kt` and `core/ui/src/androidTest/.../stan/StanHostTest.kt`.

**Interfaces:**
- Produces:
  ```kotlin
  enum class StanMood { Idle, Busy, Pleased, Shrug, Cheer, Dozing }

  @Composable
  fun StanHost(
      mood: StanMood,
      line: String?,
      modifier: Modifier = Modifier,
      palette: StanPalette = StanPalette.Dark,
  )

  internal const val HOST_W = 600.0
  internal const val HOST_H = 640.0
  internal fun hostState(mood: StanMood, t: Double, talking: Boolean): StanState
  internal fun hostFrame(mood: StanMood, t: Double, talking: Boolean): StanFrame
  ```
  `hostFrame` uses `bg = StanBackground.None`. Stan is centred at x = 300, y about 380 in host space, with a shadow
  under his feet, and `blocks`/`title` are null.

- [ ] **Step 1: Write `StanMoodsTest`.**
  - `idleStandsWithArmsAtRest`: `Eyes.Open`, `Arms.Rest`.
  - `busyTunesTheAerial`: `Arms.Tune`.
  - `pleasedHasHappyEyes`: `Eyes.Happy`, `Mouth.Smile`.
  - `shrugShrugs`: `Arms.Shrug`, `Ears.Droop`.
  - `cheerRaisesBothArms`: `Arms.Cheer`, `Eyes.Happy`.
  - `dozingSleeps`: `Eyes.Sleep`.
  - `talkingFlapsTheMouth`: with `talking = true`, the mouths at t = 0.0 and 0.09 differ and are among {Smile, O}.
    With `talking = false` the mouth is the mood's own.
  - `everyMoodStaysInHostSpace`: for each mood and t in 0.0..10.0 step 0.05, `hostFrame` has finite x, y and sc,
    0 < x < HOST_W and 0 < y < HOST_H.
- [ ] **Step 2: Run `./gradlew :core:ui:testDebugUnitTest --tests '*StanMoodsTest*'`.** Expected: FAIL (unresolved).
- [ ] **Step 3: Implement `hostState` and `hostFrame`.**
  - Idle bobs `2 * sin(t * 2)` and blinks (eyes `open` dips to 0.1 for 0.12 s every 3.7 s).
  - Busy copies the standby scene's ear and arm motion.
  - Pleased hops `-10 * max(0, sin(t * 6))` for its first 0.6 s.
  - Shrug copies the no-signal pose, without the "?".
  - Cheer bobs `armBob = 6 * sin(t * 8)`.
  - Dozing copies the off-air face and Zs, with `Arms.Rest` and no bear.
  - Talking flips Smile/O every 0.09 s.
- [ ] **Step 4: Run the test again.** Expected: PASS. `StanFixtureTest` still passes.
- [ ] **Step 5: Write `StanHostTest` (androidTest).**
  - `hostHasNoSemantics`: with line "THERE IT IS!", `onNodeWithText("THERE IT IS!")` does not exist in the
    semantics tree, and no focusable node exists.
  - `nullLineShowsNoBubble`.
  - `newLineReplacesOld`: the line changes from A to B in one frame, and only B is drawn (check the tag
    `StanHostTags.BUBBLE` text via `useUnmergedTree` on a test-only tag, kept off the accessibility tree).
  - `reducedMotionIsStill`: with `LocalMotionReduced provides true`, two frames 500 ms apart are pixel-identical
    (`captureToImage`).
- [ ] **Step 6: Implement `StanHost`.**
  - A `Column`: the bubble over Stan at 220×236 dp.
  - The bubble is a cream rounded rect (12 dp) with a tail pointing down, holding ink Ranchers 22 sp text, at most
    320 dp wide.
  - A new line pops the bubble (scale 0.9 to 1 over 160 ms; none with reduced motion) and sets `talking` for
    1.2 s. The spec's "Talking" mood is this flag, not a `StanMood`.
  - A mood change cross-fades the figure over 200 ms with `Crossfade`, and cuts with reduced motion.
  - Frames come from `rememberStanClock(mood, null, maxFps = 20)` and `hostFrame`, drawn by `StanPainter` with
    `StanStageTransform(w, h, HOST_W, HOST_H)` in a `drawWithCache` Spacer.
  - The root has `clearAndSetSemantics {}` and `focusProperties { canFocus = false }`.
- [ ] **Step 7: Verify, review and commit.** Run the global verification list for core:ui. Ask
  crew:architecture to review the diff, and show any BLOCK to Phil verbatim. Commit: "feat(ui): Stan's host moods
  and StanHost".

### Task 4: `SetupScaffold` and the first screens (PR 3a)

**Files:**
- Create: `feature/onboarding/src/main/kotlin/tv/stationbreak/feature/onboarding/SetupScaffold.kt`
- Create: `feature/onboarding/src/main/kotlin/tv/stationbreak/feature/onboarding/StanCues.kt`
- Modify: the welcome, server, trust, sign-in and launch screens. Each wraps its existing content in
  `SetupScaffold`, with no other layout change.
- Modify: `feature/onboarding/src/main/res/values/strings.xml`. Add `stan_*` lines (Task 1's final text).
- Test: `feature/onboarding/src/test/.../StanCuesTest.kt` and
  `feature/onboarding/src/androidTest/.../SetupScaffoldTest.kt`.

**Interfaces:**
- Consumes: `StanHost`, `StanMood` (Task 3).
- Produces:
  ```kotlin
  internal data class StanCue(val mood: StanMood, @StringRes val line: Int?)
  internal fun welcomeCue(): StanCue
  internal fun serverCue(phase: ServerPhase): StanCue
  internal fun trustCue(): StanCue
  internal fun signInCue(phase: SignInPhase): StanCue
  internal fun launchCue(phase: LaunchPhase): StanCue?   // null: no Stan (Routing, Trusting)
  @Composable internal fun SetupScaffold(cue: StanCue?, modifier: Modifier = Modifier, dozeEnabled: Boolean = true, content: @Composable () -> Unit)
  internal const val DOZE_AFTER_MS = 60_000L
  internal val MIN_HOST_HEIGHT = 400.dp
  ```

- [ ] **Step 1: Write `StanCuesTest`.** For every `ServerPhase`, `SignInPhase` and `LaunchPhase` subtype, assert
  the spec table's mood and line resource. In particular:
  - Failed and InvalidInput map to Shrug.
  - Testing and SigningIn map to Busy.
  - Connected maps to Pleased.
  - NotTrusted, ConfirmCleartext and TrustRequired map to Idle with the trust line.
  - `launchCue(Routing)` and `launchCue(Trusting)` are null.
- [ ] **Step 2: Run it.** Expected: FAIL.
- [ ] **Step 3: Implement the cue functions** with exhaustive `when`s, so a new phase fails to compile.
- [ ] **Step 4: Write `SetupScaffoldTest` (androidTest, `mainClock.autoAdvance = false`).**
  - `dozesAfterAMinuteWithNoKey`: the host mood is Dozing after 60 s. Expose it via a test-only
    `SemanticsPropertyKey` on a non-accessibility test node, or check that the line disappears.
  - `aKeyWakesAndStillReachesTheScreen`: after dozing, press DPAD_DOWN. Stan wakes, and focus moved in the content.
  - `noDozeWhileDisabled`: with `dozeEnabled = false`, no doze after 60 s. Screens pass false while a dialog phase
    is up (ConfirmCleartext, TrustRequired) — Review Focus 3.
  - `stanHiddenWhenShort`: in a 960×380 dp box, the host is not composed — Review Focus 1.
  - `formNeverUnderStan`: at `fontScale = 1.3`, in 960×540 dp, the content's bounds and the host's bounds do not
    intersect — Review Focus 2.
  - `nullCueShowsNoStan`.
- [ ] **Step 5: Implement `SetupScaffold`.**
  - A `BoxWithConstraints` with the screen lift background (`BrandColors.ScreenLiftTop` to `ScreenLiftBottom`,
    radial).
  - A `Row`: the content in `weight(1f)`, then a fixed 340 dp column holding `StanHost` at the bottom.
  - It sits inside the safe area. The host is omitted below `MIN_HOST_HEIGHT`.
  - `onPreviewKeyEvent { resetDoze(); false }`.
- [ ] **Step 6: Wire the screens.** Wrap welcome, server, trust, sign-in and launch in the scaffold with their
  cue. Run the existing onboarding androidTest compile and the unit tests.
- [ ] **Step 7: Verify, review and commit.** Run the global verification list for feature:onboarding and app.
  Commit: "feat(onboarding): Stan hosts welcome, server, trust, sign-in and launch".

### Task 5: Libraries, signed in, and the first-sync cheer (PR 3b)

**Files:**
- Modify: `feature/onboarding/.../firstsync/FirstSyncPhase.kt`. Add `data object Done : FirstSyncPhase`.
- Modify: `feature/onboarding/.../firstsync/FirstSyncViewModel.kt`. `finish()` sets Done, waits
  `CHEER_HOLD_MS = 1_500L` or until `skipCheer()`, then sends `FirstSyncEvent.Finished`.
- Modify: `FirstSyncScreen.kt`, `LibrariesScreen.kt` and `SignedInScreen.kt` to wrap in `SetupScaffold`.
  `FirstSyncScreen` calls `skipCheer()` on any key, including Back, while Done is up.
- Modify: `StanCues.kt`. Add
  `librariesCue(phase: LibrariesPhase, justArrived: Boolean)`, `signedInCue()` and
  `firstSyncCue(phase: FirstSyncPhase)`.
- Test: `FirstSyncViewModelTest`, `StanCuesTest`, and the existing `LaunchNavigationTest` / first-sync nav tests.

**Interfaces:**
- Consumes: `SetupScaffold`, `StanCue` (Task 4).
- Produces: `FirstSyncPhase.Done`, `FirstSyncViewModel.skipCheer()`, `CHEER_HOLD_MS`,
  `LIBRARIES_CHEER_MS = 2_000L`.

- [ ] **Step 1: Write the view-model tests** (`runTest`, virtual time).
  - `finishingShowsDoneThenFinishesAfterTheHold`: phase becomes Done. No Finished at 1_499 ms; Finished at
    1_500 ms.
  - `skipCheerFinishesAtOnce`.
  - `finishIsSentOnce`: `skipCheer()` after the hold sends nothing more.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement Done, the hold and `skipCheer()`.** The hold is the same with reduced motion (Review
  Focus 4). Run: PASS.
- [ ] **Step 4: Write the cue tests.**
  - `librariesCue(Choosing, justArrived = true)` is Cheer with the "you're in" line; with `false` it is Idle.
  - Loading and Saving are Busy. LoadFailed, NoLibraries and SaveFailed are Shrug.
  - `firstSyncCue(Syncing)` is Busy, WaitingForNetwork is Shrug, Failed is Shrug, Done is Cheer with the "on the
    air" line.
  - `signedInCue()` is Idle.

  Implement them. `justArrived` is true for the first `LIBRARIES_CHEER_MS` of the picker's entry
  (`rememberSaveable` start time).
- [ ] **Step 5: Wire the screens.** Add the androidTest
  `backDuringTheCheerGoesLive`: in the Done state, press Back, then expect LiveRoute and no `finish()` of the
  activity (Review Focus 5). Update the nav tests that expected Live at once, so they advance 1.5 s or press a
  key.
- [ ] **Step 6: Verify and commit.** Run the global verification list for feature:onboarding and app. Commit:
  "feat(onboarding): Stan hosts libraries and first sync, and cheers us on the air".

### Task 6: Device check and merge

- [ ] **Step 1: Get frames from the TVs, with Phil's approval.** Ask Phil to OK a test-session brief, then brief
  the test session for frames of each setup screen on the Insignia and the Firestick. Also ask for a jank count
  (`dumpsys gfxinfo`) while Stan idles on the server screen with the keyboard closed. Pass if janky frames are
  under 10% on the Insignia.
- [ ] **Step 2: Merge.** Each PR opens with Auto-fix on. Merge in order (Task 2, 3, 4, 5) after green CI and
  Phil's go-ahead.
- [ ] **Step 3: Update DESIGN.md.** In "Setup (onboarding)", replace the PROVISIONAL line with "Stan hosts each
  step from the bottom-right corner", and drop the Migration "Setup" bullet. Put this in PR 3b.
