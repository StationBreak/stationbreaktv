"""Round six of the channel-mark redraw: the rest of the World and Premium bands."""
import math
from marklib import *
from marklib import _n
from round2 import ellipse, star, crescent
from round4 import arc

MARKS = {}
def mark(number, key, name, says, idea, kind):
    def wrap(fn):
        MARKS[key] = dict(number=number, key=key, name=name, says=says, idea=idea, kind=kind, shapes=fn())
        return fn
    return wrap

HEART = lambda cx, cy, s: (f"M{_n(cx)} {_n(cy + s)} C{_n(cx - s * 1.5)} {_n(cy + s * .1)} {_n(cx - s * 1.25)} {_n(cy - s * 1.05)} {_n(cx - s * .55)} {_n(cy - s * .95)} "
                           f"C{_n(cx - s * .2)} {_n(cy - s * .9)} {_n(cx)} {_n(cy - s * .6)} {_n(cx)} {_n(cy - s * .45)} C{_n(cx)} {_n(cy - s * .6)} {_n(cx + s * .2)} {_n(cy - s * .9)} {_n(cx + s * .55)} {_n(cy - s * .95)} "
                           f"C{_n(cx + s * 1.25)} {_n(cy - s * 1.05)} {_n(cx + s * 1.5)} {_n(cy + s * .1)} {_n(cx)} {_n(cy + s)} Z")

@mark(404, "tokyo-after-school", "Tokyo After School", "Anime of every stripe, from the bell to bedtime.",
      "The school bell.", "symbol")
def _():
    teal = "#5FE0CC"
    bell = "M42 20 C58 20 62 36 62 52 C62 64 68 70 72 76 H12 C16 70 22 64 22 52 C22 36 26 20 42 20 Z"
    a, _b = text_box("righteous-400", "TOKYO", 86, 16, 188, 50, "l")
    b, _b = text_box("righteous-400", "AFTER", 86, 56, 188, 74, "l", "c", 0.1)
    c, _b = text_box("righteous-400", "SCHOOL", 86, 79, 188, 97, "l", "c", 0.03)
    return [fill(bell, teal), fill(circle(42, 16, 5), teal), fill("M34 80 A8 8 0 0 0 50 80 Z", WHITE),
            stroke("M8 34 Q4 46 8 58 M76 34 Q80 46 76 58", WHITE, 3.4), fill(a, teal), fill(b, WHITE), fill(c, WHITE)]

@mark(408, "giant-robot-hour", "Giant Robot Hour", "Mecha pilots, transforming frames and dramatic launch sequences.",
      "The head of a very large machine.", "symbol")
def _():
    blue = "#6FA8FF"
    head = "M28 22 H56 L70 40 V70 L58 92 H26 L14 70 V40 Z"
    visor = poly([(20, 46), (64, 46), (60, 58), (24, 58)])
    vents = " ".join(rrect(x, 72, 4, 12, 1) for x in (31, 40, 49))
    a, _b = text_box("bungee-400", "GIANT", 84, 14, 188, 38, "l")
    b, _b = text_box("bungee-400", "ROBOT", 84, 43, 188, 69, "l")
    c, _b = text_box("bungee-400", "HOUR", 84, 74, 188, 98, "l")
    return [fill(cut(head, visor, vents), blue, True), fill(rrect(6, 44, 7, 20, 2), WHITE), fill(rrect(71, 44, 7, 20, 2), WHITE), stroke("M42 22 V10", WHITE, 4),
            fill(a, WHITE), fill(b, blue), fill(c, WHITE)]

@mark(412, "power-level", "Power Level", "Shonen battles, training arcs and shouted attack names.",
      "A power meter, climbing.", "idea")
def _():
    red = "#FF5F5F"
    a, _b = text_box("anton-400", "POWER", 14, 10, 186, 54, "c", "c", 0.02, 10)
    b, _b = text_box("anton-400", "LEVEL", 14, 62, 102, 98, "l", "c", 0.02, 10)
    bars = [fill(rrect(112 + i * 11, 98 - (8 + i * 4.6), 7.5, 8 + i * 4.6, 1.5), red if i < 5 else WHITE) for i in range(7)]
    return [fill(a, red), fill(b, WHITE)] + bars

@mark(416, "tea-time", "Tea Time", "British television, brewed strong with a biscuit on the side.",
      "A cup of tea, with a biscuit on the saucer.", "symbol")
def _():
    green = "#8EDCA6"
    cup = "M14 34 H62 V52 Q62 76 38 76 Q14 76 14 52 Z"
    biscuit = cut(circle(66, 86, 8), circle(63, 84, 1.3), circle(69, 84, 1.3), circle(66, 89, 1.3))
    a, _b = text_box("playfair-display-900", "Tea", 90, 14, 188, 52, "l")
    b, _b = text_box("playfair-display-900", "Time", 90, 58, 188, 96, "l")
    return [fill(cup, green), stroke("M62 40 H70 Q78 40 78 50 Q78 60 68 60 H61", green, 5), fill(rrect(8, 82, 48, 6, 3), WHITE), fill(biscuit, "#FFD98A", True), fill(a, green), fill(b, WHITE)]

