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

/**
 * One guide session's measurement (spec 17, Task 9). Frame metrics are collected on a thread of its own, so the main
 * thread does only the platform's own bookkeeping; [finish] removes the listener, stops the thread and logs the line
 * through [log]. Frame metrics need API 24, which the app's minSdk covers.
 */
internal class GuideMeasurement private constructor(
    private val openedAtNanos: Long,
    private val window: Window?,
    private val periodNs: Long,
    private val log: (String) -> Unit,
    private val nanoTime: () -> Long,
) {
    private val durations = LongSeries()
    private var thread: HandlerThread? = null
    private var openMs = -1L
    private var finished = false

    /** Whether a frame-metrics thread was started; false without a window to listen to. */
    val threadStarted: Boolean get() = thread != null

    private val listener =
        Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            synchronized(this) {
                if (!finished) durations.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
            }
        }

    private fun attach() {
        val w = window ?: return
        val t = HandlerThread("guide-frames").also { it.start() }
        thread = t
        w.addOnFrameMetricsAvailableListener(listener, Handler(t.looper))
    }

    /** The first grid frame was drawn. Only the first call counts. */
    fun gridShown() {
        if (openMs < 0) openMs = (nanoTime() - openedAtNanos) / NANOS_PER_MS
    }

    /** Ends the session: stops collecting and logs one line, once. */
    fun finish(
        windowMs: Long,
        rows: Int,
        pip: Boolean,
    ) {
        val taken: LongArray
        synchronized(this) {
            if (finished) return
            finished = true
            taken = durations.toArray()
        }
        try {
            window?.removeOnFrameMetricsAvailableListener(listener)
        } finally {
            thread?.quitSafely()
            val report =
                GuideReport(
                    openMs = openMs,
                    windowMs = windowMs,
                    frames = percentiles(taken),
                    missedVsyncs = missedVsyncs(taken, periodNs),
                    rows = rows,
                    pip = pip,
                    durationMs = (nanoTime() - openedAtNanos) / NANOS_PER_MS,
                )
            log(report.line())
        }
    }

    companion object {
        /** The measurement for the window [view] is in, logging to logcat like the tune lines do; null when off. */
        fun start(
            measure: Boolean,
            view: View,
            openedAtNanos: Long,
            log: (String) -> Unit = { Log.i(LOG_TAG, it) },
        ): GuideMeasurement? {
            if (!measure) return null
            val rate = view.display?.refreshRate ?: 0f
            val period = if (rate > 0f) (NANOS_PER_SECOND / rate).toLong() else DEFAULT_PERIOD_NS
            return create(true, openedAtNanos, view.context.activityWindow(), period, log)
        }

        /** [measure] off gives null: no thread, no listener, no line. */
        @Suppress("LongParameterList") // The session's facts and two seams (log, clock) a test replaces.
        fun create(
            measure: Boolean,
            openedAtNanos: Long,
            window: Window?,
            periodNs: Long,
            log: (String) -> Unit,
            nanoTime: () -> Long = System::nanoTime,
        ): GuideMeasurement? {
            if (!measure) return null
            return GuideMeasurement(openedAtNanos, window, periodNs, log, nanoTime).also { it.attach() }
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
