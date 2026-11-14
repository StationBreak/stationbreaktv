#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
"""Independent reference for studio fold 2 (docs/adr/0026-studio-fold-2.md).

Writes docs/rules-format/vectors/studio-fold-2.json, kid-hold-4.json and pool-v4.json (the pool of a file that says
formatVersion 3 and of one that says 4). The folds and the holds are written from the ADR's text alone (The sets, The
pass, Tests), not from the Kotlin code they check. Fold 1 is written from docs/rules-format.md, Studio names. The pool
model of pool-v4.json is written from ADR-0026, but after reading the app's SourceMatcher, so it is less independent.

Usage:
    studio_fold_2_vectors.py DerivedCoreProperties-15.1.0.txt OUT_DIR

The first argument is Unicode 15.1.0's DerivedCoreProperties.txt
(https://www.unicode.org/Public/15.1.0/ucd/DerivedCoreProperties.txt). The script checks list 2 of the dropped set
against its Default_Ignorable_Code_Point lines (less U+200C and U+200D) and stops if they differ.
"""

from __future__ import annotations

import hashlib
import json
import pathlib
import sys

ZWNJ, ZWJ = 0x200C, 0x200D

# https://www.unicode.org/Public/15.1.0/ucd/DerivedCoreProperties.txt: its first line and its SHA-256.
UCD_HEADER = "# DerivedCoreProperties-15.1.0.txt"
UCD_SHA256 = "f55d0db69123431a7317868725b1fcbf1eab6b265d756d1bd7f0f6d9f9ee108b"

# --- the sets, as the ADR lists them ---------------------------------------------------------------------------------

S = [(0x0020, 0x0020), (0x00A0, 0x00A0), (0x1680, 0x1680), (0x2000, 0x200A), (0x202F, 0x202F), (0x205F, 0x205F),
     (0x3000, 0x3000)]
B = [(0x0009, 0x000D), (0x001C, 0x001F), (0x0085, 0x0085), (0x2028, 0x2029), (0x115F, 0x1160), (0x2800, 0x2800),
     (0x3164, 0x3164), (0xFFA0, 0xFFA0)]
FOLD1_DROPPED = [(0x0000, 0x001F), (0x007F, 0x009F), (0x00AD, 0x00AD), (0x0600, 0x0605), (0x061C, 0x061C),
                 (0x06DD, 0x06DD), (0x070F, 0x070F), (0x0890, 0x0891), (0x08E2, 0x08E2), (0x180E, 0x180E),
                 (0x200B, 0x200B), (0x200E, 0x200F), (0x202A, 0x202E), (0x2060, 0x2064), (0x2066, 0x206F),
                 (0xFEFF, 0xFEFF), (0xFFF9, 0xFFFB), (0x110BD, 0x110BD), (0x110CD, 0x110CD), (0x13430, 0x1343F),
                 (0x1BCA0, 0x1BCA3), (0x1D173, 0x1D17A), (0xE0001, 0xE0001), (0xE0020, 0xE007F)]
DEFAULT_IGNORABLE = [(0x00AD, 0x00AD), (0x034F, 0x034F), (0x061C, 0x061C), (0x115F, 0x1160), (0x17B4, 0x17B5),
                     (0x180B, 0x180D), (0x180E, 0x180E), (0x180F, 0x180F), (0x200B, 0x200B), (0x200E, 0x200F),
                     (0x202A, 0x202E), (0x2060, 0x2064), (0x2065, 0x2065), (0x2066, 0x206F), (0x3164, 0x3164),
                     (0xFE00, 0xFE0F), (0xFEFF, 0xFEFF), (0xFFA0, 0xFFA0), (0xFFF0, 0xFFF8), (0x1BCA0, 0x1BCA3),
                     (0x1D173, 0x1D17A), (0xE0000, 0xE0000), (0xE0001, 0xE0001), (0xE0002, 0xE001F),
                     (0xE0020, 0xE007F), (0xE0080, 0xE00FF), (0xE0100, 0xE01EF), (0xE01F0, 0xE0FFF)]
EXTRA = [(0x2800, 0x2800)]
D = FOLD1_DROPPED + DEFAULT_IGNORABLE + EXTRA
G_EXTRA = [(0x0009, 0x000A), (0x000D, 0x000D), (0x2028, 0x2029), (0x115F, 0x1160), (0x2800, 0x2800), (0x3164, 0x3164),
           (0xFFA0, 0xFFA0)]
