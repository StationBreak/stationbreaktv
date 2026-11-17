---
name: StationBreak
description: Always-on broadcast channels from a Jellyfin library, on the TVs people already own.
colors:
  on-air-orange: "#E8642C"
  on-air-orange-light-fill: "#AD4216"
  on-air-orange-light-text: "#8F3811"
  focus-ink-dark: "#1A0D06"
  after-hours-page: "#0E0F11"
  after-hours-sheet: "#141518"
  after-hours-sheet-line: "#3A3B40"
  daylight-page: "#D2D5DA"
  daylight-sheet: "#E4E6EA"
  listing-ink: "#111214"
  white-primary: "rgba(255, 255, 255, 0.90)"
  white-secondary: "rgba(255, 255, 255, 0.58)"
  white-supporting: "rgba(255, 255, 255, 0.38)"
  white-card: "rgba(255, 255, 255, 0.06)"
  white-line: "rgba(255, 255, 255, 0.10)"
  ink-primary: "rgba(17, 18, 20, 0.92)"
  ink-secondary: "rgba(17, 18, 20, 0.66)"
  ink-supporting: "rgba(17, 18, 20, 0.52)"
  ink-card: "rgba(17, 18, 20, 0.12)"
  ink-line: "rgba(17, 18, 20, 0.16)"
  rating-amber-dark: "#FFD166"
  rating-amber-light: "#7A4B00"
  test-card-ground: "#CFCBC2"
  test-card-ink: "#1C1B19"
  test-card-cream: "#F3EEE3"
  onboarding-ring: "#7E74B0"
typography:
  headline:
    fontFamily: "Manrope"
    fontSize: "24sp"
    fontWeight: 600
    lineHeight: 1
    letterSpacing: "-0.015em"
  screen-title:
    fontFamily: "Manrope"
    fontSize: "40sp"
    fontWeight: 400
  screen-body:
    fontFamily: "Manrope"
    fontSize: "20sp"
    fontWeight: 400
  screen-detail:
    fontFamily: "Manrope"
    fontSize: "18sp"
    fontWeight: 400
  sheet-body:
    fontFamily: "Manrope"
    fontSize: "16sp"
    fontWeight: 500
  sheet-caption:
    fontFamily: "Manrope"
    fontSize: "14sp"
    fontWeight: 400
  title:
    fontFamily: "Manrope"
    fontSize: "13sp"
    fontWeight: 500
    lineHeight: 1.2
  body:
    fontFamily: "Manrope"
    fontSize: "11sp"
    fontWeight: 400
    lineHeight: 1.2
  label:
    fontFamily: "Manrope"
    fontSize: "11sp"
    fontWeight: 500
    fontFeature: "tnum"
  flag:
    fontFamily: "Manrope"
    fontSize: "10sp"
    fontWeight: 700
    letterSpacing: "0.06em"
  ident-display:
    fontFamily: "Archivo Black"
    fontWeight: 400
    lineHeight: 1
  ident-mono:
    fontFamily: "Space Mono"
    fontWeight: 700
    letterSpacing: "0.13em"
rounded:
  grid: "2dp"
  key-cap: "3dp"
  focus-ring: "4dp"
spacing:
  hairline: "1dp"
  tight: "4dp"
  step: "8dp"
  gutter: "16dp"
  hint-pair: "20dp"
  safe-side: "48dp"
  safe-top: "27dp"
components:
  programme-cell:
    backgroundColor: "{colors.white-card}"
    textColor: "{colors.white-primary}"
    typography: "{typography.title}"
    rounded: "{rounded.grid}"
    height: "39.8dp"
  programme-cell-focused:
    backgroundColor: "{colors.on-air-orange}"
    textColor: "{colors.focus-ink-dark}"
    typography: "{typography.title}"
    rounded: "{rounded.grid}"
  sheet:
    backgroundColor: "{colors.after-hours-sheet}"
    textColor: "{colors.white-primary}"
    typography: "{typography.sheet-body}"
    width: "380dp"
  sheet-action:
    textColor: "{colors.white-primary}"
    typography: "{typography.sheet-body}"
    rounded: "{rounded.key-cap}"
  sheet-action-focused:
    backgroundColor: "{colors.on-air-orange}"
    textColor: "{colors.focus-ink-dark}"
    typography: "{typography.sheet-body}"
    rounded: "{rounded.key-cap}"
  banner:
    backgroundColor: "{colors.after-hours-page}"
    textColor: "{colors.white-primary}"
    rounded: "{rounded.grid}"
    height: "58dp"
    padding: "0 16dp"
  key-cap:
    textColor: "{colors.white-secondary}"
    rounded: "{rounded.key-cap}"
    height: "16dp"
  text-tab:
    textColor: "{colors.white-secondary}"
    typography: "{typography.body}"
  text-tab-active:
    textColor: "{colors.white-primary}"
    typography: "{typography.body}"
