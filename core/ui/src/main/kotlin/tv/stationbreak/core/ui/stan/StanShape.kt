// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("LongParameterList") // One field per number of one shape, as the motion study gives them.

package tv.stationbreak.core.ui.stan

import kotlin.math.abs
import kotlin.math.floor

/**
 * Stan's colour tokens. A shape names a token, never a colour: the palette ([StanPalette]) decides what each one is,
 * which is how the same shapes serve the ink ground and the paper ground. [token] is the name the motion study (and
 * the fixture) uses.
 */
internal enum class StanColor(
    val token: String,
) {
    Cream("cream"),
    Ink("ink"),
    Accent("accent"),
    Grey("grey"),
    Black("black"),
}

/**
 * One thing to draw, in the order it is drawn. Pure data with no drawing types, so the logic that produces it is
 * unit-testable on the JVM. Numbers are already rounded the way the motion study rounds them ([r1], [r2]), and
 * coordinates are in Stan's own space (the cabinet is 240 x 180, centred on 0,0) unless the shape is in
 * [StanFrame.back], which is in the 1920 x 1080 stage space.
 */
internal sealed interface StanShape

/** An open or closed path, stroked with round caps and joins. */
internal data class StanStroke(
    val d: String,
    val c: StanColor,
    val w: Double,
    val a: Double,
) : StanShape

/** A filled path. */
internal data class StanFill(
    val d: String,
    val c: StanColor,
    val a: Double,
) : StanShape

/** A circle, filled, or an unfilled ring when [sw] is above 0. */
internal data class StanCircle(
    val x: Double,
    val y: Double,
    val r: Double,
    val c: StanColor,
    val a: Double,
    val sw: Double,
) : StanShape

/** An ellipse. */
internal data class StanOval(
    val x: Double,
    val y: Double,
    val rx: Double,
    val ry: Double,
    val c: StanColor,
    val a: Double,
) : StanShape

/**
 * A rounded rectangle, turned [rot] degrees about ([px], [py]) and outlined in [sc] at width [sw] when [sc] is set.
 * [w], [h] and [r] are whole numbers in the study, so they are not rounded.
 */
internal data class StanRoundRect(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val r: Double,
    val c: StanColor,
    val rot: Double,
    val px: Double,
    val py: Double,
    val sc: StanColor?,
    val sw: Double,
) : StanShape

/** One character of the brand face (Ranchers), baseline at [x], [y]; [mid] centres it on [x]. */
internal data class StanGlyph(
    val ch: String,
    val x: Double,
    val y: Double,
    val size: Double,
    val c: StanColor,
    val a: Double,
    val mid: Boolean,
) : StanShape

/** Opens a group turned [rot] degrees about ([px], [py]); the matching [StanPop] closes it. */
internal data class StanPush(
    val rot: Double,
    val px: Double,
    val py: Double,
) : StanShape

/** Opens a group clipped to the path [d]; the matching [StanPop] closes it. */
internal data class StanClip(
    val d: String,
) : StanShape

/** Closes the innermost [StanPush] or [StanClip]. */
internal data object StanPop : StanShape

/** Static over Stan's screen at alpha [a]: noise tile [tile] (0..5), and a rolling bar at [roll] (0..1). */
internal data class StanSnow(
    val a: Double,
    val tile: Int,
    val roll: Double,
) : StanShape

/** A moon: the circle at [x], [y], [r] with the circle at [ox], [oy], [or] cut out of it. Stage space. */
internal data class StanCrescent(
    val x: Double,
    val y: Double,
    val r: Double,
    val ox: Double,
    val oy: Double,
    val or: Double,
    val c: StanColor,
) : StanShape

/** The study's rounding to one decimal (JavaScript's Math.round: halves go up), with no negative zero. */
internal fun r1(v: Double): Double = roundTo(v, TENTHS)

/** The study's rounding to two decimals. */
internal fun r2(v: Double): Double = roundTo(v, HUNDREDTHS)

/** Rounds to [scale] steps per unit the way JavaScript's Math.round does, keeping a negative zero out of the data. */
internal fun roundTo(
    v: Double,
    scale: Double,
): Double {
    val r = floor(v * scale + 0.5) / scale
    return if (r == 0.0) 0.0 else r
}

/** A number as JavaScript prints it in a template string: whole numbers without ".0", so path strings match. */
internal fun num(v: Double): String = if (v == floor(v) && abs(v) < WHOLE_LIMIT) v.toLong().toString() else v.toString()

private const val WHOLE_LIMIT = 1e15
private const val TENTHS = 10.0
private const val HUNDREDTHS = 100.0
