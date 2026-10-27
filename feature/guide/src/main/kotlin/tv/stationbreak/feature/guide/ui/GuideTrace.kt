// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.os.Trace
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

/** The trace section of one D-pad move: the focus step and its publish, on the main thread. */
internal const val TRACE_MOVE = "guide move"

/** The trace sections of the grid's measure and placement passes, the rows' included. */
internal const val TRACE_GRID_MEASURE = "guide grid measure"
internal const val TRACE_GRID_PLACE = "guide grid place"

/**
 * Runs [block] as a section named [name] in a system trace (Perfetto, `atrace`), so a trace of the device can say
 * where a frame's time went. The platform's own: no library, and close to free while nothing is tracing.
 */
internal inline fun <T> traced(
    name: String,
    block: () -> T,
): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}

/** Measures and places what it modifies inside the trace sections [measure] and [place]; changes nothing else. */
internal fun Modifier.tracedLayout(
    measure: String,
    place: String,
): Modifier =
    layout { measurable, constraints ->
        val placeable = traced(measure) { measurable.measure(constraints) }
        layout(placeable.width, placeable.height) { traced(place) { placeable.place(0, 0) } }
    }
