---
name: StationBreak
description: Always-on broadcast channels from a Jellyfin library. Put your feet up.
colors:
  ink: "#1C1B19"
  panel: "#262320"
  line: "#3D3832"
  cream: "#F3EEE3"
  grey: "#CFCBC2"
  muted: "#B3AB9D"
  orange: "#E8642C"
  screen-lift-top: "#2E2A25"
  screen-lift-bottom: "#151412"
  rating-amber: "#FFD166"
  paper: "#F5F1E7"
  paper-edge: "#E6DFD0"
  deep-orange: "#AE400C"
  dark-grey: "#5E5850"
  paper-lift: "#FBF8F1"
  paper-line: "#D2CFC6"
  paper-track: "#D7D3CA"
typography:
  name:
    fontFamily: "Ranchers"
    fontWeight: 400
    lineHeight: 1
  stan-line:
    fontFamily: "Ranchers"
    fontWeight: 400
    lineHeight: 1
  teletype:
    fontFamily: "Space Mono"
    fontWeight: 700
    letterSpacing: "0.13em"
  headline:
    fontFamily: "Manrope"
    fontSize: "24sp"
    fontWeight: 600
    lineHeight: 1
    letterSpacing: "-0.015em"
  screen-body:
    fontFamily: "Manrope"
    fontSize: "20sp"
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
    fontFamily: "Space Mono"
    fontSize: "10sp"
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
    backgroundColor: "{colors.panel}"
    textColor: "{colors.cream}"
    typography: "{typography.title}"
    rounded: "{rounded.grid}"
    height: "39.8dp"
  programme-cell-focused:
    backgroundColor: "{colors.orange}"
    textColor: "{colors.ink}"
    typography: "{typography.title}"
    rounded: "{rounded.grid}"
  sheet:
    backgroundColor: "{colors.panel}"
    textColor: "{colors.cream}"
    typography: "{typography.sheet-body}"
    width: "380dp"
  sheet-action-focused:
    backgroundColor: "{colors.orange}"
    textColor: "{colors.ink}"
    typography: "{typography.sheet-body}"
    rounded: "{rounded.key-cap}"
  banner:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.cream}"
    rounded: "{rounded.grid}"
    height: "58dp"
    padding: "0 16dp"
  section-flag:
    textColor: "{colors.grey}"
    typography: "{typography.flag}"
  key-cap:
    textColor: "{colors.cream}"
    rounded: "{rounded.key-cap}"
    height: "16dp"
---

<!-- Brand source: the working draft "StationBreak Brand Standards" sheet (Stan, marks, colour and type, on-screen
template, voice). Items that sheet lists as still open are marked PROVISIONAL here; settle them there first, then
here. Several tokens below are the target, not yet the shipping code: see "Migration" at the end. -->

# Design System: StationBreak

## Overview

**Creative North Star: "Stan's Station"**

A station break is the moment the programme stops and the station speaks for itself. StationBreak's station is Stan: a console TV set with rabbit ears who has put his feet up. The whole app is his station, one look from the first setup screen to the guide at 2 AM. The ground is warm ink, because it's watched from a couch in a dim room. Cream carries the words, a little orange is the spark, and Stan turns up with one line when the station has something to say.

The look is unified: one ground, one palette, one pair of voice faces, one focus colour across setup, the guide, the channel screens, the banner and the station screens. What changes between surfaces is density, not identity. The guide is the station's listings: a dense, disciplined broadcast EPG on the same ink, where Stan stays out of the way and orange marks only "this one" and "now". The station screens (tuning, off the air, no signal, technical difficulties, first launch) are where Stan and the voice faces lead.

Nothing glares and nothing shouts. Surfaces are flat, depth is a tonal step plus a hairline, and motion is short and stops when the TV's animation scale is off. Density is set from the couch on the floor device: five guide rows at 960×540dp, nothing under 10sp.

**Key Characteristics:**
- One warm-ink world everywhere; screens get a soft warm lift toward the middle, so they never read as flat black or as white.
- Four brand colours: mostly ink, then cream, with orange as the spark and grey for small supporting text.
- Stan is the station's face. Without limbs he is the mark; with limbs, the character on station screens.
- Ranchers is the station's voice (the name, Stan's lines), and Space Mono Bold its teletype (status, clocks, flags). A plain reading face carries everything else.
- Stan gets one line; the plain status says what is happening and what to do. The joke never carries the information.
- Flat: no outlines, gradients or shadows on surfaces or on Stan (except the one shadow under his feet).

