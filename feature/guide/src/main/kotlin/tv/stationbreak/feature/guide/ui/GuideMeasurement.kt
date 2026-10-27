// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.View
import android.view.Window
import tv.stationbreak.feature.guide.GuideReport
import tv.stationbreak.feature.guide.missedVsyncs
import tv.stationbreak.feature.guide.percentiles

/** The logcat tag of the live channel's tune lines, which the guide line shares: `SBLIVE guide ...`. */
private const val LOG_TAG = "SBLIVE"

private const val NANOS_PER_MS = 1_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val DEFAULT_PERIOD_NS = NANOS_PER_SECOND / 60

/** Where a session's frame reports come from: the window's frame metrics in the app, a test's own in a test. */
internal interface FrameSource {
    /** Starts reporting each drawn frame: its total duration and how many frames were dropped since the last report. */
    fun start(onFrame: (totalDurationNs: Long, dropped: Int) -> Unit)

    /** Stops reporting. */
    fun stop()
}

/**
 * A window's frame metrics, collected on a thread of its own, so the main thread does only the platform's own
 * bookkeeping. Frame metrics need API 24, which the app's minSdk covers.
 */
private class WindowFrames(
    private val window: Window,
) : FrameSource {
    private var thread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null

    override fun start(onFrame: (totalDurationNs: Long, dropped: Int) -> Unit) {
        val t = HandlerThread("guide-frames").also { it.start() }
        thread = t
        val l =
            Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
                onFrame(metrics.getMetric(FrameMetrics.TOTAL_DURATION), dropped)
            }
        listener = l
        window.addOnFrameMetricsAvailableListener(l, Handler(t.looper))
    }

    override fun stop() {
        try {
            listener?.let(window::removeOnFrameMetricsAvailableListener)
        } finally {
            thread?.quitSafely()
        }
    }
}

/**
 * One guide session's measurement (spec 17, Task 9): frame reports from [frames] while the guide is open, then one line
 * through [log] when [finish] ends it.
 */
internal class GuideMeasurement private constructor(
    private val openedAtNanos: Long,
    private val frames: FrameSource?,
    private val periodNs: Long,
    private val log: (String) -> Unit,
    private val nanoTime: () -> Long,
) {
    private val durations = LongSeries()
    private var dropped = 0L
    private var openMs = -1L
    private var finished = false

    /** Whether frames are being reported; false without a window to listen to. */
    val listening: Boolean get() = frames != null

    private fun attach() {
        frames?.start { durationNs, droppedSince ->
            synchronized(this) {
                if (!finished) {
                    durations.add(durationNs)
                    dropped += droppedSince
                }
            }
        }
    }

    /** The first grid frame was drawn. Only the first call counts. */
    fun gridShown() {
        if (openMs < 0) openMs = (nanoTime() - openedAtNanos) / NANOS_PER_MS
    }

    /**
     * Ends the session: stops collecting and logs one line, once. A frame listener that will not come off (the window
     * already gone) does not stop the line, nor the guide's own close after it.
     */
    @Suppress("LongParameterList") // The session's numbers, one per field of the line; all but two default to none.
    fun finish(
        windowMs: Long,
        rows: Int,
        extensionsAsked: Int = 0,
        extensionsApplied: Int = 0,
        tickLagMaxMs: Long = 0,
        tickLateMaxMs: Long = 0,
    ) {
        val taken: LongArray
        val droppedFrames: Long
        synchronized(this) {
            if (finished) return
            finished = true
            taken = durations.toArray()
            droppedFrames = dropped
        }
        runCatching { frames?.stop() }
        val report =
            GuideReport(
                openMs = openMs,
                windowMs = windowMs,
                frames = percentiles(taken),
                missedVsyncs = missedVsyncs(taken, periodNs),
                droppedFrames = droppedFrames.toInt(),
                rows = rows,
                durationMs = (nanoTime() - openedAtNanos) / NANOS_PER_MS,
                extensionsAsked = extensionsAsked,
                extensionsApplied = extensionsApplied,
                tickLagMaxMs = tickLagMaxMs,
                tickLateMaxMs = tickLateMaxMs,
            )
        log(report.line())
    }

    companion object {
        /** The measurement for the window [view] is in, logging to logcat like the tune lines do; see [create]. */
        fun start(
            measure: Boolean,
            view: View,
            openedAtNanos: Long,
            log: (String) -> Unit = { Log.i(LOG_TAG, it) },
        ): GuideMeasurement? {
            val rate = view.display?.refreshRate ?: 0f
            val period = if (rate > 0f) (NANOS_PER_SECOND / rate).toLong() else DEFAULT_PERIOD_NS
            val frames = view.context.activityWindow()?.let(::WindowFrames)
            return create(measure, openedAtNanos, frames, period, log)
        }

        /** [measure] off gives null: no frames, no thread, no listener, no line. */
        @Suppress("LongParameterList") // The session's facts and two seams (log, clock) a test replaces.
        fun create(
            measure: Boolean,
            openedAtNanos: Long,
            frames: FrameSource?,
            periodNs: Long,
            log: (String) -> Unit,
            nanoTime: () -> Long = System::nanoTime,
        ): GuideMeasurement? {
            if (!measure) return null
            return GuideMeasurement(openedAtNanos, frames, periodNs, log, nanoTime).also { it.attach() }
        }
    }
}

private fun Context.activityWindow(): Window? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c.window
        c = c.baseContext
    }
    return null
}

/** A growing list of longs without boxing; used from the frame thread and read once at the end. */
private class LongSeries {
    private var values = LongArray(INITIAL)
    private var size = 0

    fun add(v: Long) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = v
    }

    fun toArray(): LongArray = values.copyOf(size)

    private companion object {
        const val INITIAL = 256
    }
}
