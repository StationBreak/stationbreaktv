// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking

/**
 * The library selection's resume, as the app's start runs it (spec 26, Part 4). The instrumented tests run under
 * `HiltTestApplication`, so `StationBreakApp.onCreate` never runs there, and without this the selection's gate stays
 * shut: every sign-in, switch of user and picker Done waits on it for ever. Call it before the screens are set, where
 * the app's start would have run it, once the test's graph has its fakes.
 */
internal fun resumeSelectionAsTheAppStartDoes() {
    val graph =
        EntryPointAccessors.fromApplication(
            ApplicationProvider.getApplicationContext<Application>(),
            StationBreakApp.AppStartEntryPoint::class.java,
        )
    runBlocking { resumeSelection({ graph.selectionResume() }, graph.resumeGate()) {} }
}
