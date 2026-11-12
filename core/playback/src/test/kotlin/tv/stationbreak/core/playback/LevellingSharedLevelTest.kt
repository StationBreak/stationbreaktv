// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/** The shared level across slots, the on/off switch and the limiter's look-ahead. */
@UnstableApi
class LevellingSharedLevelTest {
    private val rate = 48_000
    private val ceiling = 10.0.pow(LevellingAudioProcessor.CEILING_DBFS / 20.0)
    private val lsb = 1.0 / 32768.0

    private fun processor(
        level: LoudnessLevel = LoudnessLevel(),
        leading: () -> Boolean = { true },
        enabled: () -> Boolean = { true },
    ): LevellingAudioProcessor {
        val p = LevellingAudioProcessor(level, leading, enabled)
        p.configure(AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        return p
    }

    private fun sine(
        lufs: Double,
        seconds: Double,
    ): ShortArray {
        val amp = 10.0.pow(lufs / 20.0)
        return ShortArray((seconds * rate).toInt() * 2) { i ->
            (amp * sin(2 * PI * 997.0 * (i / 2) / rate) * 32768.0).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** [frames] stereo frames of the constant [value], as 16-bit PCM. */
    private fun constant(
        value: Double,
        frames: Int,
    ): ShortArray = ShortArray(frames * 2) { (value * 32768.0).toInt().toShort() }

    private fun run(
        p: LevellingAudioProcessor,
        input: ShortArray,
    ): ShortArray {
        val out = ArrayList<Short>(input.size)
        val chunk = rate / 50 * 2
        var at = 0
        while (at < input.size) {
            val n = minOf(chunk, input.size - at)
            val buf = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
            for (i in 0 until n) buf.putShort(input[at + i])
            buf.flip()
            p.queueInput(buf)
            val o = p.getOutput()
            while (o.hasRemaining()) out.add(o.getShort())
            at += n
        }
        return out.toShortArray()
    }

    private fun lufsOfTail(
        pcm: ShortArray,
        seconds: Double,
    ): Double {
        val n = (seconds * rate).toInt() * 2
        var sum = 0.0
        for (i in pcm.size - n until pcm.size) sum += (pcm[i] / 32768.0).pow(2)
        return 10 * log10(2 * sum / n)
    }

    private fun left(pcm: ShortArray): List<Double> = (0 until pcm.size / 2).map { pcm[it * 2] / 32768.0 }

    @Test
    fun aStandbyCreatedAfterTheFrontConvergedStartsAtTheFrontsGain() {
        val level = LoudnessLevel()
        val front = processor(level)
        run(front, sine(-30.0, 15.0))
        assertTrue(front.currentGainDb > 5.0)

        val standby = processor(level, leading = { false })
        assertEquals(front.currentGainDb, standby.currentGainDb, 0.0)
    }

    @Test
    fun aStandbyLevelsItsOwnAudioAndNeverWritesTheSharedGain() {
        // Since the fast settle (#498) a standby learns its own item instead of following the front's gain.
        val level = LoudnessLevel()
        val standby = processor(level, leading = { false })
        // Loud audio on a standby turns its own gain down; the shared gain does not move.
        run(standby, sine(-10.0, 5.0))
        assertEquals(0.0, level.gainDb, 0.0)
        assertTrue(standby.currentGainDb < -2.0)
        // The front learns; the standby keeps its own gain, whatever the front's is.
        val own = standby.currentGainDb
        run(processor(level), sine(-30.0, 10.0))
        assertTrue(level.gainDb > 4.0)
        assertEquals(own, standby.currentGainDb, 0.0)
    }

    @Test
    fun theLimiterAttackIsARampNotAStep() {
        // +12 dB already learned (as a front's would be), a quiet constant, then a jump that needs a lot of reduction.
        val level = LoudnessLevel().apply { gainDb = LevellingAudioProcessor.MAX_BOOST_DB }
        val quiet = 0.01
        val out = run(processor(level), constant(quiet, rate / 2) + constant(0.5, rate / 2))
        assertTrue(out.maxOf { abs(it.toInt()) } * lsb <= ceiling + lsb)
        val l = left(out)
        val settled = quiet * 10.0.pow(LevellingAudioProcessor.MAX_BOOST_DB / 20.0)
        // From the middle of the quiet part (after the look-ahead's initial silence).
        val first = (rate / 4) + l.drop(rate / 4).indexOfFirst { abs(it - settled) > settled * 0.05 }
        val last = l.indexOfFirst { it > ceiling * 0.95 }
        assertTrue("first $first last $last", first in 1 until last)
        assertTrue("the output rose over ${last - first} frames", last - first >= rate / 1000)
    }

    @Test
    fun aHardJumpIsReducedBeforeItArrives() {
        val level = LoudnessLevel().apply { gainDb = LevellingAudioProcessor.MAX_BOOST_DB }
        val quietFrames = rate / 2
        val out = run(processor(level), constant(0.01, quietFrames) + constant(0.5, rate / 10))
        val l = left(out)
        // Nothing the output holds is above the ceiling, from the first loud frame on.
        assertTrue(l.all { it <= ceiling + lsb })
        // The reduction has begun before the loud audio reaches the output (the output is the input delayed).
        val lookahead = (LevellingAudioProcessor.LOOKAHEAD_SEC * rate).toInt()
        val quietLevel = l[quietFrames / 2]
        assertTrue(l[quietFrames + lookahead - 2] < quietLevel * 0.6)
    }

    @Test
    fun offPassesAudioUnchangedAndLeavesTheSharedGainAlone() {
        val level = LoudnessLevel()
        val input = sine(-40.0, 5.0)
        val out = run(processor(level, enabled = { false }), input)
        assertTrue(input.contentEquals(out))
        assertEquals(0.0, level.gainDb, 0.0)
    }

    @Test
    fun turningItOnAndOffTakesEffectWithoutReconfiguring() {
        var on = false
        val p = processor(enabled = { on })
        // Above the settle phase's boost floor (BOOST_FLOOR_LKFS): a re-enable settles from 0 dB.
        val before = run(p, sine(-34.0, 5.0))
        assertEquals(0.0, p.currentGainDb, 0.0)
        on = true
        val after = run(p, sine(-34.0, 10.0))
        assertTrue(p.currentGainDb > 5.0)
        assertTrue(lufsOfTail(after, 1.0) > lufsOfTail(before, 1.0) + 5.0)
        on = false
        val again = sine(-40.0, 1.0)
        assertTrue(again.contentEquals(run(p, again)))
    }

    @Test
    fun theEndOfAStreamLeavesTheDelayedTail() {
        val p = processor()
        val input = constant(0.1, rate / 10)
        val out = run(p, input)
        // The first frames out are the look-ahead's silence, so the last of the input is still inside.
        assertEquals(input.size, out.size)
        p.queueEndOfStream()
        val tail = p.getOutput()
        val lookahead = (LevellingAudioProcessor.LOOKAHEAD_SEC * rate).toInt()
        assertEquals((lookahead - 1) * 2 * 2, tail.remaining())
        var last = 0
        while (tail.hasRemaining()) last = tail.getShort().toInt()
        // The tail ends with the input's last frame (a constant, at unity gain).
        assertEquals(0.1 * 32768.0, last.toDouble(), 2.0)
    }

    /** A [hz] sine of peak [amp], the same on both channels. */
    private fun tone(
        hz: Double,
        amp: Double,
        seconds: Double,
    ): ShortArray =
        ShortArray((seconds * rate).toInt() * 2) { i ->
            (amp * sin(2 * PI * hz * (i / 2) / rate) * 32768.0).toInt().coerceIn(-32768, 32767).toShort()
        }

    // The limiter must hold the ceiling by itself: the final clamp is only a guard. A slow tone whose gained peak is
    // about twice the ceiling needs the sliding minimum to hold its reduction for longer than the look-ahead.
    private fun assertLimiterHoldsWithoutTheClamp(hz: Double) {
        val level = LoudnessLevel().apply { gainDb = LevellingAudioProcessor.MAX_BOOST_DB }
        val p = processor(level)
        run(p, tone(hz, 0.5, 3.0))
        assertTrue("$hz Hz: pre-clamp ${p.maxPreClamp}", p.maxPreClamp <= ceiling + lsb)
    }

    @Test
    fun aTwentyHertzTone_atTwiceTheCeiling_isLimitedWithoutTheClamp() = assertLimiterHoldsWithoutTheClamp(20.0)

    @Test
    fun aTwentyFiveHertzTone_atTwiceTheCeiling_isLimitedWithoutTheClamp() = assertLimiterHoldsWithoutTheClamp(25.0)

    @Test
    fun aQuietToLoudStep_isLimitedWithoutTheClamp() {
        val level = LoudnessLevel().apply { gainDb = LevellingAudioProcessor.MAX_BOOST_DB }
        val p = processor(level)
        run(p, constant(0.01, rate / 2) + constant(0.5, rate / 2))
        assertTrue("pre-clamp ${p.maxPreClamp}", p.maxPreClamp <= ceiling + lsb)
    }

    @Test
    fun aQuietShowWithAHotPeakEverySecond_isLimitedWithoutTheClamp() {
        val p = processor()
        val base = sine(-34.0, 30.0)
        for (s in 0 until 30) for (i in 0 until 480) base[(s * rate + i) * 2] = 30_000
        run(p, base)
        assertTrue("pre-clamp ${p.maxPreClamp}", p.maxPreClamp <= ceiling + lsb)
    }

    @Test
    fun turningItBackOnStartsFromZeroDb_notFromWhatAnEarlierShowLearned() {
        val level = LoudnessLevel()
        var on = true
        val p = processor(level, enabled = { on })
        run(p, sine(-40.0, 25.0))
        assertTrue(p.currentGainDb > 11.0)
        on = false
        run(p, sine(-10.0, 5.0))
        on = true
        // The first buffer after it comes back on is 20 ms: the gain is 0 dB, give or take one slew step.
        run(p, sine(-10.0, 0.02))
        assertEquals(0.0, p.currentGainDb, 0.15)
        assertEquals(0.0, level.gainDb, 0.15)
    }

    @Test
    fun resetKeepsTheGain() {
        val p = processor()
        run(p, sine(-30.0, 15.0))
        val before = p.currentGainDb
        p.reset()
        assertEquals(before, p.currentGainDb, 0.0)
    }
}
