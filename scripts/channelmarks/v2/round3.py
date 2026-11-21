"""Round three of the channel-mark redraw: the rest of the General band."""
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

def mirror(d_pts):
    return [(W - x, y) for x, y in d_pts]

@mark(102, "laugh-track", "Laugh Track", "Sitcoms all day, with the laughs built in.",
      "A tape cassette with the name on its label.", "plate")
def _():
    t, _b = text_box("lilita-one-400", "LAUGH TRACK", 28, 24, 172, 48)
    reels = " ".join([rrect(50, 58, 100, 28, 14), circle(68, 72, 10), circle(68, 72, 3.4), circle(132, 72, 10), circle(132, 72, 3.4)])
    return [fill(cut(rrect(14, 14, 172, 84, 9), t, reels), "#6FE3C4", True)]

@mark(114, "shag-carpet", "Shag Carpet", "The seventies: wide collars, wood panelling, bold choices.",
      "A rug with a fringe at each end.", "plate")
def _():
    gold, avo = "#F4C552", "#BFD95C"
    a, _b = text_box("titan-one-400", "SHAG", 46, 26, 154, 54)
    b, _b = text_box("titan-one-400", "CARPET", 40, 58, 160, 86)
    fringe = [stroke(f"M{x0} {y} H{x1}", avo, 3.2) for y in range(24, 92, 8) for x0, x1 in ((13, 27), (173, 187))]
    return [fill(cut(rrect(28, 18, 144, 76, 4), a, b), gold, True)] + fringe

@mark(118, "neon-decade", "Neon Decade", "Big hair, bigger synths. The eighties, loud and proud.",
      "NEON is a bent glass tube, with the shapes of the decade around it.", "idea")
def _():
    lime, violet, pink = "#C8F560", "#B48CFF", "#FF7FC8"
    a, _b = text_box("bungee-400", "NEON", 40, 14, 160, 50)
    b, _b = text_box("bungee-400", "DECADE", 22, 58, 178, 96)
    return [stroke(a, lime, 2.8), fill(b, violet), fill(poly([(14, 46), (30, 46), (22, 30)]), pink), stroke("M164 22 q5 -8 10 0 t10 0", pink, 3.6), fill(circle(178, 44, 4.5), lime)]

@mark(122, "dial-up", "Dial-Up", "The nineties, buffering gently. Please don't pick up the phone.",
      "A nineties window, still connecting.", "plate")
def _():
    teal = "#5FE0C8"
    bar = cut(rrect(14, 14, 172, 18, 3), rrect(166, 19, 8, 8, 1), rrect(152, 19, 8, 8, 1))
    t, _b = text_box("righteous-400", "DIAL-UP", 28, 40, 172, 68)
    blocks = [fill(rrect(27 + i * 16.4, 76, 12.6, 12, 1.5), teal) if i < 6 else stroke(rrect(28 + i * 16.4, 77, 10.6, 10, 1.5), teal, 2) for i in range(9)]
    return [stroke(rrect(15.5, 15.5, 169, 81, 3), teal, 3), fill(bar, teal, True), fill(t, WHITE)] + blocks

@mark(130, "binge-row", "Binge Row", "The twenty-tens, when everything became one more episode.",
      "ROW is a row of thumbnails, with the next episode waiting.", "idea")
def _():
    violet = "#B695FF"
    a, _b = text_box("baloo-2-800", "binge", 20, 8, 180, 60)
    out = [fill(a, violet)]
    for i, ch in enumerate("ROW"):
        x = 16 + i * 46
        g, _b = text_box("baloo-2-800", ch, x + 9, 72, x + 31, 92)
        out.append(fill(cut(rrect(x, 66, 40, 32, 6), g), WHITE, True))
    out.append(fill(cut("M160 66 H186 V98 H160 Q154 98 154 92 V72 Q154 66 160 66 Z", poly([(165, 74), (179, 82), (165, 90)])), violet, True))
    return out

@mark(134, "fresh-cut", "Fresh Cut", "This decade so far, still warm from the oven.",
      "Cut along the dotted line.", "idea")
def _():
    green = "#72E59A"
    a, _b = text_box("titan-one-400", "FRESH", 14, 10, 186, 46)
    b, _b = text_box("titan-one-400", "CUT", 14, 72, 186, 100)
    line = '<path d="M54 59 H186" fill="none" stroke="#FFFFFF" stroke-width="3.2" stroke-linecap="round" stroke-dasharray="7 7"/>'
    scissors = [stroke(circle(21, 52, 5.6), WHITE, 3.2), stroke(circle(21, 66, 5.6), WHITE, 3.2), stroke("M26 55 L47 62 M26 63 L47 56", WHITE, 3.4)]
    return [fill(a, green), fill(b, WHITE), line] + scissors

