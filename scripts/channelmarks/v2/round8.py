"""Round eight: the channel overhaul, part two. The new channels from #1002 and #1017 to #1026."""
import math
from marklib import *
from marklib import _n
from round2 import ellipse, star
from round4 import arc

MARKS = {}
def mark(number, key, name, says, idea, kind):
    def wrap(fn):
        MARKS[key] = dict(number=number, key=key, name=name, says=says, idea=idea, kind=kind, shapes=fn())
        return fn
    return wrap

def row(face, parts, base, limit, size=60, gap=.05):
    """Set a line where some letters are objects. parts: strings, or a number (an object's width as a share of the
    type size). Returns ([(path data or None, x, width)], size), centred on the canvas and no wider than limit."""
    for _fit in range(3):
        ws = [p * size if not isinstance(p, str) else text_at(face, p, size, 0, base)[1] for p in parts]
        total = sum(ws) + gap * size * (len(parts) - 1)
        if total > limit: size *= limit / total
    x = (W - total) / 2; out = []
    for p, w in zip(parts, ws):
        out.append((text_at(face, p, size, x, base)[0] if isinstance(p, str) else None, x, w)); x += w + gap * size
    return out, size

def puffs(spots, r):
    return union(*[circle(x, y, r * k) for x, y, k in spots])

@mark(48, "popcorn-matinee", "Popcorn Matinee", "Live-action family films, popcorn in hand.",
      "A tub of popcorn with the name on the side.", "plate")
def _():
    red = "#FF6B6B"
    tub = poly([(22, 40), (178, 40), (166, 100), (34, 100)])
    a, _b = text_box("titan-one-400", "POPCORN", 36, 46, 164, 68)
    b, _b = text_box("titan-one-400", "MATINEE", 42, 73, 158, 94)
    corn = puffs([(34, 34, 1), (52, 26, 1.15), (72, 31, 1), (90, 22, 1.2), (110, 30, 1.05), (128, 23, 1.15), (148, 31, 1), (166, 34, .95), (62, 36, .9), (100, 36, .9), (138, 36, .9)], 10)
    return [fill(cut(tub, a, b), red, True), fill(corn, WHITE)]

@mark(51, "big-screen-toons", "Big Screen Toons", "Animated features for everyone on the couch.",
      "A cinema screen, and the two O's are looking back at you.", "plate")
def _():
    lime = "#B6F24B"; face = "luckiest-guy-400"
    screen = "M14 18 Q100 8 186 18 V94 Q100 104 14 94 Z"
    top, _b = text_box(face, "BIG SCREEN", 36, 22, 164, 40)
    parts, size = row(face, ["T", .66, .66, "NS"], 88, 150, 62)
    h = cap_height(face, size); holes = [top]; eyes = []
    for d, x, w in parts:
        if d: holes.append(d)
        else:
            cx, cy, r = x + w / 2, 88 - h / 2, min(w, h) / 2
            holes.append(circle(cx, cy, r))
            eyes.append(fill(cut(circle(cx, cy, r * .78), circle(cx + r * .2, cy + r * .1, r * .36)), WHITE, True))
    return [fill(cut(screen, *holes), lime, True)] + eyes

@mark(54, "ink-and-paint", "Ink & Paint", "Big-screen cartoons, colored carefully inside the lines.",
      "Ink is only the outline; Paint is coloured in, and one letter has dripped.", "idea")
def _():
    pink = "#FF7EB6"; face = "fredoka-700"
    ink, (l, t, r, b) = text_box(face, "INK", 34, 16, 118, 50, "l")
    amp, _b = text_box(face, "&", r + 12, 16, r + 46, 50, "l")
    paint, (pl, pt, pr, pb) = text_box(face, "PAINT", 16, 58, 184, 92)
    drip = union(rrect(pl + 9, pb - 6, 7, 17, 3.5), rrect(pr - 16, pb - 6, 6, 12, 3))
    return [stroke(ink, WHITE, 2.6), fill(amp, pink), fill(paint, pink), fill(drip, pink)]

@mark(57, "starship-sunday", "Starship Sunday", "Rockets, aliens and big-screen space adventures, rated for the whole family.",
      "A rocket on its way up, beside the name.", "symbol")
