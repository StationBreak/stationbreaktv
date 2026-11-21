"""Round five of the channel-mark redraw: the rest of the Genre band."""
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

@mark(312, "rubber-suit-theatre", "Rubber Suit Theatre", "Giant monsters flattening miniature cities, zip lines visible.",
      "The zip down the back of the monster suit.", "idea")
def _():
    red = "#FF6457"
    a, _b = text_box("titan-one-400", "RUBBER SUIT", 14, 12, 186, 42)
    b, _b = text_box("titan-one-400", "THEATRE", 30, 70, 170, 99, "c", "c", 0.06)
    teeth = [fill(rrect(16 + i * 9, 50 if i % 2 == 0 else 56, 6, 6, 1.4), WHITE) for i in range(15)]
    tab = [fill(rrect(153, 47, 14, 18, 4), red), stroke("M167 56 H178", red, 4), stroke(circle(183, 56, 4.5), red, 3.4)]
    return [fill(a, red), fill(b, WHITE)] + teeth + tab

@mark(316, "midnight-projector", "The Midnight Projector", "Horror and classic monsters, best watched with the lights down.",
      "A film projector running under the moon.", "symbol")
def _():
    violet, moon = "#B49BFF", "#FFE9A6"
    reel = lambda cx: cut(circle(cx, 38, 12), circle(cx, 38, 3), circle(cx - 6, 34, 2.4), circle(cx + 6, 34, 2.4), circle(cx, 44.5, 2.4))
    proj = [fill(reel(28), violet, True), fill(reel(54), violet, True), fill(rrect(18, 52, 44, 30, 5), violet), fill(poly([(62, 60), (74, 55), (74, 79), (62, 74)]), violet),
            stroke("M28 82 L22 97 M52 82 L58 97", violet, 4)]
    a, _b = text_box("bebas-neue-400", "MIDNIGHT", 84, 30, 188, 60, "l")
    b, _b = text_box("bebas-neue-400", "PROJECTOR", 84, 64, 188, 92, "l")
    return proj + [fill(crescent(176, 19, 9), moon, True), fill(a, violet), fill(b, WHITE)]

@mark(328, "cape-and-cowl", "Cape & Cowl", "Superheroes, villains with schemes, and capes in the wind.",
      "A cape, with the name across it.", "plate")
def _():
    cape = "M72 14 H128 L186 84 Q168 100 150 86 Q133 100 116 86 Q100 100 84 86 Q67 100 50 86 Q32 100 14 84 Z"
    a, _b = text_box("anton-400", "CAPE &", 70, 24, 130, 46)
    b, _b = text_box("anton-400", "COWL", 50, 50, 150, 80)
    return [fill(cut(cape, a, b), "#FF5F6B", True)]

@mark(336, "shuffle-and-groan", "Shuffle & Groan", "The undead, the unwashed and the unlucky survivors.",
      "A hand coming up out of the ground.", "symbol")
def _():
    slime = "#A6F05A"
    finger = lambda x, top: rrect(x, top, 8.5, 62 - top, 4.2)
    hand = [fill(d, slime) for d in (finger(21, 28), finger(31.5, 18), finger(42, 22), finger(52.5, 32), rrect(21, 52, 40, 34, 8))]
    thumb = fill(poly(rot([(13, 50), (21.5, 50), (21.5, 76), (13, 76)], -24, 21.5, 76)), slime)
    a, _b = text_box("creepster-400", "SHUFFLE", 84, 20, 188, 54, "l")
    b, _b = text_box("creepster-400", "& GROAN", 84, 60, 188, 90, "l")
    group = '<g transform="translate(6 0)">' + "".join(hand + [thumb, stroke("M8 88 Q19 80 28 88 T48 88 T68 88", WHITE, 4.5)]) + "</g>"
    return [group, fill(a, slime), fill(b, WHITE)]

@mark(910, "jack-o-lantern-lounge", "Jack-o'-Lantern Lounge", "Halloween, all year round. Candy corn not included.",
      "The full name at last, and its O is the jack-o'-lantern.", "idea")
