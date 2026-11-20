"""Round four of the channel-mark redraw: the rest of the Drama band."""
import math
from marklib import *
from marklib import _n
from round2 import ellipse, star

MARKS = {}
def mark(number, key, name, says, idea, kind):
    def wrap(fn):
        MARKS[key] = dict(number=number, key=key, name=name, says=says, idea=idea, kind=kind, shapes=fn())
        return fn
    return wrap

def arc(cx, cy, rx, ry, a0, a1, steps=24):
    pts = [(cx + rx * math.cos(math.radians(a0 + (a1 - a0) * i / steps)), cy + ry * math.sin(math.radians(a0 + (a1 - a0) * i / steps))) for i in range(steps + 1)]
    return "M" + " L".join(f"{_n(x)} {_n(y)}" for x, y in pts)

@mark(212, "order-in-the-court", "Order in the Court", "Lawyers, juries and a verdict before the credits.",
      "A courthouse: the five letters of COURT are its columns.", "idea")
def _():
    brass = "#E6BC5C"
    a, _b = text_box("alfa-slab-one-400", "ORDER IN THE", 34, 39.5, 166, 50.5, "c", "c", 0.1)
    c, _b = text_box("bebas-neue-400", "COURT", 30, 58, 170, 90, "c", "c", 0.16)
    return [fill(poly([(18, 34), (100, 10), (182, 34)]), brass), fill(cut(rrect(18, 36.5, 164, 17, 1), a), brass, True), fill(c, WHITE), fill(rrect(12, 93, 176, 6.5, 1), brass)]

@mark(220, "silver-screen", "Silver Screen", "Old Hollywood in black and white, the glamour intact.",
      "A frame of film.", "plate")
def _():
    holes = " ".join(rrect(x, y, 9, 8, 2) for y in (19, 85) for x in range(24, 172, 17))
    a, _b = text_box("playfair-display-900", "Silver", 44, 31, 156, 54)
    b, _b = text_box("playfair-display-900", "Screen", 44, 57, 156, 81)
    return [fill(cut(rrect(14, 14, 172, 84, 5), holes, a, b), "#DDE2EA", True)]

@mark(224, "dead-drop", "Dead Drop", "Spies, heists and a briefcase that explodes in ten seconds.",
      "The briefcase.", "plate")
def _():
    red = "#FF5F6D"
    a, _b = text_box("zilla-slab-700", "DEAD", 40, 45, 160, 67)
    b, _b = text_box("zilla-slab-700", "DROP", 40, 70, 160, 92)
    case = cut(rrect(18, 30, 164, 68, 8), a, b, rrect(50, 35, 16, 5, 2), rrect(134, 35, 16, 5, 2))
    return [stroke("M82 30 V22 Q82 15 89 15 H111 Q118 15 118 22 V30", red, 5), fill(case, red, True)]

@mark(228, "big-rumble", "Big Rumble", "Explosions, car chases and one-liners before the blast.",
      "An explosion with the name in the middle of it.", "plate")
def _():
    pts = []
    for i in range(28):
        a = math.radians(i * 360 / 28 - 90); k = 1 if i % 2 == 0 else .76
        pts.append((100 + 90 * k * math.cos(a), 56 + 46 * k * math.sin(a)))
    a, _b = text_box("titan-one-400", "BIG", 70, 26, 130, 48, "c", "c", 0, 8)
    b, _b = text_box("titan-one-400", "RUMBLE", 44, 51, 156, 80, "c", "c", 0, 8)
    return [fill(cut(poly(pts), a, b), "#FFD93D", True)]

@mark(232, "kung-fu-theater", "Kung Fu Theater", "Fists, flying kicks and dubbing that's slightly out of sync.",
      "A board, chopped clean in two.", "plate")
def _():
    gold, red = "#F7C948", "#FF6259"
    left = rot([(14, 18), (92, 18), (98, 30), (90, 40), (98, 52), (92, 64), (14, 64)], -5, 56, 41)
    right = rot([(108, 18), (186, 18), (186, 64), (108, 64), (114, 52), (106, 40), (114, 30)], 5, 146, 41)
    k, _b = text_box("anton-400", "KUNG", 26, 26, 82, 56); f, _b = text_box("anton-400", "FU", 126, 26, 174, 56)
    lk = f'<g transform="rotate(-5 56 41)">{fill(cut(poly([(14, 18), (92, 18), (98, 30), (90, 40), (98, 52), (92, 64), (14, 64)]), k), gold, True)}</g>'
    rk = f'<g transform="rotate(5 146 41)">{fill(cut(poly([(108, 18), (186, 18), (186, 64), (108, 64), (114, 52), (106, 40), (114, 30)]), f), gold, True)}</g>'
    t, _b = text_box("anton-400", "THEATER", 34, 76, 166, 99, "c", "c", 0.14)
    return [lk, rk, fill(t, red)]

@mark(236, "docs-and-dusters", "Docs & Dusters", "Documentaries and history, with plenty of archive footage.",
      "A reel of archive film.", "symbol")
def _():
    cream, sage = "#EFE6CF", "#A9C99A"
    holes = " ".join(circle(44 + 17 * math.cos(math.radians(a)), 56 + 17 * math.sin(math.radians(a)), 7.2) for a in range(-90, 270, 72)) + " " + circle(44, 56, 3.6)
    a, _b = text_box("ultra-400", "DOCS", 86, 20, 188, 52, "l")
    b, _b = text_box("ultra-400", "& DUSTERS", 86, 60, 188, 84, "l")
    return [fill(cut(circle(44, 56, 31), holes), cream, True), fill(a, cream), fill(b, sage)]