def _():
    sky, yel = "#9EC9FF", "#FFE066"
    body = "M0 -40 C13 -26 15 -4 11 16 H-11 C-15 -4 -13 -26 0 -40 Z"
    fins = "M-11 2 L-24 22 L-11 16 Z M11 2 L24 22 L11 16 Z"
    flame = "M-7 20 Q0 44 7 20 Z"
    g = (f'<g transform="translate(40 58) rotate(28)">{fill(cut(body, circle(0, -14, 5.5)), WHITE, True)}{fill(fins, sky)}{fill(flame, yel)}</g>')
    stars = [fill(star(16, 22, 5), yel), fill(star(68, 88, 4), yel), fill(star(72, 18, 3), WHITE)]
    a, _b = text_box("righteous-400", "STARSHIP", 82, 22, 188, 50, "l")
    b, _b = text_box("righteous-400", "SUNDAY", 82, 58, 188, 88, "l")
    return [g] + stars + [fill(a, WHITE), fill(b, sky)]

@mark(60, "prime-time-pals", "Prime Time Pals", "Family series, a little something for every age.",
      "Two pals, a big one and a little one, side by side.", "symbol")
def _():
    teal, yel = "#5FE0C8", "#FFDD4A"
    def pal(cx, cy, r, color):
        eyes = circle(cx - r * .36, cy - r * .16, r * .13) + " " + circle(cx + r * .36, cy - r * .16, r * .13)
        grin = f"M{_n(cx - r * .5)} {_n(cy + r * .2)} Q{_n(cx)} {_n(cy + r * .95)} {_n(cx + r * .5)} {_n(cy + r * .2)} Z"
        return fill(cut(circle(cx, cy, r), eyes, grin), color, True)
    a, _b = text_box("baloo-2-800", "PRIME TIME", 14, 14, 186, 40)
    b, _b = text_box("baloo-2-800", "PALS", 92, 50, 186, 98, "l")
    return [pal(36, 72, 24, teal), pal(72, 82, 15, yel), fill(a, WHITE), fill(b, teal)]

@mark(73, "mask-and-mantle", "Mask & Mantle", "Capes, cowls and comic-book heroes, with a little more bite.",
      "A cape with the name on it, and the mask above.", "plate")
def _():
    red = "#FF5A6E"
    cape = "M52 40 H148 Q168 60 188 92 Q177 84 166 96 Q155 86 144 98 Q133 88 122 100 Q111 90 100 100 Q89 90 78 100 Q67 88 56 98 Q45 86 34 96 Q23 84 12 92 Q32 60 52 40 Z"
    a, _b = text_box("bungee-400", "MASK &", 58, 46, 142, 62)
    b, _b = text_box("bungee-400", "MANTLE", 40, 66, 160, 84)
    mask = "M70 14 Q85 9 100 15 Q115 9 130 14 Q138 16 134 24 Q128 36 114 34 Q105 33 100 26 Q95 33 86 34 Q72 36 66 24 Q62 16 70 14 Z"
    eyes = ellipse(84, 22, 7.5, 4.6, 10) + " " + ellipse(116, 22, 7.5, 4.6, -10)
    return [fill(cut(cape, a, b), red, True), fill(cut(mask, eyes), WHITE, True)]

@mark(76, "level-up", "Level Up", "Anime for the teens, with the volume turned up.",
      "The volume bars climb, and the arrow says which way.", "symbol")
def _():
    pink, yel = "#FF7EDB", "#FFE14D"
    bars = [fill(rrect(14 + i * 13, 88 - (12 + i * 14), 9, 12 + i * 14, 2.5), pink if i < 4 else yel) for i in range(5)]
    a, _b = text_box("orbitron-900", "LEVEL", 86, 20, 188, 50, "l")
    b, (l, t, r, bt) = text_box("orbitron-900", "UP", 86, 58, 150, 92, "l")
    ax = r + 20; arrow = poly([(ax, t - 2), (ax + 17, t + 16), (ax + 7, t + 16), (ax + 7, bt), (ax - 7, bt), (ax - 7, t + 16), (ax - 17, t + 16)])
    return bars + [fill(a, WHITE), fill(b, yel), fill(arrow, yel)]

