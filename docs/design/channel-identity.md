# Channel identity system

How StationBreak channels get a brand, and how the marks are produced. Replaces the pictorial 32dp tile icons described in `feature/guide/CHANNELMARKS.md`.

Generator and sample: `scripts/channelmarks/brands.py` + `brands.json` (see the README there). Approved sample sheet: `docs/design/channel-identity-sample.png`.

## Goal

StationBreak competes with apps that fill their guide with knock-offs of real networks. StationBreak's channels are 100% invented, and the brief is to make them **feel like networks that have always existed** without resembling any real one. So the lineup is a *portfolio of independent brands*, not one network family: every channel has its own typeface, its own colour, its own lockup, and the thing that makes them sit together is production discipline, the way real channels from different owners still look at home in the same cable guide.

## What a channel brand is

A **mark** (the logo shown in the guide), built from:

- a **typeface** chosen from the approved set below, used only for this channel's name;
- **one or two brand colours** plus white;
- a **lockup**: how the words are arranged (one line, two-tone word, big-over-small, script-over-caps, etc.);
- optionally **one device**: a geometric element (sun disc, dome arc, rules, dashed rule, crescent, dot) that gives the mark a silhouette.

Everything is flat. No gradients, bevels, shadows, outlines, tiles or backgrounds.

## Rules

1. **Original, not adjacent.** The name, the colour pairing and the lockup must not evoke a real channel. Test: cover the words — if the shape and colours alone call a real network to mind, change one of them. No splats, no ball-and-letter marks, no peacock-fan colour arrays, no "Jr." suffixes, no first-letter-only wordmarks that match a real network's silhouette.
2. **One typeface per channel, from the approved set.** A face may be used by at most 8 channels lineup-wide and at most 3 within a hundred-band, and never by two channels that are adjacent in the lineup (consecutive numbers in the band). When a face repeats within a band, the two marks must differ in both colour family and lockup form.
3. **Max two brand colours + white.** Every brand colour must read ≥ 4.5:1 on the guide base `#0E0F11`; that contrast ratio is the only brightness test. Nothing orange-adjacent (hue 10–35° at saturation above 50%) because the focused cell is orange and the mark would merge with it; everything at 36° and up is allowed.
4. **No shared look.** A lockup is arrangement (one line, two equal lines, big over small, small over big) + face style (serif, slab, round, condensed, script, display) + case (caps, lower, title) + device (or none). Two marks collide when all four match and the colour family matches; the earlier-approved mark keeps the combination, so a mark with a different arrangement, style, case or device never collides. Within a band, two marks sharing a face must also differ in colour family. No exact hex is used as the primary colour of more than two channels in a band. A mark's colour family is the family of its main brand colour (the non-white colour of its largest line); families and hue edges are in the table below, and face styles are listed under it.
5. **One device at most**, geometric only (arcs, circles, lines, triangles). Anything that needs more than that is an illustration and is out.
6. **Same box, same optical size.** Every mark fills a 50×28dp box with a 44×22dp safe area, centred, and is scaled to use the safe area fully. Two-line lockups are therefore smaller per line than one-line ones — intended.
7. **Legible at 28dp tall.** Check the 1:1 strip on the contact sheet. Long names get shortened to the part that carries the identity (The Midnight Projector → MIDNIGHT / PROJECTOR, Sing-Along Station → singalong / STATION). Secondary lines (any line set smaller than the main one) need a relative size of at least 0.30 and letter-spacing (tracking) of at most 0.22; if the small line is still thin, make it the big line or set it in a heavier face. This minimum is forward-looking: marks approved before it was written were reviewed at 1:1 and kept.
8. **Mono-safe.** Every mark must still read with all fills white. The generator writes this variant automatically (`svg-mono/`); it is used for the light theme and any future "quiet" setting.
9. **Band flavour, not band uniform.** Bands have a leaning, not a rule: Kids → rounded faces, saturated primaries; General → clean display faces, friendly mids; Drama → serifs and slabs, metals and jewel tones; Genre → condensed and italic, neon; World → warm, expressive; Premium → restrained, one colour.

### Colour families (hue edges)

Hue is measured in degrees in HSL. A colour with HSV saturation below 0.25 is **neutral** whatever its hue (silver, cream, white). Edges are inclusive at the lower end.

| Family | Hue range |
|---|---|
| red | 345° to 10° |
| yellow (includes tan) | 10° to 70° |
| green | 70° to 160° |
| teal | 160° to 192° |
| blue | 192° to 225° |
| blue-violet | 225° to 300° |
| pink | 300° to 345° |

Rule 3 still excludes saturated hues 10–35°, so in practice yellow starts at 36° for brand colours.

### Face styles (for the lockup in rule 4)

- **serif**: Playfair Display, Abril Fatface, Cinzel, Yeseva One
- **slab**: Alfa Slab One, Zilla Slab, Ultra, Special Elite
- **round**: Fredoka, Baloo 2, Sniglet, Boogaloo, Chewy
- **condensed**: Bebas Neue, Barlow Condensed, Anton, Oswald, Teko
- **script**: Pacifico
- **display**: Lilita One, Bungee, Titan One, Luckiest Guy, Righteous, Orbitron, Audiowide, Creepster

