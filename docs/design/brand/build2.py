#!/usr/bin/env python3
"""StationBreak brand, second pass: illustrated, deco, warm. Writes brand-sheet-2.html."""
import base64, glob, math, pathlib
HERE = pathlib.Path(__file__).parent
F = HERE.parent / "fonts"
def b64(pat): return base64.b64encode(pathlib.Path(glob.glob(str(F / pat))[0]).read_bytes()).decode()
LIME = b64("fontsource-limelight-*/package/files/limelight-latin-400-normal.woff")
BOWL = b64("fontsource-bowlby-one-*/package/files/bowlby-one-latin-400-normal.woff")
JOSE = b64("fontsource-josefin-sans-*/package/files/josefin-sans-latin-700-normal.woff")
MONO = b64("fontsource-space-mono-*/package/files/space-mono-latin-700-normal.woff")

INK, CREAM, ACCENT, PAPER, GOLD, SKY = "#1C1B19", "#F3EEE3", "#E8642C", "#E9DFC9", "#D9A441", "#9DB3B8"

def halftone(id_, color, op):
    return f'<pattern id="{id_}" width="10" height="10" patternUnits="userSpaceOnUse" patternTransform="rotate(30)"><circle cx="5" cy="5" r="1.6" fill="{color}" opacity="{op}"/></pattern>'

def lattice_tower(x, base_y, h, w, stroke, color, beacon=ACCENT):
    """A proper lattice mast: two legs, horizontal bars, X braces, a platform and a beacon."""
    top = base_y - h; parts = []
    n = 6
    for i in range(n + 1):
        t = i / n; y = base_y - t * h; hw = (w / 2) * (1 - 0.82 * t) + 3
        parts.append(f'<line x1="{x-hw:.1f}" y1="{y:.1f}" x2="{x+hw:.1f}" y2="{y:.1f}" stroke="{color}" stroke-width="{stroke}"/>')
        if i < n:
            t2 = (i + 1) / n; y2 = base_y - t2 * h; hw2 = (w / 2) * (1 - 0.82 * t2) + 3
            parts.append(f'<line x1="{x-hw:.1f}" y1="{y:.1f}" x2="{x+hw2:.1f}" y2="{y2:.1f}" stroke="{color}" stroke-width="{stroke*0.8}"/>')
            parts.append(f'<line x1="{x+hw:.1f}" y1="{y:.1f}" x2="{x-hw2:.1f}" y2="{y2:.1f}" stroke="{color}" stroke-width="{stroke*0.8}"/>')
    legs = f'<path d="M{x-w/2-3:.1f} {base_y} L{x-3:.1f} {top-6} M{x+w/2+3:.1f} {base_y} L{x+3:.1f} {top-6}" stroke="{color}" stroke-width="{stroke*1.3}" fill="none" stroke-linecap="round"/>'
    mast = f'<line x1="{x}" y1="{top-6}" x2="{x}" y2="{top-h*0.16}" stroke="{color}" stroke-width="{stroke*1.2}" stroke-linecap="round"/>'
    bea = f'<circle cx="{x}" cy="{top-h*0.16}" r="{stroke*2.2}" fill="{beacon}"/>'
    return "".join(parts) + legs + mast + bea

def waves(cx, cy, r0, n, gap, color, sw, span_deg=34, fade=True):
    out = []
    for i in range(n):
        r = r0 + i * gap; a = math.radians(span_deg)
        op = 1 - (i / n) * 0.55 if fade else 1
        for side in (1, -1):
            sx = cx - side * r * math.cos(a); sy = cy - r * math.sin(a); ey = cy + r * math.sin(a); sweep = 0 if side > 0 else 1
            out.append(f'<path d="M{sx:.1f} {sy:.1f} A{r} {r} 0 0 {sweep} {sx:.1f} {ey:.1f}" fill="none" stroke="{color}" stroke-width="{sw}" stroke-linecap="round" opacity="{op:.2f}"/>')
    return "".join(out)

