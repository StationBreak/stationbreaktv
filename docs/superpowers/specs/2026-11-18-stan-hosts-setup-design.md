# Stan hosts setup

Date: 2026-11-18. Step 4 of the look-and-feel plan (DESIGN.md, brand-led).

## Intent

Stan is the face of StationBreak. The first-time setup screens (Welcome, server address, certificate trust, sign
in, Quick Connect, libraries, signed in, first sync) are still plain forms in the stock tv-material purple-grey,
with large empty areas beside and below the form. Stan should live in that empty space for the whole of setup,
as company: a host in the corner with his own lines ("I wonder what's on tonight", "Put your feet up") who
reacts to the big moments. Setup should feel like Stan letting you in, not like filling in a form.

## Goals

- Stan, animated, in the empty corner of every setup screen, with a speech bubble.
- One line of his own per screen, and a short line for each big moment.
- He reacts to about six moments, mostly with poses he already has:
  - **Perks up** when the server is found.
  - **Shrugs** at a wrong address, an unreachable server, a wrong password, or no libraries.
  - **Tunes his aerial** while something is being checked (testing, signing in, loading, saving).
  - **Cheers** when signed in (the library picker opens with it), and when the first sync is done.
  - **Works the aerial** during the first sync. This is the Busy pose; the screen's own progress line carries
    the count, not Stan.
  - **Dozes off** if the screen is left alone, and wakes when any key is pressed.
- The setup screens get the warm brand colours (ink, cream, orange) and DESIGN.md's orange focus ring with no
  scale, replacing the stock purple, the lavender ring and the scaled focus.

## Non-goals

- Stan following focus, looking or pointing at the focused field or button, or reacting to typing (option 3,
  possibly later).
- Stan on screens outside setup (Settings, Channels, the guide). The guide already has the corner bug, and live
  TV has his station screens.
- Rewriting the setup instructions. They stay as they are.
- A Light (paper) scheme for setup and Settings. `StationBreakTheme` is dark-only, and the only Dark/Light
  choice lives in feature:guide. Making the whole app follow it means moving that store into core and is a
  separate change. (Builder check; my call.)
- Stan on the launch screen's Routing phase, which must stay plain so returning viewers never see setup flash.

## Anti-goals (a failure even if it works)

- Anything important only Stan says. Every instruction, error and status stays in the screen's own text, so
  someone who ignores the bubble, or uses a screen reader, loses nothing.
- Stan covering, crowding or pushing the form, or taking focus. He is never focusable.
- A screen reader reading Stan's jokes, or reading a status twice.
- Setup feeling slow or janky on the 1.5 GB Insignia (API 25).
- Lines that talk down, or that are only funny once and grate on a retry.

## Constraints

- Compose for TV, minSdk 25. Stan is drawn by the core:ui engine (`tv.stationbreak.core.ui.stan`). Use it; no
  second renderer, no Lottie or video.
- The engine's five scenes are a fixture-tested port of the brand's motion study. Leave them as they are. The
  host moods are new frames built from the same building blocks (`StanState` arms, eyes, mouth, ears).
- Reduced motion ("Remove animations"): a still frame per mood, as the scenes do. No talking-mouth flap.
- On Fire TV the system keyboard covers most of the screen while typing. Stan is hidden then, and that is fine.
- Strings go in resources (translatable). Stan's lines are not stored data.
- A new public API in core:ui gets a crew:architecture review.
- Device checks only through the test session, with Phil's approval.

## Approaches

1. **Friendly host:** idle in the corner with a line per screen, and no reactions. The cheapest, but he
   feels like a sticker.
2. **Reacts to the big moments (chosen):** the moods above, driven by each screen's existing phase
   (`ServerPhase`, `SignInPhase`, `LibrariesPhase`, `FirstSyncPhase`, `LaunchPhase`). Most poses already
   exist. This is where the charm comes from, at moderate cost.
3. **Follows everything:** on top of 2, he looks or points at the focused control and reacts to typing. This
   needs lots of new drawing and per-screen geometry, and the keyboard hides him while typing anyway.

Phil picked 2 on 2026-11-18. The "looks at what you're on" part of 3 may come later.

## Decided

- Scope: setup screens only, Stan in the empty space (Phil's photos circled the left margin and the
  bottom-right corner of the server and sign-in screens).
- Stan has his own lines, as well as reactions.
- Responsive to the big moments (option 2).
- Show before building: mock-ups of three screens (server, sign-in, first sync) with Stan in the corner, a few
  moods and the draft lines, for Phil to react to. He is not a designer, so the mock-ups are described by what
  he would see and feel.

## The design

### Moods

A small set in core:ui, separate from `StanScene`: `Idle` (standing, blinking, gentle bob, an occasional
ear twitch), `Talking` (Idle with the mouth flapping between Smile and O for the length of a new line), `Busy`
(Tune arms, like Standby), `Pleased` (happy eyes, smile, a little hop), `Shrug` (the NoSignal shrug), `Cheer`
(new: both arms up, happy eyes), `Dozing` (sleep eyes, Zs, no bear). Each is a looping frame function of time,
built from the existing shape code. Only `Cheer`, and possibly a no-bear doze, need new drawing.

### Host composable

