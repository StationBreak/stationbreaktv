#!/usr/bin/env python3
"""StationBreak channel brands — portfolio approach.

Each channel is its own small brand: its own typeface (from an approved OFL
set), one or two brand colours plus white, a lockup, and optionally one
geometric device. Cohesion comes from production rules (same box, flat colour,
no tiles, no effects, contrast floor), not from a shared typeface.

Output: SVG masters (text outlined), full-colour; plus a mono variant where
every fill becomes white (for the grid when a theme asks for it).
"""
from __future__ import annotations
import json, math, re, struct, sys, pathlib
from xml.sax.saxutils import escape
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.transformPen import TransformPen

HERE = pathlib.Path(__file__).parent
W, H = 200, 112
# App renders: a bucket is N dp at 2x, so N*2 px wide; the height keeps the 200:112 box. The app picks a bucket by screen
# density (AssetMarkLoader). 96x54 serves up to 1.9x, 128x72 serves 2x and above.
PNG_BUCKETS = (48, 64)
PNG_KEEP = {b"IHDR", b"PLTE", b"tRNS", b"IDAT", b"IEND"}
SAFE_W, SAFE_H = 176, 88
STROKE = 8
FONT_DIR = HERE / "fonts"
_fonts = {}
def font(name):
    if name not in _fonts: _fonts[name] = TTFont(FONT_DIR / f"{name}.ttf")
    return _fonts[name]

def run_path(text, fname, size, tracking=0.0, skew=0.0, x=0.0):
    f = font(fname); cmap = f.getBestCmap(); gs = f.getGlyphSet(); hmtx = f["hmtx"]
    scale = size / f["head"].unitsPerEm
    pen = SVGPathPen(gs); bounds = BoundsPen(gs)
    kern = {}
    if "kern" in f:
        for st in f["kern"].kernTables: kern.update(getattr(st, "kernTable", {}))
    prev = None
    for ch in text:
        g = cmap.get(ord(ch))
        if g is None: x += 0.28 * size; prev = None; continue
        if prev and (prev, g) in kern: x += kern[(prev, g)] * scale
        t = (scale, 0, -math.tan(math.radians(skew)) * scale, -scale, x, 0)
        gs[g].draw(TransformPen(pen, t)); gs[g].draw(TransformPen(bounds, t))
        x += hmtx[g][0] * scale + tracking * size; prev = g
    return pen.getCommands(), (bounds.bounds or (x, 0, x, 0)), x

def line_paths(line, size):
    """A line = list of segments {text,font,color,tracking,skew}. Returns frags, bbox."""
    frags = []; x = 0.0; bb = None
    for seg in line["segs"]:
        d, b, x = run_path(seg["text"], seg["font"], size, seg.get("tracking", 0), seg.get("skew", 0), x)
        if d: frags.append((d, seg.get("color", "#fff")))
        if b[2] > b[0]:
            bb = b if bb is None else (min(bb[0], b[0]), min(bb[1], b[1]), max(bb[2], b[2]), max(bb[3], b[3]))
        x += seg.get("gap", 0) * size
    return frags, bb

def build(spec):
    """Lay out lines at nominal sizes, then scale uniformly into the safe area."""
    gap = spec.get("gap", 0.10)  # fraction of big size
    placed = []  # (frags, bbox, y_top)
    y = 0.0
    for ln in spec["lines"]:
        size = 100 * ln.get("rel", 1.0)
        frags, bb = line_paths(ln, size)
        h = bb[3] - bb[1]
        placed.append((frags, bb, y, ln.get("align", "c")))
        y += h + gap * 100
    y -= gap * 100
    total_w = max(b[2] - b[0] for _, b, _, _ in placed); total_h = y
    # device reserve
    dev = spec.get("device")
    pad_w = {"ring": 0.55, "box": 0.30, "circle_left": 0.0, "crescent_right": 0.55, "dot_after": 0.25}.get(dev, 0)
    pad_h = {"ring": 0.55, "box": 0.45, "rules": 0.45, "rule_below": 0.25, "rule_below_dashed": 0.25, "arc_above": 0.75, "bars": 0.45}.get(dev, 0)
    s = min(SAFE_W / (total_w * (1 + pad_w)), SAFE_H / (total_h * (1 + pad_h)))
    rf = spec.get("device_size") or 0.30
    if dev == "circle_left": s = min(SAFE_H / total_h, (SAFE_W - 8) / (total_w + 2 * rf * total_h))
    if dev == "ring": s = min(s, SAFE_H / (math.hypot(total_w, total_h) * 1.0 + 2 * STROKE + 10))
    out = []
    cx, cy = W / 2, H / 2
    # shift content for side devices
    dx = {"circle_left": rf * total_h * s + 4, "crescent_right": -total_w * s * 0.22, "dot_after": -total_w * s * 0.10}.get(dev, 0)
    top = cy - total_h * s / 2
    for frags, bb, y0, align in placed:
        w = (bb[2] - bb[0]) * s
        x0 = cx + dx - w / 2 if align == "c" else (cx + dx - total_w * s / 2 if align == "l" else cx + dx + total_w * s / 2 - w)
        tx = x0 - bb[0] * s; ty = top + y0 * s - bb[1] * s
        for d, col in frags:
            out.append(f'<path fill="{col}" transform="translate({tx:.2f} {ty:.2f}) scale({s:.4f})" d="{d}"/>')
    cw, ch = total_w * s, total_h * s
    out = device(dev, spec.get("device_color", "#fff"), cx + dx, cy, cw, ch, spec.get("device_size")) + out
    return "".join(out)