def vintage_tv(x, y, w, body, screen, detail, ears=True, knobs=True, grille=True):
    """A bakelite set: rounded body, CRT screen with bulged corners, dial strip, rabbit ears."""
    h = w * 0.78; r = w * 0.09
    s = f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{body}"/>'
    # screen with CRT-ish corners (rounded rect + slight inset)
    sx, sy, sw_, sh = x + w*0.07, y + w*0.07, w*0.66, h*0.72
    s += f'<rect x="{sx}" y="{sy}" width="{sw_}" height="{sh}" rx="{w*0.085}" fill="{screen}"/>'
    # control strip on the right
    cx = x + w*0.86
    if knobs:
        for ky in (sy + sh*0.22, sy + sh*0.52):
            s += f'<circle cx="{cx}" cy="{ky}" r="{w*0.05}" fill="none" stroke="{detail}" stroke-width="{w*0.018}"/><circle cx="{cx}" cy="{ky}" r="{w*0.012}" fill="{detail}"/>'
    if grille:
        for i in range(5):
            gy = sy + sh*0.70 + i * w*0.028
            s += f'<line x1="{cx-w*0.055}" y1="{gy}" x2="{cx+w*0.055}" y2="{gy}" stroke="{detail}" stroke-width="{w*0.012}" stroke-linecap="round"/>'
    # feet
    s += f'<rect x="{x+w*0.16}" y="{y+h}" width="{w*0.09}" height="{w*0.06}" rx="{w*0.012}" fill="{body}"/><rect x="{x+w*0.75}" y="{y+h}" width="{w*0.09}" height="{w*0.06}" rx="{w*0.012}" fill="{body}"/>'
    if ears:
        ex, ey = x + w*0.40, y
        s += f'<path d="M{ex} {ey} L{ex-w*0.16} {ey-w*0.26} M{ex} {ey} L{ex+w*0.14} {ey-w*0.27}" stroke="{body}" stroke-width="{w*0.025}" stroke-linecap="round" fill="none"/><circle cx="{ex}" cy="{ey}" r="{w*0.04}" fill="{body}"/>'
    return s, (sx, sy, sw_, sh)

def icon(size=512, dark=False):
    bg = INK if dark else PAPER; body = CREAM if dark else INK; screen = INK if dark else CREAM; detail = INK if dark else CREAM
    tower_col = CREAM if dark else INK
    tv, (sx, sy, sw_, sh) = vintage_tv(70, 124, 372, body, screen, detail)
    cx = sx + sw_/2; base = sy + sh - 14
    inner = lattice_tower(cx, base, sh*0.78, sw_*0.30, 4.5, tower_col) + waves(cx, sy + sh*0.52, 64, 3, 26, ACCENT, 9)
    rays = "".join(f'<line x1="{cx}" y1="{sy+sh*0.52}" x2="{cx+420*math.cos(math.radians(a))}" y2="{sy+sh*0.52+420*math.sin(math.radians(a))}" stroke="{tower_col}" stroke-width="22" opacity=".06"/>' for a in range(-170, 10, 20))
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="{size}" height="{size}">
<defs>{halftone("ht", INK if not dark else CREAM, .10)}<clipPath id="scr"><rect x="{sx}" y="{sy}" width="{sw_}" height="{sh}" rx="{372*0.085}"/></clipPath></defs>
<rect width="512" height="512" rx="110" fill="{bg}"/>
<rect width="512" height="512" rx="110" fill="url(#ht)"/>
{tv}
<g clip-path="url(#scr)">{rays}{inner}</g>
</svg>'''

def wordmark(face, h=160, dark=False, ears=True):
    ink = CREAM if dark else INK; screen = INK if dark else CREAM
    tv, (sx, sy, sw_, sh) = vintage_tv(10, 60, 150, ink, screen, screen, ears=ears)
    cx = sx + sw_/2
    inner = lattice_tower(cx, sy + sh - 6, sh*0.78, sw_*0.30, 2.2, ink) + waves(cx, sy + sh*0.52, 24, 2, 11, ACCENT, 4.5)
    if face == "limelight":
        text = f'<text x="190" y="158" font-family="Limelight" font-size="92" fill="{ink}" letter-spacing="2">Station Break</text>'
        sub = f'<text x="194" y="192" font-family="Josefin Sans" font-weight="700" font-size="22" fill="{ACCENT}" letter-spacing="9">ALWAYS ON THE AIR</text>'
    elif face == "bowlby":
        text = f'<text x="190" y="162" font-family="Bowlby One" font-size="90" fill="{ink}">Station Break</text>'
        sub = f'<text x="194" y="192" font-family="Josefin Sans" font-weight="700" font-size="22" fill="{ACCENT}" letter-spacing="9">ALWAYS ON THE AIR</text>'
    else:
        text = f'<text x="190" y="160" font-family="Josefin Sans" font-weight="700" font-size="84" fill="{ink}" letter-spacing="3">STATION BREAK</text>'
        sub = f'<text x="194" y="192" font-family="Josefin Sans" font-weight="700" font-size="22" fill="{ACCENT}" letter-spacing="9">ALWAYS ON THE AIR</text>'
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1060 230" height="{h}">
<defs><clipPath id="wscr{face}{dark}"><rect x="{sx}" y="{sy}" width="{sw_}" height="{sh}" rx="{150*0.085}"/></clipPath></defs>
{tv}<g clip-path="url(#wscr{face}{dark})">{inner}</g>{text}{sub}</svg>'''