@mark(138, "glass-house", "Glass House", "Real people, real drama, a camera in every corner.",
      "A house you can see straight into, and it is recording.", "plate")
def _():
    ice = "#A9DCFF"
    a, _b = text_box("righteous-400", "GLASS", 48, 44, 152, 66)
    b, _b = text_box("righteous-400", "HOUSE", 48, 70, 152, 92)
    return [stroke("M24 98 V46 L100 14 L176 46 V98 Z", ice, 5), stroke("M36 62 L46 52 M36 74 L52 58", ice, 3), fill(a, ice), fill(b, WHITE), fill(circle(170, 22, 6.5), "#FF6B6B")]

@mark(142, "game-night", "Game Night", "Buzzers, big prizes and a host who loves a pun.",
      "A prize wheel.", "symbol")
def _():
    yel, cyan = "#FFDD4A", "#5FD8F5"; cx, cy, r = 44, 60, 31; out = []
    for i in range(8):
        a0, a1 = math.radians(i * 45 - 90), math.radians(i * 45 - 45)
        arc = [(cx + r * math.cos(a0 + (a1 - a0) * j / 6), cy + r * math.sin(a0 + (a1 - a0) * j / 6)) for j in range(7)]
        out.append(fill(poly([(cx, cy)] + arc), yel if i % 2 == 0 else cyan))
    a, _b = text_box("luckiest-guy-400", "GAME", 88, 20, 188, 54, "l")
    b, _b = text_box("luckiest-guy-400", "NIGHT", 88, 60, 188, 94, "l")
    return out + [fill(circle(cx, cy, 6), WHITE), fill(poly([(36, 20), (52, 20), (44, 35)]), WHITE), fill(a, yel), fill(b, cyan)]

@mark(150, "matinee-tears", "Matinee Tears", "Long-running soaps with secrets, slaps and plot twists.",
      "One large tear.", "symbol")
def _():
    rose = "#FF8DB8"
    drop = "M40 12 C56 38 64 52 64 68 A24 24 0 0 1 16 68 C16 52 24 38 40 12 Z"
    a, _b = text_box("yeseva-one-400", "Matinee", 78, 22, 188, 52, "l")
    b, _b = text_box("yeseva-one-400", "Tears", 78, 58, 188, 92, "l")
    return [fill(cut(drop, ellipse(29, 70, 4.4, 8, 18)), rose, True), fill(a, WHITE), fill(b, rose)]

@mark(158, "spoof-central", "Spoof Central", "Parodies of everything, played with a perfectly straight face.",
      "A joke-shop disguise: glasses, nose and moustache.", "symbol")
def _():
    pink = "#FF7FC0"
    face = [stroke(circle(27, 44, 11), WHITE, 4), stroke(circle(55, 44, 11), WHITE, 4), stroke("M38 43 H44", WHITE, 4),
            stroke("M15 27 L37 24 M45 24 L67 27", WHITE, 5.5), fill(ellipse(41, 58, 6.5, 10.5), pink),
            fill(ellipse(29, 76, 14, 6.5, -14), WHITE), fill(ellipse(53, 76, 14, 6.5, 14), WHITE)]
    a, _b = text_box("bungee-400", "SPOOF", 82, 24, 188, 52, "l")
    b, _b = text_box("bungee-400", "CENTRAL", 82, 60, 188, 86, "l")
    return face + [fill(a, WHITE), fill(b, pink)]

@mark(162, "date-night", "Date Night", "Romance and rom-coms. Tissues and popcorn recommended.",
      "A heart with the name in it.", "plate")
def _():
    heart = "M100 100 C36 62 18 40 34 20 C48 4 82 8 100 30 C118 8 152 4 166 20 C182 40 164 62 100 100 Z"
    a, _b = text_box("abril-fatface-400", "Date", 44, 20, 156, 50)
    b, _b = text_box("abril-fatface-400", "Night", 52, 53, 148, 78)
    return [fill(cut(heart, a, b), "#FF7A9E", True)]

@mark(166, "open-road", "Open Road", "Road trips, travel diaries and a very long highway.",
      "A highway shield, with the centre line running past it.", "plate")
def _():
    shield = "M46 18 Q74 28 100 12 Q126 28 154 18 Q172 56 144 84 Q120 96 100 101 Q80 96 56 84 Q28 56 46 18 Z"
    a, _b = text_box("BarlowCondensed-800", "OPEN", 66, 28, 134, 46)
    b, _b = text_box("BarlowCondensed-800", "ROAD", 58, 50, 142, 82)
    dash = lambda x: fill(rrect(x, 53, 16, 6, 2), "#FFD23F")
    return [fill(cut(shield, a, b), WHITE, True), dash(4), dash(24), dash(160), dash(180)]

