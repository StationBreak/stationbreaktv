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
import tv.stationbreak.feature.guide.FramePercentiles
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
internal class GuideMeasurement(
    private val openedAtNanos: Long,
    private val window: Window?,
    private val periodNs: Long,
    private val log: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val durations = LongSeries()
    private val vsyncs = LongSeries()
    private val thread = HandlerThread("guide-frames")
    private var openMs = -1L
    private var finished = false

    private val listener =
        Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            synchronized(this) {
                if (finished) return@OnFrameMetricsAvailableListener
                durations.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
                vsyncs.add(metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP))
            }
        }

    init {
        if (window != null) {
            thread.start()
            window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        }
    }

    /** The first grid frame was drawn. Only the first call counts. */
    fun gridShown() {
        if (openMs < 0) openMs = (nanoTime() - openedAtNanos) / NANOS_PER_MS
    }

    /** Ends the session: stops collecting and logs one line. */
    fun finish(
        windowMs: Long,
        rows: Int,
        pip: Boolean,
    ) {
        val taken: Pair<LongArray, LongArray>
        synchronized(this) {
            if (finished) return
            finished = true
            taken = durations.toArray() to vsyncs.toArray()
        }
        if (window != null) {
            window.removeOnFrameMetricsAvailableListener(listener)
            thread.quitSafely()
        }
        val shown: FramePercentiles = percentiles(taken.first)
        val report =
            GuideReport(
                openMs = openMs,
                windowMs = windowMs,
                frames = shown,
                missedVsyncs = missedVsyncs(taken.second, periodNs),
                rows = rows,
                pip = pip,
                durationMs = (nanoTime() - openedAtNanos) / NANOS_PER_MS,
            )
        log(report.line())
    }

    companion object {
        /** The measurement for the window [view] is in, logging to logcat like the tune lines do. */
        fun start(
            view: View,
            openedAtNanos: Long,
            log: (String) -> Unit = { Log.i(LOG_TAG, it) },
        ): GuideMeasurement {
            val rate = view.display?.refreshRate ?: 0f
            val period = if (rate > 0f) (NANOS_PER_SECOND / rate).toLong() else DEFAULT_PERIOD_NS
            return GuideMeasurement(openedAtNanos, view.context.activityWindow(), period, log)
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
