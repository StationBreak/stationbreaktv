# StationBreak — ChannelMarks v0 (text/initials)

Owner: Look. Status: WORKING DRAFT. Text/initials only. No image logos and no server-loaded assets until a rule for them exists (Phil).
Brand is provisional: cream `#F3EEE3`, ink `#1C1B19`, accent `#E8642C`; Archivo Black for initials, Roboto Bold for numbers.

## Source of truth in the repo (StationBreak/stationbreaktv, read via GitHub, not cloned)
- Bands: `core/engine/.../lineup/Recipe.kt` → `enum class Band`: Kids 1–99, General 100–199, Drama 200–299, Genre 300–399, World 400–499, Premium 500–599.
- Custom channels 600–899, reserved/seasonal 900–999 (`LineupDefaults.kt`). Leftovers: 99 "Kids' Odds & Ends", 199 "Odds & Ends".
- Starter roster: `core/engine/.../lineup/StarterCatalog.kt` (about 90 recipes with `key`, `name`, `number`, `band`). Numbers step by 4.
- Repo has **no** ChannelMark, logo or icon field on channels yet, and no News or Music band. `art/placeholder/` holds only launcher-icon, repo-banner and tv-banner SVGs.

## Naming convention
`cm_<NNN>_<recipe-key>_<dp>dp.png` (for example `cm_102_laugh-track_48dp.png`). Vector: `svg/cm_<NNN>_<recipe-key>.svg`. NNN is the zero-padded channel number. The key is the StarterCatalog `key`, so a mark stays tied to its recipe if the number changes.

## Sizes (@xhdpi = 2×)
| Use | dp | px @xhdpi | Content |
|---|---|---|---|
| Guide row | 32 | 64 | Initials only (number is already in the guide column) |
| Bug / on-air | 48 | 96 | Initials + number |
| Banner / channel card | 64 | 128 | Initials + number |
Tile: rounded square (radius 16%), short accent tick at top-left (a radio-tower "signal" bar).

## Band → tile treatment
| Band | Tile | Initials | Tick |
|---|---|---|---|
| Kids, General | cream + ink border | ink | accent |
| Drama, Genre, World | ink + warm-grey border `#5A554C` (so it shows on ink screens) | cream | accent |
| Premium | ink | **accent** | cream |
| Custom 600–899 / Reserved 900+ | TBD (suggest cream, dashed border) | | |
Accent is used once per tile. It never fills the tile.

## Initials rules
1. Drop the stop words *The, A, An, of, in, the, &, and* and punctuation (`Hey, Science!` → Hey Science).
2. Two or more significant words: take the first letter of the **first and last** significant words, in caps (Order in the Court → OC, Tokyo After School → TS, The Midnight Projector → MP).
3. One word: capital letter + next lowercase letter (Prestige → Pr, Snowglobe → Sn).
4. At most 2 glyphs. Never 3; they fail at 32dp from 10 ft.
5. Collisions in the same band: the higher-numbered channel falls back to rule 3 on its first word (Spooky Sleepover → "Sp", vs Sing-Along Station "SS"). Implement as a function, not a hand list.
6. A user rename recomputes the initials. A user override field is a later decision.

## Sample roster (pack v0, 12 marks)
| No. | Name | Band | Initials | Tile |
|---|---|---|---|---|
| 8 | Sing-Along Station | Kids | SS | cream |
| 16 | Saturday Morning | Kids | SM | cream |
| 56 | Snowglobe | Kids | Sn | cream |
| 102 | Laugh Track | General | LT | cream |
| 110 | Rabbit Ears | General | RE | cream |
| 178 | Backstage Pass | General | BP | cream |
| 204 | Prestige | Drama | Pr | ink |
| 212 | Order in the Court | Drama | OC | ink |
| 316 | The Midnight Projector | Genre | MP | ink |
| 404 | Tokyo After School | World | TS | ink |
| 416 | Tea Time | World | TT | ink |
| 504 | After Hours | Premium | AH | ink/accent |
Note the SS clash: Sing-Along Station (8) and Spooky Sleepover (76) are both Kids/SS. Rule 5 is needed.

## Image logos later
- Same tile frame and sizes. An image sits inside a 12% safe inset, with the number kept at 48/64dp.
- Bundled assets only (vector drawables in `art/` → `res/drawable`) until a rule allows server-loaded logos. Jellyfin channel images are not used.
- Fallback is always the text mark. It is generated in Compose (`ChannelMark(number, name, band)`) and not shipped as PNGs. The PNGs here are reference renders only.

