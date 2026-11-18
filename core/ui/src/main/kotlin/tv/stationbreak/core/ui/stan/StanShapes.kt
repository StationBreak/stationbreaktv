// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress(
    "MagicNumber", // A line-for-line port of the motion study: every number is a coordinate or a time in its data.
    "LongParameterList", // One argument per field of a shape, as the study's helpers (RR, C, O, G) take them.
    "TooManyFunctions", // The study's one stanShapes, split by body part; each part is a short function of its own.
)

package tv.stationbreak.core.ui.stan

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/*
 * Stan, as a list of plain shapes. A faithful Kotlin port of `stanShapes(state)` from the "Stan In Motion" motion
 * study (the source of truth), with its rounding helpers. The shapes come out in the same order, with the same path
 * strings and the same rounded numbers; StanFixtureTest proves it against the study's own output.
 */

internal enum class Legs { Stand, Sit, Kick }

internal enum class Ears { Rabbit, Droop, Zig }

internal enum class Arms { None, Tune, Rest, Bear, Wave, Shrug, Flail }

internal enum class Eyes { Open, Happy, Sleep, X }

internal enum class Mouth { None, Smile, Small, Tongue, O, Flat, Wavy }

/** Stan's face. [open] is how far the eyes are open (null is fully), [r] the size of an O mouth, [wob] the wavy one. */
internal data class StanFace(
    val eyes: Eyes,
    val mouth: Mouth,
    val open: Double? = null,
    val px: Double = 0.0,
    val py: Double = 0.0,
    val r: Double? = null,
    val wob: Double? = null,
    val brows: Boolean = false,
    val cheeks: Boolean = false,
)

/** What Stan is doing at one instant: the study's `s` object. The defaults are the study's too. */
internal data class StanState(
    val face: StanFace,
    val h: Double = 64.0,
    val legs: Legs = Legs.Stand,
    val kick: Double = 43.0,
    val ears: Ears = Ears.Rabbit,
    val droop: Double = 0.0,
    val earL: Double = 0.0,
    val earR: Double = 0.0,
    val cap: Double? = null,
    val arms: Arms = Arms.None,
    val wave: Double = 0.0,
    val armBob: Double = 0.0,
    val snow: Double = 0.0,
    val tile: Int = 0,
    val roll: Double = 0.0,
    val bandage: Boolean = false,
    val bear: Boolean = false,
)

private const val CAP =
    "M-42 -100 C-44 -142 -100 -178 -150 -152 C-172 -140 -184 -108 -184 -76 C-172 -104 -160 -122 -142 -120 " +
        "C-130 -118 -128 -88 -130 -56 Z"

internal fun stroke(
    d: String,
    c: StanColor,
    w: Double = 10.0,
    a: Double = 1.0,
) = StanStroke(d, c, w, r2(a))

internal fun fill(
    d: String,
    c: StanColor,
    a: Double = 1.0,
) = StanFill(d, c, r2(a))

internal fun circle(
    x: Double,
    y: Double,
    r: Double,
    c: StanColor,
    a: Double = 1.0,
    sw: Double = 0.0,
) = StanCircle(r1(x), r1(y), r1(r), c, r2(a), sw)

internal fun oval(
    x: Double,
    y: Double,
    rx: Double,
    ry: Double,
    c: StanColor,
    a: Double = 1.0,
) = StanOval(r1(x), r1(y), r1(rx), r1(ry), c, r2(a))

internal fun roundRect(
    x: Double,
    y: Double,
    w: Double,
    h: Double,
    r: Double,
    c: StanColor,
    rot: Double = 0.0,
    px: Double = 0.0,
    py: Double = 0.0,
    sc: StanColor? = null,
    sw: Double = 0.0,
) = StanRoundRect(r1(x), r1(y), w, h, r, c, r1(rot), r1(px), r1(py), sc, sw)

internal fun glyph(
    ch: String,
    x: Double,
    y: Double,
    size: Double,
    c: StanColor,
    a: Double = 1.0,
    mid: Boolean = false,
) = StanGlyph(ch, r1(x), r1(y), r1(size), c, r2(a), mid)