@mark(428, "seoul-sundays", "Seoul Sundays", "Korean dramas: slow burns, big hearts, sudden twists.",
      "The o in Seoul is a heart.", "idea")
def _():
    coral = "#FF8A80"; face = "sniglet-800"; size = 60; base = 58
    for _fit in range(2):
        _a, wa = text_at(face, "se", size, 0, base); _b2, wb = text_at(face, "ul", size, 0, base)
        xh = cap_height(face, size, "e"); s = xh * .56; gap = size * .04; total = wa + gap + s * 2.5 + gap + wb
        if total > 150: size *= 150 / total
    x = (W - total) / 2; a, _w = text_at(face, "se", size, x, base); cx = x + wa + gap + s * 1.25
    b, _w = text_at(face, "ul", size, cx + s * 1.25 + gap, base)
    c, _b = text_box(face, "SUNDAYS", 24, 70, 176, 98, "c", "c", 0.16)
    return [fill(a, WHITE), fill(b, WHITE), fill(HEART(cx, base - xh * .48, s), coral), fill(c, coral)]

@mark(432, "masala-hour", "Masala Hour", "Song, dance and drama, Bollywood style.",
      "Masala is a spice mix: a chilli with some heat coming off it.", "symbol")
def _():
    saffron, mag, chilli = "#FFC83D", "#FF62B0", "#FF5A5F"
    pod = "M22 34 C42 30 62 44 66 66 C68 80 62 94 54 96 C58 80 52 64 40 54 C32 48 22 46 16 44 C14 38 16 35 22 34 Z"
    a, _b = text_box("abril-fatface-400", "Masala", 82, 20, 188, 56, "l")
    b, _b = text_box("abril-fatface-400", "HOUR", 82, 64, 188, 90, "l", "c", 0.14)
    return [fill(pod, chilli), stroke("M20 38 Q14 28 20 18", "#8FE38A", 5.5), fill(a, saffron), fill(b, mag)]

@mark(436, "telenovela-tonight", "Telenovela Tonight", "Passion, betrayal and cliffhangers every night.",
      "The o in Tonight is a rose.", "idea")
def _():
    red = "#FF5C6C"; face = "yeseva-one-400"; size = 46; base = 96
    top, _b = text_box(face, "Telenovela", 20, 14, 180, 46)
    for _fit in range(2):
        _a, wa = text_at(face, "T", size, 0, base); _b2, wb = text_at(face, "night", size, 0, base)
        xh = cap_height(face, size, "n"); r = xh * .7; gap = size * .04; total = wa + gap + 2 * r + gap + wb
        if total > 150: size *= 150 / total
    x = (W - total) / 2; a, _w = text_at(face, "T", size, x, base); cx = x + wa + gap + r; cy = base - xh * .5
    b, _w = text_at(face, "night", size, cx + r + gap, base)
    pts = [(cx + (1 + t * r * .0016) * math.cos(math.radians(t)), cy + (1 + t * r * .0016) * math.sin(math.radians(t))) for t in range(0, 500, 20)]
    swirl = "M" + " L".join(f"{_n(px)} {_n(py)}" for px, py in pts)
    leaf = lambda dx, tilt: fill(ellipse(cx + dx, cy + r * 1.02, r * .42, r * .2, tilt), "#8FE38A")
    return [fill(top, WHITE), fill(a, red), fill(b, red), leaf(-r * .7, 24), leaf(r * .7, -24), fill(circle(cx, cy, r), red), stroke(swirl, WHITE, 2.4)]

@mark(440, "cherry-blossom-hour", "Cherry Blossom Hour", "Slice-of-life anime: quiet days, good tea and falling petals.",
      "One blossom, and two petals on the way down.", "symbol")
def _():
    pink = "#FFA3C7"
    petals = [fill(ellipse(42 + 17 * math.cos(math.radians(a)), 46 + 17 * math.sin(math.radians(a)), 13, 9.5, a), pink) for a in range(-90, 270, 72)]
    loose = [fill(ellipse(22, 84, 7, 4.4, 30), pink), fill(ellipse(60, 92, 6, 3.8, -24), pink)]
    a, _b = text_box("yeseva-one-400", "Cherry", 86, 12, 188, 40, "l")
    b, _b = text_box("yeseva-one-400", "Blossom", 86, 43, 188, 71, "l")
    c, _b = text_box("yeseva-one-400", "Hour", 86, 76, 188, 99, "l")
    return petals + [fill(circle(42, 46, 6), WHITE)] + loose + [fill(a, pink), fill(b, WHITE), fill(c, pink)]

