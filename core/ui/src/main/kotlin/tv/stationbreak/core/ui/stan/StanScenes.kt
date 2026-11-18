// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress(
    "MagicNumber", // A line-for-line port of the motion study: every number is a coordinate or a time in its data.
    "LongParameterList", // One argument per field of the frame, as the study's scene objects have them.
)

package tv.stationbreak.core.ui.stan

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * The five scenes of the "Stan In Motion" motion study, ported from its `SCENE[k](t)`: each takes a time in seconds
 * and returns one frame, so a screen only has to feed in a clock. The study is the source of truth; the fixture test
 * (StanFixtureTest) holds this port to its output.
 *
 * Cost: a frame is about 60 small shape objects plus some path strings (the ones with a moving number in them), all
 * built fresh from the clock. That is the engine's per-frame allocation: a few kilobytes, short-lived. The drawing
 * layer adds almost none (see StanStage).
 */

/**
 * The scenes. Each is a loop of [loopSeconds]: the study's own cycle for that scene (`t % loop`), after which its main
 * beat starts again. The small sways (ears, arms, bobbing) run on their own periods and are not locked to it.
 *
 * [runSeconds] is set for a scene that is a one-off run, not a loop to leave on: the time at which it has faded out
 * completely, so a screen can move on (the load screen dissolves to the guide) without hard-coding it. Null for the
 * scenes that run for as long as the screen is up.
 */
enum class StanScene(
    internal val key: String,
    val loopSeconds: Double,
    val runSeconds: Double? = null,
) {
    /** "Please stand by": he works the aerial until the picture comes in, then loses it. Blocks count up. */
    Standby("standby", 5.2),

    /**
     * The load screen: dozing, wakes, stands up and waves while the name comes in, then fades out. The study loops it
     * every 6.4 seconds; as a load screen it is over at [runSeconds] (6.2), when [StanFrame.fade] reaches 0.
     */
    Opening("opening", 6.4, OPENING_END),

    /** "Off the air": asleep with his bear, Zs drifting up, stars twinkling. */
    OffAir("offair", 3.6),

    /** "No signal": looks both ways and shrugs, his screen flickering now and then. */
    NoSignal("nosignal", 6.0),

    /** "Technical difficulties": wobbling, sparks, smoke off his bent ear and static now and then. */
    Trouble("trouble", 3.2),
    ;

    /** The frame for this scene at [t] seconds. */
    fun frame(t: Double): StanFrame =
        when (this) {
            Standby -> standby(t)
            Opening -> opening(t)
            OffAir -> offAir(t)
            NoSignal -> noSignal(t)
            Trouble -> trouble(t)
        }
}

/** Which ground a scene stands on: [Left] lifts toward the left (copy beside Stan), [Centre] toward the middle. */
internal enum class StanBackground { Left, Centre }

/** The ellipse under Stan's feet, in stage space. */
internal data class StanShadow(
    val cx: Double,
    val cy: Double,
    val rx: Double,
    val ry: Double,
)

/**
 * The load screen's name coming in, for a screen that lays out its own text. The name is "Station" (cream) then "Break"
 * (accent) on one line, then a tagline under it; each fades in (0..1) in that order, and the whole block starts
 * [offsetY] stage units low and rises to rest as "Station" arrives.
 */
data class StanTitle(
    /** Opacity of "Station", the first half of the name. Comes in first. */
    val nameStationAlpha: Double,
    /** Opacity of "Break", the second half of the name. Comes in second. */
    val nameBreakAlpha: Double,
    /** Opacity of the tagline under the name. Comes in last. */
    val taglineAlpha: Double,
    /** How far below its resting place the name is, in stage units (1920 x 1080); 18 at the start, 0 when settled. */
    val offsetY: Double,
)

/**
 * One frame of a scene. Screens read only what they lay their own text with: [blocks], [title] and [fade]; the
 * picture itself (the ground, the shadow, Stan and everything around him) is for [StanStage] to draw.
 *
 * [blocks] is how many of the seven standby blocks are lit (null when the scene has none); [title] is the load
 * screen's name coming in (null when the scene has none); [fade] (1 down to 0) is how much of the picture is left, for
 * the opening's dissolve: a screen fades its own text with it.
 */
class StanFrame internal constructor(
    internal val bg: StanBackground,
    internal val back: List<StanShape>,
    internal val shadow: StanShadow,
    internal val x: Double,
    internal val y: Double,
    internal val sc: Double,
    internal val rot: Double,
    internal val sy: Double,
    internal val stan: List<StanShape>,
    val blocks: Int?,
    val title: StanTitle?,
    val fade: Double,
)