@mark(240, "front-lines", "Front Lines", "War stories, from the trenches to the command tent.",
      "Three stripes on a sleeve, and stencilled lettering.", "symbol")
def _():
    olive = "#B7C980"
    chev = lambda y: poly([(14, y + 16), (40, y), (66, y + 16), (66, y + 27), (40, y + 11), (14, y + 27)])
    a, _b = text_box("stardos-stencil-700", "FRONT", 80, 22, 188, 52, "l")
    b, _b = text_box("stardos-stencil-700", "LINES", 80, 60, 188, 90, "l")
    return [fill(chev(18), olive), fill(chev(38), olive), fill(chev(58), olive), fill(a, WHITE), fill(b, olive)]

@mark(244, "dusty-trails", "Dusty Trails", "Westerns: horses, saloons and a showdown at noon.",
      "A cactus under the noon sun.", "symbol")
def _():
    turq = "#5EDCC8"
    cactus = [stroke("M40 96 V30", turq, 13), stroke("M40 74 H22 V52", turq, 9), stroke("M40 64 H58 V44", turq, 9)]
    a, _b = text_box("rye-400", "DUSTY", 80, 22, 188, 52, "l")
    b, _b = text_box("rye-400", "TRAILS", 80, 60, 188, 90, "l")
    return cactus + [fill(circle(64, 22, 8), "#FFD86B"), fill(a, turq), fill(b, WHITE)]

@mark(248, "noir-nights", "Noir Nights", "Rain-slick streets, trench coats and a narrator who's seen too much.",
      "A cone of light from a street lamp.", "plate")
def _():
    a, _b = text_box("abril-fatface-400", "NOIR", 74, 42, 126, 61)
    b, _b = text_box("abril-fatface-400", "NIGHTS", 50, 66, 150, 90)
    beam = poly([(92, 18), (108, 18), (186, 98), (14, 98)])
    return [fill(cut(beam, a, b), "#F2EFE2", True), fill("M84 18 Q84 8 100 8 Q116 8 116 18 Z", "#FF5F78")]

@mark(252, "corsets-and-candlelight", "Corsets & Candlelight", "Period dramas, grand houses and well-timed glances.",
      "The first l in Candlelight is a lit candle.", "idea")
def _():
    ame, gold = "#C9A6FF", "#FFD86B"; face = "yeseva-one-400"; size = 46; base = 96
    top, _b = text_box(face, "Corsets &", 38, 16, 162, 42)
    for _fit in range(2):
        _a, wa = text_at(face, "Cand", size, 0, base); _b2, wb = text_at(face, "elight", size, 0, base)
        cw = size * .2; gap = size * .07; total = wa + gap + cw + gap + wb
        if total > 174: size *= 174 / total
    x = (W - total) / 2; h = cap_height(face, size, "d")
    a, _w = text_at(face, "Cand", size, x, base); cx = x + wa + gap
    b, _w = text_at(face, "elight", size, cx + cw + gap, base)
    fx, fy = cx + cw / 2, base - h * 1.1
    flame = f"M{_n(fx)} {_n(fy - 20)} C{_n(fx + 7)} {_n(fy - 8)} {_n(fx + 5.4)} {_n(fy)} {_n(fx)} {_n(fy)} C{_n(fx - 5.4)} {_n(fy)} {_n(fx - 7)} {_n(fy - 8)} {_n(fx)} {_n(fy - 20)} Z"
    return [fill(top, ame), fill(a, gold), fill(b, gold), fill(rrect(cx, base - h * .96, cw, h * .96, 1.5), WHITE), stroke(f"M{_n(fx)} {_n(base - h * .96)} V{_n(fy)}", WHITE, 1.6), fill(rrect(cx - cw * .35, base - 3, cw * 1.7, 3, 1.5), WHITE), fill(flame, gold)]

@mark(256, "case-files", "Case Files", "True crime, told straight and told slowly.",
      "A fingerprint.", "symbol")
def _():
    red = "#FF6464"
    ridges = [stroke(arc(42, 62, rx, ry, a0, a1), WHITE, 3.6) for rx, ry, a0, a1 in
              ((4.5, 7, 200, 420), (10.5, 15, 150, 365), (16.5, 23, 170, 410), (22.5, 31, 125, 380), (28.5, 39, 160, 420))]
    a, _b = text_box("special-elite-400", "CASE", 84, 20, 188, 52, "l")
    b, _b = text_box("special-elite-400", "FILES", 84, 58, 188, 90, "l")
    return ridges + [fill(a, WHITE), fill(b, red)]

@mark(264, "disaster-zone", "Disaster Zone", "Earthquakes, tornadoes, volcanoes, and an ensemble cast in peril.",
      "A warning sign.", "symbol")
def _():
    yel = "#FFD93D"
    sign = "M42 16 Q46 10 50 16 L76 82 Q79 90 70 90 H22 Q13 90 16 82 Z" if False else "M41 18 Q46 10 51 18 L78 80 Q82 90 71 90 H21 Q10 90 14 80 Z"
    bang = rrect(42, 36, 8, 28, 3.5) + " " + circle(46, 75, 4.6)
    a, _b = text_box("bungee-400", "DISASTER", 88, 28, 188, 50, "l")
    b, _b = text_box("bungee-400", "ZONE", 88, 56, 188, 90, "l")
    return [fill(cut(sign, bang), yel, True), fill(a, WHITE), fill(b, yel)]