G = S + G_EXTRA
L = [(0x0000, 0x024F), (0x0300, 0x036F)]
FOLD1_SPACED = [(0x0009, 0x000A), (0x000D, 0x000D), (0x2028, 0x2029)]


def inside(cp: int, ranges) -> bool:
    return any(lo <= cp <= hi for lo, hi in ranges)


def in_r(cp: int) -> bool:
    return inside(cp, S) or inside(cp, B) or inside(cp, D)


def check_default_ignorable(path: pathlib.Path) -> None:
    data = path.read_bytes()
    text = data.decode("utf-8")
    header = text.split("\n", 1)[0].rstrip("\r")
    if header != UCD_HEADER:
        sys.exit(f"{path} is not Unicode 15.1.0 DerivedCoreProperties.txt: its first line is {header!r}, not {UCD_HEADER!r}")
    digest = hashlib.sha256(data).hexdigest()
    if digest != UCD_SHA256:
        sys.exit(f"{path} has SHA-256 {digest}, not the pinned {UCD_SHA256}: download it again from unicode.org")
    expected = set()
    for line in text.splitlines():
        body = line.split("#", 1)[0].strip()
        if not body:
            continue
        parts = [part.strip() for part in body.split(";")]
        if len(parts) != 2 or parts[1] != "Default_Ignorable_Code_Point":
            continue
        cps = parts[0]
        lo, _, hi = cps.partition("..")
        expected.update(range(int(lo, 16), int(hi or lo, 16) + 1))
    expected -= {ZWNJ, ZWJ}
    listed = {cp for lo, hi in DEFAULT_IGNORABLE for cp in range(lo, hi + 1)}
    if listed != expected:
        sys.exit(f"list 2 differs from {path}: missing {sorted(expected - listed)}, extra {sorted(listed - expected)}")


# --- the folds -------------------------------------------------------------------------------------------------------

def code_points(text: str):
    """The code points of a UTF-16 string held as Python code points: pairs joined, lone halves as themselves."""
    units = text.encode("utf-16-le", "surrogatepass")
    units = [int.from_bytes(units[i:i + 2], "little") for i in range(0, len(units), 2)]
    i = 0
    while i < len(units):
        u = units[i]
        if 0xD800 <= u <= 0xDBFF and i + 1 < len(units) and 0xDC00 <= units[i + 1] <= 0xDFFF:
            yield 0x10000 + ((u - 0xD800) << 10) + (units[i + 1] - 0xDC00), False
            i += 2
        else:
            yield u, 0xD800 <= u <= 0xDFFF
            i += 1


def ascii_fold(cp: int) -> int:
    return cp + 32 if 0x41 <= cp <= 0x5A else cp


def fold1(text: str) -> str:
    out = []
    for cp, lone in code_points(text):
        if lone:
            continue
        if inside(cp, FOLD1_SPACED):
            out.append(0x20)
        elif not inside(cp, FOLD1_DROPPED):
            out.append(cp)
    while out and inside(out[0], S):
        out.pop(0)
    while out and inside(out[-1], S):
        out.pop()
    return "".join(chr(ascii_fold(cp)) for cp in out)


def fold2(text: str) -> str:
    out = []
    last = None
    gap = False
    for cp, lone in code_points(text):
        if lone:
            continue
        if in_r(cp):
            if inside(cp, G):
                gap = True
            continue
        if cp in (ZWNJ, ZWJ):
            if last is None or inside(last, L) or gap:
                continue
        out.append(ascii_fold(cp))
        last = cp
        gap = False
    return "".join(chr(cp) for cp in out)


def cps(*codes: int) -> str:
    return "".join(chr(c) for c in codes)


def label(cp: int) -> str:
    return f"U+{cp:04X}"


# --- studio-fold-2.json ----------------------------------------------------------------------------------------------

