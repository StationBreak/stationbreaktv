#!/usr/bin/env python3
"""StationBreak brand, direction C: retro-futurist (Syd Mead). Writes brand-sheet-3.html."""
import base64, glob, math, pathlib
HERE = pathlib.Path(__file__).parent
F = HERE.parent / "fonts"
def b64(pat): return base64.b64encode(pathlib.Path(glob.glob(str(F / pat))[0]).read_bytes()).decode()
MICH = b64("fontsource-michroma-*/package/files/michroma-latin-400-normal.woff")
STM = b64("fontsource-share-tech-mono-*/package/files/share-tech-mono-latin-400-normal.woff")

NAVY, DEEP, TEAL, CHROME, AMBER, ACCENT, MAG = "#0B1626", "#050A14", "#1F6F8B", "#D7E3EA", "#FFC46B", "#E8642C", "#C04A8C"

def defs(pfx):
    return f'''<defs>
<linearGradient id="{pfx}sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{DEEP}"/><stop offset=".55" stop-color="{NAVY}"/><stop offset="1" stop-color="{TEAL}"/></linearGradient>
<linearGradient id="{pfx}sun" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{AMBER}"/><stop offset=".6" stop-color="{ACCENT}"/><stop offset="1" stop-color="{MAG}"/></linearGradient>
<linearGradient id="{pfx}chrome" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF"/><stop offset=".45" stop-color="{CHROME}"/><stop offset=".5" stop-color="#6E8794"/><stop offset="1" stop-color="#2B3A45"/></linearGradient>
<linearGradient id="{pfx}glow" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="{AMBER}" stop-opacity="0"/><stop offset=".5" stop-color="{AMBER}"/><stop offset="1" stop-color="{AMBER}" stop-opacity="0"/></linearGradient>
<radialGradient id="{pfx}halo" cx=".5" cy=".5" r=".5"><stop offset="0" stop-color="{AMBER}" stop-opacity=".55"/><stop offset="1" stop-color="{AMBER}" stop-opacity="0"/></radialGradient>
</defs>'''