---

# Design System: StationBreak

## Overview

**Creative North Star: "The Station Ident"**

StationBreak is a broadcaster, not a media app. The ident is its signature: the test card with the TV-set icon, the STATION BREAK wordmark in a heavy display face, a mono caption, cream and ink, and one orange tally of colour. Everything else in the app is that station's listings and control room. They are deliberately quieter, so the on-air moments carry the brand: true neutrals, one accent, flat surfaces and type that reads from across a living room.

The system has two registers under one name. The **on-air register** (Archivo Black, Space Mono, test-card ground, cream and ink) appears only when the station is talking to the viewer: the ident card, the "we'll be right back" slates, the snow between channels. The **listings register** (Manrope, white or ink at fixed alphas on a near-black or mid-grey page, On-Air Orange for focus and "now") is everything the viewer operates: the guide, the banner, the menu sheet, the channel screens, settings and onboarding. The guide overhaul set the listings register's bar: a broadcast EPG, not a themed menu of rounded capsules.

Density is set from the couch on the floor device. The guide shows five channel rows at 960×540dp, and nothing drops below 10sp. Depth comes from tone and dimming, never from shadow. Focus is a fill or a ring, never growth inside a grid. Motion is short and stops entirely when the TV's animation scale is off.

**Key Characteristics:**
- Two registers: on-air (ident, slates, snow) and listings (everything operable), never mixed on one surface.
- One accent, On-Air Orange, for focus, now and progress. It is the only saturated colour outside channel marks.
- Neutrals are a single white (Dark) or ink (Light) at fixed alphas over the page, with no warm tints.
- Flat: 1dp hairlines and 2dp corners in the grid, no shadows, gradients or washes.
- The viewer picks Dark (the default) or Light for the guide family. The system theme is never followed.
- Text and pictures keep to the overscan safe area (48dp × 27dp at 960×540); surfaces bleed to the screen edge.

## Colors

Near-black or mid-grey neutrals carry everything, and one broadcast orange marks what is live and what has focus.

