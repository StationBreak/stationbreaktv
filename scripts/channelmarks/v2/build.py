#!/usr/bin/env python3
"""Channel marks, second generation: every mark drawn from its name, tagline and theme.

    pip install fonttools skia-pathops                 # --png also needs rsvg-convert (librsvg) on the PATH
    python3 scripts/channelmarks/v2/build.py [--png <dir>]

Writes out/svg/ (colour masters), out/svg-mono/ (all fills white) and out/contact-sheet.html beside this file.
With --png it also renders each mark the way the app loads it: 48 dp and 64 dp at 2x (96x54 and 128x72 px),
transparent, colour and mono, as sl_<NNN>_<key>_<N>dp.png and sl_<NNN>_<key>_mono_<N>dp.png, stripped to the
PNG chunks the app's manifest test allows. The shipped renders were made with rsvg-convert 2.58.0; the same
version reproduces channelmarks.sha256 byte for byte.

The marks themselves are in round1.py to round6.py (the order they were reviewed in); marklib.py has the
drawing helpers. Fonts: the six new faces are in fonts/ here, the rest come from ../fonts.
"""
import html, pathlib, struct, subprocess, sys
import round1, round2, round3, round4, round5, round6
from marklib import svg

HERE = pathlib.Path(__file__).parent
PNG_BUCKETS = (48, 64)
PNG_KEEP = {b"IHDR", b"PLTE", b"tRNS", b"IDAT", b"IEND"}

def all_marks():
    marks = {}
    for mod in (round1, round2, round3, round4, round5, round6):
        marks.update(mod.MARKS)
    return sorted(marks.values(), key=lambda m: m["number"])

def strip_png(png):
    """Keep only the chunks the app's manifest test allows."""
    out = [png[:8]]; i = 8
    while i < len(png):
        n, = struct.unpack(">I", png[i:i + 4]); kind = png[i + 4:i + 8]
        if kind in PNG_KEEP: out.append(png[i:i + 12 + n])
        i += 12 + n
    return b"".join(out)

def render_png(svg_text, bucket):
    w = bucket * 2; h = round(w * 112 / 200)
    png = subprocess.run(["rsvg-convert", "-a", "-w", str(w), "-h", str(h), "-f", "png"], input=svg_text.encode(),
                         capture_output=True, check=True).stdout
    return strip_png(png)

def main(png_dir=None):
    out = HERE / "out"; (out / "svg").mkdir(parents=True, exist_ok=True); (out / "svg-mono").mkdir(exist_ok=True)
    if png_dir: png_dir = pathlib.Path(png_dir); png_dir.mkdir(parents=True, exist_ok=True)
    marks = all_marks(); pngs = 0; cards = strip = mono_strip = ""
    for m in marks:
        stem = f"sl_{m['number']:03d}_{m['key']}"
        full, mono = svg(m["shapes"], title=m["name"]), svg(m["shapes"], True, title=m["name"])
        (out / "svg" / f"{stem}.svg").write_text(full); (out / "svg-mono" / f"{stem}.svg").write_text(mono)
        if png_dir:
            for bucket in PNG_BUCKETS:
                for tag, text in (("", full), ("_mono", mono)):
                    (png_dir / f"{stem}{tag}_{bucket}dp.png").write_bytes(render_png(text, bucket)); pngs += 1
        inline, inline_mono = svg(m["shapes"]), svg(m["shapes"], True)
        cards += f'<div class="c"><div class="b">{inline}</div><p><b>{m["number"]}</b> {html.escape(m["name"])}</p><p class="i">{html.escape(m["idea"])}</p></div>'
        strip += f"<div>{inline}</div>"; mono_strip += f"<div>{inline_mono}</div>"
    (out / "contact-sheet.html").write_text(f'''<!doctype html><meta charset="utf-8"><title>Channel marks — {len(marks)}</title>
<style>body{{margin:0;background:#0E0F11;color:#ECEDEF;font:14px system-ui,sans-serif;padding:32px}}h1{{font-size:20px;margin:0 0 20px}}h2{{font-size:15px;margin:36px 0 12px;color:#9A9DA5}}
.g{{display:grid;grid-template-columns:repeat(auto-fill,minmax(230px,1fr));gap:22px 16px}}.b{{background:#1B1C1F;border-radius:4px;padding:12px}}.b svg{{display:block;width:100%;height:auto}}
p{{margin:6px 0 0}}.i{{color:#9A9DA5;font-size:12px;margin-top:2px}}.s{{display:flex;gap:2px;flex-wrap:wrap}}.s div{{width:100px;height:56px;background:rgba(255,255,255,.05)}}.s svg{{display:block;width:100px;height:56px}}</style>
<h1>Channel marks — {len(marks)}</h1><div class="g">{cards}</div>
<h2>At guide size (1:1 on 1080p)</h2><div class="s">{strip}</div>
<h2>One colour (all fills white)</h2><div class="s">{mono_strip}</div>''')
    print("wrote", len(marks), "marks" + (f" and {pngs} PNGs" if png_dir else ""))

if __name__ == "__main__":
    args = sys.argv[1:]
    main(args[args.index("--png") + 1] if "--png" in args else None)
