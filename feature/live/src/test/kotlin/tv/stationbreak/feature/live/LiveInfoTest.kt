// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.model.ItemDetails
import kotlin.time.Duration.Companion.seconds

/** The program info overlay (spec 17, Task 5): opens loading, fills in, stays quiet on failure, closes on flip/Back. */
class LiveInfoTest {
    private val about = ItemDetails(itemOf(5), "About Test Movie 5.", 1999, listOf("Drama"), null)

    private suspend fun TestScope.watching(setup: Rig.() -> Unit = {}) =
        flipping {
            schedule.channel(CH5, 5, listOf(program(0, 600, 5)))
            setup()
        }

    private fun Flipping.info(): LiveInfo? = r.live.info.value

    private fun Flipping.actionsUp() = assertIs<LiveState.Watching>(r.state).banner?.actions == true

    @Test
    fun showInfoLoadsDetails() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val f =
                watching {
                    media.itemDetailsResult = ServerResult.Success(about)
                    media.itemDetailsGate = gate
                }
            f.send(LiveCommand.ShowInfo)
            val loading = checkNotNull(f.info())
            assertTrue(loading.loading)
            assertNull(loading.details)
            assertEquals("Test Movie 5", loading.banner.title)
            // The overlay replaces the banner: two panels never stack.
            assertNull(assertIs<LiveState.Watching>(f.r.state).banner)
            gate.complete(Unit)
            runCurrent()
            val loaded = checkNotNull(f.info())
            assertFalse(loaded.loading)
            assertEquals(about, loaded.details)
            assertEquals(listOf(itemOf(5)), f.r.media.itemDetailsCalls)
        }

    @Test
    fun detailsFailureKeepsOverlayQuiet() =
        runTest {
            val f = watching { media.itemDetailsResult = ServerResult.Failure(MediaServerError.Timeout()) }
            f.send(LiveCommand.ShowInfo)
            runCurrent()
            val quiet = checkNotNull(f.info())
            assertFalse(quiet.loading)
            assertNull(quiet.details)
            assertEquals("Test Movie 5", quiet.banner.title)
        }

    @Test
    fun infoWithNoBannerShowsTheActionsBannerFirst() =
        runTest {
            val f = watching()
            advanceTimeBy(5.seconds)
            f.send(LiveCommand.ShowInfo)
            assertNull(f.info())
            assertTrue(f.actionsUp())
            f.send(LiveCommand.ShowInfo)
            assertNotNull(f.info())
        }

    @Test
    fun flipClosesInfo() =
        runTest {
            val f = watching()
            f.send(LiveCommand.ShowInfo)
            runCurrent()
            assertNotNull(f.info())
            f.up()
            assertNull(f.info())
        }

    @Test
    fun theNextTuneClosesInfo() =
        runTest {
            val f = watching()
            f.send(LiveCommand.ShowInfo)
            runCurrent()
            f.up()
            advanceTimeBy(2.seconds)
            assertNull(f.info())
            assertEquals(9, assertIs<LiveState.Watching>(f.r.state).banner?.number)
        }

    @Test
    fun backClosesInfoThenActionsBanner() =
        runTest {
            val f = watching()
            advanceTimeBy(5.seconds)
            f.send(LiveCommand.ShowBanner)
            f.send(LiveCommand.ShowInfo)
            runCurrent()
            assertNotNull(f.info())
            f.send(LiveCommand.DismissOverlay)
            assertNull(f.info())
            assertTrue("Back from the overlay lands on the actions banner", f.actionsUp())
            f.send(LiveCommand.DismissOverlay)
            assertFalse(f.actionsUp())
            assertNull(assertIs<LiveState.Watching>(f.r.state).banner)
            // A third Back has nothing to close.
            f.send(LiveCommand.DismissOverlay)
            assertNull(f.info())
            assertEquals(LiveState.Watching(null), f.r.state)
        }
}
