"""Round seven: the channel overhaul. Part one: the five renames from #1004 and the Family and Teen odds & ends."""
import math
from marklib import *
from round2 import ellipse, star

MARKS = {}
def mark(number, key, name, says, idea, kind):
    def wrap(fn):
        MARKS[key] = dict(number=number, key=key, name=name, says=says, idea=idea, kind=kind, shapes=fn())
        return fn
    return wrap

def die(cx, cy, size, tilt, color, pips):
    h = size / 2; sq = poly(rot([(cx - h, cy - h), (cx + h, cy - h), (cx + h, cy + h), (cx - h, cy + h)], tilt, cx, cy))
    dots = " ".join(circle(*rot([(cx + dx * size, cy + dy * size)], tilt, cx, cy)[0], size * .1) for dx, dy in pips)
    return fill(cut(sq, dots), color, True)

@mark(166, "open-road", "Riding Shotgun", "The passenger seat, a long highway, and a map on the dash.",
      "The view from the passenger seat: the mirror, with dice swinging from it.", "symbol")
def _():
    yel = "#FFD23F"
    mirror = [stroke("M42 8 V18", WHITE, 4), fill(rrect(16, 16, 52, 18, 7), WHITE), stroke("M34 34 L30 50 M50 34 L55 56", WHITE, 2.4)]
    dice = [die(29, 61, 21, -14, yel, ((-.26, -.26), (.26, .26))), die(57, 68, 21, 12, yel, ((-.26, -.26), (0, 0), (.26, .26)))]
    a, _b = text_box("BarlowCondensed-800", "RIDING", 84, 16, 188, 48, "l")
    b, _b = text_box("BarlowCondensed-800", "SHOTGUN", 84, 53, 188, 85, "l")
    dashes = [fill(rrect(x, 92, 15, 5, 2), yel) for x in (84, 108, 132, 156)]
    return mirror + dice + [fill(a, WHITE), fill(b, yel)] + dashes

@mark(220, "silver-screen", "Golden Age Matinee", "Golden-age matinees, in black and white, glamour intact.",
      "A frame of film, in gold now that the name is.", "plate")
def _():
    holes = " ".join(rrect(x, y, 9, 8, 2) for y in (19, 85) for x in range(24, 172, 17))
    a, _b = text_box("playfair-display-900", "Golden Age", 30, 32, 170, 54)
    b, _b = text_box("playfair-display-900", "Matinee", 44, 57, 156, 80)
    return [fill(cut(rrect(14, 14, 172, 84, 5), holes, a, b), "#F2C95C", True)]

@mark(110, "rabbit-ears", "Vertical Hold", "Fuzzy pictures from the fifties and sixties; adjust the antenna.",
      "A picture that has started to roll, and the knob that fixes it.", "plate")
def _():
    blue = "#AFD8FF"
    screen = "M26 16 H138 Q152 16 152 30 V82 Q152 96 138 96 H26 Q12 96 12 82 V30 Q12 16 26 16 Z"
    a, _b = text_box("righteous-400", "VERTICAL", 24, 26, 140, 46)
    b, _b = text_box("righteous-400", "HOLD", 30, 60, 134, 88)
    bar = poly([(12, 49), (152, 53), (152, 57.5), (12, 53.5)])
    knob = [fill(poly([(164, 26), (180, 26), (172, 14)]), WHITE), stroke(circle(172, 56, 12), WHITE, 5), stroke("M172 56 V46", WHITE, 4), fill(poly([(164, 86), (180, 86), (172, 98)]), WHITE)]
    return [fill(cut(screen, a, b, bar), blue, True)] + knob

@mark(232, "kung-fu-theater", "Fists & Fury", "Fists, flying kicks and dubbing that's slightly out of sync.",
      "A board chopped in two, with the blow landing in the middle.", "plate")