def fold_cases():
    cases = []

    def add(name: str, text: str, expect: str | None = None):
        key = fold2(text)
        if expect is not None and key != expect:
            sys.exit(f"{name}: key {key!r}, the ADR says {expect!r}")
        cases.append({"name": name, "input": text, "fold1": fold1(text), "key": key})

    add("plain ASCII is folded", "Walt Disney", "waltdisney")
    add("only A to Z fold", "Studi\u00D6 \u212A", "studi\u00D6\u212A")
    add("no space at all", "WaltDisney", "waltdisney")
    add("two spaces", "Walt  Disney", "waltdisney")
    add("empty", "", "")

    for lo, hi in S:
        for cp in range(lo, hi + 1):
            add(f"S {label(cp)} alone", cps(cp), "")
            add(f"S {label(cp)} at the start", cps(cp) + "Disney", "disney")
            add(f"S {label(cp)} at the end", "Disney" + cps(cp), "disney")
            add(f"S {label(cp)} between two words", "Walt" + cps(cp) + "Disney", "waltdisney")
    for lo, hi in B:
        for cp in range(lo, hi + 1):
            add(f"B {label(cp)} alone", cps(cp), "")
            add(f"B {label(cp)} between two words", "Walt" + cps(cp) + "Disney", "waltdisney")
    seen = set()
    for part, ranges in (("D1", FOLD1_DROPPED), ("D2", DEFAULT_IGNORABLE), ("D3", EXTRA)):
        for lo, hi in ranges:
            for cp in sorted({lo, (lo + hi) // 2, hi}):
                if cp in seen:
                    continue
                seen.add(cp)
                add(f"{part} {label(cp)} alone", cps(cp), "")
                add(f"{part} {label(cp)} inside a word", "Dis" + cps(cp) + "ney", "disney")
    for cp in (0x2065, 0xFFF0, 0xFFF8, 0xE0000, 0xE0002, 0xE0080, 0xE01F0, 0xE0FFF):
        if cp not in seen:
            sys.exit(f"reserved default-ignorable {label(cp)} has no vector")

    # Joiners.
    for j, jn in ((ZWJ, "ZWJ"), (ZWNJ, "ZWNJ")):
        add(f"{jn} inside a Latin word", "Dis" + cps(j) + "ney", "disney")
        add(f"{jn} at the start", cps(j) + "Disney", "disney")
        add(f"{jn} alone", cps(j), "")
        add(f"{jn} after a space", "Walt " + cps(j) + "Disney", "waltdisney")
        add(f"{jn} between two spaces", "Walt " + cps(j) + " Disney", "waltdisney")
        add(f"{jn} after a dropped code point", "s" + cps(0x034F, j), "s")
        add(f"{jn} after U+0301", "e" + cps(0x0301, j), "e" + cps(0x0301))
        add(f"{jn} after U+036F (last of L)", "e" + cps(0x036F, j), "e" + cps(0x036F))
        add(f"{jn} after U+0370 (first after L)", cps(0x0370, j), cps(0x0370, j))
        add(f"{jn} after U+024F (last of Latin Extended-B)", cps(0x024F, j), cps(0x024F))
        add(f"{jn} after U+0250 (first after it)", cps(0x0250, j), cps(0x0250, j))
        add(f"{jn} after a non-Latin letter", cps(0x6771, j, 0x6620), cps(0x6771, j, 0x6620))
        add(f"{jn} at the end after a non-Latin letter", cps(0x6771, 0x6620, j), cps(0x6771, 0x6620, j))
        add(f"{jn} after a non-Latin letter and U+3000", cps(0x6771, 0x6620, 0x3000, j) + "Animation",
            cps(0x6771, 0x6620) + "animation")
        add(f"{jn} after a non-Latin letter and U+000B (not in G)", cps(0x6771, 0x000B, j) + "x", cps(0x6771, j) + "x")
    add("a run of joiners inside a Latin word", "Dis" + cps(ZWJ, ZWNJ, ZWJ) + "ney", "disney")
    add("a run of joiners after a non-Latin letter", cps(0x6771, ZWJ, ZWNJ, ZWJ, 0x6620),
        cps(0x6771, ZWJ, ZWNJ, ZWJ, 0x6620))

    # The walk-through table of the ADR.
    add("walk: Walt U+2009 U+200D Disney", "Walt" + cps(0x2009, ZWJ) + "Disney", "waltdisney")
    add("walk: U+3000 U+200D Walt Disney", cps(0x3000, ZWJ) + "Walt Disney", "waltdisney")
    add("walk: Walt U+3000 U+200D Disney", "Walt" + cps(0x3000, ZWJ) + "Disney", "waltdisney")
    add("walk: Walt U+3164 Disney", "Walt" + cps(0x3164) + "Disney", "waltdisney")
    add("walk: Walt U+000B Disney", "Walt" + cps(0x000B) + "Disney", "waltdisney")
    add("walk: Walt U+0085 Disney", "Walt" + cps(0x0085) + "Disney", "waltdisney")
    add("walk: Dis U+E0002 ney", "Dis" + cps(0xE0002) + "ney", "disney")
    add("walk: Pix U+2065 ar", "Pix" + cps(0x2065) + "ar", "pixar")
    add("walk: Pathe U+0301 U+200D", "Pathe" + cps(0x0301, ZWJ), "pathe" + cps(0x0301))
    add("walk: Dis U+200D U+200C U+200D ney", "Dis" + cps(ZWJ, ZWNJ, ZWJ) + "ney", "disney")
    add("walk: Pixars U+034F U+200D", "Pixars" + cps(0x034F, ZWJ), "pixars")
    add("walk: Devanagari U+0915 U+094D U+200D U+0937", cps(0x0915, 0x094D, ZWJ, 0x0937),
        cps(0x0915, 0x094D, ZWJ, 0x0937))
    add("walk: U+6771 U+6620 U+3000 U+200D Animation", cps(0x6771, 0x6620, 0x3000, ZWJ) + "Animation",
        cps(0x6771, 0x6620) + "animation")
    add("walk: U+6771 U+000B U+200D x", cps(0x6771, 0x000B, ZWJ) + "x", cps(0x6771, ZWJ) + "x")
    add("walk: U+6771 U+6620 U+200D (trailing)", cps(0x6771, 0x6620, ZWJ), cps(0x6771, 0x6620, ZWJ))
    add("fold 1 trims, fold 2 removes the joiner: U+3000 U+200D Disney", cps(0x3000, ZWJ) + "Disney", "disney")

    # Empty keys.
    add("empty key: U+3164", cps(0x3164), "")
    add("empty key: U+2800 U+3164", cps(0x2800, 0x3164), "")
    add("empty key: spaces and joiners", " " + cps(ZWJ, 0x3000, ZWNJ) + " ", "")

    # Surrogates.
    add("a lone high surrogate inside a word", "Dis" + cps(0xD800) + "ney", "disney")
    add("a lone low surrogate inside a word", "Dis" + cps(0xDC00) + "ney", "disney")
    add("a lone surrogate alone", cps(0xDBFF), "")
    add("a pair is kept", "x" + chr(0x1F3AC) + "Y", "x" + chr(0x1F3AC) + "y")
    add("a joiner after a supplementary letter is kept", chr(0x1F3AC) + cps(ZWJ), chr(0x1F3AC) + cps(ZWJ))

    # Two KID_STUDIOS entries that differ only by a space share one key.
    add("BabyTV", "BabyTV", "babytv")
    add("Baby TV", "Baby TV", "babytv")
    return cases


# --- kid-hold-4.json -------------------------------------------------------------------------------------------------

KID_STUDIOS_USED = ["Walt Disney Animation Studios", "Disney Junior", "Pixar", "Cartoon Network", "PBS Kids",
                    "Nick Jr."]
RANK = {"G": 0, "TV-G": 0, "PG": 2, "TV-PG": 3, "TV-14": 4}


def within(rating, ceiling=2):
    rank = RANK.get(rating)
    return rank is not None and rank <= ceiling


def kid_studio(names, fold) -> bool:
    keys = {fold(n) for n in KID_STUDIOS_USED}
    return any(fold(n) != "" and fold(n) in keys for n in names)


def hold_case(name, kind, rating, studios, series_studios=None, series_rating=None):
    series = series_studios is not None

    def made(fold):
        return kid_studio(studios, fold) or (series and kid_studio(series_studios, fold))

    if kind == "movie":
        passes = within(rating)
    else:
        effective = rating if rating else series_rating
        passes = within(effective) and (not series_rating or within(series_rating))
    return {
        "name": name,
        "kind": kind,
        "officialRating": rating,
        "studios": studios,
        "series": {"officialRating": series_rating, "studios": series_studios} if series else None,
        "kidHold3": made(fold1),
        "kidHold4": made(fold2),
        "grownUpHold3Drops": made(fold2) and passes,
    }


def hold_cases():
    look_alikes = [
        ("no-break space", lambda s: s.replace(" ", cps(0x00A0))),
        ("U+2009 thin space", lambda s: s.replace(" ", cps(0x2009))),
        ("U+3000 ideographic space", lambda s: s.replace(" ", cps(0x3000))),
        ("two spaces", lambda s: s.replace(" ", "  ")),
        ("U+000B", lambda s: s.replace(" ", cps(0x000B))),
        ("U+0085", lambda s: s.replace(" ", cps(0x0085))),
        ("U+3164 Hangul filler", lambda s: s.replace(" ", cps(0x3164))),
        ("U+2800 Braille blank", lambda s: s.replace(" ", cps(0x2800))),
        ("U+115F Hangul choseong filler", lambda s: s.replace(" ", cps(0x115F))),
        ("U+1160 Hangul jungseong filler", lambda s: s.replace(" ", cps(0x1160))),
        ("U+FFA0 halfwidth Hangul filler", lambda s: s.replace(" ", cps(0xFFA0))),
        ("U+1680 Ogham space mark", lambda s: s.replace(" ", cps(0x1680))),
        ("U+202F narrow no-break space", lambda s: s.replace(" ", cps(0x202F))),
        ("U+205F medium mathematical space", lambda s: s.replace(" ", cps(0x205F))),
        ("ZWJ inside a word", lambda s: s[:2] + cps(ZWJ) + s[2:]),
        ("ZWNJ inside a word", lambda s: s[:2] + cps(ZWNJ) + s[2:]),
        ("U+E0002 inside a word", lambda s: s[:3] + cps(0xE0002) + s[3:]),
        ("U+2065 inside a word", lambda s: s[:3] + cps(0x2065) + s[3:]),
        ("U+034F inside a word", lambda s: s[:3] + cps(0x034F) + s[3:]),
        ("U+FE0F inside a word", lambda s: s[:3] + cps(0xFE0F) + s[3:]),
        ("U+17B4 inside a word", lambda s: s[:3] + cps(0x17B4) + s[3:]),
        ("U+180B inside a word", lambda s: s[:3] + cps(0x180B) + s[3:]),
        ("U+E0100 inside a word", lambda s: s[:3] + cps(0xE0100) + s[3:]),
        ("U+E00A0 inside a word", lambda s: s[:3] + cps(0xE00A0) + s[3:]),
        ("ZWJ at the start", lambda s: cps(ZWJ) + s),
        ("ZWNJ at the start", lambda s: cps(ZWNJ) + s),
        ("space then ZWJ", lambda s: s.replace(" ", " " + cps(ZWJ), 1) if " " in s else s + " " + cps(ZWJ)),
    ]
    cases = []
    for studio in KID_STUDIOS_USED:
        for what, spell in look_alikes:
            spelled = spell(studio)
            if spelled == studio:
                continue
            case = hold_case(f"{studio}, {what}, PG movie", "movie", "PG", [spelled])
            if case["kidHold3"]:
                # Fold 1 already matches it; only the look-alikes fold 1 misses are vectors of the change.
                continue
            cases.append(case)
    cases.append(hold_case("a series studio with a look-alike, PG episode", "episode", "PG", [],
                           ["Cartoon" + cps(0x3164) + "Network"], "PG"))
    cases.append(hold_case("a look-alike kid studio on a TV-14 movie: made for kids, kept on grown-up", "movie",
                           "TV-14", ["Pix" + cps(0x2065) + "ar"]))
    cases.append(hold_case("the plain spelling, PG movie", "movie", "PG", ["Pixar"]))
    cases.append(hold_case("a studio off the list with a look-alike", "movie", "PG", ["Walt" + cps(0xA0) + "Disney"]))
    cases.append(hold_case("an empty fold-2 key is no kid studio", "movie", "PG", [cps(0x2800, 0x3164)]))
    cases.append(hold_case("a joiner after a non-Latin letter does not match", "movie", "PG",
                           [cps(0x6771, ZWJ) + "Pixar"]))
    for case in cases:
        if case["kidHold3"] and case["kidHold4"] is False:
            sys.exit(f"{case['name']}: fold 2 split a fold-1 match")
    return cases


# --- output ----------------------------------------------------------------------------------------------------------

FOLD_DESCRIPTION = (
    "Studio fold 2 (docs/adr/0026-studio-fold-2.md, The sets and The pass): read the name by code point with 'last kept'"
    " empty and 'gap' off; a lone surrogate is removed; a code point in R = S u B u D is removed, and sets gap on if it"
    " is in G; U+200C or U+200D is removed if last kept is empty, last kept is in L, or gap is on, else kept; any other"
    " code point is kept as its ASCII fold (A to Z become a to z). A keep sets last kept and sets gap off. No trim, no"
    " collapse, no normalisation. 'key' is fold 2 of 'input'; 'fold1' is studio fold 1 (docs/rules-format.md, Studio"
    " names: clean, then fold) of 'input', given so that a port can check fold2(fold1(x)) = fold2(x). Every vector"
    " also satisfies fold2(key) = key. 'sets' lists S, B, D (its three parts), G and L as inclusive code point ranges"
    " in hexadecimal. Whitespace, control and format characters are written as JSON escapes (such as \\u00a0 for a"
    " no-break space) so that they can be seen; this file is all ASCII, and a supplementary code point is written as"
    " its UTF-16 surrogate pair."
)
SOURCE = (
    "Computed with an independent Python reference (scripts/rules-format/studio_fold_2_vectors.py) written from"
    " ADR-0026 alone; it checks list 2 of D against Unicode 15.1.0 DerivedCoreProperties.txt"
    " (Default_Ignorable_Code_Point, less U+200C and U+200D) before writing."
)
HOLD_DESCRIPTION = (
    "kid-hold-4 and grown-up-hold-3 (docs/adr/0026-studio-fold-2.md, Versioning): the KID_STUDIOS list is matched by"
    " studio fold 2 key (see studio-fold-2.json), on the item's studios and, for an episode, on its series' studios."
    " Every case has no genres and no children's rating, so only the studio list decides 'made for kids'. 'kidHold3' is"
    " the answer of kid-hold-3 (studio fold 1), 'kidHold4' that of kid-hold-4. 'grownUpHold3Drops' is whether"
    " grown-up-hold-3 drops the title from a grown-up channel: made for kids under kid-hold-4 and passing the widest"
    " kid ceiling (PG; for an episode its effective rating and a non-empty series rating must both pass)."
    " Whitespace, control and format characters are written as JSON escapes so that they can be seen; this file is"
    " all ASCII."
)


# --- pool-v4.json ----------------------------------------------------------------------------------------------------
# The pool of a rules file under studio fold 1 (formatVersion 3) and studio fold 2 (formatVersion 4), for studio sources,
# studio exclusions and the studios filter (ADR-0026, The pass and Where each fold is used). Only what these cases use is
# modelled: library and studio sources, studio exclusions, the studios filter; an episode also takes its series'
# studios when its series is in the snapshot.

LIBRARY = "00000000000000000000000000001001"


def canon(n: int) -> str:
    return f"{n:032x}"


def pool_item(n, studios, kind="movie", series=None):
    return {
        "id": canon(n),
        "kind": kind,
        "libraryId": LIBRARY,
        "seriesId": canon(series) if series is not None else None,
        "seasonId": None,
        "productionYear": None,
        "officialRating": None,
        "genres": [],
        "tags": [],
        "studios": studios,
    }


def pool_series(n, studios):
    return {"id": canon(n), "officialRating": None, "genres": [], "tags": [], "studios": studios}


def studio_names(item, by_id):
    names = list(item["studios"])
    series = by_id.get(item["seriesId"]) if item["kind"] == "episode" else None
    return names, (series["studios"] if series is not None else [])


def matches_name(name, item, by_id, fold):
    key = fold(name)
    if key == "":
        return False
    own, inherited = studio_names(item, by_id)
    return any(fold(n) == key for n in own + inherited)


def has_empty_key2(item, by_id):
    own, inherited = studio_names(item, by_id)
    return any(fold2(n) == "" for n in own) or any(fold2(n) == "" for n in inherited)


def matches_entry(entry, item, by_id, fold, exclusion):
    if entry["type"] == "library":
        return item["libraryId"] == entry["id"]
    name = entry["name"]
    if fold is fold2 and exclusion and fold2(name) == "":
        return has_empty_key2(item, by_id)
    return matches_name(name, item, by_id, fold)


def pool_of(case, fold):
    by_id = {s["id"]: s for s in case["series"]}
    out = []
    for item in case["items"]:
        if not any(matches_entry(e, item, by_id, fold, False) for e in case["sources"]):
            continue
        if any(matches_entry(e, item, by_id, fold, True) for e in case["exclusions"]):
            continue
        studios = (case.get("filters") or {}).get("studios")
        if studios is not None and not any(matches_name(n, item, by_id, fold) for n in studios):
            continue
        out.append(item["id"])
    return sorted(out)


def pool_case(name, items, sources, exclusions=(), series=(), filters=None, note=None):
    case = {"name": name}
    if note:
        case["note"] = note
    case.update({
        "libraries": [LIBRARY],
        "items": items,
        "series": list(series),
        "collections": [],
        "sources": list(sources),
        "exclusions": list(exclusions),
    })
    if filters is not None:
        case["filters"] = filters
    case["expectedV3"] = pool_of(case, fold1)
    case["expected"] = pool_of(case, fold2)
    return case


def pool_v4_cases():
    library = {"type": "library", "id": LIBRARY}

    def studio(name):
        return {"type": "studio", "name": name}

    look_alikes = [
        "Walt Disney",
        "WALT DISNEY",
        "Walt" + cps(0x00A0) + "Disney",
        "Walt" + cps(0x2009) + "Disney",
        "Walt" + cps(0x3000) + "Disney",
        "Walt  Disney",
        "WaltDisney",
        "Walt" + cps(0x3164) + "Disney",
        "Walt" + cps(0x115F) + "Disney",
        "Walt" + cps(0x2800) + "Disney",
        "Walt" + cps(0x000B) + "Disney",
        "Walt" + cps(0x0085) + "Disney",
        "Walt" + cps(0x2009, 0x200D) + "Disney",
        "Walt Dis" + cps(0x200D) + "ney",
        "Walt Dis" + cps(0x200C, 0x200D) + "ney",
        "Walt Dis" + cps(0xE0002) + "ney",
        "Walt Dis" + cps(0x034F) + "ney",
        "Walt Disne" + cps(0xFE0F) + "y",
        cps(0x3000, 0x200D) + "Walt Disney",
        "Walt Disney" + cps(0x200B),
        "Pixar",
        "Walt Disnay",
    ]
    items = [pool_item(0x101 + i, [name]) for i, name in enumerate(look_alikes)]
    note = ("One movie per spelling, in this order: " + ", ".join(json.dumps(n, ensure_ascii=True) for n in look_alikes)
            + ". Only the last two are other studios.")
    cases = [
        pool_case("studio source: look-alikes of the name match under fold 2 (4), only the fold 1 twins under fold 1 (3)",
                  items, [studio("Walt Disney")], note=note),
        pool_case("studio exclusion: look-alikes are excluded under fold 2 (4), only the fold 1 twins under fold 1 (3)",
                  items, [library], [studio("Walt Disney")], note=note),
        pool_case("filters studios: look-alikes pass under fold 2 (4), only the fold 1 twins under fold 1 (3)",
                  items, [library], filters={"studios": ["walt disney"]}, note=note),
        pool_case("studio source: the name in the rules is folded the same way (U+3000 and a joiner in it)",
                  items, [studio(" Walt" + cps(0x3000) + "Dis" + cps(0x200D) + "ney" + cps(0x00A0))], note=note),
    ]

    episodes = [
        pool_item(0x201, [], kind="episode", series=0x301),
        pool_item(0x202, [], kind="episode", series=0x302),
        pool_item(0x203, ["Walt" + cps(0x3000) + "Disney"], kind="episode", series=0x302),
        pool_item(0x204, [], kind="episode", series=0x399),
    ]
    shows = [pool_series(0x301, ["Walt" + cps(0x00A0) + "Disney"]), pool_series(0x302, ["Pixar"])]
    cases.append(pool_case(
        "studio exclusion under fold 2: an episode takes its series' studios; a missing series uses the episode's own",
        episodes, [library], [studio("Walt Disney")], series=shows,
        note="...0201's series has a look-alike; ...0203 has one itself; ...0204's series is not in the snapshot."))

    blank = [
        pool_item(0x101, [cps(0x2800, 0x3164)]),
        pool_item(0x102, ["Pixar", cps(0x3164)]),
        pool_item(0x103, ["Pixar"]),
        pool_item(0x104, []),
        pool_item(0x105, [cps(0x115F)]),
        pool_item(0x201, [], kind="episode", series=0x301),
        pool_item(0x202, [], kind="episode", series=0x302),
    ]
    blank_shows = [pool_series(0x301, [cps(0xFFA0)]), pool_series(0x302, ["Pixar"])]
    blank_note = ("...0101 has the one studio U+2800 U+3164, ...0102 has Pixar and U+3164, ...0105 has U+115F, the "
                  "series of ...0201 has U+FFA0: every one a studio whose fold 2 key is empty.")
    cases += [
        pool_case("the empty-key rule: under fold 2 an exclusion whose key is empty excludes every item with a studio "
                  "whose key is empty (own or series'); under fold 1 it matches only its own fold 1 key",
                  blank, [library], [studio(cps(0x3164))], series=blank_shows, note=blank_note),
        pool_case("the empty-key rule: a studio source whose fold 2 key is empty matches nothing",
                  blank, [studio(cps(0x3164))], series=blank_shows, note=blank_note),
        pool_case("the empty-key rule: a studios filter name whose fold 2 key is empty matches nothing",
                  blank, [library], series=blank_shows, filters={"studios": [cps(0x3164)]}, note=blank_note),
    ]

    cancel = [
        pool_item(0x101, ["Walt Disney"]),
        pool_item(0x102, ["Walt" + cps(0x000B) + "Disney"]),
        pool_item(0x103, ["Walt" + cps(0x3000) + "Disney"]),
        pool_item(0x104, ["Pixar"]),
    ]
    cases.append(pool_case(
        "a studio source and a studio exclusion cancel under fold 2 (4) but not under fold 1 (3)",
        cancel, [studio("Walt Disney"), studio("Pixar")], [studio("Walt" + cps(0x3000) + "Disney")],
        note=("Under fold 1 the source keeps ...0101 and the exclusion removes only ...0103, which the source does not "
              "select; under fold 2 both name the key waltdisney, so every Walt Disney spelling is selected and removed.")))

    toei = [
        pool_item(0x101, [cps(0x6771, 0x6620, 0x3000, 0x200D) + "Animation"]),
        pool_item(0x102, [cps(0x6771, 0x6620, 0x200D) + "Animation"]),
        pool_item(0x103, [cps(0x0915, 0x094D, 0x200D, 0x0937)]),
        pool_item(0x104, [cps(0x0915, 0x094D, 0x0937)]),
    ]
    cases += [
        pool_case("joiners: removed after a gap even after a non-Latin letter; kept between a non-Latin letter and a "
                  "Latin one (a known residual gap)",
                  toei, [library], [studio(cps(0x6771, 0x6620) + " Animation")]),
        pool_case("joiners: kept inside a non-Latin word, so a Devanagari conjunct and its plain form stay apart",
                  toei, [studio(cps(0x0915, 0x094D, 0x200D, 0x0937))]),
    ]
    return cases


POOL_V4_SOURCE = (
    "Computed with scripts/rules-format/studio_fold_2_vectors.py: written from ADR-0026; the pool model was written"
    " after reading SourceMatcher. The folds it uses are the independent ones of studio-fold-2.json."
)
POOL_V4_DESCRIPTION = (
    "The version 4 pool (docs/adr/0026-studio-fold-2.md), in the pool-v3.json case shape (see its description), for "
    "studio sources, studio exclusions and the studios filter only. 'expected' is the pool of the case's rules in a "
    "file that says formatVersion 4: names match by their studio fold 2 key (see studio-fold-2.json); a studio source "
    "or a studios filter name whose key is empty matches nothing; a studio exclusion whose key is empty matches every "
    "item that has, or whose series has, a studio whose key is empty. 'expectedV3' is the pool of the same rules in a "
    "file that says formatVersion 3: studio fold 1 (docs/rules-format.md, Studio names), exactly as pool-v3.json. "
    "There is no films or franchises map here. Whitespace, control and format characters are "
    "written as JSON escapes so that they can be seen; this file is all ASCII."
)


def ranges_json(ranges):
    return [[f"{lo:04X}", f"{hi:04X}"] for lo, hi in ranges]


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    check_default_ignorable(pathlib.Path(sys.argv[1]))
    out = pathlib.Path(sys.argv[2])
    fold = {
        "description": FOLD_DESCRIPTION,
        "source": SOURCE,
        "sets": {
            "S": ranges_json(S),
            "B": ranges_json(B),
            "D": {"fold1Dropped": ranges_json(FOLD1_DROPPED), "defaultIgnorable": ranges_json(DEFAULT_IGNORABLE),
                  "extra": ranges_json(EXTRA)},
            "G": ranges_json(G),
            "L": ranges_json(L),
        },
        "vectors": fold_cases(),
    }
    for case in fold["vectors"]:
        if fold2(case["key"]) != case["key"] or fold2(case["fold1"]) != case["key"]:
            sys.exit(f"{case['name']}: a property fails")
    hold = {"description": HOLD_DESCRIPTION, "source": SOURCE, "kidStudios": KID_STUDIOS_USED, "cases": hold_cases()}
    pool4 = {"description": POOL_V4_DESCRIPTION, "source": POOL_V4_SOURCE, "cases": pool_v4_cases()}
    for name, data in (("studio-fold-2.json", fold), ("kid-hold-4.json", hold), ("pool-v4.json", pool4)):
        text = json.dumps(data, indent=2, ensure_ascii=True) + "\n"
        (out / name).write_text(text, encoding="ascii")


if __name__ == "__main__":
    main()
