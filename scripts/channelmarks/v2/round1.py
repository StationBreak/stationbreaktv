"""Round one of the channel-mark redraw: twenty marks, each built from its name, tagline and theme."""
import math
from marklib import *
from marklib import _n

MARKS = {}
def mark(number, key, name, says, idea, kind):
    def wrap(fn):
        MARKS[key] = dict(number=number, key=key, name=name, says=says, idea=idea, kind=kind, shapes=fn())
        return fn
    return wrap

@mark(208, "cold-case-cable", "Cold Case Cable", "Detectives, mysteries and one unsolved case after another.",
      "A case folder pulled from the drawer, the name typed on it.", "plate")
def _():
    ice = "#8FE0E8"
    folder = "M14 24 Q14 18 20 18 H70 L80 30 H180 Q186 30 186 36 V92 Q186 98 180 98 H20 Q14 98 14 92 Z"
    t, _b = text_box("special-elite-400", "COLD CASE", 26, 46, 174, 86)
    return [fill(cut(folder, t), ice, True)]

@mark(216, "code-blue", "Code Blue", "Hospital corridors, big emergencies and tired surgeons.",
      "A hospital cross cut from a blue tile, beside the call.", "symbol")
def _():
    blue = "#62AEFF"
    cross = "M38 34 H50 V50 H66 V62 H50 V78 H38 V62 H22 V50 H38 Z"
    a, _b = text_box("bebas-neue-400", "CODE", 86, 22, 186, 54, "l")
    b, _b = text_box("bebas-neue-400", "BLUE", 86, 58, 186, 90, "l")
    return [fill(cut(rrect(14, 26, 60, 60, 12), cross), blue, True), fill(a, WHITE), fill(b, blue)]

@mark(170, "simmer", "Simmer", "Cooking, baking, and the occasional kitchen disaster.",
      "Steam curling off the word.", "idea")
def _():
    t, _b = text_box("shrikhand-400", "simmer", 14, 46, 186, 98)
    steam = [stroke(f"M{x} 40 q-8 -7 0 -13 q8 -6 0 -13", WHITE, 5) for x in (72, 100, 128)]
    return steam + [fill(t, "#FF6F61")]

@mark(154, "sideline", "Sideline", "Games, highlights and underdog stories from the touchline.",
      "The touchline, with a corner flag at the end of it.", "idea")
def _():
    green = "#74E391"
    t, _b = text_box("BarlowCondensed-800", "SIDELINE", 14, 24, 156, 80, "l", "b", 0.01, 10)
    return [fill(t, green), stroke("M14 92 H186", WHITE, 6), stroke("M170 92 V26", WHITE, 4), fill(poly([(170, 24), (188, 33), (170, 42)]), green)]

@mark(512, "one-season-only", "One Season Only", "Miniseries and limited runs, told in order and gone too soon.",
      "The last number on a film countdown.", "symbol")
def _():
    teal = "#5FE0D0"
    one, _b = text_box("bebas-neue-400", "1", 36, 34, 60, 78)
    a, _b = text_box("bebas-neue-400", "ONE SEASON", 94, 30, 188, 56, "l")
    b, _b = text_box("bebas-neue-400", "ONLY", 94, 62, 188, 88, "l", "c", 0.06)
    return [stroke(circle(48, 56, 34), teal, 5), stroke("M8 56 H88 M48 16 V96", teal, 2.2, "butt"), stroke(circle(48, 56, 25), teal, 2.2),
            fill(one, WHITE), fill(a, WHITE), fill(b, teal)]

@mark(332, "cult-following", "Cult Following", "Midnight-screening favourites, quoted word for word.",
      "The film everyone quotes, in quotation marks.", "idea")
def _():
    mint = "#7CF0C4"
    l, _b = text_box("abril-fatface-400", "“", 12, 14, 50, 54, "l", "t")
    r, _b = text_box("abril-fatface-400", "”", 150, 14, 188, 54, "r", "t")
    c, _b = text_box("teko-700", "CULT", 54, 14, 146, 68)
    f, _b = text_box("oswald-700", "FOLLOWING", 22, 78, 178, 98, "c", "c", 0.14)
    return [fill(l, mint), fill(r, mint), fill(c, WHITE), fill(f, mint)]

