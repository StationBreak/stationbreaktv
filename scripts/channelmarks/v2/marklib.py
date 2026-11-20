"""Building blocks for StationBreak channel marks.

A mark is a list of shapes on a 200 x 112 canvas (the 50 x 28 dp box at 4 px per dp), safe area 12..188 x 12..100.
Text is always converted to outlines. Nothing is drawn in a dark colour: where a shape needs a hole, the hole is
cut out of it (even-odd), so the all-white mono version of every mark still reads.
"""
import glob, math, pathlib
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.misc.transform import Transform

HERE = pathlib.Path(__file__).parent
FONT_DIRS = [HERE / "fonts", HERE.parent / "fonts"]   # the six faces added for these marks, then the shared set in ../fonts
W, H = 200, 112
WHITE = "#FFFFFF"

def _n(v):
    s = "%.2f" % v
    return s.rstrip("0").rstrip(".") if "." in s else s

class Face:
    _cache = {}
    def __new__(cls, stem):
        if stem not in cls._cache:
            self = super().__new__(cls)
            path = [p for d in FONT_DIRS for p in glob.glob(str(d / (stem + ".*")))][0]
            self.font = TTFont(path); self.gs = self.font.getGlyphSet(); self.cmap = self.font.getBestCmap()
            self.upm = self.font["head"].unitsPerEm; self.hmtx = self.font["hmtx"]
            cls._cache[stem] = self
        return cls._cache[stem]
    def glyphs(self, text, tracking=0.0):
        """[(glyph name, x offset in font units)], total advance."""
        out, x = [], 0.0
        for ch in text:
            g = self.cmap[ord(ch)]
            out.append((g, x)); x += self.hmtx[g][0] + tracking * self.upm
        return out, x - tracking * self.upm

def _draw(face, run, t, merge=False):
    """Outline a run of glyphs. With merge, overlapping letters (joined script) are fused into one shape first,
    so that cutting the text out of a plate leaves clean holes instead of filled-in overlaps."""
    pen = SVGPathPen(face.gs, ntos=_n)
    if merge:
        import pathops
        path = pathops.Path(); pp = path.getPen()
        for g, ox in run:
            face.gs[g].draw(TransformPen(pp, t.translate(ox, 0)))
        path.simplify(fix_winding=True, keep_starting_points=False)
        path.draw(pen)
        return pen.getCommands()
    for g, ox in run:
        face.gs[g].draw(TransformPen(pen, t.translate(ox, 0)))
    return pen.getCommands()

def _bounds(face, run, skew):
    pen = BoundsPen(face.gs)
    t = Transform(1, 0, math.tan(math.radians(skew)), 1, 0, 0)
    for g, ox in run:
        face.gs[g].draw(TransformPen(pen, t.translate(ox, 0)))
    return pen.bounds  # xmin, ymin, xmax, ymax, y up

def text_box(stem, text, x0, y0, x1, y1, align="c", valign="c", tracking=0.0, skew=0.0, merge=False):
    """Outline `text`, scaled so its ink just fits the box. Returns (path data, (left, top, right, bottom))."""
    face = Face(stem); run, _ = face.glyphs(text, tracking)
    bx0, by0, bx1, by1 = _bounds(face, run, skew)
    bw, bh = bx1 - bx0, by1 - by0
    k = min((x1 - x0) / bw, (y1 - y0) / bh)
    left = x0 + ((x1 - x0) - bw * k) * {"l": 0, "c": .5, "r": 1}[align]
    top = y0 + ((y1 - y0) - bh * k) * {"t": 0, "c": .5, "b": 1}[valign]
    t = Transform(k, 0, k * math.tan(math.radians(skew)), -k, left - bx0 * k, top + by1 * k)
    return _draw(face, run, t, merge), (left, top, left + bw * k, top + bh * k)

def text_at(stem, text, size, x, baseline, tracking=0.0, sx=1.0, sy=1.0, rot=0.0):
    """Outline `text` at a font size, starting at x on a baseline. Returns (path data, advance width)."""
    face = Face(stem); run, adv = face.glyphs(text, tracking); k = size / face.upm
    t = Transform().translate(x, baseline).rotate(math.radians(rot)).scale(k * sx, -k * sy)
    return _draw(face, run, t), adv * k * sx

def cap_height(stem, size, ch="H"):
    face = Face(stem); run, _ = face.glyphs(ch)
    return _bounds(face, run, 0)[3] * size / face.upm

# shapes -------------------------------------------------------------------------------------------------------------
def fill(d, color, evenodd=False):
    return f'<path d="{d}" fill="{color}"' + (' fill-rule="evenodd"' if evenodd else "") + "/>"

def cut(plate, *holes):
    """A plate with holes knocked out of it."""
    return plate + " " + " ".join(holes)

def union(*ds):
    """Fuse several shapes into one outline. Use it before cutting holes that overlap each other,
    otherwise the overlap fills back in."""
    import pathops
    from fontTools.svgLib.path import parse_path
    path = pathops.Path(); pen = path.getPen()
    for d in ds:
        parse_path(d, pen)
    path.simplify(fix_winding=True, keep_starting_points=False)
    out = SVGPathPen(None, ntos=_n); path.draw(out)
    return out.getCommands()

def stroke(d, color, w, cap="round"):
    return f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{_n(w)}" stroke-linecap="{cap}" stroke-linejoin="round"/>'

def rrect(x, y, w, h, r):
    return (f"M{_n(x+r)} {_n(y)} H{_n(x+w-r)} Q{_n(x+w)} {_n(y)} {_n(x+w)} {_n(y+r)} V{_n(y+h-r)} Q{_n(x+w)} {_n(y+h)} {_n(x+w-r)} {_n(y+h)} "
            f"H{_n(x+r)} Q{_n(x)} {_n(y+h)} {_n(x)} {_n(y+h-r)} V{_n(y+r)} Q{_n(x)} {_n(y)} {_n(x+r)} {_n(y)} Z")

def circle(cx, cy, r):
    return f"M{_n(cx-r)} {_n(cy)} A{_n(r)} {_n(r)} 0 1 0 {_n(cx+r)} {_n(cy)} A{_n(r)} {_n(r)} 0 1 0 {_n(cx-r)} {_n(cy)} Z"

def poly(pts):
    return "M" + " L".join(f"{_n(x)} {_n(y)}" for x, y in pts) + " Z"

def rot(pts, deg, cx, cy):
    a = math.radians(deg); c, s = math.cos(a), math.sin(a)
    return [(cx + (x - cx) * c - (y - cy) * s, cy + (x - cx) * s + (y - cy) * c) for x, y in pts]

def svg(shapes, mono=False, title=None):
    body = "".join(shapes)
    if mono:
        import re
        body = re.sub(r'(fill|stroke)="#[0-9A-Fa-f]{6}"', r'\1="#FFFFFF"', body)
    if title:
        import html
        return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}"><title>{html.escape(title)}</title>{body}</svg>'
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}">{body}</svg>'
