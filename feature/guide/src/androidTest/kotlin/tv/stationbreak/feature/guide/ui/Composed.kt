// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi

/**
 * What composed while [block] ran, from the trace markers the Compose compiler puts in every composable body: [all]
 * the bodies that ran, by name, and the [roots], the ones the runtime ran because something they read changed (the
 * outermost body of each recomposition; what they call runs inside them). A skipped composable runs no body and
 * leaves no mark.
 */
internal class Composed(
    val all: List<String>,
    val roots: List<String>,
) {
    /** The guide's own [roots], without the package: "ui.GuideCellView" and so on. */
    val guideRoots: List<String> get() = roots.filter { it.startsWith(GUIDE) }.map { it.removePrefix(GUIDE) }

    override fun toString(): String = "${all.size} bodies, roots $roots"

    companion object {
        private const val GUIDE = "tv.stationbreak.feature.guide."
    }
}

/** Records [Composed] while [block] runs; composition must happen on the thread that calls this, or be waited for. */
@OptIn(InternalComposeTracingApi::class)
internal fun traceComposition(block: () -> Unit): Composed {
    val all = mutableListOf<String>()
    val roots = mutableListOf<String>()
    var depth = 0
    val tracer =
        object : CompositionTracer {
            override fun traceEventStart(
                key: Int,
                dirty1: Int,
                dirty2: Int,
                info: String,
            ) {
                val name = info.substringBefore(" (")
                synchronized(all) {
                    all += name
                    if (depth == 0) roots += name
                    depth++
                }
            }

            override fun traceEventEnd() {
                synchronized(all) { depth-- }
            }

            override fun isTraceInProgress(): Boolean = true
        }
    Composer.setTracer(tracer)
    try {
        block()
    } finally {
        Composer.setTracer(null)
    }
    return synchronized(all) { Composed(all.toList(), roots.toList()) }
}
