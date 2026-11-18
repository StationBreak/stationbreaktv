// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.selection.FakeLibraryChoices
import tv.stationbreak.core.domain.tracks.InMemoryTrackChoiceStore
import tv.stationbreak.core.playback.PlaybackResolver
import tv.stationbreak.core.playback.VideoPlayerFactory
import tv.stationbreak.core.playback.WarmConfig
import tv.stationbreak.core.playback.WarmSwitches
import tv.stationbreak.feature.live.LiveTimings
import tv.stationbreak.feature.live.Rig
import tv.stationbreak.feature.live.report.RecordingServer

/** Live to guide, end to end (#94): the opening the guide is handed says whether this is the first visit. */
class FirstVisitGuideTest {
    private suspend fun TestScope.viewModel(
        r: Rig,
        server: RecordingServer,
    ): LiveViewModel {
        r.schedule.channel(CH7, 7, listOf(program(0, 180, 1)))
        r.sessions.save(r.session)
        r.library.useAccount(r.account)
        r.channels.publish(CH7 to 7)
        return LiveViewModel(
            r.schedule,
            r.lastChannel,
            r.sessions,
            r.library,
            r.channels,
            r.clock,
            ControllerTunerFactory(
                PlaybackResolver(server) { error("not resolved here") },
                server,
                WarmConfig(),
                WarmSwitches.None,
                InMemoryTrackChoiceStore(),
            ),
            VideoPlayerFactory { _, _ -> error("the test makes its own players") },
            server,
            r.details,
            LiveTimings(),
            FakeLibraryChoices(null),
        )
    }

    private fun TestScope.sit(
        r: Rig,
        vm: LiveViewModel,
    ) {
        backgroundScope.launch { vm.sit({ RecordingPlayer() }, null, { _, _ -> r.tune }, { currentTime }) {} }
        runCurrent()
        r.at(MIN)
    }

    @Test
    fun aFirstTuneOpensTheGuideWithTheFirstVisitFlag() =
        runTest {
            val server = RecordingServer({ currentTime })
            val r = Rig(this, server = server)
            val vm = viewModel(r, server)
            sit(r, vm)
            assertTrue(vm.guideOpen.value)
            val opening = vm.guideOpening(vm.guideOpenedAt.value)
            assertTrue(opening.firstVisit)
            assertEquals(CH7, opening.current)
        }

    @Test
    fun aReturningViewerGetsNoGuideAndNoFlag() =
        runTest {
            val server = RecordingServer({ currentTime })
            val r = Rig(this, server = server)
            val vm = viewModel(r, server)
            r.lastChannel.save(r.account, CH7)
            sit(r, vm)
            assertFalse(vm.guideOpen.value)
            assertFalse(vm.guideOpening(1L).firstVisit)
        }

    @Test
    fun anOrdinaryGuideOpeningAfterTheFirstVisitCarriesNoFlag() =
        runTest {
            val server = RecordingServer({ currentTime })
            val r = Rig(this, server = server)
            val vm = viewModel(r, server)
            sit(r, vm)
            vm.closeGuide()
            vm.openGuide(2L)
            assertFalse(vm.guideOpening(2L).firstVisit)
        }
}