def _():
    glow, violet = "#FFD84D", "#B78CFF"; face = "anton-400"; size = 44; base = 50
    for _fit in range(2):
        _a, wa = text_at(face, "JACK-", size, 0, base); _b2, wb = text_at(face, "'-LANTERN", size, 0, base)
        ch = cap_height(face, size); r = ch * .62; gap = size * .05; total = wa + gap + 2 * r + gap + wb
        if total > 176: size *= 176 / total
    x = (W - total) / 2; a, _w = text_at(face, "JACK-", size, x, base); cx = x + wa + gap + r; cy = base - ch / 2
    b, _w = text_at(face, "'-LANTERN", size, cx + r + gap, base)
    e = r * .3
    carve = " ".join([poly([(cx - r * .55, cy - r * .08), (cx - r * .12, cy - r * .08), (cx - r * .33, cy - r * .5)]),
                      poly([(cx + r * .12, cy - r * .08), (cx + r * .55, cy - r * .08), (cx + r * .33, cy - r * .5)]),
                      poly([(cx - r * .6, cy + r * .22), (cx - r * .2, cy + r * .42), (cx, cy + r * .24), (cx + r * .2, cy + r * .42), (cx + r * .6, cy + r * .22), (cx + r * .3, cy + r * .68), (cx - r * .3, cy + r * .68)])])
    pumpkin = ellipse(cx, cy + r * .06, r * 1.08, r * .96)
    l, _b = text_box(face, "LOUNGE", 20, 60, 180, 99, "c", "c", 0.2)
    return [fill(a, WHITE), fill(b, WHITE), stroke(f"M{_n(cx)} {_n(cy - r * .86)} q1 -6 6 -8", "#8FE38A", 3.4), fill(cut(pumpkin, carve), glow, True), fill(l, violet)]

@mark(344, "campfire-tales", "Campfire Tales", "Slashers and summer camps. Don't go into the woods.",
      "A campfire.", "symbol")
def _():
    blue, fire = "#8EC5FF", "#FFD84D"
    flame = "M42 14 C62 36 66 50 60 62 C56 72 48 76 42 76 C36 76 28 72 24 62 C18 50 28 40 34 30 C36 38 40 40 42 14 Z"
    inner = "M43 46 C52 56 52 68 43 70 C34 68 34 58 43 46 Z"
    a, _b = text_box("oswald-700", "CAMPFIRE", 82, 24, 188, 52, "l")
    b, _b = text_box("oswald-700", "TALES", 82, 58, 188, 92, "l")
    return [fill(cut(flame, inner), fire, True), stroke("M18 78 L66 92 M66 78 L18 92", WHITE, 7), fill(a, WHITE), fill(b, blue)]

@mark(348, "paradox", "Paradox", "Time travel: yesterday, tomorrow and a very tangled timeline.",
      "The O is a clock running backwards.", "idea")
def _():
    violet = "#B79CFF"; face = "righteous-400"; size = 52; base = 74
    for _fit in range(2):
        _a, wa = text_at(face, "PARAD", size, 0, base); _b2, wb = text_at(face, "X", size, 0, base)
        r = cap_height(face, size) * .66; gap = size * .09; total = wa + gap + 2 * r + gap + wb
        if total > 176: size *= 176 / total
    x = (W - total) / 2; a, _w = text_at(face, "PARAD", size, x, base); cx = x + wa + gap + r; cy = base - cap_height(face, size) / 2
    b, _w = text_at(face, "X", size, cx + r + gap, base)
    ring = arc(cx, cy, r - 2.5, r - 2.5, -50, 255)
    tipx, tipy = cx + (r - 2.5) * math.cos(math.radians(-50)), cy + (r - 2.5) * math.sin(math.radians(-50))
    head = poly(rot([(tipx - 8, tipy + 2), (tipx + 8, tipy + 2), (tipx, tipy - 10)], 128, tipx, tipy))
    hands = stroke(f"M{_n(cx)} {_n(cy - r * .5)} V{_n(cy)} L{_n(cx - r * .4)} {_n(cy + r * .24)}", WHITE, 3.6)
    return [fill(a, violet), fill(b, violet), stroke(ring, WHITE, 5), fill(head, WHITE), hands]

@mark(352, "neon-rain", "Neon Rain", "Cyberpunk and dystopias, glowing in a downpour.",
      "Rain coming down at a slant either side of the name.", "idea")
def _():
    pink, cyan = "#FF5FB0", "#52E0F5"
    a, _b = text_box("BarlowCondensed-800", "NEON", 50, 12, 150, 54, "c", "c", 0.02, 12)
    b, _b = text_box("BarlowCondensed-800", "RAIN", 50, 58, 150, 100, "c", "c", 0.02, 12)
    drops = []
    for i, (x, y, ln) in enumerate(((22, 16, 16), (36, 40, 20), (16, 54, 14), (30, 76, 18), (44, 14, 12), (164, 18, 18), (178, 44, 14), (158, 56, 20), (172, 78, 16), (184, 14, 12))):
        drops.append(stroke(f"M{x} {y} l{_n(-ln * .21)} {ln}", cyan if i % 2 == 0 else pink, 3.4))
    return [fill(a, pink), fill(b, cyan)] + drops

@mark(356, "deep-space-drift", "Deep Space Drift", "Space operas and lonely spaceships, far from anywhere.",
      "One small ship, a long way from anything.", "symbol")
