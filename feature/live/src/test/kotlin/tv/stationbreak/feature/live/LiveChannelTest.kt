// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.HOUR
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.episodeInfo
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.id
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.slate
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.standIn
import tv.stationbreak.core.playback.PlaybackEnd
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LiveChannelTest {
    /** One channel, 7: a movie from minute 0 to 60, a movie to 120, a slate to 180, a movie to 240. */
    private fun Rig.ordinaryDay() {
        schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2), slate(120, 180), program(180, 240, 3)))
    }

    private fun banner(state: LiveState?): Banner =
        when (state) {
            is LiveState.Tuning -> state.banner
            is LiveState.Watching -> checkNotNull(state.banner)
            is LiveState.Ident -> checkNotNull(state.upNext)
            is LiveState.TechnicalDifficulties -> checkNotNull(state.upNext)
            else -> error("no banner in $state")
        }

    // ---- choosing a channel ----

    @Test
    fun opensOnTheSavedChannel() =
        runTest {
            val r = Rig(this)
            r.schedule.channel(CH5, 5, listOf(program(0, 60, 1)))
            r.schedule.channel(CH7, 7, listOf(program(0, 60, 2)))
            r.lastChannel.save(r.account, CH7)
            r.start()
            assertEquals(itemOf(2), r.tunes.first().item)
            assertEquals(7, banner(r.seen.first { it is LiveState.Tuning }).number)
        }

    @Test
    fun opensOnTheLowestUnhiddenChannelWhenNothingIsSaved() =
        runTest {
            val r = Rig(this)
            r.schedule.channel(CH5, 5, listOf(program(0, 60, 1)), hidden = HiddenReason.EmptyToday)
            r.schedule.channel(CH7, 7, listOf(program(0, 60, 2)))
            r.schedule.channel(CH9, 9, listOf(program(0, 60, 3)))
            r.start()
            assertEquals(itemOf(2), r.tunes.first().item)
        }

    @Test
    fun opensOnTheLowestUnhiddenWhenTheSavedChannelNoLongerAirs() =
        runTest {
            val r = Rig(this)
            r.schedule.channel(CH5, 5, listOf(program(0, 60, 1)), hidden = HiddenReason.EmptyToday)
            r.schedule.channel(CH7, 7, listOf(program(0, 60, 2)))
            r.lastChannel.save(r.account, CH5)
            r.start()
            assertEquals(itemOf(2), r.tunes.first().item)
            // The saved value is overwritten by the tune.
            assertEquals(CH7, r.lastChannel.load(r.account))
        }

    @Test
    fun showsNoChannelsWhenNothingAirs() =
        runTest {
            val r = Rig(this)
            r.start()
            assertEquals(LiveState.NoChannels, r.state)
            assertTrue(r.tune.calls.isEmpty())
        }

    @Test
    fun showsNoChannelsWhenNoAccountIsActiveYetAndOpensOneWhenItBecomesActive() =
        runTest {
            val r = Rig(this)
            r.schedule.channel(CH7, 7, listOf(program(0, 60, 1)))
            r.start(withAccount = false)
            assertEquals(LiveState.NoChannels, r.state)
            assertTrue(r.tune.calls.isEmpty())

            r.library.useAccount(r.account)
            r.at(1_000)

            assertIs<LiveState.Tuning>(r.seen.first { it is LiveState.Tuning })
        }

    @Test
    fun noChannelsOpensAChannelWhenTheLineupArrives() =
        runTest {
            val r = Rig(this)
            r.start()
            assertEquals(LiveState.NoChannels, r.state)

            r.schedule.channel(CH7, 7, listOf(program(0, 60, 1)))
            r.channels.publish(CH7 to 7)
            r.at(1_000)

            assertIs<LiveState.Tuning>(r.seen.first { it is LiveState.Tuning })
            assertEquals(itemOf(1), r.tunes.single().item)
        }

    // ---- tuning ----

    @Test
    fun tunesAtTheScheduleOffset() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.ordinaryDay()
            r.start()
            assertEquals(10.minutes, r.tunes.single().start)
        }

    @Test
    fun tuningAnItemThatSpansBlocksStartsAtItsMediaOffset() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.schedule.channel(CH7, 7, listOf(program(0, 60, 1, mediaStartMs = 90 * MIN)))
            r.start()
            assertEquals(100.minutes, r.tunes.single().start)
        }

    @Test
    fun snowShowsForAtLeastMinSnow() =
        runTest {
            val r = Rig(this)
            r.ordinaryDay()
            r.start()
            assertIs<LiveState.Tuning>(r.state)
            r.at(499)
            assertIs<LiveState.Tuning>(r.state)
            r.at(500)
            assertIs<LiveState.Watching>(r.state)
        }

    @Test
    fun bannerLingersThenHides() =
        runTest {
            val r = Rig(this)
            r.ordinaryDay()
            r.start()
            r.at(500 + 3_999)
            assertIs<LiveState.Watching>(r.state)
            val lingering = checkNotNull((r.state as LiveState.Watching).banner)
            assertEquals(BannerLabel.Now, lingering.label)
            assertEquals(10.minutes.inWholeMilliseconds, lingering.joinedMs)
            r.at(500 + 4_000)
            assertEquals(LiveState.Watching(null), r.state)
        }

    @Test
    fun theChannelIsSavedOncePerChannelNotOncePerBlock() =
        runTest {
            val r = Rig(this)
            r.ordinaryDay()
            r.start()
            assertEquals(1, r.lastChannel.saves)
            assertEquals(CH7, r.lastChannel.load(r.account))
            r.atMin(50) // the boundary: a cut on the same channel is not a new channel, and saves nothing
            assertEquals(1, r.lastChannel.saves)
            r.atMin(110)
            assertEquals(1, r.lastChannel.saves)
        }

    // ---- block boundaries ----

    @Test
    fun cutsToThePreResolvedNextAtTheBoundary() =
        runTest {
            val r = Rig(this)
            r.ordinaryDay()
            r.start()

            r.at(50 * MIN - 45_001)
            assertTrue(r.tune.calls.none { it is Call.PreResolve })
            r.at(50 * MIN - 45_000)
            assertEquals(Call.PreResolve(itemOf(2), Duration.ZERO, 50 * MIN - 45_000), r.tune.calls.last())

            r.atMin(50)
            val cut =
                r.tune.calls
                    .filterIsInstance<Call.TuneHandle>()
                    .single()
            assertEquals(Call.TuneHandle(itemOf(2), Duration.ZERO, 50 * MIN), cut)
            val now = r.state as LiveState.Watching
            assertEquals(BannerLabel.Now, now.banner?.label)
            assertNull(now.banner?.joinedMs)
            assertEquals(1, r.tunes.size) // no plain tune for the second show
            r.at(50 * MIN + 4_000)
            assertEquals(LiveState.Watching(null), r.state)
        }

    @Test
    fun showsTheIdentForASlateBlock() =
        runTest {
            val r = Rig(this, startMin = 70)
            r.ordinaryDay()
            r.start() // second show, 10 min in; it ends at 120
            r.atMin(50) // 120 on the clock: the slate block
            val ident = assertIs<LiveState.Ident>(r.state)
            val up = checkNotNull(ident.upNext)
            assertEquals(BannerLabel.UpNext, up.label)
            assertEquals("Test Movie 3", up.title)
            assertEquals(T0 + 180 * MIN, up.slotStartMs)
            assertEquals(T0 + 240 * MIN, up.slotEndMs)
        }

    @Test
    fun theIdentGivesWayToTheNextShowAtTheBlockEnd() =
        runTest {
            val r = Rig(this, startMin = 70)
            r.ordinaryDay()
            r.start()
            r.atMin(110) // 180 on the clock
            assertIs<LiveState.Watching>(r.state)
            assertEquals(
                itemOf(3),
                r.tune.calls
                    .filterIsInstance<Call.TuneHandle>()
                    .single()
                    .item,
            )
        }

    @Test
    fun aSlateBlockStopsTheOldShowBeforeTheIdent() =
        runTest {
            val r = Rig(this, startMin = 70)
            r.ordinaryDay()
            r.start()
            r.atMin(50)
            val (state, callsThen) = r.states.last { it.first is LiveState.Ident }
            assertIs<LiveState.Ident>(state)
            assertIs<Call.Stop>(r.tune.calls[callsThen - 1])
        }

    @Test
    fun showsTheIdentWhenAShowEndsEarly() =
        runTest {
            val r = Rig(this)
            r.ordinaryDay()
            r.tune.script(itemOf(1), r.tune.playsThenEnds(20.minutes, PlaybackEnd.Ended))
            r.start()
            r.atMin(19)
            assertIs<LiveState.Watching>(r.state)
            r.atMin(20)
            val ident = assertIs<LiveState.Ident>(r.state)
            assertEquals("Test Movie 2", ident.upNext?.title)
        }

    @Test
    fun anEarlyEndStopsAndShowsTheIdentUntilTheBoundary() =
        runTest {
            val r = Rig(this)
            r.ordinaryDay()
            r.tune.script(itemOf(1), r.tune.playsThenEnds(20.minutes, PlaybackEnd.Ended))
            r.start()
            r.atMin(20)
            val (_, callsThen) = r.states.last { it.first is LiveState.Ident }
            assertIs<Call.Stop>(r.tune.calls[callsThen - 1])
            r.at(50 * MIN - 1)
            assertIs<LiveState.Ident>(r.state)
            r.atMin(50)
            assertIs<LiveState.Watching>(r.state)
            assertEquals(
                itemOf(2),
                r.tune.calls
                    .filterIsInstance<Call.TuneHandle>()
                    .single()
                    .item,
            )
        }

    @Test
    fun cutsAShowThatRunsLongAtItsBoundary() =
        runTest {
            val r = Rig(this)
            r.ordinaryDay() // the first show's end flow never emits
            r.start()
            r.at(50 * MIN - 1)
            assertEquals(Call.Tune(itemOf(1), 10.minutes, 0), r.tunes.single())
            r.atMin(50)
            assertEquals(
                itemOf(2),
                r.tune.calls
                    .filterIsInstance<Call.TuneHandle>()
                    .single()
                    .item,
            )
            assertEquals(
                0,
                r.tune.calls
                    .filterIsInstance<Call.Stop>()
                    .size,
            )
        }

    @Test
    fun aBoundaryRequeriesTheScheduleAndDiscardsAStaleHandle() =
        runTest {
            val r = Rig(this)
            r.ordinaryDay()
            r.start()
            r.at(50 * MIN - 30_000) // the second show is pre-resolved
            assertTrue(r.tune.calls.any { it is Call.PreResolve })
            // The lineup changed: another show now airs in the second block.
            r.schedule.channels
                .getValue(CH7)
                .blocks = listOf(program(0, 60, 1), program(60, 120, 9), slate(120, 180))
            r.atMin(50)

            assertTrue(r.tune.calls.contains(Call.Discard(itemOf(2), 50 * MIN)))
            assertTrue(r.tune.calls.none { it is Call.TuneHandle })
            assertEquals(Call.Tune(itemOf(9), Duration.ZERO, 50 * MIN), r.tunes.last())
        }

    @Test
    fun aMismatchedHandleIsDiscarded() =
        runTest {
            val r = Rig(this)
            r.ordinaryDay()
            r.start()
            r.at(50 * MIN - 30_000)
            // The same show, but the block now starts later in the media: the prepared position is wrong.
            val later = program(60, 120, 2, mediaStartMs = 5 * MIN)
            r.schedule.channels
                .getValue(CH7)
                .blocks = listOf(program(0, 60, 1), later)
            r.atMin(50)

            assertTrue(r.tune.calls.contains(Call.Discard(itemOf(2), 50 * MIN)))
            assertEquals(Call.Tune(itemOf(2), 5.minutes, 50 * MIN), r.tunes.last())
        }

    @Test
    fun aClockJumpIsPickedUpAtTheNextBoundary() =
        runTest {
            val r = Rig(this)
            r.schedule.channel(
                CH7,
                7,
                listOf(
                    program(0, 60, 1),
                    program(60, 120, 2),
                    slate(120, 180),
                    program(180, 240, 3),
                    program(240, 300, 4),
                ),
            )
            r.start()
            r.atMin(30)
            r.jumpMs = 3 * HOUR // the TV's clock moves on three hours
            r.at(31 * MIN)
            assertEquals(1, r.tunes.size) // nothing changes until the boundary
            r.atMin(50)

            // 240 + (50 - 50)... the clock now reads 60 + 180 = 240: the block starting at 240.
            assertTrue(r.tune.calls.contains(Call.Discard(itemOf(2), 50 * MIN)))
            assertEquals(Call.Tune(itemOf(4), Duration.ZERO, 50 * MIN), r.tunes.last())
        }

    // ---- dead items ----

    @Test
    fun aDeadItemIsRetriedOnceThenShowsTheSlate() =
        runTest {
            val r = Rig(this)
            r.ordinaryDay()
            r.tune.script(itemOf(1), FakeTune.dead(), FakeTune.dead())
            r.start()
            assertEquals(2, r.tunes.count { it.item == itemOf(1) })
            val slateState = r.states.last { it.first is LiveState.TechnicalDifficulties }
            assertEquals("Test Movie 2", (slateState.first as LiveState.TechnicalDifficulties).upNext?.title)
            assertIs<Call.Stop>(r.tune.calls[slateState.second - 1])
        }

    @Test
    fun noRetryAfterATimeout() =
        runTest {
            val r = Rig(this)
            r.ordinaryDay()
            r.tune.script(itemOf(1), FakeTune.dead(timedOut = true))
            r.start()
            assertEquals(1, r.tunes.count { it.item == itemOf(1) })
            assertTrue(r.seen.any { it is LiveState.TechnicalDifficulties })
        }

    @Test
    fun theRetryStartsAtTheClockPositionAfterTheFailure() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.ordinaryDay()
            r.tune.script(itemOf(1), FakeTune.deadAfter(20.seconds))
            r.start()
            r.at(20_000)
            val tunes = r.tunes.filter { it.item == itemOf(1) }
            assertEquals(listOf(10.minutes, 10.minutes + 20.seconds), tunes.map { it.start })
        }

    @Test
    fun aDeadItemWithLittleLeftMovesOnAndDrifts() =
        runTest {
            // 40 min into the first block: 20 min left, at or under the 30 min line.
            val r = Rig(this, startMin = 40)
            r.ordinaryDay()
            r.tune.script(itemOf(1), FakeTune.dead(), FakeTune.dead())
            r.start()
            assertEquals(0, r.schedule.standInCalls.size)
            assertIs<LiveState.TechnicalDifficulties>(r.state)

            r.at(3_000) // the slate has held 3 s
            // The next show is tuned from its start, not from the clock (which is 3 s into its slot).
            assertEquals(Call.Tune(itemOf(2), Duration.ZERO, 3_000), r.tunes.last())
        }

    @Test
    fun aDriftedShowFollowedByAShowStartsItFromTheBeginning() =
        runTest {
            val r = Rig(this, startMin = 40)
            r.schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2), program(120, 180, 3)))
            r.tune.script(itemOf(1), FakeTune.dead(), FakeTune.dead())
            r.start()
            r.at(3_000)
            assertEquals(Call.Tune(itemOf(2), Duration.ZERO, 3_000), r.tunes.last())
            r.at(3_000 + 60 * MIN)
            val cut =
                r.tune.calls
                    .filterIsInstance<Call.TuneHandle>()
                    .single()
            assertEquals(Call.TuneHandle(itemOf(3), Duration.ZERO, 3_000 + 60 * MIN), cut)
        }

    @Test
    fun theThresholdIsInclusive() =
        runTest {
            val r = Rig(this, startMin = 30) // exactly 30 min left
            r.ordinaryDay()
            r.tune.script(itemOf(1), FakeTune.dead(), FakeTune.dead())
            r.start()
            r.at(3_000)
            assertEquals(0, r.schedule.standInCalls.size)
            assertEquals(Call.Tune(itemOf(2), Duration.ZERO, 3_000), r.tunes.last())
        }

    @Test
    fun aDeadItemWithMuchLeftGetsTheStandInAtTheClockPosition() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.ordinaryDay()
            r.schedule.standInScript = { _, _ -> listOf(standIn(0, 25, 7, 0), standIn(25, 60, 8, 0)) }
            r.tune.script(itemOf(1), FakeTune.dead(), FakeTune.dead())
            r.start()
            r.at(3_000)

            assertEquals(
                setOf(id(1)),
                r.schedule.standInCalls
                    .single()
                    .second,
            )
            // Joined as if scheduled all along: now (10:03) minus the stand-in's virtual start.
            assertEquals(Call.Tune(itemOf(7), 10.minutes + 3.seconds, 3_000), r.tunes.last())
            val banner = banner(r.state)
            assertEquals(BannerLabel.FillingIn, banner.label)
            assertEquals("Test Movie 7", banner.title)

            // When the first stand-in ends, the next is chained, from its start.
            r.at(15 * MIN)
            assertEquals(Call.Tune(itemOf(8), Duration.ZERO, 15 * MIN), r.tunes.last())
        }

    @Test
    fun aStandInIsKeptUntilTheBlockEnds() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.ordinaryDay()
            r.schedule.standInScript = { _, _ -> listOf(standIn(0, 60, 7, 0)) }
            r.tune.script(itemOf(1), FakeTune.dead(), FakeTune.dead())
            r.start()
            r.at(50 * MIN - 1)
            assertEquals(1, r.schedule.standInCalls.size)
            assertEquals(1, r.tunes.count { it.item == itemOf(7) })
            assertEquals(Call.Tune(itemOf(7), 10.minutes + 3.seconds, 3_000), r.tunes.last())
            r.atMin(50)
            // The block is over: the second show cuts in as scheduled.
            assertEquals(
                itemOf(2),
                r.tune.calls
                    .filterIsInstance<Call.TuneHandle>()
                    .single()
                    .item,
            )
            assertEquals(1, r.schedule.standInCalls.size)
        }

    @Test
    fun aFailedStandInTriesOneMoreThenTheIdent() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.ordinaryDay()
            r.schedule.standInScript = { _, exclude ->
                if (id(7) in exclude) listOf(standIn(0, 60, 8, 0)) else listOf(standIn(0, 60, 7, 0))
            }
            r.tune.script(itemOf(1), FakeTune.dead(), FakeTune.dead())
            r.tune.script(itemOf(7), FakeTune.dead())
            r.tune.script(itemOf(8), FakeTune.dead())
            r.start()
            r.at(3_000)

            assertEquals(listOf(setOf(id(1)), setOf(id(1), id(7))), r.schedule.standInCalls.map { it.second })
            assertEquals(listOf(itemOf(1), itemOf(1), itemOf(7), itemOf(8)), r.tunes.map { it.item })
            val (state, callsThen) = r.states.last()
            assertIs<LiveState.Ident>(state)
            assertIs<Call.Stop>(r.tune.calls[callsThen - 1])
            // The card holds until the block ends.
            r.at(50 * MIN - 1)
            assertIs<LiveState.Ident>(r.state)
        }

    @Test
    fun aMidShowFailureTakesTheDeadItemPathWithTheRemainderAtThatMoment() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.ordinaryDay()
            // 50 min left when it started (a stand-in case); the show fails 25 min in, with 25 min left: it moves on.
            r.tune.script(itemOf(1), r.tune.playsThenEnds(25.minutes, PlaybackEnd.Failed("X")), FakeTune.dead())
            r.start()
            r.at(25 * MIN)
            // The retry starts from the clock.
            assertEquals(Call.Tune(itemOf(1), 35.minutes, 25 * MIN), r.tunes.last())
            assertIs<LiveState.TechnicalDifficulties>(r.state)
            assertEquals(0, r.schedule.standInCalls.size)
            r.at(25 * MIN + 3_000)
            assertEquals(Call.Tune(itemOf(2), Duration.ZERO, 25 * MIN + 3_000), r.tunes.last())
        }

    @Test
    fun aBlockWithNoInfoGoesStraightToTheSlateWithoutATune() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.schedule.channel(CH7, 7, listOf(program(0, 60, 1, info = null), program(60, 120, 2)))
            r.start()
            assertTrue(r.tunes.none { it.item == itemOf(1) })
            val (state, callsThen) = r.states.first { it.first is LiveState.TechnicalDifficulties }
            assertIs<LiveState.TechnicalDifficulties>(state)
            assertIs<Call.Stop>(r.tune.calls[callsThen - 1])
            r.at(3_000)
            assertEquals(listOf(id(1)), r.schedule.standInCalls.map { it.second.single() })
        }

    // ---- drift ----

    @Test
    fun aRetuneReanchorsToTheClockAfterDrift() =
        runTest {
            val r = Rig(this, startMin = 40)
            r.ordinaryDay()
            r.tune.script(itemOf(1), FakeTune.dead(), FakeTune.dead())
            val first = r.start()
            r.at(3_000)
            assertEquals(Call.Tune(itemOf(2), Duration.ZERO, 3_000), r.tunes.last())
            first.cancel()
            r.at(10_000)

            // A new sitting works out its place from the clock again: 40 min 10 s into the first block.
            r.tune.calls.clear()
            r.launchRun()
            assertEquals(Call.Tune(itemOf(1), 40.minutes + 10.seconds, 10_000), r.tunes.single())
        }

    // ---- teardown and expiry ----

    @Test
    fun sessionExpiryGoesToSignedOutNotTheSlate() =
        runTest {
            val r = Rig(this)
            r.ordinaryDay()
            r.tune.script(itemOf(1), { TuneOutcome.Expired })
            val job = r.start()
            assertEquals(LiveState.SignedOut, r.state)
            assertTrue(r.seen.none { it is LiveState.TechnicalDifficulties })
            assertTrue(job.isCompleted)
        }

    @Test
    fun cancellingRunStopsTheTunerEvenWhileWatching() =
        runTest {
            val r = Rig(this)
            r.ordinaryDay()
            val job = r.start()
            r.at(10_000)
            assertIs<LiveState.Watching>(r.state)
            assertTrue(r.tune.calls.none { it is Call.Stop })

            job.cancel()
            r.at(10_001)
            assertIs<Call.Stop>(r.tune.calls.last())
        }

    @Test
    fun cancellingRunDiscardsAHandleItHoldsAndNeverClosesTheTuner() =
        runTest {
            val r = Rig(this)
            r.ordinaryDay()
            val job = r.start()
            r.at(50 * MIN - 10_000) // the next show is pre-resolved and waiting
            job.cancel()
            r.at(50 * MIN - 9_000)
            assertTrue(r.tune.calls.any { it is Call.Discard && it.item == itemOf(2) })
            assertTrue(r.tune.calls.none { it is Call.Close })
        }

    // ---- the banner ----

    @Test
    fun bannerMatchesOnAirAtTheMoment() =
        runTest {
            val r = Rig(this, startMin = 22)
            val episode = episodeInfo(5, series = "Test Series B", season = 2, episode = 5)
            val first = program(0, 60, 5, mediaStartMs = 4 * MIN, info = episode)
            r.schedule.channel(CH7, 7, listOf(first, program(60, 120, 2)))
            r.start()
            val tunedAtMs = r.nowMs()
            val expected = r.schedule.onAir(CH7, r.clock.now(), 1)!!
            r.at(500)
            val tuning = banner(r.seen.first { it is LiveState.Tuning })
            assertEquals(expected.channel.number, tuning.number)
            assertEquals(expected.channel.name, tuning.channelName)
            assertEquals(BannerLabel.TuningIn, tuning.label)
            assertEquals("Test Series B", tuning.title)
            assertEquals(episode, tuning.info)
            assertEquals(expected.now.startMs, tuning.slotStartMs)
            assertEquals(expected.now.endMs, tuning.slotEndMs)
            assertEquals(expected.now.positionAt(tunedAtMs)?.inWholeMilliseconds, tuning.joinedMs)
            assertEquals(26 * MIN, tuning.joinedMs)
            // The picture shows the same banner while it lingers.
            val watching = r.seen.first { it is LiveState.Watching } as LiveState.Watching
            assertEquals(tuning.copy(label = BannerLabel.Now), watching.banner)
        }

    @Test
    fun aMovieBannerHasATitleAndNoEpisodeInfo() =
        runTest {
            val r = Rig(this)
            r.ordinaryDay()
            r.start()
            val b = banner(r.seen.first { it is LiveState.Tuning })
            assertEquals("Test Movie 1", b.title)
            assertNull(b.info?.seriesName)
            assertEquals(10.minutes.inWholeMilliseconds, b.joinedMs)
        }
}