def laurel(mirror):
    """One laurel branch: leaves fanned up the left side of a circle, or its mirror image."""
    d = ""
    for i in range(7):
        a = 112 + i * 23
        px, py = 56 + 40 * math.cos(math.radians(a)), 56 - 40 * math.sin(math.radians(a))
        leaf = [(0, -9.5), (3.8, -3), (3.3, 4), (0, 9.5), (-3.3, 4), (-3.8, -3)]
        pts = rot([(px + x, py + y) for x, y in leaf], 180 - a - 32, px, py)
        if mirror:
            pts = [(W - x, y) for x, y in pts]
        d += poly(pts) + " "
    return d

@mark(204, "prestige", "Prestige", "Slow-burn, award-hungry drama. Bring your full attention.",
      "Festival laurels: it wants the awards and says so.", "symbol")
def _():
    em = "#59DBA6"
    t, _b = text_box("cinzel-700", "PRESTIGE", 38, 40, 162, 72, "c", "c", 0.03)
    return [fill(laurel(False), em), fill(laurel(True), em), fill(t, WHITE)]

@mark(304, "starport", "Starport", "Sci-fi: starships, aliens and science that's mostly pretend.",
      "The O is a ringed planet.", "idea")
def _():
    blue = "#76B6FF"; face = "orbitron-900"
    star, sb = text_box(face, "STAR", 30, 14, 170, 50, "c", "c", 0.04)
    size = (sb[3] - sb[1]) / cap_height(face, 1); base = 94
    _p, wp = text_at(face, "P", size, 0, base, 0.04); _r, wr = text_at(face, "RT", size, 0, base)
    r = cap_height(face, size) / 2; gap = size * .1
    x = (W - (wp + gap + 2 * r + gap + wr)) / 2
    pp, _w = text_at(face, "P", size, x, base); px = x + wp + gap + r; py = base - r
    rt, _w = text_at(face, "RT", size, px + r + gap, base)
    ring = poly(rot([(px + r * 1.7 * math.cos(math.radians(t)), py + r * .46 * math.sin(math.radians(t))) for t in range(0, 360, 12)], -22, px, py))
    return [fill(star, WHITE), fill(pp, blue), fill(rt, blue), fill(circle(px, py, r * .8), WHITE), stroke(ring, blue, 3.4),
            fill(circle(22, 30, 2.6), WHITE), fill(circle(180, 78, 2.2), WHITE), fill(circle(26, 88, 1.8), WHITE)]

@mark(308, "dragons-and-doorways", "Dragons & Doorways", "Swords, spells and a quest that begins in a tavern.",
      "An arched doorway with the ampersand standing in it.", "plate")
def _():
    gold = "#F4C552"
    door = "M14 98 V40 A24 24 0 0 1 62 40 V98 Z"
    amp, _b = text_box("cinzel-700", "&", 22, 36, 54, 90)
    a, _b = text_box("cinzel-700", "DRAGONS", 72, 26, 188, 52, "l")
    b, _b = text_box("cinzel-700", "DOORWAYS", 72, 60, 188, 86, "l")
    return [fill(cut(door, amp), gold, True), fill(a, gold), fill(b, WHITE)]

@mark(424, "continental-drift", "Continental Drift", "World cinema with subtitles; read along and travel.",
      "The letters of DRIFT are drifting apart.", "idea")
