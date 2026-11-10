#!/usr/bin/env python3
"""StationBreak app brand — sample sheet builder. Writes brand-sheet.html (self-contained)."""
import base64, glob, pathlib
HERE = pathlib.Path(__file__).parent
F = HERE.parent / "fonts"
def b64(p): return base64.b64encode(pathlib.Path(p).read_bytes()).decode()
AB = b64(glob.glob(str(F / "fontsource-archivo-black-*/package/files/archivo-black-latin-400-normal.woff"))[0])
SM = b64(glob.glob(str(F / "fontsource-space-mono-*/package/files/space-mono-latin-700-normal.woff"))[0])
SM4 = b64(glob.glob(str(F / "fontsource-space-mono-*/package/files/space-mono-latin-400-normal.woff"))[0])

INK, CREAM, ACCENT, GREY = "#1C1B19", "#F3EEE3", "#E8642C", "#CFCBC2"

# ---------------- icon ----------------
def icon(bg, body, screen, tower, wave, size=512, radius=True):
    """Vintage set: rounded body, inset screen, two feet; one solid tower, two wave pairs."""
    r = 'rx="112"' if radius else 'rx="0"'
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="{size}" height="{size}">
<rect width="512" height="512" {r} fill="{bg}"/>
<!-- body -->
<rect x="56" y="84" width="400" height="316" rx="56" fill="{body}"/>
<!-- screen -->
<rect x="96" y="124" width="320" height="236" rx="38" fill="{screen}"/>
<!-- feet -->
<rect x="140" y="400" width="44" height="34" rx="6" fill="{body}"/>
<rect x="328" y="400" width="44" height="34" rx="6" fill="{body}"/>
<!-- tower: solid silhouette with a mast and beacon -->
<path d="M256 160 L300 352 L212 352 Z" fill="{tower}"/>
<rect x="250" y="134" width="12" height="40" rx="3" fill="{tower}"/>
<circle cx="256" cy="134" r="13" fill="{wave}"/>
<!-- waves -->
<path d="M180 203 A96 96 0 0 0 180 321" fill="none" stroke="{wave}" stroke-width="18" stroke-linecap="round"/>
<path d="M332 203 A96 96 0 0 1 332 321" fill="none" stroke="{wave}" stroke-width="18" stroke-linecap="round"/>
<path d="M143 173 A144 144 0 0 0 143 351" fill="none" stroke="{wave}" stroke-width="18" stroke-linecap="round" opacity=".55"/>
<path d="M369 173 A144 144 0 0 1 369 351" fill="none" stroke="{wave}" stroke-width="18" stroke-linecap="round" opacity=".55"/>
</svg>'''

ICON_LIGHT = icon(CREAM, INK, CREAM, INK, ACCENT)
ICON_DARK  = icon(INK, CREAM, INK, CREAM, ACCENT)

# ---------------- wordmark ----------------
def wordmark_stacked(ink, cream, accent, h=200):
    # STATION over a band with BREAK knocked out
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 420 200" height="{h}">
<text x="0" y="86" font-family="Archivo Black" font-size="84" fill="{ink}" letter-spacing="-1">STATION</text>
<rect x="0" y="104" width="420" height="90" fill="{accent}"/>
<text x="18" y="180" font-family="Archivo Black" font-size="92" fill="{cream}" letter-spacing="3">BREAK</text>
</svg>'''