def _():
    gold, red = "#F7C948", "#FF6259"
    k, _b = text_box("anton-400", "FISTS", 22, 34, 84, 68); f, _b = text_box("anton-400", "FURY", 122, 34, 178, 68)
    left = [(12, 26), (90, 26), (97, 39), (89, 51), (97, 63), (90, 76), (12, 76)]; right = [(110, 26), (188, 26), (188, 76), (110, 76), (117, 63), (109, 51), (117, 39)]
    lk = f'<g transform="rotate(-5 54 51)">{fill(cut(poly(left), k), gold, True)}</g>'
    rk = f'<g transform="rotate(5 148 51)">{fill(cut(poly(right), f), gold, True)}</g>'
    burst = []
    for i in range(16):
        a = math.radians(i * 22.5 - 90); r = 19 if i % 2 == 0 else 12.5
        burst.append((100 + r * math.cos(a), 30 + r * math.sin(a)))
    amp, _b = text_box("anton-400", "&", 92, 20, 108, 40)
    return [lk, rk, fill(cut(poly(burst), amp), red, True)]

@mark(190, "satire-desk", "Oddly Funny", "Comedy with a twist, a little off, and that's the point.",
      "A face with one eye much bigger than the other.", "symbol")
def _():
    yel = "#FFDD4A"
    eyes = [fill(cut(circle(30, 40, 19), circle(35, 44, 7.5)), WHITE, True), fill(cut(circle(60, 46, 10.5), circle(58, 44, 4.2)), WHITE, True)]
    smile = stroke("M20 76 Q34 92 46 80 Q54 72 68 82", yel, 6)
    a, _b = text_box("lilita-one-400", "ODDLY", 86, 20, 188, 52, "l")
    b, _b = text_box("lilita-one-400", "FUNNY", 86, 58, 188, 92, "l")
    return eyes + [smile, fill(a, yel), fill(b, WHITE)]

def crate(color, text_d):
    """The cardboard box shared by the odds & ends family: open flaps, the name cut into the front."""
    box = cut("M28 50 H172 V94 Q172 98 168 98 H32 Q28 98 28 94 Z", text_d)
    return [fill(box, color, True), fill(poly([(28, 50), (12, 38), (46, 38), (58, 50)]), color), fill(poly([(172, 50), (188, 38), (154, 38), (142, 50)]), color)]

@mark(66, "family-odds-and-ends", "Family Odds & Ends", "Whatever the other family channels didn't take, still fine for everyone.",
      "The same box as the other odds & ends, with games-night bits on top.", "plate")
def _():
    coral = "#FF8E80"
    t, _b = text_box("baloo-2-800", "odds & ends", 40, 58, 160, 88)
    pawn = [fill(circle(70, 16, 6.5), WHITE), fill("M70 20 L79 40 H61 Z", WHITE)]
    card = fill(cut(poly(rot([(92, 12), (112, 12), (112, 40), (92, 40)], 10, 102, 26)), ellipse(102, 26, 4.4, 6)), coral, True)
    balloon = [fill(ellipse(138, 20, 9, 11), WHITE), stroke("M138 31 Q134 36 139 40", WHITE, 2)]
    return crate(coral, t) + pawn + [card] + balloon

@mark(99, "teen-odds-and-ends", "Teen Odds & Ends", "Whatever the other teen channels didn't take, nothing past PG-13.",
      "The same box again, with headphones, a bolt and a controller.", "plate")
def _():
    violet = "#B98CFF"
    t, _b = text_box("bungee-400", "ODDS & ENDS", 38, 62, 162, 86)
    phones = [stroke("M58 34 V26 Q58 12 72 12 Q86 12 86 26 V34", WHITE, 3.6), fill(rrect(53, 28, 9, 14, 3.5), WHITE), fill(rrect(82, 28, 9, 14, 3.5), WHITE)]
    bolt = fill(poly([(108, 10), (96, 28), (104, 28), (99, 42), (114, 22), (105, 22)]), violet)
    pad = fill(cut(rrect(122, 20, 34, 18, 9), circle(131, 29, 2.6), circle(147, 29, 2.6)), WHITE, True)
    return crate(violet, t) + phones + [bolt, pad]