@mark(79, "popcorn-epic", "Popcorn Epic", "Big adventures on a big screen, no one gets out of their seat.",
      "A mountain of popcorn with a flag planted on the summit.", "symbol")
def _():
    butter, red = "#FFE27A", "#FF6B6B"
    spots = []
    for rowi, n in enumerate((1, 2, 3, 4, 5)):
        y = 34 + rowi * 14
        for i in range(n):
            spots.append((44 + (i - (n - 1) / 2) * 13.5, y, 1))
    heap = puffs(spots, 9.2)
    flag = [stroke("M44 28 V10", WHITE, 2.4), fill(poly([(45, 10), (62, 15), (45, 21)]), red)]
    a, _b = text_box("anton-400", "POPCORN", 88, 18, 188, 46, "l")
    b, _b = text_box("anton-400", "EPIC", 88, 52, 188, 98, "l")
    return [fill(heap, butter)] + flag + [fill(a, WHITE), fill(b, butter)]

@mark(82, "nervous-laughter", "Nervous Laughter", "Scares you can watch with a friend, and a hand to grab.",
      "The letters have the shakes, and the O is trying to smile.", "idea")
def _():
    green = "#A8EB6A"; face = "chewy-400"; base = 50; size = 50
    letters = ["N", "E", "R", "V", None, "U", "S"]
    for _fit in range(3):
        ws = [size * .62 if c is None else text_at(face, c, size, 0, base)[1] for c in letters]
        total = sum(ws) + size * .03 * 6
        if total > 172: size *= 172 / total
    h = cap_height(face, size); x = (W - total) / 2; out = []
    for i, (c, w) in enumerate(zip(letters, ws)):
        tilt = (-7, 6, -5, 7, 0, -6, 6)[i]; dy = (2, -2, 1, -1, 0, -2, 2)[i]
        if c:
            d, _w = text_at(face, c, size, 0, 0)
            out.append(f'<g transform="translate({_n(x)} {_n(base + dy)}) rotate({tilt} {_n(w / 2)} {_n(-h / 2)})">{fill(d, WHITE)}</g>')
        else:
            cx, cy, r = x + w / 2, base - h / 2, h * .52
            eyes = circle(cx - r * .36, cy - r * .2, r * .2) + " " + circle(cx + r * .36, cy - r * .2, r * .2)
            mx = [cx - r * .55 + i * r * .22 for i in range(6)]
            zig = poly([(mx[j], cy + r * (.3 if j % 2 == 0 else .52)) for j in range(6)] + [(mx[j], cy + r * (.3 if j % 2 == 0 else .52) + r * .17) for j in range(5, -1, -1)])
            out.append(fill(cut(circle(cx, cy, r), eyes, zig), green, True))
            out.append(fill(f"M{_n(cx + r * .95)} {_n(cy - r * 1.25)} q{_n(r * .3)} {_n(r * .5)} 0 {_n(r * .62)} q{_n(-r * .3)} {_n(-r * .12)} 0 {_n(-r * .62)} Z", "#8FD3FF"))
        x += w + size * .03
    b, _b = text_box(face, "LAUGHTER", 16, 62, 184, 98)
    return out + [fill(b, green)]

@mark(192, "wild-hours", "Wild Hours", "Lions, glaciers and a narrator with a soothing voice.",
      "A paw print where the O in Hours should be.", "idea")
def _():
    green, sand = "#8EE07A", "#F2DFA6"; face = "rammetto-one-400"
    a, _b = text_box(face, "WILD", 40, 14, 160, 50)
    parts, size = row(face, ["H", .78, "URS"], 96, 172, 44, gap=.04)
    h = cap_height(face, size); out = [fill(a, green)]
    for d, x, w in parts:
        if d: out.append(fill(d, sand))
        else:
            cx, r = x + w / 2, w / 2
            pad = ellipse(cx, 96 - h * .3, r * .62, h * .3)
            toes = [ellipse(cx + dx * r, 96 - h * dy, r * .24, h * .15, tl) for dx, dy, tl in ((-.72, .6, -20), (-.26, .84, -6), (.26, .84, 6), (.72, .6, 20))]
            out.append(fill(union(pad) + " " + " ".join(toes), green))
    return out

