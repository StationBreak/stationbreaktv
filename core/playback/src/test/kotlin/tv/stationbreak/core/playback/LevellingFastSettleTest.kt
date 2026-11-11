// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/** Fast settle on a new item (#498): measured through a real Media3 pipeline, as the player's sink drives it. */
@UnstableApi
class LevellingFastSettleTest {
    private val rate = 48_000

    private class Rig(
        val pipeline: AudioProcessingPipeline,
        val levelling: LevellingAudioProcessor,
    ) {
        /** The item the player says this slot is playing. */
        var key: String? = "A"

        /** Whether this slot is the front. */
        var leading = true
    }

    private fun rig(level: LoudnessLevel = LoudnessLevel()): Rig {
        var made: Rig? = null
        val levelling =
            LevellingAudioProcessor(level, { made?.leading ?: true }, { true }, { made?.key })
        val p =
            AudioProcessingPipeline(
                ImmutableList.of<AudioProcessor>(
                    ToInt16PcmAudioProcessor(),
                    levelling,
                    SilenceSkippingAudioProcessor(),
                    SonicAudioProcessor(),
                ),
            )
        val r = Rig(p, levelling)
        made = r
        p.configure(AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        return r
    }

    /** A new item, as the player tunes it and the sink starts it: the key, then reset, configure, flush. */
    private fun newItem(
        r: Rig,
        key: String,
    ) {
        r.key = key
        r.pipeline.reset()
        r.pipeline.configure(AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        r.pipeline.flush()
    }

    /** A stereo 997 Hz sine at [dbfs] peak. */
    private fun sine(
        dbfs: Double,
        seconds: Double,
    ): ShortArray {
        val amp = 10.0.pow(dbfs / 20.0)
        return ShortArray((seconds * rate).toInt() * 2) { i ->
            (amp * sin(2 * PI * 997.0 * (i / 2) / rate) * 32768.0).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** Feeds [input] in 20 ms buffers; returns the output, and adds the gain in dB after each buffer to [gains]. */
    private fun feed(
        r: Rig,
        input: ShortArray,
        gains: MutableList<Double>? = null,
    ): ShortArray {
        val out = ArrayList<Short>(input.size)
        val chunk = rate / 50 * 2
        var at = 0
        while (at < input.size) {
            val n = minOf(chunk, input.size - at)
            val buf = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
            for (i in 0 until n) buf.putShort(input[at + i])
            buf.flip()
            while (buf.hasRemaining()) {
                r.pipeline.queueInput(buf)
                val o = r.pipeline.getOutput()
                while (o.hasRemaining()) out.add(o.getShort())
            }
            gains?.add(r.levelling.currentGainDb)
            at += n
        }
        return out.toShortArray()
    }

    private fun rmsDb(
        pcm: ShortArray,
        from: Int,
        frames: Int,
    ): Double {
        var sum = 0.0
        for (i in from * 2 until (from + frames) * 2) sum += (pcm[i] / 32768.0).pow(2)
        return 10 * log10(sum / (frames * 2))
    }

    /**
     * The output's gain (output level over input level) in 400 ms windows every 100 ms; the time (window end) from
     * which every window stays within [tolerance] dB of [settled]. Loudness is linear in gain, so this is the output's
     * distance from target in LU.
     */
    private fun settleSeconds(
        input: ShortArray,
        output: ShortArray,
        settled: Double,
        tolerance: Double,
    ): Double {
        val window = rate * 4 / 10
        val hop = rate / 10
        val frames = minOf(input.size, output.size) / 2
        var lastOut = -1
        var start = 0
        while (start + window <= frames) {
            val g = rmsDb(output, start, window) - rmsDb(input, start, window)
            if (abs(g - settled) > tolerance) lastOut = start
            start += hop
        }
        return (lastOut + hop).toDouble() / rate + window.toDouble() / rate
    }

    @Test
    fun aFlipFromABoostedItemToALoudOneSettlesWithinThreeSeconds() {
        val r = rig()
        // Item A, quiet: learned well past the settle phase, boosted about +6 dB.
        feed(r, sine(-30.0, 20.0))
        val learned = r.levelling.currentGainDb
        assertTrue("A learned $learned", learned > 4.0)
        newItem(r, "B")
        // Item B, loud: it needs about -6 dB.
        val input = sine(-18.0, 20.0)
        val output = feed(r, input)
        val settled = r.levelling.currentGainDb
        assertTrue("B settled at $settled", settled < -4.0)
        val t = settleSeconds(input, output, settled, LU_TOLERANCE)
        println("levelling settle: $t s (A $learned dB, B $settled dB)")
        assertTrue("settled after $t s", t <= SETTLE_LIMIT_SEC)
    }

    @Test
    fun roomToneAtAShowsStartDoesNotWindTheGainUpBeforeTheDialogue() {
        // A fresh item (0 dB): 3 s of room tone at about -48 LKFS, above the silence gate, then speech level.
        val r = rig()
        val gains = mutableListOf<Double>()
        feed(r, sine(-48.0, 3.0), gains)
        assertTrue("gain during room tone ${gains.min()}..${gains.max()}", gains.all { abs(it) <= 1.0 })
        val input = sine(-24.0, 4.0)
        val output = feed(r, input)
        val settled = LevellingAudioProcessor.TARGET_LKFS + 24.0
        val t = settleSeconds(input, output, settled, LU_TOLERANCE)
        println("levelling room tone then speech: $t s")
        assertTrue("speech settled after $t s", t <= SPEECH_LIMIT_SEC)
    }

    @Test
    fun aQuietItemThatNeedsABoostGetsItWithinSixSeconds() {
        // About -32 LKFS: it needs +8 dB, reached at the gentle boost.
        val r = rig()
        val input = sine(-32.0, 15.0)
        val output = feed(r, input)
        val settled = r.levelling.currentGainDb
        assertEquals(8.0, settled, 0.5)
        val t = settleSeconds(input, output, settled, LU_TOLERANCE)
        println("levelling quiet item +8 dB: $t s")
        assertTrue("settled after $t s", t <= QUIET_LIMIT_SEC)
    }

    @Test
    fun returningToAnItemStartsAtTheGainItLearned() {
        val r = rig()
        feed(r, sine(-30.0, 10.0))
        val learnedA = r.levelling.currentGainDb
        newItem(r, "B")
        feed(r, sine(-18.0, 10.0))
        newItem(r, "A")
        val input = sine(-30.0, 0.5)
        val output = feed(r, input)
        // The first block out, after the look-ahead's delay.
        val lookahead = (LevellingAudioProcessor.LOOKAHEAD_SEC * rate).toInt()
        val first = rmsDb(output, lookahead, rate / 10) - rmsDb(input, 0, rate / 10)
        assertEquals(learnedA, first, 0.5)
    }

    @Test
    fun anItemNeverSeenStartsAtZeroDb() {
        val r = rig()
        feed(r, sine(-30.0, 10.0))
        newItem(r, "B")
        val gains = mutableListOf<Double>()
        feed(r, sine(-30.0, 0.02), gains)
        assertEquals(0.0, gains.first(), 0.01)
    }

    @Test
    fun aSeekWithinAnItemKeepsTheGain() {
        val r = rig()
        feed(r, sine(-30.0, 10.0))
        val before = r.levelling.currentGainDb
        // A seek: the sink flushes; the item is the same.
        r.pipeline.flush()
        val gains = mutableListOf<Double>()
        feed(r, sine(-30.0, 0.02), gains)
        assertEquals(before, gains.first(), 0.01)
    }

    @Test
    fun silenceAtTheStartOfANewItemDoesNotRaiseTheGain() {
        val r = rig()
        feed(r, sine(-18.0, 10.0))
        newItem(r, "B")
        val gains = mutableListOf<Double>()
        // Two seconds under the silence gate (a -70 dBFS hum), from a start of 0 dB.
        feed(r, sine(-70.0, 2.0), gains)
        assertTrue("gain rose to ${gains.max()}", gains.all { it <= 1e-9 })
    }

    @Test
    fun afterTheSettlePhaseTheSlowSlewReturns() {
        val r = rig()
        feed(r, sine(-30.0, 10.0))
        val before = r.levelling.currentGainDb
        // A big jump within the item, well after it settled: the gain moves at the slow slew again.
        feed(r, sine(-12.0, 1.0))
        val moved = before - r.levelling.currentGainDb
        assertTrue("moved $moved dB in 1 s", moved <= LevellingAudioProcessor.MAX_SLEW_DB_PER_SEC + 0.05)
    }

    @Test
    fun aStandbyLearnsItsOwnItemAndKeepsItsGainAtTheHandOver() {
        val level = LoudnessLevel()
        val front = rig(level)
        feed(front, sine(-30.0, 10.0))
        val frontGain = level.gainDb
        // The standby prepares item B: it does not take the front's gain, and writes nothing shared.
        val standby = rig(level)
        standby.leading = false
        newItem(standby, "B")
        feed(standby, sine(-18.0, 3.0))
        assertEquals(frontGain, level.gainDb, 0.0)
        assertNull(level.recall("B"))
        val standbyGain = standby.levelling.currentGainDb
        assertTrue("standby learned $standbyGain", standbyGain < -4.0)
        // The hand-over: the standby becomes the front and carries on from its own gain.
        front.leading = false
        standby.leading = true
        val gains = mutableListOf<Double>()
        feed(standby, sine(-18.0, 0.2), gains)
        assertEquals(standbyGain, gains.first(), 0.5)
        assertEquals(standby.levelling.currentGainDb, level.gainDb, 0.0)
    }

    @Test
    fun theMemoryKeepsTheMostRecentItemsOnly() {
        val level = LoudnessLevel()
        val n = LoudnessLevel.REMEMBERED_ITEMS
        for (i in 0..n) level.remember("item$i", i.toDouble())
        assertNull(level.recall("item0"))
        assertEquals(1.0, level.recall("item1")!!, 0.0)
        assertEquals(n.toDouble(), level.recall("item$n")!!, 0.0)
    }

    private companion object {
        const val LU_TOLERANCE = 2.0
        const val SETTLE_LIMIT_SEC = 3.0
        const val SPEECH_LIMIT_SEC = 1.0
        const val QUIET_LIMIT_SEC = 6.0
    }
}