## Files
- `marks/channelmarks-sheet-v0.png`: sheet with all 12 marks at 32/48/64dp
- `marks/channelmarks-on-ink-48dp.png`: 48dp strip on an ink background (contrast check)
- `marks/channelmarks/cm_*_{32,48,64}dp.png`: 36 PNGs
- `marks/channelmarks/svg/cm_*.svg`: 12 SVGs
- `marks/channelmarks/build.py`: generator (PIL). Edit the roster `R` and rerun.

## Image logos (fictional stations)
Status: v0 draft, made in parallel with Q-BRAND-1 (Phil: these are fictional in-app stations, **not** the StationBreak company brand, so no A/B/C brand marks are used). Style: mid-century broadcast / World of Tomorrow vernacular, flat vector, no third-party IP. Each station has its own palette. Light tiles use an ink border and dark tiles use a warm-grey `#5A554C` border, so they read on both cream and ink.
Bundled assets only (StarterCatalog channels ship in-app). Nothing is loaded from Jellyfin or a server. The text ChannelMark stays the fallback.

- Generator: `marks/station-logos/build_logos.py` (SVG → cairosvg → PIL)
- Vector masters: `marks/station-logos/svg/sl_<NNN>_<key>.svg` (512 viewBox)
- PNGs: `marks/station-logos/sl_<NNN>_<key>_{512,32dp,48dp,64dp}.png` (512 / 64 / 96 / 128 px)
- Contact sheet: `marks/station-logos-sheet-v0.png`

| No. | Key | Concept |
|---|---|---|
| 8 | sing-along-station | Music note inside a dashed broadcast ring |
| 16 | saturday-morning | Sun with rays rising out of a cereal bowl |
| 56 | snowglobe | Snow globe with a tiny radio tower inside |
| 102 | laugh-track | Grinning TV-screen mouth, "HA HA" |
| 110 | rabbit-ears | Rabbit-ear antenna as a bunny head |
| 178 | backstage-pass | Lanyard pass with a star |
| 204 | prestige | Deco laurel crest, gold serif P |
| 212 | order-in-the-court | Gavel strike on a plinth |
| 316 | midnight-projector | Crescent moon throwing a projector beam |
| 404 | tokyo-after-school | Red sun disc with a tower silhouette |
| 416 | tea-time | Teacup with signal-wave steam |
| 504 | after-hours | Neon clock, pink ring and cyan hands |
Notes: the "HA HA" text and the Prestige P get small at 32dp. Consider a no-text 32dp variant. Android use would mean converting the SVGs to vector drawables in `art/` → `res/drawable`.

### Batch 2: full StarterCatalog (Nov 4, 2026)
The roster was pulled through the GitHub API from `core/engine/src/commonMain/kotlin/tv/stationbreak/core/engine/lineup/StarterCatalog.kt`: 95 recipes plus the 2 leftovers (99, 199), **97 channels in total**. All 97 now have logos: 12 from v0 and 85 new. The style rules and frame are the same as v0.
- Generator: `marks/station-logos/build_logos2.py` (batch 2). `roster.json` is the parsed catalog and `batch2.json` lists what was built. `sheets.py` builds the sheets.
- Sheets: `marks/station-logos-sheet-all.png` (all 97), plus one sheet per band: `station-logos-sheet-{kids,general,drama,genre,world,premium}.png`
- Each sheet shows the 512 px art and an ink strip with 80/64/48/32 px versions for a 10-ft check.