### Primary
- **On-Air Orange** (#E8642C): the Dark theme's single accent. It is the focus fill on a programme cell or action, the now line (35% alpha through the grid), the ON NOW / NOW flag, progress fills, the 2dp channel-focus outline and the 6dp now triangle. It is also the test card's tally colour, so the brand and the "now" signal are the same colour.
- **On-Air Orange, Daylight fill** (#AD4216) and **On-Air Orange, Daylight text** (#8F3811): the Light theme's darker oranges. Each is the least saturation shift that keeps the floor on the grey page: the fill holds 3:1 over a card, and the text form holds 4.5:1 for ON NOW and 3:1 for the cell progress line.

### Neutral
- **After-Hours Page** (#0E0F11): the Dark guide page, also the banner and transport strip surface at 92% (96% for the info overlay). It's a true neutral, with no wood or warm tint.
- **After-Hours Sheet** (#141518), with its **Sheet Line** (#3A3B40): a sheet over the dimmed guide (the menu sheet, channel properties), one step up from the page, with a 1dp line on the edge facing the screen.
- **Daylight Page** (#D2D5DA) and **Daylight Sheet** (#E4E6EA): the Light theme's mid-light grey (about 83% lightness; a white page glares on a TV) and the step above it.
- **Listing Ink** (#111214): the Light theme's single neutral. Text, cards, lines and the mono channel-mark tint are this ink at fixed alphas.
- **White at alphas** (Dark): primary text 90%, secondary 58%, supporting 38% (strokes only), card 6%, rules 10%, progress track 8%, key-cap stroke 28%. **Ink at alphas** (Light): 92 / 66 / 52 / 12 / 16 / 14%.
- **Focus Ink** (#1A0D06): text on an On-Air Orange fill in Dark, with 90% for its secondary line. Light uses white.
- **Rating Amber** (#FFD166 Dark, #7A4B00 Light): the content-rating and KIDS badge on a sheet, and the only other hue in the listings register.

### Tertiary (on-air register only)
- **Test-Card Ground** (#CFCBC2), **Test-Card Ink** (#1C1B19), **Test-Card Cream** (#F3EEE3): the ident card, the slates and their mono captions, taken from the app icon art. On-Air Orange is their accent.

### Named Rules
**The One Tally Rule.** On-Air Orange means "this one" or "now": focus, the now line, ON NOW, progress. It is never decoration, never a heading colour, and never on two unrelated things on one screen.

**The Composite Rule.** Neutral text is an alpha over the page, and contrast is judged on the composite: 4.5:1 for text up to 13sp, 3:1 for larger text and for strokes. Supporting alpha (38% white, 52% ink) is for chevrons, ticks and rules only, never text. `GuidePaletteTest` and `ContrastTest` enforce it.

**The Viewer's Choice Rule.** The guide family is Dark unless the viewer chose Light in Settings. It never follows the TV's system theme. The banner is always Dark, because it sits over the picture.

## Typography

**Listings Font:** Manrope (Regular, Medium, SemiBold, Bold; static files bundled in the APK)
**On-Air Display Font:** Archivo Black
**On-Air Mono Font:** Space Mono Bold

**Character:** Manrope is a calm, slightly geometric grotesque that stays legible at 11sp from three metres. Archivo Black and Space Mono are the station talking: a heavy wordmark and a typewriter caption, used only on the test card and the slates.

### Hierarchy
- **Screen title** (Regular, 40sp): tv-material's `headlineLarge`, the one large line on an onboarding screen.
- **Headline** (SemiBold, 24sp, -0.015em): the guide panel's programme title, the info overlay title. The one large thing on the guide.
- **Screen body / detail** (Regular, 20sp / 18sp): reading text, buttons and fields on full screens.
- **Sheet body / caption** (Medium 16sp / Regular 14sp): the properties sheet and channel rows. A sheet is read further back than the grid, so it never goes under 14sp.
- **Title** (Medium, 13sp, 1.2 line height): programme names in the grid.
- **Body** (Regular, 11sp, 1.2): grid metadata, the panel's channel line and overview (two lines at most), banner line two.
- **Label** (Medium, 11sp, tabular figures): times, ranges, the clock and channel numbers. Figures are tabular via `tnum`; everything else keeps proportional figures.
- **Flag** (Bold, 10sp, +6% tracking, uppercase): ON NOW, NOW, UP NEXT, tags. This is the floor.
- **Ident display / mono** (Archivo Black at 1.0 line height; Space Mono Bold at +13% tracking): STATION BREAK, slate titles, the ident caption (at least 10sp, 1.2 line height, three lines inside the disc).

### Named Rules
**The Ten-Foot Floor Rule.** Nothing in the app renders under 10sp, at any font scale. A design that needs smaller text needs fewer words.

**The Three-Size Grid Rule.** The guide grid uses three sizes and two weights: 13sp Medium titles, 11sp Regular metadata and 11sp Medium tabular figures. Hierarchy comes from that step and from colour alpha, not from more sizes.

**The Station Voice Rule.** Archivo Black and Space Mono appear only in the on-air register. The banner, flip bar and overlays left them behind in the banner restyle, and they don't come back.

## Layout

The design canvas is 960×540dp. All text and pictures keep to the overscan safe area: 5% in from each edge (48dp sides, 27dp top and bottom), or 1.5% when the viewer picks Screen fit: Edge. `ScreenLayout.safeArea` is the one source, so the guide, live picture, banner and channel screens move together. Surfaces bleed past it: the guide page, the timeline rule, programme cells running off the right edge and a sheet's ground all run to the screen edge while their text stays inside.

The guide is a broadcast EPG: the live picture top-right (48% of the safe width, 16:9, 414.7 × 233.3dp), the details panel top-left beside it, and the grid below with five channel rows of 40.8dp each, separated by 1dp gaps. The channel column is 84dp (number, 8dp, a 50×28dp mark box). The timeline strip is 20dp with a 1dp rule. The hint row is 20dp, with 20dp between pairs, and drops pairs in the order Menu, Left, OK when space runs out; Back is never dropped.

Spacing is small and exact: 1dp hairlines and gaps, 4dp tight steps, 8dp steps, 16dp gutters inside sheets and the banner. Full screens (onboarding, channels) use the same safe-area padding through a shared `safeAreaPadding`.

### Named Rules
**The Safe-Area Rule.** Text, marks, chevrons and focus rings keep inside the safe area. Only grounds, rules and cell fills bleed to the edge.

**The Five-Row Rule.** The guide shows five rows at 960×540. Density was reduced after on-TV review; adding rows needs another review on the floor device.

## Elevation & Depth

StationBreak is flat. There are no shadows, glows, gradients or washes in the guide family; the overhaul removed every one. Depth comes from three things only: a tonal step (sheet over page, card alpha over page), dimming (a sheet dims the guide behind it with a black scrim at 55%), and a 1dp line on the edge of a raised surface. A scrolling part that runs past its edge fades out over 24dp instead of cutting a line in half.

### Named Rules
**The Flat Broadcast Rule.** If a surface needs to stand forward, step its tone and give it a 1dp line. Never add a shadow.

## Shapes

Corners are nearly square, like a broadcast graphic. Inside the grid and on banner-family surfaces the radius is at most 2dp. Key caps and sheet actions take 3dp, and the drawn focus ring 4dp. The OK key cap is the one circle. Channel marks are flat colour on transparent in a 50×28dp box: no tile, border, tint (except Light's mono ink) or shadow.

The on-air register has its own geometry, the test card: concentric rings, crosshair rules, corner targets, the TV-set icon and a centre disc that the wordmark and caption must stay inside.

## Components

### Programme Cell (signature, listings)
Near-flat cells in one grid, read as listings, not buttons.
- **Shape:** 2dp corners, 1dp gaps between cells and rows.
- **Rest:** card alpha over the page; title 13sp Medium primary, metadata 11sp Regular secondary (`S2 E7 · Animated`, `1954 · NR · Musical`).
- **Focus:** On-Air Orange fill with Focus Ink text (white in Light). No growth, no shadow; 160ms colour change, none with reduced motion.
- **Now:** a 1.5dp progress line along the bottom edge, ending at the now line. The playing channel's tile carries a marker and "Playing" for screen readers.
- **Continuation:** a 1.5dp chevron in supporting alpha.

### Channel Mark
The channel's brand, typographic and original, drawn in a 50×28dp box beside an 11sp tabular number. Colour render in Dark, mono ink in Light. A user-built channel with no mark shows Manrope Bold 12sp initials in the same box. Channel focus is a 2dp On-Air Orange outline inside the 84dp column.

### Now/Next Banner
A 58dp bar over the picture: After-Hours Page at 92%, 1dp line, 2dp corners, 16dp sides, a 2dp progress line along its top. Mark and number, an 18sp Bold title, one 11sp secondary line led by a 10sp Bold flag (NOW, TUNING IN, UP NEXT), and "Next: … at …" right-aligned. The Select banner adds a 44dp row of 28dp buttons (12sp Medium, card fill; focus is an orange fill, with no scale and no ring).

### Sheet (menu sheet, channel properties)
A 380dp right-hand sheet over the dimmed guide, full height, its ground bleeding to the screen edge and its text inside the safe area. Identity at the top; the content scrolls and fades at the edge; actions and the Back hint stay pinned below. Captions 14sp, body 16sp. Actions are 16sp Medium with 3dp corners; focus is an On-Air Orange fill. Rows inside the guide family use `panelFocus`: the theme's ring, no scale.

### Key Hints
A key cap (1dp stroke at 28% white or ink, 3dp corners, 16dp tall; OK is a circle) followed by a 10sp secondary label. Pairs sit 20dp apart with no divider.

### Text Tabs (menu row)
Plain 11sp text, secondary at rest; active is primary with a 1dp underline. No pills, no fills.

### Buttons and Fields (onboarding)
Onboarding still runs on tv-material's baseline dark scheme in Manrope at 20sp. A focused button inverts its fill and takes a 3dp mid-tone ring (Onboarding Ring, #7E74B0, at least 3:1 against both the fill and the page) and a 1.08× scale. Fields keep their fill and take the primary ring at 1.02×. This is the one place focus still scales; it isn't a grid, so nothing is pushed into a neighbour.

### Ident Card and Slates (signature, on-air)
The test card on Test-Card Ground: rings and rules in Test-Card Ink, the TV-set icon with its orange screen, STATION BREAK in Archivo Black, and a Space Mono caption of at most three lines, kept inside the centre disc (corners within 150 of the disc's 150-unit radius). The slates for a missing show, no channels and no libraries reuse the card. Snow between channels is a static frame when motion is reduced.

## Do's and Don'ts

### Do:
- **Do** use On-Air Orange for exactly one meaning on a screen: focus or now.
- **Do** draw neutral text as white (Dark) or ink (Light) at the documented alphas, and check contrast on the composite (4.5:1 at 13sp and under).
- **Do** keep text, marks and focus rings inside the safe area (48dp × 27dp at 960×540) and let grounds bleed.
- **Do** show focus in the guide family as a fill or an unscaled ring (`panelFocus`), drawn from the screen's own state.
- **Do** keep every line at 10sp or larger, and use `guideProse` (1.2 line height) for anything that can wrap.
- **Do** step tone and add a 1dp line to raise a surface, and fade a scrolling edge rather than cut a line.
- **Do** keep Archivo Black and Space Mono to the test card, slates and ident.
- **Do** honour reduced motion: no scale, no spinner turn, a still frame for snow.

### Don't:
- **Don't** round anything inside the grid or the banner family past 2dp, or turn listings back into pills or capsules.
- **Don't** add shadows, glows, gradients or warm-tinted neutrals to the guide family.
- **Don't** scale focus inside a grid or a list of rows; it pushes an edge into the neighbour.
- **Don't** follow the TV's system theme for the guide family.
- **Don't** put a channel's name in the grid; the details panel already has it.
- **Don't** tile, outline, tint (other than Light's mono ink) or shadow a channel mark, or draw one that evokes a real network.
- **Don't** use the supporting alpha (38% white, 52% ink) for text.
