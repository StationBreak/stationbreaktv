// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

@UnstableApi
class LevellingAudioProcessorTest {
    private val rate = 48_000
    private val ceiling = 10.0.pow(LevellingAudioProcessor.CEILING_DBFS / 20.0)

    private fun processor(
        channels: Int = 2,
        encoding: Int = C.ENCODING_PCM_16BIT,
        level: LoudnessLevel = LoudnessLevel(),
        leading: () -> Boolean = { true },
        enabled: () -> Boolean = { true },
    ): LevellingAudioProcessor {
        val p = LevellingAudioProcessor(level, leading, enabled)
        p.configure(AudioFormat(rate, channels, encoding))
        p.flush()
        return p
    }

    /** A 997 Hz sine, the same on both channels, whose BS.1770 loudness is [lufs] (stereo: 20 log10 amplitude). */
    private fun sine(
        lufs: Double,
        seconds: Double,
        channels: Int = 2,
        peakCap: Double = 1.0,
    ): ShortArray {
        val amp = minOf(10.0.pow(lufs / 20.0), peakCap)
        val frames = (seconds * rate).toInt()
        return ShortArray(frames * channels) { i ->
            val f = i / channels
            (amp * sin(2 * PI * 997.0 * f / rate) * 32768.0).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** Feeds [input] in 20 ms chunks and returns everything that came out. */
    private fun run(
        p: LevellingAudioProcessor,
        input: ShortArray,
        channels: Int = 2,
    ): ShortArray {
        val out = ArrayList<Short>(input.size)
        val chunk = rate / 50 * channels
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

    /** Stereo-identical loudness of the last [seconds] of [pcm]. */
    private fun lufsOfTail(
        pcm: ShortArray,
        seconds: Double,
    ): Double {
        val n = (seconds * rate).toInt() * 2
        var sum = 0.0
        for (i in pcm.size - n until pcm.size) sum += (pcm[i] / 32768.0).pow(2)
        return 10 * log10(2 * sum / n)
    }

    @Test
    fun quietShowIsBoostedAndStopsAtTheBoostCap() {
        val p = processor()
        val out = run(p, sine(-40.0, 25.0))
        // -40 needs +16 dB: the cap is +12, so it ends at the cap, at -28.
        assertEquals(LevellingAudioProcessor.MAX_BOOST_DB, p.currentGainDb, 0.15)
        assertEquals(-28.0, lufsOfTail(out, 1.0), 1.0)
    }

    @Test
    fun aShowWithinReachConvergesOnTarget() {
        val p = processor()
        val out = run(p, sine(-30.0, 25.0))
        assertEquals(LevellingAudioProcessor.TARGET_LKFS, lufsOfTail(out, 1.0), 1.0)
        assertEquals(6.0, p.currentGainDb, 0.5)
    }

    @Test
    fun loudShowIsCutAndStopsAtTheCutCap() {
        val p = processor()
        val out = run(p, sine(-10.0, 25.0))
        // -10 needs -14 dB: the cap is -12, so it ends at -22.
        assertEquals(-LevellingAudioProcessor.MAX_CUT_DB, p.currentGainDb, 0.15)
        assertEquals(-22.0, lufsOfTail(out, 1.0), 1.0)
    }

    @Test
    fun aLoudShowWithinReachConvergesDownToTarget() {
        val p = processor()
        val out = run(p, sine(-16.0, 25.0))
        assertEquals(LevellingAudioProcessor.TARGET_LKFS, lufsOfTail(out, 1.0), 1.0)
    }

    @Test
    fun gainSlewsNoFasterThanOneDbPerSecond() {
        val p = processor()
        run(p, sine(-40.0, 5.0))
        // 5 s at 1 dB/s, plus the one block of ramp: never past 5.1 dB.
        assertTrue("gain ${p.currentGainDb}", p.currentGainDb <= 5.1)
        assertTrue("gain ${p.currentGainDb}", p.currentGainDb >= 4.0)
    }

    @Test
    fun aStepFromQuietToLoudNeverPassesTheCeiling() {
        val p = processor()
        val quiet = run(p, sine(-30.0, 15.0))
        assertTrue(p.currentGainDb > 5.0)
        // Full-scale-ish loud sine: the boosted gain would push it far over 0 dBFS.
        val loud = run(p, sine(-3.0, 3.0))
        val worst = (quiet + loud).maxOf { abs(it.toInt()) } / 32768.0
        assertTrue("peak $worst", worst <= ceiling + 1.0 / 32768.0)
        // And the limiter really worked: unlimited, the first loud samples would have been above it.
        assertTrue(loud.take(rate / 10).maxOf { abs(it.toInt()) } / 32768.0 > ceiling * 0.95)
    }

    @Test
    fun aBoostedQuietPeakyShowDoesNotClip() {
        val p = processor()
        // Quiet in loudness but with a hot peak every second: the limiter must hold -1 dBFS.
        val base = sine(-34.0, 30.0)
        for (s in 0 until 30) for (i in 0 until 480) base[(s * rate + i) * 2] = 30_000
        val out = run(p, base)
        assertTrue(out.maxOf { abs(it.toInt()) } / 32768.0 <= ceiling + 1.0 / 32768.0)
    }

    @Test
    fun otherEncodingsPassThroughUntouched() {
        val others = listOf(C.ENCODING_PCM_24BIT, C.ENCODING_PCM_8BIT, C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_DTS)
        for (encoding in others) {
            val p = LevellingAudioProcessor()
            p.configure(AudioFormat(rate, 2, encoding))
            p.flush()
            assertFalse("encoding $encoding", p.isActive)
        }
        assertTrue(processor().isActive)
        assertTrue(processor(encoding = C.ENCODING_PCM_FLOAT).isActive)
    }

    @Test
    fun silenceDoesNotWindTheGainUp() {
        val p = processor()
        run(p, ShortArray(rate * 2 * 60))
        assertEquals(0.0, p.currentGainDb, 0.01)
    }

    @Test
    fun silenceAfterAShowHoldsItsGain() {
        val p = processor()
        run(p, sine(-30.0, 15.0))
        val before = p.currentGainDb
        run(p, ShortArray(rate * 2 * 30))
        assertEquals(before, p.currentGainDb, 0.01)
    }

    @Test
    fun flushKeepsTheGain() {
        val p = processor()
        run(p, sine(-30.0, 15.0))
        val before = p.currentGainDb
        assertTrue(before > 5.0)
        p.flush()
        assertEquals(before, p.currentGainDb, 0.0)
        // The first loud samples after the boundary are limited, not clipped.
        val loud = run(p, sine(-3.0, 0.5))
        assertTrue(loud.maxOf { abs(it.toInt()) } / 32768.0 <= ceiling + 1.0 / 32768.0)
    }

    @Test
    fun floatPcmIsLevelledToo() {
        val p = processor(encoding = C.ENCODING_PCM_FLOAT)
        val chunk = rate / 50
        var last = 0.0
        val amp = 10.0.pow(-30.0 / 20.0)
        for (c in 0 until 25 * 50) {
            val buf = ByteBuffer.allocateDirect(chunk * 2 * 4).order(ByteOrder.nativeOrder())
            for (f in 0 until chunk) {
                val v = (amp * sin(2 * PI * 997.0 * (c * chunk + f) / rate)).toFloat()
                buf.putFloat(v)
                buf.putFloat(v)
            }
            buf.flip()
            p.queueInput(buf)
            val o = p.getOutput()
            var m = 0.0
            while (o.hasRemaining()) m = maxOf(m, abs(o.getFloat().toDouble()))
            last = m
        }
        // -30 LUFS boosted by about 6 dB: sine peak 10^(-24/20) = 0.063, under the ceiling.
        assertEquals(10.0.pow(-24.0 / 20.0), last, 0.006)
    }

    @Test
    fun surroundLayoutsAreAccepted() {
        val p = processor(channels = 6)
        val frames = rate
        val pcm =
            ShortArray(frames * 6) { i -> (0.01 * sin(2 * PI * 997.0 * (i / 6) / rate) * 32768).toInt().toShort() }
        val out = run(p, pcm, channels = 6)
        assertEquals(pcm.size, out.size)
    }
}
