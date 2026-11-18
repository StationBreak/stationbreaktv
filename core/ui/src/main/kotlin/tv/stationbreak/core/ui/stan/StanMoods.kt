// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // Poses and timings in the motion study's own units, from the approved host mock-up.

package tv.stationbreak.core.ui.stan

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/*
 * Stan as a host: one looping frame per [StanMood], built from the study's own pose pieces (the scenes' tuning,
 * shrugging and sleeping poses, shared with them, not copied) in a small space of his own, with no ground. [StanHost]
 * draws them.
 */

/** The host's space, in Stan's units: Stan and his shadow with room above for a sleeping Stan's Zs. */
internal const val HOST_W = 600.0
internal const val HOST_H = 640.0

private const val HOST_X = 300.0
private const val HOST_Y = 380.0
private const val HOST_SCALE = 1.0

/** Sitting legs end 16 units higher than standing ones: a dozing Stan sits that much lower, on his shadow. */
private const val SIT_DROP = 16.0

/** The shadow under his feet (standing feet end at 166 under his middle). */
private val HOST_SHADOW = StanShadow(HOST_X, HOST_Y + 166, 140.0, 16.0)

private const val BLINK_EVERY = 3.7
private const val BLINK_FOR = .12
private const val HOP_FOR = .6
private const val HOP_RATE = 10.5
private const val HOP_HEIGHT = 14.0

/** The talking mouth flips every 0.1 s: two ticks of the host's 20 fps clock, so no flip is ever skipped. */
private const val FLAP_EVERY = .1
private const val TALK_MOUTH_R = 9.0

/** A standing Stan, arms at rest, his ears swaying a little. */
private fun standing(
    t: Double,
    face: StanFace,
): StanState {
    val sway = 2 * sin(t * 1.3)
    return StanState(arms = Arms.Rest, earL = sway, earR = -sway, face = face)
}

private fun pose(
    mood: StanMood,
    t: Double,
): StanState =
    when (mood) {
        StanMood.Idle -> {
            val open = if (t % BLINK_EVERY < BLINK_FOR) .1 else 1.0
            standing(t, StanFace(eyes = Eyes.Open, mouth = Mouth.Smile, open = open))
        }

        StanMood.Busy -> {
            tuning(t, 1.0, StanFace(eyes = Eyes.Open, mouth = Mouth.Tongue, px = 4.0, py = -8.0))
        }

        StanMood.Pleased -> {
            standing(t, StanFace(eyes = Eyes.Happy, mouth = Mouth.Smile, cheeks = true))
        }

        StanMood.Shrug -> {
            shrugging(t)
        }

        StanMood.Cheer -> {
            val k = sin(t * 8)
            StanState(
                arms = Arms.Cheer,
                armBob = 6 * k,
                earL = 4 * k,
                earR = -4 * k,
                face = StanFace(eyes = Eyes.Happy, mouth = Mouth.Smile, cheeks = true),
            )
        }

        StanMood.Dozing -> {
            asleep(sleepBreath(t), bear = false)
        }
    }

/** Busy is busy with the aerial and Dozing is asleep: neither moves his mouth for a line. */
private fun talks(mood: StanMood) = mood != StanMood.Busy && mood != StanMood.Dozing

/**
 * Stan's pose for [mood] at [t] seconds since the mood began. With [talking] (a new line is up), his mouth flips
 * between a smile and an O every 0.1 s, except while busy or dozing.
 */
internal fun hostState(
    mood: StanMood,
    t: Double,
    talking: Boolean,
): StanState {
    val s = pose(mood, t)
    if (!talking || !talks(mood)) return s
    val mouth = if (floor(t / FLAP_EVERY).toLong() % 2 == 0L) Mouth.Smile else Mouth.O
    return s.copy(face = s.face.copy(mouth = mouth, r = TALK_MOUTH_R))
}

/** How far Stan is lifted (negative) or lowered from his place, in his units. */
private fun bob(
    mood: StanMood,
    t: Double,
): Double =
    when (mood) {
        StanMood.Idle -> {
            2 * sin(t * 2)
        }

        StanMood.Busy -> {
            2.5 * sin(t * 3)
        }

        // One hop (the first lobe of the sine, over by 0.3 s), still until 0.6 s, then the idle bob from rest: no jump.
        StanMood.Pleased -> {
            when {
                t < PI / HOP_RATE -> -HOP_HEIGHT * sin(t * HOP_RATE)
                t < HOP_FOR -> 0.0
                else -> 2 * sin((t - HOP_FOR) * 2)
            }
        }

        StanMood.Shrug -> {
            2 * sin(t * 2.2)
        }

        StanMood.Cheer -> {
            -10 * abs(sin(t * 5))
        }

        // Sitting lower, and lifted as he breathes in so he stays on his shadow (off-air's lift, in his units).
        StanMood.Dozing -> {
            SIT_DROP - (squash(mood, t) - 1) * SLEEP_LIFT / SLATE_SCALE * HOST_SCALE
        }
    }

/** How tall Stan is drawn: a dozing Stan breathes as off the air does; otherwise 1. */
private fun squash(
    mood: StanMood,
    t: Double,
): Double = if (mood == StanMood.Dozing) roundTo(sleepSquash(sleepBreath(t)), SQUASH_ROUNDING) else 1.0

/**
 * The line the host shows for [line] in [mood]: none while he dozes, and none for a blank line, so a screen can pass
 * whatever it has without drawing an empty bubble.
 */
internal fun hostLine(
    mood: StanMood,
    line: String?,
): String? = line?.takeIf { mood != StanMood.Dozing && it.isNotBlank() }

/** How the host's [HOST_W] x [HOST_H] space sits in a canvas of [width] x [height] pixels. */
internal fun hostTransform(
    width: Float,
    height: Float,
) = StanStageTransform(width, height, HOST_W.toFloat(), HOST_H.toFloat())

/** One host frame: Stan in [mood] at [t] seconds, in the host's [HOST_W] x [HOST_H] space, on no ground. */
internal fun hostFrame(
    mood: StanMood,
    t: Double,
    talking: Boolean,
): StanFrame {
    val shapes = stanShapes(hostState(mood, t, talking))
    if (mood == StanMood.Dozing) addSleepZs(shapes, t)
    return StanFrame(
        bg = StanBackground.None,
        back = emptyList(),
        shadow = HOST_SHADOW,
        x = HOST_X,
        y = r1(HOST_Y + bob(mood, t)),
        sc = HOST_SCALE,
        rot = 0.0,
        sy = squash(mood, t),
        stan = shapes,
        blocks = null,
        title = null,
        fade = 1.0,
    )
}
