// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.id
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.standIn
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlayMethod
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.feature.live.report.RecordingServer
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Spec 26 (#633): a programme that left the library, with nothing to fill it, shows the card at once. */
class GoneItemTest {
    private val facts = ReportFacts(MediaSourceId("src"), PlaySessionId("ps"), PlayMethod.DirectPlay, 50.minutes)

    @Test
    fun fillPiece_playsLikeAProgramme_reportedNormally() =
        runTest {
            val server = RecordingServer({ testScheduler.currentTime })
            val r = Rig(this, startMin = 10, server = server)
            // The source's substitution: a fill piece is an ordinary program with info.
            r.schedule.channel(CH7, 7, listOf(program(0, 60, 5), program(60, 120, 2)))
            r.tune.reportFacts = facts
            r.start()
            r.at(31_000)

            assertEquals(Call.Tune(itemOf(5), 10.minutes, 0), r.tunes.single())
            assertIs<LiveState.Watching>(r.state)
            assertEquals(listOf("userdata", "start"), server.calls.map { it.kind })
            assertEquals(0, r.schedule.standInCalls.size)
        }

    @Test
    fun goneRemainder_identWithNoteUntilBlockEnd_noDifficultiesSlate() =
        runTest {
            val server = RecordingServer({ testScheduler.currentTime })
            val r = Rig(this, startMin = 10, server = server)
            r.tune.reportFacts = facts
            r.schedule.channel(CH7, 7, listOf(program(0, 60, 1, info = null), program(60, 120, 2)))
            r.schedule.standInScript = { _, _ -> listOf(standIn(0, 60, 7, 0)) }
            r.start()
            r.at(10 * MIN)

            val ident = assertIs<LiveState.Ident>(r.state)
            assertEquals(CardNote.NotInLibrary, ident.note)
            assertEquals("Test Movie 2", ident.upNext?.title)
            assertFalse(r.seen.any { it is LiveState.TechnicalDifficulties })
            assertTrue(r.tunes.isEmpty())
            assertTrue(r.schedule.standInCalls.isEmpty())
            r.at(49 * MIN)
            assertEquals(CardNote.NotInLibrary, assertIs<LiveState.Ident>(r.state).note)
            assertTrue(server.calls.isEmpty())

            r.atMin(60)
            assertEquals(
                itemOf(2),
                r.tune.calls
                    .filterIsInstance<Call.TuneHandle>()
                    .single()
                    .item,
            )
        }

    @Test
    fun goneRemainder_shortRemainder_doesNotMoveOnEarly() =
        runTest {
            val server = RecordingServer({ testScheduler.currentTime })
            val r = Rig(this, startMin = 55, server = server)
            r.tune.reportFacts = facts
            r.schedule.channel(CH7, 7, listOf(program(0, 60, 1, info = null), program(60, 120, 2)))
            r.start()
            r.at(MIN)

            // Five minutes left is under the stand-in threshold: a dead item would move on; a gone one waits.
            assertEquals(CardNote.NotInLibrary, assertIs<LiveState.Ident>(r.state).note)
            assertTrue(r.tunes.isEmpty())
            r.at(4 * MIN)
            assertIs<LiveState.Ident>(r.state)
            assertTrue(server.calls.isEmpty())
            r.at(5 * MIN + 1_000)
            assertEquals(
                itemOf(2),
                r.tune.calls
                    .filterIsInstance<Call.TuneHandle>()
                    .single()
                    .item,
            )
            val banner = checkNotNull(assertIs<LiveState.Watching>(r.state).banner)
            // No drift: the next show holds its own slot, not one pulled forward to the card's end.
            assertEquals(T0 + 60 * MIN, banner.slotStartMs)
        }

    @Test
    fun flipLandingOnGoneBlock_closesFlipLineWithSlate() =
        runTest {
            val f =
                flipping {
                    schedule.channels.getValue(CH9).blocks = listOf(program(0, 600, 9, info = null))
                }
            f.up()
            advanceTimeBy(10.seconds)
            runCurrent()

            assertEquals(CardNote.NotInLibrary, assertIs<LiveState.Ident>(f.r.state).note)
            // One line, closed with the slate outcome; the flip is spent (reporting clears it), so none stays open.
            assertEquals(
                "slate",
                f.r.reports
                    .filterIsInstance<TuneReport.Flip>()
                    .single()
                    .outcome,
            )
        }

    @Test
    fun deadPlayback_stillUsesStandIns() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2)))
            r.schedule.standInScript = { _, _ -> listOf(standIn(0, 60, 7, 0)) }
            r.tune.script(itemOf(1), FakeTune.dead(), FakeTune.dead())
            r.start()
            r.at(3_000)

            assertEquals(
                setOf(id(1)),
                r.schedule.standInCalls
                    .single()
                    .second,
            )
            assertEquals(itemOf(7), r.tunes.last().item)
            assertTrue(r.seen.any { it is LiveState.TechnicalDifficulties })
        }
}
