"""Round two of the channel-mark redraw: the Kids band (Tall Tales Club was done in round one)."""
import math
from marklib import *
from marklib import _n

MARKS = {}
def mark(number, key, name, says, idea, kind):
    def wrap(fn):
        MARKS[key] = dict(number=number, key=key, name=name, says=says, idea=idea, kind=kind, shapes=fn())
        return fn
    return wrap

def ellipse(cx, cy, rx, ry, tilt=0):
    return poly(rot([(cx + rx * math.cos(math.radians(a)), cy + ry * math.sin(math.radians(a))) for a in range(0, 360, 12)], tilt, cx, cy))

def star(cx, cy, r, points=5, inner=.45, turn=-90):
    pts = []
    for i in range(points * 2):
        a = math.radians(turn + i * 180 / points); rr = r if i % 2 == 0 else r * inner
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    return poly(pts)

def crescent(cx, cy, r, bite=.62, dx=.42, dy=-.18):
    """A moon: a disc with a smaller disc bitten out of its upper right."""
    return circle(cx, cy, r) + " " + circle(cx + r * dx, cy + r * dy, r * bite + r * .18)

@mark(4, "little-farm", "Little Farm", "Gentle, slow and sunny, for the very youngest viewers.",
      "A red barn with the name painted on it, and the sun coming up behind.", "plate")
def _():
    barn = "M26 98 V56 L44 34 L76 14 H124 L156 34 L174 56 V98 Z"
    a, _b = text_box("sniglet-800", "LITTLE", 66, 24, 134, 40, "c", "c", 0.1)
    b, _b = text_box("sniglet-800", "farm", 40, 46, 160, 92)
    return [fill(circle(164, 30, 15), "#FFE066"), fill(cut(barn, a, b), "#FF6F6F", True)]

@mark(8, "sing-along-station", "Sing-Along Station", "Every show breaks into song, and so should you.",
      "A pair of notes, and the words to sing.", "symbol")
def _():
    yel, pink = "#FFD84D", "#FF8FC7"
    notes = [fill(ellipse(25, 84, 10, 7.5, -20), pink), fill(ellipse(53, 76, 10, 7.5, -20), pink),
             fill(poly([(30.5, 84), (34.5, 84), (34.5, 30), (30.5, 30)]), pink), fill(poly([(58.5, 76), (62.5, 76), (62.5, 22), (58.5, 22)]), pink),
             fill(poly([(30.5, 26), (62.5, 18), (62.5, 30), (30.5, 38)]), pink)]
    a, _b = text_box("fredoka-700", "sing-along", 74, 22, 188, 56, "l")
    b, _b = text_box("fredoka-700", "station", 74, 60, 188, 92, "l")
    return notes + [fill(a, yel), fill(b, WHITE)]

@mark(12, "once-upon", "Once Upon", "Fairy tales, folk tales and happily ever after.",
      "An open storybook, one word on each page.", "plate")
def _():
    book = "M14 32 Q58 18 100 36 Q142 18 186 32 V92 Q142 78 100 96 Q58 78 14 92 Z"
    a, _b = text_box("pacifico-400", "Once", 24, 40, 92, 80, merge=True)
    b, _b = text_box("pacifico-400", "Upon", 108, 40, 176, 80, merge=True)
    return [fill(cut(book, a, b), "#CBB0FF", True), fill(star(100, 17, 9, 4, .38), "#FFD866")]

@mark(16, "saturday-morning", "Saturday Morning", "Cartoons and shorts, cereal optional.",
      "A bowl of cereal.", "symbol")
def _():
    sky, sun = "#7FD4FF", "#FFD84D"
    bowl = "M12 56 H68 A28 28 0 0 1 12 56 Z"
    loop = lambda x, y: stroke(circle(x, y, 5), sun, 3.6)
    a, _b = text_box("lilita-one-400", "SATURDAY", 78, 26, 188, 50, "l")
    b, _b = text_box("lilita-one-400", "MORNING", 78, 56, 188, 88, "l")
    return [fill(bowl, sky), fill(rrect(26, 84, 28, 6, 3), sky), loop(26, 44), loop(41, 38), loop(55, 45), fill(a, WHITE), fill(b, sky)]