`StanHost` (core:ui), given a mood and a line:
- **Drawing:** Stan at a fixed size, with his speech bubble above him: cream bubble, ink text. The line follows
  DESIGN.md's "Stan's line" rule: Ranchers, capitals, a few words at most. That is why the draft lines below are
  short (builder check; my call).
- **Changes:** a new line pops the bubble in and makes him talk briefly, and a mood change cross-fades.
- **Frame rate:** capped by `maxFps`.
- **Screen readers:** hidden from them entirely (`clearAndSetSemantics {}`) and never focusable.
- **Dozing:** a timer moves to `Dozing` after about 60 s with no key. Any key wakes him, and the key still
  goes to the screen.

### Placement

A shared setup scaffold puts the form on the left and Stan in the bottom-right corner inside the TV safe area.
The form keeps its width. Stan's area is reserved, so the two never overlap at 960×540 dp.

### Mapping

One pure function per screen, `phase → (mood, line)`, in feature:onboarding, in the same style as live's
`stanSpecOf`. Unit-tested.

| Screen | Moment | Mood | Draft line |
| --- | --- | --- | --- |
| Welcome | arrives | Talking, then Idle | "EVENING! LET'S TUNE IN." |
| Server | idle | Idle | "WHAT'S ON TONIGHT?" |
| Server | testing | Busy | "FIDDLING WITH THE AERIAL" |
| Server | found | Pleased | "THERE IT IS!" |
| Server | can't reach / bad address | Shrug | "CAN'T PICK IT UP" |
| Trust / cleartext (and Launch Trust) | asks | Idle | "NEVER SEEN THIS ONE. YOUR CALL." |
| Sign in | idle | Idle | "PUT YOUR FEET UP" |
| Sign in | signing in | Busy | "ONE SEC…" |
| Sign in | failed | Shrug | "DIDN'T TAKE. AGAIN?" |
| Quick Connect | waiting | Idle | "TAKE YOUR TIME" |
| Libraries | opens after sign-in | Cheer, then Idle | "YOU'RE IN!" |
| Libraries | choosing | Idle | "WHAT ARE WE SHOWING?" |
| Libraries | loading / saving | Busy | "STAND BY…" |
| Libraries | failed / none | Shrug | "NOTHING ON THE SHELF" |
| Signed in (reached by Back) | arrives | Idle | "STILL HERE." |
| First sync | syncing | Busy | "WARMING UP THE CHANNELS" |
| First sync | waiting for network | Shrug | "NO SIGNAL. STILL TRYING." |
| First sync | failed | Shrug | "BIT OF TROUBLE" |
| First sync | done | Cheer | "WE'RE ON THE AIR!" (see below) |
| Launch | unavailable | Shrug | "CAN'T FIND THE STATION" |
| Launch | routing / trusting | no Stan | — |
| any | ~60 s with no key | Dozing | (no bubble) |

The lines are drafts for Phil to change in the mock-ups. Instructions and errors stay in the screen's own text.

**Two cheers with no screen of their own (builder check):**
- **Sign-in success** goes straight to the library picker, so the picker opens with the cheer, then settles to
  Idle. No navigation change. (My call.)
- **First sync finishing** goes straight to Live, and `FirstSyncPhase` has no Done state.
  **Phil chose (2026-11-18):** hold for about 1.5 s on Stan's Cheer ("WE'RE ON THE AIR!") before Live
  appears. This needs a new Done phase in `FirstSyncPhase`, the move to Live after the hold, and view-model and
  navigation test changes. With reduced motion it shows the still Cheer for the same hold. A key press skips the
  hold.

### Colours

Swap `StationBreakDarkColors` (stock `darkColorScheme`, purple border) for a dark scheme built from
`BrandColors`. Setup's buttons and fields take DESIGN.md's orange focus ring with no scale. This recolours the
setup screens, text fields, buttons and progress bars, and anything else that reads `MaterialTheme.colorScheme`
(parts of Settings), so Settings is checked as well. Contrast is checked at 4.5:1. No Light scheme yet (see
Non-goals).

### Pull requests

1. The warm Material colour scheme and the orange focus ring.
2. Moods and `StanHost` in core:ui (architecture review), with tests.
3. The setup scaffold, and Stan wired into the setup screens with the mapping. It may split into server and
   sign-in first, then libraries and first sync.

Mock-ups come before PR 2. A tune-and-jank check on the Insignia, through the test session, comes before
merging PR 3.

### Tests

- **Moods:** every mood's frame is finite, within the stage, and stable at the reduced-motion still.
- **Mapping:** every phase of every screen maps to a mood, with errors mapped to Shrug.
- **Screen reader:** a Compose test that Stan's node has no semantics, and that the status text is still
  announced once.
- **Doze:** the timer dozes after the timeout and wakes on a key, which still reaches the screen.
- **Focus:** existing setup focus tests still pass with the scaffold.

## Left to the builder

- Stan's exact size and offset, and the bubble shape and tail.
- The cross-fade timing and the talking-flap length.
- The `maxFps` value (start at 20 and measure).
- The new Cheer pose drawing, which follows the study's shape vocabulary.
- How the scaffold reserves Stan's area on screens with a tall form (server, after Found).
