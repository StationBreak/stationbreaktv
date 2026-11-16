// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.debugcmd

import kotlinx.coroutines.CancellationException
import tv.stationbreak.core.domain.lineup.RebuildTodayResult
import kotlin.time.TimeSource

/** The one logcat tag the command reports under, so a test session can wait for its `done` line. */
internal const val REBUILD_TAG = "SB-Rebuild"

/** The broadcast action that asks for the rebuild (#812). Declared in the debug and benchmark manifests only. */
internal const val REBUILD_ACTION = "tv.stationbreak.debug.REBUILD_TODAY"

/**
 * Runs the rebuild of today's schedule for every channel ([rebuild]: `LineupRefresher.rebuildToday`) and says what
 * happened through [log], one line each: `started`, then `done in N ms`, `skipped: <reason>` or `failed: <exception
 * class>`. Only the class name of a failure is given: a message could hold a URL or a title. Nothing else is logged.
 * Cancellation goes through. A pure function over its two ports so it is unit-tested without Android.
 */
internal suspend fun runRebuildToday(
    rebuild: suspend () -> RebuildTodayResult,
    log: (String) -> Unit,
) {
    log("started")
    val began = TimeSource.Monotonic.markNow()
    try {
        when (rebuild()) {
            RebuildTodayResult.Done -> log("done in ${began.elapsedNow().inWholeMilliseconds} ms")
            RebuildTodayResult.NoAccount -> log("skipped: nobody is signed in")
            RebuildTodayResult.NoLibraries -> log("skipped: no libraries are chosen")
            RebuildTodayResult.NoLineup -> log("skipped: account")
        }
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Throwable,
    ) {
        if (e is CancellationException) throw e
        log("failed: ${e::class.java.simpleName}")
    }
}
