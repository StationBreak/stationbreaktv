import math, pathlib
from marklib import *
from marklib import _n, _bounds, _draw, Face
from fontTools.pens.recordingPen import RecordingPen
from fontTools.misc.transform import Transform

BLUE = "#AFD8FF"; STEM = "pacifico-400"

def rabbit(text, box, ears=None):
    """`text` set in the box with its two b's in white and a nose under them. `ears` is an optional box for a second line."""
    face = Face(STEM); run, _adv = face.glyphs(text)
    _t, (l, top, r, bot) = text_box(STEM, text, *box)
    bx0, by0, bx1, by1 = _bounds(face, run, 0); k = (r - l) / (bx1 - bx0)
    T = Transform(k, 0, 0, -k, l - bx0 * k, top + by1 * k)
    xh = _bounds(face, face.glyphs("a")[0], 0)[3]
    feet = []
    for idx in (2, 3):
        g, ox = run[idx]; rec = RecordingPen(); face.gs[g].draw(rec)
        pts = [p for _op, args in rec.value for p in args if p is not None]
        sx = min(p[0] for p in pts if abs(p[1] - xh) < xh * .12) + 55
        feet.append(T.transformPoint((ox + sx + 150, 0)))
    rest = _draw(face, [g for i, g in enumerate(run) if i not in (2, 3)], T); bb = _draw(face, [run[2], run[3]], T)
    u = k / 0.0537                                   # nose scales with the lettering
    nx = (feet[0][0] + feet[1][0]) / 2; ny = feet[0][1] + 3.2 * u
    nose = fill(poly([(nx - 4.4 * u, ny), (nx + 4.4 * u, ny), (nx, ny + 5 * u)]), WHITE)
    mouth = stroke(f"M{_n(nx)} {_n(ny + 4.5 * u)} V{_n(ny + 8 * u)} M{_n(nx - 6.5 * u)} {_n(ny + 8 * u)} Q{_n(nx - 3.2 * u)} {_n(ny + 11.5 * u)} {_n(nx)} {_n(ny + 8 * u)} "
                   f"Q{_n(nx + 3.2 * u)} {_n(ny + 11.5 * u)} {_n(nx + 6.5 * u)} {_n(ny + 8 * u)}", WHITE, 2.6 * u)
    out = [fill(rest, BLUE), fill(bb, WHITE), nose, mouth]
    if ears:
        e, _b = text_box(STEM, "Ears", *ears)
        out.append(fill(e, BLUE))
    return out

STACKED = lambda: rabbit("Rabbit", (12, 12, 132, 52, "l"), (100, 56, 188, 101, "r"))
ONE_LINE = lambda: rabbit("Rabbit Ears", (12, 22, 188, 80))

if __name__ == "__main__":
    def cell(shapes, cap):
        s = svg(shapes); m = svg(shapes, True)
        return f'<div class="big">{s}<p>{cap}</p><div class="row"><div class="sm">{s}<p>Guide size</p></div><div class="sm">{m}<p>One colour</p></div></div></div>'
    pathlib.Path("check2.html").write_text(f'''<style>body{{background:#0E0F11;margin:0;padding:28px;color:#9A9DA5;font:15px sans-serif;width:900px}}
.two{{display:flex;gap:24px}} .big{{flex:1}} .big>svg{{width:100%;background:#1B1C1F;display:block;border-radius:6px}}
.row{{display:flex;gap:28px;margin-top:18px}} .sm svg{{width:100px;height:56px;display:block;outline:1px solid #2C2E33}} p{{margin:6px 0 0}}</style>
<div class="two">{cell(STACKED(), "A. Stacked, with Ears made bigger")}{cell(ONE_LINE(), "B. Both words on one line")}</div>''')
