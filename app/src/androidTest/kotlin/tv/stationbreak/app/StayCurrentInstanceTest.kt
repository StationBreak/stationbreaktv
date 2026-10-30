// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import tv.stationbreak.core.domain.lineup.SyncActivity
import tv.stationbreak.data.library.FirstSyncRunner
import tv.stationbreak.data.library.StayCurrent
import javax.inject.Inject

/**
 * #480: the graph owns the one `StayCurrent`. What the `Application` hands the worker (through its entry point) and
 * what the graph injects (the lineup refresher's sync signal) are the same object, so there is one runner, and one
 * gate, in the process. The first-sync runner the signal reads is the graph's one too.
 */
@HiltAndroidTest
class StayCurrentInstanceTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var injected: StayCurrent

    @Inject lateinit var firstSync: FirstSyncRunner

    @Inject lateinit var sync: SyncActivity

    @Before
    fun inject() = hilt.inject()

    @Test
    fun theWorkersEntryPointAndTheGraphShareOneStayCurrentAndOneFirstSyncRunner() {
        val graph =
            EntryPointAccessors.fromApplication(
                ApplicationProvider.getApplicationContext<android.app.Application>(),
                StationBreakApp.FirstSyncEntryPoint::class.java,
            )

        assertSame(injected, graph.stayCurrent())
        assertSame(injected.runner, graph.stayCurrent().runner)
        assertSame(firstSync, graph.firstSyncRunner())
    }

    // The production binding reads the runners' own flows: at rest it is off. (Neither runner's flag can be set from
    // outside, so the on state is covered by the runners' own tests and the refresher's.)
    @Test
    fun theSyncSignalTheRefresherReadsIsOffAtRest() =
        runBlocking {
            assertFalse(sync.writing().first())
        }
}