def _():
    blue = "#8FA4FF"
    a, _b = text_box("orbitron-900", "DEEP SPACE", 14, 18, 186, 42)
    b, _b = text_box("orbitron-900", "DRIFT", 14, 58, 122, 92, "l")
    ship = f'<g transform="rotate(-24 164 62)">{fill("M164 44 Q174 56 172 74 H156 Q154 56 164 44 Z", WHITE)}{fill(poly([(156, 68), (148, 80), (156, 78)]), blue)}{fill(poly([(172, 68), (180, 80), (172, 78)]), blue)}{fill(circle(164, 60, 3.4), blue)}</g>'
    trail = '<path d="M130 96 Q142 92 150 84" fill="none" stroke="#FFFFFF" stroke-width="3" stroke-linecap="round" stroke-dasharray="1 7"/>'
    return [fill(a, blue), fill(b, WHITE), ship, trail, fill(star(140, 60, 4, 4, .38), WHITE), fill(circle(186, 92, 1.8), WHITE)]

@mark(360, "creature-feature", "Creature Feature", "Something big in the swamp, the deep or the attic.",
      "An eye, watching.", "symbol")
def _():
    green = "#7BE08A"
    eye = cut("M12 56 Q43 22 74 56 Q43 90 12 56 Z", ellipse(43, 56, 5.5, 17))
    a, _b = text_box("anton-400", "CREATURE", 84, 22, 188, 54, "l")
    b, _b = text_box("anton-400", "FEATURE", 84, 60, 188, 92, "l")
    return [fill(eye, green, True), fill(a, green), fill(b, WHITE)]

@mark(364, "end-times", "End Times", "Post-apocalyptic wastelands and the last diner on Earth.",
      "An hourglass with the sand run out.", "symbol")
def _():
    dusk = "#C39BFF"
    glass = [fill(rrect(16, 14, 52, 7, 3), WHITE), fill(rrect(16, 91, 52, 7, 3), WHITE), stroke("M22 23 H62 L44 56 H40 Z", dusk, 4), fill("M41 56 H43 L62 89 H22 Z", dusk)]
    a, _b = text_box("teko-700", "END", 84, 16, 188, 54, "l")
    b, _b = text_box("teko-700", "TIMES", 84, 58, 188, 96, "l")
    return glass + [fill(a, dusk), fill(b, WHITE)]

@mark(0, "haunted-house", "Haunted House", "Ghosts, spirits and noises in the night.",
      "A crooked house with one window lit.", "symbol")
def _():
    violet, lit = "#B79CFF", "#FFE08A"
    house = "M16 98 V52 L38 30 L60 52 V40 L68 22 L76 40 V98 Z"
    holes = " ".join([rrect(26, 60, 9, 12, 1.5), rrect(42, 60, 9, 12, 1.5), rrect(32, 80, 12, 18, 6), rrect(64, 46, 7, 10, 3.5)])
    a, _b = text_box("creepster-400", "HAUNTED", 86, 22, 188, 54, "l")
    b, _b = text_box("creepster-400", "HOUSE", 86, 60, 188, 92, "l")
    return [fill(cut(house, holes), violet, True), fill(rrect(64, 46, 7, 10, 3.5), lit), fill(a, violet), fill(b, WHITE)]

@mark(372, "twist-ending", "Twist Ending", "Mind-benders that make you rewatch the first act.",
      "A spiral that pulls you back in.", "symbol")
def _():
    blue = "#6FB4FF"
    pts = [(44 + (2 + t * 0.036) * math.cos(math.radians(t)), 56 + (2 + t * 0.036) * math.sin(math.radians(t))) for t in range(0, 841, 12)]
    spiral = "M" + " L".join(f"{_n(x)} {_n(y)}" for x, y in pts)
    a, _b = text_box("audiowide-400", "TWIST", 84, 22, 188, 52, "l")
    b, _b = text_box("audiowide-400", "ENDING", 84, 60, 188, 88, "l")
    return [stroke(spiral, blue, 5), fill(a, WHITE), fill(b, blue)]

@mark(376, "gallows-humor", "Gallows Humor", "Dark comedies for people who laugh at the wrong moments.",
      "A skull in a clown's nose.", "symbol")
def _():
    pink = "#FF6FB5"
    skull = "M42 14 C62 14 73 28 73 46 C73 57 68 64 61 68 V82 Q61 90 53 90 H31 Q23 90 23 82 V68 C16 64 11 57 11 46 C11 28 22 14 42 14 Z"
    face = " ".join([ellipse(30, 44, 7.5, 8.5), ellipse(54, 44, 7.5, 8.5)] + [rrect(x, 72, 3, 12, 1.4) for x in (32, 40.5, 49)])
    a, _b = text_box("oswald-700", "GALLOWS", 84, 24, 188, 52, "l")
    b, _b = text_box("oswald-700", "HUMOR", 84, 58, 188, 92, "l")
    return [fill(cut(skull, face), WHITE, True), fill(circle(42, 59, 7), pink), fill(a, pink), fill(b, WHITE)]