@mark(174, "fixer-hour", "Fixer Hour", "Renovations, makeovers and 'how hard can it be?'",
      "A site barrier board with hazard stripes.", "plate")
def _():
    stripes = " ".join(poly([(x, y0), (x + 10, y0), (x + 10 - 16, y0 + 16), (x - 16, y0 + 16)]) for y0 in (14, 82) for x in range(36, 196, 20))
    a, _b = text_box("lilita-one-400", "FIXER", 36, 34, 164, 56)
    b, _b = text_box("lilita-one-400", "HOUR", 44, 58, 156, 80)
    board = "M14 14 H186 V98 H14 Z"
    return [fill(cut(board, stripes, a, b), "#FFD93D", True)]

@mark(178, "backstage-pass", "Backstage Pass", "Concerts, music films and the stories behind the songs.",
      "A pass hanging from its strap.", "plate")
def _():
    a, _b = text_box("righteous-400", "BACKSTAGE", 42, 48, 158, 64)
    b, _b = text_box("righteous-400", "PASS", 50, 67, 150, 93)
    return [fill("M92 10 H108 V38 H92 Z", WHITE), fill(cut(rrect(30, 30, 140, 69, 8), rrect(84, 35.5, 32, 5.4, 2.7), a, b), "#B98CFF", True)]

@mark(900, "always-december", "Always December", "Holiday cheer on every day of the year, snow guaranteed.",
      "A calendar that never turns the page, in Christmas red and green.", "plate")
def _():
    red, green = "#FF6B6B", "#6BDDA0"
    a, _b = text_box("bebas-neue-400", "ALWAYS", 62, 25, 138, 41, "c", "c", 0.12)
    b, _b = text_box("bebas-neue-400", "DECEMBER", 30, 55, 170, 91)
    head = "M26 18 H174 Q182 18 182 26 V46 H18 V26 Q18 18 26 18 Z"
    body = "M18 49 H182 V90 Q182 98 174 98 H26 Q18 98 18 90 Z"
    return [fill(cut(head, a), red, True), fill(cut(body, b), green, True), fill(rrect(54, 10, 7, 16, 3.5), WHITE), fill(rrect(139, 10, 7, 16, 3.5), WHITE)]

@mark(186, "curtain-call", "Curtain Call", "Stage and screen musicals, jazz hands mandatory.",
      "The curtains drawn back on the name.", "idea")
def _():
    red, gold = "#FF7070", "#F6C954"
    left = "M12 12 H42 Q32 32 37 52 Q29 76 35 100 H12 Z"; right = "M188 12 H158 Q168 32 163 52 Q171 76 165 100 H188 Z"
    swag = "M40 12 H160 V18 Q145 29 130 18 Q115 29 100 18 Q85 29 70 18 Q55 29 40 18 Z"
    a, _b = text_box("abril-fatface-400", "Curtain", 46, 34, 154, 62)
    b, _b = text_box("abril-fatface-400", "Call", 52, 66, 148, 98)
    return [fill(left, red), fill(right, red), fill(swag, red), fill(a, gold), fill(b, WHITE)]

@mark(199, "odds-and-ends", "Odds & Ends", "",
      "A cardboard box of things with nowhere else to go.", "plate")
def _():
    blue = "#74ACFF"
    t, _b = text_box("anton-400", "ODDS & ENDS", 40, 60, 160, 88)
    box = cut("M28 50 H172 V94 Q172 98 168 98 H32 Q28 98 28 94 Z", t)
    flaps = [fill(poly([(28, 50), (12, 38), (46, 38), (58, 50)]), blue), fill(poly([(172, 50), (188, 38), (154, 38), (142, 50)]), blue)]
    key = [stroke(circle(70, 22, 6), WHITE, 3.6), stroke("M75 26 L88 40 M83 35 L87 31 M87 39 L91 35", WHITE, 3.6)]
    die = [fill(cut(poly(rot([(100, 14), (122, 14), (122, 36), (100, 36)], -12, 111, 25)), circle(105.5, 21, 2.3), circle(111, 25, 2.3), circle(116.5, 29, 2.3)), blue, True)]
    button = [fill(cut(circle(142, 26, 10), circle(138.5, 22.5, 1.9), circle(145.5, 22.5, 1.9), circle(138.5, 29.5, 1.9), circle(145.5, 29.5, 1.9)), WHITE, True)]
    return [fill(box, blue, True)] + flaps + key + die + button