def wordmark_line(ink, cream, accent, h=80):
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 980 100" height="{h}">
<text x="0" y="86" font-family="Archivo Black" font-size="98" fill="{ink}" letter-spacing="-1">STATION</text>
<rect x="540" y="4" width="440" height="96" fill="{accent}"/>
<text x="564" y="86" font-family="Archivo Black" font-size="98" fill="{cream}" letter-spacing="3">BREAK</text>
</svg>'''

# ---------------- slate ----------------
def slate(state, w=1920, h=1080):
    """Band slate. state: tuning | offair | error. Paper ground, horizontal bands, hairline timeline,
    one heavy band with the message, the icon's tower above it, mono label below."""
    msg = {"tuning": "PLEASE STAND BY", "offair": "OFF AIR", "error": "NO SIGNAL"}[state]
    sub = {"tuning": "TUNING CHANNEL 212 · ORDER IN THE COURT",
           "offair": "ORDER IN THE COURT RETURNS AT 6:00 AM",
           "error": "CAN'T REACH YOUR JELLYFIN SERVER · PRESS OK TO RETRY"}[state]
    squares = "".join(f'<rect x="{760+i*60}" y="760" width="40" height="40" fill="{ACCENT if i<5 else "none"}" stroke="{INK}" stroke-width="4"/>' for i in range(7)) if state == "tuning" else ""
    clock = {"tuning": "9:47 PM", "offair": "2:14 AM", "error": "9:47 PM"}[state]
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1920 1080" width="{w}" height="{h}">
<defs><linearGradient id="paper" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#D9D5CC"/><stop offset="1" stop-color="#C4BFB4"/></linearGradient></defs>
<rect width="1920" height="1080" fill="url(#paper)"/>
<!-- test-card bands: the guide's row grammar at slate scale -->
<rect x="0" y="120" width="1920" height="60" fill="{INK}" opacity=".06"/>
<rect x="0" y="180" width="1920" height="60" fill="{INK}" opacity=".10"/>
<rect x="0" y="240" width="1920" height="60" fill="{INK}" opacity=".06"/>
<rect x="0" y="780" width="1920" height="60" fill="{INK}" opacity=".06"/>
<rect x="0" y="840" width="1920" height="60" fill="{INK}" opacity=".10"/>
<rect x="0" y="900" width="1920" height="60" fill="{INK}" opacity=".06"/>
<!-- hairline timeline with a now marker -->
<line x1="0" y1="300" x2="1920" y2="300" stroke="{INK}" stroke-width="2" opacity=".35"/>
<g fill="{INK}" opacity=".35" font-family="Space Mono" font-size="22">
<text x="240" y="332">9:00 PM</text><text x="720" y="332">9:30 PM</text><text x="1200" y="332">10:00 PM</text><text x="1680" y="332">10:30 PM</text></g>
<path d="M988 292 l12 -16 l12 16 z" fill="{ACCENT}"/>
<!-- tower (from the icon) -->
<path d="M960 372 L1012 600 L908 600 Z" fill="{INK}"/>
<rect x="953" y="344" width="14" height="44" rx="3" fill="{INK}"/>
<circle cx="960" cy="342" r="15" fill="{ACCENT}"/>
<path d="M873 410 A110 110 0 0 0 873 546" fill="none" stroke="{ACCENT}" stroke-width="16" stroke-linecap="round"/>
<path d="M1047 410 A110 110 0 0 1 1047 546" fill="none" stroke="{ACCENT}" stroke-width="16" stroke-linecap="round"/>
<path d="M834 379 A160 160 0 0 0 834 577" fill="none" stroke="{ACCENT}" stroke-width="16" stroke-linecap="round" opacity=".5"/>
<path d="M1086 379 A160 160 0 0 1 1086 577" fill="none" stroke="{ACCENT}" stroke-width="16" stroke-linecap="round" opacity=".5"/>
<!-- the band -->
<rect x="0" y="600" width="1920" height="130" fill="{INK}"/>
<text x="960" y="700" text-anchor="middle" font-family="Archivo Black" font-size="96" fill="{CREAM}" letter-spacing="6">{msg}</text>
<!-- label + progress -->
<text x="960" y="{'828' if state=='tuning' else '790'}" text-anchor="middle" font-family="Space Mono" font-weight="700" font-size="26" fill="{INK}" letter-spacing="3">{sub}</text>
{squares}
<!-- corner wordmark -->
<g transform="translate(96 940) scale(.5)">
<text x="0" y="86" font-family="Archivo Black" font-size="84" fill="{INK}" letter-spacing="-1">STATION</text>
<rect x="0" y="104" width="420" height="90" fill="{ACCENT}"/>
<text x="18" y="180" font-family="Archivo Black" font-size="92" fill="{CREAM}" letter-spacing="3">BREAK</text></g>
<text x="1824" y="1000" text-anchor="end" font-family="Space Mono" font-size="26" fill="{INK}" opacity=".7">{clock}</text>
</svg>'''

# ---------------- splash storyboard (4 frames) ----------------
def splash(frame, w=480, h=270):
    f = frame
    tower = f'''<path d="M960 372 L1012 600 L908 600 Z" fill="{CREAM}"/><rect x="953" y="344" width="14" height="44" rx="3" fill="{CREAM}"/><circle cx="960" cy="342" r="15" fill="{ACCENT}"/>'''
    w1 = f'''<path d="M873 410 A110 110 0 0 0 873 546" fill="none" stroke="{ACCENT}" stroke-width="16" stroke-linecap="round"/><path d="M1047 410 A110 110 0 0 1 1047 546" fill="none" stroke="{ACCENT}" stroke-width="16" stroke-linecap="round"/>'''
    w2 = f'''<path d="M834 379 A160 160 0 0 0 834 577" fill="none" stroke="{ACCENT}" stroke-width="16" stroke-linecap="round" opacity=".5"/><path d="M1086 379 A160 160 0 0 1 1086 577" fill="none" stroke="{ACCENT}" stroke-width="16" stroke-linecap="round" opacity=".5"/>'''
    band = f'''<rect x="0" y="600" width="1920" height="130" fill="{ACCENT}"/>'''
    word = f'''<text x="960" y="700" text-anchor="middle" font-family="Archivo Black" font-size="96" fill="{CREAM}" letter-spacing="6">STATION BREAK</text>'''
    body = tower + (w1 if f >= 2 else "") + (w2 if f >= 3 else "") + (band if f >= 3 else "") + (word if f >= 4 else "")
    cap = ["0.0s · tower on ink", "0.4s · first wave pulses", "0.9s · band slides in", "1.3s · wordmark resolves, then dissolves to the guide"][f-1]
    return f'''<div class="fr"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1920 1080" width="{w}" height="{h}"><rect width="1920" height="1080" fill="{INK}"/>{body}</svg><div class="cap">{cap}</div></div>'''