/** A four-point star as a path: [r] to each tip, pinched to 28% in between. */
internal fun starD(
    x: Double,
    y: Double,
    r: Double,
): String {
    val q = r * .28
    return "M${num(r1(x))} ${num(r1(y - r))} L${num(r1(x + q))} ${num(r1(y - q))} L${num(r1(x + r))} ${num(r1(y))} " +
        "L${num(r1(x + q))} ${num(r1(y + q))} L${num(r1(x))} ${num(r1(y + r))} L${num(r1(x - q))} ${num(r1(y + q))} " +
        "L${num(r1(x - r))} ${num(r1(y))} L${num(r1(x - q))} ${num(r1(y - q))} Z"
}

/** The tip of an ear that grows from [bx] on the cabinet's top edge, [deg] degrees from upright and [len] long. */
internal fun earTip(
    bx: Double,
    deg: Double,
    len: Double,
): Pair<Double, Double> {
    val a = deg * PI / 180
    return (bx + len * sin(a)) to (-96 - len * cos(a))
}

/** A point of the stage that other parts hang from: an ear tip. */
private typealias Tip = Pair<Double, Double>

/** Stan as a list of shapes for [s], back to front: legs, ears, cap, arms, cabinet, face, snow, held things. */
internal fun stanShapes(s: StanState): MutableList<StanShape> {
    val o = ArrayList<StanShape>(SHAPE_CAPACITY)
    legs(o, s)
    val (_, right) = ears(o, s)
    cap(o, s)
    arms(o, s, right)
    cabinet(o)
    face(o, s)
    if (s.snow > 0) o.add(StanSnow(r2(s.snow), s.tile, roundTo(s.roll, 100.0)))
    if (s.bandage) bandage(o)
    if (s.bear) bear(o)
    return o
}

private const val SHAPE_CAPACITY = 64

private fun legs(
    o: MutableList<StanShape>,
    s: StanState,
) {
    when (s.legs) {
        Legs.Sit -> {
            o.addAll(
                listOf(
                    stroke("M-30 86 L-33 116", StanColor.Cream),
                    stroke("M30 86 L33 116", StanColor.Cream),
                    roundRect(-52.0, 104.0, 34.0, 46.0, 15.0, StanColor.Accent, -8.0, -35.0, 127.0),
                    roundRect(18.0, 104.0, 34.0, 46.0, 15.0, StanColor.Accent, 8.0, 35.0, 127.0),
                ),
            )
        }

        Legs.Kick -> {
            val kick = s.kick
            val a = kick * PI / 180
            val ex = 55 + 63 * sin(a)
            val ey = 86 + 63 * cos(a)
            o.add(stroke("M-55 86 L-55 150", StanColor.Cream))
            o.add(roundRect(-91.0, 146.0, 46.0, 20.0, 10.0, StanColor.Accent))
            o.add(stroke("M55 86 L${num(r1(ex))} ${num(r1(ey))}", StanColor.Cream))
            o.add(roundRect(ex - 14, ey - 6, 46.0, 20.0, 10.0, StanColor.Accent, 5 - kick, ex + 9, ey + 4))
        }

        Legs.Stand -> {
            val h = s.h
            o.add(stroke("M-55 86 L-55 ${num(r1(86 + h))}", StanColor.Cream))
            o.add(stroke("M55 86 L55 ${num(r1(86 + h))}", StanColor.Cream))
            o.add(roundRect(-91.0, 82 + h, 46.0, 20.0, 10.0, StanColor.Accent))
            o.add(roundRect(45.0, 82 + h, 46.0, 20.0, 10.0, StanColor.Accent))
        }
    }
}