private const val OPENING_FADE_START = 5.6
private const val OPENING_END = 6.2

private val SLATE_SHADOW = StanShadow(560.0, 862.0, 330.0, 34.0)
private const val SLATE_X = 560.0
private const val SLATE_SCALE = 1.72
private const val TILES = 6

private fun clamp(v: Double) = max(0.0, min(1.0, v))

private fun ramp(
    t: Double,
    a: Double,
    b: Double,
) = clamp((t - a) / (b - a))

/** Ease out with an overshoot (the study's `back`). */
private fun overshoot(x: Double): Double {
    val c = 1.70158
    return 1 + (c + 1) * (x - 1).pow(3) + c * (x - 1).pow(2)
}

private fun tile(t: Double) = (floor(t * 14) % TILES).toInt()

private fun roll(t: Double) = (t * .9) % 1

private fun standby(t: Double): StanFrame {
    val p = t % 5.2
    val snow =
        when {
            p < 1.3 -> 1.0
            p < 1.7 -> 1 - (p - 1.3) / .4
            p < 3.9 -> 0.0
            p < 4.4 -> if (sin(p * 70) > 0) .85 else .25
            else -> 1.0
        }
    val busy = if (snow > .05) 1.0 else .25
    val look = p > 2.3 && p < 3.5
    val s =
        StanState(
            arms = Arms.Tune,
            earR = busy * (6 * sin(t * 9) + 3 * sin(t * 2.3)),
            earL = busy * 3.5 * sin(t * 9 + 1.4),
            snow = snow,
            tile = tile(t),
            roll = roll(t),
            face =
                StanFace(
                    eyes = Eyes.Open,
                    px = if (look) 0.0 else 4.0,
                    py = if (look) 1.0 else -8.0,
                    mouth = if (look) Mouth.Smile else Mouth.Tongue,
                    cheeks = look,
                ),
        )
    return StanFrame(
        bg = StanBackground.Left,
        back = emptyList(),
        shadow = SLATE_SHADOW,
        x = SLATE_X,
        y = r1(580 + 2.5 * sin(t * 3)),
        sc = SLATE_SCALE,
        rot = 0.0,
        sy = 1.0,
        stan = stanShapes(s),
        blocks = min(7, floor(t / .45).toInt() % 10),
        title = null,
        fade = 1.0,
    )
}

private val STARS =
    listOf(
        Triple(1420.0, 150.0, 14.0),
        Triple(1790.0, 330.0, 10.0),
        Triple(1180.0, 230.0, 9.0),
        Triple(300.0, 180.0, 11.0),
        Triple(880.0, 130.0, 8.0),
    )

private fun offAir(t: Double): StanFrame {
    val b = sin(t * 1.5)
    val sy = 1 + .014 * b
    val s =
        StanState(
            legs = Legs.Sit,
            arms = Arms.Bear,
            bear = true,
            cap = 3 * b,
            earR = 1.5 * b,
            face = StanFace(eyes = Eyes.Sleep, mouth = Mouth.O, r = 6.5 + 1.5 * b, cheeks = true),
        )
    val shapes = stanShapes(s)
    for (i in 0 until 3) {
        val ph = ((t / 3.6) + i / 3.0) % 1
        shapes.add(glyph("Z", 112 + ph * 110, -112 - ph * 150, 34 + ph * 50, StanColor.Accent, sin(PI * ph)))
    }
    val back = ArrayList<StanShape>()
    back.add(StanCrescent(1640.0, 200.0, 70.0, 1668.0, 176.0, 62.0, StanColor.Cream))
    STARS.forEachIndexed { i, (x, y, r) ->
        back.add(fill(starD(x, y, r), StanColor.Cream, .55 + .45 * sin(t * 1.7 + i * 2.1)))
    }
    return StanFrame(
        bg = StanBackground.Left,
        back = back,
        shadow = SLATE_SHADOW,
        x = SLATE_X,
        y = r1(606 - (sy - 1) * 230),
        sc = SLATE_SCALE,
        rot = 4.0,
        sy = roundTo(sy, 10000.0),
        stan = shapes,
        blocks = null,
        title = null,
        fade = 1.0,
    )
}