@mark(20, "backyard-fort", "Backyard Fort", "Big-kid adventure: maps, mysteries and no grown-ups allowed.",
      "A fort wall with a flag flying.", "plate")
def _():
    green = "#6FE38A"
    wall = "M20 98 V28 H44 V40 H66 V28 H90 V40 H110 V28 H134 V40 H156 V28 H180 V98 Z"
    a, _b = text_box("luckiest-guy-400", "BACKYARD", 32, 48, 168, 64)
    b, _b = text_box("luckiest-guy-400", "FORT", 44, 67, 156, 94)
    return [fill(cut(wall, a, b), green, True), stroke("M100 40 V12", WHITE, 3.6), fill(poly([(101.5, 11), (122, 17), (101.5, 23)]), WHITE)]

@mark(24, "critter-channel", "Critter Channel", "Dogs, dinosaurs, ducklings and everything in between.",
      "A paw print.", "symbol")
def _():
    pink = "#FF8FB3"
    paw = [fill(ellipse(40, 72, 19, 15), pink)] + [fill(ellipse(x, y, 6.8, 8.6, t), pink) for x, y, t in ((17, 52, -22), (31, 38, -8), (49, 38, 8), (63, 52, 22))]
    a, _b = text_box("boogaloo-400", "critter", 80, 18, 188, 60, "l")
    b, _b = text_box("boogaloo-400", "CHANNEL", 80, 66, 188, 90, "l", "c", 0.06)
    return paw + [fill(a, pink), fill(b, WHITE)]

@mark(28, "robot-factory", "Robot Factory", "Robots, rockets and things that go beep.",
      "A robot's head.", "symbol")
def _():
    cyan = "#5FDCF2"
    head = cut(rrect(16, 40, 52, 46, 9), circle(31, 57, 6), circle(53, 57, 6), rrect(27, 71, 30, 6, 3))
    a, _b = text_box("titan-one-400", "ROBOT", 80, 24, 188, 54, "l")
    b, _b = text_box("titan-one-400", "FACTORY", 80, 60, 188, 88, "l")
    return [fill(head, cyan, True), stroke("M42 40 V26", cyan, 4), fill(circle(42, 22, 5.5), WHITE), fill(rrect(9, 54, 7, 16, 2), cyan), fill(rrect(68, 54, 7, 16, 2), cyan),
            fill(a, cyan), fill(b, WHITE)]

@mark(32, "hey-science", "Hey, Science!", "Why is the sky blue? Let's find out.",
      "A bubbling flask.", "symbol")
def _():
    lime = "#A8F06B"
    flask = "M32 20 H50 V44 L68 86 Q71 94 62 94 H20 Q11 94 14 86 L32 44 Z"
    a, _b = text_box("fredoka-700", "Hey,", 80, 20, 188, 44, "l")
    b, _b = text_box("fredoka-700", "Science!", 80, 50, 188, 90, "l")
    return [fill(cut(flask, circle(34, 78, 5), circle(48, 70, 3.6), circle(40, 62, 2.6)), lime, True), fill(rrect(27, 14, 28, 7, 3), lime),
            fill(circle(52, 10, 3), WHITE), fill(circle(61, 20, 2.2), WHITE), fill(a, WHITE), fill(b, lime)]

@mark(36, "rainy-day-matinee", "Rainy Day Matinee", "Family films for when the weather wins.",
      "An umbrella with the rain coming down either side.", "plate")
def _():
    teal = "#6EE0D4"
    brolly = "M30 52 A70 40 0 0 1 170 52 Q158 42 146.7 52 Q135 42 123.3 52 Q112 42 100 52 Q88 42 76.7 52 Q65 42 53.3 52 Q42 42 30 52 Z"
    a, _b = text_box("chewy-400", "RAINY DAY", 58, 24, 142, 40, "c", "c", 0.05)
    drop = lambda x, y: fill(f"M{x} {y - 6} Q{x + 5} {y + 1} {x + 3.4} {y + 3.6} Q{x} {y + 6.6} {x - 3.4} {y + 3.6} Q{x - 5} {y + 1} {x} {y - 6} Z", WHITE)
    b, _b = text_box("chewy-400", "matinee", 30, 60, 170, 99)
    return [fill(cut(brolly, a), teal, True), drop(18, 48), drop(14, 76), drop(182, 48), drop(186, 76), fill(b, teal)]