/** Draws the rabbit ears and their orange tips; returns the two tips, which the arms reach for. */
private fun ears(
    o: MutableList<StanShape>,
    s: StanState,
): Pair<Tip, Tip> {
    val left: Tip
    val right: Tip
    val d = s.droop
    when (s.ears) {
        Ears.Droop -> {
            left = -104.0 to -120 + d
            right = 104.0 to -118 + d
            o.add(stroke("M-6 -96 Q-58 -152 -104 ${num(r1(-120 + d))}", StanColor.Cream, 9.0))
            o.add(stroke("M6 -96 Q58 -152 104 ${num(r1(-118 + d))}", StanColor.Cream, 9.0))
        }

        else -> {
            left = earTip(-6.0, -34.3 + s.earL, 99.3)
            o.add(stroke("M-6 -96 L${num(r1(left.first))} ${num(r1(left.second))}", StanColor.Cream, 9.0))
            if (s.ears == Ears.Zig) {
                right = 60.0 to -188.0
                o.add(stroke("M6 -96 L42 -138 L20 -156 L60 -188", StanColor.Cream, 9.0))
            } else {
                right = earTip(6.0, 30 + s.earR, 104.0)
                o.add(stroke("M6 -96 L${num(r1(right.first))} ${num(r1(right.second))}", StanColor.Cream, 9.0))
            }
        }
    }
    o.add(circle(left.first, left.second, 9.0, StanColor.Accent))
    o.add(circle(right.first, right.second, 9.0, StanColor.Accent))
    o.add(circle(0.0, -88.0, 22.0, StanColor.Cream))
    return left to right
}

/** The nightcap: pushed, filled, striped inside a clip, popped, with its bobble. */
private fun cap(
    o: MutableList<StanShape>,
    s: StanState,
) {
    val rot = s.cap ?: return
    o.add(StanPush(r1(rot), -86.0, -78.0))
    o.add(fill(CAP, StanColor.Accent))
    o.add(StanClip(CAP))
    o.add(stroke("M-150 -74 L-30 -132", StanColor.Cream, 11.0))
    o.add(stroke("M-160 -110 L-50 -162", StanColor.Cream, 11.0))
    o.add(stroke("M-200 -112 L-150 -160", StanColor.Cream, 11.0))
    o.add(StanPop)
    o.add(circle(-184.0, -62.0, 16.0, StanColor.Cream))
    o.add(StanPop)
}

/** An arm and its hand, drawn behind the cabinet. */
private fun arm(
    o: MutableList<StanShape>,
    d: String,
    x: Double,
    y: Double,
) {
    o.add(stroke(d, StanColor.Cream))
    o.add(circle(x, y, 12.0, StanColor.Cream))
}

private fun arms(
    o: MutableList<StanShape>,
    s: StanState,
    right: Tip,
) {
    val b = s.armBob
    when (s.arms) {
        Arms.Tune -> {
            tune(o, right)
        }

        Arms.Rest -> {
            arm(o, "M-116 10 Q-150 40 -128 76", -126.0, 78.0)
            arm(o, "M116 10 Q150 40 128 76", 126.0, 78.0)
        }

        Arms.Bear -> {
            arm(o, "M-116 10 Q-150 40 -128 76", -126.0, 78.0)
        }

        Arms.Wave -> {
            wave(o, s.wave)
        }

        Arms.Shrug -> {
            arm(o, "M-116 0 Q-160 -4 -176 ${num(r1(-46 + b))}", -177.0, -50 + b)
            arm(o, "M116 0 Q160 -4 176 ${num(r1(-46 + b))}", 177.0, -50 + b)
        }

        Arms.Flail -> {
            arm(o, "M-116 0 Q-170 -30 -160 ${num(r1(-82 + b))}", -160.0, -86 + b)
            arm(o, "M116 10 Q160 44 178 ${num(r1(2 - b))}", 179.0, -2 - b)
        }

        Arms.None -> {}
    }
}

