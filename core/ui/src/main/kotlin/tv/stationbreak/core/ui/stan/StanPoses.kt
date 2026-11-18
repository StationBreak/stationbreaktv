// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // Ported from the motion study: every number is a coordinate or a time in its data.

package tv.stationbreak.core.ui.stan

import kotlin.math.PI
import kotlin.math.sin

/*
 * Poses the scenes and the host share: the study's tuning, sleeping and shrugging Stan, kept in one place so the
 * host's moods are the scenes' own poses, not copies. StanFixtureTest holds the scenes (and so these) to the study.
 */

/** Working the aerial, as the standby scene does: the right hand on the ear tip, the ears jiggling by [busy] (0..1). */
internal fun tuning(
    t: Double,
    busy: Double,
    face: StanFace,
) = StanState(
    arms = Arms.Tune,
    earR = busy * (6 * sin(t * 9) + 3 * sin(t * 2.3)),
    earL = busy * 3.5 * sin(t * 9 + 1.4),
    face = face,
)

/** A sleeping Stan's breath at [t] seconds, -1..1: [asleep] and [sleepSquash] take it. */
internal fun sleepBreath(t: Double) = sin(t * 1.5)

/** How tall a sleeping Stan is drawn as he breathes by [b]: the frame's `sy`, before rounding to [SQUASH_ROUNDING]. */
internal fun sleepSquash(b: Double) = 1 + .014 * b

/** The frame's `sy` is rounded to 1/10000. */
internal const val SQUASH_ROUNDING = 10000.0

/**
 * How far up a squashed sleeper is moved per unit of `sy` above 1, in stage units at [SLATE_SCALE], so he stays
 * sitting on his shadow as he breathes. Divide by [SLATE_SCALE] for Stan's own units.
 */
internal const val SLEEP_LIFT = 230.0

/**
 * Asleep, as off the air: sitting, in his nightcap, mouth an O, breathing by [b] (-1..1). With [bear] he hugs his
 * teddy; without, his arms rest.
 */
internal fun asleep(
    b: Double,
    bear: Boolean,
) = StanState(
    legs = Legs.Sit,
    arms = if (bear) Arms.Bear else Arms.Rest,
    bear = bear,
    cap = 3 * b,
    earR = 1.5 * b,
    face = StanFace(eyes = Eyes.Sleep, mouth = Mouth.O, r = 6.5 + 1.5 * b, cheeks = true),
)

/** The three Zs drifting up off a sleeping Stan at [t] seconds, added to his [shapes] (his own space). */
internal fun addSleepZs(
    shapes: MutableList<StanShape>,
    t: Double,
) {
    for (i in 0 until 3) {
        val ph = ((t / 3.6) + i / 3.0) % 1
        shapes.add(glyph("Z", 112 + ph * 110, -112 - ph * 150, 34 + ph * 50, StanColor.Accent, sin(PI * ph)))
    }
}

/** The no-signal shrug: ears drooping, arms out, looking about with his brows up. */
internal fun shrugging(t: Double) =
    StanState(
        ears = Ears.Droop,
        droop = 3 * sin(t * 1.6),
        arms = Arms.Shrug,
        armBob = 5 * sin(t * 2.2),
        face = StanFace(eyes = Eyes.Open, px = 6 * sin(t * 1.1), mouth = Mouth.Flat, brows = true),
    )
