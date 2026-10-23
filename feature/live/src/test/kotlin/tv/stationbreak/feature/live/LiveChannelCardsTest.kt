// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.playback.PlaybackEnd
import kotlin.time.Duration.Companion.minutes

/** The ident and "Technical difficulties" cards: what they say, and that "up next" is worked out before they show. */
class LiveChannelCardsTest {
    /** A show, then four half-hour slates (more than the near look-ahead holds), then a show. */
    private fun Rig.slatesThenAShow() {
        schedule.channel(
            CH7,
            7,
            listOf(
                program(0, 60, 1),
                slate(60, 90),
                slate(90, 120),
                slate(120, 150),
                slate(150, 180),
                program(180, 240, 2),
            ),
        )
    }

    @Test
    fun theUpNextLookIsMadeBeforeTheOldShowIsStopped() =
        runTest {
            val r = Rig(this)
            r.slatesThenAShow()
            val stopsWhenAsked = mutableListOf<Int>()
            r.schedule.onAirObserver = { next ->
                if (next > Sitting.WINDOW) stopsWhenAsked += r.tune.calls.count { it is Call.Stop }
            }
            r.tune.script(itemOf(1), r.tune.playsThenEnds(20.minutes, PlaybackEnd.Ended))
            r.start()
            r.atMin(20)

            val ident = assertIs<LiveState.Ident>(r.state)
            assertEquals("Test Movie 2", ident.upNext?.title)
            assertEquals(listOf(0), stopsWhenAsked)
            val (_, callsThen) = r.states.last { it.first is LiveState.Ident }
            assertIs<Call.Stop>(r.tune.calls[callsThen - 1])
        }

    @Test
    fun theSlateIsShownAtOnceAfterTheStopForADeadItem() =
        runTest {
            val r = Rig(this)
            r.slatesThenAShow()
            val stopsWhenAsked = mutableListOf<Int>()
            r.schedule.onAirObserver = { next ->
                if (next > Sitting.WINDOW) stopsWhenAsked += r.tune.calls.count { it is Call.Stop }
            }
            r.tune.script(itemOf(1), FakeTune.dead(), FakeTune.dead())
            r.start()

            val slate = assertIs<LiveState.TechnicalDifficulties>(r.state)
            assertEquals("Test Movie 2", slate.upNext?.title)
            assertEquals(listOf(0), stopsWhenAsked)
        }

    @Test
    fun upNextLooksNoFurtherThanTheEndOfTheNextDay() =
        runTest {
            val r = Rig(this, startMin = 10)
            // One long slate, and the only show starts after the end of day n + 1 (hour 48).
            r.schedule.channel(CH7, 7, listOf(slate(0, 49 * 60), program(49 * 60, 50 * 60, 1)))
            r.start()

            val ident = assertIs<LiveState.Ident>(r.state)
            assertNull(ident.upNext)
        }

    @Test
    fun anIdentWithNoUpNextStillNamesTheChannel() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.schedule.channel(CH7, 7, listOf(slate(0, 49 * 60), program(49 * 60, 50 * 60, 1)))
            r.start()

            val ident = assertIs<LiveState.Ident>(r.state)
            assertEquals(ChannelTag(7, "Test Channel 7"), ident.channel)
        }

    @Test
    fun theDifficultiesSlateWithNoUpNextStillNamesTheChannel() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.schedule.channel(CH7, 7, listOf(program(0, 60, 1), slate(60, 49 * 60), program(49 * 60, 50 * 60, 2)))
            r.tune.script(itemOf(1), FakeTune.dead(), FakeTune.dead())
            r.start()

            val slate = assertIs<LiveState.TechnicalDifficulties>(r.state)
            assertNull(slate.upNext)
            assertEquals(ChannelTag(7, "Test Channel 7"), slate.channel)
        }

    @Test
    fun anUpNextBannerOfADriftedSittingShowsClockTimes() =
        runTest {
            // The first show dies with 20 min left, so the sitting drifts; the ident later names the show after.
            val r = Rig(this, startMin = 40)
            val blocks = listOf(program(0, 60, 1), program(60, 120, 2), slate(120, 150), program(150, 180, 3))
            r.schedule.channel(CH7, 7, blocks)
            r.tune.script(itemOf(1), FakeTune.dead(), FakeTune.dead())
            r.start()
            r.at(3_000)
            val driftMs = 60 * MIN - (40 * MIN + 3_000)
            r.at(80 * MIN - driftMs) // the drifted time reaches the slate block at 120

            val up = checkNotNull(assertIs<LiveState.Ident>(r.state).upNext)
            assertEquals(T0 + 150 * MIN - driftMs, up.slotStartMs)
            assertTrue(up.slotEndMs - up.slotStartMs == 30 * MIN)
        }
}