A new face goes into one of these styles when it is added to the approved set.

## Approved typefaces (all bundled in `scripts/channelmarks/fonts/`, OFL or Apache, licence text in `fonts/licenses/`; used by the generator only, never shipped in the app)

| Face | Feel | Good for |
|---|---|---|
| Fredoka 700 | rounded, bouncy | Kids |
| Baloo 2 800 | rounded, soft | Kids |
| Lilita One | chunky cartoon | Kids, comedy |
| Chewy | hand-lettered cartoon | Kids |
| Bungee | signage, two-tone words | General, comedy |
| Righteous | retro-tech display | General, tech, nostalgia |
| Pacifico | brush script (pair with caps) | holiday, romance, retro |
| Bebas Neue | tall caps | Genre, action, late night |
| Barlow Condensed 600/700/800 | industrial condensed | Genre, Premium |
| Alfa Slab One | heavy slab | Drama, westerns, court |
| Playfair Display 900 | high-contrast serif | Drama, prestige, classics |
| Abril Fatface | fat didone | World, food, glamour |
| Luckiest Guy | chunky comic caps | Kids, adventure, heroes |
| Titan One | fat, round, toy-like | Kids, vehicles, robots |
| Sniglet 800 | soft, friendly rounded | Kids, preschool, gentle |
| Boogaloo | hand-drawn, bouncy | Kids, animals, mystery |
| Cinzel 700 | classical caps (lowercase draws as small caps) | Drama, prestige, war |
| Yeseva One | romantic display serif | Drama, period, memoir |
| Zilla Slab 700 | sturdy modern slab | Drama, spies, war |
| Ultra | fat western slab | Drama, westerns, documentary |
| Special Elite | worn typewriter | Drama, true crime, mysteries |
| Anton | heavy condensed poster caps | Genre, superheroes, creature features |
| Oswald 700 | tall gothic, signage | Genre, drive-in, campfire |
| Teko 700 | squared tech condensed | Genre, cult, post-apocalyptic |
| Orbitron 900 | geometric sci-fi | Genre, space, sci-fi |
| Audiowide | wide rounded sci-fi | Genre, time travel, twists |
| Creepster | dripping horror display (two channels at most) | Genre, haunted, zombies |

Add a face only if it is OFL/Apache, visibly different from the faces above, and needed by at least two channels.

## Choosing a brand for a new channel

1. Read the channel's `personality` line in the catalog. Pick the typeface that *sounds* like it.
2. Pick one colour that matches the mood, check it against rule 3, then decide whether a second colour (or white) is needed to split the name into two parts.
3. Pick a lockup: single word, two-tone word, big-over-small, small-over-big, script-over-caps, two equal lines.
4. Add a device only if it says something about the channel (dome over Snowglobe, dashed modem line under Dial-Up, bars for Graveyard *Shift*). Plain is a valid identity.
5. Check the neighbours in the same band for typeface, colour and lockup collisions; apply rule 1.
6. Add the entry to `brands.json`, run the generator, look at the contact sheet at 1:1 and in mono.

## Files and formats

- Masters: SVG, 200×112 viewBox, all text converted to outlines. `scripts/channelmarks/out-brands/svg/sl_<NNN>_<key>.svg` (colour) and `svg-mono/` (white).
- Renders: PNG at 48dp and 64dp at 2× (96×54 and 128×72 px), transparent, colour and mono, produced by `brands.py --png` into `feature/guide/src/main/assets/channelmarks/` as `sl_<NNN>_<key>_<N>dp.png` and `sl_<NNN>_<key>_mono_<N>dp.png`. `AssetMarkLoader` chooses the size bucket by screen density. The pipeline and how to update it are in `feature/guide/CHANNELMARKS.md`.

## Runtime (feature:guide)

- `ChannelMark` draws the PNG centred in a 50×28dp box with **no tile**. Dark draws the colour render as it is.
- Light theme draws the mono render tinted with the guide's ink (`GuideColors.Light.markTint`), so every mark is one dark shape; no colour mark has to pass a contrast test on the light page.
- Details panel: the channel line may be tinted with the brand's first colour (store it as `brandColor` on `GuideRow`; the generator can emit a `brands-colors.json` sidecar).
- Fallback for user-built channels with no mark: initials in Manrope Bold 12sp, white, no tile.

## Approved sample (12 of 95, the first review set)

8 Sing-Along Station · 16 Saturday Morning · 56 Snowglobe · 106 Punchline · 122 Dial-Up · 182 Always December · 212 Order in the Court · 220 Silver Screen · 316 The Midnight Projector · 352 Neon Rain · 432 Masala Hour · 508 Graveyard Shift.

The other 83 channels were added to `brands.json` one band at a time, reviewing the contact sheet after each band; all 95 are done and shipped in the app, with marks for the two catch-all channels (Kids' Odds & Ends, 99, and Odds & Ends, 199).

## Superseded

`scripts/channelmarks/channelmarks.py` and `marks.json` were a first, monochrome single-typeface pass. They can be deleted once `brands.py` covers the lineup.