/** The right hand holds the ear tip; the left rests on the knob. Two orange tuning marks by the tip. */
private fun tune(
    o: MutableList<StanShape>,
    right: Tip,
) {
    val (rx, ry) = right
    val hx = rx + 20
    val hy = ry
    arm(o, "M-116 10 Q-166 22 -130 52", -128.0, 54.0)
    arm(
        o,
        "M116 -10 Q190 -60 ${num(r1(hx + 74))} ${num(r1(hy + 46))} Q${num(r1(hx + 54))} ${num(r1(hy + 10))} " +
            "${num(r1(hx))} ${num(r1(hy))}",
        hx,
        hy,
    )
    o.add(
        stroke(
            "M${num(r1(rx - 24))} ${num(r1(ry - 26))} Q${num(r1(rx - 12))} ${num(r1(ry - 38))} " +
                "${num(r1(rx + 2))} ${num(r1(ry - 34))}",
            StanColor.Accent,
            6.0,
        ),
    )
    o.add(
        stroke(
            "M${num(r1(rx - 30))} ${num(r1(ry - 10))} Q${num(r1(rx - 34))} ${num(r1(ry - 24))} " +
                "${num(r1(rx - 26))} ${num(r1(ry - 34))}",
            StanColor.Accent,
            6.0,
        ),
    )
}

/** The raised right hand, waving by the phase [w], with two small motion strokes. */
private fun wave(
    o: MutableList<StanShape>,
    w: Double,
) {
    val hx = 170 + 14 * sin(w)
    val hy = -74 + 5 * cos(w * 2)
    arm(o, "M-116 10 Q-160 30 -150 70", -150.0, 72.0)
    arm(o, "M116 0 Q165 -20 ${num(r1(hx))} ${num(r1(hy + 4))}", hx, hy)
    o.add(
        stroke(
            "M${num(r1(hx + 20))} ${num(r1(hy - 30))} Q${num(r1(hx + 30))} ${num(r1(hy - 18))} " +
                "${num(r1(hx + 26))} ${num(r1(hy - 4))}",
            StanColor.Accent,
            6.0,
        ),
    )
    o.add(
        stroke(
            "M${num(r1(hx - 22))} ${num(r1(hy - 34))} Q${num(r1(hx - 30))} ${num(r1(hy - 22))} " +
                "${num(r1(hx - 24))} ${num(r1(hy - 8))}",
            StanColor.Accent,
            6.0,
        ),
    )
}

/** The cabinet, the screen, the knobs and the speaker lines. */
private fun cabinet(o: MutableList<StanShape>) {
    o.add(roundRect(-120.0, -90.0, 240.0, 180.0, 36.0, StanColor.Cream))
    o.add(roundRect(-100.0, -70.0, 144.0, 136.0, 30.0, StanColor.Ink))
    o.add(circle(82.0, -42.0, 13.0, StanColor.Accent))
    o.add(circle(82.0, -4.0, 13.0, StanColor.Ink))
    o.add(stroke("M68 34 L96 34", StanColor.Ink, 5.0))
    o.add(stroke("M68 46 L96 46", StanColor.Ink, 5.0))
    o.add(stroke("M68 58 L96 58", StanColor.Ink, 5.0))
}

private val EYE_XS = doubleArrayOf(-58.0, 2.0)

/** The face, faded out by any static on the screen. */
private fun face(
    o: MutableList<StanShape>,
    s: StanState,
) {
    val f = s.face
    val fa = 1 - s.snow
    for (x in EYE_XS) eye(o, f, x, fa)
    if (f.brows) {
        o.add(stroke("M-72 -42 L-46 -46", StanColor.Cream, 6.0, fa))
        o.add(stroke("M-10 -52 L16 -42", StanColor.Cream, 6.0, fa))
    }
    mouth(o, f, fa)
    if (f.cheeks) {
        o.add(circle(-86.0, 12.0, 9.0, StanColor.Accent, fa))
        o.add(circle(30.0, 12.0, 9.0, StanColor.Accent, fa))
    }
}

private fun eye(
    o: MutableList<StanShape>,
    f: StanFace,
    x: Double,
    fa: Double,
) {
    when (f.eyes) {
        Eyes.Open -> {
            val k = f.open ?: 1.0
            o.add(oval(x, -14.0, 12.0, max(1.5, 16 * k), StanColor.Cream, fa))
            if (k > .5) o.add(circle(x + f.px, -14 + f.py, 6.0, StanColor.Ink, fa))
        }

        Eyes.Happy -> {
            o.add(stroke("M${num(x - 14)} -8 Q${num(x)} -28 ${num(x + 14)} -8", StanColor.Cream, 7.0, fa))
        }

        Eyes.Sleep -> {
            o.add(stroke("M${num(x - 14)} -16 Q${num(x)} 2 ${num(x + 14)} -16", StanColor.Cream, 7.0, fa))
        }

        Eyes.X -> {
            o.add(
                stroke(
                    "M${num(x - 11)} -25 L${num(x + 11)} -3 M${num(x + 11)} -25 L${num(x - 11)} -3",
                    StanColor.Cream,
                    7.0,
                    fa,
                ),
            )
        }
    }
}