| No. | Key | Band | Concept |
|---|---|---|---|
| 4 | little-farm | Kids | Red barn under a sun |
| 12 | once-upon | Kids | Open storybook with a wishing star |
| 20 | backyard-fort | Kids | Treehouse fort with a pennant |
| 24 | critter-channel | Kids | Big paw print |
| 28 | robot-factory | Kids | Boxy tin robot head with bulb antenna |
| 32 | hey-science | Kids | Atom with orbits |
| 36 | rainy-day-matinee | Kids | Yellow umbrella in the rain |
| 40 | little-spirits | Kids | Friendly blushing little ghost |
| 44 | lights-out | Kids | Crescent moon with sleepy stars |
| 48 | pocket-heroes | Kids | Little domino mask over a hero star |
| 52 | flipbook | Kids | Stacked flipbook pages with a bouncing ball |
| 60 | giggle-garage | Kids | Garage door, comic red |
| 64 | wheels-and-whistles | Kids | Steam engine puffing clouds |
| 68 | small-sleuths | Kids | Magnifying glass |
| 72 | tall-tales-club | Kids | Sailing ship |
| 76 | spooky-sleepover | Kids | Glowing tent with flashlight beam |
| 80 | tiny-chefs | Kids | Chef's toque with red band |
| 99 | kids-odds-and-ends | Kids | Four bright toy blocks + star |
| 106 | punchline | General | Exclamation burst |
| 114 | shag-carpet | General | Seventies wavy stripes |
| 118 | neon-decade | General | Synth sunset grid with neon triangle |
| 122 | dial-up | General | Desk modem handset |
| 126 | flip-phone | General | Open flip phone |
| 130 | binge-row | General | Row of three TVs with play arrow |
| 134 | fresh-cut | General | Fresh sprout |
| 138 | glass-house | General | Glass house with red REC dot |
| 142 | game-night | General | Big die in marquee lights |
| 146 | couch-talk | General | Sofa with speech bubble |
| 150 | matinee-tears | General | Single big teardrop |
| 154 | sideline | General | Playing field lines |
| 158 | spoof-central | General | Wry comedy mask, raised brow |
| 162 | date-night | General | Heart under a starry sky |
| 166 | open-road | General | Highway to the horizon sun |
| 170 | simmer | General | Steaming pot |
| 174 | fixer-hour | General | Crossed wrench & screwdriver |
| 182 | always-december | General | Christmas tree with gold star |
| 186 | curtain-call | General | Stage curtains and spotlight star |
| 199 | odds-and-ends | General | Mixed shapes grab-bag |
| 208 | cold-case-cable | Drama | Case folder with red tape |
| 216 | code-blue | Drama | Medical cross with heartbeat |
| 220 | silver-screen | Drama | Silver movie screen with film sprockets |
| 224 | dead-drop | Drama | Briefcase with countdown dot |
| 228 | big-rumble | Drama | Explosion burst |
| 232 | kung-fu-theater | Drama | Temple gong with mallet |
| 236 | docs-and-dusters | Drama | Explorer's pith helmet over archive card |
| 240 | front-lines | Drama | Field flags on a ridgeline |
| 244 | dusty-trails | Drama | Cactus and mesa at high noon |
| 248 | noir-nights | Drama | Fedora in the rain under a streetlamp |
| 252 | corsets-and-candlelight | Drama | Single candle flame |
| 256 | case-files | Drama | Redacted case file |
| 260 | life-stories | Drama | Gilt portrait frame silhouette |
| 264 | disaster-zone | Drama | Hazard warning triangle |
| 304 | starport | Genre | Mid-century rocket |
| 308 | dragons-and-doorways | Genre | Arched tavern door under a dragon wing |
| 312 | rubber-suit-theatre | Genre | Giant monster over miniature city |
| 320 | the-cheap-seats | Genre | Row of threadbare cinema seats |
| 324 | drive-in-double | Genre | Drive-in screen with car fin |
| 328 | cape-and-cowl | Genre | Flowing cape with emblem star |
| 332 | cult-following | Genre | Hypnotic concentric rings |
| 336 | shuffle-and-groan | Genre | Shambling green hand-out silhouette |
| 340 | jack-o-lantern-lounge | Genre | Grinning jack-o'-lantern |
| 344 | campfire-tales | Genre | Campfire flame on crossed logs |
| 348 | paradox | Genre | Hourglass |
| 352 | neon-rain | Genre | Neon sign in a rainy skyline |
| 356 | deep-space-drift | Genre | Ringed planet |
| 360 | creature-feature | Genre | Swamp serpent rising |
| 364 | end-times | Genre | Blasted sunset over a lonely diner |
| 368 | haunted-house | Genre | Haunted house with lit windows |
| 372 | twist-ending | Genre | Spiral twist |
| 376 | gallows-humor | Genre | Grinning skull with a wink grin |
| 408 | giant-robot-hour | World | Mecha head with horn fins |
| 412 | power-level | World | Lightning bolt |
| 420 | whodunit-isles | World | Island lighthouse and cottage |
| 424 | continental-drift | World | Globe with drifting continents |
| 428 | seoul-sundays | World | Swirl-heart pair in a ring |
| 432 | masala-hour | World | Marigold mandala bloom |
| 436 | telenovela-tonight | World | Broken heart cliffhanger |
| 440 | cherry-blossom-hour | World | Five-petal cherry blossom |
| 508 | graveyard-shift | Premium | Tombstone under a night moon |
| 512 | one-season-only | Premium | Tear-off calendar showing 1 |
| 516 | cartoons-after-dark | Premium | Wide-eyed toon face in the dark |
| 520 | family-business | Premium | Fedora with red band and tie |
| 524 | anthology-hour | Premium | Row of book spines |
| 528 | satire-desk | Premium | Anchor desk with smirking screen |
| 532 | directors-chair | Premium | Director's chair |
Weakest at 32dp (candidates for a redo): seoul-sundays, twist-ending, cold-case-cable (small text), satire-desk ("NEWS?" text), drive-in-double ("2X"), and dial-up.