private fun noSignal(t: Double): StanFrame {
    val p = t % 6
    val snow = if (p > 5.3) (if (sin(p * 60) > 0) .7 else .2) else 0.0
    val s =
        StanState(
            ears = Ears.Droop,
            droop = 3 * sin(t * 1.6),
            arms = Arms.Shrug,
            armBob = 5 * sin(t * 2.2),
            snow = snow,
            tile = tile(t),
            roll = roll(t),
            face = StanFace(eyes = Eyes.Open, px = 6 * sin(t * 1.1), mouth = Mouth.Flat, brows = true),
        )
    val shapes = stanShapes(s)
    shapes.add(
        glyph("?", 0.0, -150 + 4 * sin(t * 3), 96 * (1 + .05 * sin(t * 3)), StanColor.Accent, 1.0, true),
    )
    return StanFrame(
        bg = StanBackground.Left,
        back = emptyList(),
        shadow = SLATE_SHADOW,
        x = SLATE_X,
        y = r1(580 + 2 * sin(t * 2.2)),
        sc = SLATE_SCALE,
        rot = 0.0,
        sy = 1.0,
        stan = shapes,
        blocks = null,
        title = null,
        fade = 1.0,
    )
}

private val SPARKS =
    listOf(
        Triple(-196.0, -150.0, 22.0),
        Triple(196.0, -110.0, 16.0),
        Triple(150.0, -208.0, 12.0),
        Triple(-150.0, 120.0, 12.0),
    )

private fun trouble(t: Double): StanFrame {
    val p = t % 3.2
    val snow =
        when {
            p < .25 -> .8
            p > 1.4 && p < 1.55 -> .5
            else -> 0.0
        }
    val s =
        StanState(
            legs = Legs.Kick,
            kick = 43 + 8 * sin(t * 5),
            ears = Ears.Zig,
            earL = 3 * sin(t * 5),
            arms = Arms.Flail,
            armBob = 8 * sin(t * 6),
            bandage = true,
            snow = snow,
            tile = tile(t),
            roll = roll(t),
            face = StanFace(eyes = Eyes.X, mouth = Mouth.Wavy, wob = 12 * cos(t * 8)),
        )
    val shapes = stanShapes(s)
    SPARKS.forEachIndexed { i, (x, y, r) ->
        val k = sin(t * 4 + i * 1.7)
        shapes.add(fill(starD(x, y, r * (.85 + .3 * k)), StanColor.Accent, .6 + .4 * k))
    }
    for (i in 0 until 3) {
        val ph = ((t / 2.4) + i / 3.0) % 1
        shapes.add(circle(92 + 10 * sin(ph * 6), -214 - ph * 90, 12 + ph * 18, StanColor.Grey, .5 * (1 - ph)))
    }
    return StanFrame(
        bg = StanBackground.Left,
        back = emptyList(),
        shadow = SLATE_SHADOW,
        x = r1(560 + 1.5 * sin(t * 23)),
        y = 580.0,
        sc = SLATE_SCALE,
        rot = r1(-7 + 2.5 * sin(t * 5)),
        sy = 1.0,
        stan = shapes,
        blocks = null,
        title = null,
        fade = 1.0,
    )
}

private fun openingFace(p: Double) =
    when {
        p < .6 -> {
            StanFace(eyes = Eyes.Sleep, mouth = Mouth.O, cheeks = true)
        }

        p < 1.3 -> {
            StanFace(eyes = Eyes.Open, open = ramp(p, .6, .8), py = 1.0, mouth = Mouth.Small, cheeks = true)
        }

        else -> {
            StanFace(eyes = Eyes.Happy, mouth = Mouth.Smile, cheeks = true)
        }
    }

private fun opening(t: Double): StanFrame {
    val p = t % 6.4
    val h = 64 * max(0.0, overshoot(ramp(p, 1.0, 1.5)))
    val e = ramp(p, 1.0, 1.4)
    val s =
        StanState(
            h = h,
            arms = if (p > 1.25) Arms.Wave else Arms.None,
            wave = t * 7,
            earL = if (p < 1) 0.0 else 4 * sin(t * 5) * e,
            earR = if (p < 1) 0.0 else -4 * sin(t * 5) * e,
            face = openingFace(p),
        )
    val a1 = ramp(p, 1.4, 1.8)
    return StanFrame(
        bg = StanBackground.Centre,
        back = emptyList(),
        shadow = StanShadow(960.0, 652.0, r1(200 + h), 20.0),
        x = 960.0,
        y = r1(650 - (102 + h) * 1.5),
        sc = 1.5,
        rot = 0.0,
        sy = 1.0,
        stan = stanShapes(s),
        blocks = null,
        title = StanTitle(r2(a1), r2(ramp(p, 1.6, 2.0)), r2(ramp(p, 2.2, 2.6)), r1(18 * (1 - a1))),
        fade = r2(1 - ramp(p, OPENING_FADE_START, OPENING_END)),
    )
}