@mark(40, "little-spirits", "Little Spirits", "Family anime: small heroes, big feelings.",
      "A small round spirit with a wisp on its head.", "symbol")
def _():
    pink = "#FF9FCB"
    body = "M40 30 C46 20 40 14 46 10 C52 18 50 26 46 30 C64 32 70 50 66 68 C62 88 50 94 40 94 C30 94 18 88 14 68 C10 50 16 32 34 30 Z"
    a, _b = text_box("sniglet-800", "Little", 80, 20, 188, 54, "l")
    b, _b = text_box("sniglet-800", "Spirits", 80, 58, 188, 94, "l")
    return [fill(cut(body, ellipse(31, 58, 4, 6), ellipse(49, 58, 4, 6)), WHITE, True), fill(circle(22, 70, 4), pink), fill(circle(58, 70, 4), pink), fill(a, WHITE), fill(b, pink)]

@mark(44, "lights-out", "Lights Out", "Calm, quiet and sleepy. The bedtime channel.",
      "The O in OUT is the moon.", "idea")
def _():
    violet, moon = "#A9A4FF", "#FFE9A6"; face = "baloo-2-800"
    a, _b = text_box(face, "lights", 20, 12, 180, 50)
    size = 62; base = 98
    for _fit in range(2):
        _u, wu = text_at(face, "ut", size, 0, base); r = cap_height(face, size, "x") / 2 * 1.08; gap = size * .05
        total = 2 * r + gap + wu
        if total > 150: size *= 150 / total
    x = (W - total) / 2; cx, cy = x + r, base - r
    u, _w = text_at(face, "ut", size, cx + r + gap, base)
    return [fill(a, WHITE), fill(crescent(cx, cy, r), moon, True), fill(u, violet),
            fill(star(x - 14, cy - 8, 5, 4, .38), moon), fill(star(x + total + 12, cy + 6, 4, 4, .38), moon)]

@mark(48, "pocket-heroes", "Pocket Heroes", "Capes, masks and kind-hearted saves.",
      "A hero's badge, the shape of a pocket.", "plate")
def _():
    badge = "M32 14 H168 Q174 14 174 20 V62 Q174 70 166 74 L104 99 Q100 100.5 96 99 L34 74 Q26 70 26 62 V20 Q26 14 32 14 Z"
    a, _b = text_box("luckiest-guy-400", "POCKET", 40, 22, 160, 44)
    b, _b = text_box("luckiest-guy-400", "HEROES", 40, 47, 160, 69)
    return [fill(cut(badge, a, b, star(100, 82, 8)), "#FFDB4D", True)]

@mark(52, "flipbook", "Flipbook", "Golden-oldie family films and classic cartoons.",
      "Pages fanning as the book is flipped.", "symbol")
def _():
    gold = "#FFD469"
    page = [(32, 92), (68, 92), (68, 64), (32, 64)]
    pages = [fill(poly(rot(page, t, 32, 92)), c) for t, c in ((-42, gold), (-28, WHITE), (-14, gold), (0, WHITE))]
    a, _b = text_box("chewy-400", "flip", 78, 14, 188, 56, "l")
    b, _b = text_box("chewy-400", "book", 78, 58, 188, 98, "l")
    return pages + [fill(a, gold), fill(b, WHITE)]

@mark(56, "snowglobe", "Snowglobe", "Winter holidays for small people, sprinkles included.",
      "The name inside a glass dome on its stand.", "idea")
def _():
    ice = "#A6DEFF"
    t, _b = text_box("baloo-2-800", "snowglobe", 30, 44, 170, 74)
    snow = [fill(circle(x, y, r), WHITE) for x, y, r in ((60, 30, 2.8), (92, 22, 2.2), (124, 31, 3), (150, 44, 2), (44, 46, 2.2), (108, 38, 1.8))]
    return [stroke("M22 78 C22 -8 178 -8 178 78", WHITE, 5), fill(t, ice), fill("M34 82 H166 L174 98 H26 Z", ice)] + snow