## Colors

Warm ink carries everything, cream does the talking, and orange is the spark.

### Primary
- **Orange** (#E8642C): Stan's ears, shoes, cheeks and tuning knob; the focus fill (with ink text, 5.2:1); the now line, NOW and status flags, progress; one word in the stacked name. A little goes a long way.

### Neutral
- **Ink** (#1C1B19): the ground. Every station screen and the guide family sit on it; it is also the cream-ground version's lettering.
- **Panel** (#262320) and **Line** (#3D3832): a raised surface (programme cells, sheets, the banner's row) and its 1dp hairline. These are the step up from ink.
- **Screen lift** (#2E2A25 to #151412): a full-screen ground's soft radial lift toward the middle. The guide grid uses flat ink; station screens and setup use the lift.
- **Cream** (#F3EEE3): Stan's cabinet, headlines, the name, primary text (14.9:1 on ink).
- **Grey** (#CFCBC2): small supporting text, outlines, key caps (10.6:1 on ink).
- **Muted** (#B3AB9D): the quietest text step, for metadata under a title (7.6:1 on ink, 6.3:1 on the lift's brightest point).
- **Rating amber** (#FFD166): the content-rating and KIDS badge. It's the only hue outside the four, kept because a rating must not read as focus.

### Light mode (paper)
The brand defines a light mode: ink and cream trade places, so Stan's cabinet turns ink and his screen cream. The ground is paper, not white. Light is the viewer's choice for bright rooms; Dark stays the default on TV, as the brand sheet notes.
- **Paper** (#F5F1E7) to **Paper edge** (#E6DFD0): the light ground, slightly darker toward the edges. The guide's page is flat paper (no lift); a programme card is paper edge, the step down.
- **Deep orange** (#AE400C): orange for words on paper (5.1:1 against cream, 5.3:1 on paper, 4.5:1 on a card). It carries ON NOW, the now line, progress, and the focus fill with cream text (5.1:1). Brand orange (#E8642C) is 2.9:1 on paper, so it stays for shapes (ears, shoes, cheeks).
- **Dark grey** (#5E5850): small supporting text on paper (6.2:1 on paper, 5.3:1 on a card); also the strokes (chevrons, ticks).
- **Ink** (#1C1B19): primary text and the tinted channel marks (15.3:1 on paper, 13.0:1 on a card).
- Derived for the guide, not from the brand sheet: **Paper lift** (#FBF8F1, a sheet that stands off the dimmed guide), **Paper line** (#D2CFC6, ink at 16% over paper: rules and key cap outlines) and **Paper track** (#D7D3CA, ink at 14% over paper: the progress track). The rating badge on a sheet is #7A4B00 (7.0:1).

### Named Rules
**The Spark Rule.** Orange is the spark, never the fill of a screen. In the guide family it means exactly "this one" (focus) or "now" (the now line, NOW, progress). Never use it for decoration or headings, and never for two unrelated things on one screen.

**The Readable Pairs Rule.** Text uses only the pairs the brand sheet clears:
- cream on ink, for anything;
- grey or muted on ink, for small text;
- orange on ink, and ink on orange, for labels, flags and headlines.
- on paper, words use deep orange and supporting text uses dark grey; ink for anything, cream on deep orange for the focus fill.

Cream on orange and orange on cream (2.9:1) are for shapes only, never text; on paper, brand orange is the same: shapes only. Orange text keeps to ink or panel; on the lift's brightest point it drops to 4.3:1, so it isn't used for small text there.

**The Viewer's Choice Rule.** The guide family follows the viewer's own Dark/Light choice, never the TV's system theme. Dark (warm ink) is the default.

## Typography

**Voice Font:** Ranchers (the name, Stan's lines)
**Teletype Font:** Space Mono Bold (status, clocks, flags, labels)
**Reading Font:** Manrope, PROVISIONAL (the brand sheet hasn't chosen a reading face; Manrope ships today, and the sheet uses Figtree as a stand-in)

**Character:** Ranchers is a relaxed, rounded display face with a grin: the station sounding like itself. Space Mono Bold is the teletype: capitals, spaced out, orange for the status and grey for the detail. The reading face stays quiet so these two can carry the brand. None of the three is used by a channel mark, so the station never looks like one of its own channels.

### Hierarchy
- **Name** (Ranchers, "Station Break", capital S and capital B): the wordmark and the station screens' sign-off.
- **Stan's line** (Ranchers, capitals, a few words at most): "PLEASE STAND BY", "GONE TO BED", "ANYBODY OUT THERE?". Never more than a few words. No closing mark on a statement, "?" on a question, "…" on a short wait (as in "ONE SEC…", "STAND BY…"), and "!" only at the big moments of setup (welcome, server found, "YOU'RE IN!", on the air).
- **Teletype status** (Space Mono Bold, capitals, +13% tracking): "TUNING CHANNEL 212", "OFF THE AIR · RETURNS AT 6:00 AM". Orange for the status, grey for the detail.
- **Headline** (reading face, SemiBold, 24sp, -0.015em): the guide panel's programme title, the info overlay title.
- **Screen body** (reading face, 20sp): setup messages, buttons and fields.
- **Sheet body / caption** (reading face, Medium 16sp / Regular 14sp): the properties sheet and channel rows.
- **Title** (reading face, Medium 13sp, 1.2 line height): programme names in the grid.
- **Body / label** (reading face, 11sp, Regular / Medium tabular): grid metadata, times, channel numbers.
- **Flag** (Space Mono Bold, 10sp, capitals, +13%): NOW, UP NEXT, section headers ("ON THE AIR · 48"), tags.

### Named Rules
**The Ten-Foot Floor Rule.** Nothing renders under 10sp, at any font scale. (The brand sheet's smallest station-screen text, 28px at 1080p, still needs checking from the couch: PROVISIONAL.)

**The Few Words Rule.** Ranchers is for the name and Stan's lines only, never more than a few words, and never for information the viewer needs to act on.

**The One Term Rule.** Each idea has one word everywhere. Getting changes from the server is **updating the library** ("Update library", "Updating…", "Updated at 9:41 PM"); first setup keeps "Getting your library ready". A channel is **switched on** or **switched off**, and a switched-on channel is **on the air**. One with nothing today is **not in today's guide** ("hidden" is not used). A gap with nothing scheduled is **off the air** ("Off the air" on a plate, in the panel and in speech), never a switched-off channel. A channel that plays what no other does is **Everything else**. What follows is **Up next**. The picture arriving is **tuning in**. Something in a slot is a **programme** (a series is a "show"). Picking from a list is **choose**, and the centre key is **OK**. The rest stay as they are: sign in and sign out, server, libraries and your library, lineup.
Known exceptions, still to settle: "Press Select to go on." on the signed-out screen (awaits the owner).

**The Three-Size Grid Rule.** The guide grid uses three sizes and two weights (13sp Medium titles, 11sp Regular metadata, 11sp Medium tabular figures). Hierarchy comes from that step and from cream, grey and muted, not from more sizes.

## Layout

The design canvas is 960×540dp. Text, Stan and pictures keep to the overscan safe area: 5% in from each edge (48dp sides, 27dp top and bottom), or 1.5% with Screen fit: Edge. `ScreenLayout.safeArea` is the one source. Grounds, hairlines and cell fills bleed to the screen edge.

**Station screens** are all built the same way: Stan on the left in the mood for the moment, his line in Ranchers, then the teletype status and what happens next. The name and the clock sit as a quiet sign-off line. One template covers tuning, off the air, no signal, technical difficulties, first launch and an empty library.

**The guide** is the station's listings: the live picture top-right (48% of the safe width, 16:9), the details panel top-left, and the grid below with five channel rows of 40.8dp, separated by 1dp gaps. The channel column is 84dp (number, 8dp, a 50×28dp mark box). The timeline strip is 20dp, and the hint row 20dp with 20dp between pairs; pairs drop in the order Menu, Left, OK, and Back is never dropped.

Spacing is small and exact: 1dp hairlines, 4dp tight steps, 8dp steps, 16dp gutters.

### Named Rules
**The Safe-Area Rule.** Text, marks, Stan, chevrons and focus rings keep inside the safe area; only grounds, hairlines and cell fills bleed.

**The Five-Row Rule.** The guide shows five rows at 960×540. Adding rows needs another review on the floor device.

## Elevation & Depth

StationBreak is flat. Depth is a tonal step (ink, then panel), a 1dp line, and dimming: a sheet dims what is behind it. Full-screen grounds have one soft warm radial lift, and that is the only gradient in the system. Stan casts the only shadow, the one under his feet. A scrolling part that runs past its edge fades out over 24dp instead of cutting a line in half.

### Named Rules
**The Flat Station Rule.** Never add a drop shadow, glow or outline to a surface or to Stan. To raise something, step it from ink to panel and give it a hairline.

## Shapes

Corners are soft on Stan and nearly square on the listings. Stan's cabinet is 4 wide by 3 high with soft corners; his limbs are single round-ended lines and his hands are dots. In the guide and on banner-family surfaces the radius is at most 2dp. Key caps and sheet actions take 3dp, the drawn focus ring 4dp, and the OK key cap is the one circle. Channel marks are flat colour on transparent in a 50×28dp box: no tile, border or shadow.

## Components

### Stan (signature)
The station's face, always drawn from the same parts: two rabbit ears with orange tips, the screen as his face, a cream cabinet, an ink screen, orange details (shoes, the tuning knob, cheeks when content), an ink volume knob, and a three-line speaker. On a cream ground the cabinet turns ink and the screen cream.
- **Moods:** Content (the default: icon, wordmark, banner), Awake (the screen asks the viewer something), Tuning (any short wait), Asleep (off the air, overnight, idle), Lost (the server is out of reach), On the fritz (something broke on our side; rare). A new situation reuses one of these before it earns a new drawing.
- **Never:** recolour him, stretch or squash him, put cream Stan on a light ground, or take his ears off.

### Marks
The icon is Stan's head, content, with no limbs: orange is the main version, ink where orange would shout. The name is "Station Break" in Ranchers, with the tagline "PUT YOUR FEET UP" in orange underneath. Drop the tagline below about 160px wide, and use the icon alone below about 100px (both PROVISIONAL, untested on a TV). In the stacked version on ink, "Break" may turn orange. On orange, the lettering is ink.

The launcher icon is the main orange icon (`icon-main.svg`: cream Stan on an orange tile; its adaptive foreground is Stan's head alone, fitted to the 66dp safe circle, over the orange ground). The TV banner is the brand banner (`tv-banner.svg`: Stan beside the wordmark and tagline, on ink). Both, and the README banner, are rendered from `art/brand/` by `scripts/render-brand-art.sh`. Stan's moods and the animated station screens come in the next step; the mood and screen SVGs in `art/brand/` are their reference art.

### Station Screen (signature)
Stan in a mood, a Ranchers line, a teletype status, then the next step. "PLEASE STAND BY · TUNING CHANNEL 212", "GONE TO BED · OFF THE AIR · RETURNS AT 6:00 AM", "ANYBODY OUT THERE? · NO SIGNAL · CAN'T REACH YOUR SERVER · OK TO RETRY". It has replaced the test-card slates on live TV.

### Programme Cell (listings)
Near-flat panel cells in one grid, 2dp corners, 1dp gaps. Title in 13sp Medium cream, metadata in 11sp muted. Focus is an orange fill with ink text: no growth, no shadow, a 160ms colour change, and none with reduced motion. The current programme gets a 1.5dp orange progress line along its bottom edge. A cell too narrow for a title (under 48dp) is its fill alone, with no "…", focused or not. Back-to-back programmes of one title, each too narrow to write it and with no episode line to lose, are named once across the run: the title, then a count of the run's programmes in the loaded guide in muted, both 13sp Medium ("Big Buck Bunny · 12"). The label is pinned to the part of the run in view, keeps clear of the chevrons the edge cells draw, steps aside to the wider side of the focused cell, and is not drawn at all where it would not hold the whole count and some of the title. Each programme stays its own cell with its own focus, panel and spoken description; the label is never read. Episode cells wide enough for their "S3 E4" line (110dp and up) keep their own title and line. The playing channel's tile carries a marker and "Playing" for screen readers.

### Now/Next Banner
A 58dp ink bar over the picture with a 1dp line, 2dp corners and a 2dp orange progress line along its top. It holds the mark and number, an 18sp title in cream, and a single 11sp line led by a teletype flag (NOW, TUNING IN, UP NEXT). The Select banner's buttons focus with an orange fill and no scale.

### Sheet and Rows (menu sheet, channel properties, channel screens, settings)
Sheets are panel-coloured, with a 1dp line on the edge facing the screen. The ground bleeds while the text keeps to the safe area, and the content scrolls and fades while actions stay pinned. A row's focus is the drawn ring in orange (`panelFocus`), with no scale. Section headers are teletype flags in grey with a count ("ON THE AIR · 48"), not text that looks like a row; a section of settings has no count ("PICTURE").

**Settings rows.** The guide's Settings is a left-edge sheet (`LeftSheet`: 380dp from the safe edge, over the dimmed guide, where the menu sheet stood; rows 36dp tall at least, as the menu sheet's, from the shared `SheetMeasures`). Every row is a label on the left in sheet-body, and what it holds on the right, so its kind shows without colour:
- a **choice** shows its value in muted and a dot for each value it has, the chosen one filled (never colour or an underline alone). Left and Right step the value in place, OK moves it on to the next one; neither closes the sheet. A screen reader hears the label, then the value as the row's state.
- a **link** (opens another screen) ends in a chevron "›" and is a button.
- an **action** ends in the round OK key cap and is a button. An action that cannot be taken back (sign out) asks first, over the sheet, with the focus on the safe answer ("Stay signed in"); Back is the safe answer too.

The hint row is pinned at the bottom with key caps and follows the row: "◂ ▸ Change · OK Next · Back Close" on a choice, "OK Open" on a link, and the action's own verb on an action ("OK Update", "OK Sign out"). A screen reader hears the arrows as words ("Left or right"). Back closes the sheet. The sign-out question's hint is "OK Choose · Back Stay signed in", and offers nothing while the sign-out runs. Section flags are in `grey` (dark grey on paper).

### Key Hints
A key cap (1dp grey stroke, 3dp corners, 16dp tall; OK is a circle) followed by a 10sp muted label. Every hint row uses key caps, including the menu sheet's. `KeyCap`, `KeyHint` and `KeyHintRow` in `core:ui` are the one drawing of them.

### Setup (onboarding)
Setup is a station screen too: the reading face at 20sp for fields and buttons, and focus in orange. A screen's main action is a 2 dp cream outline at rest; orange only for focus. Stan hosts each step from the bottom-right corner, reacting to what happens (pleased, shrug, busy, cheer, dozing).

## Do's and Don'ts

### Do:
- **Do** put every screen on warm ink, with the soft lift on full-screen grounds.
- **Do** keep orange to "this one" and "now" in the listings, and to Stan's details and one word on station screens.
- **Do** give Stan one line, then let the teletype status say plainly what is happening and what to do.
- **Do** use only the readable pairs: cream or grey on ink for text, and orange with ink for labels and flags.
- **Do** keep text, Stan and focus inside the safe area (48dp × 27dp at 960×540).
- **Do** keep every line at 10sp or larger.
- **Do** honour reduced motion: no scale, no spinner turn, and a still frame where something would move.

### Don't:
- **Don't** recolour, stretch or squash Stan, take his ears off, or put cream Stan on a light ground.
- **Don't** use Ranchers for more than a few words or for anything the viewer needs to act on.
- **Don't** set text in cream on orange or orange on cream (2.9:1).
- **Don't** add shadows, glows, outlines or gradients beyond the single ground lift and Stan's foot shadow.
- **Don't** round anything inside the grid or the banner family past 2dp, or scale focus inside a grid or list.
- **Don't** follow the TV's system theme for the guide family.
- **Don't** use a voice face in a channel mark, or draw a mark that evokes a real network.

## Migration (the design target is ahead of the code)

These tokens and components are the brand's target. The app reaches them in steps; until then the code is the record of what ships.
- **Light:** no longer behind. The guide's Light theme is the brand's paper mode (see Light mode above).
- **Ground:** the guide family ships a cool neutral page (#0E0F11) with white text at fixed alphas. The target is ink, panel, cream, grey and muted, as above, and it needs an on-TV check that the grid still reads at five rows.
- **Faces:** done. The station screens' sign-off is Ranchers, as the Name line says. Space Mono stays as the teletype (PROVISIONAL: still open on the sheet).
- **Marks:** done. The placeholder launcher icon, TV banner and README banner are replaced by the brand art in `art/brand/`.
- **Station screens:** done. Stan's station screens are the default; snow stays as an opt-in channel change through the When changing channel setting (Stand-by card or Static, the stand-by card by default) in the guide's Settings.
- **Words:** done. "Sync now" is "Update library", setup and loading lines say what is happening ("Asking your server for your library…", "Getting the listings…", "Tuning in…"), and the app keeps one term per idea (The One Term Rule).