html = f'''<!doctype html><meta charset="utf-8"><title>StationBreak brand — sample sheet</title>
<style>
@font-face{{font-family:"Archivo Black";src:url(data:font/woff;base64,{AB}) format("woff")}}
@font-face{{font-family:"Space Mono";font-weight:700;src:url(data:font/woff;base64,{SM}) format("woff")}}
@font-face{{font-family:"Space Mono";font-weight:400;src:url(data:font/woff;base64,{SM4}) format("woff")}}
body{{margin:0;background:#0e0f11;color:#fff;font:14px Inter,system-ui;padding:40px}}
h1{{font-size:18px;font-weight:600;margin:0 0 4px}} h2{{font-size:14px;font-weight:600;color:rgba(255,255,255,.7);margin:40px 0 12px}} p{{color:rgba(255,255,255,.55);margin:0 0 20px;max-width:900px;line-height:1.45}}
.row{{display:flex;gap:28px;align-items:flex-end;flex-wrap:wrap}} .lbl{{font-size:12px;color:rgba(255,255,255,.45);margin-top:6px;text-align:center}}
.card{{background:rgba(255,255,255,.04);padding:24px;border-radius:4px;display:inline-block}}
.cream{{background:{CREAM}}} .ink{{background:{INK}}}
.fr .cap{{font-size:12px;color:rgba(255,255,255,.5);margin-top:6px}}
.banner{{width:640px;height:360px;background:{INK};position:relative;overflow:hidden}}
</style>
<h1>StationBreak brand — sample sheet</h1>
<p>Operator identity, distinct from the 95 channel brands: Archivo Black + Space Mono (already bundled in core:ui as BroadcastType), ink/cream/accent/grey (BroadcastColors). The device is the band — the guide's row grammar at brand scale.</p>

<h2>Icon — reduced for launcher sizes</h2>
<div class="row">
<div><div class="card">{ICON_LIGHT}</div><div class="lbl">512 · cream</div></div>
<div><div class="card">{ICON_DARK}</div><div class="lbl">512 · ink</div></div>
<div><div class="card">{icon(CREAM, INK, CREAM, INK, ACCENT, 192)}</div><div class="lbl">192</div></div>
<div><div class="card">{icon(INK, CREAM, INK, CREAM, ACCENT, 96)}</div><div class="lbl">96</div></div>
<div><div class="card">{icon(CREAM, INK, CREAM, INK, ACCENT, 48)}</div><div class="lbl">48</div></div>
</div>

<h2>Wordmark</h2>
<div class="row">
<div><div class="card cream">{wordmark_stacked(INK, CREAM, ACCENT)}</div><div class="lbl">stacked · on cream</div></div>
<div><div class="card ink">{wordmark_stacked(CREAM, CREAM, ACCENT)}</div><div class="lbl">stacked · on ink</div></div>
<div><div class="card ink">{wordmark_stacked(CREAM, INK, CREAM).replace(f'fill="{INK}" letter-spacing="2"', f'fill="{INK}" letter-spacing="2"')}</div><div class="lbl">stacked · one colour (mono)</div></div>
</div>
<div class="row" style="margin-top:20px">
<div><div class="card cream">{wordmark_line(INK, CREAM, ACCENT)}</div><div class="lbl">single line · for the Fire TV banner and headers</div></div>
</div>

<h2>Fire TV banner (1280×720, shown at half)</h2>
<div class="banner">
<div style="position:absolute;left:40px;top:60px">{icon(INK, CREAM, INK, CREAM, ACCENT, 240, radius=False).replace(f'<rect width="512" height="512" rx="0" fill="{INK}"/>','')}</div>
<div style="position:absolute;left:300px;top:112px">{wordmark_stacked(CREAM, CREAM, ACCENT, 136)}</div>
<div style="position:absolute;left:300px;top:270px;font-family:'Space Mono';font-size:13px;letter-spacing:3px;color:{GREY}">YOUR LIBRARY · ALWAYS ON</div>
</div>

<h2>Slates — one construction, three states</h2>
<p>Paper ground, faint test-card bands, a hairline timeline with the now marker, the icon's tower, and one heavy band. The disc from the concept is gone so this is unmistakably StationBreak's, not a loading card from somewhere else.</p>
<div class="row">
<div>{slate("tuning", 880, 495)}<div class="lbl">tuning</div></div>
<div>{slate("offair", 880, 495)}<div class="lbl">off air</div></div>
</div>
<div class="row" style="margin-top:20px"><div>{slate("error", 880, 495)}<div class="lbl">no signal</div></div></div>

<h2>Splash — storyboard (≈1.6 s, then dissolve to the guide)</h2>
<div class="row">{splash(1)}{splash(2)}{splash(3)}{splash(4)}</div>
'''
(HERE / "brand-sheet.html").write_text(html)
print("ok", len(html)//1024, "KB")
