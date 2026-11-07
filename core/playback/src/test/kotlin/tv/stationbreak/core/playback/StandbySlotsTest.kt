// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_NO
import androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * Spec 24: how the standby's ExoPlayer is driven. A standby that already holds media is stopped before it is prepared
 * again: Media3 keeps a flushable codec across a new item (a renderer disable flushes, only a reset releases), so
 * without the stop no decoder comes up for the new start and the probe would call a working decoder missing.
 */
@UnstableApi
class StandbySlotsTest {
    /** One fake ExoPlayer: records the calls that matter and keeps a playback state. */
    private class FakeExo(
        val name: String,
        val calls: MutableList<String>,
    ) {
        var state = Player.STATE_IDLE
        var parameters: TrackSelectionParameters = TrackSelectionParameters.Builder().build()
        val listeners = mutableListOf<AnalyticsListener>()

        val exo: ExoPlayer =
            Proxy.newProxyInstance(LOADER, arrayOf(ExoPlayer::class.java)) { _, method, args ->
                when (method.name) {
                    "getPlaybackState" -> {
                        state
                    }

                    "getTrackSelectionParameters" -> {
                        parameters
                    }

                    "setTrackSelectionParameters" -> {
                        parameters = args[0] as TrackSelectionParameters
                        null
                    }

                    "prepare" -> {
                        calls += "$name.prepare"
                        state = Player.STATE_BUFFERING
                        null
                    }

                    "stop" -> {
                        calls += "$name.stop"
                        state = Player.STATE_IDLE
                        null
                    }

                    "setMediaItem" -> {
                        calls += "$name.setMediaItem"
                        null
                    }

                    "addAnalyticsListener" -> {
                        listeners += args[0] as AnalyticsListener
                        null
                    }

                    else -> {
                        defaultOf(method.returnType)
                    }
                }
            } as ExoPlayer

        private fun defaultOf(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
    }

    private val calls = mutableListOf<String>()
    private val front = FakeExo("front", calls)
    private val standby = FakeExo("standby", calls)

    private fun slotOf(fake: FakeExo): Slot {
        val allocator = PlaybackBuffering.mediaAllocator()
        return Slot(fake.exo, allocator, LoadCounter(), AtomicBoolean())
    }

    private val host =
        object : SlotHost {
            override val frontAnalytics: AnalyticsListener = object : AnalyticsListener {}
            override val frontListener: Player.Listener = object : Player.Listener {}

            override fun newSlot(): Slot = slotOf(standby)

            override fun drawOn(target: ExoPlayer) = Unit

            override fun deviceCannot(): String? = null

            override fun decoderMissing() = Unit
        }

    // The JVM's Handler stub does nothing: no deadline check runs here.
    private val slots = StandbySlots(slotOf(front), Handler(), CLOCK, WarmConfig(), WarmSwitches.None, host)

    private fun source(path: String): PlayerSource {
        val url = "https://server.example/$path"
        return PlayerSource(url, false, 2.seconds, emptyList(), null, null, true)
    }

    @Test
    fun aFreshStandbyIsPreparedWithoutAStop() {
        slots.warm("w1", source("a.mp4"))
        assertEquals(listOf("standby.setMediaItem", "standby.prepare"), calls)
    }

    @Test
    fun aStandbyThatHoldsMediaIsStoppedBeforeItIsPreparedAgain() {
        slots.warm("w1", source("a.mp4"))
        calls.clear()
        slots.warm("w2", source("b.mp4"))
        assertEquals(listOf("standby.stop", "standby.setMediaItem", "standby.prepare"), calls)
    }

    @Test
    fun theFrontIsNeverTouchedByAWarm() {
        slots.warm("w1", source("a.mp4"))
        slots.warm("w2", source("b.mp4"))
        assertTrue("the front was driven: $calls", calls.none { it.startsWith("front.") })
    }

    /** An event of the standby's item for the start [epoch] (its media id is `warm-<epoch>`). */
    private fun eventOf(epoch: Int): AnalyticsListener.EventTime {
        val item = MediaItem.Builder().setMediaId("warm-$epoch").build()
        val timeline = SinglePeriodTimeline(C.TIME_UNSET, true, false, false, null, item)
        return AnalyticsListener.EventTime(0L, timeline, 0, null, 0L, timeline, 0, null, 0L, 0L)
    }

    /** The video format of start [epoch] changed; [reuse] is Media3's reuse result, null when no codec was there. */
    private fun AnalyticsListener.formatChanged(
        epoch: Int,
        reuse: Int?,
    ) {
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()
        val evaluation = reuse?.let { DecoderReuseEvaluation("c2.test.avc", format, format, it, 0) }
        onVideoInputFormatChanged(eventOf(epoch), format, evaluation)
    }

    /** Warms w1 (start 1) and reports it READY; returns the SBWARM lines seen after [after] runs. */
    private fun readyLinesWhen(after: (AnalyticsListener) -> Unit): List<String> {
        val lines = mutableListOf<String>()
        WarmLog.sink = { lines += it }
        try {
            slots.warm("w1", source("a.mp4"))
            val listener = standby.listeners.single()
            listener.onPlaybackStateChanged(eventOf(1), Player.STATE_READY)
            after(listener)
        } finally {
            WarmLog.sink = null
        }
        return lines.filter { it.startsWith("ready ") }
    }

    @Test
    fun aReusedDecoderCountsAsUp() {
        // Media3 reused a running codec for the new item: no decoder-initialized event comes, but the decoder is up.
        val ready = readyLinesWhen { it.formatChanged(1, REUSE_RESULT_YES_WITHOUT_RECONFIGURATION) }
        assertEquals("ready once: $ready", 1, ready.size)
        assertTrue(ready.single(), ready.single().contains("decoderInitMs=0"))
    }

    @Test
    fun aFormatWithNoReuseIsNotADecoder() {
        val first = readyLinesWhen { it.formatChanged(1, null) }
        assertTrue("no codec yet: $first", first.isEmpty())
    }

    @Test
    fun aReuseRefusedWaitsForTheNewDecoder() {
        // REUSE_RESULT_NO: Media3 builds a new codec, whose own initialized event says it is up.
        val ready = readyLinesWhen { it.formatChanged(1, REUSE_RESULT_NO) }
        assertTrue("ready early: $ready", ready.isEmpty())
    }

    @Test
    fun aReuseOfAnOlderStartIsIgnored() {
        val ready = readyLinesWhen { it.formatChanged(0, REUSE_RESULT_YES_WITHOUT_RECONFIGURATION) }
        assertTrue("an old start made this one ready: $ready", ready.isEmpty())
    }

    private companion object {
        val LOADER: ClassLoader = ExoPlayer::class.java.classLoader!!
        val CLOCK: () -> Long = { 1_000L }
    }
}