@mark(504, "after-hours", "After Hours", "Adults only, and only if you ask for it.",
      "A padlock: it stays shut until you ask.", "symbol")
def _():
    mag = "#FF5FA8"
    body = cut(rrect(16, 48, 52, 44, 8), "M36.6 70 A7 7 0 1 1 47.4 70 L49 82 H35 Z")
    a, _b = text_box("oswald-700", "AFTER", 84, 22, 188, 52, "l", "c", 0.04)
    b, _b = text_box("oswald-700", "HOURS", 84, 58, 188, 92, "l", "c", 0.02)
    return [stroke("M27 48 V36 Q27 20 42 20 Q57 20 57 36 V48", mag, 7), fill(body, mag, True), fill(a, WHITE), fill(b, mag)]

@mark(0, "graveyard-shift", "Graveyard Shift", "Late-night oddities for insomniacs and night owls.",
      "A headstone under the moon.", "plate")
def _():
    moss = "#A9CF8E"
    stone = "M38 98 V46 C38 22 66 14 100 14 C134 14 162 22 162 46 V98 Z"
    a, _b = text_box("BarlowCondensed-800", "GRAVEYARD", 50, 34, 150, 56)
    b, _b = text_box("BarlowCondensed-800", "SHIFT", 58, 60, 142, 92)
    grass = stroke("M14 98 H186", moss, 4)
    return [fill(cut(stone, a, b), moss, True), grass, fill(crescent(178, 24, 10), "#FFE9A6", True)]

@mark(516, "cartoons-after-dark", "Cartoons After Dark", "Animation made for adults, with grown-up language.",
      "A speech bubble full of words we cannot print.", "symbol")
def _():
    violet = "#B98CFF"
    g, _b = text_box("anton-400", "#@$!", 20, 32, 68, 60)
    bubble = cut(rrect(12, 22, 64, 48, 12) + " M26 68 L20 88 L44 69 Z", g)
    a, _b = text_box("oswald-700", "CARTOONS", 86, 18, 188, 46, "l")
    b, _b = text_box("oswald-700", "AFTER", 86, 52, 188, 70, "l", "c", 0.1)
    c, _b = text_box("oswald-700", "DARK", 86, 76, 188, 98, "l", "c", 0.1)
    return [fill(bubble, violet, True), fill(a, violet), fill(b, WHITE), fill(c, WHITE)]

@mark(520, "family-business", "Family Business", "Mobsters, made men and dinners that end badly.",
      "A fedora.", "symbol")
def _():
    green = "#7BE08E"
    crown = "M22 64 C20 40 30 26 42 32 C50 22 60 22 66 32 C74 28 82 42 78 64 Z"
    hat = union(crown, ellipse(50, 68, 42, 9))
    band = "M22 54 Q50 60 78 54 V59.5 Q50 65.5 22 59.5 Z"
    a, _b = text_box("alfa-slab-one-400", "FAMILY", 98, 24, 188, 50, "l")
    b, _b = text_box("alfa-slab-one-400", "BUSINESS", 98, 58, 188, 82, "l")
    return [fill(cut(hat, band), green, True), fill(a, WHITE), fill(b, green)]

@mark(528, "satire-desk", "Comedy County", "Comedy with a twist of something else.",
      "The sign at the county line.", "plate")
def _():
    green = "#6FE08F"
    a, _b = text_box("bebas-neue-400", "COMEDY", 34, 22, 166, 46, "c", "c", 0.06)
    b, _b = text_box("bebas-neue-400", "COUNTY", 34, 50, 166, 74, "c", "c", 0.06)
    plate = cut(rrect(16, 8, 168, 78, 11), rrect(21, 13, 158, 68, 7), rrect(23.6, 15.6, 152.8, 62.8, 5), a, b)
    return [fill(plate, green, True), fill(rrect(46, 86, 7, 14, 1), WHITE), fill(rrect(147, 86, 7, 14, 1), WHITE)]

@mark(0, "directors-chair", "Director's Chair", "Director's cuts, commentary and the making of it all.",
      "The chair.", "symbol")
def _():
    red = "#FF6B6B"
    chair = [fill(rrect(18, 16, 50, 16, 3), red), stroke("M21 14 V58 M65 14 V58", WHITE, 4), fill(rrect(14, 52, 58, 11, 3), red), stroke("M20 64 L64 96 M66 64 L22 96", WHITE, 4.4)]
    a, _b = text_box("BarlowCondensed-800", "DIRECTOR'S", 86, 20, 188, 50, "l")
    b, _b = text_box("BarlowCondensed-800", "CHAIR", 86, 56, 188, 94, "l")
    return chair + [fill(a, WHITE), fill(b, red)]