@mark(194, "couch-premiere", "Couch Premiere", "Made-for-TV movies: tidy plots, tidy budgets, tidy endings.",
      "A sofa cushion, with a premiere's star and lights on it.", "plate")
def _():
    plum = "#E58BD0"
    cushion = "M22 18 Q100 26 178 18 Q190 16 188 28 Q180 56 188 84 Q190 96 178 94 Q100 86 22 94 Q10 96 12 84 Q20 56 12 28 Q10 16 22 18 Z"
    a, _b = text_box("limelight-400", "COUCH", 52, 30, 148, 52)
    b, _b = text_box("limelight-400", "PREMIERE", 30, 58, 170, 80)
    stars = star(34, 41, 7) + " " + star(166, 41, 7)
    return [fill(cut(cushion, a, b, stars), plum, True)]

@mark(198, "short-stack", "Short Stack", "Short films, back to back, none long enough for a nap.",
      "A short stack of pancakes, one word on each.", "plate")
def _():
    gold = "#F5C96B"
    a, _b = text_box("lilita-one-400", "SHORT", 44, 37, 156, 57)
    b, _b = text_box("lilita-one-400", "STACK", 36, 70, 164, 92)
    top = fill(cut(rrect(26, 32, 148, 30, 15), a), gold, True); bottom = fill(cut(rrect(14, 65, 172, 32, 16), b), gold, True)
    butter = fill(rrect(88, 19, 24, 13, 3), WHITE)
    return [bottom, top, butter]

@mark(210, "wrong-side", "Wrong Side", "Capers, hoods and cop shows that never solve a mystery.",
      "A one-way arrow, pointing the other way.", "plate")
def _():
    red = "#FF5A5A"
    arrow = poly([(12, 56), (52, 16), (52, 32), (188, 32), (188, 80), (52, 80), (52, 96)])
    t, _b = text_box("oswald-700", "WRONG SIDE", 44, 41, 178, 71)
    return [fill(cut(arrow, t), red, True)]

@mark(272, "smoke-filled-room", "Smoke-Filled Room", "Back rooms, ballots and one speech that changes everything.",
      "A cloud of cigar smoke with the name in it, and the cigar underneath.", "plate")
def _():
    smoke = "#CFC8DC"
    d = "M27 30"; x, y = 27, 30
    for dx, dy, n in ((29.2, 0, 5), (0, 22, 2), (-29.2, 0, 5), (0, -22, 2)):
        for _i in range(n):
            x += dx; y += dy; r = math.hypot(dx, dy) / 2 * 1.08
            d += f" A{_n(r)} {_n(r)} 0 0 1 {_n(x)} {_n(y)}"
    cloud = d + " Z"
    a, _b = text_box("zilla-slab-700", "SMOKE-FILLED", 38, 28, 162, 46)
    b, _b = text_box("zilla-slab-700", "ROOM", 58, 50, 142, 74)
    wisp = stroke("M62 91 Q54 88 58 84", smoke, 3)
    cigar = [fill(rrect(64, 91, 54, 8, 4), WHITE), fill(rrect(118, 91, 8, 8, 3), "#FF5A5A")]
    return [fill(cut(cloud, a, b), smoke, True), wisp] + cigar

@mark(276, "banners-and-battlements", "Banners & Battlements", "Swords, sandals and a cast of thousands.",
      "A castle wall with the name on it and banners flying from the top.", "plate")
def _():
    stone, red = "#DCCFAE", "#FF6B6B"
    pts = [(12, 100), (12, 34)]; n = 7; mw = 16; gw = (176 - n * mw) / (n - 1); x = 12
    for i in range(n):
        pts.append((x + mw, 34))
        if i < n - 1: pts += [(x + mw, 44), (x + mw + gw, 44), (x + mw + gw, 34)]
        x += mw + gw
    pts.append((188, 100))
    a, _b = text_box("cinzel-700", "BANNERS &", 40, 51, 160, 67)
    b, _b = text_box("cinzel-700", "BATTLEMENTS", 20, 74, 180, 91)
    def banner(px, flip):
        k = -1 if flip else 1
        return [stroke(f"M{px} 34 V10", WHITE, 2.2), fill(poly([(px + k, 11), (px + k * 24, 14), (px + k * 17, 19), (px + k * 24, 24), (px + k, 26)]), red)]
    return [fill(cut(poly(pts), a, b), stone, True)] + banner(20, False) + banner(180, True)