def slate(state, w=1920, h=1080):
    msg = {"tuning": "PLEASE STAND BY", "offair": "OFF THE AIR", "error": "NO SIGNAL"}[state]
    sub = {"tuning": "TUNING CHANNEL 212  ·  ORDER IN THE COURT",
           "offair": "ORDER IN THE COURT RETURNS AT 6:00 AM",
           "error": "CAN'T REACH YOUR JELLYFIN SERVER  ·  PRESS OK TO RETRY"}[state]
    cx, horizon = 960, 690
    rays = "".join(f'<path d="M{cx} {horizon-330} L{cx+1400*math.cos(math.radians(a)):.0f} {horizon-330+1400*math.sin(math.radians(a)):.0f} L{cx+1400*math.cos(math.radians(a+6)):.0f} {horizon-330+1400*math.sin(math.radians(a+6)):.0f} Z" fill="{INK}" opacity=".05"/>' for a in range(-178, 0, 12))
    mountains = f'<path d="M0 {horizon} L180 {horizon-70} L330 {horizon-30} L480 {horizon-110} L640 {horizon-40} L760 {horizon-80} L860 {horizon-20} L1060 {horizon-20} L1180 {horizon-95} L1330 {horizon-35} L1500 {horizon-120} L1680 {horizon-50} L1800 {horizon-85} L1920 {horizon-30} L1920 {horizon} Z" fill="{INK}" opacity=".18"/>'
    ground = f'<rect x="0" y="{horizon}" width="1920" height="{1080-horizon}" fill="{INK}" opacity=".10"/>'
    tower = lattice_tower(cx, horizon, 330, 150, 5, INK) + waves(cx, horizon-180, 120, 3, 48, ACCENT, 9)
    squares = "".join(f'<rect x="{770+i*58}" y="{horizon+196}" width="36" height="36" fill="{ACCENT if i<5 else "none"}" stroke="{INK}" stroke-width="3"/>' for i in range(7)) if state == "tuning" else ""
    brackets = f'<path d="M40 100 v-60 h60 M1880 100 v-60 h-60 M40 980 v60 h60 M1880 980 v60 h-60" stroke="{INK}" stroke-width="4" fill="none" opacity=".6"/>'
    band_y = horizon + 24
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1920 1080" width="{w}" height="{h}">
<defs>{halftone("sht", INK, .08)}<radialGradient id="vig" cx=".5" cy=".45" r=".75"><stop offset="0" stop-color="{PAPER}"/><stop offset="1" stop-color="#C9BEA6"/></radialGradient></defs>
<rect width="1920" height="1080" fill="url(#vig)"/><rect width="1920" height="1080" fill="url(#sht)"/>
{rays}{mountains}{ground}{tower}
<rect x="0" y="{band_y}" width="1920" height="118" fill="{INK}"/>
<text x="960" y="{band_y+86}" text-anchor="middle" font-family="Limelight" font-size="84" fill="{CREAM}" letter-spacing="10">{msg}</text>
<text x="960" y="{band_y+156}" text-anchor="middle" font-family="Space Mono" font-weight="700" font-size="24" fill="{INK}" letter-spacing="3" opacity=".85">{sub}</text>
{squares}{brackets}
<text x="96" y="990" font-family="Limelight" font-size="40" fill="{INK}" letter-spacing="2">Station Break</text>
<text x="1824" y="990" text-anchor="end" font-family="Space Mono" font-size="24" fill="{INK}" opacity=".7">{"9:47 PM" if state!="offair" else "2:14 AM"}</text>
</svg>'''

def splash(frame, w=470, h=264):
    cx, horizon = 960, 700
    rays = "".join(f'<path d="M{cx} {horizon-330} L{cx+1400*math.cos(math.radians(a)):.0f} {horizon-330+1400*math.sin(math.radians(a)):.0f} L{cx+1400*math.cos(math.radians(a+6)):.0f} {horizon-330+1400*math.sin(math.radians(a+6)):.0f} Z" fill="{CREAM}" opacity=".05"/>' for a in range(-178, 0, 12))
    tower = lattice_tower(cx, horizon, 330, 150, 5, CREAM)
    w1 = waves(cx, horizon-180, 120, 1, 48, ACCENT, 9); w3 = waves(cx, horizon-180, 120, 3, 48, ACCENT, 9)
    title = f'<text x="960" y="{horizon+120}" text-anchor="middle" font-family="Limelight" font-size="96" fill="{CREAM}" letter-spacing="6">Station Break</text>'
    sub = f'<text x="960" y="{horizon+170}" text-anchor="middle" font-family="Josefin Sans" font-weight="700" font-size="26" fill="{ACCENT}" letter-spacing="12">ALWAYS ON THE AIR</text>'
    body = (rays if frame >= 2 else "") + tower + (w1 if frame == 2 else "") + (w3 if frame >= 3 else "") + (title if frame >= 3 else "") + (sub if frame >= 4 else "")
    cap = ["0.0s · the mast, beacon lit", "0.5s · rays fade up, first wave", "1.0s · waves ring out, title letter-spaces in", "1.6s · tagline; hold, then dissolve to the guide"][frame-1]
    return f'<div class="fr"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1920 1080" width="{w}" height="{h}"><rect width="1920" height="1080" fill="{INK}"/>{body}</svg><div class="cap">{cap}</div></div>'

html = f'''<!doctype html><meta charset="utf-8"><title>StationBreak brand — second pass</title>
<style>
@font-face{{font-family:"Limelight";src:url(data:font/woff;base64,{LIME}) format("woff")}}
@font-face{{font-family:"Bowlby One";src:url(data:font/woff;base64,{BOWL}) format("woff")}}
@font-face{{font-family:"Josefin Sans";font-weight:700;src:url(data:font/woff;base64,{JOSE}) format("woff")}}
@font-face{{font-family:"Space Mono";font-weight:700;src:url(data:font/woff;base64,{MONO}) format("woff")}}
body{{margin:0;background:#0e0f11;color:#fff;font:14px Inter,system-ui;padding:40px}}
h1{{font-size:18px;font-weight:600;margin:0 0 4px}} h2{{font-size:14px;font-weight:600;color:rgba(255,255,255,.7);margin:40px 0 12px}} p{{color:rgba(255,255,255,.55);margin:0 0 20px;max-width:900px;line-height:1.45}}
.row{{display:flex;gap:28px;align-items:flex-end;flex-wrap:wrap}} .lbl{{font-size:12px;color:rgba(255,255,255,.45);margin-top:6px;text-align:center}}
.card{{background:rgba(255,255,255,.04);padding:24px;border-radius:4px;display:inline-block}} .paper{{background:{PAPER}}} .ink{{background:{INK}}}
.fr .cap{{font-size:12px;color:rgba(255,255,255,.5);margin-top:6px}}
</style>
<h1>StationBreak brand — second pass: illustrated, deco, warm</h1>
<p>Back toward the references. The set is a bakelite console with a CRT screen, dials, a speaker grille and rabbit ears; the tower is a riveted lattice mast with a lit beacon; paper, halftone and deco sunrays carry the texture. Type: Limelight (deco, the Sky Captain register) with Josefin Sans for small caps; Space Mono stays for teletype labels on slates. None of these faces are used by any channel mark.</p>

<h2>Icon</h2>
<div class="row">
<div><div class="card">{icon(512)}</div><div class="lbl">512 · paper</div></div>
<div><div class="card">{icon(512, True)}</div><div class="lbl">512 · ink</div></div>
<div><div class="card">{icon(240)}</div><div class="lbl">240 · about the size of a Fire TV tile</div></div>
<div><div class="card">{icon(120, True)}</div><div class="lbl">120</div></div>
</div>

<h2>Wordmark — three type directions, same set</h2>
<div class="row">
<div><div class="card paper">{wordmark("limelight")}</div><div class="lbl">A · Limelight — deco, Sky Captain</div></div>
<div><div class="card paper">{wordmark("bowlby")}</div><div class="lbl">B · Bowlby One — chunky 50s sign</div></div>
</div>
<div class="row" style="margin-top:20px">
<div><div class="card paper">{wordmark("josefin")}</div><div class="lbl">C · Josefin Sans — geometric 1930s caps</div></div>
<div><div class="card ink">{wordmark("limelight", dark=True)}</div><div class="lbl">A on ink</div></div>
</div>

<h2>Slates — the station break itself</h2>
<p>Sunrays behind the mast, a low horizon with hills, the lattice tower from the icon, waves ringing out, and the message on one band. Corner brackets from your first concept. No disc.</p>
<div class="row">
<div>{slate("tuning", 900, 506)}<div class="lbl">tuning</div></div>
<div>{slate("offair", 900, 506)}<div class="lbl">off the air</div></div>
</div>
<div class="row" style="margin-top:20px"><div>{slate("error", 900, 506)}<div class="lbl">no signal</div></div></div>

<h2>Splash — storyboard</h2>
<div class="row">{splash(1)}{splash(2)}{splash(3)}{splash(4)}</div>
'''
(HERE / "brand-sheet-2.html").write_text(html)
print("ok")