@mark(60, "giggle-garage", "Giggle Garage", "Silly shows, funny voices and a lot of pratfalls.",
      "A garage, with the door for the second word.", "plate")
def _():
    shed = "M20 98 V46 L100 12 L180 46 V98 Z"
    a, _b = text_box("lilita-one-400", "GIGGLE", 62, 32, 138, 50)
    b, _b = text_box("lilita-one-400", "GARAGE", 34, 60, 166, 92)
    return [fill(cut(shed, a, b, rrect(30, 54, 140, 2.6, 1.3)), "#FF79D0", True)]

@mark(64, "wheels-and-whistles", "Wheels & Whistles", "Trains, trucks, diggers and anything that rolls.",
      "The name rides on three wheels.", "idea")
def _():
    yel = "#FFD23F"
    a, _b = text_box("titan-one-400", "WHEELS", 14, 12, 186, 44)
    b, _b = text_box("titan-one-400", "& WHISTLES", 14, 48, 186, 72)
    wheel = lambda x: [stroke(circle(x, 88, 8.5), yel, 5), fill(circle(x, 88, 2.4), yel)]
    return [fill(a, yel), fill(b, WHITE), stroke("M14 78 H186", WHITE, 3.4)] + wheel(42) + wheel(100) + wheel(158)

@mark(68, "small-sleuths", "Small Sleuths", "Gentle mysteries, a case in every episode.",
      "A keyhole to peek through.", "symbol")
def _():
    peri = "#A8B4FF"
    hole = "M34.4 55.5 A11 11 0 1 1 45.6 55.5 L51 78 H29 Z"
    a, _b = text_box("boogaloo-400", "small", 82, 16, 188, 50, "l")
    b, _b = text_box("boogaloo-400", "sleuths", 82, 54, 188, 96, "l")
    return [fill(cut(rrect(14, 16, 52, 80, 14), hole), peri, True), fill(a, WHITE), fill(b, peri)]

@mark(76, "spooky-sleepover", "Spooky Sleepover", "Friendly ghosts and gentle frights, flashlight under the blanket.",
      "A friendly ghost.", "symbol")
def _():
    violet = "#B79CFF"
    ghost = "M14 54 A26 30 0 0 1 66 54 V94 Q59.5 84 53 94 Q46.5 84 40 94 Q33.5 84 27 94 Q20.5 84 14 94 Z"
    a, _b = text_box("chewy-400", "spooky", 78, 16, 188, 56, "l")
    b, _b = text_box("chewy-400", "sleepover", 78, 60, 188, 92, "l")
    return [fill(cut(ghost, ellipse(31, 52, 4.4, 6.4), ellipse(49, 52, 4.4, 6.4), "M32 68 Q40 76 48 68 Q40 72 32 68 Z"), WHITE, True), fill(a, violet), fill(b, WHITE)]

@mark(80, "tiny-chefs", "Tiny Chefs", "Messy kitchens, cooking and baking, small aprons.",
      "A chef's hat: tiny in the puff, chefs on the band.", "plate")
def _():
    hat = "M52 98 V64 C28 62 22 36 46 30 C50 10 84 6 98 22 C112 6 146 10 152 30 C178 36 172 62 148 64 V98 Z"
    a, _b = text_box("sniglet-800", "tiny", 66, 24, 134, 58)
    b, _b = text_box("sniglet-800", "CHEFS", 60, 72, 140, 92, "c", "c", 0.04)
    return [fill(cut(hat, a, b, rrect(52, 64.5, 96, 2.6, 1.3)), "#FF7C6B", True)]

@mark(99, "kids-odds-and-ends", "Kids' Odds & Ends", "",
      "A toy box with the bits and pieces jumping out.", "plate")
def _():
    teal = "#5FE0CF"
    t, _b = text_box("fredoka-700", "odds & ends", 36, 58, 164, 86)
    return [fill(cut(rrect(22, 46, 156, 52, 8), t), teal, True), fill(circle(50, 28, 10), WHITE), fill(poly([(78, 40), (90, 16), (102, 40)]), teal),
            fill(poly(rot([(114, 18), (134, 18), (134, 38), (114, 38)], 14, 124, 28)), WHITE), fill(star(158, 27, 12), teal)]