@mark(284, "out-and-about", "Out & About", "Queer stories up front, with nobody left in the margins.",
      "A rainbow, and the name walking out from under it.", "symbol")
def _():
    bands = ["#FF5A6E", "#FFD84D", "#5FDD8A", "#5CB8FF", "#B98CFF"]
    arcs = [stroke(arc(46, 84, 34 - i * 6.5, 34 - i * 6.5, 180, 360), c, 5.4, "butt") for i, c in enumerate(bands)]
    a, _b = text_box("fredoka-700", "OUT &", 92, 20, 188, 50, "l")
    b, _b = text_box("fredoka-700", "ABOUT", 92, 56, 188, 88, "l")
    return arcs + [fill(a, WHITE), fill(b, WHITE)]

@mark(380, "festival-circuit", "Festival Circuit", "Indie films that did the festival rounds and never quite left.",
      "Festival laurels, the kind on every indie poster.", "symbol")
def _():
    gold = "#EBD9A0"
    def laurel(mirror):
        out = []; cx, cy, rx, ry = 40, 54, 22, 40
        fx = (lambda x: 200 - x) if mirror else (lambda x: x)
        for i in range(8):
            a = math.radians(112 + i * 19); px, py = cx + rx * math.cos(a), cy + ry * math.sin(a)
            tang = math.degrees(math.atan2(ry * math.cos(a), -rx * math.sin(a)))
            for side in (-1, 1):
                tilt = tang + side * 38; lx = px + 6 * math.cos(math.radians(tilt)); ly = py + 6 * math.sin(math.radians(tilt))
                out.append(fill(ellipse(fx(lx), ly, 6.2, 2.6, (180 - tilt) if mirror else tilt), gold))
        pts = [(fx(cx + rx * math.cos(math.radians(t))), cy + ry * math.sin(math.radians(t))) for t in range(108, 250, 6)]
        out.append(stroke("M" + " L".join(f"{_n(x)} {_n(y)}" for x, y in pts), gold, 1.6))
        return out
    a, _b = text_box("yeseva-one-400", "FESTIVAL", 48, 28, 152, 52)
    b, _b = text_box("yeseva-one-400", "CIRCUIT", 52, 60, 148, 84)
    return laurel(False) + laurel(True) + [fill(a, WHITE), fill(b, WHITE)]

@mark(444, "fjord-and-fog", "Fjord & Fog", "Nordic crime: gray skies, long coats, longer silences.",
      "The fog rolls in from the right and Fog is already half in it.", "idea")
def _():
    mist = "#9FBACB"
    a, _b = text_box("bebas-neue-400", "FJORD", 14, 14, 186, 54, "l")
    b, (l, t, r, bt) = text_box("bebas-neue-400", "& FOG", 14, 62, 110, 98, "l")
    bars = [fill(rrect(r + 8, y, w, 6.5, 3.25), mist) for y, w in ((t + 1, 62), (t + 14.5, 78 - (r - 96)), (t + 28, 50))]
    return [fill(a, WHITE), fill(b, mist)] + bars


@mark(70, "homeroom", "Homeroom", "Lockers, first loves and growing up, one bell at a time.",
      "The classroom chalkboard, with the name in chalk and the chalk left on the ledge.", "plate")
def _():
    green = "#7FDBA0"
    t, _b = text_box("pacifico-400", "Homeroom", 28, 22, 172, 66, merge=True)
    board = fill(cut(rrect(14, 12, 172, 65, 5), t), green, True)
    ledge = fill(rrect(12, 88, 176, 6, 3), "#E9D9B0")
    chalk = fill(rrect(40, 82.5, 20, 5, 2.5), WHITE); eraser = fill(rrect(132, 80, 28, 7.5, 2.5), WHITE)
    return [board, ledge, chalk, eraser]