def device(kind, col, cx, cy, cw, ch, size=None):
    S = f'fill="none" stroke="{col}" stroke-width="{STROKE}" stroke-linecap="round"'
    if not kind: return []
    if kind == "ring":
        r = math.hypot(cw, ch) / 2 + 8
        return [f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="{r:.1f}" {S}/>']
    if kind == "box":
        pw, ph = cw + 24, ch + 22
        return [f'<rect x="{cx-pw/2:.1f}" y="{cy-ph/2:.1f}" width="{pw:.1f}" height="{ph:.1f}" rx="2" {S}/>']
    if kind in ("rule_below", "rule_below_dashed"):
        y = cy + ch / 2 + 12
        dash = ' stroke-dasharray="14 9"' if kind.endswith("dashed") else ""
        return [f'<line x1="{cx-cw/2:.1f}" y1="{y:.1f}" x2="{cx+cw/2:.1f}" y2="{y:.1f}" {S}{dash}/>']
    if kind in ("rules", "bars"):
        y0, y1 = cy - ch / 2 - 12, cy + ch / 2 + 12
        return [f'<line x1="{cx-cw/2:.1f}" y1="{y0:.1f}" x2="{cx+cw/2:.1f}" y2="{y0:.1f}" {S}/>',
                f'<line x1="{cx-cw/2:.1f}" y1="{y1:.1f}" x2="{cx+cw/2:.1f}" y2="{y1:.1f}" {S}/>']
    if kind == "arc_above":
        rx = cw / 2 + 4; ry = ch * 0.55; y = cy - ch / 2 - 4
        return [f'<path d="M{cx-rx:.1f} {y:.1f}A{rx:.1f} {ry:.1f} 0 0 1 {cx+rx:.1f} {y:.1f}" {S}/>']
    if kind == "circle_left":
        r = ch * (size or 0.30); x = cx - cw / 2 - r - 8
        return [f'<circle cx="{x:.1f}" cy="{cy:.1f}" r="{r:.1f}" fill="{col}"/>']
    if kind == "crescent_right":
        r = ch * 0.34; x = cx + cw / 2 + r + 10; y = cy - ch * 0.12
        return [f'<path d="M{x:.1f} {y-r:.1f}a{r:.1f} {r:.1f} 0 1 1 0 {2*r:.1f}a{r*1.05:.1f} {r*1.05:.1f} 0 1 0 0 {-2*r:.1f}z" fill="{col}"/>']
    if kind == "dot_after":
        r = ch * 0.11; x = cx + cw / 2 + r + 6; y = cy + ch / 2 - r
        return [f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{r:.1f}" fill="{col}"/>']
    return []

def svg(spec, mono=False):
    body = build(spec)
    if mono: body = re.sub(r'(fill|stroke)="#[0-9a-fA-F]{3,6}"', r'\1="#fff"', body)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}">'
            f'<title>{escape(spec["name"])}</title>{body}</svg>')

def parse_catalog(path):
    s = pathlib.Path(path).read_text(); out = {}
    for m in re.finditer(r'recipe\(\s*Band\.(\w+),\s*n = (\d+),\s*key = "([^"]+)",\s*name = "([^"]+)"', s):
        band, n, key, name = m.groups(); out[key] = {"band": band, "number": int(n), "key": key, "name": name}
    return out

def png_size(bucket):
    """Pixel size of a bucket: N dp at 2x wide, the 200:112 box tall."""
    w = bucket * 2
    return w, round(w * H / W)

def strip_png(png):
    """Keep only the chunks the app's manifest test allows (cairo adds a bKGD chunk)."""
    out = [png[:8]]; i = 8
    while i < len(png):
        n, = struct.unpack(">I", png[i:i + 4]); kind = png[i + 4:i + 8]
        if kind in PNG_KEEP: out.append(png[i:i + 12 + n])
        i += 12 + n
    return b"".join(out)

def render_png(svg_text, bucket):
    import cairosvg  # only needed for --png: pip install cairosvg
    w, h = png_size(bucket)
    return strip_png(cairosvg.svg2png(bytestring=svg_text.encode(), output_width=w, output_height=h))

def write_pngs(png_dir, number, key, full, mono):
    """sl_<NNN>_<key>_<N>dp.png (colour) and sl_<NNN>_<key>_mono_<N>dp.png (all fills white), N in PNG_BUCKETS."""
    names = []
    for bucket in PNG_BUCKETS:
        for tag, svg_text in (("", full), ("_mono", mono)):
            name = f"sl_{number:03d}_{key}{tag}_{bucket}dp.png"
            (png_dir / name).write_bytes(render_png(svg_text, bucket)); names.append(name)
    return names