private fun mouth(
    o: MutableList<StanShape>,
    f: StanFace,
    fa: Double,
) {
    when (f.mouth) {
        Mouth.Smile -> {
            o.add(stroke("M-52 18 Q-28 44 -4 18", StanColor.Cream, 7.0, fa))
        }

        Mouth.Small -> {
            o.add(stroke("M-48 22 Q-28 40 -8 22", StanColor.Cream, 7.0, fa))
        }

        Mouth.Tongue -> {
            o.add(stroke("M-46 24 Q-28 36 -10 24", StanColor.Cream, 7.0, fa))
            o.add(circle(-11.0, 31.0, 7.0, StanColor.Accent, fa))
        }

        Mouth.O -> {
            o.add(circle(-28.0, 28.0, f.r ?: 7.0, StanColor.Cream, fa, 6.0))
        }

        Mouth.Flat -> {
            o.add(stroke("M-46 32 L-12 26", StanColor.Cream, 7.0, fa))
        }

        Mouth.Wavy -> {
            val a = f.wob ?: 12.0
            o.add(
                stroke(
                    "M-54 30 Q-45 ${num(r1(30 - a))} -36 30 Q-27 ${num(r1(30 + a))} -18 30 " +
                        "Q-9 ${num(r1(30 - a))} 0 30",
                    StanColor.Cream,
                    7.0,
                    fa,
                ),
            )
        }

        Mouth.None -> {}
    }
}

/** The plaster across the corner of the cabinet, in front of it. */
private fun bandage(o: MutableList<StanShape>) {
    o.add(roundRect(-128.0, -84.0, 48.0, 16.0, 5.0, StanColor.Grey, 45.0, -104.0, -76.0, StanColor.Ink, 3.0))
    o.add(roundRect(-128.0, -84.0, 48.0, 16.0, 5.0, StanColor.Grey, -45.0, -104.0, -76.0, StanColor.Ink, 3.0))
}

private val BEAR_PARTS =
    listOf(
        Triple(-15.0, -56.0, 9.0),
        Triple(15.0, -56.0, 9.0),
        Triple(0.0, -40.0, 21.0),
        Triple(-20.0, 0.0, 9.0),
        Triple(20.0, 0.0, 9.0),
        Triple(-15.0, 28.0, 10.0),
        Triple(15.0, 28.0, 10.0),
    )

/** The teddy under his arm, in front of the cabinet. */
private fun bear(o: MutableList<StanShape>) {
    val bx = 142.0
    val by = 92.0
    for ((dx, dy, r) in BEAR_PARTS) o.add(circle(bx + dx, by + dy, r, StanColor.Grey))
    o.add(oval(bx, by + 6, 22.0, 26.0, StanColor.Grey))
    o.add(oval(bx, by - 33, 9.0, 7.0, StanColor.Cream))
    o.add(circle(bx, by - 36, 3.0, StanColor.Ink))
    o.add(circle(bx - 8, by - 45, 2.6, StanColor.Ink))
    o.add(circle(bx + 8, by - 45, 2.6, StanColor.Ink))
    val b = num(bx)
    val ty = num(by - 17)
    o.add(
        fill(
            "M$b $ty L${num(bx - 12)} ${num(by - 24)} L${num(bx - 12)} ${num(by - 10)} Z " +
                "M$b $ty L${num(bx + 12)} ${num(by - 24)} L${num(bx + 12)} ${num(by - 10)} Z",
            StanColor.Accent,
        ),
    )
    o.add(stroke("M116 2 Q186 14 184 70 Q182 102 160 104", StanColor.Cream))
    o.add(circle(158.0, 104.0, 12.0, StanColor.Cream))
}
