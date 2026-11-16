// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.debugcmd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tv.stationbreak.app.di.AppScope
import tv.stationbreak.core.domain.lineup.LineupRefresher

/**
 * Debug and benchmark builds only (#812; the source is shared by both through `src/nonRelease`, and each variant's own
 * manifest declares it, so no release build has it). `adb shell am broadcast -a tv.stationbreak.debug.REBUILD_TODAY -p
 * <package>` plans the active account's lineup now and builds today's schedule for every channel again, instead of
 * waiting for the 06:00 boundary. The manifest asks for the DUMP permission, which the shell UID holds and an ordinary
 * app cannot get. The outcome is in logcat under [REBUILD_TAG].
 *
 * The work runs in the app's own scope: the broadcast returns at once ([goAsync]), the result is finished within
 * [HAND_OFF_MS] whether or not the rebuild is done (the system gives a broadcast about ten seconds), and the rebuild
 * goes on and logs its end in its own time.
 */
class RebuildTodayReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface Graph {
        fun lineupRefresher(): LineupRefresher

        @AppScope
        fun appScope(): CoroutineScope
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != REBUILD_ACTION) return
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, Graph::class.java)
        val pending = goAsync()
        graph.appScope().launch {
            try {
                val work =
                    launch {
                        runRebuildToday(graph.lineupRefresher()::rebuildToday) { Log.i(REBUILD_TAG, it) }
                    }
                withTimeoutOrNull(HAND_OFF_MS) { work.join() }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val HAND_OFF_MS = 8_000L
    }
}