def main(catalog, brands, out, png_dir=None):
    cat = parse_catalog(catalog); specs = json.loads(pathlib.Path(brands).read_text())
    out = pathlib.Path(out); (out / "svg").mkdir(parents=True, exist_ok=True); (out / "svg-mono").mkdir(exist_ok=True)
    png_dir = pathlib.Path(png_dir) if png_dir else None
    if png_dir: png_dir.mkdir(parents=True, exist_ok=True)
    rows = []; pngs = 0
    for key, sp in specs.items():
        # A channel that is not a recipe (the leftovers channels) carries its own band, number and name in brands.json.
        c = cat.get(key) or {"band": sp["band"], "number": sp["number"], "key": key, "name": sp["name"]}; sp = {**c, **sp}
        full, mono = svg(sp), svg(sp, True)
        base = f"sl_{c['number']:03d}_{key}"
        (out / "svg" / f"{base}.svg").write_text(full); (out / "svg-mono" / f"{base}.svg").write_text(mono)
        if png_dir: pngs += len(write_pngs(png_dir, c["number"], key, full, mono))
        rows.append((c, full, mono, sp))
    rows.sort(key=lambda r: r[0]["number"])
    total = len(set(cat) | set(specs))
    cells = "".join(f'<div class="m"><div class="box">{f}</div><div class="cap"><b>{c["number"]}</b>{c["name"]}<i>{c["band"]} · {sp.get("note","")}</i></div></div>' for c, f, m, sp in rows)
    strip = "".join(f"<div>{f}</div>" for c, f, m, sp in rows)
    mstrip = "".join(f"<div>{m}</div>" for c, f, m, sp in rows)
    col = "".join(f'<div class="row"><div class="num">{c["number"]}</div><div class="mk">{f}</div><div class="cell{" f" if i==3 else ""}">Program title<small>1997 · TV-Y · S2 E6</small></div><div class="cell">Up next<small>Series</small></div></div>' for i, (c, f, m, sp) in enumerate(rows[:6]))
    (out / "contact-sheet.html").write_text(f"""<!doctype html><meta charset=utf-8><title>StationBreak channel brands</title>
<style>body{{margin:0;background:#0e0f11;color:#fff;font:14px Inter,system-ui;padding:40px}}h1{{font-size:18px;font-weight:600;margin:0 0 4px}}h2{{font-size:14px;font-weight:600;margin:36px 0 10px;color:rgba(255,255,255,.7)}}p{{color:rgba(255,255,255,.55);margin:0 0 24px}}
.grid{{display:grid;grid-template-columns:repeat(4,1fr);gap:26px 22px}}.m .box{{height:112px;background:rgba(255,255,255,.06);border-radius:3px;display:flex;align-items:center;justify-content:center}}.m svg{{width:200px;height:112px}}
.cap{{margin-top:8px;color:rgba(255,255,255,.8)}}.cap b{{font-variant-numeric:tabular-nums;margin-right:8px}}.cap i{{display:block;color:rgba(255,255,255,.4);font-style:normal;font-size:12px;margin-top:2px}}
.strip{{display:flex;gap:2px;flex-wrap:wrap}}.strip div{{width:100px;height:56px;background:rgba(255,255,255,.06);display:flex;align-items:center;justify-content:center}}.strip svg{{width:100px;height:56px}}
.g{{width:900px}}.row{{display:grid;grid-template-columns:34px 100px 1fr 1fr;gap:2px;height:70px;margin-bottom:2px;align-items:center}}.num{{font-size:20px;color:rgba(255,255,255,.58);text-align:right;padding-right:6px;font-variant-numeric:tabular-nums}}.mk svg{{width:100px;height:56px;display:block}}
.cell{{height:100%;background:rgba(255,255,255,.06);border-radius:3px;padding:0 16px;display:flex;flex-direction:column;justify-content:center;font-size:24px;font-weight:500}}.cell small{{font-size:19px;color:rgba(255,255,255,.58);margin-top:3px;font-weight:400}}.cell.f{{background:#f26b2b;color:#1a0d06}}.cell.f small{{color:rgba(26,13,6,.72)}}</style>
<h1>Channel brands — {len(rows)} of {total}</h1><p>Each channel is its own brand: own typeface, own colours, own lockup. Same 50×28dp box, flat colour, no tiles.</p>
<div class="grid">{cells}</div>
<h2>At guide size (1:1 on 1080p)</h2><div class="strip">{strip}</div>
<h2>Mono fallback (same masters, all fills white)</h2><div class="strip">{mstrip}</div>
<h2>In the channel column</h2><div class="g">{col}</div>""")
    print("wrote", len(rows), f"marks and {pngs} PNGs" if png_dir else "marks")

if __name__ == "__main__":
    args = sys.argv[1:]
    png = None
    if "--png" in args:
        i = args.index("--png"); png = args[i + 1]; del args[i:i + 2]
    main(*args[:3], png_dir=png)