def _():
    pink = "#FF93CC"; face = "playfair-display-900"; size = 60
    top, _b = text_box(face, "CONTINENTAL", 22, 14, 178, 30, "c", "c", 0.2)
    out = [fill(top, WHITE)]
    for _fit in range(2):
        x = 0; glyphs = []
        for ch, gap, r, dy in zip("DRIFT", (0, .03, .07, .12, .18), (0, -4, 3, -5, 6), (0, 3, -2, 4, -1)):
            x += gap * size
            g, w = text_at(face, ch, size, 0, 0); glyphs.append((ch, x, r, dy)); x += w
        if x > 172: size *= 172 / x
    off = (W - x) / 2
    for ch, gx, r, dy in glyphs:
        g, _w = text_at(face, ch, size, off + gx, 92 + dy, rot=r)
        out.append(fill(g, pink))
    return out

@mark(260, "life-stories", "Life Stories", "Biopics and memoirs about people worth a feature film.",
      "Signed like an autograph.", "idea")
def _():
    t, box = text_box("yellowtail-400", "Life Stories", 14, 16, 186, 82)
    return [fill(t, "#FF86BC"), stroke("M26 94 C70 84 118 102 178 86", WHITE, 3.6)]

@mark(126, "flip-phone", "Flip Phone", "The two-thousands, snapping shut with a satisfying click.",
      "A clamshell phone, half open.", "symbol")
def _():
    cyan, pink = "#62D8F6", "#FF93CF"
    keys = " ".join(circle(26 + c * 10, 68 + r * 9, 2.5) for r in range(3) for c in range(3))
    bottom = cut(rrect(20, 58, 32, 40, 7), keys)
    lid = rot([(20, 16), (52, 16), (52, 56), (20, 56)], 20, 36, 57)
    screen = rot([(26, 22), (46, 22), (46, 44), (26, 44)], 20, 36, 57)
    a, _b = text_box("fredoka-700", "flip", 78, 16, 186, 54, "l")
    b, _b = text_box("fredoka-700", "phone", 78, 58, 186, 98, "l")
    return [fill(cut(poly(lid), poly(screen)), pink, True), fill(bottom, cyan, True), fill(circle(36, 57, 4), WHITE), fill(a, cyan), fill(b, pink)]

@mark(106, "punchline", "Punchline", "Stand-up, sketches and one-liners without the filler.",
      "A microphone in the spotlight.", "symbol")
def _():
    yel = "#FFDB4D"
    mic = rrect(40, 28, 16, 28, 8) + " M34 48 A14 14 0 0 0 62 48 L58.5 48 A10.5 10.5 0 0 1 37.5 48 Z " + "M46.3 61 H49.7 V78 H46.3 Z " + rrect(37, 78, 22, 5, 2)
    a, _b = text_box("bungee-400", "PUNCH", 92, 22, 188, 52, "l")
    b, _b = text_box("bungee-400", "LINE", 92, 58, 188, 88, "l")
    return [fill(cut(circle(48, 56, 37), mic), yel, True), fill(a, yel), fill(b, WHITE)]

@mark(320, "the-cheap-seats", "The Cheap Seats", "Bad-movie night, with riffing on the soundtrack.",
      "A ticket stub, and it only cost a nickel.", "plate")
def _():
    ticket = "M20 22 H180 Q186 22 186 28 V46 A10 10 0 0 0 186 66 V84 Q186 90 180 90 H20 Q14 90 14 84 V66 A10 10 0 0 0 14 46 V28 Q14 22 20 22 Z"
    perf = " ".join(circle(64, y, 1.9) for y in range(29, 86, 7))
    price, _b = text_box("anton-400", "5¢", 27, 38, 57, 74)
    a, _b = text_box("anton-400", "CHEAP", 74, 28, 172, 54)
    b, _b = text_box("anton-400", "SEATS", 74, 58, 172, 84)
    return [fill(cut(ticket, perf, price, a, b), "#63DBC8", True)]

@mark(324, "drive-in-double", "Drive-In Double", "Grindhouse, B-pictures and trailers in the intermission.",
      "The roadside arrow sign, and two reels for the double bill.", "plate")