def sun_bands(cx, cy, r, pfx, n=5):
    """A low sun with horizontal cut bands near its base (the 80s futurist sun, drawn as a mask)."""
    out = [f'<circle cx="{cx}" cy="{cy}" r="{r*1.9}" fill="url(#{pfx}halo)"/>', f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="url(#{pfx}sun)"/>']
    for i in range(n):
        y = cy + r*0.15 + i*(r*0.17); hgt = r*0.03 + i*r*0.012
        out.append(f'<rect x="{cx-r-2}" y="{y:.1f}" width="{2*r+4}" height="{hgt:.1f}" fill="{NAVY}" opacity=".9"/>')
    return "".join(out)

def grid_floor(x0, x1, horizon, bottom, cx, pfx, cols=14, rows=7, color=CHROME):
    out = []
    for i in range(cols + 1):
        t = i / cols; xh = x0 + t*(x1-x0); xb = cx + (xh-cx)*3.2
        out.append(f'<line x1="{xh:.1f}" y1="{horizon}" x2="{xb:.1f}" y2="{bottom}" stroke="{color}" stroke-width="1.2" opacity=".28"/>')
    for j in range(1, rows + 1):
        t = (j / rows)**2.2; y = horizon + t*(bottom-horizon)
        out.append(f'<line x1="{x0-400}" y1="{y:.1f}" x2="{x1+400}" y2="{y:.1f}" stroke="{color}" stroke-width="{1+j*0.3:.1f}" opacity="{.18+j*.06:.2f}"/>')
    out.append(f'<line x1="{x0-400}" y1="{horizon}" x2="{x1+400}" y2="{horizon}" stroke="url(#{pfx}glow)" stroke-width="3"/>')
    return "".join(out)

def spire(cx, base, h, color=CHROME, pfx="i"):
    """A slender broadcast spire: tapered needle, two dish rings, beacon."""
    w = h*0.10
    s = f'<path d="M{cx-w/2} {base} L{cx} {base-h} L{cx+w/2} {base} Z" fill="url(#{pfx}chrome)"/>'
    s += f'<path d="M{cx-w/2} {base} L{cx} {base-h} L{cx-w*0.12} {base} Z" fill="#FFFFFF" opacity=".35"/>'
    for k, t in enumerate((0.55, 0.72)):
        y = base - h*t; rw = w*(1.1 - t*0.6)*2.2; rh = rw*0.22
        s += f'<ellipse cx="{cx}" cy="{y:.1f}" rx="{rw:.1f}" ry="{rh:.1f}" fill="none" stroke="{color}" stroke-width="{max(1.5, h*0.006):.1f}" opacity=".85"/>'
    s += f'<circle cx="{cx}" cy="{base-h-h*0.02}" r="{max(3, h*0.014):.1f}" fill="{AMBER}"/>'
    s += f'<circle cx="{cx}" cy="{base-h-h*0.02}" r="{max(8, h*0.045):.1f}" fill="url(#{pfx}halo)"/>'
    return s

def signal_bands(cx, cy, n, w0, gap, color=AMBER):
    """Horizontal signal bars widening outward, replacing arcs — scan-line signal."""
    out = []
    for i in range(n):
        wdt = w0 + i*gap; y = cy - i*gap*0.55; op = 1 - i/(n+1)
        out.append(f'<line x1="{cx-wdt/2:.1f}" y1="{y:.1f}" x2="{cx+wdt/2:.1f}" y2="{y:.1f}" stroke="{color}" stroke-width="4" stroke-linecap="round" opacity="{op:.2f}"/>')
    return "".join(out)

def icon(size=512, pfx="i"):
    horizon = 330
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="{size}" height="{size}">
{defs(pfx)}<clipPath id="{pfx}c"><rect width="512" height="512" rx="110"/></clipPath>
<g clip-path="url(#{pfx}c)">
<rect width="512" height="512" fill="url(#{pfx}sky)"/>
{sun_bands(256, 300, 120, pfx)}
<rect x="0" y="{horizon}" width="512" height="182" fill="{DEEP}"/>
{grid_floor(60, 452, horizon, 512, 256, pfx)}
{spire(256, horizon+2, 250, pfx=pfx)}
</g>
<rect x="1" y="1" width="510" height="510" rx="110" fill="none" stroke="{CHROME}" stroke-width="2" opacity=".35"/>
</svg>'''

def monitor(x, y, w, pfx):
    """A wedge-shaped concept monitor on a slim pedestal, chrome edge, screen showing the horizon scene."""
    h = w*0.62
    scr = f'<rect x="{x+w*0.05}" y="{y+w*0.05}" width="{w*0.9}" height="{h*0.78}" rx="{w*0.05}" fill="url(#{pfx}sky)"/>'
    inner = f'<g clip-path="url(#{pfx}m)">{sun_bands(x+w*0.5, y+h*0.62, w*0.2, pfx, 4)}<rect x="{x}" y="{y+h*0.66}" width="{w}" height="{h}" fill="{DEEP}"/>{grid_floor(x+w*0.15, x+w*0.85, y+h*0.66, y+h*0.85, x+w*0.5, pfx, 8, 4)}{spire(x+w*0.5, y+h*0.67, h*0.45, pfx=pfx)}</g>'
    body = f'<path d="M{x} {y+w*0.03} q0 -{w*0.03} {w*0.03} -{w*0.03} h{w*0.94} q{w*0.03} 0 {w*0.03} {w*0.03} v{h*0.86} q0 {w*0.03} -{w*0.03} {w*0.03} h-{w*0.94} q-{w*0.03} 0 -{w*0.03} -{w*0.03} z" fill="#1E2A36" stroke="url(#{pfx}chrome)" stroke-width="{w*0.012}"/>'
    ped = f'<path d="M{x+w*0.42} {y+h*0.92} h{w*0.16} l{w*0.06} {h*0.18} h-{w*0.28} z" fill="url(#{pfx}chrome)"/>'
    return f'<clipPath id="{pfx}m"><rect x="{x+w*0.05}" y="{y+w*0.05}" width="{w*0.9}" height="{h*0.78}" rx="{w*0.05}"/></clipPath>' + ped + body + scr + inner

def wordmark(h=160, dark=True, pfx="w"):
    ink = CHROME if dark else NAVY; bg = DEEP if dark else "#E6EBEF"
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1100 230" height="{h}">
{defs(pfx)}<rect width="1100" height="230" fill="{bg}"/>
{monitor(40, 36, 190, pfx)}
<text x="270" y="128" font-family="Michroma" font-size="62" fill="{ink}" letter-spacing="6">STATION</text>
<text x="270" y="196" font-family="Michroma" font-size="62" fill="url(#{pfx}sun)" letter-spacing="6">BREAK</text>
<line x1="270" y1="146" x2="1060" y2="146" stroke="{ink}" stroke-width="1.5" opacity=".5"/>
<text x="1060" y="214" text-anchor="end" font-family="Share Tech Mono" font-size="22" fill="{AMBER}" letter-spacing="6">BROADCAST SYSTEM · EST. ∞</text>
</svg>'''

def slate(state, w=1920, h=1080, pfx="s"):
    msg = {"tuning": "PLEASE STAND BY", "offair": "OFF THE AIR", "error": "NO SIGNAL"}[state]
    sub = {"tuning": "TUNING  ·  CH 212  ·  ORDER IN THE COURT", "offair": "ORDER IN THE COURT RESUMES 06:00", "error": "SERVER UNREACHABLE  ·  OK TO RETRY"}[state]
    horizon = 640
    squares = "".join(f'<rect x="{760+i*60}" y="{horizon+206}" width="40" height="6" fill="{AMBER if i<5 else CHROME}" opacity="{1 if i<5 else .35}"/>' for i in range(7)) if state == "tuning" else ""
    hud = f'<path d="M60 60 h80 M60 60 v80 M1860 60 h-80 M1860 60 v80 M60 1020 h80 M60 1020 v-80 M1860 1020 h-80 M1860 1020 v-80" stroke="{CHROME}" stroke-width="2" opacity=".5" fill="none"/>'
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1920 1080" width="{w}" height="{h}">
{defs(pfx)}
<rect width="1920" height="1080" fill="url(#{pfx}sky)"/>
{sun_bands(960, 560, 300, pfx, 7)}
<rect x="0" y="{horizon}" width="1920" height="440" fill="{DEEP}"/>
{grid_floor(300, 1620, horizon, 1080, 960, pfx, 16, 8)}
{spire(960, horizon+2, 520, pfx=pfx)}
{signal_bands(960, horizon-300, 3, 180, 90)}
<rect x="0" y="{horizon+40}" width="1920" height="90" fill="{DEEP}" opacity=".85"/>
<line x1="0" y1="{horizon+40}" x2="1920" y2="{horizon+40}" stroke="url(#{pfx}glow)" stroke-width="2"/>
<line x1="0" y1="{horizon+130}" x2="1920" y2="{horizon+130}" stroke="url(#{pfx}glow)" stroke-width="2"/>
<text x="960" y="{horizon+104}" text-anchor="middle" font-family="Michroma" font-size="58" fill="{CHROME}" letter-spacing="14">{msg}</text>
<text x="960" y="{horizon+190}" text-anchor="middle" font-family="Share Tech Mono" font-size="26" fill="{AMBER}" letter-spacing="5">{sub}</text>
{squares}{hud}
<text x="96" y="1000" font-family="Michroma" font-size="26" fill="{CHROME}" letter-spacing="6">STATION<tspan fill="{AMBER}">BREAK</tspan></text>
<text x="1824" y="1000" text-anchor="end" font-family="Share Tech Mono" font-size="26" fill="{CHROME}" opacity=".8">{"21:47" if state!="offair" else "02:14"}</text>
</svg>'''

def splash(frame, w=470, h=264, pfx="p"):
    pfx = f"{pfx}{frame}"; horizon = 640
    sun = sun_bands(960, 560 + (0 if frame >= 2 else 260), 300, pfx, 7) if frame >= 1 else ""
    grid = grid_floor(300, 1620, horizon, 1080, 960, pfx, 16, 8) if frame >= 2 else ""
    sp = spire(960, horizon+2, 520, pfx=pfx) if frame >= 3 else ""
    title = f'<text x="960" y="{horizon+110}" text-anchor="middle" font-family="Michroma" font-size="64" fill="{CHROME}" letter-spacing="16">STATION<tspan fill="{AMBER}">BREAK</tspan></text>' if frame >= 4 else ""
    cap = ["0.0s · sun below the horizon, sky only", "0.6s · sun rises, grid draws toward you", "1.2s · spire rises, beacon lights", "1.8s · title tracks in; hold; cut to guide"][frame-1]
    return f'''<div class="fr"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1920 1080" width="{w}" height="{h}">{defs(pfx)}<rect width="1920" height="1080" fill="url(#{pfx}sky)"/>{sun}<rect x="0" y="{horizon}" width="1920" height="440" fill="{DEEP}"/>{grid}{sp}{title}</svg><div class="cap">{cap}</div></div>'''

html = f'''<!doctype html><meta charset="utf-8"><title>StationBreak brand — direction C: retro-futurist</title>
<style>
@font-face{{font-family:"Michroma";src:url(data:font/woff;base64,{MICH}) format("woff")}}
@font-face{{font-family:"Share Tech Mono";src:url(data:font/woff;base64,{STM}) format("woff")}}
body{{margin:0;background:#0e0f11;color:#fff;font:14px Inter,system-ui;padding:40px}}
h1{{font-size:18px;font-weight:600;margin:0 0 4px}} h2{{font-size:14px;font-weight:600;color:rgba(255,255,255,.7);margin:40px 0 12px}} p{{color:rgba(255,255,255,.55);margin:0 0 20px;max-width:900px;line-height:1.45}}
.row{{display:flex;gap:28px;align-items:flex-end;flex-wrap:wrap}} .lbl{{font-size:12px;color:rgba(255,255,255,.45);margin-top:6px;text-align:center}}
.card{{background:rgba(255,255,255,.04);padding:24px;border-radius:4px;display:inline-block}}
.fr .cap{{font-size:12px;color:rgba(255,255,255,.5);margin-top:6px}}
</style>
<h1>StationBreak brand — direction C: retro-futurist (Syd Mead)</h1>
<p>The curveball. Less memory, more tomorrow: a low banded sun over a perspective grid, a chrome broadcast spire with a lit beacon, a wedge-shaped concept monitor on a pedestal. Michroma (Eurostile Extended) for the wordmark, Share Tech Mono for labels. Palette: deep navy to teal sky, chrome, amber-to-magenta sun. The guide's orange is the sun's core, so the two worlds still share a colour.</p>

<h2>Icon</h2>
<div class="row">
<div><div class="card">{icon(512, "a")}</div><div class="lbl">512</div></div>
<div><div class="card">{icon(240, "b")}</div><div class="lbl">240 · Fire TV tile</div></div>
<div><div class="card">{icon(120, "c")}</div><div class="lbl">120</div></div>
</div>

<h2>Wordmark with the concept monitor</h2>
<div class="row">
<div>{wordmark(170, True, "wd")}<div class="lbl">on deep</div></div>
<div>{wordmark(170, False, "wl")}<div class="lbl">on light</div></div>
</div>

<h2>Slates</h2>
<div class="row">
<div>{slate("tuning", 900, 506, "s1")}<div class="lbl">tuning</div></div>
<div>{slate("offair", 900, 506, "s2")}<div class="lbl">off the air</div></div>
</div>
<div class="row" style="margin-top:20px"><div>{slate("error", 900, 506, "s3")}<div class="lbl">no signal</div></div></div>

<h2>Splash — storyboard</h2>
<div class="row">{splash(1)}{splash(2)}{splash(3)}{splash(4)}</div>
'''
(HERE / "brand-sheet-3.html").write_text(html)
print("ok")