def _():
    yel = "#FFDB4D"
    arrow = "M14 22 H146 V10 L186 42 L146 74 V62 H14 Z"
    a, _b = text_box("limelight-400", "DRIVE-IN", 22, 30, 150, 54)
    b, _b = text_box("limelight-400", "DOUBLE", 14, 78, 128, 99, "l")
    reel = lambda cx: [stroke(circle(cx, 88, 9), WHITE, 3.4), fill(circle(cx, 88, 2.4), WHITE)]
    return [fill(cut(arrow, a), yel, True), fill(b, WHITE)] + reel(152) + reel(174)

# ---- redrawn after review: the first versions of these five were not working ----

@mark(524, "anthology-hour", "Anthology Hour", "A new story every week, a new twist every time.",
      "A stack of three books: a different story each time.", "symbol")
def _():
    blue = "#6FA8FF"
    band = lambda x, y: rrect(x, y, 5, 16, 1) + " " + rrect(x + 9, y, 2.4, 16, 1)
    books = [fill(cut(rrect(12, 30, 46, 16, 3), band(18, 30)), WHITE, True),
             fill(cut(rrect(20, 48, 42, 16, 3), band(46, 48)), "#B9D4FF", True),
             fill(cut(rrect(14, 66, 48, 16, 3), band(22, 66)), blue, True)]
    a, _b = text_box("BarlowCondensed-800", "ANTHOLOGY", 72, 26, 188, 62, "l")
    b, _b = text_box("BarlowCondensed-800", "HOUR", 72, 66, 188, 88, "l", "c", 0.12)
    return books + [fill(a, WHITE), fill(b, blue)]

@mark(420, "whodunit-isles", "Whodunit Isles", "Village murders, cosy inspectors and vicars with secrets.",
      "The O in WHODUNIT is the inspector's magnifying glass.", "idea")
def _():
    yel = "#FFD75E"; face = "corben-700"; size = 40; base = 50
    for _fit in range(2):
        _a, wa = text_at(face, "WH", size, 0, base); _b, wb = text_at(face, "DUNIT", size, 0, base)
        r = cap_height(face, size) / 2; gap = size * .09
        gap2 = size * .27
        total = wa + gap + 2 * r + gap2 + wb
        if total > 174: size *= 174 / total
    x = (W - total) / 2
    a, _w = text_at(face, "WH", size, x, base); px = x + wa + gap + r; py = base - r
    b, _w = text_at(face, "DUNIT", size, px + r + gap2, base)
    k = math.sqrt(.5)
    handle = f"M{_n(px + r * k)} {_n(py + r * k)} L{_n(px + (r + 8) * k)} {_n(py + (r + 8) * k)}"
    i, _b = text_box(face, "ISLES", 14, 64, 104, 96, "l", "c", 0.08)
    wave = lambda y: stroke(f"M118 {y} q7 -8 14 0 t14 0 t14 0 t14 0 t14 0", yel, 3.6)
    return [fill(a, yel), fill(b, yel), stroke(circle(px, py, r - 1.6), WHITE, 4.4), stroke(handle, WHITE, 5.6), fill(i, WHITE), wave(76), wave(90)]

@mark(110, "rabbit-ears", "Rabbit Ears", "Fuzzy pictures from the fifties and sixties; adjust the antenna.",
      "The two b's are the rabbit: white, with a nose underneath.", "idea")
def _():
    import rabbit
    return rabbit.STACKED()

@mark(146, "couch-talk", "Couch Talk", "Hosts, guests and chat from a comfortable seat.",
      "Both words sit on the couch: COUCH on the back, talk on the seat.", "plate")
def _():
    couch = "M14 90 V58 Q14 46 26 46 H30 V34 Q30 14 50 14 H150 Q170 14 170 34 V46 H174 Q186 46 186 58 V90 H166 V99 H154 V90 H46 V99 H34 V90 Z"
    a, _b = text_box("baloo-2-800", "COUCH", 50, 22, 150, 42, "c", "c", 0.12)
    b, _b = text_box("baloo-2-800", "talk", 48, 46, 152, 86)
    seam = rrect(44, 43.2, 112, 1.8, .9)
    return [fill(cut(couch, a, b), "#FF8E80", True)]
